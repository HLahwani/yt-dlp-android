package com.ytdlpdroid.cache

import com.ytdlpdroid.model.StreamResult

internal class ExtractionCache(capacity: Int = 30) {

    private val cache = MemoryCache<String, StreamResult>(capacity)

    fun get(videoId: String): StreamResult? = cache.get(videoId)

    fun put(videoId: String, result: StreamResult) {
        val ttlMs = result.expiresAt - System.currentTimeMillis() - 5 * 60_000L
        if (ttlMs > 0) cache.put(videoId, result, ttlMs)
    }
}
