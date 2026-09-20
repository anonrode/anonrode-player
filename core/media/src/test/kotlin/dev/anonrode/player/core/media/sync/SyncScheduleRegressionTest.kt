package dev.anonrode.player.core.media.sync

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.model.SubtitleCue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.8.7 — regression tests for the live PASS-SCHEDULE COLLAPSE.
 *
 * The v0.8.6 device log (09-19, 936 lines) is the evidence these tests encode:
 *
 *     [SYNC] pass t=20s bc=205: judged, gates refused
 *     [SYNC] pass t=20s bc=205: judged, gates refused
 *     [SYNC] pass t=20s bc=205: judged, gates refused
 *
 * Three passes, the SAME bin count, 25 ms apart.
 *
 * [AudioSyncProcessor.setEnabled] used to zero `passesUsed` on EVERY call,
 * including a redundant ON→ON — and the host mirrors the entire settings
 * DataStore into the processor, so every subtitle size/colour/position tap did
 * it. `binCount` was (correctly) left banked, so the next 10 ms window already
 * satisfied PASS_BINS[0], the one after satisfied PASS_BINS[1], and so on: the
 * ~4.8-minute listening budget collapsed into a quarter second of audio, every
 * collapsed pass judged the same tiny window and was refused, each refusal
 * reset `stableHits`, and the two CONSECUTIVE agreeing passes a lock requires
 * became unreachable. The episode then simply never synced.
 * [AudioSyncProcessor.setCues] had the same unconditional re-arm, reached via
 * PlaybackEngine.setSubSyncEnabled re-granting the SAME cue list.
 *
 * These tests drive the REAL processor through its public AudioProcessor API.
 * Timing arithmetic (8000 Hz mono 16-bit): one 10 ms analysis window is 80
 * samples, ten windows make one 100 ms bin, so ONE SECOND OF AUDIO IS TEN BINS
 * — which makes `binCount`, and therefore the expected schedule position,
 * directly controllable from a test.
 */
@UnstableApi
class SyncScheduleRegressionTest {

    private class RecordingListener : SyncListener {
        var noMatchCount = 0
        var lockedCount = 0
        override fun onSyncLocked(offsetSeconds: Float, speedFactor: Float) {
            lockedCount++
        }
        override fun onSyncNoMatch() {
            noMatchCount++
        }
    }

    private companion object {
        const val SAMPLE_RATE = 8000
        const val CHANNELS = 1

        /** Interleaved samples in one 10 ms analysis window. */
        const val WINDOW_SAMPLES = SAMPLE_RATE / 100 * CHANNELS

        /** Seconds of audio banked before the tests touch the enabled/cue flags. */
        const val BANKED_SECONDS = 200

        /** Cheap bank for the cases that do not need a deep schedule. */
        const val SHALLOW_SECONDS = 60

        /**
         * A cue track long enough for accumulateBin to keep scheduling passes.
         * Spacing is deliberately irregular-looking (37.5 s apart) so no
         * accidental alignment with the synthetic audio can produce a lock.
         */
        fun cues(shiftSec: Double = 0.0): List<SubtitleCue> =
            (0 until 40).map { i ->
                val start = i * 37.5 + shiftSec
                SubtitleCue(start = start, end = start + 2.0, lines = listOf("line $i"))
            }
    }

    private fun newProcessor(listener: SyncListener): AudioSyncProcessor =
        AudioSyncProcessor(listener).apply {
            configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
        }

    /** Feeds [seconds] of deterministic pseudo-random PCM, one window at a time. */
    private fun feed(p: AudioSyncProcessor, seconds: Int) {
        val buf = ByteBuffer.allocateDirect(WINDOW_SAMPLES * 2).order(ByteOrder.nativeOrder())
        var seed = 0x5DEECE66DL
        repeat(seconds * 100) {
            buf.clear()
            repeat(WINDOW_SAMPLES) {
                seed = seed * 1103515245L + 12345L
                buf.putShort(((seed ushr 16) and 0x7FF).toShort())
            }
            buf.flip()
            p.queueInput(buf)
            p.getOutput()
        }
    }

    /**
     * The schedule index a bin count of [bins] must have reached. One pass
     * fires per bin crossing and binCount grows by at most one per bin, so
     * exactly the thresholds at or below [bins] have been consumed.
     */
    private fun expectedPasses(bins: Int): Int =
        SpeechCorrelator.PASS_BINS.count { it <= bins }

    /** Guards the premise of the collapse tests: no accidental lock. */
    private fun assertStillListening(p: AudioSyncProcessor) {
        assertFalse(
            "synthetic audio unexpectedly LOCKED — test premise broken, use less " +
                "correlatable audio",
            p.locked,
        )
    }

