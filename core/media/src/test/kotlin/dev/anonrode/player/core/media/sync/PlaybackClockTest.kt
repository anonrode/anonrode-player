package dev.anonrode.player.core.media.sync

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackClockTest {

    @Test
    fun `positionMs advances smoothly while playing without ExoPlayer events`() {
        var nowMs = 10_000L
        val clock = PlaybackClock(elapsedMsProvider = { nowMs })

        clock.update(posMs = 5_000L, spd = 1.0f, playing = true)
        assertEquals(5_000L, clock.positionMs)

        // Advance 90 seconds of wall time with ZERO ExoPlayer onEvents callbacks
        nowMs += 90_000L
        assertEquals(95_000L, clock.positionMs)

        // Advance another 3 minutes (regression check for 1-3 minute subtitle hang)
        nowMs += 180_000L
        assertEquals(275_000L, clock.positionMs)
    }

    @Test
    fun `positionMs scales with playback speed and preserves continuity on speed change`() {
        var nowMs = 1_000L
        val clock = PlaybackClock(elapsedMsProvider = { nowMs })

        clock.update(posMs = 10_000L, spd = 1.0f, playing = true)
        nowMs += 10_000L // +10s at 1.0x -> 20s
        assertEquals(20_000L, clock.positionMs)

        // Change speed to 1.5x: must NOT jump backwards or forwards at the change instant
        clock.onPlaybackParametersChanged(PlaybackParameters(1.5f))
        assertEquals(20_000L, clock.positionMs)

        nowMs += 10_000L // +15s at 1.5x -> 35s
        assertEquals(35_000L, clock.positionMs)
    }

    @Test
    fun `pause and STATE_ENDED freeze positionMs cleanly`() {
        var nowMs = 5_000L
        val clock = PlaybackClock(elapsedMsProvider = { nowMs })

        clock.update(posMs = 30_000L, spd = 1.0f, playing = true)
        nowMs += 4_000L
        assertEquals(34_000L, clock.positionMs)

        clock.onIsPlayingChanged(false)
        assertFalse(clock.isPlaying)
        nowMs += 60_000L
        assertEquals(34_000L, clock.positionMs)

        clock.onIsPlayingChanged(true)
        assertTrue(clock.isPlaying)
        nowMs += 2_000L
        assertEquals(36_000L, clock.positionMs)

        clock.onPlaybackStateChanged(Player.STATE_ENDED)
        assertFalse(clock.isPlaying)
        nowMs += 30_000L
        assertEquals(36_000L, clock.positionMs)
    }

    @Test
    fun `seek setter and reset re-anchor immediately`() {
        var nowMs = 2_000L
        val clock = PlaybackClock(elapsedMsProvider = { nowMs })

        clock.update(posMs = 100_000L, spd = 1.0f, playing = true)
        nowMs += 5_000L
        assertEquals(105_000L, clock.positionMs)

        clock.positionMs = 600_000L
        assertEquals(600_000L, clock.positionMs)
        nowMs += 3_000L
        assertEquals(603_000L, clock.positionMs)

        clock.reset()
        assertEquals(0L, clock.positionMs)
        assertFalse(clock.isPlaying)
    }
}
