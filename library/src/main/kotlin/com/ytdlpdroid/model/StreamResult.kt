package com.ytdlpdroid.model

data class StreamResult(
    val videoId: String,
    val metadata: VideoInfo,
    val videoStream: StreamFormat?,
    val audioStream: StreamFormat,
    val muxedStream: StreamFormat?,
    val expiresAt: Long,
    /**
     * User-Agent used for the InnerTube client that produced these stream URLs.
     * The CDN validates that stream requests arrive with the same User-Agent that
     * generated the URL — pass this to the OkHttpDataSource (or any HTTP client)
     * used to play the streams.
     */
    val streamUserAgent: String,
)
