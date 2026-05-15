package com.ytdlpdroid.decipher

import com.ytdlpdroid.innertube.model.RawFormat
import com.ytdlpdroid.model.YTDLPError

internal class DecipherService(
    private val playerJsRepo: PlayerJsRepository,
    private val nParamDecipherer: NParamDecipherer,
    private val signatureDecipherer: SignatureDecipherer,
) {
    suspend fun buildPlayableUrl(format: RawFormat, playerJsUrl: String): String {
        val playerJs = playerJsRepo.fetchPlayerJs(playerJsUrl)

        val rawUrl = when {
            format.url != null -> format.url
            format.signatureCipher != null ->
                signatureDecipherer.decrypt(format.signatureCipher, playerJs)
            else -> throw YTDLPError.NoStreamsFound(
                "itag ${format.itag} has neither url nor signatureCipher")
        }

        return nParamDecipherer.transform(rawUrl, playerJs)
    }
}
