# Public API — must not be renamed or removed
-keep public class com.ytdlpdroid.YTDLPDroid { public *; }
-keep public class com.ytdlpdroid.model.** { public *; }
-keep public interface com.ytdlpdroid.js.JsEngine { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
    <fields>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# QuickJS JNI — preserve native method declarations so the JNI bridge links correctly
-keepclasseswithmembernames class com.ytdlpdroid.js.QuickJsEngine {
    native <methods>;
}
