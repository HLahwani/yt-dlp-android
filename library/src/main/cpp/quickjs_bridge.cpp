#include <jni.h>
#include <string>
#include <android/log.h>
#include "quickjs/quickjs.h"
#include "quickjs/quickjs-libc.h"

#define LOG_TAG "QuickJsBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ── helpers ─────────────────────────────────────────────────────────────── */

static jstring evalAndReturn(JNIEnv *env, const char *script, size_t scriptLen,
                              size_t memoryLimitBytes, size_t stackLimitBytes) {
    if (!script) return nullptr;

    JSRuntime *rt = JS_NewRuntime();
    if (!rt) {
        LOGE("JS_NewRuntime() returned null (OOM?) — memLimit=%zu", memoryLimitBytes);
        return nullptr;
    }
    JS_SetMemoryLimit(rt, memoryLimitBytes);
    JS_SetMaxStackSize(rt, stackLimitBytes);

    JSContext *ctx = JS_NewContext(rt);
    if (!ctx) {
        LOGE("JS_NewContext() returned null");
        JS_FreeRuntime(rt);
        return nullptr;
    }

    jstring result = nullptr;
    JSValue val = JS_Eval(ctx, script, scriptLen, "<eval>", JS_EVAL_TYPE_GLOBAL);

    if (JS_IsException(val)) {
        JSValue exc = JS_GetException(ctx);
        const char *msg = JS_ToCString(ctx, exc);
        LOGE("QuickJS exception: %s", msg ? msg : "unknown");
        JS_FreeCString(ctx, msg);
        JS_FreeValue(ctx, exc);
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
    return result;
}

/* ── existing single-function executor (small snippets) ──────────────────── */

extern "C" JNIEXPORT jstring JNICALL
Java_com_ytdlpdroid_js_QuickJsEngine_executeNative(
        JNIEnv *env, jobject /* this */,
        jstring jsCode, jstring argument) {

    const char *code = env->GetStringUTFChars(jsCode, nullptr);
    const char *arg  = env->GetStringUTFChars(argument, nullptr);
    if (!code || !arg) {
        if (code) env->ReleaseStringUTFChars(jsCode, code);
        if (arg)  env->ReleaseStringUTFChars(argument, arg);
        return nullptr;
    }

    std::string wrapped = std::string(code) + "\n__result__(\"" + std::string(arg) + "\");";

    // 8 MB is enough for small extracted function snippets.
    jstring result = evalAndReturn(env, wrapped.c_str(), wrapped.size(),
                                   8 * 1024 * 1024, 512 * 1024);

    env->ReleaseStringUTFChars(jsCode, code);
    env->ReleaseStringUTFChars(argument, arg);
    return result;
}

/* ── full player-JS executor ─────────────────────────────────────────────── */
/*
 * Loads the complete player JS into a QuickJS runtime and evaluates a
 * discovery+transform script.  Tries progressively smaller memory limits
 * so it degrades gracefully on low-RAM devices instead of crashing.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ytdlpdroid_js_QuickJsEngine_executeWithPlayerJsNative(
        JNIEnv *env, jobject /* this */,
        jstring playerJs, jstring discoveryScript) {

    const char *pjs  = env->GetStringUTFChars(playerJs,       nullptr);
    const char *disc = env->GetStringUTFChars(discoveryScript, nullptr);
    if (!pjs || !disc) {
        if (pjs)  env->ReleaseStringUTFChars(playerJs,       pjs);
        if (disc) env->ReleaseStringUTFChars(discoveryScript, disc);
        return nullptr;
    }

    // Wrap the player JS in a try-catch so top-level browser-API access
    // doesn't abort execution before utility functions are defined.
    std::string full =
        "try{\n" + std::string(pjs) + "\n}catch(_pjs_err_){}\n" +
        std::string(disc);

    env->ReleaseStringUTFChars(playerJs,       pjs);
    env->ReleaseStringUTFChars(discoveryScript, disc);

    // Try progressively smaller limits so low-RAM devices don't SIGSEGV
    // (JS_NewRuntime returns null on OOM, which we previously dereferenced).
    static const size_t kMemLimits[] = {
        64 * 1024 * 1024,   // 64 MB — enough for 1-3 MB player JS
        32 * 1024 * 1024,   // 32 MB — tight but may work
        16 * 1024 * 1024,   // 16 MB — last resort
    };
    static const size_t kStackLimit = 2 * 1024 * 1024; // 2 MB stack

    for (size_t memLimit : kMemLimits) {
        jstring result = evalAndReturn(env, full.c_str(), full.size(),
                                       memLimit, kStackLimit);
        if (result) return result;
        LOGE("Retrying with smaller memory limit (was %zu MB)", memLimit / (1024 * 1024));
    }
    return nullptr; // all attempts failed; Kotlin side falls back to original n-param
}
