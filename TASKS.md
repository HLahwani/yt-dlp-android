# YTDLPDroid — Detailed Implementation Tasks

> **Stack decisions locked in:**
> - JS engine: **QuickJS via JNI** (primary, no Rhino dependency)
> - Distribution: **single AAR** (no sub-modules, client adds one dependency)
> - Scope: **public videos only**, single video from playlist URLs, geo-restriction deferred
> - Publishing: **local AAR** for now
> - Project: **greenfield**

**Legend** — effort is solo-developer estimates, assumes familiarity with Android/NDK.  
`[deps: T-XX]` = must complete that task first.

---

## Phase 1 — Project Foundation & NDK Setup

---

### T-01 · Create Gradle project skeleton

**Effort**: 2 h

**What to do**

1. Create a new Android project in Android Studio with **no Activity** (File → New → New Project → No Activity).
2. Delete the default `app` module. Add two modules:
   - `library` — Android Library (`com.android.library`)
   - `sample` — Android Application (`com.android.application`)
3. Root `settings.gradle.kts`:
   ```kotlin
   rootProject.name = "YTDLPDroid"
   include(":library", ":sample")
   ```
4. Root `build.gradle.kts` — declare shared version catalog or `ext` versions block for all dependency versions (OkHttp, coroutines, serialization, Media3, etc.).
5. Set `compileSdk = 35`, `minSdk = 21`, `targetSdk = 35` in both modules.
6. Enable `buildFeatures { buildConfig = false }` in the library module (no BuildConfig needed).
7. Commit the skeleton with a `.gitignore` that excludes `*.iml`, `.gradle/`, `local.properties`, `build/`.

**Acceptance criteria**
- `./gradlew :library:assembleDebug` and `./gradlew :sample:assembleDebug` both succeed with no source files beyond the manifests.

---

### T-02 · Configure library `build.gradle.kts` with all dependencies

**Effort**: 1 h  
**Deps**: T-01

**What to do**

Add to `library/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android") version "2.0.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.0"
}

android {
    namespace = "com.ytdlpdroid"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        // NDK ABI filter — arm64-v8a covers 95%+ of active Android devices
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("io.mockk:mockk:1.13.12")
}
```

Create `library/consumer-rules.pro` (empty for now — content added in T-27).

**Acceptance criteria**
- `./gradlew :library:dependencies` resolves without errors.
- NDK is detected (requires Android Studio NDK installed; if not: SDK Manager → NDK Side-by-side).

---

### T-03 · Integrate QuickJS C source into the project

**Effort**: 3 h  
**Deps**: T-02

**What to do**

QuickJS is a self-contained C library (~50 source files). Do **not** use a third-party Maven wrapper — embed the source directly so the library AAR contains the `.so` without requiring the caller to add a separate dependency.

1. Download the latest QuickJS release from `https://github.com/bellard/quickjs/releases` (e.g. `quickjs-2024-01-13.tar.xz`).
2. Create directory `library/src/main/cpp/quickjs/`.
3. Copy these files from the archive into that directory:
   - `quickjs.c`, `quickjs.h`
   - `quickjs-libc.c`, `quickjs-libc.h`
   - `libregexp.c`, `libregexp.h`
   - `libunicode.c`, `libunicode.h`
   - `libbf.c`, `libbf.h`
   - `cutils.c`, `cutils.h`
   - `dtoa.c`
   - `quickjs-atom.h`, `quickjs-opcode.h`
4. Create `library/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("ytdlpdroid")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -fvisibility=hidden")

add_library(
    quickjs_bridge
    SHARED
    quickjs/quickjs.c
    quickjs/quickjs-libc.c
    quickjs/libregexp.c
    quickjs/libunicode.c
    quickjs/libbf.c
    quickjs/cutils.c
    quickjs/dtoa.c
    quickjs_bridge.cpp
)

target_include_directories(quickjs_bridge PRIVATE quickjs/)

target_compile_definitions(quickjs_bridge PRIVATE
    CONFIG_VERSION="2024-01-13"
    CONFIG_BIGNUM
)

find_library(log-lib log)
target_link_libraries(quickjs_bridge ${log-lib})
```

5. Verify `./gradlew :library:assembleDebug` compiles the C sources (the `.so` appears in `build/intermediates/cmake/`).

**Acceptance criteria**
- Build succeeds for at least `arm64-v8a`.
- No `undefined reference` linker errors.
- The shared library file `libquickjs_bridge.so` exists in the debug AAR.

**Notes**
- `CONFIG_BIGNUM` enables BigInt / BigFloat — the nsig function may use BigInt-style operations.
- `-O2` is safe for C on Android; do not use `-O3` (can cause miscompilation on some NDK versions).

---

### T-04 · Write the QuickJS JNI C++ bridge

**Effort**: 4 h  
**Deps**: T-03

**What to do**

Create `library/src/main/cpp/quickjs_bridge.cpp`. This file exposes exactly **one** JNI function to Kotlin: execute a JavaScript string that returns a string.

```cpp
#include <jni.h>
#include <string>
#include <android/log.h>
#include "quickjs/quickjs.h"
#include "quickjs/quickjs-libc.h"

#define LOG_TAG "QuickJsBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_com_ytdlpdroid_js_QuickJsEngine_executeNative(
        JNIEnv *env,
        jobject /* this */,
        jstring jsCode,
        jstring argument) {

    const char *code = env->GetStringUTFChars(jsCode, nullptr);
    const char *arg  = env->GetStringUTFChars(argument, nullptr);

    JSRuntime *rt = JS_NewRuntime();
    JS_SetMemoryLimit(rt, 8 * 1024 * 1024);   // 8 MB — nsig functions are tiny
    JS_SetMaxStackSize(rt, 512 * 1024);         // 512 KB stack

    JSContext *ctx = JS_NewContext(rt);

    // Wrap: call the user-supplied function with the argument
    // Callers must wrap their function as: (function(n){ <body> })
    std::string wrappedCode = std::string(code) + "\n__result__(" + "\"" + std::string(arg) + "\");";

    jstring result = nullptr;

    JSValue val = JS_Eval(ctx, wrappedCode.c_str(), wrappedCode.size(),
                          "<nsig>", JS_EVAL_TYPE_GLOBAL);

    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(ctx);
        const char *msg = JS_ToCString(ctx, exc);
        LOGE("QuickJS exception: %s", msg ? msg : "unknown");
        JS_FreeCString(ctx, msg);
        JS_FreeValue(ctx, exc);
        // Return null — Kotlin side throws YTDLPError.DecipherFailed
    } else {
        const char *str = JS_ToCString(ctx, val);
        if (str) {
            result = env->NewStringUTF(str);
            JS_FreeCString(ctx, str);
        }
    }

    JS_FreeValue(ctx, val);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);

    env->ReleaseStringUTFChars(jsCode, code);
    env->ReleaseStringUTFChars(argument, arg);

    return result;
}
```

**Design note on the calling convention**: The Kotlin side will rename the extracted function to `__result__` before passing it here, so the C++ code always calls `__result__(arg)`. This avoids the C++ layer needing to know the function name.

**Acceptance criteria**
- Compiles without warnings for all three ABI targets.
- A manual JNI call from a test Activity passing `"(function(n){ return n.split('').reverse().join(''); })"` and `"hello"` returns `"olleh"`.

---

### T-05 · Write the `QuickJsEngine` Kotlin wrapper

**Effort**: 2 h  
**Deps**: T-04

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/js/JsEngine.kt`:

```kotlin
package com.ytdlpdroid.js

