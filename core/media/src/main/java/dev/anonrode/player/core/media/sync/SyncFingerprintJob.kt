package dev.anonrode.player.core.media.sync

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import dev.anonrode.player.core.database.MediaDatabase
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import dev.anonrode.player.core.media.subtitle.SubtitleDecoder
import dev.anonrode.player.core.media.subtitle.SubtitleParser
import dev.anonrode.player.core.media.subtitle.SubtitleSourceResolver
import dev.anonrode.player.core.model.SubtitleCue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Background subtitle auto-sync fingerprint — the ffsubsync-style pass
 * that produces the persisted (alpha, beta) lock used on the next play.
 *
 * Pipeline (mirrors tools/engine_test_v2.py, validated 3/3 on real
 * Growling Tiger 2 audio):
 *   1. resolve the video's real file path from its content URI
 *   2. resolve the subtitle source exactly as playback does
 *      (SubtitleSourceResolver — same picker, same parsing)
 *   3. parse cues, extract speech onsets (ffmpeg silencedetect, streaming)
 *   4. SyncFinder joint (alpha, beta) search + LSQ refit + gates
 *   5. persist the lock to Room via MediaStateStore
 *
 * Only stores a result that passes the confidence gates — a weak or
 * ambiguous match is discarded (original subs stay untouched) instead
 * of locking garbage.
 */
