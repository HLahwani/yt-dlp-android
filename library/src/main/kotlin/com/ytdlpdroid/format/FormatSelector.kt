package com.ytdlpdroid.format

import com.ytdlpdroid.innertube.model.RawFormat
import com.ytdlpdroid.model.ExtractionOptions
import com.ytdlpdroid.model.StreamFormat
import com.ytdlpdroid.model.YTDLPError

internal object FormatSelector {

    data class Selection(
        val video: StreamFormat?,
        val audio: StreamFormat,
        val muxed: StreamFormat?,
    )

    /**
     * True when the process is running on a 32-bit x86 device with no ARM ABI support.
     * libgav1 (Media3's AV1 software decoder) crashes on initialisation on x86-32, so
     * we automatically downgrade AV1 to a lower priority on those devices.
     * The check is done once at class-load time and returns false on the JVM (unit tests).
     */
    private val av1UnsafeOnThisDevice: Boolean = runCatching {
        val abis = android.os.Build.SUPPORTED_ABIS
        abis.any { it == "x86" } && abis.none { it == "arm64-v8a" || it == "armeabi-v7a" }
    }.getOrDefault(false)

    fun select(
        formats: List<Pair<RawFormat, String>>,
        options: ExtractionOptions,
    ): Selection {
        // Video-only adaptive: has width, no audioSampleRate
        val videoFormats = formats
            .filter { (f, _) -> MimeTypeParser.isVideo(f.mimeType) && f.width != null && f.audioSampleRate == null }
            .map { (f, url) -> toStreamFormat(f, url) }

        // Audio-only adaptive
        val audioFormats = formats
            .filter { (f, _) -> MimeTypeParser.isAudio(f.mimeType) }
            .map { (f, url) -> toStreamFormat(f, url) }

        // Muxed (video + audio in one stream)
        val muxedFormats = formats
            .filter { (f, _) -> MimeTypeParser.isVideo(f.mimeType) && f.audioSampleRate != null }
            .map { (f, url) -> toStreamFormat(f, url) }

        val bestMuxed = muxedFormats.maxByOrNull { it.height ?: 0 }
        val bestAudio = selectBestAudio(audioFormats, options)

        return if (bestAudio != null) {
            Selection(
                video = selectBestVideo(videoFormats, options),
                audio = bestAudio,
                muxed = if (options.includeMuxedFallback) bestMuxed else null,
            )
        } else {
            // No audio-only adaptive streams — fall back to best muxed (contains both audio
            // and video). video=null signals the caller to use the muxed stream for playback.
            Selection(
                video = null,
                audio = bestMuxed ?: throw YTDLPError.NoStreamsFound("No audio-only adaptive stream found"),
                muxed = bestMuxed,
            )
        }
    }

    private fun selectBestVideo(formats: List<StreamFormat>, opts: ExtractionOptions): StreamFormat? {
        var candidates = formats
            .filter { opts.maxVideoHeight == null || (it.height ?: 0) <= opts.maxVideoHeight }

        if (opts.preferH264) {
            val h264Only = candidates.filter {
                MimeTypeParser.detectVideoCodec(it.mimeType) == VideoCodec.H264
            }
            if (h264Only.isNotEmpty()) candidates = h264Only
        } else if (av1UnsafeOnThisDevice) {
            // libgav1 (AV1 software decoder) crashes on x86-32 — skip AV1 automatically.
            // Fall through to AV1 only if it is the sole codec available.
            val nonAv1 = candidates.filter {
                MimeTypeParser.detectVideoCodec(it.mimeType) != VideoCodec.AV1
            }
            if (nonAv1.isNotEmpty()) candidates = nonAv1
        }

        return candidates.maxWithOrNull(
            compareBy<StreamFormat> { it.height ?: 0 }
                .thenBy { it.fps ?: 0 }
                .thenBy { MimeTypeParser.detectVideoCodec(it.mimeType).priority }
                .thenBy { it.bitrate }
        )
    }

    private fun selectBestAudio(formats: List<StreamFormat>, opts: ExtractionOptions): StreamFormat? =
        formats.maxWithOrNull(
            compareBy<StreamFormat> {
                val codec = MimeTypeParser.detectAudioCodec(it.mimeType)
                if (!opts.preferOpusAudio && codec == AudioCodec.AAC) 100 else codec.priority
            }.thenBy { it.bitrate }
        )

    private fun toStreamFormat(raw: RawFormat, url: String) = StreamFormat(
        itag = raw.itag,
        url = url,
        mimeType = raw.mimeType,
        codec = MimeTypeParser.extractCodecString(raw.mimeType),
        bitrate = raw.bitrate,
        width = raw.width,
        height = raw.height,
        fps = raw.fps,
        audioSampleRate = raw.audioSampleRate?.toIntOrNull(),
        contentLength = raw.contentLength?.toLongOrNull(),
        initRange = raw.initRange?.let { it.start.toLong()..it.end.toLong() },
        indexRange = raw.indexRange?.let { it.start.toLong()..it.end.toLong() },
        approximateDurationMs = raw.approxDurationMs?.toLongOrNull() ?: 0L,
    )
}
