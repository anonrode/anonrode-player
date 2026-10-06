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
import kotlinx.coroutines.isActive
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
        if (!DECODE_GATE.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS)) {
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
            val hasValidOffset = existing?.autoSyncOffsetMs != null && existing.autoSyncOffsetMs != 0L
            if (!forced && existing != null && existing.autoSyncCheckedAtMs != 0L && hasValidOffset) {
                AppLog.d("SYNC_JOB", "fingerprint verdict already recorded (${existing.autoSyncOffsetMs}ms), skipping")
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
            var sources: OnsetExtractor.OnsetSources = OnsetExtractor.OnsetSources(emptyList(), emptyList())
            var extractionComplete: Boolean = false
            var lock: LockCandidate? = null
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
                val initialSpanSec = if (resumeFrom <= 0.0) 600.0 else 300.0

                // ── Stage 0: Cue-Guided Anchor Search (fast 90s seek) ──
                var anchorLock: LockCandidate? = null
                if (cached == null && starts.size >= 12) {
                    val firstCueTime = starts.first()
                    val anchorStart = (firstCueTime - 5.0).coerceAtLeast(0.0)
                    val anchorSpan = 90.0
                    AppLog.d("SYNC_JOB", "Stage 0 anchor probe at %.1fs (span %.0fs)".format(anchorStart, anchorSpan))
                    val anchorPass = extractor.extractSources(
                        videoPath,
                        Uri.parse(videoUri),
                        resumeFromSec = anchorStart,
                        maxMediaDurationSec = anchorSpan,
                        includeVad = false,
                        isCancelled = { !coroutineContext.isActive || isStopped },
                    )
                    val anchorStarts = starts.filter { it in anchorStart..(anchorStart + anchorSpan + 60.0) }
                    if (anchorPass.silencedetect.size >= 8 && anchorStarts.size >= 8) {
                        val candidate = attemptLock(anchorPass.silencedetect, anchorStarts, "cue-anchor")
                        if (candidate != null && candidate.recall >= 0.65) {
                            sources = anchorPass
                            extractionComplete = false
                            val durMs = getVideoDurationMs(videoUri, videoPath)
                            val pw = buildMultiSpotPiecewise(
                                extractor, videoPath, videoUri, cues,
                                candidate.offsetMs / 1000.0, durMs,
                                isCancelled = { !coroutineContext.isActive || isStopped }
                            ).ifEmpty { candidate.piecewise }

                            val lockCandidate = candidate.copy(piecewise = pw)
                            lock = lockCandidate
                            withContext(kotlinx.coroutines.NonCancellable) {
                                store.updateAutoSync(videoUri, lockCandidate.offsetMs, lockCandidate.speed, lockCandidate.piecewise)
                            }
                            OnsetCache.store(
                                applicationContext, videoUri, videoFile,
                                OnsetCache.Entry(
                                    silencedetect = sources.silencedetect,
                                    vad = emptyList(),
                                    envelope = FloatArray(0),
                                    coveredSec = extractor.lastCoveredSec,
                                    complete = false,
                                ),
                            )
                            AppLog.d(
                                "SYNC_JOB",
                                "Stage 0 CUE-ANCHOR FAST LOCKED in <1s: uri=$videoUri offset=${lockCandidate.offsetMs}ms speed=${lockCandidate.speed} piecewise=${lockCandidate.piecewise} recall=${lockCandidate.recall}"
                            )
                            return@withContext Result.success()
                        }
                    }
                }

                // Stage 1: Fast silencedetect pass first (MediaCodec @ 200x realtime, ~2.5s on device)
                val fastPass = if (cached != null && cached.silencedetect.isNotEmpty()) {
                    cached.asSources()
                } else {
                    extractor.extractSources(
                        videoPath,
                        Uri.parse(videoUri),
                        resumeFrom,
                        maxMediaDurationSec = initialSpanSec,
                        includeVad = false,
                        isCancelled = { !coroutineContext.isActive || isStopped },
                    )
                }
                val isFastCancelled = !coroutineContext.isActive || isStopped
                if (isFastCancelled) {
                    OnsetCache.store(
                        applicationContext, videoUri, videoFile,
                        OnsetCache.Entry(
                            silencedetect = fastPass.silencedetect,
                            vad = emptyList(),
                            envelope = FloatArray(0),
                            coveredSec = extractor.lastCoveredSec,
                            complete = extractor.isEndOfStream,
                        ),
                    )
                    AppLog.d("SYNC_JOB", "extraction cancelled/stopped by WorkManager — cache saved, retrying")
                    return@withContext Result.retry()
                }

                // Check if fast silencedetect can lock immediately on the covered horizon
                val fastHorizonSec = extractor.lastCoveredSec + 60.0
                val fastTierStarts = if (extractor.isEndOfStream) starts else starts.filter { it <= fastHorizonSec }
                var fastLock: LockCandidate? = null
                if (fastPass.silencedetect.size >= MIN_ONSETS && fastTierStarts.size >= 10) {
                    fastLock = attemptLock(fastPass.silencedetect, fastTierStarts, "silencedetect-fast")
                }

                if (fastLock != null) {
                    sources = fastPass
                    extractionComplete = extractor.isEndOfStream
                    val durMs = getVideoDurationMs(videoUri, videoPath)
                    val pw = buildMultiSpotPiecewise(
                        extractor, videoPath, videoUri, cues,
                        fastLock.offsetMs / 1000.0, durMs,
                        isCancelled = { !coroutineContext.isActive || isStopped }
                    ).ifEmpty { fastLock.piecewise }

                    val lockCandidate = fastLock.copy(piecewise = pw)
                    lock = lockCandidate
                    OnsetCache.store(
                        applicationContext, videoUri, videoFile,
                        OnsetCache.Entry(
                            silencedetect = sources.silencedetect,
                            vad = emptyList(),
                            envelope = FloatArray(0),
                            coveredSec = extractor.lastCoveredSec,
                            complete = extractionComplete,
                        ),
                    )
                    withContext(kotlinx.coroutines.NonCancellable) {
                        store.updateAutoSync(videoUri, lockCandidate.offsetMs, lockCandidate.speed, lockCandidate.piecewise)
                    }
                    AppLog.d(
                        "SYNC_JOB",
                        "Tier 1 FAST LOCKED in 2s: uri=$videoUri offset=${lockCandidate.offsetMs}ms speed=${lockCandidate.speed} piecewise=${lockCandidate.piecewise} recall=${lockCandidate.recall}"
                    )
                } else {
                    // Stage 2: Silero VAD fallback (extracts full neural envelope and hybrid onsets)
                    val fresh = extractor.extractSources(
                        videoPath,
                        Uri.parse(videoUri),
                        resumeFrom,
                        maxMediaDurationSec = initialSpanSec,
                        includeVad = true,
                        isCancelled = { !coroutineContext.isActive || isStopped },
                    )
                    sources = if (resumeFrom > 0.0 && cached != null) {
                        OnsetExtractor.OnsetSources(
                            silencedetect = OnsetCache.mergeOnsets(cached.silencedetect, fresh.silencedetect),
                            vad = OnsetCache.mergeOnsets(cached.vad, fresh.vad),
                            envelope = OnsetCache.mergeEnvelope(cached.envelope, fresh.envelope, resumeFrom),
                        )
                    } else {
                        fresh
                    }
                    val isJobCancelled = !coroutineContext.isActive || isStopped
                    extractionComplete = extractor.isEndOfStream
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
                    if (isJobCancelled) {
                        AppLog.d("SYNC_JOB", "extraction cancelled/stopped by WorkManager — cache saved, retrying")
                        return@withContext Result.retry()
                    }
                }
            }

            // Align cue horizon to covered audio span so recall denominator is honest
            val coveredSec = extractor.lastCoveredSec
            val horizonSec = if (extractionComplete) Double.MAX_VALUE else (coveredSec + 60.0)
            val tierCues = if (extractionComplete) cues else cues.filter { it.start <= horizonSec }
            val tierStarts = if (extractionComplete) starts else starts.filter { it <= horizonSec }

            // Tier 1: Continuous soft-envelope 2D Pearson correlator (immune to onset sparsity in music)
            if (lock == null && sources.envelope.isNotEmpty() && tierCues.size >= 5) {
                val model = SyncOrchestrator.syncWithEnvelope(sources.envelope, tierCues, sources.hybrid)
                if (model != null) {
                    val candidate = toLockCandidate(model, "envelope")
                    lock = candidate
                    AppLog.d(
                        "SYNC_JOB",
                        "Tier 1 envelope lock: offset=${candidate.offsetMs}ms speed=${candidate.speed} piecewise=${candidate.piecewise} recall=${candidate.recall}"
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
                    lock = attemptLock(onsets, tierStarts, tag)
                    if (lock != null) break
                }
            }

            // Commit initial lock immediately so the player syncs subtitles within seconds
            val initialLock = lock
            if (initialLock != null && !extractionComplete) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    store.updateAutoSync(videoUri, initialLock.offsetMs, initialLock.speed, initialLock.piecewise)
                }
                AppLog.d(
                    "SYNC_JOB",
                    "Tier 1 LOCKED: uri=$videoUri offset=${initialLock.offsetMs}ms speed=${initialLock.speed} recall=${initialLock.recall}"
                )
            }

            // Tier 2: Progressive background crawler (runs in lazy 300s chunks ahead of playback)
            var activeSources = sources
            var activePiecewise = lock?.piecewise.orEmpty()
            var activeBeta = (lock?.offsetMs ?: 0L) / 1000.0
            var activeAlpha = lock?.speed?.toDouble() ?: 1.0
            var isComplete = extractionComplete

            while (!isComplete && coroutineContext.isActive && !isStopped) {
                kotlinx.coroutines.delay(4000L)
                if (!coroutineContext.isActive || isStopped) break

                val chunkResumeFrom = extractor.lastCoveredSec
                val chunk = extractor.extractSources(
                    videoPath,
                    Uri.parse(videoUri),
                    resumeFromSec = chunkResumeFrom,
                    maxMediaDurationSec = 300.0,
                    isCancelled = { !coroutineContext.isActive || isStopped },
                )
                val atEof = extractor.isEndOfStream
                if (atEof) {
                    isComplete = true
                }

                if (chunk.hybrid.isEmpty() && chunk.envelope.isEmpty()) {
                    if (atEof) {
                        OnsetCache.store(
                            applicationContext, videoUri, videoFile,
                            OnsetCache.Entry(
                                silencedetect = activeSources.silencedetect,
                                vad = activeSources.vad,
                                envelope = activeSources.envelope,
                                coveredSec = extractor.lastCoveredSec,
                                complete = true,
                            ),
                        )
                        break
                    }
                    continue
                }

                activeSources = OnsetExtractor.OnsetSources(
                    silencedetect = OnsetCache.mergeOnsets(activeSources.silencedetect, chunk.silencedetect),
                    vad = OnsetCache.mergeOnsets(activeSources.vad, chunk.vad),
                    envelope = OnsetCache.mergeEnvelope(activeSources.envelope, chunk.envelope, chunkResumeFrom),
                )
                OnsetCache.store(
                    applicationContext, videoUri, videoFile,
                    OnsetCache.Entry(
                        silencedetect = activeSources.silencedetect,
                        vad = activeSources.vad,
                        envelope = activeSources.envelope,
                        coveredSec = extractor.lastCoveredSec,
                        complete = atEof,
                    ),
                )

                // If initial Tier 1 was deferred due to sparse opening speech, try fitting with accumulated audio
                if (lock == null && activeSources.hybrid.size >= MIN_ONSETS) {
                    val rescueHorizonSec = if (atEof) Double.MAX_VALUE else (extractor.lastCoveredSec + 60.0)
                    val rescueStarts = if (atEof) starts else starts.filter { it <= rescueHorizonSec }
                    val promoted = attemptLock(activeSources.hybrid, rescueStarts, "progressive-rescue")
                    if (promoted != null) {
                        lock = promoted
                        activeBeta = promoted.offsetMs / 1000.0
                        activeAlpha = promoted.speed.toDouble()
                        activePiecewise = promoted.piecewise
                        withContext(kotlinx.coroutines.NonCancellable) {
                            store.updateAutoSync(videoUri, promoted.offsetMs, promoted.speed, promoted.piecewise)
                        }
                        AppLog.d("SYNC_JOB", "Progressive rescue LOCKED: uri=$videoUri offset=${promoted.offsetMs}ms")
                    }
                }

                // Check chunk recall in subtitle time coordinates to detect cuts or shifts
                if (lock != null) {
                    val chunkEndSec = extractor.lastCoveredSec
                    val subStart = (chunkResumeFrom - activeBeta) / activeAlpha
                    val subEnd = (chunkEndSec - activeBeta) / activeAlpha
                    val chunkCues = starts.filter { it in minOf(subStart, subEnd)..maxOf(subStart, subEnd) }

                    if (chunkCues.size >= 15 && chunk.hybrid.isNotEmpty()) {
                        val recActive = SyncFinder.evaluate(chunk.hybrid, chunkCues, activeAlpha, activeBeta)
                        if (recActive < 0.25) {
                            val cand = SyncFinder.findBestShift(
                                chunk.hybrid, chunkCues, activeAlpha, centerBeta = activeBeta, radius = 150.0,
                            )
                            val shift = cand.beta - activeBeta
                            if (cand.recall >= 0.45 && cand.margin >= 0.12 && cand.containment >= 0.18 && kotlin.math.abs(shift) >= 1.0) {
                                AppLog.d(
                                    "SYNC_JOB",
                                    "Tier 2 Progressive Cut at %.0fs: shift=%+.2fs newBeta=%+.2fs (recall=%.1f%%, margin=%.1f%%, cont=%.1f%%)"
                                        .format(chunkResumeFrom, shift, cand.beta, cand.recall * 100, cand.margin * 100, cand.containment * 100)
                                )
                                val cutAudioSec = SyncFinder.findCutBoundary(
                                    chunk.hybrid, chunkCues, activeAlpha, activeBeta, cand.beta, chunkResumeFrom,
                                )
                                val newPiecewise = if (activePiecewise.isEmpty()) {
                                    SyncFinder.piecewiseToStorage(cutAudioSec, activeBeta, cand.beta)
                                } else {
                                    "$activePiecewise;$cutAudioSec:${cand.beta}"
                                }
                                activePiecewise = newPiecewise
                                activeBeta = cand.beta
                                val currentLock = lock
                                withContext(kotlinx.coroutines.NonCancellable) {
                                    store.updateAutoSync(
                                        videoUri,
                                        currentLock?.offsetMs ?: 0L,
                                        currentLock?.speed ?: 1f,
                                        newPiecewise,
                                    )
                                }
                            }
                        }
                    }
                }

                if (atEof) break
            }

            if (isComplete) {
                store.markAutoSyncChecked(videoUri)
                AppLog.d("SYNC_JOB", "Progressive sync reached EOF for $videoUri")
                return@withContext Result.success()
            }

            if (!coroutineContext.isActive || isStopped) {
                AppLog.d("SYNC_JOB", "Progressive sync paused by caller — state saved to OnsetCache")
                return@withContext Result.retry()
            }

            if (lock == null) {
                if (runAttemptCount >= 3) {
                    store.markAutoSyncChecked(videoUri)
                    return@withContext Result.success()
                }
                return@withContext Result.retry()
            }

            return@withContext Result.success()
        } catch (c: kotlinx.coroutines.CancellationException) {
            // v0.8.7 P0: WorkManager cancels an in-flight unique job when a
            // forced "Resync now" enqueues with REPLACE. That used to surface
            // as "fingerprint failed | Job was cancelled" and then be retried
            // as if the file were poisoned. The 09-19 log shows the real cost:
            // a Tier 1 envelope lock was computed at 16:06:58.259 and the job
            // was cancelled 6 ms later, so a perfectly good lock was thrown
            // away and the episode stayed unsynced. Honour the cancellation
            // instead of mislabelling it a failure — and never schedule a
            // retry for work the caller asked to stop.
            AppLog.d("SYNC_JOB", "fingerprint cancelled (superseded work) — no retry")
            throw c

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
    private fun toLockCandidate(
        model: SyncOrchestrator.Model,
        tag: String,
    ): LockCandidate = when (model) {
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

    private fun attemptLock(
        onsets: List<Double>,
        starts: List<Double>,
        tag: String,
    ): LockCandidate? {
        val model = SyncOrchestrator.sync(onsets, starts) ?: run {
            AppLog.d("SYNC_JOB", "$tag: engine refused (gates)")
            return null
        }
        return toLockCandidate(model, tag)
    }

    private fun getVideoDurationMs(videoUri: String, videoPath: String?): Long {
        return try {
            val mmr = MediaMetadataRetriever()
            try {
                if (!videoPath.isNullOrEmpty() && File(videoPath).canRead()) mmr.setDataSource(videoPath)
                else mmr.setDataSource(applicationContext, Uri.parse(videoUri))
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            } finally {
                mmr.release()
            }
        } catch (t: Throwable) {
            AppLog.d("SYNC_JOB", "duration probe failed: ${t.message}")
            0L
        }
    }

    /**
     * Probes 3 spots across long videos (>10 minutes) at 25%, 50%, and 75% duration
     * to pre-detect commercial cut discontinuities and populate piecewise segments
     * in the background before the user even scrubs there.
     */
    private fun buildMultiSpotPiecewise(
        extractor: OnsetExtractor,
        videoPath: String?,
        videoUri: String,
        cues: List<SubtitleCue>,
        baseOffsetSec: Double,
        durMs: Long,
        isCancelled: () -> Boolean,
    ): String {
        if (durMs < 600_000L || cues.isEmpty()) return ""
        val durSec = durMs / 1000.0
        val probePoints = listOf(0.25 * durSec, 0.50 * durSec, 0.75 * durSec)
        val pwSegments = mutableListOf<Pair<Double, Double>>()
        pwSegments.add(0.0 to baseOffsetSec)
        var lastOffset = baseOffsetSec

        for (pt in probePoints) {
            if (isCancelled()) break
            val spotOffset = extractor.probeSpotSync(
                videoPath = videoPath,
                videoUri = Uri.parse(videoUri),
                cues = cues,
                positionSec = pt,
                activeOffsetSec = lastOffset,
                probeDurationSec = 6.0,
                radiusSec = 45.0,
                isCancelled = isCancelled,
            )
            if (spotOffset != null && abs(spotOffset - lastOffset) > 0.80) {
                pwSegments.add(pt to spotOffset)
                lastOffset = spotOffset
                AppLog.d("SYNC_JOB", "multi-spot probe found piecewise jump at t=%.1fs -> %+.2fs".format(pt, spotOffset))
            }
        }
        return if (pwSegments.size > 1) {
            pwSegments.joinToString(";") { "%.1f:%.3f".format(java.util.Locale.US, it.first, it.second) }
        } else ""
    }

    /**
     * v0.8 P2-2: caller-scaled decode budget. Floor is the old fixed
     * 600 s; every ms of video adds 1/8 ms of budget (≈8× realtime assumed
     * audio decode, half the claimed 10-20x worst-case margin), ceiling
     * 25 min so a corrupt duration can never hang the worker.
     */
    private fun decodeBudgetMs(videoUri: String, videoPath: String): Long {
        val durMs = getVideoDurationMs(videoUri, videoPath)
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
