package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.model.SubtitleCue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Subtitle ↔ audio alignment by soft-envelope cross-correlation — the
 * v0.8 redesign, informed by ffsubsync and validated scenario-by-scenario
 * in a 1:1 simulation port (C:/tmp/syncsim/livesim.py) BEFORE shipping.
 *
 * What changed vs the v0.7 binary grid (and why):
 *
 *   The old scorer was `score(δ) = (2·hits − mass)/mass` over A bins that
 *   are a hard binary decision (`audio[i] > 0.3f`), and every speech bin
 *   outside the cue coverage at the evaluated shift paid a −1 penalty.
 *   That construction had two systematic false-negative classes measured
 *   in simulation:
 *     • quiet endings / early-offset tracks — audio speech past the last
 *       cue (or a head region before the first one) drags the CORRECT
 *       alignment's score toward 1−2·frac, below the 0.2 gate BY
 *       CONSTRUCTION: such videos could never lock no matter how long
 *       the engine listened;
 *     • the 0.3 binarization threw the soft VAD score away, so soft-mix
 *       dialogue (0.25–0.29 bins) counted against its own alignment.
 *
 *   v0.8 scores the SAME pair of grids with a mean-centered Pearson
 *   correlation (ffsubsync's "match 1↔1 AND 0↔0" doctrine): regions
 *   where BOTH tracks agree — silence against gap included — raise r;
 *   a trailing dead zone no longer singles out the true shift, it
 *   shifts the whole curve down equally and the PROMINENCE of the peak
 *   survives. The soft envelope is kept (quantized to 8 bits purely so
 *   the inner product runs as bit-plane popcounts at word-array speed —
 *   0.004 quantization noise vs a gate at 0.30).
 *
 * Lock gates (measured against the 30-track false-lock scan):
 *   binCount ≥ [ELIGIBLE_BINS]                  — small windows produce
 *                                                  0.5+ chance peaks on
 *                                                  random tracks;
 *   peak r ≥ [PEAK_MIN]                         — a real alignment;
 *   prominence ≥ [PROM_MIN]                     — best shift minus the
 *                                                  best shift ≥2 s away;
 *   z = r·√binCount ≥ [Z_SMALL]/[Z_LARGE]       — the noise floor of a
 *                                                  correlation over n
 *                                                  bins shrinks as 1/√n,
 *                                                  so short windows must
 *                                                  clear a taller bar;
 *   two CONSECUTIVE passes agreeing within 0.25 s (see
 *   [AudioSyncProcessor]) — the single-pass fast-lock variant false-locked
 *   13/30 random tracks in simulation and is gone.
 *
 * Implementation note: runs on the sync-eval worker thread (never the
 * audio render thread). The correlation is O(shifts × words) with
 * LongArray word shifts + 8 bit-plane popcounts per shift:
 * 1201 shifts × ~155 words × (2 ors + 8 popcounts) ≈ 120k word ops per
 * pass — microseconds, and passes now run on the scheduler cadence
 * (~24 total per listening session), not once per second.
 */
object SpeechCorrelator {

    const val ALIGN_BIN = 0.1

    /**
     * Minimum analyzed audio before the first pass may run. Kept at the
     * v0.7.1 speed-pass value; in v0.8 this is a NOT-READY floor, not a
     * lock floor — the [ELIGIBLE_BINS] gate decides when locking starts.
     */
    const val MIN_AUDIO_SECONDS = 8.0

    /** Offset search radius. v0.7: ±40 s. v0.8: ±60 s, matching
     *  ffsubsync's `--max-offset-seconds` default; sim S9 (+60 s) locks. */
    const val MAX_OFFSET_SEC = 60.0

    /** Min 0.3-level speech bins in the window for a pass to be judged. */
    const val MIN_SPEECH_BINS = 30

    /**
     * v0.8 pass schedule, in bins of window growth (0.1 s each): 6 passes
     * on the dense early ramp (every ~2 s of listening), 10 at 5 s
     * intervals, 8 at 30 s intervals — ~24 correlations for a whole
     * listening session (~4.8 min of accumulated audio at the last
     * threshold) where v0.7 ran ~30 PER MINUTE at 1 Hz. The shape comes
     * from the simulation: clean pairs lock by pass 6 (~18 s), the hard
     * S3/S5 classes need passes 11/16 (~43/68 s), and nothing worth
     * locking needs faster than that. [AudioSyncProcessor] re-arms this
     * schedule on every position reset / fresh cue attach.
     */
    val PASS_BINS = intArrayOf(
        80, 100, 120, 140, 160, 180,
        230, 280, 330, 380, 430, 480, 530, 580, 630, 680,
        780, 1080, 1380, 1680, 1980, 2280, 2580, 2880,
    )

    // ── v0.8 gates (see class KDoc for the measurements behind each) ──
    const val PEAK_MIN = 0.30
    const val PROM_MIN = 0.12
    const val Z_SMALL = 9.0
    const val Z_LARGE = 7.0
    const val ELIGIBLE_BINS = 160
    const val EXCLUSION_BINS = 20

    data class Result(
        val offsetSeconds: Double,
        val score: Double,
        val margin: Double,
        val containment: Double,
        val z: Double,
    ) {
        val lockable: Boolean = true
    }

    /**
     * Whole-file 2D alignment result covering both framerate drift (alpha)
     * and temporal offset (beta): audio_time = alpha * subtitle_time + beta.
     */
    data class JointResult(
        val alpha: Double,
        val beta: Double,
        val score: Double,
        val margin: Double,
        val recall: Double,
        val halfOk: Boolean,
    )

    /**
     * Outcome of one correlation pass. Carried over from v0.7.4: the
     * caller distinguishes "this window was too thin to judge" (NotReady)
     * from "judged and refused" (NoMatch) for honest sync-log evidence.
     * The v0.8 scheduler bounds cost with the pass CADENCE, so the
     * distinction no longer gates a failure budget.
     */
    sealed class Outcome {
        object NotReady : Outcome()
        object NoMatch : Outcome()
        data class Match(val result: Result) : Outcome()
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
    ): Outcome {
        if (binCount < (MIN_AUDIO_SECONDS / ALIGN_BIN).toInt()) return Outcome.NotReady
        if (cues.size < 3) return Outcome.NotReady
        val n = binCount
        val total = audio.size

        // hard decision ONLY for the sufficiency floor + containment diag
        var hard = 0
        for (i in 0 until n) if (audio[i] > 0.3f) hard++
        if (hard < MIN_SPEECH_BINS) return Outcome.NotReady
        if (n < ELIGIBLE_BINS) return Outcome.NoMatch // reportable, not lockable yet

        // A envelope → 8 bit-planes over a word array (bit i at position i)
        val words = (n + 63) / 64
        val planes = Array(8) { LongArray(words) }
        for (i in 0 until n) {
            // quantize soft [0,1] onto the 0..255 grid
            var q = (audio[i] * 255f + 0.5f).toInt()
            if (q > 255) q = 255
            if (q > 0) {
                val w = i shr 6
                val bit = 1L shl (i and 63)
                for (p in 0 until 8) if ((q shr p) and 1 != 0) planes[p][w] = planes[p][w] or bit
            }
        }
        // exact prefix sums of A and A² for the Pearson moments
        val pa = DoubleArray(n + 1)
        val pa2 = DoubleArray(n + 1)
        for (i in 0 until n) {
            val v = audio[i].toDouble()
            pa[i + 1] = pa[i] + v
            pa2[i + 1] = pa2[i] + v * v
        }
        val sumA = pa[n]
        val sumA2 = pa2[n]
        val varA = n * sumA2 - sumA * sumA
        if (varA <= 1e-9) return Outcome.NoMatch

        // B cue grid on the FULL bin array, word layout, bit j at j
        val bWords = (total + 63) / 64
        val B = LongArray(bWords)
        for (cue in cues) {
            val i0 = maxOf(0, ((cue.start - baseSeconds) / ALIGN_BIN).toInt())
            val i1 = minOf(total - 1, ((cue.end - baseSeconds) / ALIGN_BIN).toInt())
            if (i0 > i1) continue
            var w = i0 shr 6
            val wEnd = i1 shr 6
            if (w == wEnd) {
                B[w] = B[w] or (maskRange(i0 and 63, (i1 and 63) + 1))
            } else {
                B[w] = B[w] or (maskRange(i0 and 63, 64))
                for (k in w + 1 until wEnd) B[k] = -1L
                B[wEnd] = B[wEnd] or (maskRange(0, (i1 and 63) + 1))
            }
        }

        val lo = -(maxOffsetSec / ALIGN_BIN).toInt()
        val hi = (maxOffsetSec / ALIGN_BIN).toInt()
        val shifts = hi - lo + 1
        val rs = DoubleArray(shifts)
        val dest = LongArray(words)
        val lastBits = n and 63

        var peak = -2.0
        var bestShift = 0
        for (idx in 0 until shifts) {
            val shift = idx + lo
            shiftB(B, bWords, dest, words, shift)
            if (lastBits != 0) dest[words - 1] = dest[words - 1] and ((1L shl lastBits) - 1)
            var sB = 0
            for (k in 0 until words) sB += java.lang.Long.bitCount(dest[k])
            if (sB == 0 || sB == n) { rs[idx] = -2.0; continue }
            var sAB = 0L
            for (p in 0 until 8) {
                val pl = planes[p]
                var c = 0
                for (k in 0 until words) c += java.lang.Long.bitCount(pl[k] and dest[k])
                if (c != 0) sAB += (1L shl p) * c
            }
            val num = n * (sAB.toDouble() / 255.0) - sumA * sB
            val varB = n.toDouble() * sB - (sB.toDouble() * sB)
            val den = sqrt(varA * varB)
            if (den < 1e-9) { rs[idx] = -2.0; continue }
            // Double throughout, matching the oracle: a Float r rounds at
            // ~1e-7 and margin = peak - second subtracts two near-equal r
            // values, so the ~0.12 gate could flip on the rounding.
            val r = num / den
            rs[idx] = r
            if (r > peak) { peak = r; bestShift = shift }
        }
        if (peak <= -2.0) return Outcome.NoMatch

        // prominence: best shift at least EXCLUSION_BINS away
        var second = -2.0
        for (idx in 0 until shifts) {
            if (abs(idx + lo - bestShift) > EXCLUSION_BINS && rs[idx] > second) second = rs[idx]
        }
        val margin = if (second <= -2.0) peak else peak - second

        // containment diagnostic: speech bins covered at the peak
        shiftB(B, bWords, dest, words, bestShift)
        if (lastBits != 0) dest[words - 1] = dest[words - 1] and ((1L shl lastBits) - 1)
        var inside = 0
        for (i in 0 until n) {
            if (audio[i] > 0.3f && ((dest[i shr 6] ushr (i and 63)) and 1L) != 0L) inside++
        }
        val containment = inside.toFloat() / hard

        val z = peak * sqrt(n.toDouble())
        val zFloor = if (n < 240) Z_SMALL else Z_LARGE
        val lockable = peak >= PEAK_MIN && margin >= PROM_MIN && z >= zFloor
        // Renderer convention: applied offset = −peak (subs late → negative).
        val offset = -bestShift * ALIGN_BIN
        return if (lockable) {
            Outcome.Match(
                Result(offsetSeconds = offset, score = peak.toDouble(),
                    margin = margin.toDouble(), containment = containment.toDouble(),
                    z = z),
            )
        } else Outcome.NoMatch
    }

    /**
     * Whole-file 2D subtitle ↔ audio alignment by soft-envelope cross-correlation.
     * Searches joint (alpha, beta) over physical framerate drift bands and wide offsets.
     */
    fun findJointSync(
        audio: FloatArray,
        cues: List<SubtitleCue>,
        maxOffsetSec: Double = MAX_OFFSET_SEC,
    ): JointResult? {
        if (audio.size < (MIN_AUDIO_SECONDS / ALIGN_BIN).toInt()) return null
        if (cues.size < 5) return null

        val n = audio.size
        // 1. Quantize audio soft [0, 1] into 8 bit-planes over LongArray
        val words = (n + 63) / 64
        val planes = Array(8) { LongArray(words) }
        var hardCount = 0
        for (i in 0 until n) {
            val v = audio[i]
            if (v > 0.3f) hardCount++
            var q = (v * 255f + 0.5f).toInt()
            if (q > 255) q = 255
            if (q > 0) {
                val w = i shr 6
                val bit = 1L shl (i and 63)
                for (p in 0 until 8) {
                    if ((q shr p) and 1 != 0) planes[p][w] = planes[p][w] or bit
                }
            }
        }
        if (hardCount < MIN_SPEECH_BINS) return null

        // Exact moments of A for Pearson r
        var sumA = 0.0
        var sumA2 = 0.0
        for (i in 0 until n) {
            val v = audio[i].toDouble()
            sumA += v
            sumA2 += v * v
        }
        val varA = n * sumA2 - sumA * sumA
        if (varA <= 1e-9) return null

        // Candidate framerate drift slopes (physical broadcast rungs)
        // 1. Standard drift & NTSC/telecine: 0.995 to 1.015 (step 0.0005)
        // 2. PAL speedup: 1.0410 to 1.0435 (step 0.0005)
        // 3. PAL slowdown: 0.9585 to 0.9610 (step 0.0005)
        val alphaCandidates = mutableListOf<Double>()
        for (a in 9950..10150 step 5) alphaCandidates.add(a / 10000.0)
        for (a in 10410..10435 step 5) alphaCandidates.add(a / 10000.0)
        for (a in 9585..9610 step 5) alphaCandidates.add(a / 10000.0)

        val lo = -(maxOffsetSec / ALIGN_BIN).toInt()
        val hi = (maxOffsetSec / ALIGN_BIN).toInt()
        val dest = LongArray(words)
        val lastBits = n and 63

        data class Peak(val alpha: Double, val shift: Int, val r: Double)
        var globalBest = Peak(1.0, 0, -2.0)
        val allPeaks = ArrayList<Peak>()

        // Coarse shift stride = 2 (0.2s) across all slopes
        for (alpha in alphaCandidates) {
            val lastCueSec = cues.last().end
            val bTotal = maxOf(n, (lastCueSec * alpha / ALIGN_BIN).toInt() + 600)
            val bWords = (bTotal + 63) / 64
            val B = LongArray(bWords)
            for (cue in cues) {
                val i0 = maxOf(0, ((cue.start * alpha) / ALIGN_BIN).toInt())
                val i1 = minOf(bTotal - 1, ((cue.end * alpha) / ALIGN_BIN).toInt())
                if (i0 > i1) continue
                val w0 = i0 shr 6
                val wEnd = i1 shr 6
                if (w0 == wEnd) {
                    B[w0] = B[w0] or maskRange(i0 and 63, (i1 and 63) + 1)
                } else {
                    B[w0] = B[w0] or maskRange(i0 and 63, 64)
                    for (k in w0 + 1 until wEnd) B[k] = -1L
                    B[wEnd] = B[wEnd] or maskRange(0, (i1 and 63) + 1)
                }
            }

            for (shift in lo..hi step 2) {
                shiftB(B, bWords, dest, words, shift)
                if (lastBits != 0) dest[words - 1] = dest[words - 1] and ((1L shl lastBits) - 1)
                var sB = 0
                for (k in 0 until words) sB += java.lang.Long.bitCount(dest[k])
                if (sB < 10 || sB > n - 10) continue

                var sAB = 0L
                for (p in 0 until 8) {
                    val pl = planes[p]
                    var c = 0
                    for (k in 0 until words) c += java.lang.Long.bitCount(pl[k] and dest[k])
                    if (c != 0) sAB += (1L shl p) * c
                }

                val num = n * (sAB.toDouble() / 255.0) - sumA * sB
                val varB = n.toDouble() * sB - (sB.toDouble() * sB)
                val den = sqrt(varA * varB)
                if (den < 1e-9) continue
                val r = num / den
                if (r > 0.05) {
                    allPeaks.add(Peak(alpha, shift, r))
                }
                if (r > globalBest.r) {
                    globalBest = Peak(alpha, shift, r)
                }
            }
        }

        if (globalBest.r <= 0.08) return null

        // Fine search around best: test step 1 (0.1s) and sub-bin parabola
        val bestAlpha = globalBest.alpha
        val lastCueSec = cues.last().end
        val bTotal = maxOf(n, (lastCueSec * bestAlpha / ALIGN_BIN).toInt() + 600)
        val bWords = (bTotal + 63) / 64
        val B = LongArray(bWords)
        for (cue in cues) {
            val i0 = maxOf(0, ((cue.start * bestAlpha) / ALIGN_BIN).toInt())
            val i1 = minOf(bTotal - 1, ((cue.end * bestAlpha) / ALIGN_BIN).toInt())
            if (i0 > i1) continue
            val w0 = i0 shr 6
            val wEnd = i1 shr 6
            if (w0 == wEnd) {
                B[w0] = B[w0] or maskRange(i0 and 63, (i1 and 63) + 1)
            } else {
                B[w0] = B[w0] or maskRange(i0 and 63, 64)
                for (k in w0 + 1 until wEnd) B[k] = -1L
                B[wEnd] = B[wEnd] or maskRange(0, (i1 and 63) + 1)
            }
        }

        fun evalShift(sh: Int): Double {
            shiftB(B, bWords, dest, words, sh)
            if (lastBits != 0) dest[words - 1] = dest[words - 1] and ((1L shl lastBits) - 1)
            var sB = 0
            for (k in 0 until words) sB += java.lang.Long.bitCount(dest[k])
            if (sB < 10 || sB > n - 10) return -2.0
            var sAB = 0L
            for (p in 0 until 8) {
                val pl = planes[p]
                var c = 0
                for (k in 0 until words) c += java.lang.Long.bitCount(pl[k] and dest[k])
                if (c != 0) sAB += (1L shl p) * c
            }
            val num = n * (sAB.toDouble() / 255.0) - sumA * sB
            val varB = n.toDouble() * sB - (sB.toDouble() * sB)
            val den = sqrt(varA * varB)
            return if (den < 1e-9) -2.0 else num / den
        }

        val fineShifts = (globalBest.shift - 5)..(globalBest.shift + 5)
        var fineBestShift = globalBest.shift
        var fineBestR = globalBest.r
        for (sh in fineShifts) {
            val r = evalShift(sh)
            if (r > fineBestR) {
                fineBestR = r
                fineBestShift = sh
            }
        }

        // Sub-bin parabolic peak interpolation
        val r0 = fineBestR
        val rm1 = evalShift(fineBestShift - 1)
        val rp1 = evalShift(fineBestShift + 1)
        var subShift = fineBestShift.toDouble()
        val denom = 2.0 * (rm1 - 2.0 * r0 + rp1)
        if (abs(denom) > 1e-6) {
            val delta = (rm1 - rp1) / denom
            if (abs(delta) <= 1.0) subShift += delta
        }

        // Prominence above second-best peak at distance >= 2.0s (20 bins)
        val secondBest = allPeaks
            .filter { abs(it.shift - fineBestShift) >= EXCLUSION_BINS }
            .maxOfOrNull { it.r } ?: -2.0
        val margin = if (secondBest <= -2.0) fineBestR else fineBestR - secondBest

        // Cross-half check on the winning trajectory
        val mid = n / 2
        fun evalHalf(startBin: Int, endBin: Int): Double {
            val span = endBin - startBin
            if (span < 100) return 0.0
            var sA_h = 0.0; var sA2_h = 0.0
            for (i in startBin until endBin) {
                val v = audio[i].toDouble()
                sA_h += v; sA2_h += v * v
            }
            val vA_h = span * sA2_h - sA_h * sA_h
            if (vA_h <= 1e-9) return 0.0

            var sB_h = 0.0; var sAB_h = 0.0
            for (i in startBin until endBin) {
                val subIdx = (i - fineBestShift)
                val bv = if (subIdx in 0 until bTotal) {
                    ((B[subIdx shr 6] ushr (subIdx and 63)) and 1L).toDouble()
                } else 0.0
                val av = audio[i].toDouble()
                sB_h += bv
                sAB_h += av * bv
            }
            val vB_h = span * sB_h - sB_h * sB_h
            val den_h = sqrt(vA_h * vB_h)
            return if (den_h < 1e-9) 0.0 else (span * sAB_h - sA_h * sB_h) / den_h
        }
        val r1 = evalHalf(0, mid)
        val r2 = evalHalf(mid, n)
        val halfOk = (r1 > 0.02 && r2 > 0.02 && minOf(r1, r2) >= 0.20 * maxOf(r1, r2))

        // Subtitle cue speech recall: fraction of cue intervals covering speech
        var cueHits = 0
        for (cue in cues) {
            val aStart = maxOf(0, (((cue.start * bestAlpha) / ALIGN_BIN) + fineBestShift).toInt())
            val aEnd = minOf(n - 1, (((cue.end * bestAlpha) / ALIGN_BIN) + fineBestShift).toInt())
            var hit = false
            for (i in aStart..aEnd) {
                if (audio[i] > 0.3f) { hit = true; break }
            }
            if (hit) cueHits++
        }
        val recall = cueHits.toDouble() / cues.size

        // Applied beta: audio_time = alpha * sub_time + beta
        // In shiftB: dest[i] = B[i + shift] => cue_bin = audio_bin + shift => audio_bin = cue_bin - shift
        // so beta = -subShift * ALIGN_BIN
        val betaSeconds = -subShift * ALIGN_BIN

        val lockable = fineBestR >= 0.10 && margin >= 0.03 && (halfOk || recall >= 0.35)
        return if (lockable) {
            JointResult(
                alpha = bestAlpha,
                beta = betaSeconds,
                score = fineBestR,
                margin = margin,
                recall = recall,
                halfOk = halfOk,
            )
        } else null
    }

    // ── word-array helpers ──────────────────────────────────────────────

    /** contiguous bit mask [from, to) within one 64-bit word */
    private fun maskRange(from: Int, to: Int): Long =
        if (to <= from) 0L else ((-1L shl from) ushr (64 - to)).let { it }

    /**
     * dest = B shifted so bit i of dest equals B[i + shift] (the sim's
     * `A & (B >> shift)` pairing convention). Caller masks the tail word.
     * [dest] must already be sized to cover the A window.
     */
    private fun shiftB(
        B: LongArray, bWords: Int, dest: LongArray, words: Int, shift: Int,
    ) {
        val wShift = if (shift >= 0) shift shr 6 else (-shift) shr 6
        val bShift = if (shift >= 0) shift and 63 else (-shift) and 63
        for (k in 0 until words) {
            dest[k] = if (shift >= 0) {
                // B >> shift : dest[k] draws src[k + wShift] (toward higher
                // indices because a cue bin j pairs with audio bin j - shift)
                val s1 = k + wShift
                var v = if (s1 < bWords) B[s1] ushr bShift else 0L
                if (bShift != 0) {
                    val s2 = s1 + 1
                    if (s2 < bWords) v = v or (B[s2] shl (64 - bShift))
                }
                v
            } else {
                // B << |shift|
                val s1 = k - wShift
                var v = if (s1 >= 0) B[s1] shl bShift else 0L
                if (bShift != 0) {
                    val s2 = s1 - 1
                    if (s2 >= 0) v = v or (B[s2] ushr (64 - bShift))
                }
                v
            }
        }
    }
}
