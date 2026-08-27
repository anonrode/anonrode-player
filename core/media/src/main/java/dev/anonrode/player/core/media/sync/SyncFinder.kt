package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.media.log.AppLog
import kotlin.math.abs
import kotlin.math.min

/**
 * Joint (alpha, beta) subtitle sync search — v2, ported from
 * tools/engine_test_v2.py and validated 3/3 on real Growling Tiger 2
 * audio (EP31: a=1.00894 b=+0.120 recall=59%; EP32: a=1.00944 b=-0.307
 * recall=70%; EP37: a=1.00892 b=-0.259 recall=56%; all chunks flat).
 *
 * Key differences from v1 (which self-matched subs against subs and never
 * consulted the audio):
 *  - scoring is cue->nearest-ONSET (audio is the truth), plus containment
 *    (onsets -> nearest cue) to kill periodic misalignments
 *  - coarse alpha grid step 0.001 (EP37's peak is sharp: 0.0005 off drops
 *    recall from 66% to 50%)
 *  - coarse->fine refinement, LSQ refit on matched pairs (sub-100ms)
 *  - gates: containment >= 0.18 (at 0.5s window), margin >= 0.04,
 *    cross-half consistency — refuse to lock garbage
 *
 * audio_time = (subtitle_time - beta) * alpha + beta
 */
object SyncFinder {

    data class Result(
        val alpha: Double,
        val beta: Double,
        val matchRate: Double,   // recall: fraction of cues near an onset
        val containment: Double, // fraction of onsets near a cue
        val margin: Double,      // recall minus best runner-up (>2s away)
        val pairs: Int,          // matched pairs used by the LSQ refit
        val crossHalfOk: Boolean,
        val chunkJumps: Int,     // >0 = timeline has cut(s), affine model unsafe
        val chunkBetas: List<Double>, // per-chunk median residual (piecewise correction)
    )

    private const val MAX_OFFSET = 60.0
    private const val MIN_RECALL = 0.25
    private const val MIN_CONTAINMENT = 0.18
    private const val MIN_MARGIN = 0.04
    private const val WINDOW = 0.5
    private const val WINDOW_FINE = 0.35

    fun find(onsets: List<Double>, subStarts: List<Double>): Result? {
        if (onsets.size < 20 || subStarts.size < 10) return null
        val sortedOnsets = onsets.sorted()
        val starts = subStarts.sorted()

        // 1. coarse grid: alpha step 0.001 across the FULL framerate range
        //    (0.95-1.05 catches 25fps-vs-23.976fps = 1.043, 24-vs-25 = 0.96),
        //    beta step 0.5s
        val top = ArrayList<Quad>()
        var a = 0.95
        while (a <= 1.05 + 1e-9) {
            var b = -MAX_OFFSET
            while (b <= MAX_OFFSET + 1e-9) {
                val rec = evaluate(sortedOnsets, starts, a, b, WINDOW)
                if (rec >= MIN_RECALL) top.add(Quad(rec, a, b))
                b += 0.5
            }
            a += 0.001
        }
        top.sortWith(compareByDescending { it.recall })
        val seeds = top.take(10)

        // 2. fine search around each seed (alpha 0.0005, beta 0.02s)
        var best: Quad? = null
        for (seed in seeds) {
            val aStart = maxOf(0.95, seed.alpha - 0.0025)
            val aEnd = minOf(1.05, seed.alpha + 0.0025)
            var af = aStart
            while (af <= aEnd + 1e-9) {
                val bStart = seed.beta - 0.5
                val bEnd = seed.beta + 0.5
                var bf = bStart
                while (bf <= bEnd + 1e-9) {
                    val rec = evaluate(sortedOnsets, starts, af, bf, WINDOW_FINE)
                    if (best == null || rec > best.recall) {
                        best = Quad(rec, af, bf)
                    }
                    bf += 0.02
                }
                af += 0.0005
            }
        }
        val winner = best ?: return null

        // containment gate at the WIDER window: fine window underestimates
        // coverage on cue-sparse episodes (EP37: 0.217 at 0.5s, ~0.19 at 0.35s)
        val contWide = containment(sortedOnsets, starts, winner.alpha, winner.beta, WINDOW)
        if (contWide < MIN_CONTAINMENT) return null

        // 3. LSQ refit on matched pairs (sub-100ms precision)
        var (alpha, beta, pairs) = lsqRefit(sortedOnsets, starts, winner.alpha, winner.beta)
        val recall = evaluate(sortedOnsets, starts, alpha, beta, WINDOW_FINE)

        // 4. margin vs best runner-up outside ±2s of beta
        var runner = 0.0
        var b = -MAX_OFFSET
        while (b <= MAX_OFFSET + 1e-9) {
            if (abs(b - beta) > 2.0) {
                val r = evaluate(sortedOnsets, starts, alpha, b, WINDOW_FINE)
                if (r > runner) runner = r
            }
            b += 0.5
        }
        val margin = recall - runner

        // 5. cross-half consistency
        val mid = (starts.first() + starts.last()) / 2.0
        val first = starts.filter { it < mid }
        val second = starts.filter { it >= mid }
        var crossHalfOk = true
        if (first.size >= 5 && second.size >= 5) {
            val (aF, bF, _) = lsqRefit(sortedOnsets, first, alpha, beta)
            val (aS, bS, _) = lsqRefit(sortedOnsets, second, alpha, beta)
            crossHalfOk = abs(bF - bS) <= 0.5 && abs(aF - aS) <= 0.005
        }

        // 6. piecewise chunk check (10 chunks, median+MAD residual per chunk)
        val (jumps, chunkBetas) = chunkAnalysis(sortedOnsets, starts, alpha, beta)

        val result = Result(
            alpha = alpha, beta = beta,
            matchRate = recall, containment = contWide,
            margin = margin, pairs = pairs,
            crossHalfOk = crossHalfOk, chunkJumps = jumps,
            chunkBetas = chunkBetas,
        )
        AppLog.d(
            "SYNC_FIND",
            "alpha=%.5f beta=%+.3f recall=%.2f cont=%.2f margin=%.2f pairs=%d crossHalf=%b jumps=%d"
                .format(alpha, beta, recall, contWide, margin, pairs, crossHalfOk, jumps)
        )
        return result
    }

