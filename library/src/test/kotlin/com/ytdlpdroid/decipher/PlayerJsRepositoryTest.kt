package com.ytdlpdroid.decipher

import com.ytdlpdroid.model.YTDLPError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class PlayerJsRepositoryTest {

    @get:Rule val tmpDir = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var repo: PlayerJsRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.newBuilder()
                    .scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(req.newBuilder().url(url).build())
            }
            .build()
        repo = PlayerJsRepository(tmpDir.newFolder("playerjs"), okHttp)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `fetches player js from network and writes to disk`() = runBlocking {
        server.enqueue(MockResponse().setBody("var playerJs=1;"))
        val result = repo.fetchPlayerJs("/s/player/abc/base.js")
        assertEquals("var playerJs=1;", result)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `second call with same url reads from memory without network`() = runBlocking {
        server.enqueue(MockResponse().setBody("var playerJs=1;"))
        repo.fetchPlayerJs("/s/player/abc/base.js")
        val result = repo.fetchPlayerJs("/s/player/abc/base.js")
        assertEquals("var playerJs=1;", result)
        assertEquals(1, server.requestCount) // only one network call
    }

    @Test
    fun `reads from disk cache on cold start without network`() = runBlocking {
        server.enqueue(MockResponse().setBody("var cached=1;"))
        repo.fetchPlayerJs("/s/player/abc/base.js")

        // Create a fresh repo instance pointing at the same cache dir — no memory cache
        val coldRepo = PlayerJsRepository(tmpDir.root.resolve("playerjs"),
            OkHttpClient.Builder().build())
        val result = coldRepo.fetchPlayerJs("/s/player/abc/base.js")
        assertEquals("var cached=1;", result)
        assertEquals(1, server.requestCount) // no new network call
    }

    @Test
    fun `http error throws PlayerJsFetchFailed`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertThrows(YTDLPError.PlayerJsFetchFailed::class.java) {
            runBlocking { repo.fetchPlayerJs("/s/player/abc/base.js") }
        }
    }

    @Test
    fun `extractSignatureTimestamp returns sts from player JS`() {
        val js = "some code; a.signatureTimestamp=19569; more code;"
        val result = repo.extractSignatureTimestamp(js)
        assertEquals(19569, result)
    }

    @Test
    fun `extractSignatureTimestamp handles sts colon form`() {
        val js = """var x={sts:12345,other:1}"""
        assertEquals(12345, repo.extractSignatureTimestamp(js))
    }

    @Test
    fun `extractSignatureTimestamp returns null when not present`() {
        assertNull(repo.extractSignatureTimestamp("no sts here at all"))
    }

    @Test
    fun `evict old cache keeps only two newest files`() {
        val cacheDir = tmpDir.newFolder("evict")
        val files = (1..4).map { cacheDir.resolve("file$it.playerjs").also { f -> f.writeText("x") } }
        // Make them different modification times
        files.forEachIndexed { i, f -> f.setLastModified(1000L * (i + 1)) }
        val evictRepo = PlayerJsRepository(cacheDir, OkHttpClient())
        evictRepo.evictOldCache()
        val remaining = cacheDir.listFiles()!!
        assertEquals(2, remaining.size)
        // The two newest files should survive
        assertTrue(remaining.any { it.name == "file4.playerjs" })
        assertTrue(remaining.any { it.name == "file3.playerjs" })
    }
}
