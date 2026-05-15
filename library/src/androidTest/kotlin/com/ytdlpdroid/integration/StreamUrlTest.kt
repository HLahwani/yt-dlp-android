package com.ytdlpdroid.integration

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ytdlpdroid.innertube.model.RawPlayerResponse
import com.ytdlpdroid.innertube.model.json
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Focused test: verifies WHY stream URLs from ytInitialPlayerResponse return 403,
 * and tests fixes (alr=yes, WebView fetch).
 */
@RunWith(AndroidJUnit4::class)
class StreamUrlTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun headStatus(url: String): Int =
        http.newCall(Request.Builder().url(url).head().build()).execute().use { it.code }

    /** Load watch page with mobile UA, return ytInitialPlayerResponse JSON. */
    private fun loadPlayerResponse(videoId: String): String? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/90.0.4430.91 Mobile Safari/537.36"
                addJavascriptInterface(object {
                    @JavascriptInterface fun result(v: String) { holder[0] = v; latch.countDown() }
                }, "WvBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            view?.evaluateJavascript("""
                                (function(){
                                    var pr = window.ytInitialPlayerResponse;
                                    if(pr && pr.streamingData) WvBridge.result(JSON.stringify(pr));
                                    else if(pr) WvBridge.result(JSON.stringify(pr));
                                    else WvBridge.result("null");
                                })();
                            """.trimIndent(), null)
                        }, 8_000L)
                    }
                }
            }
            wv.loadUrl("https://www.youtube.com/watch?v=$videoId")
        }
        latch.await(90, TimeUnit.SECONDS)
        return holder[0].takeIf { it != null && it != "null" }
    }

    /** Make a fetch request from within a WebView. Returns HTTP status code. */
    private fun webViewFetch(url: String, addAlr: Boolean = false): Int {
        val targetUrl = if (addAlr && !url.contains("alr=")) {
            val sep = if ("?" in url) "&" else "?"
            "${url}${sep}alr=yes"
        } else url

        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/90.0.4430.91 Mobile Safari/537.36"
                addJavascriptInterface(object {
                    @JavascriptInterface fun result(v: String) { holder[0] = v; latch.countDown() }
                }, "WvBridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        val escaped = targetUrl.replace("\\", "\\\\").replace("\"", "\\\"")
                        view?.evaluateJavascript("""
                            fetch("$escaped",{method:"GET",mode:"cors"})
                                .then(r=>WvBridge.result(""+r.status))
                                .catch(e=>WvBridge.result("err:"+e));
                        """.trimIndent(), null)
                    }
                }
            }
            wv.loadUrl("https://www.youtube.com/")
        }
        latch.await(30, TimeUnit.SECONDS)
        return holder[0]?.toIntOrNull() ?: -1
    }

    @Test
    fun test1_diagnoseStreamUrlAccess() {
        println("\n=== Stream URL Access Diagnostics for 93QIhAbxmdc ===")

        // Step 1: get player response
        val prJson = loadPlayerResponse("93QIhAbxmdc")
            ?: run { println("Failed to get ytInitialPlayerResponse"); return }

        val pr = runBlocking { json.decodeFromString<RawPlayerResponse>(prJson) }
        println("playabilityStatus = ${pr.playabilityStatus.status}")
        if (pr.playabilityStatus.status != "OK") {
            println("Non-OK status, skipping"); return
        }

        val allFormats = (pr.streamingData?.adaptiveFormats ?: emptyList()) +
                         (pr.streamingData?.formats ?: emptyList())
        println("formats = ${allFormats.size}  direct_urls = ${allFormats.count { it.url != null }}")

        // Find first audio format with direct URL
        val audioFmt = allFormats.firstOrNull {
            it.url != null && it.audioSampleRate != null
        } ?: run { println("No direct audio URL found"); return }

        val rawUrl = audioFmt.url!!
        val nParam = Regex("[?&]n=([^&]+)").find(rawUrl)?.groupValues?.get(1)
        println("\nFirst audio URL (itag=${audioFmt.itag}):")
        println("  n-param = $nParam")
        println("  has alr = ${rawUrl.contains("alr=")}")
        println("  URL prefix = ${rawUrl.take(80)}...")

        // Step 2: test raw URL via OkHttp
        val statusRaw = headStatus(rawUrl)
        println("\nOkHttp HEAD raw URL → $statusRaw")

        // Step 3: add alr=yes and test via OkHttp
        val urlWithAlr = rawUrl + (if ("?" in rawUrl) "&" else "?") + "alr=yes"
        val statusAlr = headStatus(urlWithAlr)
        println("OkHttp HEAD +alr=yes → $statusAlr")

        // Step 4: test raw URL via WebView fetch
        println("\nWebView GET raw URL...")
        val statusWv = webViewFetch(rawUrl)
        println("WebView GET raw URL → $statusWv")

        // Step 5: test with alr=yes via WebView
        println("WebView GET +alr=yes...")
        val statusWvAlr = webViewFetch(rawUrl, addAlr = true)
        println("WebView GET +alr=yes → $statusWvAlr")

        println("\n--- Summary ---")
        println("Raw URL:         OkHttp=$statusRaw  WebView=$statusWv")
        println("With alr=yes:    OkHttp=$statusAlr  WebView=$statusWvAlr")

        if (statusRaw in 200..299 || statusAlr in 200..299 ||
            statusWv in 200..299 || statusWvAlr in 200..299) {
            println("✓ At least one variant returned 2xx — URL IS valid")
        } else {
            println("All variants returned non-2xx")
        }
    }
}
