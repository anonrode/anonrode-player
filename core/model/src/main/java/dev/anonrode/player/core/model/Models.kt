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
    /**
     * Raw MediaStore DISPLAY_NAME (filename with extension). [title] is the
     * cleaned, human-readable version (extension stripped, separator runs
     * collapsed); consumers that need the exact filename — hash lookups,
     * sidecar subtitle matching — use this instead.
     */
    val displayName: String = "",
) {
    /** Derived: the title matches a season/episode pattern (see [EpisodePattern]). */
    val isSeriesEpisode: Boolean get() = EpisodePattern.find(title) != null
}

/** One "series" — a folder auto-grouped by the library scanner. */
data class Series(
    val name: String,
    val folderPath: String,
    val videos: List<Video>,
    val totalWatched: Int,
    val totalEpisodes: Int,
    /**
     * Most recent playback time (epoch ms) of any video in the folder;
     * 0 = never played. The scanner cannot know playback state, so it leaves
     * this at 0 — the library view model fills it in when it joins the
     * snapshot with the state store ("Recently played" sorting).
     */
    val lastPlayedTimeMs: Long = 0L,
) {
    /** Derived: watched fraction 0..1 (totalWatched / totalEpisodes). */
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

/**
 * Natural-order string comparison: digit runs compare numerically instead of
 * lexicographically, so "Episode 2" sorts before "Episode 10" regardless of
 * zero-padding ("007" == "7" in value; the longer run wins ties). Letters
 * compare case-insensitively. Canonical scan-order comparator — the library
 * UI and the player's episode queue may layer episode-pattern logic on top.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                // Compare the whole digit runs numerically: strip leading
                // zeros, then longer significant part = larger number.
                var ea = ia
                while (ea < a.length && a[ea].isDigit()) ea++
                var eb = ib
                while (eb < b.length && b[eb].isDigit()) eb++
                val na = a.substring(ia, ea).trimStart('0')
                val nb = b.substring(ib, eb).trimStart('0')
                val cmp = when {
                    na.length != nb.length -> na.length.compareTo(nb.length)
                    na.isNotEmpty() -> na.compareTo(nb)
                    else -> 0 // both chunks are zero
                }
                if (cmp != 0) return cmp
                // Equal values ("007" vs "7"): more leading zeros sorts first
                // so the comparison stays deterministic.
                val sigA = ea - ia - na.length
                val sigB = eb - ib - nb.length
                if (sigA != sigB) return sigA.compareTo(sigB)
                ia = ea
                ib = eb
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                ia++
                ib++
            }
        }
        return (a.length - ia).compareTo(b.length - ib)
    }
}