class SyncFingerprintJob(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_VIDEO_URI = "video_uri"

        /** "Resync now": re-fingerprint even when a lock is already persisted. */
        const val KEY_FORCE = "force"

        private const val MIN_RECALL = 0.35
        private const val MIN_MARGIN = 0.04
        private const val MIN_ONSETS = 20

        /**
         * v0.8.2: process-wide decoder gate. One whole-file decode at a
         * time — WorkManager happily runs enqueued workers in parallel and
         * concurrent MediaCodec+Silero passes starve each other (the
         * 09-15 log: three episodes' fingerprints started within 3 minutes,
         * none finished by minute 8). A busy gate means an immediate
         * Result.retry(), not a blocked worker thread.
         */
        private val DECODE_GATE = java.util.concurrent.Semaphore(1)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Re-check the live setting on EVERY attempt: a job enqueued while
        // the sub-sync toggle was on keeps retrying across process
        // restarts, and the user may have switched it off since. Without
        // this check the full-file decode runs forever against the user's
        // explicit choice. Returning success() terminates the retry
        // chain for good. v0.6.2: the gate is the user-facing toggle
        // [PlayerSettings.subtitleAutoSyncEnabled] (default OFF), NOT the
        // legacy [PlayerSettings.autoSyncEnabled] — the legacy gate now
        // only governs the MKV-embedded fast path (see PlayerActivity).
        val syncEnabled = try {
            applicationContext.playerSettingsDataStore.data.first().subtitleAutoSyncEnabled
        } catch (e: Exception) {
            false
        }
        if (!syncEnabled) {
            AppLog.d("SYNC_JOB", "sub-sync toggle off — dropping fingerprint job")
            return@withContext Result.success()
        }

        val videoUri = inputData.getString(KEY_VIDEO_URI)
        if (videoUri.isNullOrEmpty()) {
            AppLog.e("SYNC_JOB", "no video uri in input")
            return@withContext Result.failure()
        }
        // v0.8.2: ONE whole-file decode at a time. The 09-15 device log
        // shows three fingerprint jobs running CONCURRENTLY (three episodes
        // opened back to back), each holding a MediaCodec decoder plus the
        // Silero ONNX pass — three parallel ~20-minute decodes saturating a
        // mid-range SoC, so no episode got its lock fast and the "Syncing…"
        // chip lied about all of them. WorkManager happily runs queued
        // workers in parallel and offers no per-job concurrency knob, so the
        // worker enforces it with a process-wide semaphore: a job that
        // finds the gate busy returns Result.retry() immediately — WorkManager
        // re-delivers it after its backoff (default 10 min; the 09-15 log's
        // own start-to-start gaps show that cadence), and by then the
        // preceding decode has usually finished. Never block: WorkManager's
        // executor is small and shared with the app's other work.
        if (!DECODE_GATE.tryAcquire()) {
            AppLog.d("SYNC_JOB", "another decode holds the gate, retrying")
            return@withContext Result.retry()
        }
        AppLog.d("SYNC_JOB", "fingerprint start: $videoUri")
        val store = MediaStateStore(MediaDatabase.get(applicationContext).mediaStateDao())

        try {
            // v0.8 P1-7 trust rule: what blocks a re-fit is the engine's
            // own VERDICT (auto_sync_checked_at_ms), not a stored lock —
            // live locks also write auto_sync_offset_ms, and the whole-file
            // engine (validated on real content) must be allowed to run
            // and SUPERSEDE a two-agreeing-evals live value, not defer to
            // it forever. A forced "Resync now" re-fits regardless.
            val forced = inputData.getBoolean(KEY_FORCE, false)
            val existing = store.get(videoUri)
            if (!forced && existing != null && existing.autoSyncCheckedAtMs != 0L) {
                AppLog.d("SYNC_JOB", "fingerprint verdict already recorded, skipping")
                return@withContext Result.success()
            }

            val videoPath = resolveVideoPath(videoUri) ?: run {
                AppLog.e("SYNC_JOB", "cannot resolve path for $videoUri")
                return@withContext Result.failure()
            }
            val videoFile = File(videoPath)
            if (!videoFile.isFile) {
                AppLog.e("SYNC_JOB", "video file missing: $videoPath")
                return@withContext Result.failure()
            }

            // Subtitle source: the picker's explicit choice wins (embedded
            // track / online download / named sidecar); empty choice keeps
            // the legacy best-sidecar auto-pick.
            val choice = existing?.subtitleChoice.orEmpty()
            if (choice == "none") {
                AppLog.d("SYNC_JOB", "subtitles disabled for this video, nothing to sync")
                // v0.7.4 P0-1: this is a verdict about this video's current
                // state — record it so the player's auto-schedule stops
                // re-enqueuing a 90 s-delayed no-op on every open. Changing
                // the subtitle choice clears the mark; "Resync now" ignores
                // it.
                store.markAutoSyncChecked(videoUri)
                return@withContext Result.success()
            }

            val cues: List<SubtitleCue> = if (choice.isNotEmpty()) {
                val resolved = SubtitleSourceResolver.resolveCues(
                    applicationContext, videoUri, videoPath, choice,
                )
                if (resolved.size < 10) {
                    AppLog.d("SYNC_JOB", "choice '$choice' gave ${resolved.size} cues, skipping")
                    store.markAutoSyncChecked(videoUri)
                    return@withContext Result.success()
                }
                AppLog.d("SYNC_JOB", "syncing chosen source: $choice (${resolved.size} cues)")
                resolved
            } else {
                // AUTO path — resolve exactly the way playback does (see the
                // deferred parser in the player): a picked sidecar first,
                // else the first embedded text track. Without the embedded
                // fallback an MKV with muxed subs (the common release
                // format) had NO route to the engine at all — the old code
                // bailed here with "no sidecar subtitle found", so even a
                // forced "Resync now" was a silent no-op for embedded-only
                // videos, leaving the (unvalidated) live analyser as their
                // only possible sync path.
                val sub = findSidecarSubtitle(videoUri, videoPath)
                if (sub != null) {
                    val parsed = SubtitleParser.parse(sub.first, sub.second)
                    if (parsed.size < 10) {
                        AppLog.d("SYNC_JOB", "too few cues (${parsed.size}), skipping")
                        store.markAutoSyncChecked(videoUri)
                        return@withContext Result.success()
                    }
                    parsed
                } else {
                    var probeFailed = false
                    val embedded: List<SubtitleCue> = try {
                        val tracks = SubtitleSourceResolver.listEmbedded(
                            applicationContext, videoUri, videoPath,
                        )
                        if (tracks.isEmpty()) emptyList()
                        else SubtitleSourceResolver.resolveCues(
                            applicationContext, videoUri, videoPath,
                            "embedded:${tracks.first().index}",
                        ).sortedBy { it.start }
                    } catch (t: Throwable) {
                        probeFailed = true
                        AppLog.e("SYNC_JOB", "embedded subtitle probe failed", t)
                        emptyList()
                    }
                    if (embedded.size < 10) {
                        AppLog.d("SYNC_JOB", "no sidecar and no embedded track, nothing to sync")
                        // v0.7.4 P0-1: a DEFINITE "this video has no usable
                        // subtitle source" verdict is marked checked like any
                        // other, so the every-open 90 s-delayed no-op enqueue
                        // storm ends (a sidecarless file can never sync by
                        // definition — the live engine has nothing either).
                        // A PROBE FAILURE is not a verdict — leave it
                        // unmarked so the next open retries. Recovery when a
                        // sidecar appears later: the subtitle-choice setter
                        // clears the mark, and "Resync now" ignores it.
                        if (!probeFailed) store.markAutoSyncChecked(videoUri)
                        return@withContext Result.success()
                    }
                    AppLog.d("SYNC_JOB", "syncing embedded track (${embedded.size} cues)")
                    embedded
                }
            }

            val starts = cues.map { it.start }.sorted()

            // fix-1 validated onset strategy: try silencedetect first (the
            // engine's reference source), fall back to the hybrid union
            // (silencedetect + Silero-VAD, dedup 50ms — the strongest
            // source on clean content AND the rescue for music-heavy /
            // silence-sparse files), then VAD-only as last resort. Every
            // source goes through the SAME confidence gates; a refused
            // source just hands over to the next one.
            // v0.8 P2-2/P1-5: the decode budget scales with the video's
            // duration (the fixed 600 s cap truncated long movies on slow
            // SoCs and the partial onset set then "failed the gates" — a
            // false verdict), and the MediaCodec fallback can open the
            // content URI itself when the resolved path can't be read.
            //
            // v0.8.3 ACCURACY: extraction is now RESUMABLE via OnsetCache.
            // The 09-15 device log shows each budget-truncated attempt
            // re-decoding from zero (and never finishing the file), plus a
            // process-death cancellation throwing away a completed
            // extraction — locks were fitted on 24-44% of the episode and
            // the slope extrapolated over the rest, the proven source of
            // "it locks then drifts". Attempt k now resumes at the media
            // time attempt k-1 reached, and the verdict is fitted on the
            // union — 100% of the audio across a few resumable passes.
            val cached = OnsetCache.load(applicationContext, videoUri, videoFile)
            val extractor = OnsetExtractor(applicationContext)
            extractor.decodeTimeoutMs = decodeBudgetMs(videoUri, videoPath)
            val sources: OnsetExtractor.OnsetSources
            val extractionComplete: Boolean
            if (cached != null && cached.complete) {
                AppLog.d(
                    "SYNC_JOB",
                    "onset cache complete (${cached.silencedetect.size}+${cached.vad.size}) — skipping decode",
                )
                sources = cached.asSources()
                extractionComplete = true
            } else {
                val resumeFrom = cached?.coveredSec ?: -1.0
                if (resumeFrom > 0.0) {
                    AppLog.d(
                        "SYNC_JOB",
                        "resuming decode at %.0fs (cached %.0f+%.0f onsets)".format(
                            resumeFrom,
                            cached?.silencedetect?.size?.toDouble() ?: 0.0,
                            cached?.vad?.size?.toDouble() ?: 0.0,
                        ),
                    )
                }
                val fresh = extractor.extractSources(videoPath, Uri.parse(videoUri), resumeFrom)
                extractionComplete = !extractor.lastDecodeTruncated
                sources = if (resumeFrom > 0.0 && cached != null) {
                    OnsetExtractor.OnsetSources(
                        silencedetect = OnsetCache.mergeOnsets(cached.silencedetect, fresh.silencedetect),
                        vad = OnsetCache.mergeOnsets(cached.vad, fresh.vad),
                        envelope = OnsetCache.mergeEnvelope(cached.envelope, fresh.envelope, resumeFrom),
                    )
                } else {
                    fresh
                }
                OnsetCache.store(
                    applicationContext, videoUri, videoFile,
                    OnsetCache.Entry(
                        silencedetect = sources.silencedetect,
                        vad = sources.vad,
                        envelope = sources.envelope,
                        coveredSec = extractor.lastCoveredSec,
                        complete = extractionComplete,
                    ),
                )
            }

            var lock: LockCandidate? = null

            // Tier 1: Continuous soft-envelope 2D Pearson correlator (immune to onset sparsity in music)
            if (sources.envelope.isNotEmpty() && cues.size >= 5) {
                val model = SyncOrchestrator.syncWithEnvelope(sources.envelope, cues, sources.hybrid)
                if (model != null) {
                    lock = toLockCandidate(model, "envelope")
                    AppLog.d(
                        "SYNC_JOB",
                        "Tier 1 envelope lock: offset=${lock.offsetMs}ms speed=${lock.speed} piecewise=${lock.piecewise} recall=${lock.recall}"
                    )
                }
            }

            // Tier 2: Discrete onset candidate loop (fallback if envelope refused)
            val candidates = mutableListOf("silencedetect" to sources.silencedetect)
            if (sources.vad.isNotEmpty()) {
                candidates.add("hybrid" to sources.hybrid)
                candidates.add("vad" to sources.vad)
            }

            var fitsAttempted = 0
            if (lock == null) {
                for ((tag, onsets) in candidates) {
                    if (onsets.size < MIN_ONSETS) {
                        AppLog.d("SYNC_JOB", "$tag: too few onsets (${onsets.size})")
                        continue
                    }
                    fitsAttempted++
                    lock = attemptLock(onsets, starts, tag)
                    if (lock != null) break
                }
            }

            if (lock == null) {
                // v0.8 P2-2 (v0.8.3: cache-resumed): a decode that stopped
                // at the budget is NOT a verdict about the video — the
                // onset set was cut short, so the gates refusing it proves
                // nothing. Keep retrying while extraction can still grow
                // (each retry RESUMES from the cached prefix, so a slow
                // SoC finishes the file in a few passes instead of never),
                // and only on the last attempt fall through to the
                // checked-mark like any other no-lock outcome.
                if (!extractionComplete && runAttemptCount < 3) {
                    AppLog.d(
                        "SYNC_JOB",
                        "no lock AND decode was truncated at budget — resuming extraction, no verdict",
                    )
                    return@withContext Result.retry()
                }
                AppLog.d(
                    "SYNC_JOB",
                    "no lock (fits attempted: $fitsAttempted/${candidates.size} sources" +
                        (if (extractionComplete) "" else ", STILL truncated after ${runAttemptCount + 1} passes") +
                        ")",
                )
                // This is a verdict about the video's own data (the gates
                // refused every usable source, or there was too little
                // detectable speech), not a transient failure: record it so
                // the player stops re-scheduling a whole-file decode on
                // every open. A forced "Resync now" re-fits regardless.
                store.markAutoSyncChecked(videoUri)
                return@withContext Result.success()
            }

            store.updateAutoSync(videoUri, lock.offsetMs, lock.speed, lock.piecewise)
            // v0.8.3: a lock fitted on a TRUNCATED extraction is persisted
            // (better than nothing, the player applies it immediately) but
            // is NOT the final word — the checked mark stays off, so the
            // next attempt resumes extraction and refits (or the player
            // re-schedules this same owed verdict on the next open if the
            // retry chain dies). Only a fit on complete audio, or the last
            // allowed attempt, is a verdict.
            if (!extractionComplete && runAttemptCount < 3) {
                AppLog.d(
                    "SYNC_JOB",
                    "LOCKED (provisional, ${lock.tag}) uri=$videoUri — extraction truncated, resuming to refit",
                )
                return@withContext Result.retry()
            }
            // The verdict is final for this video (a lock now exists, so the
            // player's schedule gate stops anyway) — mark it explicitly so
            // the two gates can never disagree.
            store.markAutoSyncChecked(videoUri)
            AppLog.d(
                "SYNC_JOB",
                "LOCKED${if (forced) " (forced)" else ""} (${lock.tag}) uri=$videoUri " +
                    "offset=${lock.offsetMs}ms speed=${lock.speed} " +
                    "recall=${"%.2f".format(lock.recall)}"
            )
            Result.success()
        } catch (t: Throwable) {
            AppLog.e("SYNC_JOB", "fingerprint failed", t)
            // Each attempt is a full MediaCodec decode + Silero VAD pass; a
            // poisoned file must not retry forever on exponential backoff.
            if (runAttemptCount >= 3) {
                // v0.7.4 P1-5: this was the LAST allowed attempt — without
                // the checked mark the player's Policy A would re-enqueue
                // this entire doomed 3-attempt chain on every single open
                // of the video. Record the giving-up verdict; editing the
                // subtitle choice or "Resync now" re-arms/re-ignores it.
                try { store.markAutoSyncChecked(videoUri) } catch (_: Throwable) {}
                Result.failure()
            } else Result.retry()
        } finally {
            DECODE_GATE.release()
        }
    }

    private data class LockCandidate(
        val offsetMs: Long,
        val speed: Float,
        val piecewise: String,
        val recall: Double,
        val tag: String,
    )

    /**
     * Run one onset source through the consolidated engine
     * ([SyncOrchestrator.sync] = engine_best.sync_best: short-circuit
     * fitter + always-run cut ensemble, every confidence gate internal).
     * Returns null when the source is refused so the caller can try the
     * next source; never returns an ungated lock.
     */
    private fun attemptLock(
        onsets: List<Double>,
        starts: List<Double>,
        tag: String,
    ): LockCandidate? {
        val model = SyncOrchestrator.sync(onsets, starts) ?: run {
            AppLog.d("SYNC_JOB", "$tag: engine refused (gates)")
            return null
        }
        return when (model) {
            is SyncOrchestrator.Model.Single -> {
                // Deadband snapping: if framerate drift is nominal (alpha == 1.0)
                // and offset is within human subtitle pre-roll lead-time / calculation lag
                // (|beta| <= 0.20s / 200ms), snap to 0L so already well-synced subtitles
                // are preserved with pristine original timing.
                val effectiveOffsetMs = if (kotlin.math.abs(model.alpha - 1.0) <= 0.0005 && kotlin.math.abs(model.beta) <= 0.20) {
                    0L
                } else {
                    (model.beta * 1000).toLong()
                }
                LockCandidate(
                    offsetMs = effectiveOffsetMs,
                    speed = model.alpha.toFloat(),
                    piecewise = "",
                    recall = model.recall,
                    tag = "$tag/${model.path}",
                )
            }
            is SyncOrchestrator.Model.Cut -> {
                val bb = if (kotlin.math.abs(model.alpha - 1.0) <= 0.0005 && kotlin.math.abs(model.betaBefore) <= 0.20) 0.0 else model.betaBefore
                val ba = if (kotlin.math.abs(model.alpha - 1.0) <= 0.0005 && kotlin.math.abs(model.betaAfter) <= 0.20) 0.0 else model.betaAfter
                LockCandidate(
                    offsetMs = (bb * 1000).toLong(),
                    speed = model.alpha.toFloat(),
                    piecewise = SyncFinder.piecewiseToStorage(
                        model.cutAudio, bb, ba,
                    ),
                    recall = model.recallTwo,
                    tag = "$tag/cut-${model.confidence}",
                )
            }
        }
    }

    /**
     * v0.8 P2-2: caller-scaled decode budget. Floor is the old fixed
     * 600 s; every ms of video adds 1/8 ms of budget (≈8× realtime assumed
     * audio decode, half the claimed 10-20x worst-case margin), ceiling
     * 25 min so a corrupt duration can never hang the worker.
     */
    private fun decodeBudgetMs(videoUri: String, videoPath: String): Long {
        val durMs = try {
            val mmr = MediaMetadataRetriever()
            try {
                if (File(videoPath).canRead()) mmr.setDataSource(videoPath)
                else mmr.setDataSource(applicationContext, Uri.parse(videoUri))
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                mmr.release()
            }
        } catch (t: Throwable) {
            AppLog.d("SYNC_JOB", "duration probe failed, using budget floor")
            0L
        }
        return (600_000L + durMs / 8).coerceAtMost(1_500_000L)
    }

    /** content:// video URI → real file path (MediaStore DATA column). */
    private fun resolveVideoPath(videoUri: String): String? {
        val uri = Uri.parse(videoUri)
        if (uri.scheme == "file") return uri.path
        return try {
            applicationContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DATA),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (t: Throwable) {
            AppLog.e("SYNC_JOB", "query DATA failed", t)
            null
        }
    }

    /**
     * Find the auto-picked sidecar subtitle for the video. Delegates to the
     * SAME score-based picker playback uses
     * ([SubtitleSourceResolver.pickAutoSidecar] — the canonical
     * SubtitleMatcher-scored AUTO pick), then decodes the chosen file's
     * bytes — so with 2+ sidecars the persisted lock can never be fitted to
     * a different file than the one rendered. Returns (fileName, text) of
     * the pick, or null when there is none.
     */
    private fun findSidecarSubtitle(videoUri: String, videoPath: String): Pair<String, String>? {
        val sidecar = SubtitleSourceResolver.pickAutoSidecar(applicationContext, videoPath) ?: return null
        val text = try {
            applicationContext.contentResolver.openInputStream(sidecar.uri)
                ?.use { SubtitleDecoder.decode(it.readBytes(), sidecar.name) }
        } catch (t: Throwable) {
            AppLog.e("SYNC_JOB", "sidecar read failed: ${sidecar.name}", t)
            null
        } ?: return null
        AppLog.d("SYNC_JOB", "sidecar: ${sidecar.name}")
        return sidecar.name to text
    }
}
