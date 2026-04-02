package com.artmondo.algomodo.audio

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri

/**
 * Simple MediaPlayer wrapper for audio playback with seek support.
 */
class AudioPlayer(private val context: Context) {

    private var player: MediaPlayer? = null
    private var prepared = false

    val isPlaying: Boolean get() = player?.isPlaying == true
    val durationMs: Int get() = if (prepared) player?.duration ?: 0 else 0
    val currentPositionMs: Int get() = if (prepared) player?.currentPosition ?: 0 else 0

    fun load(uri: Uri): Boolean {
        release()
        return try {
            player = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                isLooping = true
            }
            prepared = true
            true
        } catch (e: Exception) {
            release()
            false
        }
    }

    fun play() {
        if (prepared) player?.start()
    }

    fun pause() {
        if (prepared && player?.isPlaying == true) player?.pause()
    }

    fun seekTo(positionMs: Int) {
        if (prepared) player?.seekTo(positionMs)
    }

    fun release() {
        try {
            player?.stop()
        } catch (_: Exception) {}
        try {
            player?.release()
        } catch (_: Exception) {}
        player = null
        prepared = false
    }
}
