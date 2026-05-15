package com.ytdlpdroid.decipher

import com.ytdlpdroid.js.JsEngine
import com.ytdlpdroid.model.YTDLPError

internal class NParamDecipherer(private val jsEngine: JsEngine) {

    // playerJs.hashCode() → extracted function code
    private val functionCache = HashMap<Int, String>(4)

    fun transform(url: String, playerJs: String): String {
        // Group 1 = prefix (?n= or &n=), group 2 = value
        val nValue = N_PARAM_RE.find(url)?.groupValues?.get(2) ?: return url
        val fnCode = functionCache.getOrPut(playerJs.hashCode()) { extractNFunction(playerJs) }
        val transformed = jsEngine.execute(fnCode, nValue)
        return url.replace(N_PARAM_RE) { m -> m.groupValues[1] + transformed }
    }

    private fun extractNFunction(js: String): String {
        val fnName = extractNFunctionName(js)
            ?: throw YTDLPError.DecipherFailed("Could not find nsig function name in player JS")
        return JsFunctionExtractor.extractFunctionBody(js, fnName)
            ?: throw YTDLPError.DecipherFailed("Could not extract nsig function body for: $fnName")
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

    companion object {
        // Group 1 = "?n=" or "&n=", group 2 = the token value
        private val N_PARAM_RE = Regex("""([?&]n=)([^&]+)""")

        private val N_FUNC_NAME_PATTERNS = listOf(
            // Modern: .get("n"))&&(b=nfn(b)  or  .get("n"))&&(b=arr[0](b)
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9_$]+=([a-zA-Z0-9_\[\]$]{2,40})\("""),
            // Alternate: x&&(b=nfn(b)
            Regex("""[a-zA-Z0-9_$]+&&\([a-zA-Z0-9_$]+=([a-zA-Z0-9_\[\]$]{2,40})\([a-zA-Z0-9_$]"""),
            // Older: .get("n"))&&(b=arr[idx](b)  — explicit array index pattern
            Regex("""\.get\("n"\)\)&&\([a-zA-Z_$]=([a-zA-Z0-9_$]{2,4})\["""),
        )
    }
}
