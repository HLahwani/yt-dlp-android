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
    JS_SetMemoryLimit(rt, 8 * 1024 * 1024);  // 8 MB — nsig functions are tiny
    JS_SetMaxStackSize(rt, 512 * 1024);        // 512 KB stack

    JSContext *ctx = JS_NewContext(rt);

    // Kotlin side sets __result__ = <function>; we call it with the argument here
    std::string wrappedCode = std::string(code) + "\n__result__(\"" + std::string(arg) + "\");";

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
