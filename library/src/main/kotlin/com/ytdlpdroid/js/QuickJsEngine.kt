package com.ytdlpdroid.js

import com.ytdlpdroid.model.YTDLPError

internal class QuickJsEngine : JsEngine {

    init {
        System.loadLibrary("quickjs_bridge")
    }

    override fun execute(functionCode: String, argument: String): String {
        val wrappedCode = "var __result__ = $functionCode;"
        return executeNative(wrappedCode, argument)
            ?: throw YTDLPError.DecipherFailed("QuickJS returned null for argument: $argument")
    }

    private external fun executeNative(jsCode: String, argument: String): String?

    /**
     * Loads [playerJs] into a high-memory QuickJS runtime (128 MB), then evaluates
     * [discoveryScript] in that context and returns the string result.
     *
     * Use this for player JS files that use string-table obfuscation (e.g. player
     * version 25f11721) where the nsig function cannot be extracted by regex patterns.
     * [discoveryScript] should end with an expression that evaluates to the
     * transformed n-param string.
     */
    fun executeWithPlayerJs(playerJs: String, discoveryScript: String): String? =
        executeWithPlayerJsNative(playerJs, discoveryScript)
            ?.takeIf { it.isNotBlank() && it != "undefined" && it != "null" }

    private external fun executeWithPlayerJsNative(playerJs: String, discoveryScript: String): String?
}
