package com.ytdlpdroid.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTypeParserTest {

    @Test fun `isVideo returns true for video mime`() = assertTrue(MimeTypeParser.isVideo("video/mp4; codecs=\"avc1.64001f\""))
    @Test fun `isAudio returns true for audio mime`() = assertTrue(MimeTypeParser.isAudio("audio/webm; codecs=\"opus\""))
    @Test fun `isVideo returns false for audio`() = assertFalse(MimeTypeParser.isVideo("audio/mp4; codecs=\"mp4a.40.2\""))

    @Test fun `extractCodecString parses quoted codecs`() =
        assertEquals("avc1.64001f", MimeTypeParser.extractCodecString("video/mp4; codecs=\"avc1.64001f\""))

    @Test fun `extractCodecString returns empty when absent`() =
        assertEquals("", MimeTypeParser.extractCodecString("video/mp4"))

    @Test fun `H264 detection`() =
        assertEquals(VideoCodec.H264, MimeTypeParser.detectVideoCodec("video/mp4; codecs=\"avc1.64001f\""))

    @Test fun `H264 avc3 variant`() =
        assertEquals(VideoCodec.H264, MimeTypeParser.detectVideoCodec("video/mp4; codecs=\"avc3.640028\""))

    @Test fun `VP9 detection`() =
        assertEquals(VideoCodec.VP9, MimeTypeParser.detectVideoCodec("video/webm; codecs=\"vp9\""))

    @Test fun `AV1 detection av01 prefix`() =
        assertEquals(VideoCodec.AV1, MimeTypeParser.detectVideoCodec("video/mp4; codecs=\"av01.0.08M.08\""))

    @Test fun `AV1 priority over H264`() {
        assertTrue(VideoCodec.AV1.priority > VideoCodec.H264.priority)
    }

    @Test fun `OPUS detection`() =
        assertEquals(AudioCodec.OPUS, MimeTypeParser.detectAudioCodec("audio/webm; codecs=\"opus\""))

    @Test fun `AAC detection mp4a`() =
        assertEquals(AudioCodec.AAC, MimeTypeParser.detectAudioCodec("audio/mp4; codecs=\"mp4a.40.2\""))

    @Test fun `VORBIS detection`() =
        assertEquals(AudioCodec.VORBIS, MimeTypeParser.detectAudioCodec("audio/webm; codecs=\"vorbis\""))

    @Test fun `OPUS priority over AAC`() {
        assertTrue(AudioCodec.OPUS.priority > AudioCodec.AAC.priority)
    }

    @Test fun `H265 hvc1 detection`() =
        assertEquals(VideoCodec.H265, MimeTypeParser.detectVideoCodec("video/mp4; codecs=\"hvc1.1.6.L93.B0\""))

    @Test fun `H265 hev1 variant`() =
        assertEquals(VideoCodec.H265, MimeTypeParser.detectVideoCodec("video/mp4; codecs=\"hev1.1.6.L93.B0\""))

    @Test fun `VP9_HDR detection`() =
        assertEquals(VideoCodec.VP9_HDR, MimeTypeParser.detectVideoCodec("video/webm; codecs=\"vp9.2\""))

    @Test fun `VP8 detection`() =
        assertEquals(VideoCodec.VP8, MimeTypeParser.detectVideoCodec("video/webm; codecs=\"vp8\""))

    @Test fun `AC3 detection`() =
        assertEquals(AudioCodec.AC3, MimeTypeParser.detectAudioCodec("audio/mp4; codecs=\"ac-3\""))

    @Test fun `MP3 detection`() =
        assertEquals(AudioCodec.MP3, MimeTypeParser.detectAudioCodec("audio/mpeg; codecs=\"mp3\""))

    @Test fun `unknown video codec`() =
        assertEquals(VideoCodec.UNKNOWN, MimeTypeParser.detectVideoCodec("video/mp4; codecs=\"hvt1\""))

    @Test fun `unknown audio codec`() =
        assertEquals(AudioCodec.UNKNOWN, MimeTypeParser.detectAudioCodec("audio/mp4; codecs=\"unknown\""))
}
