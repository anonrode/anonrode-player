package dev.anonrode.player.core.media.library

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dev.anonrode.player.core.model.EpisodePattern
import dev.anonrode.player.core.model.Series
import dev.anonrode.player.core.model.Video
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers

/**
 * Video library source of truth: MediaStore + a debounced ContentObserver.
 *
 * Folder → series auto-grouping: a folder containing 2+ videos (or any
 * episode-pattern matches) becomes one "series" card. Season subfolders
 * become part of the same series via parent-path prefixing. No scrapers —
 * deterministic and offline.
 */
class MediaScanner(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver
    private val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.DATE_MODIFIED,
        MediaStore.Video.Media.DATA,
    )

    @Volatile private var cache: LibrarySnapshot? = null
    @Volatile private var cacheAt: Long = 0L

    /** Reactive library: re-queries on every MediaStore change (debounced). */
    fun observeLibrary(): Flow<LibrarySnapshot> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        trySend(Unit)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
        .debounce(250)
        .map { scan(force = true) }
        // flowOn only affects upstream operators, so it must come AFTER map
        // for scan() to run off the collector's (main) thread.
        .flowOn(Dispatchers.IO)

    /**
     * MediaStore query with a 3-second TTL cache: EpisodeQueue.build and
     * other callers hit scan() back-to-back when opening a video, and the
     * library cannot change that fast. [force] bypasses the cache and
     * refreshes it (observer-driven refreshes).
     */
    fun scan(force: Boolean = false): LibrarySnapshot {
        if (!force) {
            val c = cache
            if (c != null && System.currentTimeMillis() - cacheAt < 3000L) return c
        }
        val videos = ArrayList<Video>()
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            MediaStore.Video.Media.DATE_MODIFIED + " DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val data = c.getString(dataCol) ?: continue
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id).toString()
                videos.add(
                    Video(
                        uri = uri,
                        path = data,
                        title = name,
                        durationMs = c.getLong(durCol),
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                        sizeBytes = c.getLong(sizeCol),
                        lastModifiedMs = c.getLong(dateCol) * 1000L,
                        mediaStoreId = id,
                        parentPath = parentOf(data),
                    )
                )
            }
        }
        val fresh = LibrarySnapshot(videos, groupIntoSeries(videos))
        cache = fresh
        cacheAt = System.currentTimeMillis()
        return fresh
    }

    /** Whitelisted folder roots (SAF tree URIs), applied as a filter. */
    fun applyFolderFilter(videos: List<Video>, allowedParentPrefixes: Set<String>): List<Video> {
        if (allowedParentPrefixes.isEmpty()) return videos
        return videos.filter { v ->
            allowedParentPrefixes.any { prefix ->
                v.parentPath.startsWith(prefix) || v.path.startsWith(prefix)
            }
        }
    }

    private fun groupIntoSeries(videos: List<Video>): List<Series> {
        val byFolder = videos.groupBy { it.parentPath }
        val series = ArrayList<Series>()
        for ((folder, vids) in byFolder) {
            if (vids.isEmpty()) continue
            val episodeCount = vids.count { it.isSeriesEpisode }
            // A folder is a "series" when it has 2+ videos or any episode patterns.
            if (vids.size >= 2 || episodeCount > 0) {
                val name = folderName(folder)
                val watched = 0 // filled by the state store join
                series.add(
                    Series(
                        name = name,
                        folderPath = folder,
                        videos = vids.sortedWith(episodeOrder()),
                        totalWatched = watched,
                        totalEpisodes = vids.size,
                    )
                )
            }
        }
        return series.sortedBy { it.name.lowercase() }
    }

    private fun episodeOrder(): Comparator<Video> = Comparator { a, b ->
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

    private fun folderName(path: String): String {
        val name = path.substringAfterLast('/').ifEmpty { path }
        return name
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "") // strip (2021)
            .replace(Regex("""\s*\[\w+\]\s*$"""), "")  // strip [GROUP]
            .replace('_', ' ')
            .trim()
    }

    private fun parentOf(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx > 0) path.substring(0, idx) else path
    }
}

data class LibrarySnapshot(
    val videos: List<Video>,
    val series: List<Series>,
)
