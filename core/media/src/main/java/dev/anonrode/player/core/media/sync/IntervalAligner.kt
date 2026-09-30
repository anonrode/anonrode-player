package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.SubtitleCue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Modern interval-alignment subtitle synchronization engine modeled after alass and ffsubsync.
 *
 * Replaces heavy neural network VAD models and ad-hoc multi-gate heuristics with:
 *   1. 1D activity representation: compact onsets/intervals (< 10 KB in memory).
 *   2. Global alignment via 1D FFT cross-correlation in ~50-100ms.
 *   3. Parabolic sub-bin interpolation for millisecond precision.
 *   4. Framerate conversion ratio search across standard broadcast ratios.
 *   5. Piecewise commercial cut detection.
 */
object IntervalAligner {

    private const val TAG = "INTERVAL_ALIGN"
    const val SAMPLE_RATE = 50 // 50 Hz = 20ms bins
    private const val WINDOW_SEC = 0.40 // 400ms matching window
    private const val MIN_RECALL = 0.25
    private const val MIN_MARGIN = 0.04

    data class Alignment(
        val alpha: Double,
        val beta: Double,
        val recall: Double,
        val margin: Double,
        val zScore: Double,
        val hits: Int,
        val totalCues: Int,
        val elapsedMs: Long,
        val piecewise: String = "",
        val cutSubSec: Double = 0.0,
        val cutAudioSec: Double = 0.0,
        val betaBefore: Double = beta,
        val betaAfter: Double = beta,
        val hasSplit: Boolean = false,
        val secondPeakLag: Double = 0.0,
        val secondPeakHits: Int = 0,
    )

    /**
     * Top-level alignment: runs FFT cross-correlation across onsets and subtitle cues.
     */
    fun align(
        onsets: List<Double>,
        cues: List<SubtitleCue>,
        maxOffsetSec: Double = 120.0,
    ): Alignment? = align(onsets, cues.map { it.start }, maxOffsetSec)

    /**
     * Top-level alignment: runs FFT cross-correlation across onsets and cue starts.
     * Evaluates nominal 1.0 framerate first, then standard broadcast conversion ratios if needed.
     */
    fun align(
        onsets: List<Double>,
        cueStarts: List<Double>,
        maxOffsetSec: Double = 120.0,
    ): Alignment? {
        if (onsets.size < 20 || cueStarts.size < 10) return null
        val t0 = System.currentTimeMillis()

        // 1. Try nominal 1.0 ratio first
        val nom = correlate(onsets, cueStarts, alpha = 1.0, maxOffsetSec = maxOffsetSec)
        if (nom != null && nom.margin >= 0.15 && nom.recall >= 0.85) {
            val elapsed = System.currentTimeMillis() - t0
            AppLog.d(TAG, "nominal lock: alpha=1.0 beta=${nom.beta}s margin=${nom.margin} recall=${nom.recall} in ${elapsed}ms")
            return nom.copy(elapsedMs = elapsed)
        }

        // 2. Search standard broadcast conversion ratios
        val candidateRatios = doubleArrayOf(
            1.0,
            25.0 / 23.976, // 1.042709 PAL -> Film
            23.976 / 25.0, // 0.959040 Film -> PAL
            25.0 / 24.0,   // 1.041667
            24.0 / 25.0,   // 0.960000
            24.0 / 23.976, // 1.001001
            1.00894,       // Web broadcast standard
        )

        var best: Alignment? = nom
        for (ratio in candidateRatios) {
            if (ratio == 1.0) continue
            val cand = correlate(onsets, cueStarts, alpha = ratio, maxOffsetSec = maxOffsetSec) ?: continue
            if (best == null || cand.recall > best.recall) {
                best = cand
            }
        }

        val elapsed = System.currentTimeMillis() - t0
        if (best != null && best.margin >= MIN_MARGIN && best.recall >= MIN_RECALL) {
            AppLog.d(TAG, "ratio lock: alpha=${best.alpha} beta=${best.beta}s margin=${best.margin} recall=${best.recall} in ${elapsed}ms")
            return best.copy(elapsedMs = elapsed)
        }

        return null
    }

    /**
     * Top-level piecewise alignment: detects commercial cuts and multi-segment offsets
     * using Dynamic Programming over temporal windows.
     */
    fun alignPiecewise(
        onsets: List<Double>,
        cues: List<SubtitleCue>,
        maxOffsetSec: Double = 120.0,
        windowSec: Double = 300.0,
        stepSec: Double = 150.0,
        splitPenalty: Double = 12.0,
    ): Alignment? = alignPiecewise(onsets, cues.map { it.start }, maxOffsetSec, windowSec, stepSec, splitPenalty)

