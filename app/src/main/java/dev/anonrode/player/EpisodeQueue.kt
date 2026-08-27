package dev.anonrode.player

import dev.anonrode.player.core.media.library.MediaScanner
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.core.model.EpisodePattern
import dev.anonrode.player.core.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ordered playback queue for one series folder.
 *
 * Built by taking the current video's parent directory, collecting every
 * video in MediaStore that shares that directory, sorting them by
 * season/episode number ([EpisodePattern.find], falling back to plain title
 * order for unpatterned files), and resolving the index of the video that is
 * playing now. Drives auto-play-next, manual skip buttons, and the
 * "Up Next" overlay in [dev.anonrode.player.ui.PlayerScreen].
 */
data class EpisodeQueue(
    val episodes: List<Video>,      // all videos in the same series/folder, sorted by episode number
    val currentIndex: Int,          // which episode is currently playing
) {
    /** The episode playing right now, if the index is in range. */
    val current: Video? get() = episodes.getOrNull(currentIndex)

    fun next(): Video? = episodes.getOrNull(currentIndex + 1)
    fun previous(): Video? = episodes.getOrNull(currentIndex - 1)

    companion object {

        /**
         * Episode-aware ordering, mirroring [MediaScanner]'s library grouping:
         * season first, then episode number; pattern-less titles sort after
         * patterned ones alphabetically.
         */
        fun episodeOrder(): Comparator<Video> = Comparator { a, b ->
            val ea = EpisodePattern.find(a.title)
            val eb = EpisodePattern.find(b.title)
            when {
                ea != null && eb != null -> {
                    val sa = ea.first ?: 0
                    val sb = eb.first ?: 0
                    if (sa != sb) sa.compareTo(sb) else ea.second.compareTo(eb.second)
                }
                ea != null -> -1
                eb != null -> 1
                else -> a.title.compareTo(b.title)
            }
        }

        /** Sort a list of videos into watching order. */
        fun sortEpisodes(videos: List<Video>): List<Video> = videos.sortedWith(episodeOrder())

        /**
         * Scan MediaStore and build the queue around [currentUri]: all videos
         * sharing its parent directory path, episode-sorted, with the current
         * index resolved. Returns null when the video is no longer in
         * MediaStore (queue features silently disable).
         */
        suspend fun build(scanner: MediaScanner, currentUri: String): EpisodeQueue? =
            withContext(Dispatchers.IO) {
                val videos = scanner.scan().videos
                val current = videos.firstOrNull { it.uri == currentUri }
                    ?: return@withContext null
                fromVideos(videos, current)
            }

        /** Pure variant of [build] for callers that already hold a library snapshot. */
        fun fromVideos(videos: List<Video>, current: Video): EpisodeQueue? {
            val siblings = sortEpisodes(videos.filter { it.parentPath == current.parentPath })
            val index = siblings.indexOfFirst { it.uri == current.uri }
            if (index < 0) return null
            return EpisodeQueue(episodes = siblings, currentIndex = index)
        }

        /**
         * Queue from an explicit user-selected ordered URI list (library
         * multi-select), preserving the caller's order instead of episode-sorting.
         * URIs missing from the MediaStore snapshot are skipped; returns null when
         * [currentUri] itself is absent so the caller can fall back to folder
         * siblings.
         */
        suspend fun fromExplicitUris(
            scanner: MediaScanner,
            orderedUris: List<String>,
            currentUri: String,
        ): EpisodeQueue? =
            withContext(Dispatchers.IO) {
                fromExplicitVideos(scanner.scan().videos, orderedUris, currentUri)
            }

        /** Pure variant of [fromExplicitUris] for callers holding a snapshot. */
        fun fromExplicitVideos(
            videos: List<Video>,
            orderedUris: List<String>,
            currentUri: String,
        ): EpisodeQueue? {
            val byUri = videos.associateBy { it.uri }
            val episodes = orderedUris.mapNotNull { byUri[it] }
            val index = episodes.indexOfFirst { it.uri == currentUri }
            if (index < 0) return null
            return EpisodeQueue(episodes = episodes, currentIndex = index)
        }
    }
}

/* ── MediaStateStore helpers ─────────────────────────────────────────────────
 * core/ is read-only for this feature, so the extra entry points the player
 * needs are provided as extension functions living in the app module.
 * -------------------------------------------------------------------------- */

/**
 * Persist playback speed for [uri] (Room, media_state.playback_speed).
 * Delegates to the existing [MediaStateStore.updateSpeed].
 */
suspend fun MediaStateStore.updatePlaybackSpeed(uri: String, speed: Float) {
    updateSpeed(uri, speed)
}

/** Last persisted speed for [uri]; null when unset or invalid (<= 0). */
suspend fun MediaStateStore.savedPlaybackSpeed(uri: String): Float? =
    get(uri)?.playbackSpeed?.takeIf { it > 0f }
