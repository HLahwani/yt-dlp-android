package com.ytdlpdroid.innertube

// playbackContext sent with all Android/iOS clients — tells YouTube we want
// HTML5 adaptive streams and unlocks certain video types that otherwise return
// UNPLAYABLE / "Video unavailable" from the InnerTube player API.
private const val PLAYBACK_CTX = """"playbackContext": {"contentPlaybackContext": {"html5Preference": "HTML5_PREF_WANTS"}},"""

internal sealed class InnerTubeClientConfig(
    val clientName: String,
    val clientNumber: String,
    val clientVersion: String,
    val userAgent: String,
    val androidSdkVersion: Int? = null,
    /** Extra fields inside `context.client` JSON object. */
    val extraClientFields: String = "",
    /** Extra top-level JSON fields in the request body. */
    val extraBodyFields: String = "",
    /** YouTube InnerTube API key appended as ?key= (required for some clients). */
    val apiKey: String? = null,
) {
    object ANDROID : InnerTubeClientConfig(
        clientName = "ANDROID",
        clientNumber = "3",
        clientVersion = "19.44.38",
        userAgent = "com.google.android.youtube/19.44.38 (Linux; U; Android 11; sdk_gphone_x86 Build/RSR1.201013.001) gzip",
        androidSdkVersion = 30,
        extraBodyFields = """"params": "8AEB", $PLAYBACK_CTX""",
    )

    object ANDROID_TESTSUITE : InnerTubeClientConfig(
        clientName = "ANDROID_TESTSUITE",
        clientNumber = "30",
        clientVersion = "1.9",
        userAgent = "com.google.android.youtube/1.9 (Linux; U; Android 13; Pixel 7 Pro Build/TQ3A.230901.001) gzip",
        androidSdkVersion = 33,
        extraBodyFields = """"params": "8AEB", $PLAYBACK_CTX""",
    )

    object TVHTML5_SIMPLY_EMBEDDED : InnerTubeClientConfig(
        clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        clientNumber = "85",
        clientVersion = "2.0",
        userAgent = "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1",
        extraBodyFields = """"thirdParty": {"embedUrl": "https://www.youtube.com/"}, $PLAYBACK_CTX""",
    )

    object ANDROID_VR : InnerTubeClientConfig(
        clientName = "ANDROID_VR",
        clientNumber = "28",
        clientVersion = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12; eureka-user Build/SQ3A.220605.009.A1) gzip",
        androidSdkVersion = 32,
        extraBodyFields = PLAYBACK_CTX,
    )

    object IOS : InnerTubeClientConfig(
        clientName = "IOS",
        clientNumber = "5",
        clientVersion = "19.44.4",
        userAgent = "com.google.ios.youtube/19.44.4 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
        extraBodyFields = PLAYBACK_CTX,
    )

    object MWEB : InnerTubeClientConfig(
        clientName = "MWEB",
        clientNumber = "2",
        clientVersion = "2.20240726.00.00",
        userAgent = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36",
    )

    object WEB_EMBEDDED : InnerTubeClientConfig(
        clientName = "WEB_EMBEDDED_PLAYER",
        clientNumber = "56",
        clientVersion = "2.20240101.00.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        extraBodyFields = """"thirdParty": {"embedUrl": "https://www.youtube.com/"},""",
        apiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
    )

    object WEB : InnerTubeClientConfig(
        clientName = "WEB",
        clientNumber = "1",
        clientVersion = "2.20240101.00.00",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        extraClientFields = """"originalUrl": "https://www.youtube.com", "platform": "DESKTOP", "utcOffsetMinutes": 0,""",
        apiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
    )
}

internal fun InnerTubeClientConfig.buildRequestBody(
    videoId: String,
    visitorData: String? = null,
    regionCode: String? = null,
): String {
    val sdkField = androidSdkVersion?.let { """"androidSdkVersion": $it,""" } ?: ""
    val visitorField = visitorData?.let { """"visitorData": "$it",""" } ?: ""
    // Default to "US" matching yt-dlp behaviour.
    // An explicit regionCode lets callers access geo-restricted content.
    val glValue = regionCode ?: "US"
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
          $extraBodyFields
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
