package dev.anonrode.player.core.media.sync

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Cut-recovery ensemble — port of engine_best.detect_cut_best and its two
 * validated detectors:
 *
 *   A = tools/_fix2a_ransac_cut.py  detect_cut      (RANSAC/Hough two lines)
 *   B = tools/_cut_fix2b_phys.py    detect_cut_phys (spectrum modes + alass-DP)
 *
 * Decision (engine_best.py):
 *   * both no-cut                     -> null (high-confidence no-cut)
 *   * both cut AND agree (|d cut_sub| <= 40 s, |d beta_after| <= 10 s)
 *     -> evidence-weighted blend + joint ANCOVA refit, confidence "high"
 *   * disagree or single-method cut   -> arbitrate with the COMMON
 *     density-normalized excess objective (fix-2b dp_cut_scan); accept the
 *     winner only if its gain over the beta-optimized no-cut baseline
 *     >= SPLIT_PENALTY, else refuse.
 *
 * Model: audio_time = alpha * subtitle_time + beta, with a cut removing
 * cut_len = beta_before - beta_after seconds of audio at cut_sub.
 *
 * Diagnostics-only outputs of the Python detectors (diag dicts, slope_ok,
 * voter counts) are omitted; every value that feeds a decision is kept.
 */
object CutEnsemble {

    data class CutResult(
        val alpha: Double,
        val betaBefore: Double,
        val betaAfter: Double,
        val cutSub: Double,
        val cutLen: Double,
        val cutAudio: Double,
        val confidence: String, // "high" | "medium" | "single-method"
    )

    // ── ensemble constants (engine_best.py) ─────────────────────────────
    private const val CUT_AGREE_TOL = 40.0 // s: methods' cut_sub must be this close
    private const val BETA_AGREE_TOL = 10.0 // s: methods' beta_after must be this close

    // ── fix-2a constants (_fix2a_ransac_cut.py) ─────────────────────────
    private const val BAND = 150.0 // (cue,onset) pairing band
    private const val TOL_VOTE = 0.30
    private const val TOL_FIT = 0.35
    private const val MIN_SIDE = 15
    private const val CAND_WINDOW = 2.5
    private const val MIN_CUT_LEN_RANSAC = 5.0
    private const val MIN_INLIERS2 = 5
    private const val MIN_GAIN = 0.008
    private const val ORPHAN_MARGIN = 120.0
    private const val TERR_W = 20 // dominant-territory smoothing half-window (cues)
    private const val TERR_THR = 0.06
    private const val TERR_RUN = 25
    private const val LAST_CLUSTER_SPAN = 30.0
    private const val LAST_CLUSTER_NEED = 3
    private const val POST_CLUSTER_NEED = 2
    private const val POST_CLUSTER_GAP = 30.0
    private const val B_LO2 = -175.0
    private const val B_HI2 = 175.0
    private const val B_STEP2 = 0.25
    private const val BGRID_N = 1401 // arange(-175, 175.25, 0.25)
    private const val EXCL2 = 5.0 // exclusion band around beta1
    private const val LAM_WIN_A = 40.0 // make_lambda density half-window
    private const val SCAN_W = 4 // cut_excess_scan boxcar half-window
    private const val RECALL_WIN = 0.35
    private const val HIST_LO = -160.0
    private const val HIST_HI = 160.0
    private const val HIST_STEP = 0.25
    private const val HIST_BINS = 1280 // edges -160..160 step 0.25

    // ── fix-2b constants (_cut_fix2b_phys.py) ───────────────────────────
    private const val MATCH_WIN = 0.5
    private const val BETA_LO = -180.0
    private const val BETA_HI = 180.0
    private const val ALPHA_LO = 0.970
    private const val ALPHA_HI = 1.040
    private const val MIN_BETA = 5.0
    private const val PEAK_MARGIN = 0.05
    private const val MIN_AFTER_ONLY = 12
    private const val MIN_CUT_LEN_PHYS = 3.0
    private const val LAM_WIN_B = 30.0
    private const val SPLIT_PENALTY = 6.0
    private const val CLUSTER_GAP = 30.0
    private const val CLUSTER_NEED = 3
    private const val DA_SMALL = 2.0
    private const val DA_BIG = 10.0
    private const val DA_RUN_MIN = 3
    private const val DA_RUN_QUAL = 0.60
    private const val ORPHAN_MA_RATE = 0.35
    private const val FIT_TOL = 0.35

    // ====================================================================
    // public API (contracted with SyncOrchestrator)
    // ====================================================================

    /** Agreement-gated ensemble; null = no cut (or refused). */
    fun detectCut(onsets: List<Double>, cueStarts: List<Double>): CutResult? {
        val cues = cueStarts.sorted().toDoubleArray()
        val on = onsets.sorted().toDoubleArray()
        if (cues.size < 30 || on.size < 50) return null

        // NOTE argument order mirrors Python: detect_cut(onsets, cues) but
        // detect_cut_phys(cues, onsets).
        val na = detectCutRansac(on, cues)
            ?.let { NormCut(it.alpha, it.betaBefore, it.betaAfter, it.cutSub) }
        val nb = detectCutPhys(cues, on)
            ?.let { NormCut(it.alpha, it.betaBefore, it.betaAfter, it.cutSub) }

        if (na == null && nb == null) return null // both firewalls: no cut

        val base0 = noCutBaseline(cues, on, (na ?: nb!!).alpha)

        if (na != null && nb != null) {
            val dcut = abs(na.cutSub - nb.cutSub)
            val dbeta = abs(na.betaAfter - nb.betaAfter)
            if (dcut <= CUT_AGREE_TOL && dbeta <= BETA_AGREE_TOL) {
                // AGREEMENT: evidence-weighted blend + joint ANCOVA refit.
                val sa = modelExcess(on, cues, na) - base0
                val sb = modelExcess(on, cues, nb) - base0
                val wa = max(sa, 1.0)
                val wb = max(sb, 1.0)
                val w = wa + wb
                val cutSub = (wa * na.cutSub + wb * nb.cutSub) / w
                val alpha0 = (wa * na.alpha + wb * nb.alpha) / w
                val ba0 = (wa * na.betaAfter + wb * nb.betaAfter) / w
                // beta_before: blend when consistent; when the methods diverge
                // (>1 s) apply the minimal-shift prior — the before-line is the
                // original pre-cut lock offset, which is small for any subtitle
                // that was roughly synced (fix-2a's weak before-line can lock a
                // copy-symmetric phantom at beta_true + alpha*dialogue-lag).
                val bb0 = if (abs(na.betaBefore - nb.betaBefore) <= 1.0) {
                    (wa * na.betaBefore + wb * nb.betaBefore) / w
                } else {
                    if (abs(na.betaBefore) <= abs(nb.betaBefore)) na.betaBefore else nb.betaBefore
                }
                val l0 = bb0 - ba0
                val refit = jointRefit(cues, on, cutSub, l0, alpha0, bb0, ba0)
                var a = refit.first
                var bb = refit.second
                var ba = refit.third
                // runaway guard (same as fix-2b)
                if (!(abs(a - alpha0) <= 0.004 && abs(bb - bb0) <= 2.0 &&
                        abs(ba - ba0) <= 5.0 && bb - ba >= MIN_CUT_LEN_PHYS)
                ) {
                    a = alpha0; bb = bb0; ba = ba0
                }
                return CutResult(
                    alpha = a, betaBefore = bb, betaAfter = ba,
                    cutSub = cutSub, cutLen = bb - ba,
                    cutAudio = a * cutSub + bb, confidence = "high",
                )
            }
            // DISAGREEMENT -> fall through to evidence arbitration.
        }

        // Arbitration: score every detected model with the common objective.
        val scored = ArrayList<Pair<Double, NormCut>>()
        for (nCut in listOfNotNull(na, nb)) {
            scored.add((modelExcess(on, cues, nCut) - base0) to nCut)
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.first }
        val (sBest, nBest) = scored[0]
        if (sBest < SPLIT_PENALTY) return null // refuse: no decisive evidence

        val a0 = nBest.alpha
        val bb0 = nBest.betaBefore
        val ba0 = nBest.betaAfter
        val l0 = bb0 - ba0
        val refit = jointRefit(cues, on, nBest.cutSub, l0, a0, bb0, ba0)
        var a = refit.first
        var bb = refit.second
        var ba = refit.third
        if (!(abs(a - a0) <= 0.004 && abs(bb - bb0) <= 2.0 &&
                abs(ba - ba0) <= 5.0 && bb - ba >= MIN_CUT_LEN_PHYS)
        ) {
            a = a0; bb = bb0; ba = ba0
        }
        val conf = if (na != null && nb != null) "medium" else "single-method"
        return CutResult(
            alpha = a, betaBefore = bb, betaAfter = ba,
            cutSub = nBest.cutSub, cutLen = bb - ba,
            cutAudio = a * nBest.cutSub + bb, confidence = conf,
        )
    }

