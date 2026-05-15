package com.ytdlpdroid.extractor

import com.ytdlpdroid.cache.ExtractionCache
import com.ytdlpdroid.decipher.DecipherService
import com.ytdlpdroid.decipher.PlayerJsRepository
import com.ytdlpdroid.innertube.InnerTubeClient
import com.ytdlpdroid.innertube.InnerTubeClientConfig
import com.ytdlpdroid.innertube.model.RawFormat
import com.ytdlpdroid.innertube.model.RawPlayabilityStatus
import com.ytdlpdroid.innertube.model.RawPlayerResponse
import com.ytdlpdroid.innertube.model.RawStreamingData
import com.ytdlpdroid.innertube.model.RawVideoDetails
import com.ytdlpdroid.model.ExtractionOptions
import com.ytdlpdroid.model.StreamFormat
import com.ytdlpdroid.model.StreamResult
import com.ytdlpdroid.model.VideoInfo
import com.ytdlpdroid.model.YTDLPError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YouTubeExtractorTest {

    private val okAudioFormat = RawFormat(
        itag = 251, url = "https://example.com/audio?n=abc",
        mimeType = "audio/webm; codecs=\"opus\"", bitrate = 130_000L,
        audioSampleRate = "48000", approxDurationMs = "10000",
    )
    private val okDetails = RawVideoDetails(videoId = "testVid", title = "Test", lengthSeconds = "120")

    private fun okResponse(formats: List<RawFormat> = listOf(okAudioFormat)) = RawPlayerResponse(
        playabilityStatus = RawPlayabilityStatus("OK"),
        streamingData = RawStreamingData(expiresInSeconds = "21600", adaptiveFormats = formats),
        videoDetails = okDetails,
    )

    private fun ageGatedResponse() = RawPlayerResponse(RawPlayabilityStatus("LOGIN_REQUIRED"))

    private fun makeDecipher() = mockk<DecipherService> {
        coEvery { buildPlayableUrl(any(), any()) } answers { firstArg<RawFormat>().url!! }
    }

    private fun makeRepo() = mockk<PlayerJsRepository> {
        coEvery { fetchPlayerJsUrl(any()) } returns "/s/player/abc/base.js"
        coEvery { fetchPlayerJs(any()) } returns "var x=1;"
        every { extractSignatureTimestamp(any()) } returns null
    }

    /** Cache that always misses; put() is a no-op. */
    private fun emptyCache() = mockk<ExtractionCache>(relaxed = true).also {
        every { it.get(any()) } returns null
    }

    private fun primeCache(result: StreamResult) = mockk<ExtractionCache>(relaxed = true).also {
        every { it.get(any()) } returns result
    }

    private fun cachedResult() = StreamResult(
        videoId = "testVid",
        metadata = VideoInfo("testVid", "Test", "", "", 120L, false, "", emptyList()),
        videoStream = null,
        audioStream = StreamFormat(251, "https://example.com/audio", "audio/webm; codecs=\"opus\"",
            "opus", 130_000L, null, null, null, 48000, null, null, null, 10000L),
        muxedStream = null,
        expiresAt = System.currentTimeMillis() + 3_600_000L,
        streamUserAgent = "test-ua",
    )

    // ---- tests ----

    @Test
    fun `cache hit returns without any network calls`() = runTest {
        val client = mockk<InnerTubeClient>()
        val extractor = YouTubeExtractor(client, makeRepo(), makeDecipher(), primeCache(cachedResult()))
        extractor.extract("testVid", ExtractionOptions())
        coVerify(exactly = 0) { client.fetchPlayerResponse(any(), any(), any(), any()) }
    }

    @Test
    fun `successful ANDROID_VR response returns StreamResult`() = runTest {
        val client = mockk<InnerTubeClient> {
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID_VR, any(), any()) } returns okResponse()
        }
        val result = YouTubeExtractor(client, makeRepo(), makeDecipher(), emptyCache())
            .extract("testVid", ExtractionOptions())
        assertEquals("testVid", result.videoId)
        assertEquals("Test", result.metadata.title)
    }

    @Test
    fun `age-gated ANDROID_VR falls through to ANDROID`() = runTest {
        // Chain order: ANDROID_VR (first, yt-dlp default) → ANDROID → ...
        val client = mockk<InnerTubeClient> {
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID_VR, any(), any()) } returns ageGatedResponse()
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID, any(), any()) } returns okResponse()
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID_TESTSUITE, any(), any()) } returns ageGatedResponse()
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.TVHTML5_SIMPLY_EMBEDDED, any(), any()) } returns ageGatedResponse()
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.IOS, any(), any()) } returns ageGatedResponse()
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.MWEB, any(), any()) } returns ageGatedResponse()
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.WEB_EMBEDDED, any(), any()) } returns ageGatedResponse()
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.WEB, any(), any()) } returns ageGatedResponse()
        }
        val result = YouTubeExtractor(client, makeRepo(), makeDecipher(), emptyCache())
            .extract("testVid", ExtractionOptions())
        assertEquals("testVid", result.videoId)
        coVerify { client.fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID, any(), any()) }
    }

    @Test
    fun `all clients age-gated throws AllClientsFailed`() {
        val client = mockk<InnerTubeClient> {
            coEvery { fetchPlayerResponse(any(), any(), any(), any()) } returns ageGatedResponse()
        }
        assertThrows(YTDLPError.AllClientsFailed::class.java) {
            runBlocking {
                YouTubeExtractor(client, makeRepo(), makeDecipher(), emptyCache())
                    .extract("testVid", ExtractionOptions())
            }
        }
    }

    @Test
    fun `live stream throws LiveStreamNotSupported`() {
        val liveResponse = okResponse().copy(videoDetails = okDetails.copy(isLiveContent = true))
        val client = mockk<InnerTubeClient> {
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID_VR, any(), any()) } returns liveResponse
        }
        assertThrows(YTDLPError.LiveStreamNotSupported::class.java) {
            runBlocking {
                YouTubeExtractor(client, makeRepo(), makeDecipher(), emptyCache())
                    .extract("testVid", ExtractionOptions())
            }
        }
    }

    @Test
    fun `result is stored in cache after successful extraction`() = runTest {
        val client = mockk<InnerTubeClient> {
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID_VR, any(), any()) } returns okResponse()
        }
        val cache = emptyCache()
        YouTubeExtractor(client, makeRepo(), makeDecipher(), cache)
            .extract("testVid", ExtractionOptions())
        coVerify { cache.put("testVid", any()) }
    }

    @Test
    fun `geo-blocked video throws GeoBlocked`() {
        val geoResponse = RawPlayerResponse(
            RawPlayabilityStatus("UNPLAYABLE", "This video is not available in your country"))
        val client = mockk<InnerTubeClient> {
            coEvery { fetchPlayerResponse(any(), any(), any(), any()) } returns geoResponse
        }
        assertThrows(YTDLPError.GeoBlocked::class.java) {
            runBlocking {
                YouTubeExtractor(client, makeRepo(), makeDecipher(), emptyCache())
                    .extract("testVid", ExtractionOptions())
            }
        }
    }

    @Test
    fun `regionCode is threaded to InnerTube requests`() = runTest {
        val client = mockk<InnerTubeClient> {
            coEvery { fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID_VR, "DE", any()) } returns okResponse()
        }
        YouTubeExtractor(client, makeRepo(), makeDecipher(), emptyCache())
            .extract("testVid", ExtractionOptions(regionCode = "DE"))
        coVerify { client.fetchPlayerResponse(any(), InnerTubeClientConfig.ANDROID_VR, "DE", any()) }
    }
}
