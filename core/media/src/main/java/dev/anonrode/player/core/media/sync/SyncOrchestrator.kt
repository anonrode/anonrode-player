package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.SubtitleCue

/**
 * Top-level sync orchestrator — port of engine_best.sync_best, upgraded with
 * the continuous soft-envelope 2D Pearson correlator (tier 1) and hardened
 * cut firewalls.
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

    /**
     * Top-level auto-sync: attempts the continuous soft-envelope 2D correlator
     * (highest accuracy on music/dialogue alike), falling back to discrete onset matching.
     */
    fun syncWithEnvelope(
        envelope: FloatArray,
        cues: List<SubtitleCue>,
        onsets: List<Double>,
    ): Model? {
        val singleFromEnvelope = if (envelope.isNotEmpty() && cues.size >= 5) {
            SpeechCorrelator.findJointSync(envelope, cues)?.let { joint ->
                AppLog.d(
                    "SYNC_ORCH",
                    "continuous envelope candidate: alpha=${joint.alpha} beta=${joint.beta}s " +
                        "score=${joint.score} margin=${joint.margin} recall=${joint.recall} halfOk=${joint.halfOk}"
                )
                Model.Single(
                    alpha = joint.alpha,
                    beta = joint.beta,
                    recall = joint.recall,
                    margin = joint.margin,
                    halfOk = joint.halfOk,
                    path = "continuous-envelope",
                )
            }
        } else null

        val starts = cues.map { it.start }
        return resolveModel(singleFromEnvelope, onsets, starts)
    }

    /** Refuse-don't-guess: null when no model passes the confidence gates. */
    fun sync(onsets: List<Double>, cueStarts: List<Double>): Model? {
        return resolveModel(null, onsets, cueStarts)
    }

    private fun resolveModel(
        envelopeSingle: Model.Single?,
        onsets: List<Double>,
        cueStarts: List<Double>,
    ): Model? {
        val on = onsets.sorted()
        val cs = cueStarts.sorted()

        val single = envelopeSingle ?: (
            if (on.size >= 20 && cs.size >= 10) {
                SyncBest.find(on, cs)?.let {
                    Model.Single(
                        alpha = it.alpha, beta = it.beta, recall = it.recall,
                        margin = it.margin, halfOk = it.halfOk, path = it.path,
                    )
                }
            } else null
        )

        val cut = if (on.size >= 20 && cs.size >= 10) CutEnsemble.detectCut(on, cs) else null
        if (cut != null) {
            val (rec1, rec2) = CutEnsemble.recallTwoLines(
                on.toDoubleArray(), cs.toDoubleArray(),
                cut.alpha, cut.betaBefore, cut.betaAfter,
                cut.cutSub, cut.cutLen,
            )
            val singleConfident = single != null &&
                single.margin >= SHORT_CIRCUIT_MARGIN && single.halfOk

            // Critical anti-hallucination guard: if the single model was refused,
            // NEVER accept a "single-method" cut. Only accept if both detectors
            // independently agreed (confidence == "agree"), two-line recall is high (>= 0.40),
            // and it beats single-line recall by at least +0.06.
            val cutViable = if (single == null) {
                cut.confidence == "agree" && rec2 >= 0.40 && (rec2 - rec1) >= 0.06
            } else {
                !(singleConfident && rec2 <= rec1)
            }

            if (cutViable) {
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
            AppLog.d("SYNC_ORCH", "cut dropped: gates failed or did not beat single lock")
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
