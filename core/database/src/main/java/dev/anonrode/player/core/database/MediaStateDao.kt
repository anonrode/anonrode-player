package dev.anonrode.player.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaStateDao {

    @Upsert
    suspend fun upsert(entity: MediaStateEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MediaStateEntity>)

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    suspend fun get(uri: String): MediaStateEntity?

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    fun getAsFlow(uri: String): Flow<MediaStateEntity?>

    @Query("SELECT * FROM media_state ORDER BY last_played_time_ms DESC")
    fun getAll(): Flow<List<MediaStateEntity>>

    /** Continue-watching feed, most recently played first. */
    @Query(
        "SELECT * FROM media_state WHERE finished = 0 AND playback_position_ms > 5000 " +
            "AND last_played_time_ms IS NOT NULL ORDER BY last_played_time_ms DESC"
    )
    fun getInProgress(): Flow<List<MediaStateEntity>>

    @Query("DELETE FROM media_state WHERE uri IN (:uris)")
    suspend fun delete(uris: List<String>)

    @Query("DELETE FROM media_state")
    suspend fun clear()

    /**
     * Bootstrap a row for :uri if it has none yet (INSERT OR IGNORE — an
     * existing row is left untouched). Pairs with the partial-column
     * UPDATEs below so concurrent writers never clobber each other's
     * fields. Every NOT NULL column is bound explicitly: the schema has
     * no SQL DEFAULT clauses, so a uri-only insert would violate NOT NULL
     * and be silently ignored.
     */
    @Query(
        "INSERT OR IGNORE INTO media_state(" +
            "uri, playback_position_ms, external_subtitle_uris, subtitle_choice, " +
            "subtitle_delay_ms, auto_sync_offset_ms, auto_sync_speed_factor, " +
            "auto_sync_piecewise, playback_speed, video_scale, finished" +
        ") VALUES(:uri, 0, '', '', 0, 0, 1.0, '', 1.0, 1.0, 0)"
    )
    suspend fun ensureRow(uri: String)

    /**
     * Partial-column writers: each touches ONLY its own fields (plus
     * last_played_time_ms), so concurrent writers cannot overwrite each
     * other. duration uses COALESCE so a null (unknown) duration never
     * clobbers a stored one.
     */
    @Query(
        "UPDATE media_state SET playback_position_ms = :positionMs, " +
            "duration_ms = COALESCE(:durationMs, duration_ms), finished = :finished, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updatePositionFields(
        uri: String,
        positionMs: Long,
        durationMs: Long?,
        finished: Boolean,
        lastPlayedMs: Long,
    )

    @Query(
        "UPDATE media_state SET audio_track_index = :index, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateAudioTrackFields(uri: String, index: Int?, lastPlayedMs: Long)

    @Query(
        "UPDATE media_state SET subtitle_track_index = :index, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateSubtitleTrackFields(uri: String, index: Int?, lastPlayedMs: Long)

    @Query(
        "UPDATE media_state SET subtitle_choice = :choice, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateSubtitleChoiceFields(uri: String, choice: String, lastPlayedMs: Long)

    @Query(
        "UPDATE media_state SET subtitle_delay_ms = :delayMs, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateSubtitleDelayFields(uri: String, delayMs: Long, lastPlayedMs: Long)

    @Query(
        "UPDATE media_state SET auto_sync_offset_ms = :offsetMs, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateAutoSyncOffsetFields(uri: String, offsetMs: Long, lastPlayedMs: Long)

    @Query(
        "UPDATE media_state SET auto_sync_speed_factor = :speed, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateAutoSyncSpeedFields(uri: String, speed: Float, lastPlayedMs: Long)

    /** Complete auto-sync lock in ONE statement: offset + speed + piecewise. */
    @Query(
        "UPDATE media_state SET auto_sync_offset_ms = :offsetMs, " +
            "auto_sync_speed_factor = :speed, auto_sync_piecewise = :piecewise, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateAutoSyncFields(
        uri: String,
        offsetMs: Long,
        speed: Float,
        piecewise: String,
        lastPlayedMs: Long,
    )

    @Query(
        "UPDATE media_state SET playback_speed = :speed, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateSpeedFields(uri: String, speed: Float, lastPlayedMs: Long)

    @Query(
        "UPDATE media_state SET video_scale = :scale, " +
            "last_played_time_ms = :lastPlayedMs WHERE uri = :uri"
    )
    suspend fun updateZoomFields(uri: String, scale: Float, lastPlayedMs: Long)
}
