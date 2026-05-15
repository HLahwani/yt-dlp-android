package com.ytdlpdroid.integration

import com.ytdlpdroid.innertube.InnerTubeClientConfig
import com.ytdlpdroid.innertube.buildRequestBody
import com.ytdlpdroid.innertube.playerApiUrl
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Prints raw InnerTube responses — run manually to diagnose API issues. */
class DiagnosticTest {

    private val cookieStore = ConcurrentHashMap<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isNotEmpty()) cookieStore[url.host] = cookies
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host] ?: emptyList()
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    private val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun initSession(): String? = try {
        val html = http.newCall(
            Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent", browserUA)
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()
        ).execute().use { it.body?.string() ?: "" }
        println("  Session cookies: ${cookieStore.values.flatten().map { it.name }}")
        (Regex(""""VISITOR_DATA":"([^"]+)"""").find(html)
            ?: Regex(""""visitorData":"([^"]+)"""").find(html))?.groupValues?.get(1)
    } catch (e: Exception) { null }

    private fun rawPost(videoId: String, cfg: InnerTubeClientConfig, vd: String? = null): String {
        val body = cfg.buildRequestBody(videoId, vd)
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(cfg.playerApiUrl())
            .post(body)
            .header("User-Agent", cfg.userAgent)
            .header("X-YouTube-Client-Name", cfg.clientNumber)
            .header("X-YouTube-Client-Version", cfg.clientVersion)
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: "(empty)"
            val statusBlock = Regex(""""playabilityStatus"\s*:\s*\{[^}]+\}""")
                .find(text)?.value ?: text.take(400)
            "HTTP ${resp.code} | $statusBlock"
        }
    }

    @Test
    fun `diagnose all clients for test videos`() {
        val vd = initSession()
        println("\nVisitor data: ${vd?.take(30)}...")

        val videos = mapOf(
            "93QIhAbxmdc" to "[1] valid",
            "27OZc-ku6is" to "[2] embed",
            "dQw4w9WgXcQ" to "[baseline] Rick Astley",
        )
        val configs = listOf(
            InnerTubeClientConfig.ANDROID_TESTSUITE,
            InnerTubeClientConfig.TVHTML5_SIMPLY_EMBEDDED,
            InnerTubeClientConfig.MWEB,
            InnerTubeClientConfig.WEB_EMBEDDED,
            InnerTubeClientConfig.WEB,
        )

        for ((videoId, label) in videos) {
            println("\n=== $label ($videoId) ===")
            for (cfg in configs) {
                val useVd = if (cfg is InnerTubeClientConfig.WEB) vd else null
                try {
                    val result = rawPost(videoId, cfg, useVd)
                    println("  ${cfg.clientName.padEnd(32)}: $result")
                } catch (e: Exception) {
                    println("  ${cfg.clientName.padEnd(32)}: ERROR ${e.message}")
                }
            }
        }
    }
}
