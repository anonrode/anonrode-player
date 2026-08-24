package dev.anonrode.player.core.media.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Multi-feature speech detector: energy + syllable variance + ZCR.
 * Separates dialogue from steady-state background music in C-drama content.
 */
object SpeechDetector {

    fun detect(samples: ShortArray, count: Int, floor: Double, peak: Double): Float {
        if (count == 0) return 0f
        var sumSq = 0.0; var zcr = 0; var prevSign = samples[0] >= 0
        var sumAbs = 0.0
        for (k in 0 until count) {
            val v = samples[k]; sumSq += v.toDouble() * v; sumAbs += abs(v.toFloat())
            val s = v >= 0; if (s != prevSign) zcr++; prevSign = s
        }
        val rms = sqrt(sumSq / count)
        val meanAmp = (sumAbs / count).toFloat()
        variance = samples.take(count).sumOf { d -> d.toDouble().let { it * it } / max(meanAmp * meanAmp, 1f) }.toFloat() / count

        val energyScore = if (peak > floor) ((rms - floor*1.08) / max(peak - floor*1.08, 0.001)).coerceIn(0.0, 1.0).toFloat() else 0f
        val varianceScore = min(variance / 2f, 1f)
        val zcrNorm = zcr.toFloat() / count
        val zcrScore = when { zcrNorm in 0.02f..0.15f -> 1f; zcrNorm < 0.02f -> 0.5f; else -> max(0f, 1f - (zcrNorm - 0.15f) / 0.2f) }
        return energyScore * 0.5f + varianceScore * 0.3f + zcrScore * 0.2f
    }

    private var variance: Float = 0f
}
