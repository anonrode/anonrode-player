package dev.anonrode.player.core.media.state

import dev.anonrode.player.core.database.MediaStateDao
import dev.anonrode.player.core.database.MediaStateEntity
import dev.anonrode.player.core.model.MediaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Per-video state repository keyed by content URI. Every writer is an
 * ensureRow bootstrap + a partial-column UPDATE, so concurrent writers
 * (position saves, sync locks, nudges, speed, fingerprint job) can never
 * clobber each other's fields.
 */
class MediaStateStore(private val dao: MediaStateDao) {

    suspend fun get(uri: String): MediaState? = dao.get(uri)?.toModel()

    fun getAsFlow(uri: String): Flow<MediaState?> = dao.getAsFlow(uri).map { it?.toModel() }

    /** Continue-watching feed. */
    fun getInProgress(): Flow<List<MediaState>> = dao.getInProgress().map { list -> list.map { it.toModel() } }

    /** Every persisted state (including finished rows), most recently played first. */
    fun getAllStates(): Flow<List<MediaState>> = dao.getAll().map { list -> list.map { it.toModel() } }

    /**
     * Persist position/duration/finished. A null [durationMs] means "unknown"
     * and NEVER clobbers a previously stored duration (the UPDATE uses
     * COALESCE for it).
     */
    suspend fun updatePosition(uri: String, positionMs: Long, durationMs: Long?, finished: Boolean = false) {
        dao.ensureRow(uri)
        dao.updatePositionFields(uri, positionMs, durationMs, finished, System.currentTimeMillis())
    }

    suspend fun updateAudioTrack(uri: String, index: Int?) {
        dao.ensureRow(uri)
        dao.updateAudioTrackFields(uri, index, System.currentTimeMillis())
    }

    suspend fun updateSubtitleTrack(uri: String, index: Int?) {
        dao.ensureRow(uri)
        dao.updateSubtitleTrackFields(uri, index, System.currentTimeMillis())
    }

    /**
     * Persist the picker's explicit subtitle source. See
     * [MediaStateEntity.subtitleChoice] for the grammar
     * (""=auto, "none", "embedded:N", "sidecar:name", "online:name").
     */
    suspend fun updateSubtitleChoice(uri: String, choice: String) {
        dao.ensureRow(uri)
        dao.updateSubtitleChoiceFields(uri, choice, System.currentTimeMillis())
    }

    suspend fun updateSubtitleDelay(uri: String, delayMs: Long) {
        dao.ensureRow(uri)
        dao.updateSubtitleDelayFields(uri, delayMs, System.currentTimeMillis())
    }

    /** Persist an auto-sync lock so re-watches start instantly in sync. */
    suspend fun updateAutoSyncSpeed(uri: String, speed: Float) {
        dao.ensureRow(uri)
        dao.updateAutoSyncSpeedFields(uri, speed, System.currentTimeMillis())
    }

    suspend fun updateAutoSyncOffset(uri: String, offsetMs: Long) {
        dao.ensureRow(uri)
        dao.updateAutoSyncOffsetFields(uri, offsetMs, System.currentTimeMillis())
    }

    /**
     * Persist a complete auto-sync lock (offset + speed + optional piecewise
     * cut segments) in ONE statement, so the fields can't clobber each other.
     * Used by the background fingerprint job; the live engine keeps using
     * the separate offset/speed writers above.
     */
    suspend fun updateAutoSync(
        uri: String,
        offsetMs: Long,
        speed: Float,
        piecewise: String = "",
    ) {
        dao.ensureRow(uri)
        dao.updateAutoSyncFields(uri, offsetMs, speed, piecewise, System.currentTimeMillis())
    }

    suspend fun updateSpeed(uri: String, speed: Float) {
        dao.ensureRow(uri)
        dao.updateSpeedFields(uri, speed, System.currentTimeMillis())
    }

    suspend fun updateZoom(uri: String, scale: Float) {
        dao.ensureRow(uri)
        dao.updateZoomFields(uri, scale, System.currentTimeMillis())
    }

    /**
     * Remove state rows for videos that no longer exist in the library.
     *
     * DANGEROUS BY DESIGN unless the caller asserts [fullScanConfirmed]:
     * [existingUris] must be the complete URI set of a FULL, SUCCESSFUL
     * library scan. Feeding it a partial or failed scan (one folder, a
     * query that threw, a scan that ran before the video permission was
     * granted) would mass-delete watch history for every video the partial
     * scan missed. With [fullScanConfirmed] = false (the default) this is a
     * no-op, so a careless call cannot destroy data.
     *
     * Only `content://` rows are ever pruned — state keyed by other schemes
     * (SAF documents, file://) is left alone since MediaStore scans cannot
     * prove their absence.
     */
    suspend fun pruneMissing(existingUris: Set<String>, fullScanConfirmed: Boolean = false) {
        if (!fullScanConfirmed) return
        val all = dao.getAll().first()
        val missing = all.filter { it.uri.startsWith("content://") && it.uri !in existingUris }
            .map { it.uri }
        if (missing.isNotEmpty()) dao.delete(missing)
    }

    private fun MediaStateEntity.toModel() = MediaState(
        uri = uri,
        playbackPositionMs = playbackPositionMs,
        durationMs = durationMs,
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = subtitleTrackIndex,
        externalSubtitleUris = externalSubtitleUris.split(',').filter { it.isNotEmpty() },
        subtitleChoice = subtitleChoice,
        subtitleDelayMs = subtitleDelayMs,
        autoSyncOffsetMs = autoSyncOffsetMs,
        autoSyncSpeedFactor = autoSyncSpeedFactor,
        autoSyncPiecewise = autoSyncPiecewise,
        playbackSpeed = playbackSpeed,
        videoScale = videoScale,
        lastPlayedTimeMs = lastPlayedTimeMs,
        finished = finished,
    )
}
