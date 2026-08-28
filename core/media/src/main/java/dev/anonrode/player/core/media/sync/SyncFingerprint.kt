package dev.anonrode.player.core.media.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Entry point for the background subtitle sync fingerprint. Deduplicates
 * by video URI: one fingerprint job per video, even if triggered from
 * multiple places. Runs once, with exponential backoff on failure.
 *
 * The job decodes the whole video file — on low-end devices that fights
 * playback for CPU/IO. It therefore starts with a delay (so the viewing
 * session that triggered it is underway or over) and only when the
 * battery is healthy. The job itself re-checks the auto-sync setting at
 * runtime, so switching sync off kills pending/retrying jobs.
 */
object SyncFingerprint {

    private const val WORK_TAG = "subtitle-sync-fingerprint"

    fun schedule(context: Context, videoUri: String) {
        val request = OneTimeWorkRequestBuilder<SyncFingerprintJob>()
            .setInputData(workDataOf(SyncFingerprintJob.KEY_VIDEO_URI to videoUri))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInitialDelay(90, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_TAG-$videoUri",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
