package com.ytdlpdroid.sample

import com.ytdlpdroid.model.StreamResult

sealed class PlayerState {
    object Idle : PlayerState()
    object Loading : PlayerState()
    data class Ready(val result: StreamResult) : PlayerState()
    data class Error(val message: String) : PlayerState()
}
