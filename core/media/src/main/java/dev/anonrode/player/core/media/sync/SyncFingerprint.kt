package dev.anonrode.player.core.media.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Entry point for the background subtitle sync fingerprint. Deduplicates
 * by video URI: one fingerprint job per video, even if triggered from
 * multiple places. Runs once, with exponential backoff on failure.
 *
 * The job decodes the whole video file — on low-end devices that fights
 * playback for CPU/IO. It therefore starts with a delay (so the viewing
 * session that triggered it is underway or over) and only when the
 * battery is healthy. The job itself re-checks the
 * [dev.anonrode.player.core.datastore.PlayerSettings.subtitleAutoSyncEnabled]
 * toggle at runtime, so switching sync off kills pending/retrying jobs.
 *
 * v0.6.2 SUB-SYNC UX PASS: [scheduleSuspending] reads the DataStore
 * toggle and returns WITHOUT enqueueing when the toggle is OFF. The
 * legacy [schedule] entry point is preserved as a fire-and-forget shim
 * (calls [scheduleSuspending] on a process-global IO scope) so existing
 * call sites keep working; new call sites should prefer the suspend
 * variant. The toggle is the SINGLE source of truth for "should sync
 * run at all" — default OFF matches the spec; the bottom-row sync chip
 * in the player chrome flips it on for every video until the user turns
 * it back off.
 */
object SyncFingerprint {

    private const val WORK_TAG = "subtitle-sync-fingerprint"

    /**
     * Suspend entry point: reads the user toggle from DataStore once; if
     * OFF (the default), returns WITHOUT enqueueing. Otherwise enqueues
     * a unique WorkManager job deduped by video URI. The job itself
     * re-reads the toggle at runtime so a flipped-off toggle still kills
     * pending/retrying jobs even after this call returned.
     *
     * [force] re-fingerprints even when a persisted lock exists — the
     * "Resync now" contract: the user explicitly asked for a re-fit
     * (e.g. after editing the subtitle file), and the job's own
     * skip-if-locked guard would silently turn that request into a no-op.
     * A force run replaces the pending non-force job for the same URI
     * (REPLACE, not KEEP) so the explicit request is not swallowed by an
     * earlier KEEP-enqueued job.
     *
     * Callers must invoke this on a background dispatcher (Dispatchers.IO)
     * — the Flow read is suspending.
     */
    suspend fun scheduleSuspending(
        context: Context,
        videoUri: String,
        force: Boolean = false,
        immediate: Boolean = false,
        overrideEnabled: Boolean? = null,
    ) {
        val enabled = overrideEnabled ?: try {
            context.playerSettingsDataStore.data.first().subtitleAutoSyncEnabled
        } catch (_: Throwable) {
            false
        }
        if (!enabled) return
        val runNow = force || immediate
        val constraints = Constraints.Builder()
            .apply { if (!runNow) setRequiresBatteryNotLow(true) }
            .build()
        val request = OneTimeWorkRequestBuilder<SyncFingerprintJob>()
            .setInputData(
                workDataOf(
                    SyncFingerprintJob.KEY_VIDEO_URI to videoUri,
                    SyncFingerprintJob.KEY_FORCE to force,
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(if (runNow) 0 else 90, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_TAG-$videoUri",
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Fire-and-forget shim for callers that don't already hold a
     * coroutine scope. Delegates to [scheduleSuspending] on a
     * process-global IO scope.
     */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    @Suppress("OPT_IN_USAGE")
    fun schedule(
        context: Context,
        videoUri: String,
        force: Boolean = false,
        immediate: Boolean = false,
        overrideEnabled: Boolean? = null,
    ) {
        GlobalScope.launch(Dispatchers.IO) {
            scheduleSuspending(context.applicationContext, videoUri, force, immediate, overrideEnabled)
        }
    }

    /**
     * Cancel a pending or in-flight fingerprint for [videoUri]. Safe to call
     * even if no job was scheduled — WorkManager treats unknown ids as a
     * no-op. Called when the user finishes with a video (activity destroy
     * without backstack restore, or explicit removal from library) so the
     * 10-minute full-file decode doesn't keep retrying on a video nobody
     * is watching.
     */
    fun cancel(context: Context, videoUri: String) {
        WorkManager.getInstance(context).cancelUniqueWork("$WORK_TAG-$videoUri")
    }
}