internal interface JsEngine {
    /**
     * Executes [functionCode] (a JS function expression) with [argument] as its single
     * string parameter and returns the string result.
     *
     * [functionCode] must be a JS expression that evaluates to a function,
     * e.g. "function(n) { ... }". The engine assigns it to __result__ internally.
     */
    fun execute(functionCode: String, argument: String): String
}
```

Create `library/src/main/kotlin/com/ytdlpdroid/js/QuickJsEngine.kt`:

```kotlin
package com.ytdlpdroid.js

import com.ytdlpdroid.model.YTDLPError

internal class QuickJsEngine : JsEngine {

    init {
        System.loadLibrary("quickjs_bridge")
    }

    override fun execute(functionCode: String, argument: String): String {
        // Rename function to __result__ so the C++ bridge can always call __result__(arg)
        val wrappedCode = "var __result__ = $functionCode;"
        return executeNative(wrappedCode, argument)
            ?: throw YTDLPError.DecipherFailed("QuickJS returned null for argument: $argument")
    }

    private external fun executeNative(jsCode: String, argument: String): String?
}
```

**Acceptance criteria**
- Unit test: `QuickJsEngine().execute("function(n){ return n + '_ok'; }", "test")` returns `"test_ok"`.
- Unit test: malformed JS throws `YTDLPError.DecipherFailed`.

---

### T-06 · Define all error types

**Effort**: 1 h  
**Deps**: T-01

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/model/YTDLPError.kt`:

```kotlin
package com.ytdlpdroid.model

sealed class YTDLPError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class InvalidUrl(input: String) :
        YTDLPError("Not a recognisable YouTube URL or video ID: \"$input\"")

    class VideoUnavailable(videoId: String, reason: String?) :
        YTDLPError("Video $videoId is unavailable${reason?.let { ": $it" } ?: ""}")

    class AgeRestricted(videoId: String) :
        YTDLPError("Video $videoId requires age verification — all clients failed to bypass")

    class LiveStreamNotSupported(videoId: String) :
        YTDLPError("Video $videoId is a live stream; live streams are not supported")

    class NoStreamsFound(videoId: String) :
        YTDLPError("No playable streams found for video $videoId")

    class DecipherFailed(reason: String) :
        YTDLPError("Stream URL decryption failed: $reason")

    class NetworkError(reason: String, cause: Throwable) :
        YTDLPError("Network error: $reason", cause)

    class AllClientsFailed(videoId: String) :
        YTDLPError("All InnerTube clients failed to retrieve streams for video $videoId")

    class PlayerJsFetchFailed(jsUrl: String, cause: Throwable) :
        YTDLPError("Failed to fetch player JS from $jsUrl", cause)
}
```

**Acceptance criteria**
- All error types compile.
- Each error is a distinct subtype that callers can catch individually.

---

## Phase 2 — URL Parsing & InnerTube API Client

---

### T-07 · Implement `VideoIdParser`

**Effort**: 2 h  
**Deps**: T-06

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/extractor/VideoIdParser.kt`.

Handle all of these input formats:

| Input | Expected video ID |
|---|---|
| `https://www.youtube.com/watch?v=dQw4w9WgXcQ` | `dQw4w9WgXcQ` |
| `https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&index=2` | `dQw4w9WgXcQ` (ignore playlist params) |
| `https://youtu.be/dQw4w9WgXcQ` | `dQw4w9WgXcQ` |
| `https://youtu.be/dQw4w9WgXcQ?t=42` | `dQw4w9WgXcQ` |
| `https://www.youtube.com/embed/dQw4w9WgXcQ` | `dQw4w9WgXcQ` |
| `https://www.youtube.com/shorts/dQw4w9WgXcQ` | `dQw4w9WgXcQ` |
| `https://music.youtube.com/watch?v=dQw4w9WgXcQ` | `dQw4w9WgXcQ` |
| `https://m.youtube.com/watch?v=dQw4w9WgXcQ` | `dQw4w9WgXcQ` |
| `dQw4w9WgXcQ` (raw ID) | `dQw4w9WgXcQ` |

```kotlin
internal object VideoIdParser {
    // A YouTube video ID is exactly 11 characters: [A-Za-z0-9_-]
    private val PATTERNS = listOf(
        Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        Regex("""youtube\.com/(?:embed|shorts|v)/([A-Za-z0-9_-]{11})"""),
        Regex("""^([A-Za-z0-9_-]{11})$"""),
    )

    fun parse(input: String): String {
        val trimmed = input.trim()
        for (pattern in PATTERNS) {
            pattern.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        }
        throw YTDLPError.InvalidUrl(trimmed)
    }
}
```

**Acceptance criteria**
- Unit tests cover all rows in the table above.
- Unit test: playlist URL returns only the video ID (not the playlist ID).
- Unit test: invalid input (`"not_a_url"`, `""`, `"https://vimeo.com/123"`) throws `YTDLPError.InvalidUrl`.

---

### T-08 · Define domain models for stream formats and results

**Effort**: 2 h  
**Deps**: T-06

**What to do**

Create these data classes under `com.ytdlpdroid.model`:

**`StreamFormat.kt`**
```kotlin
data class StreamFormat(
    val itag: Int,
    val url: String,            // fully deciphered, ready to play
    val mimeType: String,
    val codec: String,          // e.g. "avc1.64001f", "vp9", "av01.0.08M.08"
    val bitrate: Long,
    val width: Int?,            // null for audio-only
    val height: Int?,
    val fps: Int?,
    val audioSampleRate: Int?,  // null for video-only
    val contentLength: Long?,
    val initRange: LongRange?,
    val indexRange: LongRange?,
    val approximateDurationMs: Long,
)
```

**`VideoInfo.kt`**
```kotlin
data class VideoInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String,
    val durationSeconds: Long,
    val isLive: Boolean,
    val thumbnailUrl: String,   // highest-resolution thumbnail available
    val keywords: List<String>,
)
```

**`StreamResult.kt`**
```kotlin
data class StreamResult(
    val videoId: String,
    val metadata: VideoInfo,
    val videoStream: StreamFormat?,   // null only if video is audio-only
    val audioStream: StreamFormat,
    val muxedStream: StreamFormat?,   // best muxed format (itag 22/18) as fallback
    val expiresAt: Long,              // System.currentTimeMillis() + expiresInSeconds * 1000
)
```

**`ExtractionOptions.kt`**
```kotlin
data class ExtractionOptions(
    val maxVideoHeight: Int? = null,          // null = highest available
    val preferH264: Boolean = false,          // force H.264 for broad device compatibility
    val preferOpusAudio: Boolean = true,      // Opus > AAC by default
    val includeMuxedFallback: Boolean = true, // include itag 18/22 as muxedStream
)
```

**Acceptance criteria**
- All models compile.
- All fields have a sensible default or are clearly nullable.

---

### T-09 · Define raw JSON models for InnerTube player response

**Effort**: 3 h  
**Deps**: T-02, T-08

**What to do**

These are `@Serializable` data classes that mirror the JSON structure returned by InnerTube. They are **internal** — the public API uses the domain models from T-08.

Create `library/src/main/kotlin/com/ytdlpdroid/innertube/model/` and add:

