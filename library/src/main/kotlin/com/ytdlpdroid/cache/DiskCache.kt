package com.ytdlpdroid.cache

import java.io.File

internal class DiskCache(private val dir: File) {

    fun get(key: String): String? {
        val f = fileFor(key)
        return if (f.exists() && f.length() > 0) f.readText() else null
    }

    fun put(key: String, value: String) {
        dir.mkdirs()
        fileFor(key).writeText(value)
    }

    fun evictBeyond(keepNewest: Int) {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(keepNewest)
            ?.forEach { it.delete() }
    }

    private fun fileFor(key: String) = File(dir, "${key.hashCode()}.cache")
}
