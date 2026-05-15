# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
# Build the library AAR (debug)
./gradlew :library:assembleDebug

# Build the library AAR (release)
./gradlew :library:assembleRelease
# Output: library/build/outputs/aar/library-release.aar

# Build the sample app
./gradlew :sample:assembleDebug

# Run unit tests (JVM)
./gradlew :library:testDebugUnitTest

# Run instrumented tests on connected device
./gradlew :library:connectedDebugAndroidTest

# Run a specific instrumented test class
./gradlew :library:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.ytdlpdroid.integration.WebViewExtractionTest
```

The NDK must be installed via Android Studio SDK Manager → NDK (Side by side). Without it, the CMake build step for `quickjs_bridge` will fail.

## Architecture

### Extraction Pipeline

```
YouTube URL → VideoIdParser → YouTubeExtractor
    → InnerTubeClient (client fallback chain, 8 clients)
    → PlayerResponseParser
    → PlayerJsRepository (fetch/cache player JS)
    → DecipherService:
        NParamDecipherer (transform n= param via QuickJS)
        SignatureDecipherer (WEB client signatureCipher path)
    → FormatSelector → StreamResult
```

`YouTubeExtractor.fetchWithFallback` tries clients in order:
`ANDROID → ANDROID_TESTSUITE → TVHTML5_SIMPLY_EMBEDDED → ANDROID_VR → IOS → MWEB → WEB_EMBEDDED → WEB`

Any client that returns a non-OK playability status (including `UNPLAYABLE`) causes a fallback to the next client — only `LiveStreamNotSupported` fails immediately. The `WEB` client fetches visitor data from the homepage and injects it into requests to avoid "page needs to be reloaded" responses.

### Module Structure

- **`:library`** — single AAR, namespace `com.ytdlpdroid`. Public API: `YTDLPDroid`, `ExtractionOptions`, `StreamResult`, `VideoInfo`, `StreamFormat`, `YTDLPError`, `JsEngine`.
- **`:sample`** — demo app using Media3 for playback.

### Key Package Layout (library)

| Package | Purpose |
|---|---|
| `com.ytdlpdroid` | `YTDLPDroid` (public entry point / Builder) |
| `com.ytdlpdroid.model` | Public data models + `YTDLPError` sealed class |
| `com.ytdlpdroid.extractor` | `YouTubeExtractor`, `VideoIdParser` |
| `com.ytdlpdroid.innertube` | `InnerTubeClient`, `InnerTubeClientConfig`, `PlayerResponseParser` |
| `com.ytdlpdroid.innertube.model` | Internal `@Serializable` raw JSON models |
| `com.ytdlpdroid.decipher` | `DecipherService`, `NParamDecipherer`, `SignatureDecipherer`, `PlayerJsRepository`, `JsFunctionExtractor` |
| `com.ytdlpdroid.js` | `JsEngine` fun interface, `QuickJsEngine` (JNI wrapper) |
| `com.ytdlpdroid.format` | `FormatSelector`, `MimeTypeParser`, `Codec` enums |
| `com.ytdlpdroid.cache` | `MemoryCache`, `DiskCache`, `ExtractionCache` |
| `com.ytdlpdroid.network` | `HttpClientProvider` |

### Native Layer (QuickJS JNI)

The JavaScript engine is QuickJS compiled from C source via CMake. This is the **only** NDK component.

- C source in `library/src/main/cpp/quickjs/` (embedded directly, no Maven wrapper)
- `library/src/main/cpp/quickjs_bridge.cpp` exposes one JNI function: `Java_com_ytdlpdroid_js_QuickJsEngine_executeNative`
- The Kotlin side wraps the extracted function as `var __result__ = <fn>;` so C++ always calls `__result__("arg")`
- Memory limit: 8 MB per evaluation; stack limit: 512 KB
- Target ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86` (the last added for x86 emulator support)

### The n-Parameter (Critical Path, T-M2)

Every stream URL has `?n=<token>` that must be transformed by a function in YouTube's player JS. Without transformation the CDN returns HTTP 403.

`NParamDecipherer` extracts the nsig function name via `N_FUNC_NAME_PATTERNS` and caches the extracted code keyed by `playerJs.hashCode()`.

**Current known failure (player `25f11721`, 2025-2026)**: Both `player-plasma-es6` and `player_ias.vflset` variants use **string-table obfuscation** where all string literals (including `"n"`, `"reverse"`, `"splice"`) are stored in a runtime array `y` (e.g., `y[19]="n"`, `y[18]="reverse"`, `y[25]="splice"`). There is no visible `split("")` → operations → `join("")` function body. The nsig transform is dispatched through `uF(Z,N,r)` → `wD(Z,N,r,X,z)` with XOR-obfuscated parameters. Traditional regex patterns fail:
- N-P1 (`.get("n"))&&`) — does not exist in this player
- N-P2 (`X&&(a=fn(b)`) — matches `yu` (a type-boxer that returns `[0, input]`) or `String` (a `toString()` override)
- `split("")` heuristic — no function matching this pattern exists

**Fix path for T-M2**: See yt-dlp's current `_extract_n_function_name` in `youtube.py`. The string-table deobfuscation approach: find array `y` (defined near position 2912 in player `25f11721`, ~400+ elements), build an index→string map, then locate the `uF(Z,N,nParam)` call that applies the n-param operations through the `wD` dispatcher.

### Signature Decipherer (T-M3)