    // ── scoring ──────────────────────────────────────────────────────
    /** Fraction of cue starts that have an audio onset within `window`. */
    private fun evaluate(onsets: List<Double>, cueStarts: List<Double>,
                         alpha: Double, beta: Double, window: Double): Double {
        if (onsets.isEmpty() || cueStarts.isEmpty()) return 0.0
        var hits = 0
        for (s in cueStarts) {
            val t = alpha * s + beta
            val k = lowerBound(onsets, t)
            if ((k > 0 && abs(onsets[k - 1] - t) <= window) ||
                (k < onsets.size && abs(onsets[k] - t) <= window)) {
                hits++
            }
        }
        return hits.toDouble() / cueStarts.size
    }

    /** Fraction of onsets that fall within `window` of a transformed cue. */
    private fun containment(onsets: List<Double>, cueStarts: List<Double>,
                            alpha: Double, beta: Double, window: Double): Double {
        if (onsets.isEmpty() || cueStarts.isEmpty()) return 0.0
        var contained = 0
        for (o in onsets) {
            val t = (o - beta) / alpha
            val k = lowerBound(cueStarts, t)
            if ((k > 0 && abs(alpha * cueStarts[k - 1] + beta - o) <= window) ||
                (k < cueStarts.size && abs(alpha * cueStarts[k] + beta - o) <= window)) {
                contained++
            }
        }
        return contained.toDouble() / onsets.size
    }

    // ── LSQ refit on matched pairs (two outlier-trimmed rounds) ──────
    private fun lsqRefit(onsets: List<Double>, cueStarts: List<Double>,
                         alpha0: Double, beta0: Double): Triple<Double, Double, Int> {
        var alpha = alpha0
        var beta = beta0
        var pairs = ArrayList<Pair<Double, Double>>()
        for (s in cueStarts) {
            val t = alpha * s + beta
            val k = lowerBound(onsets, t)
            if (k > 0 && abs(onsets[k - 1] - t) <= WINDOW_FINE) {
                pairs.add(s to onsets[k - 1])
            } else if (k < onsets.size && abs(onsets[k] - t) <= WINDOW_FINE) {
                pairs.add(s to onsets[k])
            }
        }
        if (pairs.size < 8) return Triple(alpha, beta, pairs.size)
        repeat(2) {
            val n = pairs.size
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
            for ((s, o) in pairs) {
                sx += s; sy += o; sxx += s * s; sxy += s * o
            }
            val denom = n * sxx - sx * sx
            if (abs(denom) < 1e-12) return@repeat
            val a = (n * sxy - sx * sy) / denom
            val b = (sy - a * sx) / n
            pairs = ArrayList(pairs.filter { abs(a * it.first + b - it.second) <= WINDOW_FINE })
            alpha = a; beta = b
        }
        return Triple(alpha, beta, pairs.size)
    }