**`RawPlayerResponse.kt`**
```kotlin
@Serializable
internal data class RawPlayerResponse(
    val playabilityStatus: RawPlayabilityStatus,
    val streamingData: RawStreamingData? = null,
    val videoDetails: RawVideoDetails? = null,
)

@Serializable
internal data class RawPlayabilityStatus(
    val status: String,                     // "OK", "LOGIN_REQUIRED", "UNPLAYABLE", "ERROR"
    val reason: String? = null,
)

@Serializable
internal data class RawStreamingData(
    val expiresInSeconds: String? = null,
    val formats: List<RawFormat> = emptyList(),
    val adaptiveFormats: List<RawFormat> = emptyList(),
)

@Serializable
internal data class RawFormat(
    val itag: Int,
    val url: String? = null,
    val signatureCipher: String? = null,
    val mimeType: String,
    val bitrate: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val qualityLabel: String? = null,
    val audioQuality: String? = null,
    val audioSampleRate: String? = null,
    val approxDurationMs: String? = null,
    val contentLength: String? = null,
    val initRange: RawRange? = null,
    val indexRange: RawRange? = null,
)

@Serializable
internal data class RawRange(val start: String, val end: String)

@Serializable
internal data class RawVideoDetails(
    val videoId: String,
    val title: String,
    val lengthSeconds: String? = null,
    val author: String? = null,
    val channelId: String? = null,
    val isLiveContent: Boolean = false,
    val keywords: List<String> = emptyList(),
    val thumbnail: RawThumbnailList? = null,
)

@Serializable
internal data class RawThumbnailList(val thumbnails: List<RawThumbnail> = emptyList())

@Serializable
internal data class RawThumbnail(val url: String, val width: Int = 0, val height: Int = 0)
```

Configure the JSON decoder to be lenient (YouTube adds new fields regularly):
```kotlin
internal val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}
```

**Acceptance criteria**
- Unit test: parse the sample JSON fixture from `test/resources/player_response_ok.json` (create a real-looking but anonymised fixture) without throwing.
- Unit test: parse a response where `streamingData` is absent (unavailable video) without throwing.

---

### T-10 · Implement `InnerTubeClientConfig` sealed class

