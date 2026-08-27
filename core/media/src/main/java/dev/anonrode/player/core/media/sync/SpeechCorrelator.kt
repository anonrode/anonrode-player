package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.model.SubtitleCue
import kotlin.math.abs

/**
 * Subtitle ↔ audio alignment by binary speech-track cross-correlation —
 * the ffsubsync method, validated against simulation (tools/subtitle_engine_sim.py,
 * 23/23 cases) before implementation.
 *
 * Both sources are discretized to a 100ms speech-activity grid:
 *   A(t) = audio speech (adaptive floor/peak VAD, binarized at 0.3)
 *   B(t) = subtitle cue activity (1 inside a cue)
 *
 * Score every alignment δ ∈ [−40s, +40s]:
 *   score(δ) = Σ A(t)·(2·B(t+δ) − 1) / Σ A(t)   ∈ [−1, 1]
 *
 * Lock gates (all must hold):
 *   score > 0.2                        — strongly positive peak
 *   margin > 0.15                      — clear separation from runner-up
 *                                           (±2s exclusion zone)
 *   containment ≥ 0.7                  — most audio speech sits inside cues
 *   cross-half validation              — the offset found on the first half
 *                                           of the audio replicates on the
 *                                           second half (multiple-comparisons
 *                                           guard: chance peaks don't
 *                                           replicate, true offsets do)
 */
object SpeechCorrelator {

    const val ALIGN_BIN = 0.1
    const val MIN_AUDIO_SECONDS = 16.0
    const val MAX_OFFSET_SEC = 40.0
    const val MIN_SPEECH_BINS = 30

    data class Result(
        val offsetSeconds: Double,
        val score: Double,
        val margin: Double,
        val containment: Double,
    ) {
        val lockable: Boolean = true
    }

    /**
     * @param audio soft speech values per 0.1s bin; index 0 is the window
     *              start, i.e. bin i covers media time
     *              baseSeconds + i*0.1s (the processor slides the window
     *              forward for long/resumed playback)
     * @param binCount number of valid bins (window span = binCount*0.1s)
     * @param baseSeconds media time of audio[0]; cues are mapped onto the
     *                    grid relative to it
     */
    fun findOffset(
        audio: FloatArray,
        binCount: Int,
        cues: List<SubtitleCue>,
        maxOffsetSec: Double = MAX_OFFSET_SEC,
        baseSeconds: Double = 0.0,
    ): Result? {
        if (binCount < (MIN_AUDIO_SECONDS / ALIGN_BIN).toInt()) return null
        if (cues.size < 3) return null
        val total = audio.size

        // ── VAD: hard binary decision on the soft speech track ─────
        val a = ByteArray(binCount)
        var mass = 0
        for (i in 0 until binCount) {
            if (audio[i] > 0.3f) {
                a[i] = 1
                mass++
            }
        }
        if (mass < MIN_SPEECH_BINS) return null // <3s of detected speech

        // ── subtitle track B on the same grid ──────────────────────
        val b = ByteArray(total)
        for (cue in cues) {
            val i0 = maxOf(0, ((cue.start - baseSeconds) / ALIGN_BIN).toInt())
            val i1 = minOf(total - 1, ((cue.end - baseSeconds) / ALIGN_BIN).toInt())
            for (i in i0..i1) b[i] = 1
        }

        // ── correlate over all shifts ──────────────────────────────
        val lo = -(maxOffsetSec / ALIGN_BIN).toInt()
        val hi = (maxOffsetSec / ALIGN_BIN).toInt()
        val scores = FloatArray(hi - lo + 1)
        var bestScore = -2.0f
        var bestShift = 0
        for (shift in lo..hi) {
            var sc = 0
            for (i in 0 until binCount) {
                if (a[i].toInt() == 0) continue
                val j = i + shift
                val bit = if (j in 0 until total) b[j].toInt() else 0
                sc += 2 * bit - 1
            }
            val norm = sc / mass.toFloat()
            scores[shift - lo] = norm
            if (norm > bestScore) {
                bestScore = norm
                bestShift = shift
            }
        }

        // ── runner-up outside ±2s exclusion zone ───────────────────
        var second = -2.0f
        for (k in scores.indices) {
            val sh = k + lo
            if (abs(sh - bestShift) * ALIGN_BIN > 2.0 && scores[k] > second) {
                second = scores[k]
            }
        }
        val margin = bestScore - second

        // ── containment: audio speech mass inside cues at the peak ─
        var inside = 0
        for (i in 0 until binCount) {
            if (a[i].toInt() == 0) continue
            val j = i + bestShift
            inside += if (j in 0 until total) b[j].toInt() else 0
        }
        val containment = inside / mass.toFloat()

        // ── cross-half validation ──────────────────────────────────
        val mid = binCount / 2
        var half1Shift = Int.MIN_VALUE
        var half1Score = -2.0f
        for (shift in lo..hi) {
            var sc = 0f
            var m = 0f
            for (i in 0 until mid) {
                if (a[i].toInt() == 0) continue
                m += 1f
                val j = i + shift
                sc += if (j in 0 until total) b[j].toInt() else 0
            }
            val v = if (m > 0f) sc / m else 0f
            if (v > half1Score) {
                half1Score = v
                half1Shift = shift
            }
        }
        var m2 = 0f
        var c2 = 0f
        for (i in mid until binCount) {
            if (a[i].toInt() == 0) continue
            m2 += 1f
            val j = i + bestShift
            c2 += if (j in 0 until total) b[j].toInt() else 0
        }
        val containment2 = if (m2 > 0f) c2 / m2 else 0f
        val validated = half1Shift != Int.MIN_VALUE &&
            abs(half1Shift - bestShift) * ALIGN_BIN <= 0.3 &&
            containment2 >= 0.7

        val lockable = bestScore > 0.2 && margin > 0.15 &&
            containment >= 0.7 && validated
        // Renderer convention: applied offset = −peak (subs late → negative).
        val offset = -bestShift * ALIGN_BIN
        return if (lockable) {
            Result(offsetSeconds = offset, score = bestScore.toDouble(),
                margin = margin.toDouble(), containment = containment.toDouble())
        } else null
    }
}
