package dev.anonrode.player.core.media.library

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.EpisodePattern
import dev.anonrode.player.core.model.NaturalOrder
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
 *
 * Caching: [scan] serves an in-memory snapshot and only re-queries MediaStore
 * when the snapshot was invalidated (a class-level ContentObserver marks it
 * dirty on any Video-table change), when the TTL backstop expires, or when
 * [force] is set. Back-to-back callers (rescan from settings) therefore hit
 * memory, not the provider. The playback open path goes further: it reads
 * [cachedSnapshot] (never scans) and falls back to [scan] only when no
 * snapshot exists yet.
 *
 * Robustness: hidden directories (any path segment starting with '.') and
 * pending files are skipped, rows are deduplicated by path, display titles
 * are cleaned (extension stripped, `_`/`.`/`-` separator runs collapsed to
 * spaces), and unchanged files keep their previous [Video] instance across
 * rescans (mtime + size check) so downstream keys stay stable.
 */
class MediaScanner(private val context: Context) {

    private val resolver: ContentResolver = context.contentResolver

    // IS_PENDING only exists on API 29+; querying it on older devices would
    // throw "no such column", so it is conditionally projected.
    private val projection: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.IS_PENDING,
            )
        } else {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DATA,
            )
        }

    private val lock = Any()

    @Volatile private var cache: LibrarySnapshot? = null
    @Volatile private var cacheAt: Long = 0L

    /**
     * Set by the MediaStore observer on any Video-table change; cleared when
     * a scan rebuilds the snapshot. The TTL below is only a backstop for the
     * rare case an observer event is missed (or permission is granted after
     * the first, empty scan — no change event fires for that).
     */
    @Volatile private var dirty = true

    /**
     * Class-level invalidation observer, registered once for the scanner's
     * (process) lifetime. Collectors of [observeLibrary] register their own
     * observer to drive emissions; this one only marks the cache stale.
     * Null handler = delivered on the binder thread, which is fine: it only
     * writes a volatile flag.
     */
    private val invalidator = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            dirty = true
        }
    }

    init {
        try {
            resolver.registerContentObserver(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, invalidator
            )
        } catch (t: Throwable) {
            // No permission yet / provider gone: scans still work (the TTL
            // backstop refreshes), we just lose push invalidation.
            AppLog.e("SCAN", "observer registration failed", t)
        }
    }

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
     * Library snapshot, served from the in-memory cache whenever it is still
     * valid (not dirty and within the TTL backstop). [force] bypasses the
     * cache and rebuilds it (observer-driven refreshes, settings rescan).
     * Thread-safe: concurrent callers serialize on the rebuild lock and share
     * one fresh result.
     */
    fun scan(force: Boolean = false): LibrarySnapshot {
        if (!force) {
            val c = cache
            if (c != null && !dirty && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) return c
        }
        synchronized(lock) {
            if (!force) {
                val c = cache
                if (c != null && !dirty && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) return c
            }
            // Cleared BEFORE the query: a change that lands mid-query sets
            // dirty again and the next scan picks it up.
            dirty = false
            val videos = queryVideos(null, null)
            if (videos == null) {
                // Query failed (permission revoked, provider down): keep
                // serving the last good snapshot and retry next call.
                dirty = true
                return cache ?: LibrarySnapshot(emptyList(), emptyList())
            }
            val fresh = LibrarySnapshot(videos, groupIntoSeries(videos))
            cache = fresh
            cacheAt = System.currentTimeMillis()
            return fresh
        }
    }

    /**
     * The in-memory snapshot if one exists — even when stale (dirty or past
     * the TTL backstop) — or null when nothing has been scanned yet.
     * Callers that tolerate a slightly stale view (the episode queue on the
     * open hot path) prefer this over [scan] to skip the MediaStore
     * round-trip entirely; [scan] remains the fallback for the no-snapshot
     * case.
     */
    fun cachedSnapshot(): LibrarySnapshot? = cache

    /**
     * Videos in ONE folder (direct children), episode-sorted. Served from
     * the cache when it is fresh; otherwise runs a folder-scoped MediaStore
     * query (DATA LIKE prefix) instead of pulling the whole Video table —
     * the efficient path for building an episode queue around one file.
     */
    fun videosInFolder(folderPath: String): List<Video> {
        val c = cache
        if (c != null && !dirty && System.currentTimeMillis() - cacheAt < CACHE_TTL_MS) {
            return c.videos.filter { it.parentPath == folderPath }.sortedWith(episodeOrder())
        }
        val prefix = if (folderPath.endsWith("/")) folderPath else "$folderPath/"
        // Direct children only: matches "<prefix>%" but not "<prefix>%/%"
        // (which would pull in subfolders as well).
        val selection = MediaStore.Video.Media.DATA + " LIKE ? AND " +
            MediaStore.Video.Media.DATA + " NOT LIKE ?"
        val videos = queryVideos(selection, arrayOf(prefix + "%", prefix + "%/%")) ?: emptyList()
        return videos.sortedWith(episodeOrder())
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

    /**
     * MediaStore Video-table query → cleaned, deduplicated [Video] list.
     * Returns null when the provider query itself fails so the caller can
     * fall back to the previous snapshot. [selection]/[selectionArgs] scope
     * the query (see [videosInFolder]); null = the whole table.
     */
    private fun queryVideos(selection: String?, selectionArgs: Array<String>?): List<Video>? {
        // Reuse previous instances for unchanged files (same uri, mtime and
        // size) so identity stays stable across incremental rescans.
        val previous = cache?.videos?.associateBy { it.path } ?: emptyMap()
        val videos = ArrayList<Video>()
        val seenPaths = HashSet<String>()
        try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs,
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
                // Column access is version-guarded: the IS_PENDING constant
                // does not exist at runtime below API 29.
                val pendingCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.Video.Media.IS_PENDING)
                } else {
                    -1
                }
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val data = c.getString(dataCol) ?: continue
                    // Skip in-progress downloads/copies (API 29+).
                    if (pendingCol >= 0 && c.getInt(pendingCol) == 1) continue
                    // Skip hidden trees (any segment starting with '.', e.g.
                    // ".thumbs"). Folders containing a .nomedia file are
                    // already excluded from MediaStore's Video table by the
                    // indexer; this guards the rest.
                    if (isHiddenPath(data)) continue
                    // MediaStore occasionally returns duplicate rows for one
                    // file; keep the first (most recent, per the sort order).
                    if (!seenPaths.add(data)) continue
                    val id = c.getLong(idCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                    ).toString()
                    val mtimeMs = c.getLong(dateCol) * 1000L
                    val sizeBytes = c.getLong(sizeCol)
                    val old = previous[data]
                    if (old != null && old.uri == uri &&
                        old.lastModifiedMs == mtimeMs && old.sizeBytes == sizeBytes
                    ) {
                        videos.add(old)
                        continue
                    }
                    videos.add(
                        Video(
                            uri = uri,
                            path = data,
                            title = cleanTitle(name),
                            durationMs = c.getLong(durCol),
                            width = c.getInt(wCol),
                            height = c.getInt(hCol),
                            sizeBytes = sizeBytes,
                            lastModifiedMs = mtimeMs,
                            mediaStoreId = id,
                            parentPath = parentOf(data),
                            displayName = name,
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            AppLog.e("SCAN", "MediaStore query failed", t)
            return null
        }
        return videos
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
        // Natural order: "Show 2" before "Show 10" (case-insensitive).
        return series.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
    }

    /**
     * Canonical watching order: season/episode pattern first (season, then
     * episode number), natural title order as tie-breaker and for files
     * without a pattern ("Episode 2" before "Episode 10").
     */
    private fun episodeOrder(): Comparator<Video> = Comparator { a, b ->
        val ea = EpisodePattern.find(a.title)
        val eb = EpisodePattern.find(b.title)
        when {
            ea != null && eb != null -> {
                val sa = ea.first ?: 0
                val sb = eb.first ?: 0
                val cmp = if (sa != sb) sa.compareTo(sb) else ea.second.compareTo(eb.second)
                if (cmp != 0) cmp else NaturalOrder.compare(a.title, b.title)
            }
            ea != null -> -1
            eb != null -> 1
            else -> NaturalOrder.compare(a.title, b.title)
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

    /**
     * Human-readable title from a MediaStore display name: extension
     * stripped, runs of `_`, `.` and `-` collapsed to single spaces
     * ("Show.S01E02.720p_x264.mkv" → "Show S01E02 720p x264"). Falls back
     * to the raw name if cleaning empties it.
     */
    private fun cleanTitle(displayName: String): String {
        val noExt = displayName.substringBeforeLast('.')
        val cleaned = noExt
            .replace(Regex("[_.-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifEmpty { noExt.ifEmpty { displayName } }
    }

    /** True when any path segment (file name included) starts with '.'. */
    private fun isHiddenPath(path: String): Boolean {
        for (segment in path.split('/')) {
            if (segment.startsWith(".")) return true
        }
        return false
    }

    private fun parentOf(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx > 0) path.substring(0, idx) else path
    }

    companion object {
        /**
         * Backstop refresh age for the cached snapshot. Observer events do
         * the real invalidation; this only guards against missed events (e.g.
         * the first scan ran before the video permission was granted).
         */
        private const val CACHE_TTL_MS = 30_000L
    }
}

data class LibrarySnapshot(
    val videos: List<Video>,
    val series: List<Series>,
)
