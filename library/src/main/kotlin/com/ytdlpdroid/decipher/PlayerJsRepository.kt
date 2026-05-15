package com.ytdlpdroid.decipher

import com.ytdlpdroid.cache.DiskCache
import com.ytdlpdroid.model.YTDLPError
import com.ytdlpdroid.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

internal class PlayerJsRepository(
    cacheDir: File,
    private val httpClient: OkHttpClient = HttpClientProvider.client,
) {
    private val diskCache = DiskCache(cacheDir)

    // Keeps the two most-recently-used player JS texts in memory.
    private val memCache = LinkedHashMap<String, String>(4, 0.75f, true)

    suspend fun fetchPlayerJs(playerJsUrl: String): String = withContext(Dispatchers.IO) {
        memCache[playerJsUrl]?.let { return@withContext it }

        diskCache.get(playerJsUrl)?.let { text ->
            memCache[playerJsUrl] = text
            return@withContext text
        }

        val fullUrl = if (playerJsUrl.startsWith("http")) playerJsUrl
                      else "https://www.youtube.com$playerJsUrl"

        val text = fetchText(fullUrl)
        diskCache.put(playerJsUrl, text)
        if (memCache.size >= 3) memCache.entries.iterator().let { it.next(); it.remove() }
        memCache[playerJsUrl] = text
        text
    }

    suspend fun fetchPlayerJsUrl(videoId: String): String = withContext(Dispatchers.IO) {
        val html = fetchText(
            "https://www.youtube.com/watch?v=$videoId",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        )
        Regex(""""jsUrl"\s*:\s*"(/s/player/[a-f0-9]+/[^"]+base\.js)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""(/s/player/[a-f0-9]+/player_ias\.vflset/[^"]+base\.js)""").find(html)?.groupValues?.get(1)
            ?: throw YTDLPError.PlayerJsFetchFailed(
                "watch?v=$videoId", IOException("player JS URL not found in page"))
    }

    fun evictOldCache() = diskCache.evictBeyond(2)

    /** Extracts the signatureTimestamp (sts) from player JS. Required in playbackContext for age-restricted video bypass. */
    fun extractSignatureTimestamp(playerJs: String): Int? =
        Regex("""(?:signatureTimestamp|sts)\s*[=:]\s*(\d{5,6})""")
            .find(playerJs)?.groupValues?.get(1)?.toIntOrNull()

    private fun fetchText(url: String, vararg headers: Pair<String, String>): String {
        val req = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful)
                    throw YTDLPError.PlayerJsFetchFailed(url, IOException("HTTP ${resp.code}"))
                resp.body?.string()
                    ?: throw YTDLPError.PlayerJsFetchFailed(url, IOException("Empty body"))
            }
        } catch (e: IOException) {
            throw YTDLPError.PlayerJsFetchFailed(url, e)
        }
    }
}
