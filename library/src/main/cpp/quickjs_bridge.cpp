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
    JSRuntime *rt = JS_NewRuntime();
    JS_SetMemoryLimit(rt, memoryLimitBytes);
    JS_SetMaxStackSize(rt, stackLimitBytes);

    JSContext *ctx = JS_NewContext(rt);

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

    // Wrap: var __result__ = <fn>; then call it.
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
 * Loads the complete player JS (up to ~3 MB) into a QuickJS runtime and then
 * evaluates a discovery+transform script that finds and invokes the nsig
 * function behaviorally.
 *
 * discoveryScript must end with an expression whose value is the transformed
 * n-param string (or the original n-param on failure).
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_ytdlpdroid_js_QuickJsEngine_executeWithPlayerJsNative(
        JNIEnv *env, jobject /* this */,
        jstring playerJs, jstring discoveryScript) {

    const char *pjs  = env->GetStringUTFChars(playerJs,       nullptr);
    const char *disc = env->GetStringUTFChars(discoveryScript, nullptr);

    // Wrap the player JS in a try-catch so top-level browser-API access (e.g.
    // document.addEventListener) doesn't abort execution before the utility
    // functions (wD, uF, $b, etc.) have been defined.  The discovery script
    // runs afterwards and can use whichever functions loaded successfully.
    std::string full =
        "try{\n" + std::string(pjs) + "\n}catch(_pjs_err_){}\n" +
        std::string(disc);

    // 128 MB memory, 4 MB stack — enough for a 1-3 MB player JS.
    jstring result = evalAndReturn(env, full.c_str(), full.size(),
                                   128 * 1024 * 1024, 4 * 1024 * 1024);

    env->ReleaseStringUTFChars(playerJs,       pjs);
    env->ReleaseStringUTFChars(discoveryScript, disc);
    return result;
}
