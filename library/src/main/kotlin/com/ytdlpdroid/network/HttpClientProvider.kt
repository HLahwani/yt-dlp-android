package com.ytdlpdroid.network

import okhttp3.ConnectionPool
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

internal object HttpClientProvider {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor(maxRetries = 3, retryOnStatusCodes = setOf(429, 500, 502, 503)))
            .build()
    }
}

internal class RetryInterceptor(
    private val maxRetries: Int,
    private val retryOnStatusCodes: Set<Int>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var attempt = 0
        var lastResponse: Response? = null
        while (attempt <= maxRetries) {
            val response = chain.proceed(chain.request())
            if (response.code !in retryOnStatusCodes) return response
            lastResponse?.close()
            lastResponse = response
            attempt++
            if (attempt <= maxRetries) Thread.sleep(500L * attempt) // 0.5s, 1s, 1.5s backoff
        }
        return lastResponse!!
    }
}
