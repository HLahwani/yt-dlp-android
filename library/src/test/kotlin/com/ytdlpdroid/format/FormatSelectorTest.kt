package com.ytdlpdroid.format

import com.ytdlpdroid.innertube.model.RawFormat
import com.ytdlpdroid.model.ExtractionOptions
import com.ytdlpdroid.model.YTDLPError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatSelectorTest {

    // ---- helpers ----

    private fun videoFmt(itag: Int, height: Int, mimeType: String, bitrate: Long = 1_000_000L, fps: Int = 30) =
        RawFormat(itag = itag, url = "https://example.com/$itag", mimeType = mimeType,
            bitrate = bitrate, width = height * 16 / 9, height = height, fps = fps,
            approxDurationMs = "10000")

    private fun audioFmt(itag: Int, mimeType: String, bitrate: Long = 128_000L) =
        RawFormat(itag = itag, url = "https://example.com/$itag", mimeType = mimeType,
            bitrate = bitrate, audioSampleRate = "48000", approxDurationMs = "10000")

    private fun muxedFmt(itag: Int, height: Int) =
        RawFormat(itag = itag, url = "https://example.com/$itag",
            mimeType = "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"",
            bitrate = 500_000L, width = height * 16 / 9, height = height,
            audioSampleRate = "44100", approxDurationMs = "10000")

    private fun resolve(vararg raws: RawFormat) =
        raws.map { it to (it.url ?: "") }

    // ---- tests ----

    @Test
    fun `AV1 preferred over H264 at same resolution`() {
        val formats = resolve(
            videoFmt(394, 1080, "video/mp4; codecs=\"av01.0.08M.08\""),
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            audioFmt(251, "audio/webm; codecs=\"opus\""),
        )
        val result = FormatSelector.select(formats, ExtractionOptions())
        assertEquals("av01.0.08M.08", result.video!!.codec)
    }

    @Test
    fun `preferH264 forces H264 selection`() {
        val formats = resolve(
            videoFmt(394, 1080, "video/mp4; codecs=\"av01.0.08M.08\""),
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            audioFmt(251, "audio/webm; codecs=\"opus\""),
        )
        val result = FormatSelector.select(formats, ExtractionOptions(preferH264 = true))
        assertEquals("avc1.640028", result.video!!.codec)
    }

    @Test
    fun `maxVideoHeight 720 excludes 1080p`() {
        val formats = resolve(
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            videoFmt(136, 720, "video/mp4; codecs=\"avc1.4d401f\""),
            audioFmt(251, "audio/webm; codecs=\"opus\""),
        )
        val result = FormatSelector.select(formats, ExtractionOptions(maxVideoHeight = 720))
        assertEquals(720, result.video!!.height)
    }

    @Test
    fun `opus preferred over AAC by default`() {
        val formats = resolve(
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            audioFmt(251, "audio/webm; codecs=\"opus\""),
            audioFmt(140, "audio/mp4; codecs=\"mp4a.40.2\""),
        )
        val result = FormatSelector.select(formats, ExtractionOptions())
        assertEquals("opus", result.audio.codec)
    }

    @Test
    fun `preferOpusAudio false selects AAC`() {
        val formats = resolve(
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            audioFmt(251, "audio/webm; codecs=\"opus\"", bitrate = 130_000L),
            audioFmt(140, "audio/mp4; codecs=\"mp4a.40.2\"", bitrate = 128_000L),
        )
        val result = FormatSelector.select(formats, ExtractionOptions(preferOpusAudio = false))
        assertEquals("mp4a.40.2", result.audio.codec)
    }

    @Test
    fun `no audio stream throws NoStreamsFound`() {
        val formats = resolve(
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
        )
        assertThrows(YTDLPError.NoStreamsFound::class.java) {
            FormatSelector.select(formats, ExtractionOptions())
        }
    }

    @Test
    fun `highest resolution selected when multiple available`() {
        val formats = resolve(
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            videoFmt(136, 720,  "video/mp4; codecs=\"avc1.4d401f\""),
            videoFmt(135, 480,  "video/mp4; codecs=\"avc1.4d401e\""),
            audioFmt(251, "audio/webm; codecs=\"opus\""),
        )
        val result = FormatSelector.select(formats, ExtractionOptions())
        assertEquals(1080, result.video!!.height)
    }

    @Test
    fun `muxed fallback included when requested`() {
        val formats = resolve(
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            audioFmt(251, "audio/webm; codecs=\"opus\""),
            muxedFmt(22, 720),
            muxedFmt(18, 360),
        )
        val result = FormatSelector.select(formats, ExtractionOptions(includeMuxedFallback = true))
        assertNotNull(result.muxed)
        assertEquals(720, result.muxed!!.height)
    }

    @Test
    fun `muxed fallback excluded when not requested`() {
        val formats = resolve(
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            audioFmt(251, "audio/webm; codecs=\"opus\""),
            muxedFmt(22, 720),
        )
        val result = FormatSelector.select(formats, ExtractionOptions(includeMuxedFallback = false))
        assertNull(result.muxed)
    }

    @Test
    fun `video can be null for audio-only content`() {
        val formats = resolve(audioFmt(251, "audio/webm; codecs=\"opus\""))
        val result = FormatSelector.select(formats, ExtractionOptions())
        assertNull(result.video)
        assertNotNull(result.audio)
    }

    @Test
    fun `higher bitrate audio wins when codecs are equal`() {
        val formats = resolve(
            videoFmt(137, 1080, "video/mp4; codecs=\"avc1.640028\""),
            audioFmt(251, "audio/webm; codecs=\"opus\"", bitrate = 160_000L),
            audioFmt(250, "audio/webm; codecs=\"opus\"", bitrate = 70_000L),
        )
        val result = FormatSelector.select(formats, ExtractionOptions())
        assertEquals(251, result.audio.itag)
    }

    @Test
    fun `toStreamFormat maps all fields correctly`() {
        val formats = resolve(
            RawFormat(
                itag = 248, url = "https://example.com/248",
                mimeType = "video/webm; codecs=\"vp9\"",
                bitrate = 1_800_000L, width = 1920, height = 1080, fps = 30,
                contentLength = "47000000", approxDurationMs = "211560",
                initRange = com.ytdlpdroid.innertube.model.RawRange("0", "219"),
                indexRange = com.ytdlpdroid.innertube.model.RawRange("220", "851"),
            ),
            audioFmt(251, "audio/webm; codecs=\"opus\""),
        )
        val result = FormatSelector.select(formats, ExtractionOptions())
        with(result.video!!) {
            assertEquals(248, itag)
            assertEquals(1920, width)
            assertEquals(1080, height)
            assertEquals(30, fps)
            assertEquals(47_000_000L, contentLength)
            assertEquals(0L..219L, initRange)
            assertEquals(220L..851L, indexRange)
            assertTrue(url.isNotEmpty())
        }
    }
}
