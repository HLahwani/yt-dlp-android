package com.ytdlpdroid.model

data class VideoInfo(
    val videoId: String,
    val title: String,
    val author: String,
    val channelId: String,
    val durationSeconds: Long,
    val isLive: Boolean,
    val thumbnailUrl: String,
    val keywords: List<String>,
)
