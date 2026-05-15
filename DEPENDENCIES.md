# YTDLPDroid — Transitive Dependencies

When consuming `library-release.aar` directly (not via Maven), add these
dependencies to your app's `build.gradle.kts`:

```kotlin
// Networking
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// JSON parsing
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

// Coroutines (required — all public APIs are suspend functions)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
```

The QuickJS native library (`libquickjs_bridge.so`) is bundled inside the AAR
for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. No separate NDK dependency is needed.

## AAR size

| Variant | Size |
|---|---|
| Release AAR (all 3 ABIs) | ~1.6 MB |

## ProGuard / R8

The `consumer-rules.pro` bundled in the AAR handles all necessary keep rules
for the public API and QuickJS JNI bridge. No additional ProGuard rules are
required in the consuming app.
