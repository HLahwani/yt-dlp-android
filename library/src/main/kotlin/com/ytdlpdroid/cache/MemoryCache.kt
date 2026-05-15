package com.ytdlpdroid.cache

internal class MemoryCache<K, V>(private val maxSize: Int) {

    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(maxSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, Entry<V>>) = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) { map.remove(key); return null }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V, ttlMs: Long) {
        map[key] = Entry(value, System.currentTimeMillis() + ttlMs)
    }

    @Synchronized
    fun remove(key: K) { map.remove(key) }
}
