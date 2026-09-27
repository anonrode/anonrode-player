package dev.anonrode.player.core.media.sync

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.model.SubtitleCue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for speech envelope extraction and locking in [AudioSyncProcessor].
 *
 * Verifies that speech-shaped bursts separated by irregular silence gaps produce
 * clean contrast in the speech envelope, allowing [SpeechCorrelator] to find the
 * correct shift and lock onto subtitles authored late, while pure silence refuses
 * to invent an offset.
 */
@UnstableApi
class SyncEnvelopeTest {

    private class RecordingListener : SyncListener {
        var lockedOffset: Float? = null
        override fun onSyncLocked(offsetSeconds: Float, speedFactor: Float) {
            lockedOffset = offsetSeconds
        }

        override fun onSyncNoMatch() = Unit
    }

    private companion object {
        const val SAMPLE_RATE = 8000
        const val CHANNELS = 1

        /** Interleaved samples in one 10 ms analysis window. */
        const val WINDOW_SAMPLES = SAMPLE_RATE / 100 * CHANNELS

        const val TOTAL_SECONDS = 240

        /** Amplitude of a speech burst and of a gap. */
        const val SPEECH_AMP = 9000
        const val GAP_AMP = 60

        /** Cues are authored this many seconds LATE; the engine must undo it. */
        const val SHIFT = 3.0

        /** One correlator bin is 0.1 s; allow two bins for bin-edge effects. */
        const val TOLERANCE = 0.2f

        class Seg(val start: Double, val end: Double)

        /**
         * Irregular speech/gap alternation. Starts after a second of quiet so
         * the adaptive floor has a genuine silence level to latch onto before
         * the first burst.
         */
        fun segments(totalSeconds: Int): List<Seg> {
            var seed = 0x2545F4914F6CDD1DL
            fun next(bound: Int): Int {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                return ((seed ushr 33).toInt() and 0x7FFFFFFF) % bound
            }
            val out = ArrayList<Seg>()
            var t = 1.0
            while (t < totalSeconds) {
                val on = 0.8 + next(18) / 10.0    // 0.8 .. 2.5 s
                val off = 0.3 + next(13) / 10.0   // 0.3 .. 1.5 s
                if (t + on > totalSeconds) break
                out.add(Seg(t, t + on))
                t += on + off
            }
            return out
        }
    }

    private fun newProcessor(listener: SyncListener): AudioSyncProcessor =
        AudioSyncProcessor(listener).apply {
            configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        }

    /**
     * Feeds [TOTAL_SECONDS] of speech-shaped PCM, one 10 ms window at a time.
     * Windows inside a burst get [SPEECH_AMP]-scale noise; the gaps get
     * [GAP_AMP]-scale noise, so the track has real on/off dynamics for the
     * envelope to find.
     */
    private fun feedDialogue(p: AudioSyncProcessor, segs: List<Seg>) {
        val buf = ByteBuffer.allocateDirect(WINDOW_SAMPLES * 2).order(ByteOrder.nativeOrder())
        var seed = -7046029254386353131L
        var next = 0
        for (w in 0 until TOTAL_SECONDS * 100) {
            val t = w / 100.0
            while (next < segs.size && segs[next].end <= t) next++
            val speaking = next < segs.size && t >= segs[next].start && t < segs[next].end
            val amp = if (speaking) SPEECH_AMP else GAP_AMP
            buf.clear()
            repeat(WINDOW_SAMPLES) {
                seed = seed * 1103515245L + 12345L
                val noise = ((seed ushr 20).toInt() and 0x1FFF) - 0x1000   // -4096..4095
                val v = (noise.toLong() * amp / 4096L).toInt().coerceIn(-32768, 32767)
                buf.putShort(v.toShort())
            }
            buf.flip()
            p.queueInput(buf)
            p.getOutput()
        }
    }

    /**
     * Regression assertion: with the clean envelope, speech bursts lock
     * onto cues authored a known time late.
     */
    @Test
    fun `speech shaped audio must lock onto cues authored a known time late`() {
        val segs = segments(TOTAL_SECONDS)
        assertTrue("test premise: need a realistic number of bursts", segs.size > 40)

        val listener = RecordingListener()
        val p = newProcessor(listener)

        // Cues describe the same bursts, but authored SHIFT seconds late.
        p.setCues(
            segs.map {
                SubtitleCue(
                    start = it.start + SHIFT,
                    end = it.end + SHIFT,
                    lines = listOf("burst"),
                )
            }
        )

        feedDialogue(p, segs)

        // Allow background worker thread to finish correlation passes
        var waited = 0
        while (listener.lockedOffset == null && waited < 2500) {
            Thread.sleep(50)
            waited += 50
        }

        val offset = listener.lockedOffset
        assertTrue(
            "auto-sync never locked on speech-shaped audio; " +
                "the speech envelope is not tracking the cue on/off pattern",
            offset != null,
        )
        // Cues are SHIFT late, so the corrective offset is -SHIFT.
        assertEquals("locked on the wrong shift", (-SHIFT).toFloat(), offset!!, TOLERANCE)
    }

    /**
     * Guards against noise locking: pure silence carries no speech structure,
     * so the engine must decline rather than invent an offset.
     */
    @Test
    fun `silence must not lock`() {
        val listener = RecordingListener()
        val p = newProcessor(listener)
        p.setCues(segments(TOTAL_SECONDS).map {
            SubtitleCue(start = it.start, end = it.end, lines = listOf("burst"))
        })

        val buf = ByteBuffer.allocateDirect(WINDOW_SAMPLES * 2).order(ByteOrder.nativeOrder())
        repeat(TOTAL_SECONDS * 100) {
            buf.clear()
            repeat(WINDOW_SAMPLES) { buf.putShort(0) }
            buf.flip()
            p.queueInput(buf)
            p.getOutput()
        }

        Thread.sleep(250)
        assertTrue("engine invented an offset from silence", listener.lockedOffset == null)
    }
}
