package com.ytdlpdroid.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ytdlpdroid.decipher.PlayerJsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlayerJsAnalysisTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun repo() = PlayerJsRepository(
        File(ctx.cacheDir, "ytdlpdroid_analysis").also { it.mkdirs() }
    )

    @Test
    fun findP7AndZOFunctions() {
        val playerJsUrl = "/s/player/25f11721/player-plasma-es6-en_US.vflset/base.js"
        val pjs = runBlocking { repo().fetchPlayerJs(playerJsUrl) }
        println("length=${pjs.length}\n")

        // ---- 1. p7 function (URL parser, called from Fv(8,5464,...)) ----
        println("=== p7 function ===")
        Regex("""(?:^|[^a-zA-Z0-9_])p7\s*=\s*function[^{]{0,50}\{[^§]{0,3000}""").find(pjs)?.let { m ->
            println(m.value.take(3000))
        } ?: println("p7 not found")

        // ---- 2. ZO function (URL builder, called from Nf()) ----
        println("\n\n=== ZO function ===")
        Regex("""(?:^|[^a-zA-Z0-9_])ZO\s*=\s*function[^{]{0,50}\{[^§]{0,3000}""").find(pjs)?.let { m ->
            println(m.value.take(3000))
        } ?: println("ZO not found")

        // ---- 3. Sr function (URL serializer, calls Fv(15,5471,...)) ----
        println("\n\n=== Sr function ===")
        Regex("""(?:^|[^a-zA-Z0-9_])Sr\s*=\s*function[^{]{0,50}\{[^§]{0,2000}""").find(pjs)?.let { m ->
            println(m.value.take(2000))
        } ?: println("Sr not found")

        // ---- 4. k2 function (called from kU) ----
        println("\n\n=== k2 function ===")
        Regex("""(?:^|[^a-zA-Z0-9_])k2\s*=\s*function[^{]{0,50}\{[^§]{0,2000}""").find(pjs)?.let { m ->
            println(m.value.take(2000))
        } ?: println("k2 not found")

        // ---- 5. si function (called from wD) ----
        println("\n\n=== si function ===")
        Regex("""(?:^|[^a-zA-Z0-9_$])si\s*=\s*function[^{]{0,50}\{[^§]{0,1000}""").find(pjs)?.let { m ->
            println(m.value.take(1000))
        } ?: println("si not found")

        // ---- 6. Try to find n-param in URL-related operations ----
        // Search for where y[19] (="n") appears with y[31] (="get") or near "set"
        println("\n\n=== Any reference to n-param operations ===")
        // Search 500-char window around each occurrence of y[19]
        Regex("""y\[19\]""").findAll(pjs).take(10).forEachIndexed { i, m ->
            val s = maxOf(0, m.range.first - 200)
            val e = minOf(pjs.length, m.range.last + 200)
            println("\n[y19-$i pos=${m.range.first}]")
            println(pjs.substring(s, e))
        }
    }
}
