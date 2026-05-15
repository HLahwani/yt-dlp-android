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
    private val clientChain = listOf(
        InnerTubeClientConfig.ANDROID,
        InnerTubeClientConfig.ANDROID_TESTSUITE,
        InnerTubeClientConfig.TVHTML5_SIMPLY_EMBEDDED,
        InnerTubeClientConfig.ANDROID_VR,
        InnerTubeClientConfig.IOS,
        InnerTubeClientConfig.MWEB,
        InnerTubeClientConfig.WEB_EMBEDDED,
        InnerTubeClientConfig.WEB,
    )

    suspend fun extract(videoId: String, options: ExtractionOptions): StreamResult {
        extractionCache.get(videoId)?.let { return it }

        val (playerResponse, winningClient) = fetchWithFallback(videoId)

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

    private suspend fun fetchWithFallback(videoId: String): Pair<RawPlayerResponse, InnerTubeClientConfig> {
        var lastError: Throwable? = null
        for (client in clientChain) {
            try {
                val response = innerTubeClient.fetchPlayerResponse(videoId, client)
                PlayerResponseParser.checkPlayability(response, videoId)
                return response to client
            } catch (e: YTDLPError.LiveStreamNotSupported) {
                throw e  // no point trying other clients for an offline live stream
            } catch (e: YTDLPError) {
                // VideoUnavailable from one client (e.g. ANDROID requiring PO token) does not
                // mean the video is unavailable from all clients — always exhaust the chain.
                lastError = e; continue
            }
        }
        throw YTDLPError.AllClientsFailed(videoId)
    }
}
