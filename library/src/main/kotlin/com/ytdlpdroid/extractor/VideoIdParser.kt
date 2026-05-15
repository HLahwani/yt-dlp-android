package com.ytdlpdroid.extractor

import com.ytdlpdroid.model.YTDLPError

internal object VideoIdParser {
    private val PATTERNS = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/(?:embed|shorts|v)/([A-Za-z0-9_-]{11})"""),
        Regex("""^([A-Za-z0-9_-]{11})$"""),
    )

    fun parse(input: String): String {
        val trimmed = input.trim()
        for (pattern in PATTERNS) {
            pattern.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        }
        throw YTDLPError.InvalidUrl(trimmed)
    }
}
