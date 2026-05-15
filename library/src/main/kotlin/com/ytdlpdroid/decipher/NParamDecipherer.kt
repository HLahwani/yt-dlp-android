package com.ytdlpdroid.decipher

import com.ytdlpdroid.js.JsEngine
import com.ytdlpdroid.js.QuickJsEngine
import com.ytdlpdroid.model.YTDLPError

internal class NParamDecipherer(private val jsEngine: JsEngine) {

    // playerJs.hashCode() → extracted function code (or FULL_PLAYER_JS_SENTINEL)
    private val functionCache = HashMap<Int, String>(4)

    fun transform(url: String, playerJs: String): String {
        // Group 1 = prefix (?n= or &n=), group 2 = the token value
        val nValue = N_PARAM_RE.find(url)?.groupValues?.get(2) ?: return url
        val fnCode = functionCache.getOrPut(playerJs.hashCode()) { extractNFunction(playerJs) }

        val transformed = if (fnCode == FULL_PLAYER_JS_SENTINEL) {
            // Regex patterns failed — execute the full player JS in QuickJS with a
            // behavioral discovery script to find and call the nsig function.
            transformViaFullPlayerJs(playerJs, nValue)
        } else {
            jsEngine.execute(fnCode, nValue)
        }

        return url.replace(N_PARAM_RE) { m -> m.groupValues[1] + transformed }
    }

    private fun extractNFunction(js: String): String {
        // Try fast regex-based extraction first (works for older players and
        // players that don't use string-table obfuscation).
        val fnName = extractNFunctionName(js)
        if (fnName != null) {
            val body = JsFunctionExtractor.extractFunctionBody(js, fnName)
            if (body != null && isPlausibleNsigFunction(body)) return body
        }
        // Fall back to full-player-JS execution for obfuscated players
        // (e.g. player 25f11721 which uses string-table XOR dispatch).
        return FULL_PLAYER_JS_SENTINEL
    }

    /**
     * Validates extracted function code by running it with a test input.
     * Rejects functions that:
     *  - Return `undefined` or the same string (not a transformer)
     *  - Return pure hex strings (hash-function false positives like `88cbd254cf8a0353`)
     *  - Return strings outside plausible n-param length (5-25 chars)
     */
    private fun isPlausibleNsigFunction(fnCode: String): Boolean {
        val testInput1 = "abcdefghijklmnopq"  // 17-char alphanumeric test
        val testInput2 = "qponmlkjihgfedcba"  // reversed — different output proves it's a real transform
        return try {
            val r1 = jsEngine.execute(fnCode, testInput1) ?: return false
            val r2 = jsEngine.execute(fnCode, testInput2) ?: return false
            val base64url = Regex("[A-Za-z0-9_\\-]+")
            val pureHex = Regex("[0-9a-f]+")  // all-lowercase hex = hash function, not nsig
            r1 != testInput1 && r1 != "undefined" && r1 != "null"
                    && '=' !in r1 && '+' !in r1 && '/' !in r1
                    && r1.length in 5..25 && r1.length <= testInput1.length + 2
                    && base64url.matches(r1) && !pureHex.matches(r1)
                    && r2 != testInput2 && r2.length in 5..25
                    && base64url.matches(r2) && !pureHex.matches(r2)
                    && r1 != r2
        } catch (_: Exception) {
            false
        }
    }

    private fun extractNFunctionName(js: String): String? {
        for (pattern in N_FUNC_NAME_PATTERNS) {
            val raw = pattern.find(js)?.groupValues?.get(1) ?: continue
            return if ('[' in raw) resolveArrayElement(js, raw) else raw
        }
        return null
    }

    private fun resolveArrayElement(js: String, ref: String): String? {
        val arrName = ref.substringBefore('[')
        val idx = ref.substringAfter('[').substringBefore(']').toIntOrNull() ?: 0
        return Regex("""var\s+${Regex.escape(arrName)}\s*=\s*\[([^\]]+)]""")
            .find(js)?.groupValues?.get(1)
            ?.split(',')?.getOrNull(idx)?.trim()
    }

