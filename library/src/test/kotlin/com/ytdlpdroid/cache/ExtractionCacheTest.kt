package com.ytdlpdroid.cache

import com.ytdlpdroid.model.ExtractionOptions
import com.ytdlpdroid.model.StreamFormat
import com.ytdlpdroid.model.StreamResult
import com.ytdlpdroid.model.VideoInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractionCacheTest {

    private fun audio(url: String) = StreamFormat(
        itag = 251, url = url, mimeType = "audio/webm; codecs=\"opus\"", codec = "opus",
        bitrate = 130_000L, width = null, height = null, fps = null,
        audioSampleRate = 48000, contentLength = null, initRange = null, indexRange = null,
        approximateDurationMs = 10_000L,
    )

    private fun result(expiresInMs: Long) = StreamResult(
        videoId = "vid",
        metadata = VideoInfo("vid", "T", "", "", 10L, false, "", emptyList()),
        videoStream = null,
        audioStream = audio("https://example.com/a"),
        muxedStream = null,
        expiresAt = System.currentTimeMillis() + expiresInMs,
        streamUserAgent = "test-ua",
    )

    @Test
    fun `put and get returns result when expiry is in the future`() {
        val cache = ExtractionCache()
        // Expires 10 minutes from now — well beyond the 5-min safety margin
        cache.put("vid", result(expiresInMs = 10 * 60_000L))
        assertNotNull(cache.get("vid"))
    }

    @Test
    fun `result inside safety margin is not cached`() {
        val cache = ExtractionCache()
        // Only 4 minutes until expiry — stripped by the 5-min safety margin → ttl < 0
        cache.put("vid", result(expiresInMs = 4 * 60_000L))
        assertNull(cache.get("vid"))
    }

    @Test
    fun `get returns null for unknown video`() {
        assertNull(ExtractionCache().get("unknown"))
    }

    @Test
    fun `capacity limits are respected`() {
        val cache = ExtractionCache(capacity = 2)
        val longExpiry = 10 * 60_000L
        cache.put("a", result(longExpiry))
        cache.put("b", result(longExpiry))
        cache.put("c", result(longExpiry)) // evicts "a" (LRU)
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }
}
