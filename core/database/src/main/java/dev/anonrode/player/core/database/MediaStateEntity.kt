package dev.anonrode.player.core.database

import androidx.room.ColumnInfo
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
    @ColumnInfo(name = "playback_position_ms") val playbackPositionMs: Long = 0L,
    // Duration at save time — identity check: if the file's duration differs
    // from this at play time, the stored position is stale → reset to 0.
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    @ColumnInfo(name = "audio_track_index") val audioTrackIndex: Int? = null,
    @ColumnInfo(name = "subtitle_track_index") val subtitleTrackIndex: Int? = null,
    // Comma-separated persisted subtitle URI permissions.
    @ColumnInfo(name = "external_subtitle_uris") val externalSubtitleUris: String = "",
    // Explicit subtitle source chosen in the picker. Grammar:
    //   ""                 → auto (legacy sidecar auto-pick)
    //   "none"             → subtitles off
    //   "embedded:<index>" → in-container track (MediaExtractor index)
    //   "sidecar:<name>"   → sibling file by display name
    //   "online:<name>"    → downloaded file name in SubtitleDownloadStore
    @ColumnInfo(name = "subtitle_choice") val subtitleChoice: String = "",
    @ColumnInfo(name = "subtitle_delay_ms") val subtitleDelayMs: Long = 0L,
    // Persisted auto-sync lock (ms). Re-watches start instantly in sync;
    // the live engine quietly re-listens and refines.
    @ColumnInfo(name = "auto_sync_offset_ms") val autoSyncOffsetMs: Long = 0L,
    @ColumnInfo(name = "auto_sync_speed_factor") val autoSyncSpeedFactor: Float = 1f,
    // Piecewise cut segments: "startAudioSec:betaSec;startAudioSec:betaSec".
    // Empty = single affine lock (offset + speed apply everywhere).
    @ColumnInfo(name = "auto_sync_piecewise") val autoSyncPiecewise: String = "",
    // Background-fingerprint bookkeeping: 0 = the whole-file sync engine has
    // never reached a verdict for this video; non-zero = it ran to a verdict
    // (lock stored above, or the fit was refused / the content had too few
    // onsets). Gates the auto-schedule in openVideo so a checked video is not
    // re-decoded on every open; cleared when the subtitle source changes and
    // bypassed by a forced "Resync now".
    @ColumnInfo(name = "auto_sync_checked_at_ms") val autoSyncCheckedAtMs: Long = 0L,
    @ColumnInfo(name = "playback_speed") val playbackSpeed: Float = 1f,
    @ColumnInfo(name = "video_scale") val videoScale: Float = 1f,
    /**
     * Persisted playlist shuffle (0/1). The player's Shuffle ribbon tool used
     * to flip a Compose flag that reset on every open; this makes the
     * choice survive a re-open of the same video.
     */
    @ColumnInfo(name = "shuffle_enabled", defaultValue = "0") val shuffleEnabled: Boolean = false,
    /**
     * Persisted repeat mode: 0 = off, 1 = repeat one, 2 = repeat all. Mirrors
     * [dev.anonrode.player.ui.RepeatLoopMode] ordinals; stored as an Int
     * rather than an enum name so a future enum change cannot strand old
     * rows with an unparseable value.
     */
    @ColumnInfo(name = "repeat_mode", defaultValue = "0") val repeatMode: Int = 0,
    @ColumnInfo(name = "last_played_time_ms") val lastPlayedTimeMs: Long? = null,
    @ColumnInfo(name = "finished") val finished: Boolean = false,
)
