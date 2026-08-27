package dev.anonrode.player.core.media.sync

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Consolidated single-segment fitter — port of engine_best.find_sync_best.
 *
 * Model: audio_time = alpha * subtitle_time + beta, fitted against audio
 * speech onsets and subtitle cue starts. Refuse-don't-guess: every emitted
 * lock passes the v2 confidence gates (containment >= 0.18 @0.5 s,
 * recall-margin >= 0.04, cross-half); otherwise null.
 *
 * Phases (engine_best.py):
 *   0. plain reference fitter            <- tools/engine_test_v2.py
 *   1. ROBUST SHORT-CIRCUIT: a confident plain lock (margin >= 0.06,
 *      cross-half OK, AND trimmed-score peak check against false
 *      attractors) is returned as-is.
 *   2. wide acquisition (beta in [-600, +600]) + length-ratio alpha seed;
 *      a confident wide lock with better recall than plain wins
 *                                          <- tools/_fix3a_wide.py
 *   3. the 6-phase outlier-robust pipeline seeded with plain + wide +
 *      ratio candidates                    <- tools/_fix3b_robust.py
 *   4. fallback: gated wide, then gated plain; else null (refuse).
 *
 * Porting notes (semantics preserved):
 *   - evaluate()'s bisect-neighbor hit test (neighbors k-1/k of the
 *     insertion point) is equivalent to min-distance to the nearest
 *     onset (see _fix3b_common.evaluate_fast); the hot loops use a
 *     monotone two-pointer nearest scan instead of per-point binary
 *     search — same distances, much faster on-device.
 *   - containment is computed only where the Python decision path uses
 *     it (plain/wide fine grids rank by recall only).
 *   - np.argsort tie order is unspecified; the stable Kotlin sort keeps
 *     row-major order for equal recalls.
 */
object SyncBest {

    data class Result(
        val alpha: Double,
        val beta: Double,
        val recall: Double,
        val containment: Double,
        val margin: Double,
        val pairs: Int,
        val halfOk: Boolean,
        val guard: Double,
        val path: String, // shortcircuit|wide|pipeline|wide-fallback|plain-fallback
    )

    // ── constants (engine_best.py / engine_test_v2.py) ──────────────────
    private const val W_COARSE = 0.5
    private const val W_FINE = 0.35
    private const val MAX_OFF = 60.0 // plain/v2 beta search radius
    private const val CONT_GATE = 0.18
    private const val MARGIN_GATE = 0.04
    private const val SHORT_CIRCUIT_MARGIN = 0.06
    private const val WIDE_OFF = 600.0 // wide beta search radius
    private const val HIST_BIN = 0.5 // wide coarse histogram bin
    private const val GUARD_BAND = 1.0 // ffsubsync borrow (a)
    private const val TRIM = 0.40 // trimmed score: drop worst 40% of cues
    private const val N_COARSE_KEEP = 30
    private const val N_FINE_SEEDS = 8

    private data class PlainResult(
        val alpha: Double,
        val beta: Double,
        val recall: Double,
        val containment: Double,
        val margin: Double,
        val pairs: Int,
        val halfOk: Boolean,
        val guard: Double = 0.0,
    )

    // ====================================================================
    // public entry — engine_best.find_sync_best
    // ====================================================================

    fun find(onsets: List<Double>, cueStarts: List<Double>): Result? {
        if (onsets.size < 20 || cueStarts.size < 10) return null
        val on = onsets.sorted().toDoubleArray()
        val cs = cueStarts.sorted().toDoubleArray()

        // Phase 0: plain reference fitter (exact find_sync_v2 semantics).
        val r0 = findSyncV2(on, cs)

        // Phase 1: robust short-circuit, hardened with the trimmed-peak
        // check so false attractors (margin 0.07-0.10 but ~0.7 s off under
        // heavy garbage) cannot ride the fast path.
        if (r0 != null && r0.margin >= SHORT_CIRCUIT_MARGIN && r0.halfOk &&
            trimmedPeakOk(on, cs, r0.alpha, r0.beta)
        ) {
            return r0.toResult(guardScore(on, cs, r0.alpha, r0.beta), "shortcircuit")
        }

        // Phase 2: wide acquisition (fix-3a) + ratio seed.
        val rWide = findWideBest(on, cs)
        if (rWide != null && rWide.margin >= SHORT_CIRCUIT_MARGIN && rWide.halfOk &&
            (r0 == null || rWide.recall > r0.recall)
        ) {
            return rWide.toResult(rWide.guard, "wide")
        }

        // Phase 3: robust pipeline with plain/wide/ratio seeds.
        val extra = ArrayList<DoubleArray>()
        if (rWide != null) extra.add(doubleArrayOf(rWide.alpha, rWide.beta))
        val seed = ratioAlphaSeed(on, cs)
        if (seed != null) extra.add(doubleArrayOf(seed.first, seed.second))
        val r = robustPipeline(on, cs, r0, extra)
        if (r != null) return r.toResult(r.guard, "pipeline")

        // Phase 4: fallbacks — every emitted lock still passes all gates.
        if (rWide != null) return rWide.toResult(rWide.guard, "wide-fallback")
        if (r0 != null && r0.margin >= MARGIN_GATE && r0.halfOk) {
            return r0.toResult(guardScore(on, cs, r0.alpha, r0.beta), "plain-fallback")
        }
        return null
    }

