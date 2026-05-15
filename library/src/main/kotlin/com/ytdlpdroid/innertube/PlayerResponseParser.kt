package com.ytdlpdroid.innertube

import com.ytdlpdroid.innertube.model.RawPlayerResponse
import com.ytdlpdroid.model.VideoInfo
import com.ytdlpdroid.model.YTDLPError

internal object PlayerResponseParser {

    fun checkPlayability(raw: RawPlayerResponse, videoId: String) {
        when (raw.playabilityStatus.status) {
            "OK" -> Unit
            "LOGIN_REQUIRED" -> throw YTDLPError.AgeRestricted(videoId)
            "UNPLAYABLE" -> {
                val reason = raw.playabilityStatus.reason
                if (isGeoRestricted(reason)) throw YTDLPError.GeoBlocked(videoId, reason)
                throw YTDLPError.VideoUnavailable(videoId, reason)
            }
            "ERROR" -> {
                val reason = raw.playabilityStatus.reason
                if (isGeoRestricted(reason)) throw YTDLPError.GeoBlocked(videoId, reason)
                throw YTDLPError.VideoUnavailable(videoId, reason)
            }
            "LIVE_STREAM_OFFLINE" -> throw YTDLPError.LiveStreamNotSupported(videoId)
            else -> throw YTDLPError.VideoUnavailable(videoId, "status: ${raw.playabilityStatus.status}")
        }
    }

    fun extractVideoInfo(raw: RawPlayerResponse): VideoInfo {
        val details = raw.videoDetails ?: error("No videoDetails in response")
        val bestThumb = details.thumbnail?.thumbnails
            ?.maxByOrNull { it.width * it.height }?.url ?: ""
        return VideoInfo(
            videoId = details.videoId,
            title = details.title,
            author = details.author ?: "",
            channelId = details.channelId ?: "",
            durationSeconds = details.lengthSeconds?.toLongOrNull() ?: 0L,
            isLive = details.isLiveContent,
            thumbnailUrl = bestThumb,
            keywords = details.keywords,
        )
    }

    fun isLive(raw: RawPlayerResponse): Boolean = raw.videoDetails?.isLiveContent == true

    /** Returns true when the playability reason indicates a geo/region restriction. */
    private fun isGeoRestricted(reason: String?): Boolean {
        if (reason == null) return false
        val r = reason.lowercase()
        return r.contains("country") ||
               r.contains("region") ||
               r.contains("location") ||
               r.contains("not available in your") ||
               r.contains("not made this video available")
    }
}
