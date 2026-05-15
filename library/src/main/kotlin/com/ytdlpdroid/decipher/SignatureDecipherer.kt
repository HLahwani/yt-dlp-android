package com.ytdlpdroid.decipher

import com.ytdlpdroid.js.JsEngine
import com.ytdlpdroid.model.YTDLPError
import java.net.URLDecoder

internal class SignatureDecipherer(private val jsEngine: JsEngine) {

    // playerJs.hashCode() → extracted function code (ready to pass to JsEngine)
    private val functionCache = HashMap<Int, String>(2)

    fun decrypt(signatureCipher: String, playerJs: String): String {
        val params = signatureCipher.split('&').associate {
            val eq = it.indexOf('=')
            it.substring(0, eq) to it.substring(eq + 1)
        }
        val encSig = params["s"]
            ?: throw YTDLPError.DecipherFailed("Missing 's' in signatureCipher")
        val sp = params["sp"] ?: "sig"
        val baseUrl = params["url"]?.let { URLDecoder.decode(it, "UTF-8") }
            ?: throw YTDLPError.DecipherFailed("Missing 'url' in signatureCipher")

        val fnCode = functionCache.getOrPut(playerJs.hashCode()) { extractSigFunctionCode(playerJs) }
        val decryptedSig = jsEngine.execute(fnCode, encSig)
        return "$baseUrl&$sp=$decryptedSig"
    }

    private fun extractSigFunctionCode(js: String): String {
        val fnName = SIG_FUNC_PATTERNS.firstNotNullOfOrNull { it.find(js)?.groupValues?.get(1) }
            ?: throw YTDLPError.DecipherFailed("Sig function name not found in player JS")

        val mainFn = JsFunctionExtractor.extractFunctionBody(js, fnName)
            ?: throw YTDLPError.DecipherFailed("Could not extract sig function body for: $fnName")

        // The sig function typically delegates to a helper object — include it in the returned code.
        val helperName = Regex("""([a-zA-Z0-9$]{2,4})\.[a-zA-Z0-9$]+\(""").find(mainFn)
            ?.groupValues?.get(1)

        val helperObj = helperName?.let { JsFunctionExtractor.extractObjectLiteral(js, it) }

        return if (helperObj != null) {
            // Wrap in IIFE so the helper is in scope when the returned function is invoked.
            "(function(){var $helperName=$helperObj;\nreturn $mainFn;})()"
        } else {
            mainFn
        }
    }

    companion object {
        private val SIG_FUNC_PATTERNS = listOf(
            Regex("""\.sig\|\|([a-zA-Z0-9$]+)\("""),
            Regex("""\.signature\s*=\s*([a-zA-Z0-9$]+)\("""),
            Regex(""""signature"\s*,\s*([a-zA-Z0-9$]+)\("""),
        )
    }
}
