package com.ytdlpdroid.model

data class ExtractionOptions(
    val maxVideoHeight: Int? = null,
    val preferH264: Boolean = false,
    val preferOpusAudio: Boolean = true,
    val includeMuxedFallback: Boolean = true,
)