    fun alignPiecewise(
        onsets: List<Double>,
        cueStarts: List<Double>,
        maxOffsetSec: Double = 120.0,
        windowSec: Double = 300.0,
        stepSec: Double = 150.0,
        splitPenalty: Double = 12.0,
    ): Alignment? {
        if (onsets.size < 20 || cueStarts.size < 10) return null
        val t0 = System.currentTimeMillis()

        // 1. Global alignment first (fallback to nominal if split peaks reduced margin)
        val nom = correlate(onsets, cueStarts, alpha = 1.0, maxOffsetSec = maxOffsetSec)
        val global = align(onsets, cueStarts, maxOffsetSec) ?: nom ?: return null
        if (global.recall < 0.20) return null

        // 2. High-precision exact prefix-sum cut evaluation across global candidate peaks (handles short cold opens)
        val beta1 = global.beta
        val beta2 = global.secondPeakLag
        if (global.secondPeakHits >= 3 && abs(beta1 - beta2) >= 1.0) {
            val exactCut = evaluatePrefixSumCut(onsets, cueStarts, global.alpha, beta1, beta2)
            if (exactCut != null && exactCut.gain >= 0.05) {
                val elapsed = System.currentTimeMillis() - t0
                val pw = "0.0:${exactCut.betaBefore};${exactCut.cutAudioSec}:${exactCut.betaAfter}"
                AppLog.d(TAG, "exact prefix-sum cut locked: before=${exactCut.betaBefore}s after=${exactCut.betaAfter}s cutAudio=${exactCut.cutAudioSec}s recall=${exactCut.recallTwo} gain=${exactCut.gain} in ${elapsed}ms")
                return global.copy(
                    piecewise = pw,
                    cutSubSec = exactCut.cutSubSec,
                    cutAudioSec = exactCut.cutAudioSec,
                    betaBefore = exactCut.betaBefore,
                    betaAfter = exactCut.betaAfter,
                    recall = exactCut.recallTwo,
                    hasSplit = true,
                    elapsedMs = elapsed,
                )
            }
        }

        val span = cueStarts.last() - cueStarts.first()
        if (span < 600.0) return global // Content too short for multi-window commercial cuts

        // 3. Multi-window Dynamic Programming fallback for complex multi-break content
        data class WinRes(val wStart: Double, val wEnd: Double, val bestOffset: Double, val score: Float, val count: Int)
        val winResults = mutableListOf<WinRes>()
        val candidateOffsets = mutableSetOf<Double>()
        candidateOffsets.add((global.beta * 10).roundToInt() / 10.0)

        var wStart = cueStarts.first()
        val lastCue = cueStarts.last()
        while (wStart < lastCue) {
            val wEnd = min(wStart + windowSec, lastCue + 1.0)
            val cuesW = cueStarts.filter { it in wStart until wEnd }
            if (cuesW.size >= 8) {
                val onsetsW = onsets.filter { it >= wStart - maxOffsetSec && it <= wEnd + maxOffsetSec }
                if (onsetsW.size >= 10) {
                    val wCorr = correlate(onsetsW, cuesW, alpha = global.alpha, maxOffsetSec = maxOffsetSec)
                    if (wCorr != null && wCorr.margin >= 0.04) {
                        winResults.add(WinRes(wStart, wEnd, wCorr.beta, wCorr.hits.toFloat(), cuesW.size))
                        candidateOffsets.add((wCorr.beta * 10).roundToInt() / 10.0)
                    }
                }
            }
            wStart += stepSec
        }

        if (winResults.size < 3 || candidateOffsets.size < 2) {
            return global
        }

        val sortedCands = candidateOffsets.sorted()
        val nW = winResults.size
        val nC = sortedCands.size

        // DP[w][c]: best cumulative score up to window w selecting candidate c
        val dp = Array(nW) { DoubleArray(nC) { -1e9 } }
        val backtrack = Array(nW) { IntArray(nC) }

        for (c in 0 until nC) {
            val cand = sortedCands[c]
            val w0 = winResults[0]
            val match = if (abs(cand - w0.bestOffset) <= 0.3) w0.score.toDouble() else 0.0
            dp[0][c] = match
        }

        for (w in 1 until nW) {
            val wr = winResults[w]
            for (c in 0 until nC) {
                val cand = sortedCands[c]
                val localScore = if (abs(cand - wr.bestOffset) <= 0.3) wr.score.toDouble() else 0.0
                var bestPrev = -1e9
                var bestPrevIdx = 0
                for (prev in 0 until nC) {
                    val prevCand = sortedCands[prev]
                    val penalty = if (abs(prevCand - cand) < 0.25) 0.0 else (splitPenalty * wr.score * 0.15)
                    val v = dp[w - 1][prev] - penalty
                    if (v > bestPrev) {
                        bestPrev = v
                        bestPrevIdx = prev
                    }
                }
                dp[w][c] = bestPrev + localScore
                backtrack[w][c] = bestPrevIdx
            }
        }

        var bestEndIdx = 0
        var bestEndVal = -1e9
        for (c in 0 until nC) {
            if (dp[nW - 1][c] > bestEndVal) {
                bestEndVal = dp[nW - 1][c]
                bestEndIdx = c
            }
        }

        val path = IntArray(nW)
        path[nW - 1] = bestEndIdx
        for (w in nW - 1 downTo 1) {
            path[w - 1] = backtrack[w][path[w]]
        }

        // Check if there is a cut transition
        var cutWindow = -1
        val firstCand = sortedCands[path[0]]
        for (w in 1 until nW) {
            if (abs(sortedCands[path[w]] - firstCand) >= 0.5) {
                cutWindow = w
                break
            }
        }

        if (cutWindow < 0) {
            return global
        }

        val betaBefore = firstCand
        val betaAfter = sortedCands[path[cutWindow]]
        val cutSubSec = winResults[cutWindow].wStart
        val cutAudioSec = (cutSubSec + betaBefore).coerceAtLeast(0.0)

        // Evaluate two-piece recall
        var hitsBefore = 0
        var hitsAfter = 0
        val tol = 0.35
        for (c in cueStarts) {
            val expectedAudio = if (c < cutSubSec) (c * global.alpha + betaBefore) else (c * global.alpha + betaAfter)
            val idx = onsets.binarySearch(expectedAudio)
            val insertion = if (idx >= 0) idx else -idx - 1
            var hit = false
            for (k in max(0, insertion - 2)..min(onsets.size - 1, insertion + 2)) {
                if (abs(onsets[k] - expectedAudio) <= tol) {
                    hit = true; break
                }
            }
            if (hit) {
                if (c < cutSubSec) hitsBefore++ else hitsAfter++
            }
        }
        val recallTwo = (hitsBefore + hitsAfter).toDouble() / cueStarts.size

        if (recallTwo >= global.recall + 0.05) {
            val pw = "0.0:$betaBefore;$cutAudioSec:$betaAfter"
            val elapsed = System.currentTimeMillis() - t0
            AppLog.d(TAG, "piecewise cut locked: before=${betaBefore}s after=${betaAfter}s cutAudio=${cutAudioSec}s recall=${recallTwo} in ${elapsed}ms")
            return global.copy(
                piecewise = pw,
                cutSubSec = cutSubSec,
                cutAudioSec = cutAudioSec,
                betaBefore = betaBefore,
                betaAfter = betaAfter,
                recall = recallTwo,
                hasSplit = true,
                elapsedMs = elapsed,
            )
        }

        return global
    }

