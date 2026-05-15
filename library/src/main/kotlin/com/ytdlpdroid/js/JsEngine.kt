package com.ytdlpdroid.js

fun interface JsEngine {
    fun execute(functionCode: String, argument: String): String
}