**Effort**: 2 h  
**Deps**: T-02

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/innertube/InnerTubeClientConfig.kt`.

These constants come from yt-dlp's `INNERTUBE_CLIENTS` dict. They change infrequently but must be updated when YouTube rejects old client versions.

```kotlin
internal sealed class InnerTubeClientConfig(
    val clientName: String,
    val clientNumber: String,          // X-YouTube-Client-Name header value
    val clientVersion: String,
    val userAgent: String,
    val androidSdkVersion: Int? = null,
) {
    object ANDROID : InnerTubeClientConfig(
        clientName = "ANDROID",
        clientNumber = "3",
        clientVersion = "19.09.37",
        userAgent = "com.google.android.youtube/19.09.37 (Linux; U; Android 11; sdk_gphone_x86 Build/RSR1.201013.001) gzip",
        androidSdkVersion = 30,
    )

    object ANDROID_VR : InnerTubeClientConfig(
        clientName = "ANDROID_VR",
        clientNumber = "28",
        clientVersion = "1.57.29",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.57.29 (Linux; U; Android 12; eureka-user Build/SQ3A.220605.009.A1) gzip",
        androidSdkVersion = 32,
    )

    object WEB_EMBEDDED : InnerTubeClientConfig(
        clientName = "WEB_EMBEDDED_PLAYER",
        clientNumber = "56",
        clientVersion = "2.20231219.01.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    )

    object WEB : InnerTubeClientConfig(
        clientName = "WEB",
        clientNumber = "1",
        clientVersion = "2.20231219.01.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    )
}
```

Also create a companion function that builds the JSON request body from a config:

```kotlin
internal fun InnerTubeClientConfig.buildRequestBody(videoId: String): String {
    // Build using kotlinx.serialization or manual string construction
    // Must include: context.client, videoId, racyCheckOk, contentCheckOk
}
```

**Acceptance criteria**
- Each config object has all fields populated.
- `buildRequestBody("test123")` produces valid JSON with the correct `clientName` field.
- Unit test: JSON body contains `"videoId": "test123"`.

---

### T-11 · Implement `HttpClientProvider`

**Effort**: 1 h  
**Deps**: T-02

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/network/HttpClientProvider.kt`.

```kotlin
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
            if (attempt <= maxRetries) Thread.sleep(500L * attempt)  // 0.5s, 1s, 1.5s backoff
        }
        return lastResponse!!
    }
}
```

Allow the caller to inject a custom `OkHttpClient` via `YTDLPDroid.Builder` (see T-25) to override this default (e.g., for adding a proxy, custom cache, or logging interceptor in the sample app).

**Acceptance criteria**
- Compiles. No real network call needed at this stage.

---

### T-12 · Implement `InnerTubeClient`

**Effort**: 3 h  
**Deps**: T-09, T-10, T-11

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/innertube/InnerTubeClient.kt`.

This class makes the POST request and returns a parsed `RawPlayerResponse`.

```kotlin
internal class InnerTubeClient(
    private val httpClient: OkHttpClient = HttpClientProvider.client,
) {
    suspend fun fetchPlayerResponse(
        videoId: String,
        config: InnerTubeClientConfig,
    ): RawPlayerResponse = withContext(Dispatchers.IO) {
        val body = config.buildRequestBody(videoId)
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player")
            .post(body)
            .header("User-Agent", config.userAgent)
            .header("X-YouTube-Client-Name", config.clientNumber)
            .header("X-YouTube-Client-Version", config.clientVersion)
            .header("Content-Type", "application/json")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw YTDLPError.NetworkError("InnerTube player request failed", e)
        }

        response.use {
            if (!it.isSuccessful) throw YTDLPError.NetworkError("HTTP ${it.code}", IOException(it.message))
            val bodyStr = it.body?.string() ?: throw YTDLPError.NetworkError("Empty response body", IOException())
            json.decodeFromString<RawPlayerResponse>(bodyStr)
        }
    }
}
```

**Acceptance criteria**
- MockWebServer test: server returns a canned `player_response_ok.json`, client parses it and returns a non-null `streamingData`.
- MockWebServer test: server returns HTTP 429, client retries and ultimately throws `YTDLPError.NetworkError`.
- MockWebServer test: server returns a response with `playabilityStatus.status = "LOGIN_REQUIRED"`, client returns it (no exception — the caller handles status).

---

## Phase 3 — Player JS, Deciphering, n-Param

---

### T-13 · Implement `PlayerJsRepository`

**Effort**: 3 h  
**Deps**: T-11, T-06

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/decipher/PlayerJsRepository.kt`.

Responsibilities:
- Fetch the player JS text over HTTP (it's a plain JS file, ~1 MB).
- Cache it on disk, keyed by the full URL (the URL contains a version hash, so it is self-invalidating).
- Provide the raw JS text to callers.

```kotlin
internal class PlayerJsRepository(
    private val cacheDir: File,
    private val httpClient: OkHttpClient = HttpClientProvider.client,
) {
    // In-memory: maps playerJsUrl → jsText (only last 2 player versions needed)
    private val memoryCache = HashMap<String, String>(2)

    suspend fun fetchPlayerJs(playerJsUrl: String): String = withContext(Dispatchers.IO) {
        memoryCache[playerJsUrl]?.let { return@withContext it }

        val cacheKey = playerJsUrl.hashCode().toString()
        val cacheFile = File(cacheDir, "playerjs_$cacheKey.js")

        if (cacheFile.exists() && cacheFile.length() > 0) {
            val text = cacheFile.readText()
            memoryCache[playerJsUrl] = text
            return@withContext text
        }

        val url = if (playerJsUrl.startsWith("http")) playerJsUrl
                  else "https://www.youtube.com$playerJsUrl"

        val response = try {
            httpClient.newCall(Request.Builder().url(url).build()).execute()
        } catch (e: IOException) {
            throw YTDLPError.PlayerJsFetchFailed(url, e)
        }

        val text = response.use {
            if (!it.isSuccessful) throw YTDLPError.PlayerJsFetchFailed(url, IOException("HTTP ${it.code}"))
            it.body?.string() ?: throw YTDLPError.PlayerJsFetchFailed(url, IOException("Empty body"))
        }

        cacheFile.writeText(text)
        memoryCache[playerJsUrl] = text
        text
    }

    // Evict old cached JS files (keep only the 2 newest)
    fun evictOldCache() {
        cacheDir.listFiles { f -> f.name.startsWith("playerjs_") && f.name.endsWith(".js") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(2)
            ?.forEach { it.delete() }
    }
}
```

**How to get the player JS URL**: It is embedded in the YouTube watch page HTML and also in some InnerTube responses. Add a helper function that fetches `https://www.youtube.com/watch?v=<videoId>` and extracts it:

```kotlin
suspend fun fetchPlayerJsUrl(videoId: String): String = withContext(Dispatchers.IO) {
    // GET the watch page, extract player JS URL with regex
    val html = httpClient.newCall(
        Request.Builder().url("https://www.youtube.com/watch?v=$videoId")
            .header("User-Agent", "Mozilla/5.0 ...")
            .build()
    ).execute().use { it.body?.string() ?: "" }

    Regex(""""jsUrl"\s*:\s*"(/s/player/[a-f0-9]+/[^"]+base\.js)"""")
        .find(html)?.groupValues?.get(1)
        ?: Regex("""(/s/player/[a-f0-9]+/player_ias\.vflset/[a-z]{2}_[A-Z]{2}/base\.js)""")
            .find(html)?.groupValues?.get(1)
        ?: throw YTDLPError.PlayerJsFetchFailed("watch page", IOException("player JS URL not found in page"))
}
```

**Acceptance criteria**
- Second call with the same URL reads from memory (no network).
- After a cold start, a previously-cached file is returned without a network call.
- `evictOldCache()` deletes files beyond the newest two.

---

### T-14 · Implement `NParamDecipherer`

**Effort**: 4 h  
**Deps**: T-05, T-13

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/decipher/NParamDecipherer.kt`.

This is the most critical component. The `n` parameter in each stream URL must be transformed by a JavaScript function extracted from the player JS.

```kotlin
internal class NParamDecipherer(
    private val jsEngine: JsEngine,
) {
    // Cache: playerJsHash → extracted function code
    private val functionCache = HashMap<Int, String>(4)

    fun transform(url: String, playerJs: String): String {
        val nValue = extractNParam(url) ?: return url  // no n param → return as-is
        val fnCode  = getOrExtractFunction(playerJs)
        val transformed = jsEngine.execute(fnCode, nValue)
        return url.replace(Regex("""([?&]n=)[^&]+"""), "$1$transformed")
    }

    private fun extractNParam(url: String): String? =
        Regex("""[?&]n=([^&]+)""").find(url)?.groupValues?.get(1)

    private fun getOrExtractFunction(playerJs: String): String {
        val key = playerJs.hashCode()
        return functionCache.getOrPut(key) { extractNFunction(playerJs) }
    }

    // Extracts the nsig transform function from player JS.
    // The function name is referenced near the 'n' parameter handling code.
    private fun extractNFunction(js: String): String {
        val fnName = extractNFunctionName(js)
            ?: throw YTDLPError.DecipherFailed("Could not find nsig function name in player JS")
        return extractFunctionBody(js, fnName)
            ?: throw YTDLPError.DecipherFailed("Could not extract nsig function body for: $fnName")
    }

    private fun extractNFunctionName(js: String): String? {
        // Multiple patterns — YouTube changes the surrounding code regularly
        val patterns = listOf(
            Regex("""\.get\("n"\)\)&&\([a-zA-Z0-9$]=([a-zA-Z0-9$]{2,4})\["""),
            Regex("""[a-zA-Z0-9$]\s*&&\s*[a-zA-Z0-9$]\.set\([^,]+\s*,\s*(?:encodeURIComponent\s*)?\(([a-zA-Z0-9$]{2,4})\[0\]\("""),
            Regex("""\.get\("n"\)\)&&\([a-zA-Z]=([a-zA-Z0-9$\[\]]{2,30})\("""),
        )
        for (p in patterns) {
            p.find(js)?.groupValues?.get(1)?.let { name ->
                // name may be "abc[0]" (array element) — resolve the actual function
                return if (name.contains('[')) resolveArrayFunctionName(js, name) else name
            }
        }
        return null
    }

    // When the function is stored in an array (e.g. "g[0]"), find the array definition
    private fun resolveArrayFunctionName(js: String, arrayRef: String): String? {
        val arrayName = arrayRef.substringBefore('[')
        val idx = arrayRef.substringAfter('[').substringBefore(']').toIntOrNull() ?: 0
        val arrayPattern = Regex("""var $arrayName\s*=\s*\[([^\]]+)\]""")
        return arrayPattern.find(js)?.groupValues?.get(1)
            ?.split(',')?.getOrNull(idx)?.trim()
    }

    // Extracts the full function source text: "function fnName(a){...}" or "var fnName=function(a){...}"
    private fun extractFunctionBody(js: String, fnName: String): String? {
        val escapedName = Regex.escape(fnName)
        // Match: fnName=function(a){...} or function fnName(a){...}
        val startPattern = Regex("""(?:$escapedName\s*=\s*function|function\s+$escapedName)\s*\([^)]*\)\s*\{""")
        val match = startPattern.find(js) ?: return null
        val bodyStart = match.range.first
        // Walk the JS to find the matching closing brace
        var depth = 0
        var i = match.range.last   // position of the opening '{'
        while (i < js.length) {
            when (js[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return "function${js.substring(match.range.last - 1, i + 1).dropWhile { it != '(' }}" }
            }
            i++
        }
        return null
    }
}
```

**Acceptance criteria**
- Unit test: given a real (anonymised) player JS fixture containing a known nsig function, `transform(urlWithN, js)` returns a URL with the `n` param changed.
- Unit test: URL with no `n` param is returned unchanged.
- Unit test: same player JS called twice uses cache (function extraction runs once — verify with a counter or spy).
- Unit test: unrecognised player JS format throws `YTDLPError.DecipherFailed`.

---

### T-15 · Implement `SignatureDecipherer` (WEB client fallback)

**Effort**: 3 h  
**Deps**: T-05, T-13

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/decipher/SignatureDecipherer.kt`.

This is only invoked when the WEB client is used as a last resort, since the ANDROID client returns direct URLs.

A `signatureCipher` looks like: `s=<ENCRYPTED>&sp=sig&url=<URL_ENCODED_BASE_URL>`

```kotlin
internal class SignatureDecipherer(
    private val jsEngine: JsEngine,
) {
    private val functionCache = HashMap<Int, String>(2)

    fun decrypt(signatureCipher: String, playerJs: String): String {
        val params = parseSignatureCipher(signatureCipher)
        val encryptedSig = params["s"] ?: throw YTDLPError.DecipherFailed("Missing 's' in signatureCipher")
        val sp = params["sp"] ?: "sig"
        val baseUrl = params["url"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            ?: throw YTDLPError.DecipherFailed("Missing 'url' in signatureCipher")

        val fnCode = getOrExtractSigFunction(playerJs)
        val decryptedSig = jsEngine.execute(fnCode, encryptedSig)

        return "$baseUrl&$sp=$decryptedSig"
    }

    private fun parseSignatureCipher(cipher: String): Map<String, String> =
        cipher.split('&').associate {
            val eq = it.indexOf('=')
            it.substring(0, eq) to it.substring(eq + 1)
        }

    private fun getOrExtractSigFunction(js: String): String {
        val key = js.hashCode()
        return functionCache.getOrPut(key) { extractSigFunction(js) }
    }

    private fun extractSigFunction(js: String): String {
        // 1. Find the sig function name
        val fnName = listOf(
            Regex("""\.sig\|\|([a-zA-Z0-9$]+)\("""),
            Regex("""\.signature\s*=\s*([a-zA-Z0-9$]+)\("""),
            Regex(""""signature",([a-zA-Z0-9$]+)\("""),
        ).firstNotNullOfOrNull { it.find(js)?.groupValues?.get(1) }
            ?: throw YTDLPError.DecipherFailed("Sig function name not found in player JS")

        // 2. Extract the function + its helper object (the sig function delegates to a helper)
        // The helper object contains operations: reverse, slice, swap
        return buildSigFunctionWithHelper(js, fnName)
            ?: throw YTDLPError.DecipherFailed("Could not extract sig function for: $fnName")
    }

    private fun buildSigFunctionWithHelper(js: String, fnName: String): String? {
        // Extract main function body (same brace-matching as NParamDecipherer)
        val mainFn = extractFunctionBody(js, fnName) ?: return null
        // Extract the helper object name from inside the main function
        val helperName = Regex("""([a-zA-Z0-9$]{2,4})\.[a-zA-Z0-9$]+\(""").find(mainFn)
            ?.groupValues?.get(1) ?: return null
        val helperObject = extractHelperObject(js, helperName) ?: return null
        return "var $helperName=$helperObject;\n$mainFn"
    }

    private fun extractHelperObject(js: String, name: String): String? {
        val escaped = Regex.escape(name)
        val start = Regex("""var $escaped\s*=\s*\{""").find(js) ?: return null
        var depth = 0; var i = start.range.last
        while (i < js.length) {
            when (js[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return js.substring(start.range.last - 1, i + 1) } }
            i++
        }
        return null
    }

    private fun extractFunctionBody(js: String, fnName: String): String? {
        // Same implementation as NParamDecipherer — consider extracting to a shared utility
        TODO("Share with NParamDecipherer via a JsFunctionExtractor utility class")
    }
}
```

**Note**: Refactor `extractFunctionBody` into a shared `JsFunctionExtractor` utility class to avoid duplication between T-14 and T-15.

**Acceptance criteria**
- Unit test: given a canned WEB-client format with `signatureCipher`, `decrypt()` returns a URL with `&sig=...` appended.
- Unit test: `decrypt()` throws `YTDLPError.DecipherFailed` when the player JS has no recognisable sig function.

---

### T-16 · Refactor: extract `JsFunctionExtractor` utility

**Effort**: 1 h  
**Deps**: T-14, T-15

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/decipher/JsFunctionExtractor.kt` with the shared `extractFunctionBody(js, fnName)` method used by both T-14 and T-15. Update both classes to call it.

**Acceptance criteria**
- No duplication of the brace-matching logic.
- All existing unit tests still pass.

---

### T-17 · Implement `DecipherService` (facade)

**Effort**: 2 h  
**Deps**: T-14, T-15, T-13

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/decipher/DecipherService.kt`.

```kotlin
internal class DecipherService(
    private val playerJsRepo: PlayerJsRepository,
    private val nParamDecipherer: NParamDecipherer,
    private val signatureDecipherer: SignatureDecipherer,
) {
    suspend fun buildPlayableUrl(
        format: RawFormat,
        playerJsUrl: String,
    ): String {
        val playerJs = playerJsRepo.fetchPlayerJs(playerJsUrl)

        // Step 1: resolve raw URL (direct or signatureCipher)
        val rawUrl = when {
            format.url != null -> format.url
            format.signatureCipher != null -> signatureDecipherer.decrypt(format.signatureCipher, playerJs)
            else -> throw YTDLPError.NoStreamsFound("itag ${format.itag} has neither url nor signatureCipher")
        }

        // Step 2: apply n-param transform (always needed)
        return nParamDecipherer.transform(rawUrl, playerJs)
    }
}
```

**Acceptance criteria**
- Unit test: format with `url` → only n-param transform applied.
- Unit test: format with `signatureCipher` → sig decryption + n-param transform.
- Unit test: format with neither → throws `YTDLPError.NoStreamsFound`.

---

## Phase 4 — Format Selection

---

### T-18 · Implement `MimeTypeParser` and `Codec` enum

**Effort**: 2 h  
**Deps**: T-08

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/format/Codec.kt`:

```kotlin
internal enum class VideoCodec(val priority: Int) {
    AV1(50), VP9_HDR(45), VP9(40), H265(35), H264(30), VP8(10), UNKNOWN(0);
}

internal enum class AudioCodec(val priority: Int) {
    OPUS(50), VORBIS(40), AAC(30), AC3(20), MP3(10), UNKNOWN(0);
}
```

Create `library/src/main/kotlin/com/ytdlpdroid/format/MimeTypeParser.kt`:

```kotlin
internal object MimeTypeParser {
    // mimeType examples:
    //   "video/mp4;codecs=\"avc1.64001f\""
    //   "video/webm;codecs=\"vp9\""
    //   "video/mp4;codecs=\"av01.0.08M.08\""
    //   "audio/webm;codecs=\"opus\""
    //   "audio/mp4;codecs=\"mp4a.40.2\""

    fun isVideo(mimeType: String) = mimeType.startsWith("video/")
    fun isAudio(mimeType: String) = mimeType.startsWith("audio/")

    fun extractCodecString(mimeType: String): String =
        Regex("""codecs="([^"]+)"""").find(mimeType)?.groupValues?.get(1)?.trim() ?: ""

    fun detectVideoCodec(mimeType: String): VideoCodec {
        val c = extractCodecString(mimeType).lowercase()
        return when {
            c.startsWith("av01") || c.startsWith("av1") -> VideoCodec.AV1
            c.contains("vp9") && (c.contains("hdr") || mimeType.contains("vp9.2")) -> VideoCodec.VP9_HDR
            c.contains("vp9") -> VideoCodec.VP9
            c.contains("hvc1") || c.contains("hev1") -> VideoCodec.H265
            c.contains("avc1") || c.contains("avc3") -> VideoCodec.H264
            c.contains("vp8") -> VideoCodec.VP8
            else -> VideoCodec.UNKNOWN
        }
    }

    fun detectAudioCodec(mimeType: String): AudioCodec {
        val c = extractCodecString(mimeType).lowercase()
        return when {
            c.contains("opus") -> AudioCodec.OPUS
            c.contains("vorbis") -> AudioCodec.VORBIS
            c.contains("mp4a") || c.contains("aac") -> AudioCodec.AAC
            c.contains("ac-3") || c.contains("ac3") -> AudioCodec.AC3
            c.contains("mp3") -> AudioCodec.MP3
            else -> AudioCodec.UNKNOWN
        }
    }
}
```

**Acceptance criteria**
- Unit tests for all codec strings listed in the examples above.
- `detectVideoCodec` for `"video/mp4;codecs=\"avc1.64001f\""` returns `H264`.
- `detectAudioCodec` for `"audio/webm;codecs=\"opus\""` returns `OPUS`.

---

### T-19 · Implement `FormatSelector`

**Effort**: 3 h  
**Deps**: T-18, T-09, T-08

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/format/FormatSelector.kt`.

The selector receives a list of `RawFormat` objects that already have their URLs resolved (i.e., after `DecipherService` has processed them), plus the user's `ExtractionOptions`.

```kotlin
internal object FormatSelector {

    fun select(
        formats: List<Pair<RawFormat, String>>,  // (rawFormat, resolvedUrl)
        options: ExtractionOptions,
    ): Triple<StreamFormat?, StreamFormat, StreamFormat?> {  // video, audio, muxed

        val videoFormats = formats
            .filter { (f, _) -> MimeTypeParser.isVideo(f.mimeType) && f.width != null }
            .map { (f, url) -> toStreamFormat(f, url) }

        val audioFormats = formats
            .filter { (f, _) -> MimeTypeParser.isAudio(f.mimeType) && f.width == null }
            .map { (f, url) -> toStreamFormat(f, url) }

        val muxedFormats = formats
            .filter { (f, _) -> MimeTypeParser.isVideo(f.mimeType) && f.audioSampleRate != null }
            .map { (f, url) -> toStreamFormat(f, url) }

        val bestVideo = selectBestVideo(videoFormats, options)
        val bestAudio = selectBestAudio(audioFormats, options)
            ?: throw YTDLPError.NoStreamsFound("No audio-only adaptive stream found")
        val bestMuxed = if (options.includeMuxedFallback) selectBestMuxed(muxedFormats) else null

        return Triple(bestVideo, bestAudio, bestMuxed)
    }

    private fun selectBestVideo(formats: List<StreamFormat>, opts: ExtractionOptions): StreamFormat? {
        return formats
            .filter { opts.maxVideoHeight == null || (it.height ?: 0) <= opts.maxVideoHeight }
            .filter { if (opts.preferH264) MimeTypeParser.detectVideoCodec(it.mimeType) == VideoCodec.H264 else true }
            .maxWithOrNull(
                compareBy<StreamFormat> { it.height ?: 0 }
                    .thenBy { it.fps ?: 0 }
                    .thenBy { MimeTypeParser.detectVideoCodec(it.mimeType).priority }
                    .thenBy { it.bitrate }
            )
    }

    private fun selectBestAudio(formats: List<StreamFormat>, opts: ExtractionOptions): StreamFormat? {
        return formats.maxWithOrNull(
            compareBy<StreamFormat> {
                if (opts.preferOpusAudio) MimeTypeParser.detectAudioCodec(it.mimeType).priority
                else if (MimeTypeParser.detectAudioCodec(it.mimeType) == AudioCodec.AAC) 100
                else MimeTypeParser.detectAudioCodec(it.mimeType).priority
            }.thenBy { it.bitrate }
        )
    }

    private fun selectBestMuxed(formats: List<StreamFormat>): StreamFormat? {
        // Prefer itag 22 (720p H.264) > itag 18 (360p H.264)
        return formats.maxByOrNull { it.height ?: 0 }
    }

    private fun toStreamFormat(raw: RawFormat, url: String): StreamFormat = StreamFormat(
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
```

**Acceptance criteria**
- Unit test: given a list of mixed formats, returns AV1 video over H.264 when both are at the same resolution.
- Unit test: `opts.preferH264 = true` forces H.264 selection.
- Unit test: `opts.maxVideoHeight = 720` excludes 1080p formats.
- Unit test: Opus audio is preferred over AAC by default.
- Unit test: empty format list throws `YTDLPError.NoStreamsFound`.

---

## Phase 5 — Caching

---

### T-20 · Implement `MemoryCache`

**Effort**: 1 h  
**Deps**: T-01

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/cache/MemoryCache.kt`.

```kotlin
internal class MemoryCache<K, V>(private val maxSize: Int) {

    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val map = object : LinkedHashMap<K, Entry<V>>(maxSize + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, Entry<V>>) = size > maxSize
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) { map.remove(key); return null }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V, ttlMs: Long) {
        map[key] = Entry(value, System.currentTimeMillis() + ttlMs)
    }

    @Synchronized
    fun remove(key: K) { map.remove(key) }
}
```

**Acceptance criteria**
- Unit test: get after TTL expiry returns null.
- Unit test: inserting `maxSize + 1` entries evicts the least-recently-used.

---

### T-21 · Implement `ExtractionCache`

**Effort**: 2 h  
**Deps**: T-20, T-08

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/cache/ExtractionCache.kt`.

Wraps a `MemoryCache<String, StreamResult>` and handles TTL calculation from `expiresAt`.

```kotlin
internal class ExtractionCache(capacity: Int = 30) {
    private val cache = MemoryCache<String, StreamResult>(capacity)

    // key: videoId + "_" + clientName (different clients may return different qualities)
    fun get(videoId: String): StreamResult? = cache.get(videoId)

    fun put(videoId: String, result: StreamResult) {
        val ttlMs = result.expiresAt - System.currentTimeMillis() - (5 * 60 * 1000L) // 5-min safety margin
        if (ttlMs > 0) cache.put(videoId, result, ttlMs)
    }

    fun isExpired(videoId: String) = cache.get(videoId) == null
}
```

**Acceptance criteria**
- Unit test: a result cached with `expiresAt = now + 10s` is returned within 5s and null after 10s.

---

## Phase 6 — Core Orchestration & Public API

---

### T-22 · Implement `PlayerResponseParser`

**Effort**: 2 h  
**Deps**: T-09, T-08

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/innertube/PlayerResponseParser.kt`.

Converts `RawPlayerResponse` to `VideoInfo` and validates playability status.

```kotlin
internal object PlayerResponseParser {

    fun extractVideoInfo(raw: RawPlayerResponse): VideoInfo {
        val details = raw.videoDetails ?: error("No videoDetails in response")
        val thumbnails = details.thumbnail?.thumbnails ?: emptyList()
        val bestThumb = thumbnails.maxByOrNull { it.width * it.height }?.url ?: ""
        return VideoInfo(
            videoId = details.videoId,
            title = details.title,
            author = details.author ?: "",
            channelId = details.channelId ?: "",
            durationSeconds = details.lengthSeconds?.toLongOrNull() ?: 0L,
            isLive = details.isLiveContent,
            thumbnailUrl = bestThumb,
            keywords = details.keywords,
        )
    }

    fun checkPlayability(raw: RawPlayerResponse, videoId: String) {
        when (raw.playabilityStatus.status) {
            "OK" -> Unit
            "LOGIN_REQUIRED" -> throw YTDLPError.AgeRestricted(videoId)
            "UNPLAYABLE" -> throw YTDLPError.VideoUnavailable(videoId, raw.playabilityStatus.reason)
            "ERROR" -> throw YTDLPError.VideoUnavailable(videoId, raw.playabilityStatus.reason)
            "LIVE_STREAM_OFFLINE" -> throw YTDLPError.LiveStreamNotSupported(videoId)
            else -> throw YTDLPError.VideoUnavailable(videoId, "status: ${raw.playabilityStatus.status}")
        }
    }

    fun isLive(raw: RawPlayerResponse): Boolean =
        raw.videoDetails?.isLiveContent == true
}
```

**Acceptance criteria**
- Unit test: `checkPlayability` on status `"OK"` does not throw.
- Unit test: status `"LOGIN_REQUIRED"` throws `YTDLPError.AgeRestricted`.
- Unit test: `extractVideoInfo` selects the highest-resolution thumbnail.

---

### T-23 · Implement `YouTubeExtractor` (main pipeline)

**Effort**: 5 h  
**Deps**: T-12, T-17, T-19, T-21, T-22, T-07

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/extractor/YouTubeExtractor.kt`.

This is the heart of the library — it coordinates every component.

```kotlin
internal class YouTubeExtractor(
    private val innerTubeClient: InnerTubeClient,
    private val playerJsRepo: PlayerJsRepository,
    private val decipherService: DecipherService,
    private val extractionCache: ExtractionCache,
) {
    // Client fallback order: direct-URL clients first, sig-cipher (WEB) last
    private val clientChain = listOf(
        InnerTubeClientConfig.ANDROID,
        InnerTubeClientConfig.ANDROID_VR,
        InnerTubeClientConfig.WEB_EMBEDDED,
        InnerTubeClientConfig.WEB,
    )

    suspend fun extract(videoId: String, options: ExtractionOptions): StreamResult {
        extractionCache.get(videoId)?.let { return it }  // cache hit

        val (playerResponse, usedClient) = fetchWithFallback(videoId)

        if (PlayerResponseParser.isLive(playerResponse)) {
            throw YTDLPError.LiveStreamNotSupported(videoId)
        }

        val videoInfo = PlayerResponseParser.extractVideoInfo(playerResponse)
        val streamingData = playerResponse.streamingData
            ?: throw YTDLPError.NoStreamsFound(videoId)

        // Get player JS URL — needed for n-param decryption
        val playerJsUrl = playerJsRepo.fetchPlayerJsUrl(videoId)

        // Resolve all format URLs in parallel (IO-bound)
        val allFormats = (streamingData.adaptiveFormats + streamingData.formats)
        val resolvedFormats = coroutineScope {
            allFormats.map { fmt ->
                async(Dispatchers.IO) {
                    try {
                        fmt to decipherService.buildPlayableUrl(fmt, playerJsUrl)
                    } catch (e: YTDLPError.DecipherFailed) {
                        null  // skip undecipherable formats rather than failing the whole request
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (resolvedFormats.isEmpty()) throw YTDLPError.NoStreamsFound(videoId)

        val (videoStream, audioStream, muxedStream) = FormatSelector.select(resolvedFormats, options)

        val expiresInSeconds = streamingData.expiresInSeconds?.toLongOrNull() ?: 21600L
        val result = StreamResult(
            videoId = videoId,
            metadata = videoInfo,
            videoStream = videoStream,
            audioStream = audioStream,
            muxedStream = muxedStream,
            expiresAt = System.currentTimeMillis() + expiresInSeconds * 1000L,
        )

        extractionCache.put(videoId, result)
        return result
    }

    private suspend fun fetchWithFallback(videoId: String): Pair<RawPlayerResponse, InnerTubeClientConfig> {
        var lastError: Throwable? = null
        for (client in clientChain) {
            try {
                val response = innerTubeClient.fetchPlayerResponse(videoId, client)
                PlayerResponseParser.checkPlayability(response, videoId)
                return response to client
            } catch (e: YTDLPError.AgeRestricted) {
                lastError = e
                continue  // try next client (age gate bypass)
            } catch (e: YTDLPError.NetworkError) {
                lastError = e
                continue
            }
            // Other errors (VideoUnavailable, LiveStream) propagate immediately
        }
        throw lastError ?: YTDLPError.AllClientsFailed(videoId)
    }
}
```

**Acceptance criteria**
- Integration test (real network): `extract("dQw4w9WgXcQ", ExtractionOptions())` returns a `StreamResult` with non-empty `videoStream.url` and `audioStream.url` that respond HTTP 200.
- Unit test (mocked): if ANDROID client returns `LOGIN_REQUIRED`, ANDROID_VR is tried next.
- Unit test (mocked): if all clients return `LOGIN_REQUIRED`, throws `YTDLPError.AllClientsFailed`.
- Unit test: a cached result is returned without any HTTP calls.

---

### T-24 · Implement `DiskCache` for player JS (deduplicate from T-13)

**Effort**: 1 h  
**Deps**: T-13

**What to do**

Review T-13's inline caching code. Extract it into a proper `DiskCache` class that can be shared and tested independently.

```kotlin
internal class DiskCache(private val dir: File) {
    fun get(key: String): String? {
        val file = fileFor(key)
        return if (file.exists() && file.length() > 0) file.readText() else null
    }
    fun put(key: String, value: String) { fileFor(key).writeText(value) }
    fun evictBeyond(keepNewest: Int) {
        dir.listFiles()?.sortedByDescending { it.lastModified() }
            ?.drop(keepNewest)?.forEach { it.delete() }
    }
    private fun fileFor(key: String) = File(dir, key.hashCode().toString() + ".cache")
}
```

Update `PlayerJsRepository` to use `DiskCache`.

**Acceptance criteria**
- Unit test: put then get returns the same value.
- Unit test: `evictBeyond(2)` deletes old files.

---

### T-25 · Implement `YTDLPDroid` public entry point (Builder)

**Effort**: 3 h  
**Deps**: T-23, T-24, T-05

**What to do**

Create `library/src/main/kotlin/com/ytdlpdroid/YTDLPDroid.kt`.

This is the **only** public class the caller needs.

```kotlin
class YTDLPDroid private constructor(
    private val extractor: YouTubeExtractor,
) {
    /**
     * Extract video and audio stream URLs for [url].
     *
     * Must be called from a coroutine. Throws [YTDLPError] subclasses on failure.
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

        /** Provide your own OkHttpClient (e.g. with logging or a proxy). */
        fun httpClient(client: OkHttpClient) = apply { this.httpClient = client }

        /** Override the JS engine. Defaults to QuickJsEngine. */
        fun jsEngine(engine: JsEngine) = apply { this.jsEngine = engine }

        /** Max number of stream results held in memory. Default: 30. */
        fun memoryCacheCapacity(n: Int) = apply { this.memoryCacheCapacity = n }

        fun build(): YTDLPDroid {
            val playerJsCacheDir = File(cacheDir, "ytdlpdroid_playerjs").also { it.mkdirs() }
            val httpClient = this.httpClient ?: HttpClientProvider.client
            val jsEngine = this.jsEngine ?: QuickJsEngine()

            val playerJsRepo = PlayerJsRepository(playerJsCacheDir, httpClient)
            val nDecipherer = NParamDecipherer(jsEngine)
            val sigDecipherer = SignatureDecipherer(jsEngine)
            val decipherService = DecipherService(playerJsRepo, nDecipherer, sigDecipherer)
            val innerTubeClient = InnerTubeClient(httpClient)
            val cache = ExtractionCache(memoryCacheCapacity)
            val extractor = YouTubeExtractor(innerTubeClient, playerJsRepo, decipherService, cache)

            return YTDLPDroid(extractor)
        }
    }
}
```

**Acceptance criteria**
- `YTDLPDroid.Builder(cacheDir).build()` compiles and builds without errors.
- The public API surface exposes only: `YTDLPDroid`, `ExtractionOptions`, `StreamResult`, `VideoInfo`, `StreamFormat`, `YTDLPError` — nothing internal leaks.
- Integration test in the `sample` module: build a `YTDLPDroid` and call `extract()`.

---

## Phase 7 — Sample App & Media3 Integration

---

### T-26 · Configure the sample app module

**Effort**: 2 h  
**Deps**: T-25

**What to do**

Configure `sample/build.gradle.kts`:

```kotlin
dependencies {
    implementation(project(":library"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")

    // Media3
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
}
```

Add `INTERNET` permission to `sample/src/main/AndroidManifest.xml`.

**Acceptance criteria**
- `./gradlew :sample:assembleDebug` succeeds.

---

### T-27 · Build the sample app UI (URL input → playback)

**Effort**: 4 h  
**Deps**: T-26

**What to do**

Create a single `MainActivity` with:
- An `EditText` for the YouTube URL.
- A "Play" button.
- A `PlayerView` from Media3.
- A `ProgressBar` shown during extraction.
- A `TextView` for error messages.

The ViewModel calls `ytdlp.extract(url)` in a coroutine and posts the result to LiveData. The Activity observes and sets up the player.

**`PlayerViewModel.kt`**:
```kotlin
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val ytdlp = YTDLPDroid.Builder(application.cacheDir).build()

    val state = MutableLiveData<PlayerState>(PlayerState.Idle)

    fun play(url: String) {
        state.value = PlayerState.Loading
        viewModelScope.launch {
            try {
                val result = ytdlp.extract(url)
                state.value = PlayerState.Ready(result)
            } catch (e: YTDLPError) {
                state.value = PlayerState.Error(e.message ?: "Unknown error")
            }
        }
    }
}

sealed class PlayerState {
    object Idle : PlayerState()
    object Loading : PlayerState()
    data class Ready(val result: StreamResult) : PlayerState()
    data class Error(val message: String) : PlayerState()
}
```

**`MainActivity.kt`** — Media3 setup when `PlayerState.Ready`:

```kotlin
private fun setupPlayer(result: StreamResult) {
    val dataSourceFactory = OkHttpDataSource.Factory(/* okhttp client */)
        .setDefaultRequestProperties(mapOf(
            "Referer" to "https://www.youtube.com/",
            "Origin" to "https://www.youtube.com",
        ))

    val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

    val videoSource = result.videoStream?.let {
        mediaSourceFactory.createMediaSource(MediaItem.fromUri(it.url))
    }
    val audioSource = mediaSourceFactory.createMediaSource(
        MediaItem.fromUri(result.audioStream.url)
    )

    val source = if (videoSource != null) {
        MergingMediaSource(videoSource, audioSource)
    } else {
        audioSource
    }

    player.setMediaSource(source)
    player.prepare()
    player.play()
}
```

**Acceptance criteria**
- Entering a valid YouTube URL and tapping Play starts video playback within ~3 seconds on WiFi.
- Entering an invalid URL shows a readable error message.
- Screen rotation does not restart extraction (ViewModel survives rotation).

---

## Phase 8 — Hardening, ProGuard & AAR Export

---

### T-28 · Write `consumer-rules.pro`

**Effort**: 1 h  
**Deps**: T-25

**What to do**

Fill in `library/consumer-rules.pro` (these rules are applied to any app that uses the AAR):

```proguard
# Public API — must not be renamed or removed
-keep public class com.ytdlpdroid.YTDLPDroid { public *; }
-keep public class com.ytdlpdroid.model.** { public *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# QuickJS JNI — keep native method declarations
-keepclasseswithmembernames class com.ytdlpdroid.js.QuickJsEngine {
    native <methods>;
}
```

**Acceptance criteria**
- Release build of the sample app (`./gradlew :sample:assembleRelease`) runs without `ClassNotFoundException` or JNI errors.

---

### T-29 · Add comprehensive unit tests

**Effort**: 4 h  
**Deps**: T-07 through T-23

**What to do**

For each component below, add the following tests if not already written inline in the component tasks:

| Component | Key tests to add |
|---|---|
| `VideoIdParser` | All 9 URL formats, playlist URL strips playlist params, invalid inputs |
| `MimeTypeParser` | All codec variants from Appendix |
| `FormatSelector` | Quality ranking, codec preference, height cap, empty list |
| `NParamDecipherer` | Real nsig function fixture, no-n-param passthrough, cache hit |
| `SignatureDecipherer` | signatureCipher round-trip with real player JS fixture |
| `DecipherService` | Direct URL path, signatureCipher path, missing URL path |
| `InnerTubeClient` | MockWebServer: 200 + parse, 429 + retry, invalid JSON |
| `PlayerResponseParser` | All playability statuses, live stream detection, thumbnail selection |
| `MemoryCache` | LRU eviction, TTL expiry |
| `YouTubeExtractor` | Client fallback chain (mocked), cache hit, live stream rejection |

Create fixture files in `library/src/test/resources/`:
- `player_response_ok.json` — real-looking ANDROID client response with several formats
- `player_response_login_required.json`
- `player_response_unplayable.json`
- `player_js_fragment.js` — a snippet of real player JS containing a known nsig function (for testing extraction without real network)

**Acceptance criteria**
- `./gradlew :library:test` passes all tests.
- Coverage on core logic classes (`NParamDecipherer`, `FormatSelector`, `VideoIdParser`) above 80%.

---

### T-30 · Integration test with real YouTube URLs

**Effort**: 2 h  
**Deps**: T-25, T-29

**What to do**

Create `library/src/test/kotlin/.../integration/RealExtractionTest.kt`.

Mark these tests with `@Ignore` by default — they require internet access and should be run manually or in a dedicated CI step.

```kotlin
@Ignore("Requires internet — run manually")
class RealExtractionTest {

    private val ytdlp = YTDLPDroid.Builder(File(System.getProperty("java.io.tmpdir"), "ytdlpdroid_test"))
        .build()

    @Test
    fun `standard video returns valid stream URLs`() = runBlocking {
        val result = ytdlp.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw") // Me at the zoo
        assertThat(result.audioStream.url).isNotEmpty()
        // Verify stream URLs respond HTTP 200
        val audioResponse = OkHttpClient().newCall(Request.Builder().url(result.audioStream.url).head().build()).execute()
        assertThat(audioResponse.code).isEqualTo(200)
    }

    @Test
    fun `video from playlist URL returns single video`() = runBlocking {
        // URL with both v= and list= — only v= should be used
        val result = ytdlp.extract("https://www.youtube.com/watch?v=jNQXAC9IVRw&list=PL123&index=1")
        assertThat(result.videoId).isEqualTo("jNQXAC9IVRw")
    }

    @Test
    fun `unavailable video throws VideoUnavailable`() = runBlocking {
        assertThrows<YTDLPError.VideoUnavailable> {
            ytdlp.extract("https://www.youtube.com/watch?v=xxxxxxxxxxx")
        }
    }
}
```

**Acceptance criteria**
- All three tests pass when run with internet access.

---

### T-31 · Build and export the release AAR

**Effort**: 1 h  
**Deps**: T-28

**What to do**

1. Run `./gradlew :library:assembleRelease`.
2. The AAR is output to `library/build/outputs/aar/library-release.aar`.
3. Verify the AAR size is within the target budget (≤ 3 MB without native `.so` files; the native module adds ~1.5 MB per included ABI).
4. To use the AAR in another project:
   - Copy `library-release.aar` to the other project's `libs/` folder.
   - In that project's `build.gradle.kts`: `implementation(files("libs/library-release.aar"))`
   - Add the transitive dependencies (OkHttp, kotlinx-serialization, kotlinx-coroutines) to that project.
5. Document all transitive dependencies the caller must add in a `DEPENDENCIES.md` file.

**Acceptance criteria**
- AAR exists and is non-zero.
- A fresh Android project can import and call `YTDLPDroid.Builder(...).build()` without build errors.

---

## Maintenance Tasks (Do When YouTube Updates Break Things)

---

### T-M1 · Update InnerTube client versions

**Trigger**: YouTube returns HTTP 400 or `{"error": {"code": 400, "message": "API version not supported"}}`.

**What to do**: Update version strings in `InnerTubeClientConfig`. Check yt-dlp's `INNERTUBE_CLIENTS` in `yt_dlp/extractor/youtube.py` for the current values.

---

### T-M2 · Fix nsig function extraction patterns

**Trigger**: All stream URLs are throttled (< 100 KB/s in practice). The `NParamDecipherer` logs a `DecipherFailed` error, or returns the token unchanged.

**What to do**:
1. Download the current player JS manually.
2. Search for the code around `"n"` parameter handling.
3. Update regex patterns in `NParamDecipherer.extractNFunctionName()`.
4. Check yt-dlp's `_extract_n_function_name` for their current patterns.

---

### T-M3 · Fix sig function extraction patterns

**Trigger**: WEB client fallback streams return HTTP 403.

**What to do**: Update patterns in `SignatureDecipherer.extractSigFunction()`.

---

## Task Summary

| Phase | Tasks | Effort |
|---|---|---|
| 1 — NDK & Project Setup | T-01 … T-06 | ~13 h |
| 2 — URL Parsing & InnerTube | T-07 … T-12 | ~13 h |
| 3 — Player JS & Deciphering | T-13 … T-17 | ~13 h |
| 4 — Format Selection | T-18 … T-19 | ~5 h |
| 5 — Caching | T-20 … T-21 | ~3 h |
| 6 — Core Orchestration & API | T-22 … T-25 | ~13 h |
| 7 — Sample App | T-26 … T-27 | ~6 h |
| 8 — Hardening & AAR Export | T-28 … T-31 | ~8 h |
| **Total** | **31 tasks** | **~74 h** |

---

*Generated: 2026-05-11*  
*Reference implementation: https://github.com/yt-dlp/yt-dlp (MIT License)*
