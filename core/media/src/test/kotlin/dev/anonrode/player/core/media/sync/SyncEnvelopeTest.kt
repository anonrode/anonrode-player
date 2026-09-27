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
 * v0.8.8 — regression test for the SPEECH-ENVELOPE COLLAPSE.
 *
 * `AudioSyncProcessor.finishWindow` used to score each 10 ms window as
 *
 *     energyScore * 0.5 + varianceScore * 0.3 + zcrScore * 0.2
 *
 * Neither supporting term could vary in a useful way:
 *
 *  * `varianceScore = min(normVar / 2, 1)` saturated at 1.0 — measured mean
 *    0.992 over real dialogue — so it contributed a flat +0.30 to every
 *    window and carried no information at all.
 *  * `energyScore` divided by `up - uf` where `up = max(uf + 0.0012, peak)`.
 *    `peak` was floored at `floor + 0.00035` while `floor` chased `rms`, so
 *    in the steady state `peak <= uf` and the denominator collapsed to the
 *    0.0012 epsilon. Any window at or below `floor * 1.08` scored a hard 0 —
 *    measured at ~70% of all windows.
 *
 * The consequence was measured, not guessed. Over three real episodes
 * (Better Call Saul S1E02/E03/E05, 44.1 kHz) the resulting 100 ms bins never
 * fell below ~0.39, ALL of them cleared the 0.3 "hard speech" gate in
 * `SpeechCorrelator`, and Pearson r against the subtitle on/off mask peaked
 * at 0.18-0.21 — below `PEAK_MIN` (0.30). The live engine therefore never
 * locked on real dialogue, which is why auto-sync appeared to do nothing.
 *
 * The fix replaces the three-term score with the window's RMS placed between
 * an asymmetric quiet floor and loud peak. On the same ground truth that
 * takes 33/33 known offsets, this locks every time with a residual of one
 * bin (0.1 s).
 *
 * The audio here is synthetic but shaped like dialogue: irregular speech
 * bursts separated by irregular gaps, generated from a fixed seed. The
 * irregularity matters — a periodic pattern would correlate just as well at
 * every aliased shift, so a lock would prove nothing.
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
         * the first burst — otherwise the very first window defines both ends
         * of the range and the test proves nothing.
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
     * THE regression assertion. With the collapsed envelope the peak
     * correlation never reached PEAK_MIN, so `locked` stayed false and the
     * offset was never reported — the feature was inert on real content.
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
     * Guards the premise of the test above: pure silence carries no speech
     * structure, so the engine must decline rather than invent an offset.
     * Without this, a "lock" on the first test could just be noise locking.
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

        assertTrue("engine invented an offset from silence", listener.lockedOffset == null)
    }
}
