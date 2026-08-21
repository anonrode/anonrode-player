package dev.anonrode.player.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-video playback state. Keyed by content URI (stable across storage
 * re-mounts). Written constantly during playback, so it stays tiny.
 */
@Entity(
    tableName = "media_state",
    indices = [Index(value = ["uri"], unique = true)],
)
data class MediaStateEntity(
    @PrimaryKey val uri: String,
    val playbackPositionMs: Long = 0L,
    // Duration at save time — identity check: if the file's duration differs
    // from this at play time, the stored position is stale → reset to 0.
    val durationMs: Long? = null,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    // Comma-separated persisted subtitle URI permissions.
    val externalSubtitleUris: String = "",
    val subtitleDelayMs: Long = 0L,
    // Persisted auto-sync lock (seconds*1000). Re-watches start instantly in
    // sync; the live engine quietly re-listens and refines.
    val autoSyncOffsetMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val videoScale: Float = 1f,
    val lastPlayedTimeMs: Long? = null,
    val finished: Boolean = false,
)
