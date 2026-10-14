package dev.anonrode.player.core.media.sync

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/**
 * Lightweight, lock-free, thread-safe timekeeper for media playback state.
 *
 * ExoPlayer updates this clock on the Main thread via [onPositionDiscontinuity]
 * and playback state listeners. Background threads (sync processors, speech
 * correlators) can read these volatile fields safely without accessing
 * ExoPlayer directly, eliminating cross-thread IllegalStateExceptions.
 */
class PlaybackClock : Player.Listener {

    @Volatile
    var positionMs: Long = 0L

    @Volatile
    var speed: Float = 1.0f

    @Volatile
    var isPlaying: Boolean = false

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        positionMs = newPosition.positionMs
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        speed = playbackParameters.speed
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            isPlaying = false
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        positionMs = player.currentPosition
        speed = player.playbackParameters.speed
        isPlaying = player.isPlaying
    }

    fun update(posMs: Long, spd: Float = speed, playing: Boolean = isPlaying) {
        positionMs = posMs
        speed = spd
        isPlaying = playing
    }

    fun reset() {
        positionMs = 0L
        speed = 1.0f
        isPlaying = false
    }
}
