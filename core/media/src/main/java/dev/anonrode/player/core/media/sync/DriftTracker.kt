package dev.anonrode.player.core.media.sync

import kotlin.math.abs

/**
 * Tracks local offset estimates over time and fits a linear drift model.
 *
 * Model: offset(t) = base + rate × t
 * Where `rate` captures progressive drift (frame-rate mismatch).
 *
 * Rate policy (v0.7.2 device-fix round): a rate is only reported when the
 * samples behind it cover enough MEDIA TIME to separate a real rate from
 * measurement noise. The live correlator produces one estimate per evaluation
 * slot (~1 s of audio) and keeps the last six, so a normal session's fitted
 * window spans ~5 s — across 5 s even a perfectly clean 0.25 s disagreement
 * (the stability tolerance!) "fits" a ±5 % rate, which applied as a subtitle
 * speed and persisted to Room would be a far bigger error than the offset
 * being corrected. Below [MIN_DRIFT_SPAN_SEC] the live engine therefore
 * returns an offset-only correction (speed 1.0), and the whole-file
 * fingerprint engine — which fits (alpha, beta) jointly on hundreds of
 * onsets across the entire runtime, and is what the PC validation measured —
 * owns drift. A rate that does clear the span floor is additionally clamped
 * to [MAX_RATE], the plausible frame-rate/container-mismatch range.
 */
class DriftTracker {
    private val points = mutableListOf<Point>()

    data class Point(val timeSec: Double, val offsetSec: Double)

    fun add(timeSec: Double, offsetSec: Double) {
        points.add(Point(timeSec, offsetSec))
        if (points.size > 30) points.removeAt(0) // keep recent history
    }

    /**
     * Drops all history (v0.7.4 P1-4). Without this the tracker survived
     * [AudioSyncProcessor.resetWindow], so the six points a post-seek /
     * post-episode-switch lock fits can straddle the boundary: the span
     * then easily clears [MIN_DRIFT_SPAN_SEC], the LSQ fit "discovers" a
     * huge rate from the offset jump, the clamp turns it into ±2.5 %, and
     * a ~9-second-per-minute subtitle speed error gets persisted as if
     * measured.
     */
    fun reset() {
        points.clear()
    }

    /**
     * Least-squares fit: offset(t) = a + b×t
     * Returns (baseOffset, driftRatePerSecond) or null if insufficient data.
     */
    fun fit(): Pair<Double, Double>? {
        val pts = points.sortedBy { it.timeSec }.takeLast(6)
        if (pts.size < 2) return null
        val n = pts.size
        val mt = pts.sumOf { it.timeSec } / n
        val mo = pts.sumOf { it.offsetSec } / n
        var num = 0.0; var den = 0.0
        for (p in pts) {
            num += (p.timeSec - mt) * (p.offsetSec - mo)
            den += (p.timeSec - mt) * (p.timeSec - mt)
        }
        if (abs(den) < 1e-9) return null
        val b = num / den
        return Pair(mo - b * mt, b)
    }

    /**
     * Returns (offsetSeconds, speedFactor) to apply to the subtitle render
     * loop. The offset is always the freshest measured alignment; the speed
     * is only ever non-1.0 for a fit spanning [MIN_DRIFT_SPAN_SEC] of media
     * time (see the class KDoc).
     */
    fun getCorrection(): Pair<Double, Float> {
        val latest = points.lastOrNull()?.offsetSec ?: return Pair(0.0, 1f)
        val pts = points.sortedBy { it.timeSec }.takeLast(6)
        if (pts.size < 2) return Pair(latest, 1f)
        val span = pts.last().timeSec - pts.first().timeSec
        val (base, rate) = fit() ?: return Pair(latest, 1f)
        if (span < MIN_DRIFT_SPAN_SEC) {
            // Drift is not measurable on this little media time. Report the
            // offset the last agreeing evaluations produced — extrapolating
            // an untrustworthy rate over the whole media position would be
            // worse than no correction at all.
            return Pair(latest, 1f)
        }
        val r = rate.coerceIn(-MAX_RATE, MAX_RATE)
        // Only apply drift correction if rate is significant (>0.1%)
        if (abs(r) < 0.001) return Pair(base, 1f)
        return Pair(base, (1.0 + r).toFloat())
    }

    companion object {
        /** Media-time span the fitted samples must cover before a rate is
         *  reported at all. See the class KDoc for why a few one-second
         *  evaluation slots are not enough evidence. */
        private const val MIN_DRIFT_SPAN_SEC = 60.0

        /** Plausible rate mismatch (23.976 vs 24 fps = 0.1 %, NTSC film
         *  transfers ~0.1 %). Anything larger than this is measurement
         *  noise, not a real speed difference. */
        private const val MAX_RATE = 0.025
    }
}
