package dev.anonrode.player.core.media.sync

import android.os.SystemClock
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/**
 * Lightweight, lock-free, thread-safe timekeeper for media playback state.
 *
 * ExoPlayer only emits [onPositionDiscontinuity] and [onEvents] on discrete
 * state transitions (seeks, buffering changes, track switches) — NOT on steady
 * playback ticks. Storing an immutable monotonic anchor `(posMs, elapsedMs,
 * speed, playing)` and extrapolating while [isPlaying] ensures millisecond-
 * accurate position reads from any thread (including background audio playback
 * and sync evaluation workers) even when no ExoPlayer event has fired for
 * minutes. The foreground render loop also refreshes this anchor via [update]
 * on every tick.
 */
class PlaybackClock(
    private val elapsedMsProvider: () -> Long = ::defaultElapsedMs,
) : Player.Listener {

    private data class Anchor(
        val posMs: Long,
        val elapsedMs: Long,
        val speed: Float,
        val playing: Boolean,
    )

    @Volatile
    private var anchor = Anchor(
        posMs = 0L,
        elapsedMs = 0L,
        speed = 1.0f,
        playing = false,
    )

    var positionMs: Long
        get() {
            val a = anchor
            if (!a.playing || a.elapsedMs == 0L) return a.posMs
            val dtWallMs = (elapsedMsProvider() - a.elapsedMs).coerceAtLeast(0L)
            return (a.posMs + (dtWallMs * a.speed).toLong()).coerceAtLeast(0L)
        }
        set(value) {
            val a = anchor
            anchor = Anchor(
                posMs = value.coerceAtLeast(0L),
                elapsedMs = elapsedMsProvider(),
                speed = a.speed,
                playing = a.playing,
            )
        }

    val speed: Float
        get() = anchor.speed

    val isPlaying: Boolean
        get() = anchor.playing

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val a = anchor
        anchor = Anchor(
            posMs = newPosition.positionMs.coerceAtLeast(0L),
            elapsedMs = elapsedMsProvider(),
            speed = a.speed,
            playing = a.playing,
        )
    }

    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
        val nowPos = positionMs
        val a = anchor
        anchor = Anchor(
            posMs = nowPos,
            elapsedMs = elapsedMsProvider(),
            speed = playbackParameters.speed.coerceAtLeast(0.1f),
            playing = a.playing,
        )
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        val nowPos = positionMs
        val a = anchor
        anchor = Anchor(
            posMs = nowPos,
            elapsedMs = elapsedMsProvider(),
            speed = a.speed,
            playing = isPlaying,
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
            val nowPos = positionMs
            val a = anchor
            anchor = Anchor(
                posMs = nowPos,
                elapsedMs = elapsedMsProvider(),
                speed = a.speed,
                playing = false,
            )
        }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        update(
            posMs = player.currentPosition,
            spd = player.playbackParameters.speed,
            playing = player.isPlaying,
        )
    }

    fun update(posMs: Long, spd: Float = anchor.speed, playing: Boolean = anchor.playing) {
        anchor = Anchor(
            posMs = posMs.coerceAtLeast(0L),
            elapsedMs = elapsedMsProvider(),
            speed = spd.coerceAtLeast(0.1f),
            playing = playing,
        )
    }

    fun reset() {
        anchor = Anchor(
            posMs = 0L,
            elapsedMs = elapsedMsProvider(),
            speed = 1.0f,
            playing = false,
        )
    }

    companion object {
        private fun defaultElapsedMs(): Long = try {
            SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.nanoTime() / 1_000_000L
        }
    }
}
