package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.media.log.AppLog

/**
 * Top-level sync orchestrator — port of engine_best.sync_best.
 *
 *   single-segment fitter (SyncBest.find, the consolidated fix-3a/fix-3b
 *   short-circuit fitter) -> cut check ALWAYS runs (CutEnsemble.detectCut,
 *   the fix-2a + fix-2b agreement-gated ensemble) -> two-segment model if
 *   a cut is confirmed, else the single-segment model; null (refuse) if
 *   the gates fail.
 *
 * The cut check ALWAYS runs because on a real cut the single-segment
 * fitter can lock the dominant segment CONFIDENTLY (the other segment's
 * cues simply never match), leaving cross-half clean — measured on
 * cut90@300, where the lock sits at beta=-89.9 with margin 0.23 and
 * half_ok True. The ensemble's no-cut firewalls (both detectors +
 * agreement / common-objective arbitration) are the hallucination guard;
 * defensively, a confirmed cut is still dropped when it does not improve
 * two-line recall over a confident single-segment lock.
 *
 * Note: engine_best's sync_best also runs the 10-chunk piecewise_check
 * and reports chunks/jumps in its output, but nothing in its decision
 * logic depends on them (they are diagnostics) — omitted here to keep
 * the on-device pass lean. The decision semantics are identical.
 */
object SyncOrchestrator {

    sealed class Model {
        /** Single affine segment: audio_time = alpha * subtitle_time + beta. */
        data class Single(
            val alpha: Double,
            val beta: Double,
            val recall: Double,
            val margin: Double,
            val halfOk: Boolean,
            val path: String,
        ) : Model()

        /** Two affine segments split by a cut at [cutSub] (subtitle time). */
        data class Cut(
            val alpha: Double,
            val betaBefore: Double,
            val betaAfter: Double,
            val cutSub: Double,
            val cutLen: Double,
            val cutAudio: Double,
            val confidence: String,
            val recallOne: Double,
            val recallTwo: Double,
            val single: Single?,
        ) : Model()
    }

    private const val SHORT_CIRCUIT_MARGIN = 0.06

    /** Refuse-don't-guess: null when no model passes the confidence gates. */
    fun sync(onsets: List<Double>, cueStarts: List<Double>): Model? {
        if (onsets.size < 20 || cueStarts.size < 10) return null
        val on = onsets.sorted()
        val cs = cueStarts.sorted()

        val single = SyncBest.find(on, cs)?.let {
            Model.Single(
                alpha = it.alpha, beta = it.beta, recall = it.recall,
                margin = it.margin, halfOk = it.halfOk, path = it.path,
            )
        }

        val cut = CutEnsemble.detectCut(on, cs)
        if (cut != null) {
            val (rec1, rec2) = CutEnsemble.recallTwoLines(
                on.toDoubleArray(), cs.toDoubleArray(),
                cut.alpha, cut.betaBefore, cut.betaAfter,
                cut.cutSub, cut.cutLen,
            )
            val singleConfident = single != null &&
                single.margin >= SHORT_CIRCUIT_MARGIN && single.halfOk
            if (!(singleConfident && rec2 <= rec1)) {
                AppLog.d(
                    "SYNC_ORCH",
                    "cut accepted: conf=${cut.confidence} cutSub=${cut.cutSub} " +
                        "bb=${cut.betaBefore} ba=${cut.betaAfter} rec1=$rec1 rec2=$rec2"
                )
                return Model.Cut(
                    alpha = cut.alpha,
                    betaBefore = cut.betaBefore,
                    betaAfter = cut.betaAfter,
                    cutSub = cut.cutSub,
                    cutLen = cut.cutLen,
                    cutAudio = cut.cutAudio,
                    confidence = cut.confidence,
                    recallOne = rec1,
                    recallTwo = rec2,
                    single = single,
                )
            }
            AppLog.d("SYNC_ORCH", "cut dropped: two-line recall did not beat confident single lock")
        }

        if (single == null) {
            AppLog.d("SYNC_ORCH", "refused: no single-segment lock passed the gates")
            return null
        }
        AppLog.d(
            "SYNC_ORCH",
            "single lock: path=${single.path} a=${single.alpha} b=${single.beta} " +
                "recall=${single.recall} margin=${single.margin}"
        )
        return single
    }
}