    // ── piecewise check: per-chunk median residual, count jumps ──────
    /** Returns (jumpCount, perChunkMedianBetas). Jumps > 0 means the
     *  timeline has cuts the affine model cannot fix; the medians are
     *  the piecewise correction (apply beta_i to cues in chunk i).
     *
     *  10 equal subtitle-time chunks; cues are bucketed once by chunk
     *  index, then each cue's nearest-onset residual is found via
     *  binary search (O(N log N) total). A jump counts only when BOTH
     *  |Δβ| > 1.0s AND |ΔMAD| > 0.5s, so a clean drift with a slightly
     *  noisier chunk does not fire. Chunks with < 3 matches are skipped. */
    private fun chunkAnalysis(onsets: List<Double>, cueStarts: List<Double>,
                              alpha: Double, beta: Double,
                              nChunks: Int = 10): Pair<Int, List<Double>> {
        if (cueStarts.size < 10 || onsets.size < 20) return 0 to emptyList()
        val s0 = cueStarts.first()
        val span = cueStarts.last() - s0
        if (span <= 0.0) return 0 to emptyList()
        val bins = ArrayList<ArrayList<Double>>(nChunks)
        for (k in 0 until nChunks) bins.add(ArrayList())
        for (s in cueStarts) {
            val k = minOf(((s - s0) / span * nChunks).toInt(), nChunks - 1)
            bins[k].add(s)
        }
        val medians = ArrayList<Double>()
        val mads = ArrayList<Double>()
        for (k in 0 until nChunks) {
            val res = ArrayList<Double>()
            for (s in bins[k]) {
                val t = alpha * s + beta
                val i = lowerBound(onsets, t)
                var best: Double? = null
                for (candIdx in intArrayOf(i - 1, i)) {
                    if (candIdx in onsets.indices && abs(onsets[candIdx] - t) <= WINDOW) {
                        val cand = onsets[candIdx]
                        if (best == null || abs(cand - t) < abs(best - t)) best = cand
                    }
                }
                if (best != null) res.add(best - t)
            }
            if (res.size >= 3) {
                res.sort()
                val med = if (res.size % 2 == 1) res[res.size / 2]
                          else 0.5 * (res[res.size / 2 - 1] + res[res.size / 2])
                val dev = res.map { abs(it - med) }.sorted()
                medians.add(med)
                mads.add(dev[dev.size / 2])
            }
        }
        var jumps = 0
        for (i in 1 until medians.size) {
            if (abs(medians[i] - medians[i - 1]) > 1.0 &&
                abs(mads[i] - mads[i - 1]) > 0.5) jumps++
        }
        return jumps to medians
    }

