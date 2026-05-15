package com.ytdlpdroid.model

sealed class YTDLPError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class InvalidUrl(input: String) :
        YTDLPError("Not a recognisable YouTube URL or video ID: \"$input\"")

    class VideoUnavailable(videoId: String, reason: String?) :
        YTDLPError("Video $videoId is unavailable${reason?.let { ": $it" } ?: ""}")

    class GeoBlocked(videoId: String, reason: String?) :
        YTDLPError("Video $videoId is not available in this region${reason?.let { ": $it" } ?: ""}")

    class AgeRestricted(videoId: String) :
        YTDLPError("Video $videoId requires age verification — all clients failed to bypass")

    class LiveStreamNotSupported(videoId: String) :
        YTDLPError("Video $videoId is a live stream; live streams are not supported")

    class NoStreamsFound(videoId: String) :
        YTDLPError("No playable streams found for video $videoId")

    class DecipherFailed(reason: String) :
        YTDLPError("Stream URL decryption failed: $reason")

    class NetworkError(reason: String, cause: Throwable) :
        YTDLPError("Network error: $reason", cause)

    class AllClientsFailed(videoId: String, lastError: Throwable? = null) :
        YTDLPError(
            "All InnerTube clients failed to retrieve streams for video $videoId" +
            (lastError?.message?.let { " — $it" } ?: ""),
            lastError,
        )

    class PlayerJsFetchFailed(jsUrl: String, cause: Throwable) :
        YTDLPError("Failed to fetch player JS from $jsUrl", cause)
}
