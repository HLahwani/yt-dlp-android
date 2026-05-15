package com.ytdlpdroid.sample

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.ytdlpdroid.model.StreamResult
import com.ytdlpdroid.sample.databinding.ActivityMainBinding
import okhttp3.OkHttpClient

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var player: ExoPlayer
    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        player = ExoPlayer.Builder(this).build()
        binding.playerView.player = player

        viewModel.state.observe(this) { state ->
            when (state) {
                is PlayerState.Idle -> Unit
                is PlayerState.Loading -> {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.errorText.visibility = View.GONE
                    binding.playButton.isEnabled = false
                }
                is PlayerState.Ready -> {
                    binding.progressBar.visibility = View.GONE
                    binding.playButton.isEnabled = true
                    setupPlayer(state.result)
                }
                is PlayerState.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.playButton.isEnabled = true
                    binding.errorText.text = state.message
                    binding.errorText.visibility = View.VISIBLE
                }
            }
        }

        binding.playButton.setOnClickListener { submitUrl() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { submitUrl(); true } else false
        }
    }

    private fun submitUrl() {
        val url = binding.urlInput.text?.toString()?.trim().orEmpty()
        if (url.isNotEmpty()) viewModel.play(url)
    }

    private fun setupPlayer(result: StreamResult) {
        // Use the same User-Agent the InnerTube client used when generating stream URLs.
        // The CDN validates this matches — a mismatch produces HTTP 403 on stream requests.
        val dataSourceFactory = OkHttpDataSource.Factory(OkHttpClient())
            .setDefaultRequestProperties(mapOf(
                "User-Agent" to result.streamUserAgent,
                "Referer" to "https://www.youtube.com/",
                "Origin" to "https://www.youtube.com",
            ))
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)

        val videoSource = result.videoStream?.let {
            mediaSourceFactory.createMediaSource(MediaItem.fromUri(it.url))
        }
        val audioSource = mediaSourceFactory.createMediaSource(
            MediaItem.fromUri(result.audioStream.url)
        )

        val source = if (videoSource != null) MergingMediaSource(videoSource, audioSource)
                     else audioSource

        player.setMediaSource(source)
        player.prepare()
        player.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}