For the WEB client (signatureCipher path), `SignatureDecipherer.SIG_FUNC_PATTERNS` looks for `.sig||fn(`, `.signature=fn(`, `"signature",fn(`. These also fail for player `25f11721`.

**What was found**: The sig decryption helper in player `25f11721` is the object `$b` with three methods:
- `$b.Dh(arr, N)` — swap `arr[0]` with `arr[N % arr.length]`
- `$b.ZN(arr)` — `arr.reverse()`
- `$b.Df(arr, N)` — `arr.splice(0, N)`

The actual decryption happens inside `uF(58,1015,kU(41,8341,encryptedSig))`:
1. `kU(41,8341,sig)` = `decodeURIComponent(sig)`
2. `uF(58,1015,result)` applies the sequence: `split("") → splice(0,1) → reverse() → swap(0,31) → reverse() → swap(0,5) → swap(0,25) → swap(0,8) → reverse() → splice(0,1) → join("")`

**These are player-version-specific hardcoded XOR offsets.** They change with each player build.

**New pattern needed**: look for `$b\.ZN|$b\.Dh|$b\.Df` to find the sig function body, or look for the outer `uF(Z,N,...kU(...))` call pattern.

### InnerTube API Bot Detection (2025-2026)

Direct InnerTube API calls from datacenter/cloud IPs are blocked by YouTube's bot detection system (returns `UNPLAYABLE`, `LOGIN_REQUIRED "Sign in to confirm you're not a bot"`, or HTTP 400 `failedPrecondition`). Even requests made from within a YouTube-loaded WebView using the page's own `ytcfg` context fail without a PO (Proof of Origin) token.

The **only working approach from emulator/datacenter IPs** is `ytInitialPlayerResponse` via a WebView loaded with a mobile User-Agent — this returns `player-plasma-es6` player with direct stream URLs. However:
- The CDN (googlevideo.com) also blocks requests from datacenter IPs with HTTP 403
- Even `fetch()` from within a YouTube WebView context fails (CORS/connection block returns status 0)
- This is confirmed as an IP-level CDN restriction, not a n-param issue

On a **real Android device on a carrier/residential network**, all of the above work correctly.

### InnerTube Client Versions

Client versions are in `InnerTubeClientConfig.kt`. Update when YouTube returns HTTP 400 `failedPrecondition` (T-M1). Cross-reference yt-dlp's `INNERTUBE_CLIENTS`.

Current client chain: `ANDROID → ANDROID_TESTSUITE → TVHTML5_SIMPLY_EMBEDDED → ANDROID_VR → IOS → MWEB → WEB_EMBEDDED → WEB`

The `WEB` client initializes a session via a homepage GET (capturing `VISITOR_INFO1_LIVE`, `YSC` cookies) and injects visitor data into requests.

### JSON Parsing

`kotlinx.serialization` JSON decoder uses `ignoreUnknownKeys = true`, `isLenient = true`, `coerceInputValues = true` in `RawPlayerResponse.kt`. Never disable these flags.

## Implementation Status

All 31 tasks (T-01 through T-31) are complete. 122 JVM unit tests pass. Release AAR is 1.6 MB.

**Blocked maintenance tasks:**
- **T-M2** (nsig patterns): `NParamDecipherer.N_FUNC_NAME_PATTERNS` do not match player `25f11721` due to string-table obfuscation. Streams throttled/403 on real devices.
- **T-M3** (sig patterns): `SignatureDecipherer.SIG_FUNC_PATTERNS` do not match player `25f11721`. WEB client formats with `signatureCipher` cannot be decrypted.

## Maintenance

### T-M2: Fix nsig function extraction

**Trigger**: Stream URLs return HTTP 403 on a real (residential-IP) device.

**Player `25f11721` investigation findings** (2026-05-14):
- String table variable `y` defined at pos ~2912, array of 400+ strings. `y[19]="n"`, `y[18]="reverse"`, `y[25]="splice"`, `y[15]="join"`, `y[42]="split"`, `y[27]="set"`, `y[31]="get"`.
- No `y[19]` references appear in URL manipulation code — the n-param is handled entirely through the obfuscated dispatcher.
- The nsig transform goes through `uF(Z,N,r)` → `wD(Z,N,r,X,z)` with XOR-computed indices.
- `wD` at pos ~65357 performs array operations (splice, reverse, swap) based on bitmask conditions on `Z`.

**Steps to fix**:
1. Download current `player_ias.vflset/en_US/base.js` (use `PlayerJsRepository` on a real device)
2. Find string table array, build index→string map
3. Locate calls matching `[string_table_var][n_index]` in URL manipulation
4. OR: check yt-dlp's `_extract_n_function_name` for current patterns

### T-M3: Fix sig function extraction

**Trigger**: WEB client formats all fail with "Sig function name not found in player JS".

**Player `25f11721` sig decryption**: uses `$b` object + `uF` dispatcher (see Architecture section above). The `SIG_FUNC_PATTERNS` need a new entry that locates the `uF(Z,N,kU(...,sig))` call. Consider:
```kotlin
Regex("""uF\s*\(\d+\s*,\s*\d+\s*,\s*kU\s*\(\d+\s*,\s*\d+\s*,\s*[a-zA-Z_$][a-zA-Z0-9_$]*\.s\b""")
```

### T-M1: Update InnerTube client versions

**Trigger**: HTTP 400 `failedPrecondition` from ANDROID client, or `"API version not supported"`.

Update `InnerTubeClientConfig.kt`. Reference: yt-dlp `INNERTUBE_CLIENTS` dict.
