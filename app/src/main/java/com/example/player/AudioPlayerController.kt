package com.example.player

import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.example.model.SchoolItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class AudioPlayerState(
    val currentItem: SchoolItem? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isLooping: Boolean = false,
    val error: String? = null
) {
    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val formattedPosition: String
        get() = formatTime(positionMs)

    val formattedDuration: String
        get() = formatTime(durationMs)

    private fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).toInt()
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}

class AudioPlayerController(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    val pos = player.currentPosition.toLong()
                    val dur = player.duration.toLong().coerceAtLeast(0L)
                    _state.value = _state.value.copy(
                        positionMs = pos,
                        durationMs = dur,
                        isPlaying = true
                    )
                    handler.postDelayed(this, 300)
                }
            }
        }
    }

    fun play(item: SchoolItem) {
        val file = File(item.path)
        if (!file.exists()) {
            _state.value = _state.value.copy(error = "Ses dosyası bulunamadı")
            return
        }

        try {
            stop()
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                isLooping = _state.value.isLooping
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    playbackParams = playbackParams.setSpeed(_state.value.playbackSpeed)
                }
                setOnCompletionListener {
                    if (!_state.value.isLooping) {
                        _state.value = _state.value.copy(
                            isPlaying = false,
                            positionMs = it.duration.toLong()
                        )
                        handler.removeCallbacks(progressRunnable)
                    }
                }
                start()
            }
            mediaPlayer = player

            _state.value = _state.value.copy(
                currentItem = item,
                isPlaying = true,
                positionMs = 0L,
                durationMs = player.duration.toLong().coerceAtLeast(0L),
                error = null
            )
            handler.post(progressRunnable)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = "Oynatma hatası: ${e.localizedMessage}")
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            pause()
        } else {
            resume()
        }
    }

    fun pause() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _state.value = _state.value.copy(
                    isPlaying = false,
                    positionMs = player.currentPosition.toLong()
                )
                handler.removeCallbacks(progressRunnable)
            }
        }
    }

    fun resume() {
        mediaPlayer?.let { player ->
            if (!player.isPlaying) {
                player.start()
                _state.value = _state.value.copy(isPlaying = true)
                handler.post(progressRunnable)
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.let { player ->
            val target = positionMs.coerceIn(0L, player.duration.toLong())
            player.seekTo(target.toInt())
            _state.value = _state.value.copy(positionMs = target)
        }
    }

    fun skipForward(seconds: Int = 10) {
        mediaPlayer?.let { player ->
            val target = (player.currentPosition + seconds * 1000).coerceAtMost(player.duration)
            player.seekTo(target)
            _state.value = _state.value.copy(positionMs = target.toLong())
        }
    }

    fun skipBackward(seconds: Int = 10) {
        mediaPlayer?.let { player ->
            val target = (player.currentPosition - seconds * 1000).coerceAtLeast(0)
            player.seekTo(target)
            _state.value = _state.value.copy(positionMs = target.toLong())
        }
    }

    fun setSpeed(speed: Float) {
        _state.value = _state.value.copy(playbackSpeed = speed)
        mediaPlayer?.let { player ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    player.playbackParams = player.playbackParams.setSpeed(speed)
                } catch (_: Exception) {}
            }
        }
    }

    fun toggleLoop() {
        val nextLoop = !_state.value.isLooping
        _state.value = _state.value.copy(isLooping = nextLoop)
        mediaPlayer?.isLooping = nextLoop
    }

    fun stop() {
        handler.removeCallbacks(progressRunnable)
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            } catch (_: Exception) {}
        }
        mediaPlayer = null
        _state.value = _state.value.copy(
            isPlaying = false,
            positionMs = 0L
        )
    }

    fun close() {
        stop()
        _state.value = AudioPlayerState()
    }
}
