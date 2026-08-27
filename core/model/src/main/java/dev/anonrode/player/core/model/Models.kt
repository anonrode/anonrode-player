package dev.anonrode.player.core.model

/**
 * A video file in the library. Identity is the content URI (stable across
 * storage re-mounts, unlike file paths).
 */
data class Video(
    val uri: String,
    val path: String,
    val title: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val lastModifiedMs: Long,
    val mediaStoreId: Long,
    val parentPath: String,
) {
    val isSeriesEpisode: Boolean get() = EpisodePattern.find(title) != null
}

/** One "series" — a folder auto-grouped by the library scanner. */
data class Series(
    val name: String,
    val folderPath: String,
    val videos: List<Video>,
    val totalWatched: Int,
    val totalEpisodes: Int,
) {
    val progress: Float get() = if (totalEpisodes == 0) 0f else totalWatched.toFloat() / totalEpisodes
}

/** A parsed subtitle cue. Times in seconds. */
data class SubtitleCue(
    val start: Double,
    val end: Double,
    val lines: List<String>,
)

/** Playback state for one video, persisted in Room. */
data class MediaState(
    val uri: String,
    val playbackPositionMs: Long = 0L,
    val durationMs: Long? = null,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val externalSubtitleUris: List<String> = emptyList(),
    /** Picker-selected subtitle source; see MediaStateEntity.subtitleChoice. */
    val subtitleChoice: String = "",
    val subtitleDelayMs: Long = 0L,
    val autoSyncOffsetMs: Long = 0L,
    val autoSyncSpeedFactor: Float = 1f,
    val autoSyncPiecewise: String = "",
    val playbackSpeed: Float = 1f,
    val videoScale: Float = 1f,
    val lastPlayedTimeMs: Long? = null,
    val finished: Boolean = false,
)

object EpisodePattern {
    // Handles: S01E02  S01_E02  S01_EP02  S01.E02  S02-EP07  1x02  S01E02v2
    private val full = Regex("""[Ss](\d{1,2})[_.\-\s]*[Ee][Pp]?[_.\-\s]*(\d{1,3})(?!\d)""")
    // Lookarounds + boundary: "1920x1080" must NOT match as 80x72-style episodes.
    private val cross = Regex("""(?<!\d)(\d{1,2})x(\d{2})(?!\d)""")
    private val seasonWord = Regex("""[Ss]eason\s*(\d{1,2})\s*[Ee]p(?:isode)?\s*(\d{1,3})""", RegexOption.IGNORE_CASE)
    private val bare = Regex("""[Ee][Pp]?[_.\-\s]*(\d{1,3})(?!\d)""")

    fun find(name: String): Pair<Int?, Int>? {
        val m1 = full.find(name)
        if (m1 != null) return (m1.groupValues[1].toIntOrNull()) to m1.groupValues[2].toInt()
        val m2 = cross.find(name)
        if (m2 != null) return m2.groupValues[1].toIntOrNull() to m2.groupValues[2].toInt()
        val m3 = seasonWord.find(name)
        if (m3 != null) return m3.groupValues[1].toIntOrNull() to m3.groupValues[2].toInt()
        val m4 = bare.find(name)
        if (m4 != null) return null to m4.groupValues[1].toInt()
        return null
    }

    /** 0-based episode index used for ordering within a season. */
    fun episodeIndex(name: String): Int? = find(name)?.second
}
