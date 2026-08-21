package dev.anonrode.player.core.media.sync

import kotlin.math.abs
import kotlin.math.min

/** Result of an alignment search. */
data class AlignmentResult(
    val offsetSeconds: Double,
    val confidence: Double,
    val spread: Double,
    val coverage: Double,
    val score: Double,
)

/**
 * Live alignment state: accumulated audio energy + the current model.
 * Mirrors the mxweb-player JS `createAlignState`.
 */
class AlignmentState(
    val model: CueModel,
) {
    val audio = FloatArray(model.length)
    val rise = FloatArray(model.length)
    var maxTime = 0.0
    var sampleCount = 0
    var floor = 0.0
    var peak = 0.0
    var lastSpeech = 0.0
    var bestResult: AlignmentResult? = null
    var stableHits = 0
    var lastOffset: Double? = null
    var locked = false
}

/**
 * Ported from the mxweb-player JS `scoreAlignment` / `findBestAlignment`.
 * Scores how well the accumulated audio matches the cue model at a given
 * offset, then does a coarse (0.25s) search followed by a fine (0.05s) pass.
 */
object AlignmentEngine {
    const val ALIGN_BIN = 0.1
    const val ALIGN_MIN_AUDIO_SECONDS = 16.0
    const val LOCK_CONFIDENCE = 0.74

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val copy = values.sorted()
        val mid = copy.size / 2
        return if (copy.size % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) / 2
    }

    private fun scoreAlignment(state: AlignmentState, offsetSec: Double): AlignmentResult? {
        val shift = (offsetSec / ALIGN_BIN).toInt()
        var score = 0.0
        var weighted = 0.0
        var edgeHits = 0.0
        var coverage = 0.0
        val model = state.model
        val maxBin = min(state.audio.size - 1, (state.maxTime / ALIGN_BIN).toInt())

        for (i in 0..maxBin) {
            val a = state.audio[i].toDouble()
            val r = state.rise[i].toDouble()
            if (a < 0.035 && r < 0.025) continue
            val j = i - shift
            if (j < 0 || j >= model.length) continue
            val expected = model.speech[j].toDouble()
            val edge = model.edge[j].toDouble()
            score += a * (expected * 1.6 - (1 - min(1.0, expected)) * 0.82)
            score += r * (edge * 1.35 + expected * 0.32)
            weighted += 1
            if (expected > 0.22) coverage += 1
            if (edge > 0.35 && r > 0.04) edgeHits++
        }

        if (weighted < 90) return null
        val norm = score / weighted
        return AlignmentResult(
            offsetSeconds = offsetSec,
            score = norm + edgeHits / maxOf(1.0, weighted) * 0.9 - abs(offsetSec) * 0.0015,
            coverage = coverage / weighted,
            spread = 0.0,
            confidence = 0.0,
        )
    }

    fun findBest(state: AlignmentState): AlignmentResult? {
        if (state.maxTime < ALIGN_MIN_AUDIO_SECONDS) return null

        val limit = min(40.0, maxOf(6.0, state.maxTime - 4))
        val coarse = ArrayList<AlignmentResult>()
        var off = -limit
        while (off <= limit + 0.001) {
            scoreAlignment(state, off)?.let {
                coarse.add(it.copy(offsetSeconds = off))
            }
            off += 0.25
        }
        if (coarse.size < 6) return null

        coarse.sortByDescending { it.score }
        val bestCoarse = coarse[0]
        var runner = coarse.firstOrNull { abs(it.offsetSeconds - bestCoarse.offsetSeconds) > 0.8 }
            ?: coarse[min(1, coarse.size - 1)]

        var best = bestCoarse
        var fine = bestCoarse.offsetSeconds - 0.6
        while (fine <= bestCoarse.offsetSeconds + 0.6 + 0.0001) {
            scoreAlignment(state, fine)?.let { c ->
                if (c.score > best.score) best = c.copy(offsetSeconds = fine)
            }
            fine += 0.05
        }

        val scoreList = coarse.map { it.score }
        val mid = median(scoreList)
        val spread = best.score - runner.score
        val confidence =
            spread * 2.15 +
                (best.score - mid) * 0.8 +
                best.coverage * 0.55 +
                min(0.4, best.coverage * 0.0 + 0.0) // edgeHits folded into coverage path

        return best.copy(
            spread = spread,
            confidence = confidence,
        )
    }
}