    @Test
    fun `redundant setEnabled true must not rewind or collapse the schedule`() {
        val listener = RecordingListener()
        val p = newProcessor(listener)
        p.setCues(cues())

        feed(p, BANKED_SECONDS)

        val bins = p.binCount
        val armed = p.passesUsed
        assertTrue("expected ~2000 bins, got $bins", bins in 1900..2100)
        assertEquals(expectedPasses(bins), armed)
        assertFalse("the budget must not be spent yet", p.gaveUp)
        assertStillListening(p)
        assertEquals(0, listener.noMatchCount)

        // Exactly what a subtitle colour tap does: the host mirrors the whole
        // settings DataStore, so setSubSyncEnabled(true) lands on an already-ON
        // processor.
        val passesBefore = p.passesScheduled
        p.setEnabled(true)
        feed(p, 3)

        // THE regression assertion. On v0.8.6 this scheduled ~21 extra passes
        // — every threshold already crossed, re-crossed — inside 0.2 s of
        // audio, each one resetting stableHits and driving the budget toward
        // gaveUp. On fixed code it schedules none: the next threshold is 2280
        // bins and binCount is only ~2000. Note that `passesUsed` ALONE cannot
        // see this — a reset re-climbs the same thresholds and converges back
        // to the same value, which is why the 09-19 log was the only place the
        // collapse ever showed up.
        val extraPasses = p.passesScheduled - passesBefore
        assertTrue(
            "a redundant setEnabled(true) scheduled $extraPasses extra passes " +
                "(expected 0 — this is the v0.8.6 collapse)",
            extraPasses <= 1,
        )
        assertTrue(
            "a redundant setEnabled(true) rewound the pass schedule " +
                "(armed=$armed now=${p.passesUsed})",
            p.passesUsed >= armed,
        )
        assertEquals(
            "a redundant setEnabled(true) collapsed the pass schedule",
            expectedPasses(p.binCount),
            p.passesUsed,
        )
        assertFalse("a redundant setEnabled(true) spent the whole budget", p.gaveUp)
        assertEquals(
            "a redundant setEnabled(true) handed off to the fingerprint",
            0,
            listener.noMatchCount,
        )
    }

    @Test
    fun `re-pushing the identical cue list must not rewind the schedule`() {
        val listener = RecordingListener()
        val p = newProcessor(listener)
        val track = cues()
        p.setCues(track)

        feed(p, BANKED_SECONDS)

        val armed = p.passesUsed
        assertEquals(expectedPasses(p.binCount), armed)
        assertStillListening(p)

        // PlaybackEngine.setSubSyncEnabled(true) re-grants the SAME list on
        // every settings emission — the second unconditional re-arm path.
        val passesBefore = p.passesScheduled
        p.setCues(track)
        feed(p, 3)

        val extraPasses = p.passesScheduled - passesBefore
        assertTrue(
            "re-pushing an identical cue list scheduled $extraPasses extra " +
                "passes (expected 0)",
            extraPasses <= 1,
        )
        assertEquals(
            "re-pushing an identical cue list collapsed the pass schedule",
            expectedPasses(p.binCount),
            p.passesUsed,
        )
        assertTrue(p.passesUsed >= armed)
        assertFalse(p.gaveUp)
        assertEquals(0, listener.noMatchCount)
    }

    @Test
    fun `a genuine OFF to ON flip still re-arms the schedule`() {
        val listener = RecordingListener()
        val p = newProcessor(listener)
        p.setCues(cues())

        feed(p, BANKED_SECONDS)
        assertTrue("expected the schedule to have advanced", p.passesUsed > 0)
        assertStillListening(p)

        p.setEnabled(false)
        assertTrue("OFF must force the give-up", p.gaveUp)

        // The flip the re-arm exists for must keep working.
        p.setEnabled(true)
        assertEquals("an OFF→ON flip must re-arm the budget", 0, p.passesUsed)
        assertFalse(p.gaveUp)
        assertFalse(p.locked)
    }

    @Test
    fun `a genuinely different cue track still re-arms the schedule`() {
        val listener = RecordingListener()
        val p = newProcessor(listener)
        p.setCues(cues())

        feed(p, BANKED_SECONDS)
        assertTrue("expected the schedule to have advanced", p.passesUsed > 0)
        assertStillListening(p)

        p.setCues(cues(shiftSec = 0.5))
        assertEquals(
            "a real track change is a fresh matching problem and must re-arm",
            0,
            p.passesUsed,
        )
    }

    @Test
    fun `clearing the cue list neither re-arms nor hands off`() {
        val listener = RecordingListener()
        val p = newProcessor(listener)
        p.setCues(cues())

        feed(p, SHALLOW_SECONDS)
        val armed = p.passesUsed
        assertTrue("expected the schedule to have advanced", armed > 0)

        p.setCues(emptyList())

        assertEquals("an empty track must not touch the budget", armed, p.passesUsed)
        assertEquals("an empty track must not hand off", 0, listener.noMatchCount)
    }
}