    /**
     * Port of _fix2a_ransac_cut.recall_two_lines: (rec1, rec2) where rec2 is
     * the two-line model hit rate and rec1 the one-line (everything on the
     * after line) hit rate; orphan cues in [cutSub, cutSub+cutLen] are
     * skipped (their content was deleted).
     */
    fun recallTwoLines(
        onsets: DoubleArray,
        cueStarts: DoubleArray,
        alpha: Double,
        betaBefore: Double,
        betaAfter: Double,
        cutSub: Double,
        cutLen: Double,
    ): Pair<Double, Double> {
        val m = onsets.size
        var hits1 = 0
        var hits2 = 0
        for (s in cueStarts) {
            val beta = when {
                s < cutSub -> betaBefore
                s > cutSub + cutLen -> betaAfter
                else -> continue // orphan cues: content deleted
            }
            val t = alpha * s + beta
            val k = lowerBound(onsets, t)
            if ((k > 0 && abs(onsets[k - 1] - t) <= RECALL_WIN) ||
                (k < m && abs(onsets[k] - t) <= RECALL_WIN)
            ) {
                hits2++
            }
            // one-line model: everything on the after line
            val t1 = alpha * s + betaAfter
            val k1 = lowerBound(onsets, t1)
            if ((k1 > 0 && abs(onsets[k1 - 1] - t1) <= RECALL_WIN) ||
                (k1 < m && abs(onsets[k1] - t1) <= RECALL_WIN)
            ) {
                hits1++
            }
        }
        val n = cueStarts.size
        return (hits1.toDouble() / n) to (hits2.toDouble() / n)
    }

    // ====================================================================
    // shared primitives
    // ====================================================================

    private data class NormCut(
        val alpha: Double,
        val betaBefore: Double,
        val betaAfter: Double,
        val cutSub: Double,
    )

