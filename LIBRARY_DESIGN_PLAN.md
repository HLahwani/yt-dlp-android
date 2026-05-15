# YTDLPDroid — Android Kotlin YouTube Extraction Library
## Full Design & Implementation Plan

> Target: A Kotlin-first Android library that extracts YouTube video and audio stream URLs,
> ready for playback via Jetpack Media3. Inspired by yt-dlp's extraction algorithm.

---

## Table of Contents

1. [Goals & Constraints](#1-goals--constraints)
2. [How YouTube Streaming Works (Technical Background)](#2-how-youtube-streaming-works)
3. [Architecture Overview](#3-architecture-overview)
4. [Module Structure](#4-module-structure)
5. [Component Deep-Dives](#5-component-deep-dives)
6. [JavaScript Engine Options (Critical Decision)](#6-javascript-engine-options-critical-decision)
7. [InnerTube Client Strategy](#7-innertube-client-strategy)
8. [Public API Design](#8-public-api-design)
9. [Caching Strategy](#9-caching-strategy)
10. [Performance Optimizations](#10-performance-optimizations)
11. [Media3 Integration Guide](#11-media3-integration-guide)
12. [Gradle / Build Configuration](#12-gradle--build-configuration)
13. [Step-by-Step Implementation Roadmap](#13-step-by-step-implementation-roadmap)
14. [Risk Register & Mitigation](#14-risk-register--mitigation)
15. [Legal & Ethical Notes](#15-legal--ethical-notes)

---

## 1. Goals & Constraints

### What the library does
- Accepts a YouTube URL (or video ID) plus optional quality/codec preferences.
- Returns one or more `StreamResult` objects containing:
  - A video-only DASH stream URL (adaptive)
  - An audio-only DASH stream URL (adaptive)
  - (Optional) a muxed URL for simple players
  - Video metadata (title, duration, thumbnail, author)
- All returned URLs are directly playable by Android's Media3 `ExoPlayer`.

### What it does NOT do
- No downloading / saving to disk.
- No FFmpeg muxing; Media3 handles adaptive merging natively.
- No login / OAuth flows (focus on public videos; age-gate bypass via alternative clients).

### Hard constraints
- Pure Kotlin public API (no Java inheritance required by the caller).
- Minimum Android SDK: **21** (Lollipop).
- Zero transitive dependency on full JavaScript runtimes (no full V8 in the AAR).
- Library AAR size target: **< 3 MB** (excluding optional native module).
- Thread-safe and coroutine-friendly.

---

## 2. How YouTube Streaming Works

Understanding the full yt-dlp pipeline is mandatory before coding anything.

### 2.1 The Two Kinds of Stream URLs

| Type | Where it comes from | Needs decryption? | Client |
|---|---|---|---|
| **Direct URL** (`url` field) | ANDROID / IOS InnerTube clients | n-param transform only | ANDROID, IOS |
| **signatureCipher** (`signatureCipher` field) | WEB / WEB_SAFARI clients | sig + n-param transform | WEB |

The ANDROID InnerTube client returns **direct URLs** — this is the primary path for this library because it avoids full JavaScript signature decryption. Only the lighter `n`-parameter transform is needed.

### 2.2 The n-Parameter (Throttle Token)

Every stream URL contains a query parameter `?n=<token>`. If this token is not transformed by YouTube's JavaScript function, the CDN throttles the stream to ~50 KB/s or returns HTTP 403.

```
Original URL: ...&n=JlyEJ0UViqfmhLcpxr8&...
Transformed:  ...&n=wFr3BZafKa9d&...         ← playable at full speed
```

The transformation function is embedded in YouTube's player JavaScript file (e.g. `/s/player/xxxxxxxx/player_ias.vflset/base.js`). It changes with every player version, so:
1. We must fetch and cache the player JS.
2. Extract the n-transform function by regex.
3. Execute it on the n-token via a JavaScript engine.

### 2.3 Full Extraction Pipeline

```
YouTube URL
    │
    ▼
[1] Parse Video ID
    │
    ▼
[2] InnerTube POST /player  (ANDROID client, no API key needed)
    │   Body: { videoId, context: { client: ANDROID } }
    │
    ▼
[3] Parse playerResponse JSON
    │   streamingData.adaptiveFormats[]
    │   streamingData.formats[]           (muxed, fallback only)
    │   videoDetails.*
    │
    ▼
[4] Decrypt n-parameter for each stream URL
    │   4a. Fetch & cache player JS  (URL from response or webpage)
    │   4b. Extract nsig function name (regex on JS)
    │   4c. Execute nsig(n) via JS engine
    │   4d. Replace n= in URL
    │
    ▼
[5] Select best video + audio streams
    │   Priority: av1 > vp9 > h264  |  opus > aac
    │   Resolution: user preference or highest available
    │
    ▼
[6] Return StreamResult to caller
        .videoUrl  (DASH video-only)
        .audioUrl  (DASH audio-only)
        .muxedUrl  (optional fallback)
        .metadata  (VideoInfo)
```

### 2.4 signatureCipher (Fallback Path)

If the ANDROID client fails (blocked region, unusual video), fall back to WEB client:

```
signatureCipher = "s=<ENC_SIG>&sp=sig&url=<BASE_URL>"

1. Extract player JS URL from webpage HTML
2. Find sig function name in JS:   \.sig\|\|([a-zA-Z0-9$]+)\(
3. Execute sig_function(encrypted_sig) → decrypted_sig
4. Append &sig=<decrypted_sig> to URL
5. Still apply n-param transform afterward
```

### 2.5 InnerTube API at a Glance

- **Endpoint**: `POST https://www.youtube.com/youtubei/v1/player`
- **No API key required** for the ANDROID client (can omit `?key=`)
- **Key headers** for ANDROID client:
  ```
  User-Agent: com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip
  X-YouTube-Client-Name: 3
  X-YouTube-Client-Version: 19.09.37
  Content-Type: application/json
  ```
- **Request body** (ANDROID):
  ```json
  {
    "context": {
      "client": {
        "clientName": "ANDROID",
        "clientVersion": "19.09.37",
        "androidSdkVersion": 30,
        "userAgent": "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
        "hl": "en",
        "gl": "US"
      }
    },
    "videoId": "VIDEO_ID",
    "params": "8AEB",
    "playbackContext": {
      "contentPlaybackContext": {
        "html5Preference": "HTML5_PREF_WANTS"
      }
    },
    "racyCheckOk": true,
    "contentCheckOk": true
  }
  ```

---

## 3. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                     PUBLIC API LAYER                             │
│  YTDLPDroid (builder)  ─►  YouTubeExtractor  ─►  StreamResult   │
└───────────────────────────────┬──────────────────────────────────┘
                                │
           ┌────────────────────┼────────────────────┐
           │                    │                     │
    ┌──────▼──────┐   ┌────────▼────────┐   ┌───────▼───────┐
    │  InnerTube  │   │  PlayerJs       │   │  Format       │
    │  API Client │   │  Decipher Layer │   │  Selector     │
    │  (OkHttp)   │   │                 │   │               │
    └──────┬──────┘   └────────┬────────┘   └───────┬───────┘
           │                   │                     │
    ┌──────▼──────┐   ┌────────▼────────┐   ┌───────▼───────┐
    │  Response   │   │  JS Engine      │   │  StreamResult │
    │  Parser     │   │  (QuickJS JNI   │   │  Builder      │
    │  (JSON)     │   │   or Rhino)     │   │               │
    └─────────────┘   └─────────────────┘   └───────────────┘
           │
    ┌──────▼──────┐
    │  Cache      │
    │  Layer      │
    │  (disk+mem) │
    └─────────────┘
```

### Design Principles
- **Single Responsibility**: each class has one job.
- **Strategy Pattern** for the JavaScript engine (swap between Rhino and QuickJS JNI without breaking the API).
- **Repository Pattern** for all network/cache access.
- **Coroutines** for async operations; blocking callers can use `runBlocking` if needed.
- **No Android framework dependency in core logic** — extractors work as pure Kotlin, making unit testing easy.

---

## 4. Module Structure

```
YTDLPDroid/
├── library/                         ← Main AAR module
│   ├── src/
│   │   ├── main/
│   │   │   ├── kotlin/com/ytdlpdroid/
│   │   │   │   ├── YTDLPDroid.kt                  # Entry point / Builder
│   │   │   │   ├── extractor/
│   │   │   │   │   ├── YouTubeExtractor.kt         # Orchestrates full pipeline
│   │   │   │   │   ├── VideoIdParser.kt            # URL → video ID
│   │   │   │   │   └── PlaylistExtractor.kt        # (future: playlist support)
│   │   │   │   ├── innertube/
│   │   │   │   │   ├── InnerTubeClient.kt          # HTTP + request building
│   │   │   │   │   ├── InnerTubeClientConfig.kt    # Client configs (ANDROID, WEB…)
│   │   │   │   │   └── PlayerResponseParser.kt     # JSON → domain models
│   │   │   │   ├── decipher/
│   │   │   │   │   ├── DecipherService.kt          # Facade for sig + nsig
│   │   │   │   │   ├── NParamDecipherer.kt         # n-param transform
│   │   │   │   │   ├── SignatureDecipherer.kt      # Legacy sig cipher (WEB fallback)
│   │   │   │   │   └── PlayerJsRepository.kt      # Fetch/cache player.js
│   │   │   │   ├── js/
│   │   │   │   │   ├── JsEngine.kt                # Interface
│   │   │   │   │   ├── RhinoJsEngine.kt           # Rhino implementation
│   │   │   │   │   └── QuickJsEngine.kt           # QuickJS JNI implementation
│   │   │   │   ├── format/
│   │   │   │   │   ├── FormatSelector.kt          # Chooses best video+audio
│   │   │   │   │   ├── StreamFormat.kt            # Data model for one format
│   │   │   │   │   ├── MimeTypeParser.kt          # Parse codec strings
│   │   │   │   │   └── Codec.kt                  # Codec enum + priority
│   │   │   │   ├── cache/
│   │   │   │   │   ├── MemoryCache.kt             # LRU in-memory cache
│   │   │   │   │   └── DiskCache.kt               # File-based cache with TTL
│   │   │   │   ├── network/
│   │   │   │   │   └── HttpClientProvider.kt      # OkHttp singleton / config
│   │   │   │   └── model/
│   │   │   │       ├── VideoInfo.kt               # Title, duration, thumbnails
│   │   │   │       ├── StreamResult.kt            # What the caller gets back
│   │   │   │       ├── ExtractionOptions.kt       # Quality/codec preferences
│   │   │   │       └── YTDLPError.kt             # Typed errors
│   │   │   └── cpp/                               # Optional: QuickJS JNI
│   │   │       ├── CMakeLists.txt
│   │   │       ├── quickjs_bridge.cpp
│   │   │       └── quickjs/                       # QuickJS source (MIT)
│   │   └── test/                                  # Unit tests
│   └── build.gradle.kts
│
├── sample/                          ← Demo app using Media3
│   └── src/main/...
│
└── build.gradle.kts
```

---

## 5. Component Deep-Dives

### 5.1 `VideoIdParser`

Extracts the video ID from any of these URL forms:

| URL Pattern | Example |
|---|---|
| Standard watch URL | `https://www.youtube.com/watch?v=dQw4w9WgXcQ` |
| Short URL | `https://youtu.be/dQw4w9WgXcQ` |
| Embed URL | `https://www.youtube.com/embed/dQw4w9WgXcQ` |
| Shorts URL | `https://www.youtube.com/shorts/dQw4w9WgXcQ` |
| Raw video ID | `dQw4w9WgXcQ` |
| Music URL | `https://music.youtube.com/watch?v=dQw4w9WgXcQ` |

```kotlin
object VideoIdParser {
    private val PATTERNS = listOf(
        Regex("""(?:v=|youtu\.be/|embed/|shorts/)([a-zA-Z0-9_-]{11})"""),
        Regex("""^([a-zA-Z0-9_-]{11})$""")
    )

    fun parse(input: String): String {
        for (pattern in PATTERNS) {
            pattern.find(input)?.groupValues?.get(1)?.let { return it }
        }
        throw YTDLPError.InvalidUrl(input)
    }
}
```

### 5.2 `InnerTubeClient`

All InnerTube requests go through this class.

```kotlin
class InnerTubeClient(
    private val httpClient: OkHttpClient,
    private val config: InnerTubeClientConfig,
) {
    suspend fun fetchPlayerResponse(videoId: String): PlayerResponse {
        val body = buildPlayerRequestBody(videoId, config)
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player")
            .post(body.toRequestBody("application/json".toMediaType()))
            .headers(config.headers)
            .build()
        // Execute + parse (see PlayerResponseParser)
    }
}
```

**`InnerTubeClientConfig`** defines per-client constants:

```kotlin
sealed class InnerTubeClientConfig(
    val clientName: String,
    val clientVersion: String,
    val userAgent: String,
    val headers: Headers,
    val androidSdkVersion: Int? = null,
) {
    object ANDROID : InnerTubeClientConfig(
        clientName = "ANDROID",
        clientVersion = "19.09.37",
        userAgent = "com.google.android.youtube/19.09.37 (Linux; U; Android 11) gzip",
        headers = headersOf(
            "X-YouTube-Client-Name", "3",
            "X-YouTube-Client-Version", "19.09.37",
        ),
        androidSdkVersion = 30,
    )

    object ANDROID_VR : InnerTubeClientConfig(
        clientName = "ANDROID_VR",
        clientVersion = "1.57.29",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.57.29 ...",
        headers = headersOf("X-YouTube-Client-Name", "28", ...),
    )

    object WEB : InnerTubeClientConfig(
        clientName = "WEB",
        clientVersion = "2.20240101",
        userAgent = "Mozilla/5.0 ...",
        headers = headersOf("X-YouTube-Client-Name", "1", ...),
    )
    // ... IOS, MWEB, TV_EMBEDDED, WEB_EMBEDDED
}
```

### 5.3 `PlayerResponseParser`

Parses the raw JSON player response into domain models using `kotlinx.serialization`.

Key fields extracted:
- `playabilityStatus.status` → detect `LOGIN_REQUIRED`, `UNPLAYABLE`, `ERROR`
- `streamingData.adaptiveFormats[]` → list of `StreamFormat`
- `streamingData.formats[]` → muxed fallback formats
- `streamingData.expiresInSeconds` → cache TTL for URLs
- `videoDetails.{title,videoId,lengthSeconds,thumbnails,author,channelId}`
- `playerConfig.audioConfig` → loudness normalization info
- `playerUrl` or extract from returned JS path

```kotlin
@Serializable
data class RawPlayerResponse(
    val playabilityStatus: PlayabilityStatus,
    val streamingData: StreamingData? = null,
    val videoDetails: VideoDetails? = null,
)

@Serializable
data class RawStreamFormat(
    val itag: Int,
    val url: String? = null,
    val signatureCipher: String? = null,
    val mimeType: String,
    val bitrate: Long,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val qualityLabel: String? = null,
    val audioQuality: String? = null,
    val audioSampleRate: String? = null,
    val approxDurationMs: String? = null,
    val contentLength: String? = null,
    val initRange: Range? = null,
    val indexRange: Range? = null,
)
```

### 5.4 `DecipherService`

Facade that coordinates `NParamDecipherer` and `SignatureDecipherer`.

```kotlin
class DecipherService(
    private val playerJsRepo: PlayerJsRepository,
    private val jsEngine: JsEngine,
) {
    // Returns the fully playable URL
    suspend fun buildPlayableUrl(format: RawStreamFormat, playerJsUrl: String): String {
        val rawUrl = when {
            format.url != null -> format.url
            format.signatureCipher != null -> decryptSignatureCipher(format.signatureCipher, playerJsUrl)
            else -> throw YTDLPError.NoUrlFound(format.itag)
        }
        return applyNParam(rawUrl, playerJsUrl)
    }
}
```

### 5.5 `NParamDecipherer`

1. Receives a URL containing `&n=XXXXXXX`.
2. Fetches (or retrieves from cache) the player JS.
3. Extracts the nsig function name via regex:
   ```
   \.get\("n"\)\)&&\([a-z]=([a-zA-Z0-9$]{2,4})\[
   [a-zA-Z0-9$]{2,4}\[(\d+)\]
   ```
4. Calls `jsEngine.execute(nFunctionCode, nValue)`.
5. Replaces `n=OLD` with `n=NEW` in the URL.

**Caching**: The extracted function code is cached keyed by player JS hash (changes ~daily). The function lookup is O(1) after the first call.

### 5.6 `PlayerJsRepository`

- **Input**: player JS URL (found in the watch page HTML or InnerTube response).
- Stores the JS text in disk cache with 24-hour TTL (player versions change daily).
- Stores extracted function snippets in a memory LRU cache.
- The player URL looks like: `/s/player/abc12345/player_ias.vflset/base.js`

Player JS URL extraction from webpage:
```kotlin
val regex = Regex(""""jsUrl"\s*:\s*"(/s/player/[a-f0-9]+/[^"]+base\.js)"""")
```

### 5.7 `FormatSelector`

Selects the optimal video and audio streams from the list of `adaptiveFormats`.

**Video codec priority** (descending):
```
av01 (AV1) > vp9.2 (HDR VP9) > vp9 > avc1 (H.264) > vp8
```

**Audio codec priority**:
```
opus > vorbis > mp4a (AAC) > ac-3
```

**Resolution priority**: User-specified max, or default to 1080p. Fall back up/down if unavailable.

**Algorithm**:
```kotlin
fun selectBest(
    formats: List<StreamFormat>,
    options: ExtractionOptions,
): Pair<StreamFormat, StreamFormat> {   // video, audio

    val videoFormats = formats
        .filter { it.type == FormatType.VIDEO_ONLY }
        .filter { options.maxHeight == null || it.height!! <= options.maxHeight }
        .sortedWith(compareByDescending<StreamFormat> { it.height }
            .thenByDescending { it.fps ?: 0 }
            .thenByDescending { codecPriority(it.codec) }
            .thenByDescending { it.bitrate })

    val audioFormats = formats
        .filter { it.type == FormatType.AUDIO_ONLY }
        .sortedWith(compareByDescending<StreamFormat> { codecPriority(it.codec) }
            .thenByDescending { it.bitrate })

    return videoFormats.first() to audioFormats.first()
}
```

---

## 6. JavaScript Engine Options (Critical Decision)

The n-parameter transform requires executing a snippet of obfuscated JavaScript. This is the **only** place JavaScript execution is needed when using the ANDROID InnerTube client (no `signatureCipher`).

### Option A — Rhino (Pure Java, No NDK) ⭐ Recommended Starter

| Attribute | Value |
|---|---|
| Library | `org.mozilla:rhino:1.7.15` |
| Size overhead | ~1.2 MB in AAR |
| NDK required | No |
| ES version | ES5 + partial ES6 |
| Startup time | ~100ms cold, <1ms warm (reuse context) |
| Android minSdk | 14+ |
| Risk | YouTube nsig functions may use ES6+ features (arrow functions, `const`, `let`) |

The nsig functions yt-dlp has seen in practice use ES5-compatible code. Rhino is fine for most cases. Use `Context.VERSION_ES6` flag.

**Integration**:
```kotlin
class RhinoJsEngine : JsEngine {
    private val scope: ScriptableObject by lazy {
        val cx = Context.enter()
        cx.optimizationLevel = -1          // interpreted mode for Android
        cx.languageVersion = Context.VERSION_ES6
        cx.initSafeStandardObjects().also { Context.exit() }
    }

    override fun execute(functionCode: String, argument: String): String {
        val cx = Context.enter()
        try {
            cx.evaluateString(scope, functionCode, "nsig", 1, null)
            val fn = scope.get("ytdlp_nsig", scope) as Function
            return Context.toString(fn.call(cx, scope, scope, arrayOf(argument)))
        } finally {
            Context.exit()
        }
    }
}
```

### Option B — QuickJS via JNI (Smallest Footprint, Best Performance)

| Attribute | Value |
|---|---|
| Engine | QuickJS (Bellard, MIT) |
| Native size | ~200 KB per ABI |
| NDK required | Yes (NDK r21+) |
| ES version | ES2020 |
| Startup time | <5ms cold |
| Build complexity | Medium (CMakeLists.txt) |
| Risk | NDK build pipeline required |

**Best long-term choice** if you're comfortable with NDK. Several open-source Android QuickJS wrappers exist:
- `io.github.taoweiji.quickjs:quickjs-android` (Maven Central)
- `com.github.dokar3:quickjs-kt` (Kotlin multiplatform wrapper)

```kotlin
class QuickJsEngine : JsEngine {
    private val runtime: QuickJSRuntime by lazy { QuickJSRuntime.create() }

    override fun execute(functionCode: String, argument: String): String {
        return runtime.evaluate("""
            $functionCode
            ytdlp_nsig("$argument");
        """.trimIndent()) as String
    }
}
```

### Option C — Duktape Android

| Attribute | Value |
|---|---|
| Library | `com.squareup.duktape:duktape-android:1.4.0` |
| Native size | ~300 KB per ABI |
| ES version | ES5.1 + partial ES6 |
| Maintenance | Unmaintained (last update 2021) |

Not recommended due to maintenance status and ES5-only support.

### Option D — Hard-coded Pattern Interpreter

Analyze the structure of nsig functions and implement in pure Kotlin. YouTube's nsig functions follow a consistent pattern of:
- Array of string/function literals
- Series of operations (reverse, splice, swap, push, etc.)

This is the approach some yt-dlp forks take. Extremely fast (no JS overhead), but breaks when YouTube changes the function structure.

Only viable as an optimization layer on top of Option A/B, not as the primary strategy.

### Recommendation

**Phase 1 (MVP)**: Use **Rhino** — zero NDK complexity, works reliably.  
**Phase 2 (Optimization)**: Add **QuickJS JNI** as the default with Rhino as fallback.

```kotlin
interface JsEngine {
    fun execute(functionCode: String, argument: String): String
}

// In YTDLPDroid.Builder:
fun jsEngine(engine: JsEngine) = apply { this.jsEngine = engine }
// Default: RhinoJsEngine()
// Optional: QuickJsEngine()
```

---

## 7. InnerTube Client Strategy

### Client Priority Chain

```
1. ANDROID          ← Primary: direct URLs, no sig cipher, no API key
2. ANDROID_VR       ← Age-gate bypass (some restricted videos)
3. WEB_EMBEDDED     ← Embeddable videos age-gate bypass
4. IOS              ← Fallback (may need PO token for some formats)
5. WEB              ← Last resort: signatureCipher path (slower, needs sig decryption)
```

### Client-Specific Behavior

| Client | URL type | n-param needed | sig needed | Age-gate |
|---|---|---|---|---|
| ANDROID | Direct | Yes | No | Partial |
| ANDROID_VR | Direct | Yes | No | Yes (most) |
| IOS | Direct | Yes | No | Partial |
| WEB | signatureCipher | Yes | Yes | No |
| WEB_EMBEDDED | Direct | Yes | No | Yes (embeddable) |

### Fallback Logic

```kotlin
suspend fun extractWithFallback(videoId: String): PlayerResponse {
    val clients = listOf(
        InnerTubeClientConfig.ANDROID,
        InnerTubeClientConfig.ANDROID_VR,
        InnerTubeClientConfig.WEB_EMBEDDED,
        InnerTubeClientConfig.WEB,
    )
    for (client in clients) {
        val response = innerTubeClient.fetchPlayerResponse(videoId, client)
        when (response.playabilityStatus.status) {
            "OK" -> return response
            "LOGIN_REQUIRED" -> continue      // try next client (age gate)
            "UNPLAYABLE" -> continue
            "ERROR" -> throw YTDLPError.VideoUnavailable(videoId, response.playabilityStatus.reason)
        }
    }
    throw YTDLPError.AllClientsFailed(videoId)
}
```

### Visitor Data & Cookies

The ANDROID client works without cookies for most public videos. For improved reliability:
- Include `X-Goog-Visitor-Id` header if visitor data is available.
- Visitor data can be obtained from any initial YouTube page load or InnerTube browse response.
- Cache visitor data per session (valid for the session lifetime).

---

## 8. Public API Design

### 8.1 Initialization

```kotlin
// Minimal setup
val ytdlp = YTDLPDroid.build(context)

// Full configuration
val ytdlp = YTDLPDroid.Builder(context)
    .cacheDir(context.cacheDir)          // default: context.cacheDir/ytdlpdroid
    .memoryCacheSize(50)                  // max cached entries
    .jsEngine(QuickJsEngine())            // override JS engine
    .httpClient(customOkHttpClient)       // bring your own OkHttp
    .userLocale(Locale.getDefault())
    .build()
```

### 8.2 Extraction

```kotlin
// Suspend function (use from coroutine or viewModelScope)
val result: StreamResult = ytdlp.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ")

// With options
val result = ytdlp.extract(
    url = "https://youtu.be/dQw4w9WgXcQ",
    options = ExtractionOptions(
        maxVideoHeight = 1080,
        preferredVideoCodec = VideoCodec.H264,   // or AV1, VP9
        preferredAudioCodec = AudioCodec.AAC,    // or OPUS
        includeMuxed = false,                    // skip muxed formats
    )
)
```

### 8.3 Result Model

```kotlin
data class StreamResult(
    val videoId: String,
    val metadata: VideoInfo,
    val videoStream: StreamFormat?,    // null only if audio-only video
    val audioStream: StreamFormat,
    val muxedStream: StreamFormat?,    // best muxed (itag 18 or 22), if requested
    val expiresAt: Instant,            // when URLs expire (~6 hours)
)

data class VideoInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String,
    val durationSeconds: Long,
    val isLive: Boolean,
    val thumbnails: List<Thumbnail>,
    val keywords: List<String>,
)

data class StreamFormat(
    val itag: Int,
    val url: String,                   // fully playable URL
    val mimeType: String,
    val codec: String,
    val bitrate: Long,
    val width: Int?,
    val height: Int?,
    val fps: Int?,
    val audioSampleRate: Int?,
    val contentLength: Long?,
    val initRange: LongRange?,         // for DASH init segment
    val indexRange: LongRange?,        // for DASH index segment
    val approximateDurationMs: Long,
)
```

### 8.4 Error Handling

```kotlin
sealed class YTDLPError(message: String) : Exception(message) {
    class InvalidUrl(url: String) : YTDLPError("Not a valid YouTube URL: $url")
    class VideoUnavailable(videoId: String, reason: String?) : YTDLPError(...)
    class AgeRestricted(videoId: String) : YTDLPError(...)
    class PrivateVideo(videoId: String) : YTDLPError(...)
    class GeoBlocked(videoId: String, region: String?) : YTDLPError(...)
    class LiveStreamNotSupported(videoId: String) : YTDLPError(...)
    class NetworkError(cause: Exception) : YTDLPError(...)
    class DecipherFailed(reason: String) : YTDLPError(...)
    class NoStreamsFound(videoId: String) : YTDLPError(...)
    class AllClientsFailed(videoId: String) : YTDLPError(...)
}

// Usage
try {
    val result = ytdlp.extract(url)
} catch (e: YTDLPError.AgeRestricted) {
    // handle
} catch (e: YTDLPError) {
    // catch-all for library errors
}
```

### 8.5 Cancellation

All `suspend` functions are cancellation-cooperative via Kotlin coroutines. Callers can use `withTimeout`, `cancel()` on scope, etc.

---

## 9. Caching Strategy

### What to Cache and For How Long

| Item | Cache Type | TTL | Key |
|---|---|---|---|
| Player JS text | Disk | 24 hours | player JS URL hash |
| nsig function code | Memory (LRU) | Until evicted | player JS hash |
| sig function code | Memory (LRU) | Until evicted | player JS hash |
| Stream URLs | Memory (LRU) | Per `expiresInSeconds` field (~6 hrs) | video ID + client |
| Video metadata | Memory (LRU) | 1 hour | video ID |
| Visitor data | Memory | Session | N/A |

### Implementation

```kotlin
class MemoryCache<K, V>(maxSize: Int) {
    private val lru = object : LinkedHashMap<K, CacheEntry<V>>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, CacheEntry<V>>) = size > maxSize
    }

    fun get(key: K): V? {
        val entry = lru[key] ?: return null
        return if (entry.isExpired()) { lru.remove(key); null } else entry.value
    }

    fun put(key: K, value: V, ttl: Duration) {
        lru[key] = CacheEntry(value, Instant.now() + ttl)
    }
}

class DiskCache(private val dir: File) {
    fun get(key: String): String? { /* read file if not expired */ }
    fun put(key: String, value: String, ttl: Duration) { /* write with metadata */ }
}
```

### Player JS Cache Note

The player JS URL contains a version hash (`/s/player/abc12345/...`). When a new player is deployed by YouTube (daily or more), the hash changes. The cache is therefore naturally invalidated without needing explicit TTL purging — just cache by the full URL as the key.

---

## 10. Performance Optimizations

### 10.1 Parallel Requests

When a stream result is not cached, the extraction involves:
1. InnerTube player request (mandatory)
2. Fetch player JS (if not cached) — can be done in parallel with step 1 if URL is known

Use `async/await` in coroutines:

```kotlin
coroutineScope {
    val playerResponseDeferred = async { innerTubeClient.fetchPlayerResponse(videoId) }
    // Start fetching player JS early if we have a cached URL for this player version
    val playerJsDeferred = cachedPlayerJsUrl?.let { async { playerJsRepo.fetch(it) } }

    val playerResponse = playerResponseDeferred.await()
    val playerJsUrl = extractPlayerJsUrl(playerResponse) ?: fetchPlayerJsUrlFromWebPage(videoId)
    val playerJs = playerJsDeferred?.await() ?: playerJsRepo.fetch(playerJsUrl)
    // ...
}
```

### 10.2 Lazy JS Function Extraction

Extract the nsig function name from player JS **once per player version**, cache it. Only re-extract when the player JS hash changes.

### 10.3 URL Expiry Management

Stream URLs expire (usually in 6 hours based on `expiresInSeconds`). Cache the entire `StreamResult` and re-extract only when it's about to expire (e.g., < 5 minutes remaining).

### 10.4 Rhino Context Reuse

Rhino's `Context` creation is expensive. Use a thread-local or pooled context:

```kotlin
private val contextPool = ArrayDeque<Context>()

fun borrowContext(): Context = synchronized(contextPool) {
    contextPool.removeFirstOrNull() ?: Context.enter().apply { ... }
}

fun returnContext(cx: Context) = synchronized(contextPool) {
    contextPool.addFirst(cx)
}
```

### 10.5 Format Parsing with Lazy Properties

Don't parse all 30+ adaptive formats eagerly. Use `Sequence` and stop at the first format that satisfies selection criteria.

### 10.6 OkHttp Connection Pooling

Configure OkHttp with adequate pool settings for YouTube's servers (multiple streams per video ID request):

```kotlin
OkHttpClient.Builder()
    .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .addInterceptor(RetryInterceptor(maxRetries = 3))
    .build()
```

---

## 11. Media3 Integration Guide

### 11.1 Why Separate Video + Audio Streams Work with Media3

Media3 `ExoPlayer` natively supports **DASH** and **adaptive streams**. When given separate video and audio URLs, you can use `MergingMediaSource` to combine them:

```kotlin
// After calling ytdlp.extract(url)
val result = ytdlp.extract(youtubeUrl)

val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
    .createMediaSource(MediaItem.fromUri(result.videoStream!!.url))

val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
    .createMediaSource(MediaItem.fromUri(result.audioStream.url))

val mergedSource = MergingMediaSource(videoSource, audioSource)

player.setMediaSource(mergedSource)
player.prepare()
player.play()
```

### 11.2 Custom DataSource for Header Injection

YouTube stream URLs sometimes require specific headers. Wrap `DefaultHttpDataSource` to inject them:

```kotlin
class YouTubeDataSource(upstream: HttpDataSource) : ForwardingDataSource(upstream) {
    override fun open(dataSpec: DataSpec): Long {
        val patchedSpec = dataSpec.buildUpon()
            .setHttpRequestHeaders(mapOf(
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
            ))
            .build()
        return super.open(patchedSpec)
    }
}
```

### 11.3 MediaItem Convenience Extension (Optional Module)

```kotlin
// In an optional `ytdlpdroid-media3` module
suspend fun YTDLPDroid.toMediaItem(url: String): Pair<MediaItem, MediaSource> {
    val result = extract(url)
    // build and return merged MediaSource
}
```

### 11.4 Subtitle / Track Selection

The player response also contains `captions.playerCaptionsTracklistRenderer.captionTracks[]`. Parse these for automatic subtitle URL discovery, passable directly to Media3 as `SubtitleConfiguration`.

---

## 12. Gradle / Build Configuration

### 12.1 `library/build.gradle.kts`

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ytdlpdroid"
    compileSdk = 35
    minSdk = 21

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false      // library; let app handle minification
        }
    }

    // Optional: QuickJS NDK
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
}

dependencies {
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")   // debug builds

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // JavaScript engine (Rhino)
    implementation("org.mozilla:rhino:1.7.15")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
```

### 12.2 `consumer-rules.pro`

```proguard
# Keep all public API classes
-keep class com.ytdlpdroid.YTDLPDroid { *; }
-keep class com.ytdlpdroid.model.** { *; }
-keep class com.ytdlpdroid.extractor.ExtractionOptions { *; }

# Keep Rhino from being stripped
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# Keep serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
```

### 12.3 Modular Structure for Optional Features

```
:library          ← core (no Android Context needed in logic classes)
:library-media3   ← optional Media3 convenience extensions
:library-native   ← optional QuickJS NDK module (replaces Rhino)
:sample           ← demo application
```

---

## 13. Step-by-Step Implementation Roadmap

### Phase 1 — Core Extraction (Week 1–2)

- [ ] Set up Gradle project with library + sample modules
- [ ] Implement `VideoIdParser` with all URL patterns + unit tests
- [ ] Implement `InnerTubeClient` with ANDROID client config
- [ ] Implement `PlayerResponseParser` using `kotlinx.serialization`
- [ ] Implement `FormatSelector` (video + audio selection logic)
- [ ] Implement `RhinoJsEngine` wrapper
- [ ] Implement `NParamDecipherer` (regex extraction + JS execution)
- [ ] Implement `PlayerJsRepository` with disk cache
- [ ] Wire everything in `YouTubeExtractor`
- [ ] Create public `YTDLPDroid` API with Builder
- [ ] Integration test: extract a real video and verify URLs respond 200

### Phase 2 — Robustness & Fallbacks (Week 3)

- [ ] Implement client fallback chain (ANDROID → ANDROID_VR → WEB_EMBEDDED → WEB)
- [ ] Implement `SignatureDecipherer` for WEB client fallback
- [ ] Add `MemoryCache` + `DiskCache` for stream URL caching
- [ ] Add proper `YTDLPError` hierarchy
- [ ] Handle live streams (return HLS URL)
- [ ] Handle private / unavailable / geo-blocked videos gracefully

### Phase 3 — Media3 Integration Sample (Week 4)

- [ ] Sample app: pick a YouTube URL, extract, play with `ExoPlayer`
- [ ] `MergingMediaSource` demo (separate video + audio)
- [ ] `YouTubeDataSource` with header injection
- [ ] Subtitle track discovery and display
- [ ] Implement URL expiry check + auto-refresh

### Phase 4 — Performance & Native (Week 5–6)

- [ ] Add `QuickJsEngine` NDK option
- [ ] Benchmark Rhino vs QuickJS on multiple devices
- [ ] Parallel coroutine optimization for player JS + InnerTube calls
- [ ] Cache warm-up strategy on app startup
- [ ] ProGuard / R8 testing

### Phase 5 — Polish & Release (Week 7–8)

- [ ] Write KDoc for all public API surfaces
- [ ] Publish to Maven Central or GitHub Packages
- [ ] Create comprehensive README
- [ ] Add CI (GitHub Actions): build + unit tests on every push

---

## 14. Risk Register & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| YouTube changes nsig function structure | High (weekly) | High (streams throttled) | Cache invalidation on player JS hash change; auto-refetch; Rhino handles new function patterns transparently |
| ANDROID client stops returning direct URLs | Medium | High | Client fallback chain; WEB client always works |
| YouTube blocks library's requests (rate limit) | Medium | Medium | Respect CDN TTLs, don't hammer API, add jitter to retries |
| Rhino fails on ES6+ nsig function | Low-Medium | High | Switch to QuickJS JNI; add function pre-validation test |
| Age-gate bypass breaks | Medium | Low | ANDROID_VR and WEB_EMBEDDED clients as alternatives |
| Stream URL expiry mid-playback | Low | Medium | Monitor expiry, pre-refresh URLs before expiry with background coroutine |
| PO Token required by ANDROID client | Low (2025) | High | Investigate token providers; fallback to IOS/WEB client |
| Legal challenges | Uncertain | High | See Section 15 |

### Keeping Up with YouTube Changes

Subscribe to:
- [yt-dlp GitHub issues](https://github.com/yt-dlp/yt-dlp/issues) — changes often reported within hours
- yt-dlp changelog for client config updates
- Monitor player JS hash changes (log when it changes)

Version your cached player JS by hash; when a new hash appears, automatically purge and re-extract the nsig function.

---

## 15. Legal & Ethical Notes

- **YouTube ToS §4.B** prohibits automated access to YouTube content without explicit permission.
- This library is designed for **personal, educational, and research use** (e.g., offline-first apps in regions with poor connectivity, accessibility tools, research tools).
- Do not use this library to build commercial services that redistribute YouTube content.
- Consider the YouTube Data API v3 for metadata (quotas apply, but ToS-compliant).
- YouTube Premium subscribers have legitimate offline download rights in the YouTube app; this library does not replicate that entitlement.
- The developer is responsible for ensuring their app's use case complies with applicable laws and YouTube's Terms of Service.

---

## Appendix: Key itag Reference

| itag | Type | Container | Codec | Resolution / Quality |
|---|---|---|---|---|
| 18 | Muxed | MP4 | H.264 + AAC | 360p |
| 22 | Muxed | MP4 | H.264 + AAC | 720p |
| 137 | Video | MP4 | H.264 | 1080p |
| 248 | Video | WebM | VP9 | 1080p |
| 313 | Video | WebM | VP9 | 2160p (4K) |
| 394–401 | Video | MP4 | AV1 | 144p–2160p |
| 140 | Audio | MP4 | AAC-LC | 128kbps |
| 141 | Audio | MP4 | AAC-LC | 256kbps |
| 251 | Audio | WebM | Opus | ~160kbps |
| 171 | Audio | WebM | Vorbis | ~128kbps |

## Appendix: InnerTube API Client Versions (as of 2025)

> These must be kept up-to-date as YouTube rejects severely outdated client versions.
> Cross-reference with yt-dlp's `INNERTUBE_CLIENTS` dict in `youtube.py`.

| Client Name | Client Number | Version (example) |
|---|---|---|
| ANDROID | 3 | 19.09.37 |
| ANDROID_VR | 28 | 1.57.29 |
| IOS | 5 | 19.09.3 |
| WEB | 1 | 2.20240101 |
| MWEB | 2 | 2.20240101 |
| WEB_EMBEDDED | 56 | 2.20240101 |
| TV_EMBEDDED | 85 | 2.0 |

---

*Document version: 1.0 — 2026-05-11*  
*Reference: https://github.com/yt-dlp/yt-dlp (MIT License)*