    private fun lowerBound(arr: List<Double>, target: Double): Int {
        var lo = 0; var hi = arr.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (arr[mid] < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    data class Quad(val recall: Double, val alpha: Double, val beta: Double)

    // ── piecewise search for cut timelines ───────────────────────────
    // Onset-distance matching alone cannot FIND a cut: a removed scene
    // shifts the onset pattern, and cues can match "shifted copies" of
    // other scenes at distance 0. The discriminator is the residual
    // histogram: for the TRUE alpha, per-cue residuals d = onset − α·s
    // cluster into TWO modes (beta before the cut, beta after). The
    // nearest-onset residual is immune to copy-matching by construction.
    // Mirrors tools/engine_test_v2.py.
    data class PiecewiseResult(
        val alpha: Double,
        val cutAudioSec: Double,
        val betaBefore: Double,
        val betaAfter: Double,
        val shiftSec: Double,
        val recall: Double,
        val gain: Double,
    )

    private const val PW_WINDOW = 0.5
    private const val PW_MIN_RECALL = 0.40
    private const val PW_MIN_GAIN = 0.05
    private const val PW_MIN_MODE_SEP = 5.0

    fun findPiecewise(onsets: List<Double>, subStarts: List<Double>): PiecewiseResult? {
        if (onsets.size < 20 || subStarts.size < 10) return null
        val sortedOnsets = onsets.sorted()
        val starts = subStarts.sorted()
        val n = starts.size

        // residual of the nearest onset for every cue at a given alpha
        fun residuals(alpha: Double): DoubleArray {
            val ds = DoubleArray(n)
            for (i in 0 until n) {
                val t = alpha * starts[i]
                val k = lowerBound(sortedOnsets, t)
                var best = Double.MAX_VALUE
                if (k > 0) best = abs(sortedOnsets[k - 1] - t)
                if (k < sortedOnsets.size) {
                    val d2 = abs(sortedOnsets[k] - t)
                    if (d2 < best) best = d2
                }
                ds[i] = if (best == Double.MAX_VALUE) 0.0 else {
                    // signed residual (nearest side)
                    val kk = lowerBound(sortedOnsets, t)
                    val o = if (kk > 0 && (kk >= sortedOnsets.size ||
                        abs(sortedOnsets[kk - 1] - t) <= abs(sortedOnsets[kk] - t)))
                        sortedOnsets[kk - 1] else sortedOnsets[kk.coerceAtMost(sortedOnsets.size - 1)]
                    o - t
                }
            }
            return ds
        }

        // histogram + smoothing over beta in [-300, 60], 0.25s bins
        data class Modes(val frac2: Double, val frac1: Double,
                         val b1: Double, val b2: Double,
                         val assign: IntArray)

        fun twoModes(ds: DoubleArray): Modes {
            val lo = -300.0
            val step = 0.25
            val nb = ((60.0 - lo) / step).toInt() + 1
            val hist = IntArray(nb)
            for (d in ds) {
                val i = ((d - lo) / step).toInt()
                if (i in 0 until nb) hist[i]++
            }
            val smooth = IntArray(nb)
            for (i in 0 until nb) {
                var s = 0
                for (j in maxOf(0, i - 2)..minOf(nb - 1, i + 2)) s += hist[j]
                smooth[i] = s
            }
            fun peakBeta(exclude: Double?): Pair<Double, Int> {
                var bestI = 0
                var bestV = -1
                for (i in 0 until nb) {
                    if (exclude != null && abs(lo + i * step - exclude) < PW_MIN_MODE_SEP) continue
                    if (smooth[i] > bestV) { bestV = smooth[i]; bestI = i }
                }
                return (lo + bestI * step) to bestV
            }
            val (b1, _) = peakBeta(null)
            val (b2, _) = peakBeta(b1)
            if (abs(b2 - b1) < PW_MIN_MODE_SEP) {
                return Modes(0.0, 0.0, b1, b1, IntArray(n) { -1 })
            }
            val assign = IntArray(n)
            var c1 = 0
            var c2 = 0
            for (i in 0 until n) {
                val d1 = abs(ds[i] - b1)
                val d2 = abs(ds[i] - b2)
                if (minOf(d1, d2) <= PW_WINDOW) {
                    if (d1 <= d2) { assign[i] = 0; c1++ } else { assign[i] = 1; c2++ }
                } else assign[i] = -1
            }
            return Modes((c1 + c2).toDouble() / n, c1.toDouble() / n, b1, b2, assign)
        }

        // coarse alpha scan
        var bestFrac2 = -1.0
        var bestAlpha = 1.0
        var bestFrac1 = 0.0
        var bestB1 = 0.0
        var bestB2 = 0.0
        var bestAssign = IntArray(n)
        var a = 0.95
        while (a <= 1.05 + 1e-9) {
            val m = twoModes(residuals(a))
            if (m.frac2 > bestFrac2) {
                bestFrac2 = m.frac2; bestAlpha = a; bestFrac1 = m.frac1
                bestB1 = m.b1; bestB2 = m.b2; bestAssign = m.assign
            }
            a += 0.005
        }
        // fine alpha scan around the best
        var af = maxOf(0.95, bestAlpha - 0.003)
        val aEnd = minOf(1.05, bestAlpha + 0.003)
        while (af <= aEnd + 1e-9) {
            val m = twoModes(residuals(af))
            if (m.frac2 > bestFrac2) {
                bestFrac2 = m.frac2; bestAlpha = af; bestFrac1 = m.frac1
                bestB1 = m.b1; bestB2 = m.b2; bestAssign = m.assign
            }
            af += 0.001
        }

        val frac2 = bestFrac2
        val frac1 = bestFrac1
        val alpha = bestAlpha
        val b1 = bestB1
        val b2 = bestB2
        val assign = bestAssign
        if (frac2 < PW_MIN_RECALL || (frac2 - frac1) < PW_MIN_GAIN ||
            abs(b2 - b1) < PW_MIN_MODE_SEP) return null

        // ANCOVA refit on assigned pairs: o = α·s + β_mode
        // o − ō_m = α·(s − s̄_m) → α = ΣΣ(s−s̄)(o−ō) / ΣΣ(s−s̄)²
        var ra = alpha
        var rb1 = b1
        var rb2 = b2
        repeat(3) {
            var m1x = 0.0; var m1y = 0.0; var m1n = 0.0
            var m2x = 0.0; var m2y = 0.0; var m2n = 0.0
            for (i in 0 until n) {
                if (assign[i] < 0) continue
                val s = starts[i]
                val t = ra * s + (if (assign[i] == 0) rb1 else rb2)
                val k = lowerBound(sortedOnsets, t)
                var d = Double.MAX_VALUE
                var o = 0.0
                if (k > 0 && abs(sortedOnsets[k - 1] - t) < d) {
                    d = abs(sortedOnsets[k - 1] - t); o = sortedOnsets[k - 1]
                }
                if (k < sortedOnsets.size && abs(sortedOnsets[k] - t) < d) {
                    d = abs(sortedOnsets[k] - t); o = sortedOnsets[k]
                }
                if (d > PW_WINDOW) continue
                if (assign[i] == 0) { m1x += s; m1y += o; m1n += 1 }
                else { m2x += s; m2y += o; m2n += 1 }
            }
            if (m1n < 3 || m2n < 3) return@repeat
            val s1m = m1x / m1n; val o1m = m1y / m1n
            val s2m = m2x / m2n; val o2m = m2y / m2n
            var num = 0.0
            var den = 0.0
            for (i in 0 until n) {
                if (assign[i] < 0) continue
                val s = starts[i]
                val t = ra * s + (if (assign[i] == 0) rb1 else rb2)
                val k = lowerBound(sortedOnsets, t)
                var d = Double.MAX_VALUE
                var o = 0.0
                if (k > 0 && abs(sortedOnsets[k - 1] - t) < d) {
                    d = abs(sortedOnsets[k - 1] - t); o = sortedOnsets[k - 1]
                }
                if (k < sortedOnsets.size && abs(sortedOnsets[k] - t) < d) {
                    d = abs(sortedOnsets[k] - t); o = sortedOnsets[k]
                }
                if (d > PW_WINDOW) continue
                val sm = if (assign[i] == 0) s1m else s2m
                val om = if (assign[i] == 0) o1m else o2m
                num += (s - sm) * (o - om)
                den += (s - sm) * (s - sm)
            }
            if (abs(den) < 1e-9) return@repeat
            rb1 = o1m - ra * s1m
            rb2 = o2m - ra * s2m
            ra = num / den
        }

        // cut position: between last before-mode cue and first after-mode cue
        var lastBefore = -1
        var firstAfter = -1
        for (i in 0 until n) {
            if (assign[i] == 0) lastBefore = i
            if (assign[i] == 1 && firstAfter == -1) firstAfter = i
        }
        var cutSub: Double? = null
        if (lastBefore >= 0 && firstAfter >= 0 && lastBefore < firstAfter) {
            cutSub = (starts[lastBefore] + starts[firstAfter]) / 2.0
        } else {
            // inserted scene: flip the other way
            var lastAfter = -1
            var firstBefore = -1
            for (i in 0 until n) {
                if (assign[i] == 1) lastAfter = i
                if (assign[i] == 0 && firstBefore == -1) firstBefore = i
            }
            if (lastAfter >= 0 && firstBefore >= 0 && lastAfter < firstBefore) {
                cutSub = (starts[lastAfter] + starts[firstBefore]) / 2.0
            }
        }
        if (cutSub == null) return null

        // final recall with the refined model
        var hits = 0
        for (i in 0 until n) {
            if (assign[i] < 0) continue
            val t = ra * starts[i] + (if (assign[i] == 0) rb1 else rb2)
            val k = lowerBound(sortedOnsets, t)
            if ((k > 0 && abs(sortedOnsets[k - 1] - t) <= PW_WINDOW) ||
                (k < sortedOnsets.size && abs(sortedOnsets[k] - t) <= PW_WINDOW)) {
                hits++
            }
        }
        val recall = hits.toDouble() / n
        if (recall < PW_MIN_RECALL) return null

        return PiecewiseResult(
            alpha = ra,
            cutAudioSec = ra * cutSub + rb1,
            betaBefore = rb1,
            betaAfter = rb2,
            shiftSec = rb1 - rb2,
            recall = recall,
            gain = frac2 - frac1,
        )
    }

    /** Serialize segments for storage: "startSec:betaSec;startSec:betaSec".
     *  The renderer picks the last segment whose start <= playback time. */
    fun piecewiseToStorage(cutAudioSec: Double, betaBefore: Double, betaAfter: Double): String =
        "0.0:$betaBefore;$cutAudioSec:$betaAfter"
}