    /** np.searchsorted side="left": first index with a[i] >= x. */
    private fun lowerBound(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] >= x) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** np.searchsorted side="right": first index with a[i] > x. */
    private fun upperBound(a: DoubleArray, x: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] > x) hi = mid else lo = mid + 1
        }
        return lo
    }

    /** np.median: mean of the two middle values for even length. */
    private fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return Double.NaN
        val s = v.copyOf().also { it.sort() }
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    /** np.argmax: first index of the maximum. */
    private fun argMax(v: DoubleArray): Int {
        var bi = 0
        for (i in v.indices) if (v[i] > v[bi]) bi = i
        return bi
    }

    private fun argMaxInt(v: IntArray): Int {
        var bi = 0
        for (i in v.indices) if (v[i] > v[bi]) bi = i
        return bi
    }

    /**
     * np.convolve(x, ones(2w+1)/(2w+1), mode="same"): zero-padded boxcar
     * mean over +/-w neighbours.
     */
    private fun boxcarSame(x: DoubleArray, w: Int): DoubleArray {
        val n = x.size
        val k = 2 * w + 1
        val p = DoubleArray(n + 1)
        for (i in 0 until n) p[i + 1] = p[i] + x[i]
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val lo = max(0, i - w)
            val hi = min(n - 1, i + w)
            out[i] = (p[hi + 1] - p[lo]) / k
        }
        return out
    }

    /** np.interp: linear interpolation with edge clamping. */
    private fun interp(x: Double, grid: DoubleArray, fp: DoubleArray): Double {
        val n = grid.size
        if (n == 1 || x <= grid[0]) return fp[0]
        if (x >= grid[n - 1]) return fp[n - 1]
        var i = upperBound(grid, x) - 1
        if (i > n - 2) i = n - 2
        val frac = (x - grid[i]) / (grid[i + 1] - grid[i])
        return fp[i] + frac * (fp[i + 1] - fp[i])
    }

    /**
     * Per-point nearest-onset distance (np _nearest_d): for t[i], the
     * distance to the closest onset, inf when the side is empty.
     */
    private fun nearestD(t: DoubleArray, onsets: DoubleArray): DoubleArray {
        val m = onsets.size
        return DoubleArray(t.size) { i ->
            val k = lowerBound(onsets, t[i])
            val dlo = if (k > 0) abs(onsets[k - 1] - t[i]) else Double.POSITIVE_INFINITY
            val dhi = if (k < m) abs(onsets[k] - t[i]) else Double.POSITIVE_INFINITY
            min(dlo, dhi)
        }
    }

    /**
     * spectrum(t, onsets, betas, window) from _fix2b_grid_cut.py: fraction
     * of cues with some onset within `window` of t + beta, per beta.
     * t must be sorted ascending (alpha > 0 times sorted cues); the nearest
     * onset is then tracked with a monotone two-pointer scan instead of a
     * per-point binary search (same distances, much faster on-device).
     */
    private fun spectrum(
        t: DoubleArray,
        onsets: DoubleArray,
        betas: DoubleArray,
        window: Double,
    ): DoubleArray {
        val n = t.size
        val m = onsets.size
        val out = DoubleArray(betas.size)
        if (n == 0 || m == 0) return out
        for (j in betas.indices) {
            val b = betas[j]
            var p = 0
            var cnt = 0
            for (i in 0 until n) {
                val tt = t[i] + b
                while (p + 1 < m && abs(onsets[p + 1] - tt) <= abs(onsets[p] - tt)) p++
                if (abs(onsets[p] - tt) <= window) cnt++
            }
            out[j] = cnt.toDouble() / n
        }
        return out
    }

    // ====================================================================
    // fix-2a: RANSAC/Hough two-parallel-line detector
    // ====================================================================

    private data class RansacCut(
        val alpha: Double,
        val betaBefore: Double,
        val betaAfter: Double,
        val cutSub: Double,
        val cutLen: Double,
    )

    private fun buildCloud(onsets: DoubleArray, starts: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val sList = ArrayList<Double>()
        val oList = ArrayList<Double>()
        for (sVal in starts) {
            val i0 = lowerBound(onsets, sVal - BAND)
            val i1 = upperBound(onsets, sVal + BAND)
            for (j in i0 until i1) {
                sList.add(sVal)
                oList.add(onsets[j])
            }
        }
        return DoubleArray(sList.size) { sList[it] } to DoubleArray(oList.size) { oList[it] }
    }

    /**
     * np.histogram on edges arange(-160, 160.25, 0.25): bin i covers
     * [edge_i, edge_i+1); the LAST bin includes its right edge.
     */
    private fun binHistogram(r: Double, h: IntArray) {
        if (r < HIST_LO || r > HIST_HI) return
        var idx = floor((r - HIST_LO) / HIST_STEP).toInt()
        if (idx >= HIST_BINS) idx = HIST_BINS - 1
        h[idx]++
    }

    /** 3-bin sliding-window peak count of the residual histogram. */
    private fun houghScore(s: DoubleArray, o: DoubleArray, a: Double, h: IntArray): Int {
        h.fill(0)
        for (i in s.indices) binHistogram(o[i] - a * s[i], h)
        var best = 0
        for (i in 0 until HIST_BINS - 2) {
            val w = h[i] + h[i + 1] + h[i + 2]
            if (w > best) best = w
        }
        return best
    }

    private fun houghAlpha(s: DoubleArray, o: DoubleArray): Pair<Double, Int> {
        val h = IntArray(HIST_BINS)
        var bestA = 1.0
        var bestS = -1
        // coarse 0.0005 grid over [0.95, 1.05]
        for (ak in 9500..10500 step 5) {
            val a = ak / 10000.0
            val sc = houghScore(s, o, a, h)
            if (sc > bestS) {
                bestA = a
                bestS = sc
            }
        }
        // fine 0.0001 grid over +/-0.002 around the winner
        val loK = ((bestA - 0.002) * 10000).toInt()
        val hiK = ((bestA + 0.002) * 10000).toInt()
        for (ak in loK..hiK) {
            val a = ak / 10000.0
            val sc = houghScore(s, o, a, h)
            if (sc > bestS) {
                bestA = a
                bestS = sc
            }
        }
        return bestA to bestS
    }

    private fun dominantBeta(s: DoubleArray, o: DoubleArray, alpha: Double): Double {
        val h = IntArray(HIST_BINS)
        val r = DoubleArray(s.size)
        for (i in s.indices) {
            val ri = o[i] - alpha * s[i]
            r[i] = ri
            binHistogram(ri, h)
        }
        val iMax = argMaxInt(h)
        val coarse = HIST_LO + iMax * HIST_STEP + 0.5 * HIST_STEP
        val near = ArrayList<Double>()
        for (ri in r) if (abs(ri - coarse) <= 0.5) near.add(ri)
        return if (near.isNotEmpty()) median(near.toDoubleArray()) else coarse
    }

    /** Per cue, nearest onset within tol of alpha*s+beta (at most 1/cue). */
    private fun nearestPairs(
        onsets: DoubleArray,
        starts: DoubleArray,
        alpha: Double,
        beta: Double,
        tol: Double,
    ): Pair<DoubleArray, DoubleArray> {
        val px = ArrayList<Double>()
        val py = ArrayList<Double>()
        val m = onsets.size
        for (sVal in starts) {
            val t = alpha * sVal + beta
            val k = lowerBound(onsets, t)
            var best = Double.NaN
            for (j in intArrayOf(k - 1, k)) {
                if (j in 0 until m && abs(onsets[j] - t) <= tol) {
                    if (best.isNaN() || abs(onsets[j] - t) < abs(best - t)) best = onsets[j]
                }
            }
            if (!best.isNaN()) {
                px.add(sVal)
                py.add(best)
            }
        }
        return DoubleArray(px.size) { px[it] } to DoubleArray(py.size) { py[it] }
    }

    /** np.polyfit(px, py, 1); null when underdetermined/singular. */
    private fun lsqLine(px: DoubleArray, py: DoubleArray): Pair<Double, Double>? {
        if (px.size < 3) return null
        var mx = 0.0
        var my = 0.0
        for (i in px.indices) {
            mx += px[i]
            my += py[i]
        }
        mx /= px.size
        my /= px.size
        var num = 0.0
        var den = 0.0
        for (i in px.indices) {
            num += (px[i] - mx) * (py[i] - my)
            den += (px[i] - mx) * (px[i] - mx)
        }
        if (den <= 1e-12) return null
        val a = num / den
        return a to (my - a * mx)
    }

    /** Onset density per audio second on a 1 s grid (fix-2a make_lambda). */
    private fun makeLambda(onsets: DoubleArray, tMax: Double): DoubleArray {
        val count = ceil(tMax + 1.0).toInt() // np.arange(0.0, t_max + 1.0, 1.0)
        return DoubleArray(count) { i ->
            val t = i.toDouble()
            (upperBound(onsets, t + LAM_WIN_A) - lowerBound(onsets, t - LAM_WIN_A))
                .toDouble() / (2.0 * LAM_WIN_A)
        }
    }

    private fun lamAt(lam: DoubleArray, t: Double): Double {
        val idx = min(max(t, 0.0), lam.size - 1.0).toInt()
        return lam[idx]
    }

    private class VoteVec(val v: DoubleArray, val e: DoubleArray)

    /** Per-cue voter (onset within tol of alpha*s+beta) + chance rate E. */
    private fun voteVec(
        onsets: DoubleArray,
        starts: DoubleArray,
        alpha: Double,
        beta: Double,
        tol: Double,
        lam: DoubleArray,
    ): VoteVec {
        val n = starts.size
        val m = onsets.size
        val v = DoubleArray(n)
        val e = DoubleArray(n)
        for (i in 0 until n) {
            val t = alpha * starts[i] + beta
            val k = lowerBound(onsets, t)
            if ((k > 0 && abs(onsets[k - 1] - t) <= tol) ||
                (k < m && abs(onsets[k] - t) <= tol)
            ) {
                v[i] = 1.0
            }
            e[i] = 1.0 - exp(-2.0 * tol * lamAt(lam, t))
        }
        return VoteVec(v, e)
    }

    private class Territory(val edgeIdx: Int, val side: String)

    /**
     * Locate the dominant line's territory via the density-normalized
     * excess voter rate (v - E), smoothed over +/-TERR_W cues and required
     * to sustain TERR_THR for TERR_RUN consecutive cues. Returns
     * (edgeIdx, "suffix") when the dominant line occupies the tail, or
     * (edgeIdx, "prefix") for the mirror (late cut); null when there is
     * no split evidence.
     */
    private fun dominantTerritory(v: DoubleArray, e: DoubleArray): Territory? {
        val n = v.size
        if (n < 2 * (TERR_W + MIN_SIDE + TERR_RUN)) return null
        val ex = DoubleArray(n) { v[it] - e[it] }
        val sm = boxcarSame(ex, TERR_W)
        val hi = BooleanArray(n) { sm[it] >= TERR_THR }
        var i0 = -1
        for (i in MIN_SIDE until n - TERR_RUN) {
            var all = true
            for (j in i until i + TERR_RUN) {
                if (!hi[j]) {
                    all = false
                    break
                }
            }
            if (all) {
                i0 = i
                break
            }
        }
        var j1 = -1
        for (i in n - MIN_SIDE downTo TERR_RUN + 1) {
            var all = true
            for (j in i - TERR_RUN until i) {
                if (!hi[j]) {
                    all = false
                    break
                }
            }
            if (all) {
                j1 = i
                break
            }
        }
        if (i0 < 0) return null
        if (i0 <= MIN_SIDE) {
            // dominant line starts at the beginning -> late cut or no cut
            if (j1 >= 0 && j1 < n - MIN_SIDE) return Territory(j1, "prefix")
            return null
        }
        return Territory(i0, "suffix")
    }

    /**
     * End of the last cluster of >=need voters within span seconds
     * (voters restricted to starts < tLim); null when none qualifies.
     */
    private fun lastVoterCluster(starts: DoubleArray, v: DoubleArray, tLim: Double): Double? {
        val idx = ArrayList<Int>()
        for (i in starts.indices) if (v[i] != 0.0 && starts[i] < tLim) idx.add(i)
        if (idx.size < LAST_CLUSTER_NEED) return null
        var bestEnd = -1
        var a = 0
        while (a < idx.size) {
            var b = a
            while (b + 1 < idx.size && starts[idx[b + 1]] - starts[idx[a]] <= LAST_CLUSTER_SPAN) b++
            if (b - a + 1 >= LAST_CLUSTER_NEED) bestEnd = idx[b]
            a++
        }
        return if (bestEnd >= 0) starts[bestEnd] else null
    }

    /**
     * Upper bound on the cut from the after line's first voter cluster:
     * first cluster of >=need voters chained with gaps <=gap at/after
     * tMin, returns cluster_start - L; clusters implying a cut before
     * cutLo are orphan-copy clusters and are skipped.
     */
    private fun firstPostClusterBound(
        starts: DoubleArray,
        v: DoubleArray,
        cutLo: Double,
        l: Double,
        tMin: Double,
    ): Double? {
        val idx = ArrayList<Int>()
        for (i in starts.indices) if (v[i] != 0.0 && starts[i] >= tMin) idx.add(i)
        val clusters = ArrayList<ArrayList<Int>>()
        var cur = ArrayList<Int>()
        for ((k, i) in idx.withIndex()) {
            if (cur.isNotEmpty() && starts[i] - starts[idx[k - 1]] > POST_CLUSTER_GAP) {
                clusters.add(cur)
                cur = ArrayList()
            }
            cur.add(i)
        }
        if (cur.isNotEmpty()) clusters.add(cur)
        for (clu in clusters) {
            if (clu.size >= POST_CLUSTER_NEED) {
                val cutHi = starts[clu[0]] - l
                if (cutHi >= cutLo) return cutHi
            }
        }
        return null
    }

    private class Spec2(val bgrid: DoubleArray, val score: DoubleArray, val keep: BooleanArray)

    /**
     * Cue-deduplicated intercept votes minus expected chance votes on the
     * minority side. Each minority cue votes once per beta2 over the
     * merged interval union of [o - alpha*s - tol, o - alpha*s + tol];
     * Esum is the chance expectation from the local onset density.
     */
    private fun secondLineSpectrum(
        onsets: DoubleArray,
        starts: DoubleArray,
        idx: IntArray,
        alpha: Double,
        beta1: Double,
        lam: DoubleArray,
    ): Spec2 {
        val bgrid = DoubleArray(BGRID_N) { B_LO2 + it * B_STEP2 }
        val votes = DoubleArray(BGRID_N)
        val eSum = DoubleArray(BGRID_N)
        val tmax = (lam.size - 1).toDouble()
        for (ii in idx) {
            val s = starts[ii]
            val i0 = lowerBound(onsets, s - BAND)
            val i1 = upperBound(onsets, s + BAND)
            val ivs = ArrayList<DoubleArray>(max(0, i1 - i0))
            for (j in i0 until i1) {
                ivs.add(doubleArrayOf(onsets[j] - alpha * s - TOL_VOTE, onsets[j] - alpha * s + TOL_VOTE))
            }
            ivs.sortWith(compareBy({ it[0] }, { it[1] }))
            val merged = ArrayList<DoubleArray>()
            for (iv in ivs) {
                if (merged.isNotEmpty() && iv[0] <= merged.last()[1]) {
                    if (iv[1] > merged.last()[1]) merged.last()[1] = iv[1]
                } else {
                    merged.add(iv)
                }
            }
            for (iv in merged) {
                val a = iv[0]
                val b = iv[1]
                if (b < B_LO2 || a > B_HI2) continue
                val lo = lowerBound(bgrid, a)
                val hi = upperBound(bgrid, b)
                for (jj in lo until hi) votes[jj] += 1.0
            }
            for (jj in 0 until BGRID_N) {
                val tv = (alpha * s + bgrid[jj]).coerceIn(0.0, tmax).toInt()
                eSum[jj] += 1.0 - exp(-2.0 * TOL_VOTE * lam[tv])
            }
        }
        val score = DoubleArray(BGRID_N) { votes[it] - eSum[it] }
        val keep = BooleanArray(BGRID_N) { abs(bgrid[it] - beta1) >= EXCL2 }
        return Spec2(bgrid, score, keep)
    }

    private class Pick2(
        val beta2: Double?,
        val best: Double,
        val passThr: Double,
        val cands: List<DoubleArray>,
    )

    /** Near-best candidate set + minimal-shift prior. */
    private fun pickSecondLine(
        bgrid: DoubleArray,
        score: DoubleArray,
        keep: BooleanArray,
    ): Pick2 {
        val n = score.size
        val scr = DoubleArray(n) { if (keep[it]) score[it] else Double.NEGATIVE_INFINITY }
        val finite = ArrayList<Double>()
        for (x in scr) if (x.isFinite()) finite.add(x)
        if (finite.isEmpty()) return Pick2(null, Double.NEGATIVE_INFINITY, 4.0, emptyList())
        val med = median(finite.toDoubleArray())
        val devs = DoubleArray(finite.size) { abs(finite[it] - med) }
        val stdRob = 1.4826 * median(devs)
        val passThr = max(4.0, 2.0 * stdRob)
        var best = Double.NEGATIVE_INFINITY
        for (x in scr) if (x > best) best = x
        if (!best.isFinite() || best < passThr - 1.5) return Pick2(null, best, passThr, emptyList())
        // local maxima within CAND_WINDOW of best and above pass_thr - 1.5
        val cands = ArrayList<DoubleArray>()
        for (i in 1 until n - 1) {
            if (!scr[i].isFinite()) continue
            if (scr[i] >= best - CAND_WINDOW && scr[i] >= passThr - 1.5 &&
                scr[i] >= scr[i - 1] && scr[i] >= scr[i + 1]
            ) {
                cands.add(doubleArrayOf(bgrid[i], scr[i]))
            }
        }
        // merge candidates within 2 s, keep the stronger
        cands.sortBy { it[0] }
        val merged = ArrayList<DoubleArray>()
        for (c in cands) {
            if (merged.isNotEmpty() && c[0] - merged.last()[0] <= 2.0) {
                if (c[1] > merged.last()[1]) merged[merged.lastIndex] = c
            } else {
                merged.add(c)
            }
        }
        if (merged.isEmpty()) return Pick2(null, best, passThr, emptyList())
        // minimal-shift prior: smallest |beta2| among near-best candidates
        merged.sortBy { abs(it[0]) }
        return Pick2(merged[0][0], best, passThr, merged)
    }

    /** Fine scan (0.05 s) of the excess score around the coarse winner. */
    private fun refineBeta2(
        onsets: DoubleArray,
        starts: DoubleArray,
        idx: IntArray,
        alpha: Double,
        beta20: Double,
        lam: DoubleArray,
    ): Pair<Double, Double> {
        var bestB = beta20
        var bestS = Double.NEGATIVE_INFINITY
        val m = onsets.size
        val loK = ((beta20 - 0.6) * 20).toInt()
        val hiK = ((beta20 + 0.6) * 20).toInt()
        for (bk in loK..hiK) {
            val b = bk / 20.0
            var vv = 0
            var e = 0.0
            for (ii in idx) {
                val s = starts[ii]
                val t = alpha * s + b
                val k = lowerBound(onsets, t)
                if ((k > 0 && abs(onsets[k - 1] - t) <= TOL_VOTE) ||
                    (k < m && abs(onsets[k] - t) <= TOL_VOTE)
                ) {
                    vv++
                }
                e += 1.0 - exp(-2.0 * TOL_VOTE * lamAt(lam, t))
            }
            val sc = vv - e
            if (sc > bestS) {
                bestB = b
                bestS = sc
            }
        }
        return bestB to bestS
    }

    /**
     * Excess-score scan for the cut boundary c:
     *   score(c) = sum_{s < c} (v_b - E_b) + sum_{s > c+L} (v_a - E_a)
     * Both excess vectors are boxcar-smoothed (+/-SCAN_W cues) before the
     * prefix/suffix sums. Returns the RIGHTMOST c within 2.0 of the max
     * (the plateau's right edge is where the desert ends), or null when
     * the scan range is empty.
     */
    private fun cutExcessScan(
        starts: DoubleArray,
        vA: DoubleArray,
        vB: DoubleArray,
        eA: DoubleArray,
        eB: DoubleArray,
        l: Double,
        cLoIn: Double,
        cHiIn: Double,
    ): Pair<Double?, Double> {
        val n = starts.size
        val exB = boxcarSame(DoubleArray(n) { vB[it] - eB[it] }, SCAN_W)
        val exA = boxcarSame(DoubleArray(n) { vA[it] - eA[it] }, SCAN_W)
        val pb = DoubleArray(n + 1)
        for (i in 0 until n) pb[i + 1] = pb[i] + exB[i]
        val sa = DoubleArray(n + 1)
        for (i in n - 1 downTo 0) sa[i] = sa[i + 1] + exA[i]
        val cLo = max(cLoIn, starts[MIN_SIDE] - 5.0)
        val cHi = min(cHiIn, starts[n - MIN_SIDE] - l + 5.0)
        var bestLl = Double.NEGATIVE_INFINITY
        val scores = ArrayList<DoubleArray>()
        var c = cLo
        while (c <= cHi) {
            val i1 = lowerBound(starts, c) // side="left"
            val i2 = upperBound(starts, c + l) // side="right"
            val sc = pb[i1] + sa[i2]
            scores.add(doubleArrayOf(c, sc))
            if (sc > bestLl) bestLl = sc
            c += 1.0
        }
        if (scores.isEmpty()) return null to Double.NEGATIVE_INFINITY
        var bestC = Double.NEGATIVE_INFINITY
        for (p in scores) {
            if (p[1] >= bestLl - 2.0 && p[0] > bestC) bestC = p[0]
        }
        return bestC to bestLl
    }

    /**
     * fix-2a main detector. Returns null for every "single"/refused mode
     * (the ensemble only consumes mode=="cut" results).
     */
    private fun detectCutRansac(onsets: DoubleArray, startsIn: DoubleArray): RansacCut? {
        val starts = startsIn.copyOf().also { it.sort() }
        val n = starts.size

        // 0. point cloud
        val (cloudS, cloudO) = buildCloud(onsets, starts)
        if (cloudS.size < 500) return null // "cloud too small"

        // 1. Hough alpha + dominant line
        val hough = houghAlpha(cloudS, cloudO)
        var alpha = hough.first
        var beta1 = dominantBeta(cloudS, cloudO, alpha)
        val line1 = nearestPairs(onsets, starts, alpha, beta1, TOL_VOTE)
        val fit1 = lsqLine(line1.first, line1.second)
        if (fit1 != null) {
            alpha = fit1.first
            beta1 = fit1.second
        }

        // 2. dominant-line voters; locate the dominant line's territory
        val lam = makeLambda(onsets, max(onsets.last(), starts.last()) + 200.0)
        val vote1 = voteVec(onsets, starts, alpha, beta1, TOL_VOTE, lam)
        val terr = dominantTerritory(vote1.v, vote1.e) ?: return null
        val edgeT = starts[terr.edgeIdx]
        val terrSide = terr.side
        var minorityIdx: IntArray = if (terrSide == "suffix") {
            // dominant line = tail; minority = prefix trimmed by orphan margin
            val b = ArrayList<Int>()
            for (i in 0 until terr.edgeIdx) if (starts[i] < edgeT - ORPHAN_MARGIN) b.add(i)
            IntArray(b.size) { b[it] }
        } else {
            // dominant line = head (late cut); minority = suffix
            val b = ArrayList<Int>()
            for (i in terr.edgeIdx until n) if (starts[i] > edgeT + ORPHAN_MARGIN) b.add(i)
            IntArray(b.size) { b[it] }
        }
        if (minorityIdx.size < MIN_SIDE) return null // "no significant split"

        // 3-6. two-round iteration: second line -> cut length -> cut bounds
        //      -> cut position -> clean minority -> refit second line.
        var beta2 = 0.0
        var sc2 = 0.0
        var passThr = 4.0
        var betaBefore = 0.0
        var betaAfter = 0.0
        var l = 0.0
        var cutLo = 0.0
        var cutSub = 0.0
        var rnd = 0
        while (rnd < 2) {
            val spec = secondLineSpectrum(onsets, starts, minorityIdx, alpha, beta1, lam)
            val pick = pickSecondLine(spec.bgrid, spec.score, spec.keep)
            val beta2c = pick.beta2 ?: return null // "no significant second line"
            passThr = pick.passThr
            val refined = refineBeta2(onsets, starts, minorityIdx, alpha, beta2c, lam)
            beta2 = refined.first
            sc2 = refined.second

            if (terrSide == "suffix") {
                betaBefore = beta2
                betaAfter = beta1
            } else {
                betaBefore = beta1
                betaAfter = beta2
            }
            l = betaBefore - betaAfter
            if (l < MIN_CUT_LEN_RANSAC) return null // cut too short

            val va = voteVec(onsets, starts, alpha, betaAfter, TOL_VOTE, lam)
            val vb = voteVec(onsets, starts, alpha, betaBefore, TOL_VOTE, lam)
            val cl = lastVoterCluster(starts, vb.v, edgeT)
            cutLo = cl ?: starts[MIN_SIDE]
            var cutHi = firstPostClusterBound(starts, va.v, cutLo, l, edgeT - 60.0)
            if (cutHi == null) {
                val scan = cutExcessScan(
                    starts, va.v, vb.v, va.e, vb.e, l,
                    starts[MIN_SIDE] - 5.0, starts[n - MIN_SIDE] - l + 5.0,
                )
                cutHi = scan.first ?: (edgeT - l)
            }
            // np.clip(midpoint, starts[2]+1, starts[-2]-L-1) == max(min(x, hi), lo)
            val loB = starts[2] + 1.0
            val hiB = starts[n - 2] - l - 1.0
            cutSub = max(min(0.5 * (cutLo + cutHi), hiB), loB)

            // clean minority for the next round: only cues on the second
            // line's own side of the flip.
            val b = ArrayList<Int>()
            if (terrSide == "suffix") {
                for (i in 0 until n) if (starts[i] < cutLo + 10.0) b.add(i)
            } else {
                for (i in 0 until n) if (starts[i] > cutHi + l - 10.0) b.add(i)
            }
            minorityIdx = IntArray(b.size) { b[it] }
            if (minorityIdx.size < MIN_SIDE) break
            rnd++
        }

        // 5. line-2 inliers on the clean minority (free-slope check is
        //    diagnostic-only in Python — slope_ok — and omitted here).
        val startsMin = DoubleArray(minorityIdx.size) { starts[minorityIdx[it]] }
        val pairs2 = nearestPairs(onsets, startsMin, alpha, beta2, TOL_FIT)
        val n2 = pairs2.first.size
        if (n2 < MIN_INLIERS2) return null // noise peak

        // 7. joint ANCOVA refit (shared alpha, two betas), 2 rounds
        var aJ = alpha
        var bbJ = betaBefore
        var baJ = betaAfter
        val cutC = cutSub
        val m = onsets.size
        for (r in 0 until 2) {
            val gb = ArrayList<DoubleArray>()
            val ga = ArrayList<DoubleArray>()
            for (s in starts) {
                if (s < cutC) {
                    val t = aJ * s + bbJ
                    val k = lowerBound(onsets, t)
                    var best = Double.NaN
                    for (j in intArrayOf(k - 1, k)) {
                        if (j in 0 until m && abs(onsets[j] - t) <= TOL_FIT) {
                            if (best.isNaN() || abs(onsets[j] - t) < abs(best - t)) best = onsets[j]
                        }
                    }
                    if (!best.isNaN()) gb.add(doubleArrayOf(s, best))
                } else if (s > cutC + l) {
                    val t = aJ * s + baJ
                    val k = lowerBound(onsets, t)
                    var best = Double.NaN
                    for (j in intArrayOf(k - 1, k)) {
                        if (j in 0 until m && abs(onsets[j] - t) <= TOL_FIT) {
                            if (best.isNaN() || abs(onsets[j] - t) < abs(best - t)) best = onsets[j]
                        }
                    }
                    if (!best.isNaN()) ga.add(doubleArrayOf(s, best))
                }
            }
            if (gb.size >= 4 && ga.size >= 4) {
                var mbx = 0.0
                var mby = 0.0
                for (p in gb) {
                    mbx += p[0]
                    mby += p[1]
                }
                mbx /= gb.size
                mby /= gb.size
                var maxx = 0.0
                var may = 0.0
                for (p in ga) {
                    maxx += p[0]
                    may += p[1]
                }
                maxx /= ga.size
                may /= ga.size
                var num = 0.0
                var den = 0.0
                for (p in gb) {
                    num += (p[0] - mbx) * (p[1] - mby)
                    den += (p[0] - mbx) * (p[0] - mbx)
                }
                for (p in ga) {
                    num += (p[0] - maxx) * (p[1] - may)
                    den += (p[0] - maxx) * (p[0] - maxx)
                }
                if (den > 1e-9) aJ = num / den
                bbJ = mby - aJ * mbx
                baJ = may - aJ * maxx
            }
        }

        // 8. recalls + no-cut firewall
        val (rec1, rec2) = recallTwoLines(onsets, starts, aJ, bbJ, baJ, cutSub, l)
        val gain = rec2 - rec1
        val strong2 = n2 >= 8 && sc2 >= passThr - 1.5
        if (gain < MIN_GAIN && !strong2) return null

        return RansacCut(aJ, bbJ, baJ, cutSub, l)
    }

    // ====================================================================
    // fix-2b: spectrum two-mode + alass-style DP detector
    // ====================================================================

    private data class PhysCut(
        val alpha: Double,
        val betaBefore: Double,
        val betaAfter: Double,
        val cutSub: Double,
        val cutLen: Double,
    )

    /** Spectrum max-peak-count alpha; coarse 0.001 then fine 0.0002. */
    private fun estimateAlphaPhys(cues: DoubleArray, onsets: DoubleArray): Double {
        val betas = DoubleArray(361) { BETA_LO + it * 1.0 } // arange(-180, 180+eps, 1.0)
        val t = DoubleArray(cues.size)
        fun peakCount(a: Double): Int {
            for (i in cues.indices) t[i] = a * cues[i]
            val spec = spectrum(t, onsets, betas, MATCH_WIN)
            var mx = 0.0
            for (v in spec) if (v > mx) mx = v
            return (mx * cues.size).toInt() // Python int() truncation
        }
        var bestA = 1.0
        var bestV = -1
        for (a1000 in (ALPHA_LO * 1000).toInt()..(ALPHA_HI * 1000).toInt()) {
            val v = peakCount(a1000 / 1000.0)
            if (v > bestV) {
                bestV = v
                bestA = a1000 / 1000.0
            }
        }
        val lo = max(ALPHA_LO, bestA - 0.003)
        val hi = min(ALPHA_HI, bestA + 0.003)
        for (a10 in (lo * 10000).toInt()..(hi * 10000).toInt() step 2) {
            val v = peakCount(a10 / 10000.0)
            if (v > bestV) {
                bestV = v
                bestA = a10 / 10000.0
            }
        }
        return bestA
    }

    /**
     * Median (nearest_onset - t) over cues matched near `mode`, searching
     * the nearest onset near the MODE-predicted position t+mode (NOT near
     * t — searching near t only sees copy-matches and returns ~0).
     * Returns (median or NaN, count).
     */
    private fun modeBeta(t: DoubleArray, onsets: DoubleArray, mask: BooleanArray, mode: Double): Pair<Double, Int> {
        val res = ArrayList<Double>()
        val m = onsets.size
        for (i in t.indices) {
            if (!mask[i]) continue
            val p = t[i] + mode
            val k = lowerBound(onsets, p)
            val ilo = (k - 1).coerceIn(0, m - 1)
            val ihi = k.coerceIn(0, m - 1)
            val dloAbs = abs(onsets[ilo] - p)
            val dhiAbs = abs(onsets[ihi] - p)
            val dlo = if (k > 0) dloAbs else Double.POSITIVE_INFINITY
            val dhi = if (k < m) dhiAbs else Double.POSITIVE_INFINITY
            val nearest = if (dlo <= dhi) onsets[ilo] else onsets[ihi]
            if (min(dlo, dhi) <= MATCH_WIN) res.add(nearest - t[i])
        }
        if (res.isEmpty()) return Double.NaN to 0
        return median(res.toDoubleArray()) to res.size
    }

    private class LamProfile(val grid: DoubleArray, val lam: DoubleArray)

    /** Local onset density per audio second (fix-2b lambda_profile). */
    private fun lambdaProfile(onsets: DoubleArray, tMax: Double): LamProfile {
        val count = ceil(tMax + 1.0).toInt() // np.arange(0.0, t_max + step, step)
        val grid = DoubleArray(count) { it.toDouble() }
        val lam = DoubleArray(count)
        for (i in 0 until count) {
            val g = grid[i]
            // both searchsorted calls are side="left" in the Python source
            val cnt = lowerBound(onsets, g + LAM_WIN_B) - lowerBound(onsets, g - LAM_WIN_B)
            lam[i] = cnt / (2.0 * LAM_WIN_B)
        }
        return LamProfile(grid, lam)
    }

    private class DpScan(
        val grid: DoubleArray,
        val scores: DoubleArray,
        val mb: BooleanArray,
        val ma: BooleanArray,
        val da: DoubleArray,
    )

    /**
     * alass-style DP cut scan: ordered 3-state segmentation score S(c),
     * states B(beta=bb) -> O(orphan, duration pinned to L) -> A(beta=ba).
     * Per-cue ratings are density-normalized match excesses
     * (match - E, E = 1 - exp(-2*tol*lambda(target))).
     */
    private fun dpCutScan(
        starts: DoubleArray,
        t: DoubleArray,
        onsets: DoubleArray,
        bb: Double,
        ba: Double,
        l: Double,
    ): DpScan {
        val n = starts.size
        val tBb = DoubleArray(n) { t[it] + bb }
        val tBa = DoubleArray(n) { t[it] + ba }
        val db = nearestD(tBb, onsets)
        val da = nearestD(tBa, onsets)
        val mb = BooleanArray(n) { db[it] <= MATCH_WIN }
        val ma = BooleanArray(n) { da[it] <= MATCH_WIN }
        val tMax = max(onsets.last(), max(t.last() + abs(ba) + 60.0, t.last() + abs(bb) + 60.0))
        val prof = lambdaProfile(onsets, tMax)
        val pb = DoubleArray(n + 1)
        val sa = DoubleArray(n + 1)
        for (i in 0 until n) {
            val lb = interp(tBb[i].coerceIn(0.0, tMax), prof.grid, prof.lam)
            val eb = 1.0 - exp(-2.0 * MATCH_WIN * lb)
            pb[i + 1] = pb[i] + ((if (mb[i]) 1.0 else 0.0) - eb)
        }
        for (i in n - 1 downTo 0) {
            val la = interp(tBa[i].coerceIn(0.0, tMax), prof.grid, prof.lam)
            val ea = 1.0 - exp(-2.0 * MATCH_WIN * la)
            sa[i] = sa[i + 1] + ((if (ma[i]) 1.0 else 0.0) - ea)
        }
        val cLo = max(starts[3], 30.0)
        val cHi = starts[n - 4] - l
        val gStart = ceil(cLo)
        val gEnd = floor(cHi)
        val nGrid = if (gEnd >= gStart) (gEnd - gStart).toInt() + 1 else 0
        val grid = DoubleArray(nGrid) { gStart + it }
        val scores = DoubleArray(nGrid)
        for (i in 0 until nGrid) {
            val g = grid[i]
            val i1 = lowerBound(starts, g) // side="left"
            val i2 = upperBound(starts, g + l) // side="right"
            scores[i] = pb[i1] + sa[i2]
        }
        return DpScan(grid, scores, mb, ma, da)
    }

    /** Last cue time < lim with a match (detected matches are hard evidence). */
    private fun lastMatchBefore(starts: DoubleArray, mm: BooleanArray, lim: Double): Double? {
        var last: Double? = null
        for (i in starts.indices) if (mm[i] && starts[i] < lim) last = starts[i]
        return last
    }

    /**
     * Last SINGLE bb-match (mb and not ma) < lim. Double matches are not
     * trusted as before-line anchors: orphan cues inside a dense suture
     * copy-match BOTH betas.
     */
    private fun lastSingleMatch(
        starts: DoubleArray,
        mb: BooleanArray,
        ma: BooleanArray,
        lim: Double,
    ): Double? {
        var last: Double? = null
        for (i in starts.indices) if (mb[i] && !ma[i] && starts[i] < lim) last = starts[i]
        return last
    }

    /**
     * Start of the first cluster of >=need matches chained with gaps
     * <=gap among cues > tMin; isolated chance matches are skipped.
     */
    private fun firstMatchCluster(starts: DoubleArray, mm: BooleanArray, tMin: Double): Double? {
        val run = ArrayList<Int>()
        var prev = -1
        for (i in starts.indices) {
            if (!mm[i] || starts[i] <= tMin) continue
            if (run.isNotEmpty() && starts[i] - starts[prev] > CLUSTER_GAP) {
                if (run.size >= CLUSTER_NEED) return starts[run[0]]
                run.clear()
            }
            run.add(i)
            prev = i
        }
        if (run.size >= CLUSTER_NEED) return starts[run[0]]
        return null
    }

    /**
     * Dead-zone right-edge evidence: scan cues right of c0 while da stays
     * <= DA_BIG tracking the small-da quality; orphan cues have small da
     * (targets in the dense pre-suture content) and the first after-cues'
     * da jumps up when the suture content is sparse. Returns the last
     * small-da cue time, or null if the run is unclean.
     */
    private fun daJumpEdge(starts: DoubleArray, da: DoubleArray, c0: Double): Double? {
        var nScan = 0
        var nGood = 0
        var lastGood: Double? = null
        for (i in starts.indices) {
            if (starts[i] < c0) continue
            if (da[i] > DA_BIG) break
            nScan++
            if (da[i] <= DA_SMALL) {
                lastGood = starts[i]
                nGood++
            }
        }
        if (lastGood == null || nScan < DA_RUN_MIN) return null
        if (nGood.toDouble() / nScan < DA_RUN_QUAL) return null
        return lastGood
    }

    /** cut_sub from the DP plateau: evidence bounds + midpoint. */
    private fun locateCut(
        starts: DoubleArray,
        grid: DoubleArray,
        scores: DoubleArray,
        mb: BooleanArray,
        ma: BooleanArray,
        da: DoubleArray,
        l: Double,
    ): Double {
        var c0 = grid[0]
        var best = scores[0]
        for (i in scores.indices) {
            if (scores[i] > best) {
                best = scores[i]
                c0 = grid[i]
            }
        }

        // left bound: last SINGLE bb match at/before the plateau, with a
        // widening fallback chain.
        var cutLo = lastSingleMatch(starts, mb, ma, c0 + 0.75 * l)
        if (cutLo == null) cutLo = lastSingleMatch(starts, mb, ma, c0 + l)
        if (cutLo == null) cutLo = lastMatchBefore(starts, mb, c0 + 0.75 * l)
        if (cutLo == null) cutLo = lastMatchBefore(starts, mb, c0 + l)
        if (cutLo == null) cutLo = c0

        // right bound candidate H1: first sustained da cluster minus L
        val sCl = firstMatchCluster(starts, ma, c0 + l)
        val h1 = if (sCl != null) sCl - l else null

        // right bound candidate H2: dead-zone-corrected da-jump edge minus
        // L, trusted only when the orphan window shows a dense pre-suture.
        var winCnt = 0
        var maCnt = 0
        for (i in starts.indices) {
            if (starts[i] >= c0 && starts[i] <= c0 + l) {
                winCnt++
                if (ma[i]) maCnt++
            }
        }
        val rate = if (winCnt >= 3) maCnt.toDouble() / winCnt else 0.0
        val edge = daJumpEdge(starts, da, c0)
        val h2 = if (edge != null) edge - l else null

        var cutHi: Double? = null
        if (rate >= ORPHAN_MA_RATE && h2 != null && h2 >= cutLo) {
            cutHi = if (h1 == null) h2 else min(h1, h2)
        }
        if (cutHi == null) cutHi = h1
        if (cutHi == null || cutHi <= cutLo) cutHi = cutLo + max(10.0, 0.5 * l)

        return 0.5 * (cutLo + cutHi)
    }

    /** Per-cue (s, nearest_onset) pairs within FIT_TOL under (a, beta). */
    private fun collectPairs(
        cues: DoubleArray,
        onsets: DoubleArray,
        a: Double,
        beta: Double,
        mask: BooleanArray,
    ): ArrayList<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val m = onsets.size
        for (i in cues.indices) {
            if (!mask[i]) continue
            val s = cues[i]
            val t = a * s + beta
            val k = lowerBound(onsets, t)
            val ilo = (k - 1).coerceIn(0, m - 1)
            val ihi = k.coerceIn(0, m - 1)
            val dloAbs = abs(onsets[ilo] - t)
            val dhiAbs = abs(onsets[ihi] - t)
            val dlo = if (k > 0) dloAbs else Double.POSITIVE_INFINITY
            val dhi = if (k < m) dhiAbs else Double.POSITIVE_INFINITY
            val nearest = if (dlo <= dhi) onsets[ilo] else onsets[ihi]
            if (min(dlo, dhi) <= FIT_TOL) out.add(doubleArrayOf(s, nearest))
        }
        return out
    }

    /** Joint ANCOVA refit: shared alpha, two betas, 2 rounds (fix-2b). */
    private fun jointRefit(
        cues: DoubleArray,
        onsets: DoubleArray,
        cutSub: Double,
        l: Double,
        alpha0: Double,
        bb0: Double,
        ba0: Double,
    ): Triple<Double, Double, Double> {
        var a = alpha0
        var bb = bb0
        var ba = ba0
        for (r in 0 until 2) {
            val gbMask = BooleanArray(cues.size) { cues[it] < cutSub }
            val gaMask = BooleanArray(cues.size) { cues[it] > cutSub + l }
            val gb = collectPairs(cues, onsets, a, bb, gbMask)
            val ga = collectPairs(cues, onsets, a, ba, gaMask)
            if (gb.size >= 4 && ga.size >= 4) {
                var mbx = 0.0
                var mby = 0.0
                for (p in gb) {
                    mbx += p[0]
                    mby += p[1]
                }
                mbx /= gb.size
                mby /= gb.size
                var maxx = 0.0
                var may = 0.0
                for (p in ga) {
                    maxx += p[0]
                    may += p[1]
                }
                maxx /= ga.size
                may /= ga.size
                var num = 0.0
                var den = 0.0
                for (p in gb) {
                    num += (p[0] - mbx) * (p[1] - mby)
                    den += (p[0] - mbx) * (p[0] - mbx)
                }
                for (p in ga) {
                    num += (p[0] - maxx) * (p[1] - may)
                    den += (p[0] - maxx) * (p[0] - maxx)
                }
                if (den > 1e-9) a = num / den
                bb = mby - a * mbx
                ba = may - a * maxx
            } else {
                break
            }
        }
        return Triple(a, bb, ba)
    }

    /** fix-2b main detector; null = refused / no cut. */
    private fun detectCutPhys(cuesIn: DoubleArray, onsetsIn: DoubleArray): PhysCut? {
        val cues = cuesIn.copyOf().also { it.sort() }
        val onsets = onsetsIn.copyOf().also { it.sort() }

        // 1. shared alpha
        val alpha0 = estimateAlphaPhys(cues, onsets)
        val t = DoubleArray(cues.size) { alpha0 * cues[it] }

        // 2. two-mode decomposition
        val betas = DoubleArray(721) { BETA_LO + it * 0.5 } // arange(-180, 180+eps, 0.5)
        val spec = spectrum(t, onsets, betas, MATCH_WIN)
        val baseline = median(spec)
        val iGlobal = argMax(spec)
        val betaGlobal = betas[iGlobal]
        val recGlobal = spec[iGlobal]
        var anySel = false
        for (b in betas) {
            if (abs(b) >= MIN_BETA) {
                anySel = true
                break
            }
        }
        if (!anySel) return null // "beta grid too narrow" (defensive)
        var iBest = -1
        var bestSel = Double.NEGATIVE_INFINITY
        for (i in betas.indices) {
            if (abs(betas[i]) >= MIN_BETA && spec[i] > bestSel) {
                bestSel = spec[i]
                iBest = i
            }
        }
        val afterMode = betas[iBest]
        val recBest = bestSel
        if (abs(betaGlobal) < MIN_BETA && recGlobal >= recBest) return null // single segment
        if (recBest < baseline + PEAK_MARGIN) return null // no significant peak

        val n = cues.size
        val dAfter = nearestD(DoubleArray(n) { t[it] + afterMode }, onsets)
        val d0 = nearestD(t, onsets)
        val maM = BooleanArray(n)
        val m0 = BooleanArray(n)
        var nAfterOnly = 0
        for (i in 0 until n) {
            maM[i] = dAfter[i] <= MATCH_WIN
            m0[i] = d0[i] <= MATCH_WIN
            if (maM[i] && !m0[i]) nAfterOnly++
        }
        if (nAfterOnly < MIN_AFTER_ONLY) return null

        val baRes = modeBeta(t, onsets, maM, afterMode)
        val ba = baRes.first
        val maskB = BooleanArray(n) { m0[it] && !maM[it] }
        val bbRes = modeBeta(t, onsets, maskB, 0.0)
        var bb = if (bbRes.first.isFinite()) bbRes.first else 0.0 // minimal-shift prior
        if (!ba.isFinite()) return null // "no mode-beta for the after mode"
        var l = bb - ba
        if (l < MIN_CUT_LEN_PHYS) return null // implied cut too short

        // 3. alass-DP cut scan + split-penalty firewall
        val scan = dpCutScan(cues, t, onsets, bb, ba, l)
        if (scan.grid.isEmpty()) return null // cue span too short
        // internal no-cut baseline: one segment at beta~=0 (its excess votes)
        val prof0 = lambdaProfile(onsets, max(onsets.last(), t.last()) + 100.0)
        var base0 = 0.0
        for (i in 0 until n) {
            val lb0 = interp(t[i].coerceIn(0.0, prof0.grid.last()), prof0.grid, prof0.lam)
            val e0 = 1.0 - exp(-2.0 * MATCH_WIN * lb0)
            base0 += (if (m0[i]) 1.0 else 0.0) - e0
        }
        var sMax = Double.NEGATIVE_INFINITY
        for (s in scan.scores) if (s > sMax) sMax = s
        if (sMax - base0 < SPLIT_PENALTY) return null // DP gain below penalty

        // 4. boundary refinement inside the DP plateau
        val cutSub = locateCut(cues, scan.grid, scan.scores, scan.mb, scan.ma, scan.da, l)

        // 5. joint ANCOVA refit (guarded)
        val refit = jointRefit(cues, onsets, cutSub, l, alpha0, bb, ba)
        var alphaF = alpha0
        var bbF = bb
        var baF = ba
        if (abs(refit.first - alpha0) <= 0.004 && abs(refit.second - bb) <= 2.0 &&
            abs(refit.third - ba) <= 5.0 && refit.second - refit.third >= MIN_CUT_LEN_PHYS
        ) {
            alphaF = refit.first
            bbF = refit.second
            baF = refit.third
        }
        l = bbF - baF
        return PhysCut(alphaF, bbF, baF, cutSub, l)
    }

    // ====================================================================
    // ensemble common objective (engine_best.py)
    // ====================================================================

    /**
     * COMMON arbitration objective: the fix-2b density-normalized alass
     * excess score S(c) evaluated at the candidate's cut position.
     * Identical formula for both methods' candidates -> directly
     * comparable.
     */
    private fun modelExcess(onsets: DoubleArray, cues: DoubleArray, nCut: NormCut): Double {
        val l = nCut.betaBefore - nCut.betaAfter
        if (l < MIN_CUT_LEN_PHYS) return Double.NEGATIVE_INFINITY
        val t = DoubleArray(cues.size) { nCut.alpha * cues[it] }
        val scan = dpCutScan(cues, t, onsets, nCut.betaBefore, nCut.betaAfter, l)
        if (scan.grid.isEmpty()) return Double.NEGATIVE_INFINITY
        val i = lowerBound(scan.grid, nCut.cutSub).coerceIn(0, scan.grid.size - 1)
        return scan.scores[i]
    }

    /**
     * No-cut baseline excess: the BEST single-segment model at this alpha
     * (beta optimized over +/-150 s), same excess formula as
     * detect_cut_phys's firewall. The baseline MUST optimize beta, not
     * pin it at 0: a beta=0 baseline is artificially low whenever the
     * true lock offset is non-zero, which makes any candidate whose
     * beta_before sits near the true lock show a huge spurious gain (that
     * accepted a fix-2a hallucination on clean EP29).
     */
    private fun noCutBaseline(cues: DoubleArray, onsets: DoubleArray, alpha: Double): Double {
        val t = DoubleArray(cues.size) { alpha * cues[it] }
        val betas = DoubleArray(601) { -150.0 + it * 0.5 } // arange(-150, 150+eps, 0.5)
        val spec = spectrum(t, onsets, betas, MATCH_WIN)
        val bBest = betas[argMax(spec)]
        val d = nearestD(DoubleArray(cues.size) { t[it] + bBest }, onsets)
        val tMax = max(onsets.last(), t.last() + abs(bBest) + 100.0)
        val prof = lambdaProfile(onsets, tMax)
        var sum = 0.0
        for (i in cues.indices) {
            val lb = interp((t[i] + bBest).coerceIn(0.0, tMax), prof.grid, prof.lam)
            val e = 1.0 - exp(-2.0 * MATCH_WIN * lb)
            sum += (if (d[i] <= MATCH_WIN) 1.0 else 0.0) - e
        }
        return sum
    }
}
