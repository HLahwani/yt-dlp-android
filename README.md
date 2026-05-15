# YTDLPDroid

A Kotlin-first Android library that extracts YouTube video and audio stream URLs, ready for playback via Jetpack Media3. Inspired by [yt-dlp](https://github.com/yt-dlp/yt-dlp)'s extraction algorithm.

## Features

- Accepts any YouTube URL form: watch, short, embed, Shorts, Music, bare video ID
- Returns separate adaptive video and audio DASH stream URLs for Media3 `ExoPlayer`
- 8-client InnerTube fallback chain with automatic geo-restriction and age-gate bypass
- n-parameter transform via embedded QuickJS (NDK, no runtime JS dependency)
- Signature cipher decryption for the WEB client fallback path
- In-memory + on-disk caching for stream URLs and player JS
- Coroutine-native API — all public functions are `suspend`

## Requirements

| Item | Value |
|---|---|
| Min SDK | 21 (Android 5.0) |
| NDK | Required (bundled `.so` files cover `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) |
| Kotlin | 2.0+ |
| Java compatibility | 17 |

---

## Installation

### AAR (local)

Copy `library-release.aar` to your project's `libs/` folder, then add to `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation(files("libs/library-release.aar"))

    // Required transitive dependencies
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

The QuickJS native library is bundled inside the AAR — no extra NDK setup needed in the consuming app.

**AAR size:** ~1.6 MB (all four ABIs combined)

### ProGuard / R8

The AAR ships with `consumer-rules.pro`. No additional rules are needed in the consuming app.

---

## Quick Start

```kotlin
// 1. Create a single instance (keep it alive for the lifetime of the screen/app)
val ytdlp = YTDLPDroid.Builder(context.cacheDir).build()

// 2. Extract from a coroutine
viewModelScope.launch {
    try {
        val result = ytdlp.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

        // 3. Play with Media3
        val dataSourceFactory = OkHttpDataSource.Factory(OkHttpClient())
            .setDefaultRequestProperties(mapOf(
                "User-Agent" to result.streamUserAgent,   // ← required to avoid CDN 403
                "Referer"    to "https://www.youtube.com/",
                "Origin"     to "https://www.youtube.com",
            ))

        val videoSource = result.videoStream?.let {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(it.url))
        }
        val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(result.audioStream.url))

        player.setMediaSource(
            if (videoSource != null) MergingMediaSource(videoSource, audioSource)
            else audioSource
        )
        player.prepare()
        player.play()

    } catch (e: YTDLPError) {
        // handle
    }
}
```

> **Important**: Always pass `result.streamUserAgent` in the `User-Agent` header when making CDN stream requests. YouTube's CDN validates that the User-Agent used to *fetch* the stream matches the one that *generated* the URL. A mismatch causes HTTP 403.

---

## API Reference

### `YTDLPDroid.Builder`

```kotlin
YTDLPDroid.Builder(cacheDir: File)
    .httpClient(client: OkHttpClient)       // custom OkHttp (proxy, logging, etc.)
    .jsEngine(engine: JsEngine)             // override JS engine (default: QuickJsEngine)
    .memoryCacheCapacity(n: Int)            // max cached stream results (default: 30)
    .build(): YTDLPDroid
```

### `YTDLPDroid.extract`

```kotlin
suspend fun extract(
    url: String,
    options: ExtractionOptions = ExtractionOptions(),
): StreamResult
```

Accepts any YouTube URL form:

| URL type | Example |
|---|---|
| Standard watch | `https://www.youtube.com/watch?v=dQw4w9WgXcQ` |
| Short URL | `https://youtu.be/dQw4w9WgXcQ` |
| Embed | `https://www.youtube.com/embed/dQw4w9WgXcQ` |
| Shorts | `https://www.youtube.com/shorts/dQw4w9WgXcQ` |
| Music | `https://music.youtube.com/watch?v=dQw4w9WgXcQ` |
| Mobile | `https://m.youtube.com/watch?v=dQw4w9WgXcQ` |
| Bare video ID | `dQw4w9WgXcQ` |
| Playlist URL | extracts the single video (`v=` param); playlist is ignored |

### `ExtractionOptions`

```kotlin
data class ExtractionOptions(
    val maxVideoHeight: Int? = null,       // cap resolution, e.g. 720
    val preferH264: Boolean = false,       // force H.264 over AV1/VP9
    val preferOpusAudio: Boolean = true,   // Opus > AAC (default)
    val includeMuxedFallback: Boolean = true, // include itag 18/22 as muxedStream
    val regionCode: String? = null,        // BCP-47, e.g. "DE" for geo-restricted content
)
```

**Codec priority (default):**
- Video: AV1 > VP9 HDR > VP9 > H.265 > H.264 > VP8
- Audio: Opus > Vorbis > AAC > AC3 > MP3

### `StreamResult`

```kotlin
data class StreamResult(
    val videoId: String,
    val metadata: VideoInfo,
    val videoStream: StreamFormat?,   // null for audio-only videos
    val audioStream: StreamFormat,
    val muxedStream: StreamFormat?,   // best muxed (itag 18/22), if requested
    val expiresAt: Long,              // epoch ms when URLs expire (~6 hours)
    val streamUserAgent: String,      // pass this in the User-Agent header for CDN requests
)
```

### `StreamFormat`

```kotlin
data class StreamFormat(
    val itag: Int,
    val url: String,                  // directly playable DASH URL
    val mimeType: String,
    val codec: String,                // e.g. "av01.0.08M.08", "opus"
    val bitrate: Long,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val audioSampleRate: Int?,
    val contentLength: Long?,
    val initRange: LongRange?,
    val indexRange: LongRange?,
    val approximateDurationMs: Long,
)
```

### `VideoInfo`

```kotlin
data class VideoInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String,
    val durationSeconds: Long,
    val isLive: Boolean,
    val thumbnailUrl: String,
    val keywords: List<String>,
)
```

### Error Handling

All errors extend `YTDLPError : Exception`.

```kotlin
try {
    val result = ytdlp.extract(url)
} catch (e: YTDLPError.InvalidUrl)            { /* unrecognised URL */ }
  catch (e: YTDLPError.GeoBlocked)            { /* not available in this region */ }
  catch (e: YTDLPError.AgeRestricted)         { /* all clients failed age-gate bypass */ }
  catch (e: YTDLPError.VideoUnavailable)      { /* private, removed, or otherwise unavailable */ }
  catch (e: YTDLPError.LiveStreamNotSupported){ /* video is a live stream */ }
  catch (e: YTDLPError.NoStreamsFound)        { /* no playable adaptive formats */ }
  catch (e: YTDLPError.NetworkError)          { /* HTTP or connectivity failure */ }
  catch (e: YTDLPError.AllClientsFailed)      { /* all 8 InnerTube clients failed */ }
  catch (e: YTDLPError)                       { /* catch-all */ }
```

---

## Geo-Restricted Content

For videos restricted to a specific country, pass the region code:

```kotlin
val result = ytdlp.extract(
    url = "https://www.youtube.com/watch?v=XXXXX",
    options = ExtractionOptions(regionCode = "DE"),
)
```

The library also automatically tries the `ANDROID_VR` InnerTube client early in the fallback chain since it has the best geo-bypass success rate. When all clients return a geo-restriction error, `YTDLPError.GeoBlocked` is thrown (not `AllClientsFailed`) so you can distinguish the cause.

---

## Media3 Integration

### Gradle dependencies

```kotlin
implementation("androidx.media3:media3-exoplayer:1.4.1")
implementation("androidx.media3:media3-ui:1.4.1")
implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
```

### Full playback setup

```kotlin
private fun setupPlayer(result: StreamResult) {
    // Pass the stream User-Agent — the CDN validates it against the URL generator.
    val dataSourceFactory = OkHttpDataSource.Factory(OkHttpClient())
        .setDefaultRequestProperties(mapOf(
            "User-Agent" to result.streamUserAgent,
            "Referer"    to "https://www.youtube.com/",
            "Origin"     to "https://www.youtube.com",
        ))

    val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

    val source = if (result.videoStream != null) {
        MergingMediaSource(
            mediaSourceFactory.createMediaSource(MediaItem.fromUri(result.videoStream.url)),
            mediaSourceFactory.createMediaSource(MediaItem.fromUri(result.audioStream.url)),
        )
    } else {
        // Audio-only video
        mediaSourceFactory.createMediaSource(MediaItem.fromUri(result.audioStream.url))
    }

    player.setMediaSource(source)
    player.prepare()
    player.play()
}
```

### URL expiry

Stream URLs expire approximately 6 hours after extraction (`result.expiresAt`). Re-call `extract()` before `expiresAt` to refresh them.

```kotlin
if (System.currentTimeMillis() > result.expiresAt - 5 * 60_000L) {
    result = ytdlp.extract(url)  // refresh 5 minutes before expiry
}
```

---

## Custom JS Engine

The default JS engine is QuickJS (embedded NDK). For unit tests or environments without NDK support, pass a `JsEngine` stub:

```kotlin
val ytdlp = YTDLPDroid.Builder(cacheDir)
    .jsEngine { functionCode, argument -> argument }  // passthrough stub
    .build()
```

`JsEngine` is a `fun interface`:

```kotlin
fun interface JsEngine {
    fun execute(functionCode: String, argument: String): String
}
```

---

## InnerTube Client Fallback Chain

The library tries eight InnerTube clients in order, moving to the next on any failure:

| # | Client | Notes |
|---|---|---|
| 1 | `ANDROID` | Primary; direct URLs, no signature cipher |
| 2 | `ANDROID_VR` | Best geo-bypass rate (Oculus Quest client) |
| 3 | `ANDROID_TESTSUITE` | Bypasses PO-token requirements |
| 4 | `TVHTML5_SIMPLY_EMBEDDED` | Exempt from some bot-checks |
| 5 | `IOS` | Direct URLs, iOS client |
| 6 | `MWEB` | Mobile web |
| 7 | `WEB_EMBEDDED` | Embedded player context |
| 8 | `WEB` | Last resort; uses signature cipher path |

Client versions are pinned and must be updated when YouTube rejects old versions (see *Maintenance*).

---

## Building from Source

```bash
# Debug AAR
./gradlew :library:assembleDebug

# Release AAR  →  library/build/outputs/aar/library-release.aar
./gradlew :library:assembleRelease

# Unit tests (JVM — no device needed)
./gradlew :library:testDebugUnitTest

# Instrumented tests (requires connected device or emulator)
./gradlew :library:connectedDebugAndroidTest

# Sample app
./gradlew :sample:assembleDebug
```

**NDK requirement:** Install via Android Studio → SDK Manager → NDK (Side by side). The CMake build step for `quickjs_bridge` will fail without it.

---

## Maintenance

YouTube regularly updates its player JavaScript and InnerTube client requirements.

### T-M1 — Update InnerTube client versions

**Trigger:** HTTP 400 `failedPrecondition` from ANDROID client, or `"API version not supported"`.

Update version strings in `InnerTubeClientConfig.kt`. Reference: yt-dlp's `INNERTUBE_CLIENTS` dict in `yt_dlp/extractor/youtube.py`.

### T-M2 — Fix n-param extraction patterns

**Trigger:** All stream URLs return HTTP 403 (throttled) on a real residential-IP device.

The n-parameter in stream URLs must be transformed by a function in YouTube's player JS. The current implementation:
1. Tries regex patterns for traditional players
2. Falls back to loading the full player JS into QuickJS and discovering the nsig function behaviorally — it inspects `_yt_player` (the IIFE's `g` parameter object) and finds the 2-4 character function that consistently transforms alphanumeric strings

Player `25f11721` (2025-2026) uses string-table obfuscation. The discovery found a hash-based transform function (`P0` or similar) rather than the classic array-shuffle approach. If streams are throttled on a new player version, check yt-dlp's `_extract_n_function_name` for updated patterns.

### T-M3 — Fix signature cipher extraction patterns

**Trigger:** WEB-client fallback formats all fail with "Sig function name not found".

The signature cipher helper `$b` in player `25f11721` uses `uF(58,1015,kU(41,8341,sig))` — see `CLAUDE.md` for the decoded operation sequence. If updated, check `SignatureDecipherer.SIG_FUNC_PATTERNS`.

---

## Known Limitations

- **Live streams** are not supported (`YTDLPError.LiveStreamNotSupported`).
- **Age-restricted videos** may be accessible via `ANDROID_VR` or `WEB_EMBEDDED` clients, but some require a logged-in session (not implemented).
- **Downloading to disk** is out of scope — this library returns URLs; saving is the caller's responsibility.
- **CDN IP restriction:** YouTube stream CDN (googlevideo.com) blocks requests from known datacenter / cloud IPs. The library works correctly on residential and carrier networks.

---

## Legal

This library is intended for **personal, educational, and research use** (e.g., accessibility tools, offline-first apps in areas with poor connectivity). 

- Automated access to YouTube content without explicit permission may violate **YouTube's Terms of Service § 4.B**.
- Do not use this library to build commercial services that redistribute YouTube content.
- The developer is responsible for ensuring their use case complies with applicable laws and YouTube's Terms of Service.
- For ToS-compliant metadata access, consider the [YouTube Data API v3](https://developers.google.com/youtube/v3).

---

## License

See [yt-dlp](https://github.com/yt-dlp/yt-dlp) (MIT) for the extraction algorithm reference. QuickJS is © Fabrice Bellard, licensed under MIT.
