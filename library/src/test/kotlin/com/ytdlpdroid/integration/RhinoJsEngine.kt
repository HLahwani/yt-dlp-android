package com.ytdlpdroid.integration

import com.ytdlpdroid.js.JsEngine
import com.ytdlpdroid.model.YTDLPError
import org.mozilla.javascript.Context
import org.mozilla.javascript.RhinoException

/**
 * JVM-compatible JS engine backed by Mozilla Rhino.
 * Used only in integration tests — the production engine is QuickJS (NDK).
 */
internal class RhinoJsEngine : JsEngine {

    override fun execute(functionCode: String, argument: String): String {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1          // interpreter mode — required on restricted VMs
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initSafeStandardObjects()

            // Mirror the same wrapping QuickJsEngine uses so extracted function code is identical
            val escaped = argument.replace("\\", "\\\\").replace("\"", "\\\"")
            val script = "var __result__ = $functionCode; __result__(\"$escaped\");"

            val result = cx.evaluateString(scope, script, "<nsig>", 1, null)
            return Context.toString(result)
                ?: throw YTDLPError.DecipherFailed("Rhino returned null for argument: $argument")
        } catch (e: RhinoException) {
            throw YTDLPError.DecipherFailed("Rhino error: ${e.details()}")
        } finally {
            Context.exit()
        }
    }
}
