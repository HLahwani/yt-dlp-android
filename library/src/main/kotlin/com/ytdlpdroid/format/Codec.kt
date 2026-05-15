package com.ytdlpdroid.format

internal enum class VideoCodec(val priority: Int) {
    AV1(50), VP9_HDR(45), VP9(40), H265(35), H264(30), VP8(10), UNKNOWN(0)
}

internal enum class AudioCodec(val priority: Int) {
    OPUS(50), VORBIS(40), AAC(30), AC3(20), MP3(10), UNKNOWN(0)
}
