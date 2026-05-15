package com.ytdlpdroid.decipher

import com.ytdlpdroid.innertube.model.RawFormat
import com.ytdlpdroid.model.YTDLPError
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

class DecipherServiceTest {

    private val fakePlayerJs = "/* fake player js */"
    private val fakePlayerJsUrl = "/s/player/abc123/base.js"

    private fun makeRepo(): PlayerJsRepository = mockk {
        coEvery { fetchPlayerJs(fakePlayerJsUrl) } returns fakePlayerJs
    }

    private fun makeNParam(transform: (String) -> String = { it + "_n" }): NParamDecipherer =
        mockk { every { transform(any(), any()) } answers { transform(firstArg()) } }

    private fun makeSig(result: String = "https://example.com/v&sig=DECRYPTED"): SignatureDecipherer =
        mockk { every { decrypt(any(), any()) } returns result }

    private fun directFormat(url: String) = RawFormat(
        itag = 251, url = url, mimeType = "audio/webm; codecs=\"opus\"", bitrate = 130000)

    private fun cipherFormat(cipher: String) = RawFormat(
        itag = 251, url = null, signatureCipher = cipher, mimeType = "audio/webm; codecs=\"opus\"", bitrate = 130000)

    private fun emptyFormat() = RawFormat(
        itag = 251, url = null, signatureCipher = null, mimeType = "audio/webm; codecs=\"opus\"", bitrate = 130000)

    @Test
    fun `direct url — only n-param transform applied`() = runTest {
        val nParam = makeNParam { it.replace("n=old", "n=new") }
        val sig = makeSig()
        val service = DecipherService(makeRepo(), nParam, sig)
        val format = directFormat("https://example.com?itag=251&n=old")
        val result = service.buildPlayableUrl(format, fakePlayerJsUrl)
        assertEquals("https://example.com?itag=251&n=new", result)
        verify(exactly = 0) { sig.decrypt(any(), any()) }
    }

    @Test
    fun `signatureCipher — sig decrypt then n-param transform`() = runTest {
        val decryptedUrl = "https://example.com/v?n=old"
        val sig = makeSig(decryptedUrl)
        val nParam = makeNParam { it.replace("n=old", "n=new") }
        val service = DecipherService(makeRepo(), nParam, sig)
        val encoded = URLEncoder.encode(decryptedUrl, "UTF-8")
        val result = service.buildPlayableUrl(cipherFormat("s=SIG&sp=sig&url=$encoded"), fakePlayerJsUrl)
        assertEquals("https://example.com/v?n=new", result)
        verify(exactly = 1) { sig.decrypt(any(), any()) }
    }

    @Test
    fun `format with neither url nor cipher throws NoStreamsFound`() = runTest {
        val service = DecipherService(makeRepo(), makeNParam(), makeSig())
        assertThrows(YTDLPError.NoStreamsFound::class.java) {
            kotlinx.coroutines.runBlocking {
                service.buildPlayableUrl(emptyFormat(), fakePlayerJsUrl)
            }
        }
    }

    @Test
    fun `fetches player js from repo`() = runTest {
        val repo = makeRepo()
        val service = DecipherService(repo, makeNParam(), makeSig())
        service.buildPlayableUrl(directFormat("https://example.com?n=x"), fakePlayerJsUrl)
        io.mockk.coVerify(exactly = 1) { repo.fetchPlayerJs(fakePlayerJsUrl) }
    }
}
