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
    private val clientChain = listOf(
        InnerTubeClientConfig.ANDROID,
        InnerTubeClientConfig.ANDROID_VR,          // best geo-bypass; loosest regional rules
        InnerTubeClientConfig.ANDROID_TESTSUITE,
        InnerTubeClientConfig.TVHTML5_SIMPLY_EMBEDDED,
        InnerTubeClientConfig.IOS,
        InnerTubeClientConfig.MWEB,
        InnerTubeClientConfig.WEB_EMBEDDED,
        InnerTubeClientConfig.WEB,
    )

    suspend fun extract(videoId: String, options: ExtractionOptions): StreamResult {
        extractionCache.get(videoId)?.let { return it }

        val (playerResponse, winningClient) = fetchWithFallback(videoId, options.regionCode)

        if (PlayerResponseParser.isLive(playerResponse))
            throw YTDLPError.LiveStreamNotSupported(videoId)

        val videoInfo = PlayerResponseParser.extractVideoInfo(playerResponse)
        val streamingData = playerResponse.streamingData
            ?: throw YTDLPError.NoStreamsFound(videoId)

        val playerJsUrl = playerJsRepo.fetchPlayerJsUrl(videoId)

        val allFormats = streamingData.adaptiveFormats + streamingData.formats
        val resolvedFormats = coroutineScope {
            allFormats.map { fmt ->
                async(Dispatchers.IO) {
                    runCatching { fmt to decipherService.buildPlayableUrl(fmt, playerJsUrl) }
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
    ): Pair<RawPlayerResponse, InnerTubeClientConfig> {
        var lastError: Throwable? = null
        for (client in clientChain) {
            try {
                val response = innerTubeClient.fetchPlayerResponse(videoId, client, regionCode)
                PlayerResponseParser.checkPlayability(response, videoId)
                return response to client
            } catch (e: YTDLPError.LiveStreamNotSupported) {
                throw e  // no point trying other clients for an offline live stream
            } catch (e: YTDLPError.GeoBlocked) {
                // Geo-block on one client doesn't rule out others (ANDROID_VR bypasses more).
                lastError = e; continue
            } catch (e: YTDLPError) {
                lastError = e; continue
            }
        }
        // If every client returned a geo-block, surface that specific error so callers
        // can distinguish geo-restriction from other failures.
        throw if (lastError is YTDLPError.GeoBlocked) lastError
              else YTDLPError.AllClientsFailed(videoId)
    }
}
