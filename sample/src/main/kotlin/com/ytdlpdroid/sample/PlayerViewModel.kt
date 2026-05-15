package com.ytdlpdroid.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.ytdlpdroid.YTDLPDroid
import com.ytdlpdroid.model.YTDLPError
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val ytdlp = YTDLPDroid.Builder(application.cacheDir).build()

    val state = MutableLiveData<PlayerState>(PlayerState.Idle)

    fun play(url: String) {
        state.value = PlayerState.Loading
        viewModelScope.launch {
            try {
                val result = ytdlp.extract(url)
                state.value = PlayerState.Ready(result)
            } catch (e: YTDLPError) {
                state.value = PlayerState.Error(e.message ?: "Extraction failed")
            } catch (e: Exception) {
                state.value = PlayerState.Error("Unexpected error: ${e.message}")
            }
        }
    }
}
