package com.ytdlpdroid.innertube

import com.ytdlpdroid.innertube.model.RawPlayerResponse
import com.ytdlpdroid.innertube.model.json
import com.ytdlpdroid.model.YTDLPError
import com.ytdlpdroid.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class InnerTubeClient(
    httpClient: OkHttpClient = HttpClientProvider.client,
) {
    // Cookie-aware client so YouTube session cookies (CONSENT, YSC, VISITOR_INFO1_LIVE)
    // are captured during the init GET and replayed on InnerTube POST requests.
    private val cookieJar = InMemoryCookieJar()
    private val httpClient = httpClient.newBuilder().cookieJar(cookieJar).build()

    @Volatile private var sessionInitialized = false
    @Volatile private var cachedVisitorData: String? = null

    suspend fun fetchPlayerResponse(
        videoId: String,
        config: InnerTubeClientConfig,
        regionCode: String? = null,
    ): RawPlayerResponse = withContext(Dispatchers.IO) {
        // Only fetch visitor data / session cookies for the WEB client — other clients don't
        // need them and the homepage fetch would consume MockWebServer responses in unit tests.
        if (config is InnerTubeClientConfig.WEB) ensureSession()

        val visitorData = if (config is InnerTubeClientConfig.WEB) cachedVisitorData else null

        val body = config.buildRequestBody(videoId, visitorData, regionCode)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(config.playerApiUrl())
            .post(body)
            .header("User-Agent", config.userAgent)
            .header("X-YouTube-Client-Name", config.clientNumber)
            .header("X-YouTube-Client-Version", config.clientVersion)
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://www.youtube.com")
            .header("Referer", "https://www.youtube.com/")
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw YTDLPError.NetworkError("InnerTube player request failed", e)
        }

        response.use {
            if (!it.isSuccessful) throw YTDLPError.NetworkError("HTTP ${it.code}", IOException(it.message))
            val bodyStr = it.body?.string()
                ?: throw YTDLPError.NetworkError("Empty response body", IOException())
            json.decodeFromString<RawPlayerResponse>(bodyStr)
        }
    }

    /** One-time session init: GETs the YouTube homepage so cookies and visitor data are captured. */
    private fun ensureSession() {
        if (sessionInitialized) return
        synchronized(this) {
            if (sessionInitialized) return
            try {
                val browserUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                val html = httpClient.newCall(
                    Request.Builder()
                        .url("https://www.youtube.com/")
                        .header("User-Agent", browserUA)
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .build()
                ).execute().use { it.body?.string() ?: "" }

                cachedVisitorData = (Regex(""""VISITOR_DATA":"([^"]+)"""").find(html)
                    ?: Regex(""""visitorData":"([^"]+)"""").find(html))
                    ?.groupValues?.get(1)
            } catch (_: Exception) {
                // non-fatal — requests will proceed without cookies
            } finally {
                sessionInitialized = true
            }
        }
    }
}

private class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, List<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isNotEmpty()) store[url.host] = cookies
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
}
