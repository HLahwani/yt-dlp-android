package com.ytdlpdroid.decipher

import com.ytdlpdroid.js.JsEngine
import com.ytdlpdroid.model.YTDLPError
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class SignatureDeciphererTest {

    private val playerJs: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("player_js_fragment.js")!!
            .bufferedReader().readText()
    }

    private fun reversingEngine(): JsEngine = mockk {
        every { execute(any(), any()) } answers { secondArg<String>().reversed() }
    }

    private fun cipher(sig: String, url: String): String {
        val encoded = URLEncoder.encode(url, "UTF-8")
        return "s=$sig&sp=sig&url=$encoded"
    }

    @Test
    fun `decrypts simple sig cipher and appends sig to url`() {
        val engine = reversingEngine()
        val decipherer = SignatureDecipherer(engine)
        val result = decipherer.decrypt(cipher("ABCDE", "https://example.com/video"), playerJs)
        assertEquals("https://example.com/video&sig=EDCBA", result)
    }

    @Test
    fun `uses sp field as query param name`() {
        val engine = reversingEngine()
        val decipherer = SignatureDecipherer(engine)
        val encoded = URLEncoder.encode("https://example.com/v", "UTF-8")
        val result = decipherer.decrypt("s=XY&sp=signature&url=$encoded", playerJs)
        assertTrue(result.contains("&signature=YX"))
    }

    @Test
    fun `defaults sp to sig when absent`() {
        val engine = reversingEngine()
        val decipherer = SignatureDecipherer(engine)
        val encoded = URLEncoder.encode("https://example.com/v", "UTF-8")
        val result = decipherer.decrypt("s=AB&url=$encoded", playerJs)
        assertTrue(result.contains("&sig=BA"))
    }

    @Test
    fun `throws DecipherFailed when player js has no sig function`() {
        val decipherer = SignatureDecipherer(mockk())
        assertThrows(YTDLPError.DecipherFailed::class.java) {
            decipherer.decrypt(cipher("SIG", "https://example.com"), "var unrelated=1;")
        }
    }

    @Test
    fun `throws DecipherFailed when cipher has no s field`() {
        val decipherer = SignatureDecipherer(mockk())
        assertThrows(YTDLPError.DecipherFailed::class.java) {
            decipherer.decrypt("sp=sig&url=https%3A%2F%2Fexample.com", playerJs)
        }
    }

    @Test
    fun `extracts sig function with helper from fixture`() {
        val engine = reversingEngine()
        val decipherer = SignatureDecipherer(engine)
        // Use a JS snippet containing only the sigWithHelper pattern
        val js = """
            var Tb={sw:function(a,b){var c=a[0];a[0]=a[b%a.length];a[b%a.length]=c},rv:function(a){a.reverse()}};
            function sigWithHelper(a){var b=a.split("");Tb.sw(b,52);Tb.rv(b);return b.join("")}
            a.sig||sigWithHelper(encSig)
        """.trimIndent()
        // Just verify no exception — engine mock handles execution
        val encoded = URLEncoder.encode("https://example.com/v", "UTF-8")
        val result = decipherer.decrypt("s=TEST&sp=sig&url=$encoded", js)
        assertTrue(result.startsWith("https://example.com/v&sig="))
    }
}
