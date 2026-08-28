package dev.anonrode.player.core.media.sync

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import dev.anonrode.player.core.database.MediaDatabase
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.core.media.subtitle.SubtitleDecoder
import dev.anonrode.player.core.media.subtitle.SubtitleParser
import dev.anonrode.player.core.media.subtitle.SubtitleSourceResolver
import dev.anonrode.player.core.model.SubtitleCue
import java.io.File
import kotlinx.coroutines.Dispatchers
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

        private const val MIN_RECALL = 0.35
        private const val MIN_MARGIN = 0.04
        private const val MIN_ONSETS = 20
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoUri = inputData.getString(KEY_VIDEO_URI)
        if (videoUri.isNullOrEmpty()) {
            AppLog.e("SYNC_JOB", "no video uri in input")
            return@withContext Result.failure()
        }
        AppLog.d("SYNC_JOB", "fingerprint start: $videoUri")
        val store = MediaStateStore(MediaDatabase.get(applicationContext).mediaStateDao())

        try {
            // Skip if a lock already exists for this video
            val existing = store.get(videoUri)
            if (existing != null &&
                (existing.autoSyncOffsetMs != 0L || existing.autoSyncSpeedFactor != 1f)) {
                AppLog.d("SYNC_JOB", "already locked, skipping")
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
                return@withContext Result.success()
            }

            val cues: List<SubtitleCue> = if (choice.isNotEmpty()) {
                val resolved = SubtitleSourceResolver.resolveCues(
                    applicationContext, videoUri, videoPath, choice,
                )
                if (resolved.size < 10) {
                    AppLog.d("SYNC_JOB", "choice '$choice' gave ${resolved.size} cues, skipping")
                    return@withContext Result.success()
                }
                AppLog.d("SYNC_JOB", "syncing chosen source: $choice (${resolved.size} cues)")
                resolved
            } else {
                val sub = findSidecarSubtitle(videoUri, videoPath) ?: run {
                    AppLog.d("SYNC_JOB", "no sidecar subtitle found, nothing to sync")
                    return@withContext Result.success()
                }
                val parsed = SubtitleParser.parse(sub.first, sub.second)
                if (parsed.size < 10) {
                    AppLog.d("SYNC_JOB", "too few cues (${parsed.size}), skipping")
                    return@withContext Result.success()
                }
                parsed
            }

            val starts = cues.map { it.start }.sorted()

            // fix-1 validated onset strategy: try silencedetect first (the
            // engine's reference source), fall back to the hybrid union
            // (silencedetect + Silero-VAD, dedup 50ms — the strongest
            // source on clean content AND the rescue for music-heavy /
            // silence-sparse files), then VAD-only as last resort. Every
            // source goes through the SAME confidence gates; a refused
            // source just hands over to the next one.
            val sources = OnsetExtractor(applicationContext).extractSources(videoPath)
            val candidates = mutableListOf("silencedetect" to sources.silencedetect)
            if (sources.vad.isNotEmpty()) {
                candidates.add("hybrid" to sources.hybrid)
                candidates.add("vad" to sources.vad)
            }

            var lock: LockCandidate? = null
            for ((tag, onsets) in candidates) {
                if (onsets.size < MIN_ONSETS) {
                    AppLog.d("SYNC_JOB", "$tag: too few onsets (${onsets.size})")
                    continue
                }
                lock = attemptLock(onsets, starts, tag)
                if (lock != null) break
            }

            if (lock == null) {
                AppLog.d("SYNC_JOB", "no lock from any onset source (all refused)")
                return@withContext Result.success()
            }

            store.updateAutoSync(videoUri, lock.offsetMs, lock.speed, lock.piecewise)
            AppLog.d(
                "SYNC_JOB",
                "LOCKED (${lock.tag}) uri=$videoUri offset=${lock.offsetMs}ms " +
                    "speed=${lock.speed} recall=${"%.2f".format(lock.recall)}"
            )
            Result.success()
        } catch (t: Throwable) {
            AppLog.e("SYNC_JOB", "fingerprint failed", t)
            // Each attempt is a full MediaCodec decode + Silero VAD pass; a
            // poisoned file must not retry forever on exponential backoff.
            if (runAttemptCount >= 3) Result.failure() else Result.retry()
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
            is SyncOrchestrator.Model.Single -> LockCandidate(
                offsetMs = (model.beta * 1000).toLong(),
                speed = model.alpha.toFloat(),
                piecewise = "",
                recall = model.recall,
                tag = "$tag/${model.path}",
            )
            is SyncOrchestrator.Model.Cut -> LockCandidate(
                offsetMs = (model.betaBefore * 1000).toLong(),
                speed = model.alpha.toFloat(),
                piecewise = SyncFinder.piecewiseToStorage(
                    model.cutAudio, model.betaBefore, model.betaAfter,
                ),
                recall = model.recallTwo,
                tag = "$tag/cut-${model.confidence}",
            )
        }
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
