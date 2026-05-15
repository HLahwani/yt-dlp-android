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

        val bestAudio = selectBestAudio(audioFormats, options)
            ?: throw YTDLPError.NoStreamsFound("No audio-only adaptive stream found")

        return Selection(
            video = selectBestVideo(videoFormats, options),
            audio = bestAudio,
            muxed = if (options.includeMuxedFallback) muxedFormats.maxByOrNull { it.height ?: 0 } else null,
        )
    }

    private fun selectBestVideo(formats: List<StreamFormat>, opts: ExtractionOptions): StreamFormat? {
        var candidates = formats
            .filter { opts.maxVideoHeight == null || (it.height ?: 0) <= opts.maxVideoHeight }
        if (opts.preferH264)
            candidates = candidates.filter {
                MimeTypeParser.detectVideoCodec(it.mimeType) == VideoCodec.H264
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