    /**
     * Computes 1D FFT cross-correlation between audio onsets and scaled subtitle cue starts.
     */
    fun correlate(
        onsets: List<Double>,
        cueStarts: List<Double>,
        alpha: Double = 1.0,
        sampleRate: Int = SAMPLE_RATE,
        maxOffsetSec: Double = 120.0,
    ): Alignment? {
        val nCues = cueStarts.size
        if (nCues == 0 || onsets.isEmpty()) return null

        val maxOnset = onsets.maxOrNull() ?: 0.0
        val maxSub = (cueStarts.maxOrNull() ?: 0.0) * alpha
        val maxTime = max(maxOnset, maxSub) + maxOffsetSec + 10.0
        val nBins = ceil(maxTime * sampleRate).toInt() + 1

        // 1. Discretize onsets with triangular tent window smoothing
        val oArr = FloatArray(nBins)
        val winBins = (WINDOW_SEC * sampleRate).roundToInt().coerceAtLeast(1)
        val halfWin = winBins / 2
        for (o in onsets) {
            val center = (o * sampleRate).roundToInt()
            val startIdx = max(0, center - halfWin)
            val endIdx = min(nBins - 1, center + halfWin)
            for (idx in startIdx..endIdx) {
                val w = max(0f, 1f - abs(idx - center).toFloat() / halfWin.toFloat())
                if (w > oArr[idx]) {
                    oArr[idx] = w
                }
            }
        }

        // 2. Discretize scaled subtitle cue starts with narrow tent window
        val sArr = FloatArray(nBins)
        val subHalf = max(1, halfWin / 2)
        for (s in cueStarts) {
            val center = (s * alpha * sampleRate).roundToInt()
            val startIdx = max(0, center - subHalf)
            val endIdx = min(nBins - 1, center + subHalf)
            for (idx in startIdx..endIdx) {
                val w = max(0f, 1f - abs(idx - center).toFloat() / subHalf.toFloat())
                if (w > sArr[idx]) {
                    sArr[idx] = w
                }
            }
        }

        // 3. Next power of 2 for FFT
        val minLen = nBins * 2
        var fftLen = 1
        while (fftLen < minLen) {
            fftLen = fftLen shl 1
        }

        val realO = FloatArray(fftLen)
        val imagO = FloatArray(fftLen)
        System.arraycopy(oArr, 0, realO, 0, nBins)

        val realS = FloatArray(fftLen)
        val imagS = FloatArray(fftLen)
        System.arraycopy(sArr, 0, realS, 0, nBins)

        // Forward FFTs
        fft(realO, imagO, invert = false)
        fft(realS, imagS, invert = false)

        // Pointwise multiplication: O * conj(S)
        val realCorr = FloatArray(fftLen)
        val imagCorr = FloatArray(fftLen)
        for (i in 0 until fftLen) {
            val ro = realO[i]; val io = imagO[i]
            val rs = realS[i]; val is_ = imagS[i]
            // (ro + j*io) * (rs - j*is) = (ro*rs + io*is) + j*(io*rs - ro*is)
            realCorr[i] = ro * rs + io * is_
            imagCorr[i] = io * rs - ro * is_
        }

        // Inverse FFT
        fft(realCorr, imagCorr, invert = true)

        // 4. Slice lags in [-maxOffsetSec, +maxOffsetSec]
        val maxLagBins = (maxOffsetSec * sampleRate).roundToInt()
        val numLags = maxLagBins * 2 + 1
        val slicedCorr = FloatArray(numLags)
        val lags = DoubleArray(numLags)

        for (i in 0 until numLags) {
            val lagBin = -maxLagBins + i
            lags[i] = lagBin.toDouble() / sampleRate
            val circIdx = if (lagBin < 0) fftLen + lagBin else lagBin
            slicedCorr[i] = realCorr[circIdx]
        }

        // 5. Peak picking
        var bestIdx = 0
        var bestVal = -1f
        for (i in 0 until numLags) {
            if (slicedCorr[i] > bestVal) {
                bestVal = slicedCorr[i]
                bestIdx = i
            }
        }

        val bestLag = lags[bestIdx]

        // Normalize hits by single-cue tent convolution peak
        var singleCueNorm = 1.0
        for (k in 1..subHalf) {
            singleCueNorm += 2.0 * (1.0 - k.toDouble() / halfWin) * (1.0 - k.toDouble() / subHalf)
        }
        val hits = (bestVal / singleCueNorm).roundToInt()

        // Parabolic sub-bin interpolation
        var refinedLag = bestLag
        if (bestIdx > 0 && bestIdx < numLags - 1) {
            val y0 = slicedCorr[bestIdx - 1]
            val y1 = slicedCorr[bestIdx]
            val y2 = slicedCorr[bestIdx + 1]
            val denom = 2.0 * (2.0 * y1 - y0 - y2)
            if (abs(denom) > 1e-6) {
                val deltaBin = (y2 - y0) / denom
                refinedLag += deltaBin / sampleRate
            }
        }

        // Second peak outside 1.5s window
        val deadbandBins = (1.5 * sampleRate).roundToInt()
        var secondPeakVal = 0f
        var secondPeakIdx = -1
        for (i in 0 until numLags) {
            if (abs(i - bestIdx) > deadbandBins && slicedCorr[i] > secondPeakVal) {
                secondPeakVal = slicedCorr[i]
                secondPeakIdx = i
            }
        }

        val secondPeakLag = if (secondPeakIdx >= 0) lags[secondPeakIdx] else 0.0
        val secondPeakHits = if (secondPeakIdx >= 0) (secondPeakVal / singleCueNorm).roundToInt() else 0

        val margin = (bestVal - secondPeakVal) / max(1f, bestVal)
        val recall = min(1.0, hits.toDouble() / nCues)

        // Median and MAD for Z-score
        val sortedVals = slicedCorr.clone().apply { sort() }
        val median = sortedVals[sortedVals.size / 2]
        val absDevs = FloatArray(sortedVals.size) { abs(sortedVals[it] - median) }.apply { sort() }
        val mad = absDevs[absDevs.size / 2].coerceAtLeast(1e-4f)
        val zScore = (bestVal - median) / (1.4826f * mad)

        return Alignment(
            alpha = alpha,
            beta = (refinedLag * 1000.0).roundToInt() / 1000.0,
            recall = recall,
            margin = margin.toDouble(),
            zScore = zScore.toDouble(),
            hits = hits,
            totalCues = nCues,
            elapsedMs = 0L,
            secondPeakLag = (secondPeakLag * 1000.0).roundToInt() / 1000.0,
            secondPeakHits = secondPeakHits,
        )
    }

