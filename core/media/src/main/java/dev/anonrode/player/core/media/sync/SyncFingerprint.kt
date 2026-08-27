package dev.anonrode.player.core.media.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Entry point for the background subtitle sync fingerprint. Deduplicates
 * by video URI: one fingerprint job per video, even if triggered from
 * multiple places. Runs once, with exponential backoff on failure.
 */
object SyncFingerprint {

    private const val WORK_TAG = "subtitle-sync-fingerprint"

    fun schedule(context: Context, videoUri: String) {
        val request = OneTimeWorkRequestBuilder<SyncFingerprintJob>()
            .setInputData(workDataOf(SyncFingerprintJob.KEY_VIDEO_URI to videoUri))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_TAG-$videoUri",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }
}
