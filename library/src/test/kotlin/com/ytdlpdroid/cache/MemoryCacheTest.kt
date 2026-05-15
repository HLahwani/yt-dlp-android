package com.ytdlpdroid.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryCacheTest {

    @Test
    fun `get returns value within TTL`() {
        val cache = MemoryCache<String, String>(10)
        cache.put("k", "v", ttlMs = 60_000L)
        assertEquals("v", cache.get("k"))
    }

    @Test
    fun `get returns null after TTL expires`() {
        val cache = MemoryCache<String, String>(10)
        cache.put("k", "v", ttlMs = -1L) // already expired
        assertNull(cache.get("k"))
    }

    @Test
    fun `remove evicts entry`() {
        val cache = MemoryCache<String, String>(10)
        cache.put("k", "v", ttlMs = 60_000L)
        cache.remove("k")
        assertNull(cache.get("k"))
    }

    @Test
    fun `LRU eviction on maxSize + 1 inserts`() {
        val cache = MemoryCache<Int, Int>(3)
        cache.put(1, 1, 60_000L)
        cache.put(2, 2, 60_000L)
        cache.put(3, 3, 60_000L)
        // Access key 1 to make it recently used
        cache.get(1)
        // Insert key 4 — should evict key 2 (least recently used)
        cache.put(4, 4, 60_000L)
        assertNull(cache.get(2))
        assertEquals(1, cache.get(1))
        assertEquals(3, cache.get(3))
        assertEquals(4, cache.get(4))
    }

    @Test
    fun `miss on absent key returns null`() {
        val cache = MemoryCache<String, String>(10)
        assertNull(cache.get("missing"))
    }
}
