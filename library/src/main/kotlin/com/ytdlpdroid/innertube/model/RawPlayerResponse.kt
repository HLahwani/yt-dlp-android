package com.ytdlpdroid.innertube.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

@Serializable
internal data class RawPlayerResponse(
    val playabilityStatus: RawPlayabilityStatus,
    val streamingData: RawStreamingData? = null,
    val videoDetails: RawVideoDetails? = null,
)

@Serializable
internal data class RawPlayabilityStatus(
    val status: String,
    val reason: String? = null,
)

@Serializable
internal data class RawStreamingData(
    val expiresInSeconds: String? = null,
    val formats: List<RawFormat> = emptyList(),
    val adaptiveFormats: List<RawFormat> = emptyList(),
)

@Serializable
internal data class RawFormat(
    val itag: Int,
    val url: String? = null,
    val signatureCipher: String? = null,
    val mimeType: String,
    val bitrate: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val qualityLabel: String? = null,
    val audioQuality: String? = null,
    val audioSampleRate: String? = null,
    val approxDurationMs: String? = null,
    val contentLength: String? = null,
    val initRange: RawRange? = null,
    val indexRange: RawRange? = null,
)

@Serializable
internal data class RawRange(val start: String, val end: String)

@Serializable
internal data class RawVideoDetails(
    val videoId: String,
    val title: String,
    val lengthSeconds: String? = null,
    val author: String? = null,
    val channelId: String? = null,
    val isLiveContent: Boolean = false,
    val keywords: List<String> = emptyList(),
    val thumbnail: RawThumbnailList? = null,
)

@Serializable
internal data class RawThumbnailList(val thumbnails: List<RawThumbnail> = emptyList())

@Serializable
internal data class RawThumbnail(val url: String, val width: Int = 0, val height: Int = 0)
