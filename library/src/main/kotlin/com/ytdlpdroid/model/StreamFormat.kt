package com.ytdlpdroid.model

data class StreamFormat(
    val itag: Int,
    val url: String,
    val mimeType: String,
    val codec: String,
    val bitrate: Long,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val audioSampleRate: Int?,
    val contentLength: Long?,
    val initRange: LongRange?,
    val indexRange: LongRange?,
    val approximateDurationMs: Long,
)