    private fun PlainResult.toResult(guard: Double, path: String) = Result(
        alpha = alpha, beta = beta, recall = recall, containment = containment,
        margin = margin, pairs = pairs, halfOk = halfOk, guard = guard, path = path,
    )

    // ====================================================================
    // scoring primitives (engine_test_v2.evaluate semantics)
    // ====================================================================

    private fun lowerBound(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] >= x) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun upperBound(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] > x) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.copyOf().also { it.sort() }
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    /**
     * Cue-side recall: fraction of cues with some onset within `window`
     * of alpha*s + beta. t = alpha*cs + beta is ascending, so the nearest
     * onset is tracked with a monotone pointer (equivalent min-distance).
     */
    private fun recallAt(on: DoubleArray, cs: DoubleArray, alpha: Double, beta: Double, window: Double): Double {
        if (on.isEmpty() || cs.isEmpty()) return 0.0
        val nOn = on.size
        var hits = 0
        var p = 0
        for (i in cs.indices) {
            val t = alpha * cs[i] + beta
            while (p + 1 < nOn && abs(on[p + 1] - t) <= abs(on[p] - t)) p++
            if (abs(on[p] - t) <= window) hits++
        }
        return hits.toDouble() / cs.size
    }

    /**
     * Onset-side containment: fraction of onsets with some cue within
     * `window` of the predicted cue time (o - beta)/alpha.
     */
    private fun containmentAt(on: DoubleArray, cs: DoubleArray, alpha: Double, beta: Double, window: Double): Double {
        if (on.isEmpty() || cs.isEmpty()) return 0.0
        val nCs = cs.size
        var contained = 0
        var q = 0
        for (i in on.indices) {
            val o = on[i]
            while (q + 1 < nCs) {
                val dCur = abs(alpha * cs[q] + beta - o)
                val dNext = abs(alpha * cs[q + 1] + beta - o)
                if (dNext <= dCur) q++ else break
            }
            if (abs(alpha * cs[q] + beta - o) <= window) contained++
        }
        return contained.toDouble() / on.size
    }

    // ====================================================================
    // LSQ refit (engine_test_v2.lsq_fit)
    // ====================================================================

    private class LsqResult(val alpha: Double, val beta: Double, val pairs: Int)

    /**
     * Two plain-style LSQ rounds with window pruning. Pair matching
     * prefers the LEFT neighbor when it is within the window (exact
     * lsq_fit semantics, not min-distance).
     */
    private fun lsqFit(on: DoubleArray, cs: DoubleArray, alpha0: Double, beta0: Double, window: Double): LsqResult {
        var pairs = ArrayList<DoubleArray>()
        val m = on.size
        for (s in cs) {
            val t = alpha0 * s + beta0
            val k = lowerBound(on, t)
            if (k > 0 && abs(on[k - 1] - t) <= window) {
                pairs.add(doubleArrayOf(s, on[k - 1]))
            } else if (k < m && abs(on[k] - t) <= window) {
                pairs.add(doubleArrayOf(s, on[k]))
            }
        }
        if (pairs.size < 8) return LsqResult(alpha0, beta0, pairs.size)
        var alpha = alpha0
        var beta = beta0
        for (r in 0 until 2) {
            val n = pairs.size
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for (p in pairs) {
                sx += p[0]
                sy += p[1]
                sxx += p[0] * p[0]
                sxy += p[0] * p[1]
            }
            val d = n * sxx - sx * sx
            if (abs(d) < 1e-12) break
            val a = (n * sxy - sx * sy) / d
            val b = (sy - a * sx) / n
            pairs = ArrayList(pairs.filter { abs(a * it[0] + b - it[1]) <= window })
            alpha = a
            beta = b
        }
        return LsqResult(alpha, beta, pairs.size)
    }

    // ====================================================================
    // plain reference fitter (engine_test_v2.find_sync_v2)
    // ====================================================================

    private fun findSyncV2(on: DoubleArray, cs: DoubleArray): PlainResult? {
        if (on.size < 20 || cs.size < 10) return null

        // 1. coarse grid: alpha step 0.001 over [0.95, 1.05], beta step
        //    0.5 s over [-60, +60]; keep the top-10 by recall (>= 0.25).
        val top = ArrayList<DoubleArray>() // (rec, alpha, beta)
        for (a1000 in 950..1050) {
            val alpha = a1000 / 1000.0
            for (b10 in -600..600 step 5) {
                val beta = b10 / 10.0
                val rec = recallAt(on, cs, alpha, beta, W_COARSE)
                if (rec >= 0.25) top.add(doubleArrayOf(rec, alpha, beta))
            }
        }
        top.sortByDescending { it[0] }
        val topN = if (top.size > 10) top.subList(0, 10) else top

        // 2. fine search around each candidate (alpha 0.0005, beta 0.02)
        var best: DoubleArray? = null // (rec, alpha, beta)
        for (cand in topN) {
            val a0 = cand[1]
            val b0 = cand[2]
            val aStart = max(0.95, a0 - 0.0025)
            val aEnd = min(1.05, a0 + 0.0025)
            for (a10 in (aStart * 2000).toInt()..(aEnd * 2000).toInt()) {
                val alpha = a10 / 2000.0
                for (b100 in ((b0 - 0.5) * 100).toInt()..((b0 + 0.5) * 100).toInt() step 2) {
                    val beta = b100 / 100.0
                    val rec = recallAt(on, cs, alpha, beta, W_FINE)
                    val cur = best
                    if (cur == null || rec > cur[0]) best = doubleArrayOf(rec, alpha, beta)
                }
            }
        }
        if (best == null) return null
        val a1 = best[1]
        val b1 = best[2]

        // containment gate at the WIDER window
        val contWide = containmentAt(on, cs, a1, b1, W_COARSE)
        if (contWide < CONT_GATE) return null

        // 3. LSQ refit
        val fit = lsqFit(on, cs, a1, b1, W_FINE)
        val rec2 = recallAt(on, cs, fit.alpha, fit.beta, W_FINE)
        val cont2 = containmentAt(on, cs, fit.alpha, fit.beta, W_FINE)

        // 4. margin: recall at the fit minus the best runner-up beta
        //    more than 2 s away (absolute scan over [-60, +60])
        var runner = 0.0
        for (b10 in -600..600 step 5) {
            val beta = b10 / 10.0
            if (abs(beta - fit.beta) > 2.0) {
                val r = recallAt(on, cs, fit.alpha, beta, W_FINE)
                if (r > runner) runner = r
            }
        }
        val margin = rec2 - runner

        // 5. cross-half
        val mid = (cs[0] + cs[cs.size - 1]) / 2
        val first = cs.filter { it < mid }.toDoubleArray()
        val second = cs.filter { it >= mid }.toDoubleArray()
        var halfOk = true
        if (first.size >= 5 && second.size >= 5) {
            val fF = lsqFit(on, first, fit.alpha, fit.beta, W_FINE)
            val fS = lsqFit(on, second, fit.alpha, fit.beta, W_FINE)
            halfOk = abs(fF.beta - fS.beta) <= 0.5 && abs(fF.alpha - fS.alpha) <= 0.005
        }

        return PlainResult(fit.alpha, fit.beta, rec2, cont2, margin, fit.pairs, halfOk)
    }

    // ====================================================================
    // ffsubsync borrows (engine_best.py)
    // ====================================================================

    /**
     * Guard-band edge penalty: mean edge quality in [0, 1] over the cues
     * matched within W_FINE (1 = matched onset follows a >= GUARD_BAND s
     * pause; unmatched cues count 0). Used ONLY to break exact ties and
     * as a diagnostic — never overrides recall or relaxes a gate.
     */
    private fun guardScore(on: DoubleArray, cs: DoubleArray, alpha: Double, beta: Double): Double {
        if (on.isEmpty() || cs.isEmpty()) return 0.0
        val nOn = on.size
        var sum = 0.0
        for (s in cs) {
            val t = alpha * s + beta
            val k = lowerBound(on, t)
            val dl = if (k > 0) abs(on[k - 1] - t) else Double.POSITIVE_INFINITY
            val dr = if (k < nOn) abs(on[k] - t) else Double.POSITIVE_INFINITY
            val useL = k > 0 && dl <= W_FINE
            val useR = !useL && k < nOn && dr <= W_FINE
            if (!useL && !useR) continue
            val j = if (useL) k - 1 else k
            val gap = if (j > 0) on[j] - on[j - 1] else Double.POSITIVE_INFINITY
            sum += (gap / GUARD_BAND).coerceIn(0.0, 1.0)
        }
        return sum / cs.size
    }

    /**
     * Length-ratio alpha seed: alpha ~ (speech span of the audio) /
     * (span of the subtitle cues), beta = median nearest-onset residual.
     * Null when the ratio is outside [0.95, 1.05], the spans are too
     * short, or fewer than 8 cues match within W_FINE.
     */
    private fun ratioAlphaSeed(on: DoubleArray, cs: DoubleArray): Pair<Double, Double>? {
        val spanO = on.last() - on[0]
        val spanC = cs.last() - cs[0]
        if (spanC <= 60.0 || spanO <= 60.0) return null
        val a = spanO / spanC
        if (a < 0.95 || a > 1.05) return null
        val n = on.size
        val res = ArrayList<Double>()
        for (i in cs.indices) {
            val t = a * cs[i]
            val k = lowerBound(on, t)
            val ilo = if (k > 0) k - 1 else 0
            val ihi = if (k < n) k else n - 1
            val dl = abs(on[ilo] - t)
            val dr = abs(on[ihi] - t)
            val d = min(dl, dr)
            if (d <= W_FINE) {
                val nearest = if (dl <= dr) on[ilo] else on[ihi]
                res.add(nearest - t)
            }
        }
        if (res.size < 8) return null
        return a to median(res.toDoubleArray())
    }

    // ====================================================================
    // wide-offset acquisition (fix-3a)
    // ====================================================================

    private class CoarseCand(val height: Int, val alpha: Double, val beta: Double)

    /**
     * Coarse (alpha, beta) acquisition via residual histogram: for each
     * alpha on a 0.001 grid, histogram all pair residuals o - alpha*s
     * into 0.5 s bins over [-600, +600]; keep the top-4 peak candidates,
     * de-duplicated so they are distinct in (alpha, beta).
     */
    private fun coarseCandidates(on: DoubleArray, cs: DoubleArray): ArrayList<CoarseCand> {
        val nb = (2.0 * WIDE_OFF / HIST_BIN).toInt() // 2400 bins
        val hist = IntArray(nb)
        val t = DoubleArray(cs.size)
        val cand = ArrayList<CoarseCand>()
        for (a1000 in 950..1050) {
            val alpha = a1000 / 1000.0
            for (i in cs.indices) t[i] = alpha * cs[i]
            hist.fill(0)
            for (o in on) {
                // only cues whose predicted time falls within +/-WIDE_OFF
                // of this onset can land in the histogram
                val i0 = lowerBound(cs, (o - WIDE_OFF) / alpha)
                val i1 = upperBound(cs, (o + WIDE_OFF) / alpha)
                for (i in i0 until i1) {
                    val d = o - t[i]
                    if (d < -WIDE_OFF || d > WIDE_OFF) continue
                    var idx = floor((d + WIDE_OFF) / HIST_BIN).toInt()
                    if (idx >= nb) idx = nb - 1 // last bin includes right edge
                    hist[idx]++
                }
            }
            var iMax = 0
            for (i in 1 until nb) if (hist[i] > hist[iMax]) iMax = i
            val center = -WIDE_OFF + iMax * HIST_BIN + 0.5 * HIST_BIN
            cand.add(CoarseCand(hist[iMax], alpha, center))
        }
        cand.sortByDescending { it.height }
        val kept = ArrayList<CoarseCand>()
        for (c in cand) {
            var distinct = true
            for (k in kept) {
                if (abs(c.alpha - k.alpha) <= 0.002 && abs(c.beta - k.beta) <= 2.0) {
                    distinct = false
                    break
                }
            }
            if (distinct) kept.add(c)
            if (kept.size >= 4) break
        }
        return kept
    }

    /**
     * Fine handoff for one wide candidate (mirrors find_sync_v2 with the
     * widened +/-1.0 s fine beta box, lock-relative margin scan and the
     * relaxed 0.6 s cross-half tolerance). Null when any gate refuses.
     */
    private fun refineCandidate(on: DoubleArray, cs: DoubleArray, a0: Double, b0: Double): PlainResult? {
        // 2a. fine grid search
        var best: DoubleArray? = null // (rec, alpha, beta)
        val aStart = max(0.95, a0 - 0.0025)
        val aEnd = min(1.05, a0 + 0.0025)
        for (a10 in (aStart * 2000).toInt()..(aEnd * 2000).toInt()) {
            val alpha = a10 / 2000.0
            for (b100 in ((b0 - 1.0) * 100).toInt()..((b0 + 1.0) * 100).toInt() step 2) {
                val beta = b100 / 100.0
                val rec = recallAt(on, cs, alpha, beta, W_FINE)
                val cur = best
                if (cur == null || rec > cur[0]) best = doubleArrayOf(rec, alpha, beta)
            }
        }
        if (best == null) return null
        val a1 = best[1]
        val b1 = best[2]

        // 2b. containment gate at the wider window
        val contWide = containmentAt(on, cs, a1, b1, W_COARSE)
        if (contWide < CONT_GATE) return null

        // 2c. LSQ refit
        val fit = lsqFit(on, cs, a1, b1, W_FINE)
        val rec2 = recallAt(on, cs, fit.alpha, fit.beta, W_FINE)
        val cont2 = containmentAt(on, cs, fit.alpha, fit.beta, W_FINE)

        // 2d. margin gate, runner-up scanned +/-60 s around the LOCK
        var runner = 0.0
        for (b10 in ((fit.beta - MAX_OFF) * 10).toInt()..((fit.beta + MAX_OFF) * 10).toInt() step 5) {
            val beta = b10 / 10.0
            if (abs(beta - fit.beta) > 2.0) {
                val r = recallAt(on, cs, fit.alpha, beta, W_FINE)
                if (r > runner) runner = r
            }
        }
        val margin = rec2 - runner
        if (margin < MARGIN_GATE) return null

        // 2e. cross-half gate (beta tolerance relaxed 0.5 -> 0.6 for wide)
        val mid = (cs[0] + cs[cs.size - 1]) / 2
        val first = cs.filter { it < mid }.toDoubleArray()
        val second = cs.filter { it >= mid }.toDoubleArray()
        var halfOk = true
        if (first.size >= 5 && second.size >= 5) {
            val fF = lsqFit(on, first, fit.alpha, fit.beta, W_FINE)
            val fS = lsqFit(on, second, fit.alpha, fit.beta, W_FINE)
            halfOk = abs(fF.beta - fS.beta) <= 0.6 && abs(fF.alpha - fS.alpha) <= 0.005
        }
        if (!halfOk) return null

        return PlainResult(fit.alpha, fit.beta, rec2, cont2, margin, fit.pairs, halfOk)
    }

    /** engine_best.find_wide_best: histogram candidates + ratio seed. */
    private fun findWideBest(on: DoubleArray, cs: DoubleArray): PlainResult? {
        if (on.size < 20 || cs.size < 10) return null
        val kept = coarseCandidates(on, cs)
        // length-ratio alpha seed: tried after the histogram candidates,
        // skipped if it duplicates an existing one.
        val seed = ratioAlphaSeed(on, cs)
        if (seed != null) {
            val (aS, bS) = seed
            var dup = false
            for (c in kept) {
                if (abs(aS - c.alpha) <= 0.002 && abs(bS - c.beta) <= 2.0) {
                    dup = true
                    break
                }
            }
            if (!dup) kept.add(CoarseCand(0, aS, bS))
        }
        for (c in kept) {
            val r = refineCandidate(on, cs, c.alpha, c.beta)
            if (r != null) {
                return r.copy(guard = guardScore(on, cs, r.alpha, r.beta))
            }
        }
        return null
    }

    // ====================================================================
    // robust 6-phase pipeline (fix-3b + engine_best extras)
    // ====================================================================

    /**
     * Trimmed continuous score: per-cue tent score w = max(0, 1 - d/0.35)
     * with d = distance to the nearest onset; drop the worst 40% of cues,
     * sum the rest / n. Robust peak-finder against garbage cues.
     */
    private fun trimmedScore(on: DoubleArray, cs: DoubleArray, alpha: Double, beta: Double): Double {
        if (on.isEmpty() || cs.isEmpty()) return 0.0
        val n = cs.size
        val nOn = on.size
        val w = DoubleArray(n)
        var p = 0
        for (i in 0 until n) {
            val t = alpha * cs[i] + beta
            while (p + 1 < nOn && abs(on[p + 1] - t) <= abs(on[p] - t)) p++
            val d = abs(on[p] - t)
            w[i] = max(0.0, 1.0 - d / W_FINE)
        }
        val keep = max(1, ceil((1.0 - TRIM) * n).toInt())
        var sum = 0.0
        if (keep < n) {
            val sorted = w.copyOf().also { it.sort() }
            for (i in n - keep until n) sum += sorted[i]
        } else {
            for (x in w) sum += x
        }
        return sum / n
    }

    /**
     * (s, o) pairs: each cue matched to its nearest onset within window,
     * LEFT neighbor preferred on ties (exact _match_pairs semantics).
     */
    private fun matchPairs(
        on: DoubleArray,
        cs: DoubleArray,
        alpha: Double,
        beta: Double,
        window: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val sList = ArrayList<Double>()
        val oList = ArrayList<Double>()
        val nOn = on.size
        for (s in cs) {
            val t = alpha * s + beta
            val k = lowerBound(on, t)
            val dl = if (k > 0) abs(on[k - 1] - t) else Double.POSITIVE_INFINITY
            val dr = if (k < nOn) abs(on[k] - t) else Double.POSITIVE_INFINITY
            val useL = k > 0 && dl <= window
            val useR = !useL && k < nOn && dr <= window
            if (useL) {
                sList.add(s)
                oList.add(on[k - 1])
            } else if (useR) {
                sList.add(s)
                oList.add(on[k])
            }
        }
        return DoubleArray(sList.size) { sList[it] } to DoubleArray(oList.size) { oList[it] }
    }

    private fun lsq(s: DoubleArray, o: DoubleArray): Pair<Double, Double> {
        val n = s.size
        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (i in 0 until n) {
            sx += s[i]
            sy += o[i]
            sxx += s[i] * s[i]
            sxy += s[i] * o[i]
        }
        val d = n * sxx - sx * sx
        if (abs(d) < 1e-12) return 1.0 to (sy / n - sx / n)
        val a = (n * sxy - sx * sy) / d
        val b = (sy - a * sx) / n
        return a to b
    }

    private class Refit(val alpha: Double, val beta: Double, val pairs: Int)

    /**
     * Phases 4+5: two plain-style LSQ rounds for alpha (prune at the
     * match window), then beta from the MEDIAN of (o - alpha*s) over the
     * final pairs (50% breakdown point against asymmetric contamination).
     */
    private fun robustRefit(on: DoubleArray, cs: DoubleArray, alpha0: Double, beta0: Double): Refit {
        var a = alpha0
        var b = beta0
        for (r in 0 until 2) {
            val mp = matchPairs(on, cs, a, b, W_FINE)
            val s = mp.first
            val o = mp.second
            if (s.size < 8) break
            val l1 = lsq(s, o)
            a = l1.first
            b = l1.second
            var cnt = 0
            for (i in s.indices) if (abs(a * s[i] + b - o[i]) <= W_FINE) cnt++
            if (cnt < 8) break
            val sk = DoubleArray(cnt)
            val ok = DoubleArray(cnt)
            var j = 0
            for (i in s.indices) {
                if (abs(a * s[i] + b - o[i]) <= W_FINE) {
                    sk[j] = s[i]
                    ok[j] = o[i]
                    j++
                }
            }
            val l2 = lsq(sk, ok)
            a = l2.first
            b = l2.second
        }
        a = a.coerceIn(0.95, 1.05)
        val fin = matchPairs(on, cs, a, b, W_FINE)
        val pairs = fin.first.size
        if (pairs >= 8) {
            val res = DoubleArray(pairs) { fin.second[it] - a * fin.first[it] }
            b = median(res)
        }
        return Refit(a, b, pairs)
    }

    /**
     * v2 margin, lock-relative: recall at the fit minus the best runner-up
     * beta more than 2 s away, scanned +/-60 s around the LOCK (fix-3a
     * re-centering; identical to v2's absolute scan for in-range locks).
     */
    private fun marginRel(on: DoubleArray, cs: DoubleArray, alpha: Double, beta: Double): Pair<Double, Double> {
        val rec = recallAt(on, cs, alpha, beta, W_FINE)
        var runner = 0.0
        for (b10 in ((beta - MAX_OFF) * 10).toInt()..((beta + MAX_OFF) * 10).toInt() step 5) {
            val bb = b10 / 10.0
            if (abs(bb - beta) > 2.0) {
                val r = recallAt(on, cs, alpha, bb, W_FINE)
                if (r > runner) runner = r
            }
        }
        return (rec - runner) to rec
    }

    /**
     * v2 cross-half check; beta tolerance relaxed 0.5 -> 0.6 s only for
     * wide locks (|beta| > 60): a wide shift leaves one half with far
     * fewer matched pairs, so its half-fit beta is noisier; genuine
     * piecewise offsets are seconds-scale and still caught.
     */
    private fun halfCheck(on: DoubleArray, cs: DoubleArray, alpha: Double, beta: Double): Boolean {
        val mid = (cs[0] + cs[cs.size - 1]) / 2
        val first = cs.filter { it < mid }.toDoubleArray()
        val second = cs.filter { it >= mid }.toDoubleArray()
        if (first.size >= 5 && second.size >= 5) {
            val fF = lsqFit(on, first, alpha, beta, W_FINE)
            val fS = lsqFit(on, second, alpha, beta, W_FINE)
            val tol = if (abs(beta) > 60.0) 0.6 else 0.5
            return abs(fF.beta - fS.beta) <= tol && abs(fF.alpha - fS.alpha) <= 0.005
        }
        return true
    }

    /**
     * Exact engine recall (window 0.5) for the full coarse grid
     * (alpha 0.95-1.05 step 0.001, beta -60..60 step 0.5).
     */
    private fun coarseRecallMatrix(on: DoubleArray, cs: DoubleArray): Triple<DoubleArray, DoubleArray, Array<DoubleArray>> {
        val alphas = DoubleArray(101) { (950 + it) / 1000.0 }
        val betas = DoubleArray(241) { (-600 + it * 5) / 10.0 }
        val r = Array(101) { DoubleArray(241) }
        val n = cs.size
        val nOn = on.size
        for (ai in alphas.indices) {
            val alpha = alphas[ai]
            for (bi in betas.indices) {
                val beta = betas[bi]
                var p = 0
                var hits = 0
                for (i in cs.indices) {
                    val t = alpha * cs[i] + beta
                    while (p + 1 < nOn && abs(on[p + 1] - t) <= abs(on[p] - t)) p++
                    if (abs(on[p] - t) <= W_COARSE) hits++
                }
                r[ai][bi] = hits.toDouble() / n
            }
        }
        return Triple(alphas, betas, r)
    }

    /**
     * engine_best._robust_pipeline: fix-3b phases 1-6 with extra seeds
     * (wide locks, ratio seed) injected into the candidate pool.
     */
    private fun robustPipeline(
        on: DoubleArray,
        cs: DoubleArray,
        r0: PlainResult?,
        extraSeeds: List<DoubleArray>,
    ): PlainResult? {
        // Phase 1: coarse grid, exact engine recall, keep top 30.
        val matrix = coarseRecallMatrix(on, cs)
        val alphas = matrix.first
        val betas = matrix.second
        val r = matrix.third
        val cells = ArrayList<Triple<Double, Int, Int>>()
        for (ai in alphas.indices) {
            for (bi in betas.indices) cells.add(Triple(r[ai][bi], ai, bi))
        }
        cells.sortByDescending { it.first }
        val cand = ArrayList<Triple<Double, Double, Double>>() // (rec, a, b)
        for (cell in cells) {
            val rec = cell.first
            if (rec < 0.25) break
            cand.add(Triple(rec, alphas[cell.second], betas[cell.third]))
            if (cand.size >= N_COARSE_KEEP) break
        }
        if (cand.isEmpty() && r0 != null) {
            cand.add(Triple(r0.recall, r0.alpha, r0.beta))
        }
        if (cand.isEmpty()) return null
        if (r0 != null) {
            cand.add(Triple(r0.recall, r0.alpha, r0.beta))
        }
        // extra seeds join the pool, scored with the exact engine recall
        // at window 0.5 like every other candidate.
        for (xy in extraSeeds) {
            val recX = recallAt(on, cs, xy[0], xy[1], W_COARSE)
            cand.add(Triple(recX, xy[0], xy[1]))
        }

        // Phase 2: seed selection = UNION top-8 trimmed / top-8 recall,
        // plus beta-neighbors (b +/- 0.5) of every seed.
        class Scored(val trim: Double, val rec: Double, val a: Double, val b: Double)
        val scored = cand.map { Scored(trimmedScore(on, cs, it.second, it.third), it.first, it.second, it.third) }
        val byTrim = scored.sortedByDescending { it.trim }.take(N_FINE_SEEDS)
        val byRec = scored.sortedByDescending { it.rec }.take(N_FINE_SEEDS)
        val seeds = ArrayList<DoubleArray>()
        val seenCells = HashSet<Pair<Double, Double>>()
        for (s in byTrim + byRec) {
            for (bb in doubleArrayOf(s.b, s.b - 0.5, s.b + 0.5)) {
                if (seenCells.add(s.a to bb)) seeds.add(doubleArrayOf(s.a, bb))
            }
        }

        // Phase 3: two-track fine search around the seed set (shared
        // point set; insertion-ordered like the Python dict).
        val pts = LinkedHashSet<Pair<Int, Int>>()
        for (sd in seeds) {
            val a0 = sd[0]
            val b0 = sd[1]
            val aStart = max(0.95, a0 - 0.0025)
            val aEnd = min(1.05, a0 + 0.0025)
            for (a10 in (aStart * 2000).toInt()..(aEnd * 2000).toInt()) {
                for (b100 in ((b0 - 0.5) * 100).toInt()..((b0 + 0.5) * 100).toInt() step 2) {
                    pts.add(a10 to b100)
                }
            }
        }
        var bestA: DoubleArray? = null // recall track (rec, alpha, beta)
        var bestB: DoubleArray? = null // trimmed track (sc, alpha, beta)
        for ((a10, b100) in pts) {
            val alpha = a10 / 2000.0
            val beta = b100 / 100.0
            val rec = recallAt(on, cs, alpha, beta, W_FINE)
            val curA = bestA
            if (curA == null || rec > curA[0]) bestA = doubleArrayOf(rec, alpha, beta)
            val sc = trimmedScore(on, cs, alpha, beta)
            val curB = bestB
            if (curB == null || sc > curB[0]) bestB = doubleArrayOf(sc, alpha, beta)
        }
        if (bestA == null || bestB == null) return null

        // Phases 4+5: robust refit of both track solutions.
        val solA = robustRefit(on, cs, bestA[1], bestA[2])
        val solB = robustRefit(on, cs, bestB[1], bestB[2])

        // Candidate order: same basin -> track A first (plain's
        // calibration); disagree -> trimmed score orders. plain's result
        // and the extra seeds join the pool afterwards.
        class Cand2(val a: Double, val b: Double, val pairs: Int)
        val basinClose = abs(solA.beta - solB.beta) <= 0.5 && abs(solA.alpha - solB.alpha) <= 0.002
        val cands = ArrayList<Cand2>()
        cands.add(Cand2(solA.alpha, solA.beta, solA.pairs))
        if (!basinClose) {
            cands.add(Cand2(solB.alpha, solB.beta, solB.pairs))
            cands.sortByDescending { trimmedScore(on, cs, it.a, it.b) }
        }
        if (r0 != null) cands.add(Cand2(r0.alpha, r0.beta, r0.pairs))
        for (xy in extraSeeds) {
            val mp = matchPairs(on, cs, xy[0], xy[1], W_FINE)
            cands.add(Cand2(xy[0], xy[1], mp.first.size))
        }

        // dedupe near-identical candidates; guard-band edge penalty
        // breaks exact ties (ffsubsync borrow a).
        val uniq = ArrayList<Cand2>()
        for (c in cands) {
            var hit = -1
            for (i in uniq.indices) {
                if (abs(c.a - uniq[i].a) <= 0.0002 && abs(c.b - uniq[i].b) <= 0.05) {
                    hit = i
                    break
                }
            }
            if (hit < 0) {
                uniq.add(c)
            } else {
                val u = uniq[hit]
                if (guardScore(on, cs, c.a, c.b) > guardScore(on, cs, u.a, u.b)) {
                    uniq[hit] = c
                }
            }
        }

        // Phase 6: the same gates as plain; first candidate passing wins.
        for (c in uniq) {
            val contWide = containmentAt(on, cs, c.a, c.b, W_COARSE)
            if (contWide < CONT_GATE) continue
            val mr = marginRel(on, cs, c.a, c.b)
            val margin = mr.first
            val rec2 = mr.second
            if (margin < MARGIN_GATE) continue
            val halfOk = halfCheck(on, cs, c.a, c.b)
            if (!halfOk) continue
            val cont2 = containmentAt(on, cs, c.a, c.b, W_FINE)
            return PlainResult(
                alpha = c.a, beta = c.b, recall = rec2, containment = cont2,
                margin = margin, pairs = c.pairs, halfOk = halfOk,
                guard = guardScore(on, cs, c.a, c.b),
            )
        }
        return null
    }

    // ====================================================================
    // short-circuit attractor guard (engine_best._trimmed_peak_ok)
    // ====================================================================

    /**
     * Under heavy garbage corruption plain's recall argmax can jump to a
     * false attractor ~0.7 s away while still showing margin 0.07-0.10,
     * so the margin gate alone cannot protect a short-circuit. The lock
     * is only trusted if it is also a trimmed-score peak: the trimmed
     * argmax over the fine neighborhood (alpha +/-0.0025 step 0.0005,
     * beta +/-1.0 step 0.02) must sit within 0.15 s / 0.0005 of the lock.
     */
    private fun trimmedPeakOk(on: DoubleArray, cs: DoubleArray, alpha: Double, beta: Double): Boolean {
        var bestSc = trimmedScore(on, cs, alpha, beta)
        var bestA = alpha
        var bestB = beta
        val aStart = max(0.95, alpha - 0.0025)
        val aEnd = min(1.05, alpha + 0.0025)
        for (a10 in (aStart * 2000).toInt()..(aEnd * 2000).toInt()) {
            val a = a10 / 2000.0
            for (b100 in ((beta - 1.0) * 100).toInt()..((beta + 1.0) * 100).toInt() step 2) {
                val b = b100 / 100.0
                val sc = trimmedScore(on, cs, a, b)
                if (sc > bestSc) {
                    bestSc = sc
                    bestA = a
                    bestB = b
                }
            }
        }
        return abs(bestB - beta) <= 0.15 && abs(bestA - alpha) <= 0.0005
    }
}
