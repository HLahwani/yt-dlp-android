package com.ytdlpdroid.extractor

import com.ytdlpdroid.cache.ExtractionCache
import com.ytdlpdroid.decipher.DecipherService
import com.ytdlpdroid.decipher.PlayerJsRepository
import com.ytdlpdroid.format.FormatSelector
import com.ytdlpdroid.innertube.InnerTubeClient
import com.ytdlpdroid.innertube.InnerTubeClientConfig
import com.ytdlpdroid.innertube.PlayerResponseParser
import com.ytdlpdroid.innertube.model.RawPlayerResponse
import com.ytdlpdroid.model.ExtractionOptions
import com.ytdlpdroid.model.StreamResult
import com.ytdlpdroid.model.YTDLPError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal class YouTubeExtractor(
    private val innerTubeClient: InnerTubeClient,
    private val playerJsRepo: PlayerJsRepository,
    private val decipherService: DecipherService,
    private val extractionCache: ExtractionCache,
) {
    // ANDROID_VR is placed early because it has the best geo-restriction bypass rate.
    // The full chain exhausts all clients before giving up.
    // ANDROID_VR first — yt-dlp's default client. No PO token required, returns direct URLs.
    // ANDROID and IOS require PO tokens for CDN access; they may return UNPLAYABLE without them.
    private val clientChain = listOf(
        InnerTubeClientConfig.ANDROID_VR,
        InnerTubeClientConfig.ANDROID,
        InnerTubeClientConfig.ANDROID_TESTSUITE,
        InnerTubeClientConfig.TVHTML5_SIMPLY_EMBEDDED,
        InnerTubeClientConfig.IOS,
        InnerTubeClientConfig.MWEB,
        InnerTubeClientConfig.WEB_EMBEDDED,
        InnerTubeClientConfig.WEB,
    )

    suspend fun extract(videoId: String, options: ExtractionOptions): StreamResult {
        extractionCache.get(videoId)?.let { return it }

        // Pre-fetch the player JS so we can include signatureTimestamp (sts) in InnerTube
        // requests. sts is required by YouTube for age-restricted videos to return OK rather
        // than UNPLAYABLE. Failures here are non-fatal — we proceed without sts.
        val playerJsUrl = runCatching { playerJsRepo.fetchPlayerJsUrl(videoId) }.getOrNull()
        val signatureTimestamp = playerJsUrl?.let { url ->
            runCatching {
                val js = playerJsRepo.fetchPlayerJs(url)
                playerJsRepo.extractSignatureTimestamp(js)
            }.getOrNull()
        }

        val (playerResponse, winningClient) = fetchWithFallback(videoId, options.regionCode, signatureTimestamp)

        if (PlayerResponseParser.isLive(playerResponse))
            throw YTDLPError.LiveStreamNotSupported(videoId)

        val videoInfo = PlayerResponseParser.extractVideoInfo(playerResponse)
        val streamingData = playerResponse.streamingData
            ?: throw YTDLPError.NoStreamsFound(videoId)

        // Reuse the already-fetched player JS URL; fall back to a fresh fetch only if the
        // pre-fetch failed (transient error, bot-detected watch page, etc.).
        val resolvedPlayerJsUrl = playerJsUrl ?: playerJsRepo.fetchPlayerJsUrl(videoId)

        val allFormats = streamingData.adaptiveFormats + streamingData.formats
        val resolvedFormats = coroutineScope {
            allFormats.map { fmt ->
                async(Dispatchers.IO) {
                    runCatching { fmt to decipherService.buildPlayableUrl(fmt, resolvedPlayerJsUrl) }
                        .getOrNull()
                }
            }.awaitAll().filterNotNull()
        }

        if (resolvedFormats.isEmpty()) throw YTDLPError.NoStreamsFound(videoId)

        val (videoStream, audioStream, muxedStream) = FormatSelector.select(resolvedFormats, options)

        val expiresInSeconds = streamingData.expiresInSeconds?.toLongOrNull() ?: 21600L
        val result = StreamResult(
            videoId = videoId,
            metadata = videoInfo,
            videoStream = videoStream,
            audioStream = audioStream,
            muxedStream = muxedStream,
            expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L,
            streamUserAgent = winningClient.userAgent,
        )

        extractionCache.put(videoId, result)
        return result
    }

    private suspend fun fetchWithFallback(
        videoId: String,
        regionCode: String?,
        signatureTimestamp: Int? = null,
    ): Pair<RawPlayerResponse, InnerTubeClientConfig> {
        var lastError: Throwable? = null
        var geoBlockedError: YTDLPError.GeoBlocked? = null
        for (client in clientChain) {
            try {
                val response = innerTubeClient.fetchPlayerResponse(videoId, client, regionCode, signatureTimestamp)
                PlayerResponseParser.checkPlayability(response, videoId)
                return response to client
            } catch (e: YTDLPError.LiveStreamNotSupported) {
                throw e  // no point trying other clients for an offline live stream
            } catch (e: YTDLPError.GeoBlocked) {
                // Remember the geo-block even if later clients fail for other reasons
                // (e.g. bot-detection prevents the bypass from working).
                geoBlockedError = e; lastError = e; continue
            } catch (e: YTDLPError) {
                lastError = e; continue
            }
        }
        // Surface the most specific terminal error so callers can react appropriately:
        //
        //  GeoBlocked     — any client indicated geo-restriction (bypass clients couldn't help)
        //  VideoUnavailable — WEB (last, most capable client) still says "unavailable":
        //                     the video is deleted, private, or genuinely inaccessible;
        //                     not a transient bot-detection issue worth retrying
        //  AllClientsFailed — mixed failures (bot detection, network, etc.)
        throw geoBlockedError
            ?: if (lastError is YTDLPError.VideoUnavailable) lastError
               else YTDLPError.AllClientsFailed(videoId, lastError)
    }
}
