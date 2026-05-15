package com.ytdlpdroid.integration

import com.ytdlpdroid.YTDLPDroid
import com.ytdlpdroid.model.StreamResult
import com.ytdlpdroid.model.YTDLPError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live extraction tests against real YouTube URLs.
 * Require real network access and a residential/carrier IP.
 * Run manually: remove @Ignore and run with a real device or residential VPN.
 */
@Ignore("Requires real network + residential IP — run manually")
class UrlExtractionTest {

    private val cacheDir = File(System.getProperty("java.io.tmpdir"), "ytdlpdroid_urltest")
        .also { it.mkdirs() }

    private val ytdlp = YTDLPDroid.Builder(cacheDir)
        .jsEngine(RhinoJsEngine())
        .build()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // ---- helpers ----

    private fun head(url: String): Int =
        http.newCall(Request.Builder().url(url).head().build()).execute().use { it.code }

    private fun printResult(label: String, result: StreamResult) {
        println()
        println("  title    : ${result.metadata.title}")
        println("  author   : ${result.metadata.author}")
        println("  duration : ${result.metadata.durationSeconds}s")
        result.videoStream?.let {
            println("  video    : itag=${it.itag}  ${it.height}p ${it.fps ?: 0}fps  codec=${it.codec}")
        } ?: println("  video    : (none — audio-only)")
        println("  audio    : itag=${result.audioStream.itag}  codec=${result.audioStream.codec}  ${result.audioStream.bitrate / 1000}kbps")
        println("  expires  : ${(result.expiresAt - System.currentTimeMillis()) / 60_000}m from now")
    }

    /**
     * Extracts [url], prints diagnostics, verifies stream URLs return HTTP 2xx,
     * and returns the result.
     *
     * If [allowRestricted] is true a geo/age restriction error is treated as a
     * pass (the SDK behaved correctly — it just cannot bypass the restriction in
     * the test region).
     */
    private fun extract(label: String, url: String, allowRestricted: Boolean = false): StreamResult? {
        println("\n━━━ $label ━━━")
        println("  url: $url")

        return try {
            val result = runBlocking {
                withTimeout(90_000L) { ytdlp.extract(url) }
            }

            printResult(label, result)

            val audioStatus = head(result.audioStream.url)
            println("  audio-url HEAD → $audioStatus")
            assertTrue("Audio URL should return 2xx (got $audioStatus)", audioStatus in 200..299)

            result.videoStream?.let {
                val videoStatus = head(it.url)
                println("  video-url HEAD → $videoStatus")
                assertTrue("Video URL should return 2xx (got $videoStatus)", videoStatus in 200..299)
            }

            println("  ✓ PASS")
            result
        } catch (e: YTDLPError) {
            val restricted = e is YTDLPError.VideoUnavailable
                    || e is YTDLPError.AllClientsFailed
                    || e is YTDLPError.AgeRestricted

            if (allowRestricted && restricted) {
                println("  ~ RESTRICTED (acceptable): ${e::class.simpleName} — ${e.message}")
                null
            } else {
                println("  ✗ FAIL: ${e::class.simpleName} — ${e.message}")
                throw e
            }
        }
    }

    // ---- tests ----

    @Test
    fun `1 valid standard watch URL`() {
        val result = extract(
            "[1] valid",
            "https://www.youtube.com/watch?v=93QIhAbxmdc",
        )
        checkNotNull(result) { "Expected a valid result for URL [1]" }
        assertTrue("videoId should match", result.videoId == "93QIhAbxmdc")
        assertTrue("title must not be empty", result.metadata.title.isNotEmpty())
    }

    @Test
    fun `2 valid embed URL with extra query params`() {
        val result = extract(
            "[2] embed",
            "https://www.youtube.com/embed/27OZc-ku6is?enablejsapi=1&wmode=opaque&autoplay=1",
        )
        checkNotNull(result) { "Expected a valid result for URL [2]" }
        assertTrue("videoId should match", result.videoId == "27OZc-ku6is")
    }

    @Test
    fun `3 country-restricted video`() {
        // This video may be geo-blocked depending on the test machine's region.
        // We accept either a successful extraction OR a documented restriction error.
        val result = extract(
            "[3] country-restricted",
            "https://www.youtube.com/watch?v=l_98K4_6UQ0",
            allowRestricted = true,
        )
        if (result != null) {
            assertTrue("videoId should match", result.videoId == "l_98K4_6UQ0")
            println("  (video accessible from this region — streams verified)")
        }
    }
}
