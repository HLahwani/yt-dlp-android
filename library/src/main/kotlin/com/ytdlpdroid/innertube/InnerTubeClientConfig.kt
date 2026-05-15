package com.ytdlpdroid.innertube

private const val INNERTUBE_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

internal sealed class InnerTubeClientConfig(
    val clientName: String,
    val clientNumber: String,
    val clientVersion: String,
    val userAgent: String,
    val androidSdkVersion: Int? = null,
    /** Extra fields inside `context.client` JSON object. */
    val extraClientFields: String = "",
    /** Extra top-level JSON fields in the request body (static, non-video-specific). */
    val extraBodyFields: String = "",
    /** YouTube InnerTube API key appended as ?key= (required for some clients). */
    val apiKey: String? = null,
    /** Whether to include a playbackContext block in the request body. */
    val usePlaybackContext: Boolean = false,
    /**
     * Whether to include signatureTimestamp (sts) inside playbackContext.
     * sts is extracted from the WEB player JS and is only valid for browser-based clients.
     * Android/iOS clients use their own player version — sending WEB sts causes UNPLAYABLE.
     */
    val includeSignatureTimestamp: Boolean = false,
) {
    open fun dynamicBodyFields(videoId: String): String = extraBodyFields

    // yt-dlp default: android_vr (no PO token required, returns direct stream URLs).
    // NOTE: Do NOT add params="8AEB" here — yt-dlp sends no PLAYER_PARAMS for android_vr,
    // and adding it causes YouTube to return UNPLAYABLE for this client.
    // No apiKey — yt-dlp does not send a key for android_vr (REQUIRE_JS_PLAYER=False means no
    // watch page is fetched, so no ytcfg API key is available). Adding the key causes YouTube
    // to route the request differently and return UNPLAYABLE.
    object ANDROID_VR : InnerTubeClientConfig(
        clientName = "ANDROID_VR",
        clientNumber = "28",
        clientVersion = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        androidSdkVersion = 32,
        extraClientFields = """"osName": "Android", "osVersion": "12L", "deviceMake": "Oculus", "deviceModel": "Quest 3",""",
        usePlaybackContext = true,
    )

    // ANDROID requires PO tokens for CDN access (yt-dlp GVS_PO_TOKEN_POLICY required=True).
    // Without PO tokens YouTube returns UNPLAYABLE — this client is kept as a fallback in case
    // android_vr fails, but it will often not produce playable CDN URLs on its own.
    object ANDROID : InnerTubeClientConfig(
        clientName = "ANDROID",
        clientNumber = "3",
        clientVersion = "21.02.35",
        userAgent = "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip",
        androidSdkVersion = 30,
        extraClientFields = """"osName": "Android", "osVersion": "11",""",
        usePlaybackContext = true,
    )

    object ANDROID_TESTSUITE : InnerTubeClientConfig(
        clientName = "ANDROID_TESTSUITE",
        clientNumber = "30",
        clientVersion = "1.9",
        userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 13; Pixel 7 Pro Build/TQ3A.230901.001) gzip",
        androidSdkVersion = 33,
        extraClientFields = """"osName": "Android", "osVersion": "13",""",
        usePlaybackContext = true,
    )

    object TVHTML5_SIMPLY_EMBEDDED : InnerTubeClientConfig(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientNumber = "85",
        clientVersion = "2.0",
        userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
        usePlaybackContext = true,
    ) {
        override fun dynamicBodyFields(videoId: String) =
            """"thirdParty": {"embedUrl": "https://www.youtube.com/watch?v=$videoId"},"""
    }

    object IOS : InnerTubeClientConfig(
        clientName = "IOS",
        clientNumber = "5",
        clientVersion = "21.02.3",
        userAgent = "com.google.ios.youtube/21.02.3 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        extraClientFields = """"osName": "iPhone", "osVersion": "18.3.2.22D82", "deviceMake": "Apple", "deviceModel": "iPhone16,2",""",
        usePlaybackContext = true,
    )

    object MWEB : InnerTubeClientConfig(
        clientName = "MWEB",
        clientNumber = "2",
        clientVersion = "2.20260115.01.00",
        userAgent = "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)",
        apiKey = INNERTUBE_API_KEY,
    )

    object WEB_EMBEDDED : InnerTubeClientConfig(
        clientName = "WEB_EMBEDDED_PLAYER",
        clientNumber = "56",
        clientVersion = "1.20260115.01.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        extraBodyFields = """"thirdParty": {"embedUrl": "https://www.youtube.com/"},""",
        apiKey = INNERTUBE_API_KEY,
        usePlaybackContext = true,
        includeSignatureTimestamp = true,
    )

    object WEB : InnerTubeClientConfig(
        clientName = "WEB",
        clientNumber = "1",
        clientVersion = "2.20260114.08.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        extraClientFields = """"originalUrl": "https://www.youtube.com", "platform": "DESKTOP", "utcOffsetMinutes": 0,""",
        apiKey = INNERTUBE_API_KEY,
        usePlaybackContext = true,
        includeSignatureTimestamp = true,
    )
}

internal fun InnerTubeClientConfig.buildRequestBody(
    videoId: String,
    visitorData: String? = null,
    regionCode: String? = null,
    signatureTimestamp: Int? = null,
): String {
    val sdkField = androidSdkVersion?.let { """"androidSdkVersion": $it,""" } ?: ""
    val visitorField = visitorData?.let { """"visitorData": "$it",""" } ?: ""
    val glValue = regionCode ?: "US"
    val dynamicFields = dynamicBodyFields(videoId)
    val playbackCtxField = if (usePlaybackContext) {
        val stsField = if (includeSignatureTimestamp) {
            signatureTimestamp?.let { """, "signatureTimestamp": $it""" } ?: ""
        } else ""
        """"playbackContext": {"contentPlaybackContext": {"html5Preference": "HTML5_PREF_WANTS"$stsField}},"""
    } else ""
    return """
        {
          "context": {
            "client": {
              "clientName": "$clientName",
              "clientVersion": "$clientVersion",
              "userAgent": "$userAgent",
              $sdkField
              $visitorField
              $extraClientFields
              "hl": "en",
              "gl": "$glValue"
            }
          },
          "videoId": "$videoId",
          $dynamicFields
          $playbackCtxField
          "racyCheckOk": true,
          "contentCheckOk": true
        }
    """.trimIndent()
}

/** Returns the full API URL including API key if configured. */
internal fun InnerTubeClientConfig.playerApiUrl(): String {
    val base = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    return if (apiKey != null) "$base&key=$apiKey" else base
}
