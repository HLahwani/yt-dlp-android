package com.ytdlpdroid.integration

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ytdlpdroid.YTDLPDroid
import com.ytdlpdroid.model.StreamResult
import com.ytdlpdroid.model.YTDLPError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class InstrumentedUrlExtractionTest {

    companion object {
        private lateinit var ytdlp: YTDLPDroid
        private val http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        @BeforeClass @JvmStatic
        fun setup() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext

            // Load YouTube in a WebView so JavaScript executes and YouTube's session
            // cookies (YSC, VISITOR_INFO1_LIVE, __Secure-ROLLOUT_TOKEN, etc.) are set.
            // These cookies carry the session context that makes InnerTube accept requests.
            val cookies = initWebViewSession()
            println("[setup] WebView cookies: $cookies")

            val fastHttp = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .apply {
                    if (!cookies.isNullOrBlank()) {
                        // Inject WebView session cookies into every InnerTube request
                        addInterceptor { chain ->
                            chain.proceed(
                                chain.request().newBuilder()
                                    .header("Cookie", cookies)
                                    .build()
                            )
                        }
                    }
                }
                .build()

            ytdlp = YTDLPDroid.Builder(ctx.cacheDir)
                .httpClient(fastHttp)
                .build()
        }

        /**
         * Loads https://www.youtube.com/ in a WebView on the main thread,
         * waits 6 s for JS to execute, then extracts the resulting cookies.
         */
        private fun initWebViewSession(): String? {
            val latch = CountDownLatch(1)
            val cookieHolder = arrayOfNulls<String>(1)

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(
                    WebView(InstrumentationRegistry.getInstrumentation().targetContext), true
                )

                val wv = WebView(InstrumentationRegistry.getInstrumentation().targetContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/90.0.4430.91 Mobile Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // Wait 6 s after page load for YouTube's JS challenges to complete
                            Handler(Looper.getMainLooper()).postDelayed({
                                CookieManager.getInstance().flush()
                                cookieHolder[0] = CookieManager.getInstance()
                                    .getCookie("https://www.youtube.com")
                                latch.countDown()
                            }, 6_000L)
                        }
                    }
                }
                wv.loadUrl("https://www.youtube.com/")
            }

            latch.await(60, TimeUnit.SECONDS)
            return cookieHolder[0]
        }
    }

    // ---- helpers ----

    private fun head(url: String): Int =
        http.newCall(Request.Builder().url(url).head().build()).execute().use { it.code }

    private fun extract(label: String, url: String, allowRestricted: Boolean = false): StreamResult? {
        println("\n━━━ $label ━━━  url=$url")
        return try {
            val result = runBlocking { withTimeout(180_000L) { ytdlp.extract(url) } }
            println("  title   : ${result.metadata.title}")
            println("  author  : ${result.metadata.author}")
            println("  duration: ${result.metadata.durationSeconds}s")
            result.videoStream?.also {
                println("  video   : itag=${it.itag} ${it.height}p ${it.fps}fps codec=${it.codec}")
            } ?: println("  video   : (none)")
            println("  audio   : itag=${result.audioStream.itag} codec=${result.audioStream.codec} ${result.audioStream.bitrate/1000}kbps")

            val audioStatus = head(result.audioStream.url)
            println("  audio HEAD → $audioStatus")
            assertTrue("Audio URL must return 2xx (got $audioStatus)", audioStatus in 200..299)

            result.videoStream?.let {
                val videoStatus = head(it.url)
                println("  video HEAD → $videoStatus")
                assertTrue("Video URL must return 2xx (got $videoStatus)", videoStatus in 200..299)
            }

            println("  ✓ PASS")
            result
        } catch (e: YTDLPError) {
            val isRestriction = e is YTDLPError.VideoUnavailable
                    || e is YTDLPError.AllClientsFailed
                    || e is YTDLPError.AgeRestricted
            if (allowRestricted && isRestriction) {
                println("  ~ RESTRICTED: ${e::class.simpleName} — ${e.message}")
                null
            } else {
                println("  ✗ FAIL: ${e::class.simpleName} — ${e.message}")
                throw e
            }
        }
    }

    // ---- tests ----

    @Test
    fun test1_validStandardUrl() {
        val result = extract("[1] valid", "https://www.youtube.com/watch?v=93QIhAbxmdc")
        checkNotNull(result) { "Expected streams for URL [1]" }
        assertTrue(result.videoId == "93QIhAbxmdc")
        assertTrue(result.metadata.title.isNotEmpty())
    }

    @Test
    fun test2_embedUrlWithQueryParams() {
        val result = extract(
            "[2] embed",
            "https://www.youtube.com/embed/27OZc-ku6is?enablejsapi=1&wmode=opaque&autoplay=1",
        )
        checkNotNull(result) { "Expected streams for URL [2]" }
        assertTrue(result.videoId == "27OZc-ku6is")
    }

    @Test
    fun test3_countryRestrictedVideo() {
        val result = extract(
            "[3] country-restricted",
            "https://www.youtube.com/watch?v=l_98K4_6UQ0",
            allowRestricted = true,
        )
        if (result != null) {
            assertTrue(result.videoId == "l_98K4_6UQ0")
            println("  (accessible from this region — streams verified)")
        }
    }
}
