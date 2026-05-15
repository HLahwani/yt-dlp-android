package com.ytdlpdroid.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiskCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `put then get returns value`() {
        val cache = DiskCache(tmp.newFolder())
        cache.put("key1", "hello")
        assertEquals("hello", cache.get("key1"))
    }

    @Test
    fun `get returns null for missing key`() {
        val cache = DiskCache(tmp.newFolder())
        assertNull(cache.get("absent"))
    }

    @Test
    fun `evictBeyond keeps newest files`() {
        val dir = tmp.newFolder()
        val cache = DiskCache(dir)
        cache.put("a", "1")
        Thread.sleep(10)
        cache.put("b", "2")
        Thread.sleep(10)
        cache.put("c", "3")
        cache.evictBeyond(2)
        assertEquals(2, dir.listFiles()!!.size)
        // Newest two ("b" and "c") survive
        assertEquals("2", cache.get("b"))
        assertEquals("3", cache.get("c"))
        assertNull(cache.get("a"))
    }

    @Test
    fun `put creates directory if absent`() {
        val dir = tmp.root.resolve("newdir/sub")
        val cache = DiskCache(dir)
        cache.put("k", "v")
        assertTrue(dir.exists())
        assertEquals("v", cache.get("k"))
    }
}
