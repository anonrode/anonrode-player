package dev.anonrode.player.core.media.state

import dev.anonrode.player.core.database.MediaStateDao
import dev.anonrode.player.core.database.MediaStateEntity
import dev.anonrode.player.core.model.MediaState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Per-video state repository: read-modify-write on the Room row, keyed by
 * content URI. Also prunes rows for URIs that vanished from the library.
 */
class MediaStateStore(private val dao: MediaStateDao) {

    suspend fun get(uri: String): MediaState? = dao.get(uri)?.toModel()

    fun getAsFlow(uri: String): Flow<MediaState?> = dao.getAsFlow(uri).map { it?.toModel() }

    /** Continue-watching feed. */
    fun getInProgress(): Flow<List<MediaState>> = dao.getInProgress().map { list -> list.map { it.toModel() } }

    suspend fun updatePosition(uri: String, positionMs: Long, durationMs: Long?, finished: Boolean = false) {
        val cur = dao.get(uri) ?: MediaStateEntity(uri = uri)
        dao.upsert(
            cur.copy(
                playbackPositionMs = positionMs,
                durationMs = durationMs,
                finished = finished,
                lastPlayedTimeMs = System.currentTimeMillis(),
            )
        )
    }

    suspend fun updateAudioTrack(uri: String, index: Int?) {
        val cur = dao.get(uri) ?: MediaStateEntity(uri = uri)
        dao.upsert(cur.copy(audioTrackIndex = index, lastPlayedTimeMs = System.currentTimeMillis()))
    }

    suspend fun updateSubtitleTrack(uri: String, index: Int?) {
        val cur = dao.get(uri) ?: MediaStateEntity(uri = uri)
        dao.upsert(cur.copy(subtitleTrackIndex = index, lastPlayedTimeMs = System.currentTimeMillis()))
    }

    suspend fun updateSubtitleDelay(uri: String, delayMs: Long) {
        val cur = dao.get(uri) ?: MediaStateEntity(uri = uri)
        dao.upsert(cur.copy(subtitleDelayMs = delayMs, lastPlayedTimeMs = System.currentTimeMillis()))
    }

    /** Persist an auto-sync lock so re-watches start instantly in sync. */
    suspend fun updateAutoSyncSpeed(uri: String, speed: Float) {
        val cur = dao.get(uri) ?: MediaStateEntity(uri = uri)
        dao.upsert(cur.copy(autoSyncSpeedFactor = speed, lastPlayedTimeMs = System.currentTimeMillis()))
    }

    suspend fun updateAutoSyncOffset(uri: String, offsetMs: Long) {
        val cur = dao.get(uri) ?: MediaStateEntity(uri = uri)
        dao.upsert(cur.copy(autoSyncOffsetMs = offsetMs, lastPlayedTimeMs = System.currentTimeMillis()))
    }

    suspend fun updateSpeed(uri: String, speed: Float) {
        val cur = dao.get(uri) ?: MediaStateEntity(uri = uri)
        dao.upsert(cur.copy(playbackSpeed = speed, lastPlayedTimeMs = System.currentTimeMillis()))
    }

    suspend fun updateZoom(uri: String, scale: Float) {
        val cur = dao.get(uri) ?: MediaStateEntity(uri = uri)
        dao.upsert(cur.copy(videoScale = scale, lastPlayedTimeMs = System.currentTimeMillis()))
    }

    /** Remove state for videos that no longer exist in the library. */
    suspend fun pruneMissing(existingUris: Set<String>) {
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
        subtitleDelayMs = subtitleDelayMs,
        autoSyncOffsetMs = autoSyncOffsetMs,
        playbackSpeed = playbackSpeed,
        videoScale = videoScale,
        lastPlayedTimeMs = lastPlayedTimeMs,
        finished = finished,
    )
}
