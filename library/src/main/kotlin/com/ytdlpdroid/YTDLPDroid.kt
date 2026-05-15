package com.ytdlpdroid

import com.ytdlpdroid.cache.ExtractionCache
import com.ytdlpdroid.decipher.DecipherService
import com.ytdlpdroid.decipher.NParamDecipherer
import com.ytdlpdroid.decipher.PlayerJsRepository
import com.ytdlpdroid.decipher.SignatureDecipherer
import com.ytdlpdroid.extractor.VideoIdParser
import com.ytdlpdroid.extractor.YouTubeExtractor
import com.ytdlpdroid.innertube.InnerTubeClient
import com.ytdlpdroid.js.JsEngine
import com.ytdlpdroid.js.QuickJsEngine
import com.ytdlpdroid.model.ExtractionOptions
import com.ytdlpdroid.model.StreamResult
import com.ytdlpdroid.network.HttpClientProvider
import okhttp3.OkHttpClient
import java.io.File

class YTDLPDroid private constructor(
    private val extractor: YouTubeExtractor,
) {
    /**
     * Extracts playable stream URLs for [url] (YouTube URL or bare video ID).
     * Must be called from a coroutine. Throws [com.ytdlpdroid.model.YTDLPError] subclasses on failure.
     */
    suspend fun extract(
        url: String,
        options: ExtractionOptions = ExtractionOptions(),
    ): StreamResult {
        val videoId = VideoIdParser.parse(url)
        return extractor.extract(videoId, options)
    }

    class Builder(private val cacheDir: File) {
        private var httpClient: OkHttpClient? = null
        private var jsEngine: JsEngine? = null
        private var memoryCacheCapacity: Int = 30

        /** Provide a custom OkHttpClient (e.g. for logging or proxy). */
        fun httpClient(client: OkHttpClient) = apply { this.httpClient = client }

        /** Override the JS engine. Defaults to QuickJsEngine (NDK). */
        fun jsEngine(engine: JsEngine) = apply { this.jsEngine = engine }

        /** Max number of extraction results held in memory. Default: 30. */
        fun memoryCacheCapacity(n: Int) = apply { this.memoryCacheCapacity = n }

        fun build(): YTDLPDroid {
            val playerJsCacheDir = File(cacheDir, "ytdlpdroid_playerjs").also { it.mkdirs() }
            val http = httpClient ?: HttpClientProvider.client
            val js = jsEngine ?: QuickJsEngine()

            val playerJsRepo = PlayerJsRepository(playerJsCacheDir, http)
            val decipherService = DecipherService(
                playerJsRepo,
                NParamDecipherer(js),
                SignatureDecipherer(js),
            )
            return YTDLPDroid(
                YouTubeExtractor(
                    InnerTubeClient(http),
                    playerJsRepo,
                    decipherService,
                    ExtractionCache(memoryCacheCapacity),
                )
            )
        }
    }
}
