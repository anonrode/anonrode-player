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
}
