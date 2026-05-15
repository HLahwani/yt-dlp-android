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
}
