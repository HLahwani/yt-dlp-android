package com.ytdlpdroid.format

internal object MimeTypeParser {

    fun isVideo(mimeType: String) = mimeType.startsWith("video/")
    fun isAudio(mimeType: String) = mimeType.startsWith("audio/")

    fun extractCodecString(mimeType: String): String =
        Regex("""codecs="([^"]+)"""").find(mimeType)?.groupValues?.get(1)?.trim() ?: ""

    fun detectVideoCodec(mimeType: String): VideoCodec {
        val c = extractCodecString(mimeType).lowercase()
        return when {
            c.startsWith("av01") || c.startsWith("av1") -> VideoCodec.AV1
            c.contains("vp9") && (c.contains("hdr") || c.contains("vp9.2")) -> VideoCodec.VP9_HDR
            c.contains("vp9") -> VideoCodec.VP9
            c.contains("hvc1") || c.contains("hev1") -> VideoCodec.H265
            c.contains("avc1") || c.contains("avc3") -> VideoCodec.H264
            c.contains("vp8") -> VideoCodec.VP8
            else -> VideoCodec.UNKNOWN
        }
    }

    fun detectAudioCodec(mimeType: String): AudioCodec {
        val c = extractCodecString(mimeType).lowercase()
        return when {
            c.contains("opus") -> AudioCodec.OPUS
            c.contains("vorbis") -> AudioCodec.VORBIS
            c.contains("mp4a") || c.contains("aac") -> AudioCodec.AAC
            c.contains("ac-3") || c.contains("ac3") -> AudioCodec.AC3
            c.contains("mp3") -> AudioCodec.MP3
            else -> AudioCodec.UNKNOWN
        }
    }
}
