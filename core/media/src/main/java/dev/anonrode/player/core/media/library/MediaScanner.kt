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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * Cold start (v0.6.2 incremental pass): the scanner also persists its last
 * known-good snapshot to disk as JSON in `filesDir/library_snapshot.json`.
 * That file is loaded synchronously in [loadFromDisk] (called from
 * `AnonrodeApp.initApp` BEFORE setContent) and seeds the in-memory cache.
 * Result: the first emission of [observeLibrary] is the disk snapshot — the
 * library appears within one frame even when MediaStore is still being
 * scanned. The MediaStore scan then runs in the background, emits a delta,
 * and the LazyColumn updates incrementally.
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

    /**
     * Application-scoped IO scope for fire-and-forget persistence. The
     * scanner lives for the process, so this scope lives for the process.
     * Writes are scheduled here; reads stay synchronous on the calling
     * thread.
     */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Mutex around the disk write — if two scans finish in quick succession
     * we still want a single serialised file write, not a torn JSON. Reads
     * are NOT behind this mutex: cold start wants the file NOW.
     */
    private val writeMutex = Mutex()

    /**
     * One-shot guard for the on-disk first read. After the constructor runs
     * [loadFromDisk] (called from `AnonrodeApp.initApp`) the volatile cache
     * is already populated. If the constructor is invoked again (e.g. tests
     * that re-create the Application) the second call is a no-op.
     */
    private val diskLoadAttempted = AtomicBoolean(false)

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

    /**
     * Load the on-disk library snapshot synchronously and seed the volatile
     * cache. Called from `AnonrodeApp.initApp` BEFORE setContent runs so the
     * first [observeLibrary] emission (the disk snapshot) lands within the
     * first frame of MainActivity. Safe to call multiple times: only the
     * first call reads the file; subsequent calls are no-ops.
     *
     * The file is read with `ignoreUnknownKeys = true` for forward
     * compatibility — old shapes still load cleanly after the DTO gains
     * fields.
     */
    fun loadFromDisk() {
        if (!diskLoadAttempted.compareAndSet(false, true)) return
        val file = context.filesDir.resolve(SNAPSHOT_FILE_NAME)
        val snap = readSnapshotFile(file) ?: return
        // Seed the cache WITHOUT going through scan() so we don't touch
        // dirty (still true) or cacheAt (left at 0 so the TTL backstop
        // fires the first MediaStore scan quickly).
        cache = snap
        AppLog.d("SCAN", "loaded disk snapshot: ${snap.videos.size} videos, ${snap.series.size} series")
    }

    /**
     * Reactive library: emits the disk-cached snapshot immediately (if any)
     * on subscribe, then re-queries on every MediaStore change (debounced).
     * Each emission after the first is a [LibraryDelta] when a previous
     * snapshot exists so consumers can update incrementally.
     *
     * The first emission is the disk snapshot, which may be hours stale.
     * The MediaStore scan runs in the background and emits the
     * authoritative result shortly after; until then, the [ScanSource] tag
     * is DISK so the UI can show a subtle "refreshing…" affordance if it
     * wants to. By default the UI treats both sources the same.
     */
    fun observeLibrary(): Flow<LibraryEvent> {
        // Stream 1: MediaStore-driven refreshes. Debounced so a burst of
        // change events (the indexer committing a new file produces several
        // in a few ms) collapses to one scan.
        val mediaStoreChanges = callbackFlow {
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
            .map { computeNextEvent() }

        // Stream 2: cold-start disk snapshot, sent exactly once per
        // collector. We bypass debounce so the first frame after
        // composition has data, even before MediaStore has been touched.
        val diskSnapshot = callbackFlow {
            val initial = cache
            if (initial != null) {
                trySend(LibraryEvent.SnapshotLoaded(initial, ScanSource.DISK))
            }
            close()
        }

        return kotlinx.coroutines.flow.merge(diskSnapshot, mediaStoreChanges)
            // Only emit when the new state actually changes. We compare by
            // URI set + a content hash so an unchanged MediaStore rescan
            // does NOT invalidate the LazyColumn.
            .distinctUntilChanged { a, b ->
                a.snapshot.identityKey() == b.snapshot.identityKey()
            }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Build the next [LibraryEvent]. Computes the diff against the current
     * cache and emits a [LibraryDelta] when both old and new snapshots
     * exist; the very first MediaStore pass still emits a full SnapshotLoaded
     * so downstream code can use a single code path.
     */
    private fun computeNextEvent(): LibraryEvent {
        val previous = cache
        val fresh = scanInternal(force = true)
        if (previous == null || previous.videos.isEmpty()) {
            return LibraryEvent.SnapshotLoaded(fresh, ScanSource.MEDIASTORE)
        }
        val delta = LibraryDelta.diff(previous, fresh)
        // A delta with both no additions AND no removals is still a
        // SnapshotLoaded for the "modified only" case (LazyColumn rerenders
        // the few changed rows by key). This keeps the consumer logic flat:
        // one code path, one update.
        return if (delta.added.isEmpty() && delta.removed.isEmpty()) {
            LibraryEvent.SnapshotLoaded(fresh, ScanSource.MEDIASTORE)
        } else {
            LibraryEvent.SnapshotDelta(delta, fresh)
        }
    }

    /**
     * Library snapshot, served from the in-memory cache whenever it is still
     * valid (not dirty and within the TTL backstop). [force] bypasses the
     * cache and rebuilds it (observer-driven refreshes, settings rescan).
     * Thread-safe: concurrent callers serialize on the rebuild lock and share
     * one fresh result.
     */
    fun scan(force: Boolean = false): LibrarySnapshot {
        return scanInternal(force)
    }

    /**
     * Internal: the real scan. Returns the in-memory cache when it is still
     * valid (not dirty, within TTL), otherwise rebuilds from MediaStore and
     * persists the result to disk in the background.
     */
    private fun scanInternal(force: Boolean): LibrarySnapshot {
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
            // Persist the fresh result on the IO scope. Fire-and-forget: a
            // write failure is non-fatal (we still have the in-memory cache
            // for this process) but is logged.
            schedulePersist(fresh)
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
     *
     * After [loadFromDisk] the cache is populated without a scan, so this
     * returns the on-disk library on the very first call.
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

    /**
     * Persist the fresh snapshot to disk on the IO scope. The write is
     * serialised behind [writeMutex] so two scans finishing in quick
     * succession still produce one consistent file. Failure is logged and
     * swallowed: the in-memory cache is the source of truth for the running
     * process; disk is only a cold-start accelerator for the next run.
     */
    private fun schedulePersist(snap: LibrarySnapshot) {
        val file = context.filesDir.resolve(SNAPSHOT_FILE_NAME)
        persistScope.launch {
            writeMutex.withLock {
                try {
                    val dto = PersistedLibrarySnapshot.from(snap)
                    val json = JSON.encodeToString(PersistedLibrarySnapshot.serializer(), dto)
                    // Write atomically: write to a temp file, then rename.
                    // A torn read on the next cold start would decode to an
                    // empty list (we treat decode failure as "no cache").
                    val tmp = File(file.parentFile, SNAPSHOT_FILE_NAME + ".tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(file)) {
                        // Fallback for filesystems that don't allow rename
                        // over an existing target.
                        file.writeText(json)
                        tmp.delete()
                    }
                } catch (t: Throwable) {
                    AppLog.e("SCAN", "failed to persist snapshot", t)
                }
            }
        }
    }

    /**
     * Read the on-disk snapshot synchronously. Returns null when the file
     * is missing, unreadable, or decodes to a malformed value. Corrupt
     * files are deleted so the next persist starts clean.
     */
    private fun readSnapshotFile(file: File): LibrarySnapshot? {
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val text = file.readText()
            if (text.isBlank()) return null
            val dto = JSON.decodeFromString(PersistedLibrarySnapshot.serializer(), text)
            dto.toSnapshot()
        } catch (t: Throwable) {
            AppLog.e("SCAN", "discarding corrupt disk snapshot", t)
            // Best-effort delete so we don't keep failing on every cold
            // start. The next MediaStore scan will write a fresh file.
            try { file.delete() } catch (_: Throwable) {}
            null
        }
    }

    companion object {
        /**
         * Backstop refresh age for the cached snapshot. Observer events do
         * the real invalidation; this only guards against missed events (e.g.
         * the first scan ran before the video permission was granted).
         */
        private const val CACHE_TTL_MS = 30_000L

        /**
         * On-disk snapshot file. ~100KB for 1k videos (just metadata —
         * no thumbnails). Replaced atomically: see [schedulePersist].
         */
        const val SNAPSHOT_FILE_NAME = "library_snapshot.json"

        private val JSON = Json {
            ignoreUnknownKeys = true
            // Reasonable defaults for a metadata-only file with no booleans
            // or polymorphic types: pretty-print is fine for ~100KB.
        }
    }
}

/**
 * The in-memory snapshot, served to UI and the open-video hot path.
 * Plain data class; no serialisation concerns leak into UI code.
 */
data class LibrarySnapshot(
    val videos: List<Video>,
    val series: List<Series>,
) {
    /**
     * Cheap identity key for `distinctUntilChanged` on the flow. Two
     * snapshots with the same URI set size AND the same (URI, size, mtime)
     * pairs produce the same key — that's exactly when the LazyColumn
     * doesn't need to rerun. Content hash collisions are not catastrophic
     * (worst case: a wasted recomposition), so a non-cryptographic hash is
     * appropriate here.
     */
    fun identityKey(): Long {
        var h = 1125899906842597L // large prime
        h = 31 * h + videos.size
        for (v in videos) {
            h = 31 * h + v.uri.hashCode()
            h = 31 * h + v.sizeBytes.hashCode()
            h = 31 * h + v.lastModifiedMs.hashCode()
        }
        return h
    }
}

/**
 * On-disk representation. Kept structurally identical to [LibrarySnapshot]
 * today, but a separate type so we can evolve the file format (add a
 * persisted scan timestamp, library version, etc.) without touching the
 * in-memory model. The DTO is the only thing that knows about the file
 * format; [LibrarySnapshot] is the only thing the UI knows about.
 */
@Serializable
private data class PersistedLibrarySnapshot(
    val version: Int = CURRENT_VERSION,
    val videos: List<Video>,
    val series: List<Series>,
) {
    fun toSnapshot(): LibrarySnapshot = LibrarySnapshot(videos, series)

    companion object {
        const val CURRENT_VERSION = 1
        fun from(snap: LibrarySnapshot) = PersistedLibrarySnapshot(
            version = CURRENT_VERSION,
            videos = snap.videos,
            series = snap.series,
        )
    }
}

/**
 * Where a [LibraryEvent] came from. Surfaced in the API so the UI can
 * show a "refreshing…" affordance for MediaStore events when the visible
 * data is the (possibly stale) disk snapshot.
 */
enum class ScanSource { DISK, MEDIASTORE }

/**
 * A change in the library. The view model renders the same either way —
 * `SnapshotLoaded` is the simpler case (the first event on cold start or
 * the first MediaStore pass when there was no disk cache), `SnapshotDelta`
 * carries the per-URI additions / removals / modifications so the
 * LazyColumn can patch itself in place.
 */
sealed interface LibraryEvent {
    val snapshot: LibrarySnapshot

    /** Whole new snapshot — use it directly. */
    data class SnapshotLoaded(
        override val snapshot: LibrarySnapshot,
        val source: ScanSource,
    ) : LibraryEvent

    /** Partial update against a previous snapshot. [snapshot] is the new
     *  authoritative state; the lists are the per-URI delta against the
     *  previous emission. */
    data class SnapshotDelta(
        val delta: LibraryDelta,
        override val snapshot: LibrarySnapshot,
    ) : LibraryEvent
}

/**
 * Set-difference of two library snapshots, keyed by [Video.uri]. Cheap to
 * compute (HashSet union/intersect); consumed by the LazyColumn keying
 * logic so a 1k-addition update does not re-render the existing 19k rows.
 */
data class LibraryDelta(
    /** Brand-new videos (URI in [new] but not [old]). */
    val added: List<Video>,
    /** Removed videos (URI in [old] but not [new]). */
    val removed: List<Video>,
    /**
     * Videos whose (size, mtime) changed but URI is stable. The new instance
     * is the authoritative one — Compose's key-based LazyColumn re-renders
     * just these rows.
     */
    val modified: List<Video>,
) {
    companion object {
        fun diff(old: LibrarySnapshot, new: LibrarySnapshot): LibraryDelta {
            if (old.videos.isEmpty()) {
                return LibraryDelta(added = new.videos, removed = emptyList(), modified = emptyList())
            }
            val oldByUri = old.videos.associateBy { it.uri }
            val newByUri = new.videos.associateBy { it.uri }
            val added = ArrayList<Video>()
            val modified = ArrayList<Video>()
            for ((uri, v) in newByUri) {
                val o = oldByUri[uri]
                if (o == null) added.add(v)
                else if (o.sizeBytes != v.sizeBytes || o.lastModifiedMs != v.lastModifiedMs) {
                    modified.add(v)
                }
            }
            val removed = ArrayList<Video>()
            for ((uri, v) in oldByUri) {
                if (uri !in newByUri) removed.add(v)
            }
            return LibraryDelta(added, removed, modified)
        }
    }
}
