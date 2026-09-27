package dev.anonrode.player.core.media.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9 — regression tests for the drift-tracker's "insignificant rate" branch.
 *
 * [DriftTracker.getCorrection] fits `offset(t) = base + rate·t` by least
 * squares and then decides whether `rate` is worth acting on. It used to
 * return the fit's INTERCEPT (`base`) in both the significant and the
 * insignificant case.
 *
 * `base` is the offset extrapolated back to t=0, and t is ABSOLUTE media
 * time. So on the insignificant branch the tracker applied a drift
 * correction it had just classified as noise. Measured, with a rate of
 * 0.0009 — 0.09 %, an order of magnitude below the 0.1 % significance
 * floor:
 *
 *   position   600 s -> 0.594 s of applied offset error
 *   position  3600 s -> 3.294 s of applied offset error
 *
 * for a pass-to-pass stability tolerance of 0.25 s. [AudioSyncProcessor]
 * forwards the pair straight to `SyncListener.onSyncLocked`, and
 * PlaybackEngine persists it, so the error was written to the database and
 * replayed on every later viewing of the file — subtitles that looked
 * synced at the 10-minute mark were seconds out an hour in, and stayed
 * that way across restarts.
 *
 * The fix returns the freshly MEASURED offset when the rate is not
 * significant, matching what the too-little-span branch already did.
 */
class DriftTrackerTest {

    private companion object {
        /** Mirror of DriftTracker's private floors, asserted to stay honest. */
        const val MIN_DRIFT_SPAN_SEC = 60.0
        const val RATE_SIGNIFICANCE = 0.001

        /** Tolerance for "the applied offset equals the measured offset". */
        const val EPS = 1e-3
    }

    /**
     * Feeds six points spanning [startSec]..[startSec]+60 with a perfectly
     * linear drift of [rate] whose value AT [startSec] is [offsetAtStart].
     */
    private fun fed(startSec: Double, offsetAtStart: Double, rate: Double): DriftTracker {
        val t = DriftTracker()
        for (i in 0 until 6) {
            val time = startSec + i * (MIN_DRIFT_SPAN_SEC / 5.0)
            t.add(time, offsetAtStart + rate * (time - startSec))
        }
        return t
    }

    @Test
    fun `insignificant rate applies the measured offset, not the extrapolated intercept`() {
        val rate = 0.0009 // 0.09 % — under the significance floor
        val start = 600.0
        val offsetAtStart = 2.0

        val (applied, speed) = fed(start, offsetAtStart, rate).getCorrection()

        assertEquals("a negligible rate must not move the speed", 1f, speed, 0f)
        // Correct answer: the LAST measured offset.
        val expected = offsetAtStart + rate * MIN_DRIFT_SPAN_SEC
        assertEquals(
            "applied offset drifted off the measurement via the intercept",
            expected,
            applied,
            EPS,
        )
    }

    @Test
    fun `the extrapolation error grows with media position`() {
        val rate = 0.0009
        val offsetAtStart = 2.0

        val early = fed(600.0, offsetAtStart, rate).getCorrection().first
        val late = fed(3600.0, offsetAtStart, rate).getCorrection().first

        // The measurement is identical at both positions, so the applied
        // offset must be too. Before the fix these differed by 2.7 s.
        assertEquals(
            "applied offset must not depend on where in the file we are",
            early,
            late,
            EPS,
        )
        assertTrue(
            "sanity: offset should still be the measured ~2.05 s, got $early",
            kotlin.math.abs(early - 2.054) < 0.01,
        )
    }
    @Test
    fun `a real drift above the floor still applies offset and speed together`() {
        val rate = 0.004 // 0.4 % — above the floor, below MAX_RATE (2.5 %)
        val start = 600.0
        val (applied, speed) = fed(start, 2.0, rate).getCorrection()

        // Significant rate: speed is applied, and the offset MUST be the
        // matching intercept, because the pair (alpha, beta) has to describe
        // ONE consistent affine map audio_time = alpha*cue_time + beta. Beta
        // is by definition the offset at cue_time = 0, which for this fit is
        // 2.0 + 0.004*(0 - 600) = -0.4 — not the 2.0 measured at t=600.
        assertEquals(1.0 + rate, speed.toDouble(), 1e-3)
        assertEquals(2.0 + rate * (0.0 - start), applied, 1e-3)
    }

    @Test
    fun `a rate above MAX_RATE is clamped, not extrapolated without limit`() {
        val (_, speed) = fed(600.0, 2.0, 0.5).getCorrection() // absurd 50 %
        assertEquals(1.025, speed.toDouble(), 1e-3) // MAX_RATE = 2.5 %
    }

    @Test
    fun `too little span keeps the measured offset and unit speed`() {
        val t = DriftTracker()
        for (i in 0 until 6) t.add(100.0 + i * 2.0, 1.5 + i * 0.01) // 10 s span
        val (applied, speed) = t.getCorrection()
        assertEquals(1f, speed, 0f)
        assertEquals(1.55, applied, 1e-6)
    }

    @Test
    fun `a single point reports itself with unit speed`() {
        val t = DriftTracker()
        t.add(1234.0, -3.25)
        val (applied, speed) = t.getCorrection()
        assertEquals(1f, speed, 0f)
        assertEquals(-3.25, applied, 1e-9)
    }

    @Test
    fun `reset drops history so a post-seek lock cannot fit across the boundary`() {
        val t = DriftTracker()
        t.add(0.0, 5.0)
        t.add(600.0, 1.0) // a jump, e.g. across a seek
        t.reset()
        t.add(600.0, 2.0)
        t.add(660.0, 2.0)
        val (applied, speed) = t.getCorrection()
        // Without the reset the 5.0->1.0 jump fits a huge rate, which the
        // clamp turns into a several-percent subtitle speed error.
        assertEquals(1f, speed, 0f)
        assertEquals(2.0, applied, 1e-6)
    }
}
