package com.ytdlpdroid.innertube

import com.ytdlpdroid.model.YTDLPError
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class InnerTubeClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: InnerTubeClient

    // ANDROID config avoids triggering ensureSession() (WEB-only) which would consume mock responses
    private val testConfig = InnerTubeClientConfig.ANDROID

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        client = InnerTubeClient(okHttp)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun loadFixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!
            .bufferedReader().readText()

    private fun baseUrl() = server.url("/youtubei/v1/player").toString()

    // Patch the request URL to point at MockWebServer by subclassing config
    private fun clientPointing(config: InnerTubeClientConfig = testConfig): InnerTubeClient {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val newUrl = original.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(original.newBuilder().url(newUrl).build())
            }
            .build()
        return InnerTubeClient(okHttp)
    }

    @Test
    fun `200 with ok fixture parses streaming data`() = runTest {
        server.enqueue(MockResponse().setBody(loadFixture("player_response_ok.json")))
        val c = clientPointing()
        val response = c.fetchPlayerResponse("jNQXAC9IVRw", testConfig)
        assertEquals("OK", response.playabilityStatus.status)
        assertNotNull(response.streamingData)
    }

    @Test
    fun `LOGIN_REQUIRED response is returned without throwing`() = runTest {
        server.enqueue(MockResponse().setBody(loadFixture("player_response_login_required.json")))
        val response = clientPointing().fetchPlayerResponse("videoId1", testConfig)
        assertEquals("LOGIN_REQUIRED", response.playabilityStatus.status)
    }

    @Test
    fun `HTTP 500 throws NetworkError`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThrows(YTDLPError.NetworkError::class.java) {
            kotlinx.coroutines.runBlocking { clientPointing().fetchPlayerResponse("vid", testConfig) }
        }
    }

    @Test
    fun `429 is retried and succeeds on third attempt`() = runTest {
        val retryClient = run {
            val okHttp = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .addInterceptor(com.ytdlpdroid.network.RetryInterceptor(
                    maxRetries = 3, retryOnStatusCodes = setOf(429, 500, 502, 503)))
                .addInterceptor { chain ->
                    val req = chain.request()
                    val url = req.url.newBuilder()
                        .scheme("http").host(server.hostName).port(server.port).build()
                    chain.proceed(req.newBuilder().url(url).build())
                }
                .build()
            InnerTubeClient(okHttp)
        }
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setBody(loadFixture("player_response_ok.json")))

        val response = retryClient.fetchPlayerResponse("jNQXAC9IVRw", testConfig)
        assertEquals("OK", response.playabilityStatus.status)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `invalid JSON response throws an exception`() {
        server.enqueue(MockResponse().setBody("not valid json {{ }}"))
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { clientPointing().fetchPlayerResponse("vid", testConfig) }
        }
    }
}