    /**
     * In-place Cooley-Tukey Radix-2 FFT.
     * Length of real and imag must be a power of 2.
     */
    private fun fft(real: FloatArray, imag: FloatArray, invert: Boolean) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = (if (invert) -2.0 else 2.0) * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + halfLen] = uR - vR
                    imag[i + k + halfLen] = uI - vI
                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }

        if (invert) {
            val invN = 1f / n
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }

    data class ExactCut(
        val betaBefore: Double,
        val betaAfter: Double,
        val cutSubSec: Double,
        val cutAudioSec: Double,
        val recallOne: Double,
        val recallTwo: Double,
        val gain: Double,
        val cutCueIdx: Int,
    )

    private fun evaluatePrefixSumCut(
        onsets: List<Double>,
        cueStarts: List<Double>,
        alpha: Double,
        beta1: Double,
        beta2: Double,
        tol: Double = 0.35,
    ): ExactCut? {
        val n = cueStarts.size
        if (n < 8) return null

        fun isMatch(c: Double, beta: Double): Int {
            val expectedAudio = c * alpha + beta
            val idx = onsets.binarySearch(expectedAudio)
            val ins = if (idx >= 0) idx else -idx - 1
            for (k in maxOf(0, ins - 2)..minOf(onsets.size - 1, ins + 2)) {
                if (abs(onsets[k] - expectedAudio) <= tol) return 1
            }
            return 0
        }

        val m1 = IntArray(n) { isMatch(cueStarts[it], beta1) }
        val m2 = IntArray(n) { isMatch(cueStarts[it], beta2) }
        val hits1 = m1.sum()
        val hits2 = m2.sum()
        val singleRecall = maxOf(hits1, hits2).toDouble() / n

        // Prefix sums for beta1 -> beta2
        val cum1 = IntArray(n + 1)
        for (i in 0 until n) cum1[i + 1] = cum1[i] + m1[i]
        val cum2 = IntArray(n + 1)
        for (i in n - 1 downTo 0) cum2[i] = cum2[i + 1] + m2[i]

        var bestScore12 = -1
        var bestK12 = -1
        for (k in 1 until n) {
            val score = cum1[k] + cum2[k]
            if (score > bestScore12) {
                bestScore12 = score
                bestK12 = k
            }
        }

        // Prefix sums for beta2 -> beta1 (opposite cut direction)
        val cum2Fwd = IntArray(n + 1)
        for (i in 0 until n) cum2Fwd[i + 1] = cum2Fwd[i] + m2[i]
        val cum1Rev = IntArray(n + 1)
        for (i in n - 1 downTo 0) cum1Rev[i] = cum1Rev[i + 1] + m1[i]

        var bestScore21 = -1
        var bestK21 = -1
        for (k in 1 until n) {
            val score = cum2Fwd[k] + cum1Rev[k]
            if (score > bestScore21) {
                bestScore21 = score
                bestK21 = k
            }
        }

        val bestScore: Int
        val bestK: Int
        val bBefore: Double
        val bAfter: Double
        if (bestScore12 >= bestScore21) {
            bestScore = bestScore12
            bestK = bestK12
            bBefore = beta1
            bAfter = beta2
        } else {
            bestScore = bestScore21
            bestK = bestK21
            bBefore = beta2
            bAfter = beta1
        }

        val recallTwo = bestScore.toDouble() / n
        val gain = recallTwo - singleRecall

        val cutSubSec = cueStarts[bestK]
        val cutAudioSec = maxOf(0.0, cutSubSec * alpha + bBefore)
        return ExactCut(
            betaBefore = bBefore,
            betaAfter = bAfter,
            cutSubSec = cutSubSec,
            cutAudioSec = (cutAudioSec * 10.0).roundToInt() / 10.0,
            recallOne = singleRecall,
            recallTwo = recallTwo,
            gain = gain,
            cutCueIdx = bestK,
        )
    }
}
