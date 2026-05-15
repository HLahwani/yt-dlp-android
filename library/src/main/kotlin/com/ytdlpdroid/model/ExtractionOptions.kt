package com.ytdlpdroid.model

data class ExtractionOptions(
    val maxVideoHeight: Int? = null,
    val preferH264: Boolean = false,
    val preferOpusAudio: Boolean = true,
    val includeMuxedFallback: Boolean = true,
    /**
     * BCP-47 region code passed as the `gl` field in InnerTube requests (e.g. "US", "DE", "JP").
     * `null` omits the field and lets YouTube infer the region from the server-side IP address.
     * Set explicitly if the video is geo-restricted to a specific country you have access to.
     */
    val regionCode: String? = null,
)
