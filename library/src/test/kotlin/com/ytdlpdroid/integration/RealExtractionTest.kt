package com.ytdlpdroid.integration

import com.ytdlpdroid.YTDLPDroid
import com.ytdlpdroid.model.YTDLPError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * End-to-end tests that hit real YouTube infrastructure.
 * Annotated @Ignore so they are skipped in CI. Run manually with:
 *   ./gradlew :library:testDebugUnitTest --tests "*.RealExtractionTest"
 * after removing the @Ignore annotation temporarily.
 */
@Ignore("Requires internet — run manually")
class RealExtractionTest {

    private val ytdlp = YTDLPDroid.Builder(
        File(System.getProperty("java.io.tmpdir"), "ytdlpdroid_test")
    ).build()

    private val http = OkHttpClient()

    private fun headRequest(url: String) =
        http.newCall(Request.Builder().url(url).head().build()).execute()

    @Test
    fun `standard video returns valid playable stream URLs`() = runBlocking {
        val result = ytdlp.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw")

        assertTrue("audioStream url must not be empty", result.audioStream.url.isNotEmpty())
        assertFalse("audioStream url must start with https",
            result.audioStream.url.startsWith("http://"))

        val audioResp = headRequest(result.audioStream.url)
        assertEquals("Audio stream URL must respond 200", 200, audioResp.code)
        audioResp.close()
    }

    @Test
    fun `video from playlist URL extracts single video only`() = runBlocking {
        val result = ytdlp.extract(
            "https://www.youtube.com/watch?v=jNQXAC9IVRw&list=PL_AX3LCBDlKiG0xjEHymVwxfYaJFp0c5j&index=1"
        )
        assertEquals("jNQXAC9IVRw", result.videoId)
    }

    @Test
    fun `short youtu_be URL is resolved correctly`() = runBlocking {
        val result = ytdlp.extract("https://youtu.be/jNQXAC9IVRw")
        assertEquals("jNQXAC9IVRw", result.videoId)
        assertTrue(result.audioStream.url.isNotEmpty())
    }

    @Test
    fun `unavailable video ID throws VideoUnavailable`() = runBlocking {
        try {
            ytdlp.extract("https://www.youtube.com/watch?v=xxxxxxxxxxx")
            throw AssertionError("Expected YTDLPError.VideoUnavailable to be thrown")
        } catch (e: YTDLPError.VideoUnavailable) {
            // expected
        } catch (e: YTDLPError.AllClientsFailed) {
            // also acceptable — all clients may classify it differently
        }
    }

    @Test
    fun `metadata fields are populated`() = runBlocking {
        val result = ytdlp.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        with(result.metadata) {
            assertTrue(title.isNotEmpty())
            assertTrue(author.isNotEmpty())
            assertTrue(durationSeconds > 0)
            assertTrue(thumbnailUrl.isNotEmpty())
        }
    }
}
