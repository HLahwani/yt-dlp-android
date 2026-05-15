package com.ytdlpdroid.decipher

import com.ytdlpdroid.js.JsEngine
import com.ytdlpdroid.model.YTDLPError
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NParamDeciphererTest {

    private val playerJs: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("player_js_fragment.js")!!
            .bufferedReader().readText()
    }

    // JsEngine mock that reverses the string (matches nsig_fn in the fixture)
    private fun reversingEngine(): JsEngine = mockk {
        every { execute(any(), any()) } answers { secondArg<String>().reversed() }
    }

    @Test
    fun `url without n param is returned unchanged`() {
        val engine = mockk<JsEngine>(relaxed = true)
        val decipherer = NParamDecipherer(engine)
        val url = "https://example.com/video?itag=251&expire=9999"
        assertEquals(url, decipherer.transform(url, "any js"))
        verify(exactly = 0) { engine.execute(any(), any()) }
    }

    @Test
    fun `transforms n param using extracted function`() {
        val engine = reversingEngine()
        val decipherer = NParamDecipherer(engine)
        val url = "https://example.com/video?itag=251&n=hello&expire=9999"
        val result = decipherer.transform(url, playerJs)
        assertEquals("https://example.com/video?itag=251&n=olleh&expire=9999", result)
    }

    @Test
    fun `n param at end of url is replaced correctly`() {
        val engine = reversingEngine()
        val decipherer = NParamDecipherer(engine)
        val url = "https://example.com/video?itag=251&expire=9999&n=world"
        val result = decipherer.transform(url, playerJs)
        assertEquals("https://example.com/video?itag=251&expire=9999&n=dlrow", result)
    }

    @Test
    fun `function extracted only once for same player js`() {
        val engine = reversingEngine()
        val decipherer = NParamDecipherer(engine)
        val url = "https://example.com/video?n=abc"
        decipherer.transform(url, playerJs)
        decipherer.transform(url, playerJs)
        // 2 validation calls (isPlausibleNsigFunction with 2 test inputs) + 2 transform calls = 4 total.
        // Extraction + validation happen only once (result is cached for the player JS hash).
        verify(exactly = 4) { engine.execute(any(), any()) }
    }

    @Test
    fun `player js with no nsig pattern throws DecipherFailed`() {
        val decipherer = NParamDecipherer(mockk())
        assertThrows(YTDLPError.DecipherFailed::class.java) {
            decipherer.transform("https://example.com?n=abc", "var unrelated = 1;")
        }
    }

    @Test
    fun `resolves array-element function reference from fixture`() {
        // The fixture also contains the nsig_arr[0] pattern; verify extraction finds a function
        val engine = reversingEngine()
        val decipherer = NParamDecipherer(engine)
        // Use a JS snippet where only the array-ref pattern is present
        val js = """
            var nsig_named=function(a){return a.split("").reverse().join("")};
            var nsig_arr=[nsig_named];
            (b=b.get("n"))&&(b=nsig_arr[0](b));
        """.trimIndent()
        val result = decipherer.transform("https://example.com?n=hello", js)
        assertEquals("https://example.com?n=olleh", result)
    }
}
