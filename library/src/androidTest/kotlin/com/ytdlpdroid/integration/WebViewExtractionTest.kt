package com.ytdlpdroid.integration

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ytdlpdroid.decipher.DecipherService
import com.ytdlpdroid.decipher.NParamDecipherer
import com.ytdlpdroid.decipher.PlayerJsRepository
import com.ytdlpdroid.decipher.SignatureDecipherer
import com.ytdlpdroid.format.FormatSelector
import com.ytdlpdroid.innertube.PlayerResponseParser
import com.ytdlpdroid.innertube.model.RawPlayerResponse
import com.ytdlpdroid.innertube.model.json
import com.ytdlpdroid.js.QuickJsEngine
import com.ytdlpdroid.model.ExtractionOptions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests using ytInitialPlayerResponse (mobile UA = player-plasma-es6 = direct URLs).
 * Each format has a direct stream URL with n-param that needs transformation.
 *
 * Key question being tested: does adding alr=yes fix the 403?
 * (VR function in player-plasma-es6 always adds alr=yes to stream URLs)
 */
@RunWith(AndroidJUnit4::class)
class WebViewExtractionTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Loads the YouTube watch page and captures the ACTUAL stream URL that
     * YouTube's player requests to the CDN — this URL has the already-transformed
     * n-param applied by the player's own nsig function.
     *
     * Returns JSON: {"playerResponse":{...}, "playerJsUrl":"...", "streamUrl":"https://rr...googlevideo.com/..."}
     */
    private fun fetchPlayerDataWithStreamUrl(videoId: String): String? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/90.0.4430.91 Mobile Safari/537.36"
                addJavascriptInterface(object {
                    @JavascriptInterface fun result(v: String) { holder[0] = v; latch.countDown() }
                    @JavascriptInterface fun streamUrl(u: String) {
                        println("[WebView] Captured stream URL: ${u.take(80)}...")
                        // Once we have a stream URL, build the result
                        if (holder[0] == null) {
                            // We'll get the playerResponse separately after 10s
                        }
                    }
                }, "Bridge")

                webViewClient = object : WebViewClient() {
                    // Inject XHR override BEFORE page scripts run
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        view?.evaluateJavascript("""
                            (function(){
                                if(window.__ytdlp_xhr_patched__) return;
                                window.__ytdlp_xhr_patched__=true;
                                window.__ytdlp_stream_url__=null;
                                var origOpen=XMLHttpRequest.prototype.open;
                                XMLHttpRequest.prototype.open=function(m,u){
                                    if(u&&u.indexOf('googlevideo.com')>=0&&u.indexOf('videoplayback')>=0&&!window.__ytdlp_stream_url__){
                                        window.__ytdlp_stream_url__=u;
                                        Bridge.streamUrl(u);
                                    }
                                    return origOpen.apply(this,arguments);
                                };
                                // Also override fetch
                                var origFetch=window.fetch;
                                window.fetch=function(u,o){
                                    var p=origFetch.apply(this,arguments);
                                    if(typeof u==='string'&&u.indexOf('googlevideo.com')>=0&&u.indexOf('videoplayback')>=0&&!window.__ytdlp_stream_url__){
                                        window.__ytdlp_stream_url__=u;
                                        Bridge.streamUrl(u);
                                    }
                                    return p;
                                };
                            })();
                        """.trimIndent(), null)
                    }

                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            view?.evaluateJavascript("""
                                (function(){
                                    try{
                                        var pr=window.ytInitialPlayerResponse;
                                        if(!pr||!pr.playabilityStatus){Bridge.result('{}');return;}
                                        var jsUrl=null;
                                        try{
                                            var cfgs=ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS')||{};
                                            for(var k in cfgs){if(cfgs[k].jsUrl){jsUrl=cfgs[k].jsUrl;break;}}
                                        }catch(e){}
                                        Bridge.result(JSON.stringify({
                                            playerResponse:pr,
                                            playerJsUrl:jsUrl,
                                            streamUrl:window.__ytdlp_stream_url__||null
                                        }));
                                    }catch(e){Bridge.result('{"error":"'+e+'"}')}
                                })();
                            """.trimIndent(), null)
                        }, 10_000L)
                    }
                }
            }
            wv.loadUrl("https://www.youtube.com/watch?v=$videoId")
        }
        latch.await(120, TimeUnit.SECONDS)
        return holder[0]
    }

    /**
     * Loads [url] in WebView with mobile UA, waits [delayAfterLoadMs] for JS,
     * then evaluates [script] and returns the string result.
     */
    private fun webViewEval(url: String, script: String, delayAfterLoadMs: Long = 10_000L): String? {
        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val wv = WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Mobile UA → player-plasma-es6 → direct stream URLs in ytInitialPlayerResponse
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 11; Pixel 5) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/90.0.4430.91 Mobile Safari/537.36"
                addJavascriptInterface(object {
                    @JavascriptInterface fun result(v: String) { holder[0] = v; latch.countDown() }
                    @JavascriptInterface fun error(msg: String) { println("[WebView] $msg") }
                }, "Bridge")
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            view?.evaluateJavascript(script, null)
                        }, delayAfterLoadMs)
                    }
                }
            }
            wv.loadUrl(url)
        }
        latch.await(120, TimeUnit.SECONDS)  // 120s to allow for slow emulator page loads
        return holder[0]
    }

    private fun fetchPlayerDataViaWebView(videoId: String): String? = fetchPlayerDataWithStreamUrl(videoId)

    private fun fetchPlayerDataViaWebViewOriginal(videoId: String): String? {
        val script = """
            (function() {
              try {
                var pr = window.ytInitialPlayerResponse;
                if (!pr || !pr.playabilityStatus) {
                  Bridge.error('ytInitialPlayerResponse absent');
                  return;
                }
                var jsUrl = null;
                try {
                  var cfgs = ytcfg.get('WEB_PLAYER_CONTEXT_CONFIGS') || {};
                  for (var k in cfgs) {
                    if (cfgs[k].jsUrl) { jsUrl = cfgs[k].jsUrl; break; }
                  }
                } catch(e) {}
                if (!jsUrl) {
                  var m = document.documentElement.innerHTML.match(/"jsUrl"\s*:\s*"(\/s\/player\/[^"]+base\.js)"/);
                  if (m) jsUrl = m[1];
                }

                // Try to get VR-processed URL for first audio format (to get transformed n-param)
                var vrProcessedUrl = null;
                try {
                  var fmts = (pr.streamingData && pr.streamingData.adaptiveFormats) || [];
                  for (var i = 0; i < fmts.length; i++) {
                    var fmt = fmts[i];
                    if (fmt.url && fmt.mimeType && fmt.mimeType.indexOf("opus") >= 0) {
                      // Call VR (player's URL processor) on the raw URL
                      if (typeof VR !== 'undefined') {
                        var processed = VR(fmt.url, "sig", "");
                        if (processed && typeof processed.Nf === 'function') {
                          vrProcessedUrl = processed.Nf();
                        } else if (processed && processed.W) {
                          vrProcessedUrl = processed.W;
                        }
                      }
                      break;
                    }
                  }
                } catch(vrErr) {}

                Bridge.result(JSON.stringify({
                  playerResponse: pr,
                  playerJsUrl: jsUrl,
                  vrProcessedUrl: vrProcessedUrl
                }));
              } catch(e) {
                Bridge.error('JS error: ' + e);
              }
            })();
        """.trimIndent()
        return webViewEval("https://www.youtube.com/watch?v=$videoId", script)
    }

    /** Makes a GET request from within a youtube.com WebView context. Returns HTTP status. */
    private fun webViewFetchStatus(targetUrl: String): Int {
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
                            fetch("$escaped", {method:"GET", mode:"cors", headers:{"Range":"bytes=0-0"}})
                                .then(r => WvBridge.result(r.status.toString()))
                                .catch(e => WvBridge.result("0"));
                        """.trimIndent(), null)
                    }
                }
            }
            wv.loadUrl("https://www.youtube.com/")
        }
        latch.await(30, TimeUnit.SECONDS)
        return holder[0]?.toIntOrNull() ?: -1
    }

    private val jsEngine by lazy { QuickJsEngine() }

    private fun makeDecipherService(): Triple<PlayerJsRepository, NParamDecipherer, DecipherService> {
        val cacheDir = File(ctx.cacheDir, "ytdlpdroid_wv_test").also { it.mkdirs() }
        val repo = PlayerJsRepository(cacheDir)
        val nParam = NParamDecipherer(jsEngine)
        val sig = SignatureDecipherer(jsEngine)
        return Triple(repo, nParam, DecipherService(repo, nParam, sig))
    }

    private fun extractAndVerify(label: String, videoId: String, allowRestricted: Boolean = false) {
        println("\n━━━ $label ━━━  videoId=$videoId")

        println("  Loading watch page in WebView...")
        val payload = fetchPlayerDataViaWebView(videoId)
        if (payload == null) {
            if (allowRestricted) { println("  ~ RESTRICTED: no payload"); return }
            throw AssertionError("No payload for $videoId after 120s")
        }
        println("  Got payload (${payload.length} chars)")

        val envelope = json.decodeFromString<JsonObject>(payload)
        val responseJson = envelope["playerResponse"]?.toString()
            ?: throw AssertionError("No playerResponse in payload")
        val webViewPlayerJsUrl = envelope["playerJsUrl"]?.toString()?.trim('"')
        val capturedStreamUrl = envelope["streamUrl"]?.toString()?.trim('"').takeIf { !it.isNullOrBlank() && it != "null" }
        val vrProcessedUrl = capturedStreamUrl
        capturedStreamUrl?.let {
            val capturedN = Regex("[?&]n=([^&]+)").find(it)?.groupValues?.get(1)
            println("  XHR-captured stream URL n-param = $capturedN")
        }

        val raw = json.decodeFromString<RawPlayerResponse>(responseJson)
        println("  playabilityStatus = ${raw.playabilityStatus.status}  jsUrl=$webViewPlayerJsUrl")
        if (raw.playabilityStatus.status != "OK") {
            if (allowRestricted) { println("  ~ RESTRICTED: ${raw.playabilityStatus.reason}"); return }
            throw AssertionError("Non-OK: ${raw.playabilityStatus.status} — ${raw.playabilityStatus.reason}")
        }

        val videoInfo = PlayerResponseParser.extractVideoInfo(raw)
        println("  title    = ${videoInfo.title}")
        println("  duration = ${videoInfo.durationSeconds}s")
        assertTrue("title must not be empty", videoInfo.title.isNotEmpty())
        assertEquals(videoId, videoInfo.videoId)

        val streamingData = raw.streamingData ?: throw AssertionError("No streamingData")
        val allFormats = streamingData.adaptiveFormats + streamingData.formats
        println("  formats = ${allFormats.size}  has_url=${allFormats.count { it.url != null }}  has_sig=${allFormats.count { it.signatureCipher != null }}")

        allFormats.firstOrNull { it.url != null }?.url?.let { u ->
            val rawN = Regex("[?&]n=([^&]+)").find(u)?.groupValues?.get(1)
            val hasAlr = u.contains("alr=")
            println("  first url: n=$rawN  alr=$hasAlr")
        }

        val (repo, _, decipherService) = makeDecipherService()
        val rawJsUrl = webViewPlayerJsUrl?.takeIf { it.isNotBlank() }
            ?: runBlocking { repo.fetchPlayerJsUrl(videoId) }
        // Swap to player_ias for standard nsig patterns
        val playerHash = Regex("""/s/player/([a-f0-9]+)/""").find(rawJsUrl ?: "")?.groupValues?.get(1)
        val playerJsUrl = playerHash?.let { "/s/player/$it/player_ias.vflset/en_US/base.js" } ?: rawJsUrl
        println("  playerJsUrl = $playerJsUrl")

        val resolved = runBlocking {
            allFormats.mapNotNull { fmt ->
                runCatching { fmt to decipherService.buildPlayableUrl(fmt, playerJsUrl) }
                    .onFailure { e -> if (allFormats.indexOf(fmt) < 2) println("  [decipher fail] ${e.message?.take(80)}") }
                    .getOrNull()
            }
        }
        println("  resolved ${resolved.size}/${allFormats.size}")
        resolved.firstOrNull()?.second?.let { url ->
            println("  first n-param after transform = ${Regex("[?&]n=([^&]+)").find(url)?.groupValues?.get(1)}")
        }
        assertTrue("Must resolve at least one format", resolved.isNotEmpty())

        val selection = FormatSelector.select(resolved, ExtractionOptions())
        println("  audio = itag=${selection.audio.itag} codec=${selection.audio.codec} ${selection.audio.bitrate/1000}kbps")
        selection.video?.let { println("  video = itag=${it.itag} ${it.height}p ${it.fps}fps") }

        val rawAudioUrl = resolved.firstOrNull { it.first.audioSampleRate != null }?.second
            ?: selection.audio.url
        val audioRaw = http.newCall(Request.Builder().url(rawAudioUrl).head().build()).execute().use { it.code }
        println("  audio HEAD OkHttp → $audioRaw")

        // Try from WebView context (same cookies/UA as the page that generated the URL)
        val wvStatus = webViewFetchStatus(rawAudioUrl)
        println("  audio GET WebView → $wvStatus")

        if (vrProcessedUrl != null) {
            val vrStatus = http.newCall(Request.Builder().url(vrProcessedUrl).head().build()).execute().use { it.code }
            println("  XHR-captured HEAD → $vrStatus  (n=${Regex("[?&]n=([^&]+)").find(vrProcessedUrl)?.groupValues?.get(1)})")
        }

        val bestStatus = if (wvStatus in 200..299) wvStatus else audioRaw
        assertTrue("Audio URL must return 2xx (OkHttp=$audioRaw WebView=$wvStatus)", bestStatus in 200..299)

        selection.video?.let {
            val vs = http.newCall(Request.Builder().url(it.url).head().build()).execute().use { r -> r.code }
            println("  video HEAD → $vs")
        }

        println("  ✓ PASS")
    }

    @Test fun test1_validStandardUrl() = extractAndVerify("[1] valid 93QIhAbxmdc", "93QIhAbxmdc")
    @Test fun test1b_altVideo() = extractAndVerify("[1b] jNQXAC9IVRw (Me at the zoo)", "jNQXAC9IVRw")
    @Test fun test2_embedUrl() = extractAndVerify("[2] embed", "27OZc-ku6is")
    @Test fun test3_countryRestricted() = extractAndVerify("[3] country-restricted", "l_98K4_6UQ0", allowRestricted = true)
}