    /**
     * Loads the full player JS into a high-memory QuickJS context (128 MB) and
     * uses a behavioral discovery script to find the nsig function.
     *
     * The discovery script tries every 2-4-character global function:
     *   - If calling it with a test string returns a DIFFERENT, comma-free string
     *     of plausible length (5-25 chars), it is treated as the nsig function.
     *
     * This handles string-table-obfuscated players where the nsig function is
     * unreachable via static regex patterns.
     */
    private fun transformViaFullPlayerJs(playerJs: String, nValue: String): String {
        val engine = jsEngine as? QuickJsEngine
            ?: throw YTDLPError.DecipherFailed(
                "Full-player-JS nsig requires QuickJsEngine; got ${jsEngine::class.simpleName}")

        val escapedN = nValue.replace("\\", "\\\\").replace("\"", "\\\"")

        // Minimal browser stubs — the player JS accesses DOM/window at the top level.
        val browserShim = """
var self=globalThis;
if(typeof document==='undefined'){var document={createElement:function(){return{style:{},setAttribute:function(){},appendChild:function(){},addEventListener:function(){},classList:{add:function(){},remove:function(){}}};},getElementById:function(){return null;},querySelector:function(){return null;},querySelectorAll:function(){return{length:0,forEach:function(){}};},head:{appendChild:function(){},querySelector:function(){return null;},insertBefore:function(){}},body:{appendChild:function(){},insertBefore:function(){}},addEventListener:function(){},createTextNode:function(t){return{textContent:t};}}}
if(typeof navigator==='undefined'){var navigator={userAgent:'Mozilla/5.0',platform:'Win32',language:'en-US',languages:['en-US'],cookieEnabled:true}}
if(typeof location==='undefined'){var location={href:'https://www.youtube.com/',origin:'https://www.youtube.com',hostname:'www.youtube.com',protocol:'https:',pathname:'/',search:'',hash:''}}
if(typeof localStorage==='undefined'){var localStorage={getItem:function(){return null;},setItem:function(){},removeItem:function(){},clear:function(){},length:0}}
if(typeof sessionStorage==='undefined'){var sessionStorage={getItem:function(){return null;},setItem:function(){},removeItem:function(){}}}
if(typeof performance==='undefined'){var performance={now:function(){return Date.now();},timing:{navigationStart:0}}}
if(typeof XMLHttpRequest==='undefined'){var XMLHttpRequest=function(){this.open=function(){};this.send=function(){};this.setRequestHeader=function(){};this.addEventListener=function(){};}}
if(typeof fetch==='undefined'){var fetch=function(){return Promise.reject(new Error('no fetch'));}}
if(typeof MutationObserver==='undefined'){var MutationObserver=function(fn){this.observe=function(){};this.disconnect=function(){};}}
if(typeof requestAnimationFrame==='undefined'){var requestAnimationFrame=function(fn){return 0;};}
if(typeof screen==='undefined'){var screen={width:1920,height:1080,colorDepth:24};}
if(typeof crypto==='undefined'){var crypto={getRandomValues:function(a){for(var i=0;i<a.length;i++)a[i]=Math.floor(Math.random()*256);return a;}};}
""".trimIndent()

        // The player JS: (function(g){var window=this;...})(_yt_player)
        // Inside the IIFE: g=_yt_player, window=this(=globalThis in QuickJS non-strict).
        // The player JS is `var _yt_player={};(function(g){...})(_yt_player)`.
        // Everything assigned to `g` inside the IIFE is on the GLOBAL `_yt_player`.
        // After the IIFE runs (even if it throws partway), `_yt_player` has all
        // functions assigned to `g` before the crash (~178K bytes in).
        // The discovery script runs AFTER the player JS and reads _yt_player directly.
        val discovery = buildDiscoveryScript(escapedN)
        return engine.executeWithPlayerJs(browserShim + "\n" + playerJs, discovery)
            ?: nValue
    }

    /**
     * Builds the discovery script that runs AFTER the player JS try-catch.
     * Uses `_yt_player` (the global object that was passed as `g` to the IIFE)
     * to access all player utility functions without needing to inject inside the IIFE.
     */
    private fun buildDiscoveryScript(escapedN: String): String {
        val ti  = "abcdefghijklmnopq"
        val ti2 = "qponmlkjihgfedcbaZ"
        return """
(function() {
    var nVal="$escapedN",ti="$ti",ti2="$ti2",res=nVal;
    function ok(r,i){return typeof r==='string'&&r!==i&&r!=='undefined'&&r!=='null'&&r.indexOf('=')<0&&r.indexOf('+')<0&&r.indexOf('/')<0&&r.indexOf(':')<0&&r.length>=5&&r.length<=i.length+2&&/^[A-Za-z0-9_-]+${'$'}/.test(r)&&!/^[0-9a-f]+${'$'}/.test(r);}
    function isRealNsig(fn){try{var r1=fn(ti),r2=fn(ti2);return ok(r1,ti)&&ok(r2,ti2)&&r1!==r2;}catch(e){return false;}}
    try{
        // _yt_player is the `g` param of the IIFE — all player functions assigned to g are here.
        var g=globalThis._yt_player||{};
        var keys=Object.keys(g).filter(function(k){return k.length>=2&&k.length<=4&&typeof g[k]==='function';});
        for(var i=0;i<keys.length;i++){var k=keys[i];if(isRealNsig(g[k])){res=g[k](nVal);break;}}
    }catch(e){}
    return res;
})()
""".trimIndent()
    }

    companion object {
        // Sentinel stored in functionCache when regex patterns failed;
        // signals that full-player-JS execution should be used.
        private const val FULL_PLAYER_JS_SENTINEL = "__FULL_PLAYER_JS__"

        // Group 1 = "?n=" or "&n=", group 2 = the token value
        private val N_PARAM_RE = Regex("""([?&]n=)([^&]+)""")

        private val N_FUNC_NAME_PATTERNS = listOf(
            // Modern: .get("n"))&&(b=nfn(b)  or  .get("n"))&&(b=arr[0](b)
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9_$]+=([a-zA-Z0-9_\[\]$]{2,40})\("""),
            // Alternate: x&&(b=nfn(b)
            Regex("""[a-zA-Z0-9_$]+&&\([a-zA-Z0-9_$]+=([a-zA-Z0-9_\[\]$]{2,40})\([a-zA-Z0-9_$]"""),
            // Older: .get("n"))&&(b=arr[idx](b)  — explicit array index pattern
            Regex("""\.get\("n"\)\)&&\([a-zA-Z_$]=([a-zA-Z0-9_$]{2,4})\["""),
            // yt-dlp heuristic: nsig function body always starts with var b=a.split("")
            Regex("""([a-zA-Z0-9_$]{3,})\s*=\s*function\s*\([a-zA-Z_$]\)\s*\{var\s+[a-zA-Z_$]\s*=\s*[a-zA-Z_$]\.split\(""\)"""),
        )
    }
}
