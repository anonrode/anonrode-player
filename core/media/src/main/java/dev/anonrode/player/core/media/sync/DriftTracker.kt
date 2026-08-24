package dev.anonrode.player.core.media.sync

import kotlin.math.abs

/**
 * Tracks local offset estimates over time and fits a linear drift model.
 *
 * Model: offset(t) = base + rate × t
 * Where `rate` captures progressive drift (frame-rate mismatch).
 */
class DriftTracker {
    private val points = mutableListOf<Point>()

    data class Point(val timeSec: Double, val offsetSec: Double)

    fun add(timeSec: Double, offsetSec: Double) {
        points.add(Point(timeSec, offsetSec))
        if (points.size > 30) points.removeAt(0) // keep recent history
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
     * Returns (baseOffset, speedFactor) where speedFactor = 1 + driftRate.
     * If drift is below threshold, returns (latest_offset, 1.0).
     */
    fun getCorrection(mediaTimeSec: Double): Pair<Double, Float> {
        val fit = fit() ?: return Pair(
            points.lastOrNull()?.offsetSec ?: 0.0, 1f
        )
        val (base, rate) = fit
        // Only apply drift correction if rate is significant (>0.1%)
        if (abs(rate) < 0.001) return Pair(base, 1f)
        return Pair(base, (1.0 + rate).toFloat())
    }
}
