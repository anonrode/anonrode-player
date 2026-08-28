package dev.anonrode.player.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anonrode.player.core.media.library.LibrarySnapshot
import dev.anonrode.player.core.media.library.MediaScanner
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.core.model.EpisodePattern
import dev.anonrode.player.core.model.MediaState
import dev.anonrode.player.core.model.Series
import dev.anonrode.player.core.model.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** Sort modes for the FOLDERS section (the design's "sort ▾" menu). */
enum class FolderSort(val label: String) {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    RECENT("Recently played"),
    MOST_VIDEOS("Most videos"),
}

/**
 * Library view model: reactive MediaStore snapshot joined with per-video
 * playback state (continue-watching, watched counts, resume positions).
 *
 * The joined snapshot is cached here as the single source of truth for the
 * whole library UI: search, sorting and folder drill-down all operate on the
 * in-memory data and NEVER trigger a MediaStore rescan. Scans happen only via
 * [MediaScanner.observeLibrary] (initial load + MediaStore change observer)
 * or an explicit [rescan] — both funnel into the same pipeline, and neither
 * resets the user's query / sort / drill-down state.
 */
class LibraryViewModel(
    private val scanner: MediaScanner,
    private val stateStore: MediaStateStore,
) : ViewModel() {

    data class InProgress(
        val video: Video,
        val fraction: Float,
        val label: String,
    )

    /** One episode row inside a folder drill-down. */
    data class EpisodeItem(
        val video: Video,
        /** Watched fraction 0..1 (position/duration; 1 when finished). */
        val fraction: Float,
        val finished: Boolean,
        /** "12:34 / 43:26" while partially watched, else null. */
        val resumeLabel: String?,
    )

    /** One row in the global search results. */
    data class SearchHit(
        val video: Video,
        val folderName: String,
        val fraction: Float,
        val finished: Boolean,
    )

    data class UiState(
        val loading: Boolean = true,
        /** Continue watching, most recently played first, deduplicated. */
        val inProgress: List<InProgress> = emptyList(),
        /** Folders sorted per [sort]. */
        val series: List<Series> = emptyList(),
        val videos: List<Video> = emptyList(),
        val videoCount: Int = 0,
        /** Current search query ("" = browse mode). */
        val query: String = "",
        val sort: FolderSort = FolderSort.NAME_ASC,
        /** folderPath → episodes in display order, with progress info. */
        val episodesByFolder: Map<String, List<EpisodeItem>> = emptyMap(),
        /** Live search results; empty while [query] is blank. */
        val searchHits: List<SearchHit> = emptyList(),
    )

    /**
     * The latest raw library snapshot. Exposed so other features (e.g. the
     * player's episode queue) can consume the already-scanned library instead
     * of re-querying MediaStore on every video open.
     */
    private val _snapshot = MutableStateFlow(LibrarySnapshot(emptyList(), emptyList()))
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()

    /** Synchronous accessor for the cached snapshot (never scans). */
    fun currentSnapshot(): LibrarySnapshot = _snapshot.value

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Joined library data, independent of query/sort. */
    private data class LibData(
        val inProgress: List<InProgress> = emptyList(),
        val series: List<Series> = emptyList(),
        val videos: List<Video> = emptyList(),
        val episodesByFolder: Map<String, List<EpisodeItem>> = emptyMap(),
        val folderLastPlayed: Map<String, Long> = emptyMap(),
        val folderNameByPath: Map<String, String> = emptyMap(),
        val fractionByUri: Map<String, Float> = emptyMap(),
        val finishedUris: Set<String> = emptySet(),
    )

    private var libData = LibData()
    private var query: String = ""
    private var sort: FolderSort = FolderSort.NAME_ASC

    /** Explicit rescan trigger; the seed value is skipped via drop(1). */
    private val rescanRequests = MutableStateFlow(0)

    init {
        // Single scan pipeline: observer-driven snapshots + explicit rescan
        // requests (the only two places a scan is ever triggered).
        val library = merge(
            scanner.observeLibrary(),
            rescanRequests
                .drop(1)
                .map { scanner.scan(force = true) }
                .flowOn(Dispatchers.IO),
        )
        viewModelScope.launch {
            combine(
                library,
                stateStore.getInProgress(),
                stateStore.getAllStates(),
            ) { snap, inProg, allStates ->
                snap to join(snap, inProg, allStates)
            }.collect { (snap, data) ->
                _snapshot.value = snap
                libData = data
                rebuildUi()
            }
        }
    }

    /**
     * Force a library rescan through the single pipeline above. Query, sort
     * and drill-down state are untouched — the refreshed snapshot is simply
     * re-joined underneath them.
     */
    fun rescan() {
        rescanRequests.value++
    }

    /** Live-filter the library as the user types (pure in-memory filter). */
    fun setQuery(raw: String) {
        query = raw
        rebuildUi()
    }

    /** Sort the FOLDERS section (in-memory; not persisted yet). */
    fun setSort(mode: FolderSort) {
        sort = mode
        rebuildUi()
    }

    private fun rebuildUi() {
        val q = query.trim()
        _ui.value = UiState(
            loading = false,
            inProgress = libData.inProgress,
            series = sortSeries(libData.series, sort, libData.folderLastPlayed),
            videos = libData.videos,
            videoCount = libData.videos.size,
            query = query,
            sort = sort,
            episodesByFolder = libData.episodesByFolder,
            searchHits = if (q.isEmpty()) emptyList() else search(q),
        )
    }

    /** Join a fresh snapshot with playback state. Pure — no IO here. */
    private fun join(
        snap: LibrarySnapshot,
        inProgressStates: List<MediaState>,
        allStates: List<MediaState>,
    ): LibData {
        val byUri = snap.videos.associateBy { it.uri }
        val statesByUri = allStates.associateBy { it.uri }
        val finishedUris = allStates.filter { it.finished }.map { it.uri }.toSet()
        val folderNameByPath = snap.series.associate { it.folderPath to it.name }

        fun folderNameOf(v: Video): String =
            folderNameByPath[v.parentPath]
                ?: v.parentPath.substringAfterLast('/').ifEmpty { v.parentPath }

        // Watched fraction per URI (finished counts as fully watched).
        val fractionByUri = HashMap<String, Float>()
        for (st in allStates) {
            val dur = st.durationMs ?: 0L
            val frac = when {
                st.finished -> 1f
                dur > 0 && st.playbackPositionMs > 0L ->
                    (st.playbackPositionMs.toFloat() / dur).coerceIn(0f, 1f)
                else -> 0f
            }
            if (frac > 0f) fractionByUri[st.uri] = frac
        }

        // Continue watching: most recently played first (by the persisted
        // playback timestamp, not the file's MediaStore modification date),
        // deduplicated by URI, dropping videos that left the library.
        val continueWatching = inProgressStates
            .distinctBy { it.uri }
            .sortedByDescending { it.lastPlayedTimeMs ?: 0L }
            .mapNotNull { st ->
                val v = byUri[st.uri] ?: return@mapNotNull null
                val dur = st.durationMs ?: 0L
                val frac = if (dur > 0) {
                    (st.playbackPositionMs.toFloat() / dur).coerceIn(0f, 1f)
                } else 0f
                InProgress(v, frac, "${folderNameOf(v)} · ${fmtMin(st.playbackPositionMs)} / ${fmtMin(dur)}")
            }

        // Per-folder episode rows (display order + progress) and folder stats.
        val episodesByFolder = HashMap<String, List<EpisodeItem>>()
        val folderLastPlayed = HashMap<String, Long>()
        val seriesWithWatched = snap.series.map { s ->
            val items = s.videos.sortedWith(naturalVideoOrder).map { v ->
                val st = statesByUri[v.uri]
                val finished = st?.finished == true
                val resume = if (!finished && st != null && st.playbackPositionMs > 0L) {
                    val d = st.durationMs ?: 0L
                    if (d > 0) "${fmtMin(st.playbackPositionMs)} / ${fmtMin(d)}" else null
                } else null
                EpisodeItem(v, if (finished) 1f else (fractionByUri[v.uri] ?: 0f), finished, resume)
            }
            episodesByFolder[s.folderPath] = items
            val last = s.videos.maxOfOrNull { statesByUri[it.uri]?.lastPlayedTimeMs ?: 0L } ?: 0L
            if (last > 0L) folderLastPlayed[s.folderPath] = last
            s.copy(
                totalWatched = s.videos.count { finishedUris.contains(it.uri) },
                videos = items.map { it.video },
            )
        }

        return LibData(
            inProgress = continueWatching,
            series = seriesWithWatched,
            videos = snap.videos,
            episodesByFolder = episodesByFolder,
            folderLastPlayed = folderLastPlayed,
            folderNameByPath = folderNameByPath,
            fractionByUri = fractionByUri,
            finishedUris = finishedUris,
        )
    }

    private fun sortSeries(
        list: List<Series>,
        mode: FolderSort,
        lastPlayed: Map<String, Long>,
    ): List<Series> = when (mode) {
        FolderSort.NAME_ASC -> list.sortedBy { it.name.lowercase() }
        FolderSort.NAME_DESC -> list.sortedByDescending { it.name.lowercase() }
        FolderSort.MOST_VIDEOS ->
            list.sortedWith(compareByDescending<Series> { it.totalEpisodes }.thenBy { it.name.lowercase() })
        // Never-played folders (0) sink to the bottom, alphabetical within.
        FolderSort.RECENT ->
            list.sortedWith(compareByDescending<Series> { lastPlayed[it.folderPath] ?: 0L }.thenBy { it.name.lowercase() })
    }

    /** Case-insensitive substring search across episode titles and folder
     *  names. Title matches come first, folder-name matches after. */
    private fun search(needleRaw: String): List<SearchHit> {
        val needle = needleRaw.lowercase()
        val titleHits = ArrayList<SearchHit>()
        val folderHits = ArrayList<SearchHit>()
        for (v in libData.videos) {
            val folder = libData.folderNameByPath[v.parentPath]
                ?: v.parentPath.substringAfterLast('/').ifEmpty { v.parentPath }
            if (v.title.lowercase().contains(needle)) {
                titleHits.add(hitFor(v, folder))
            } else if (folder.lowercase().contains(needle)) {
                folderHits.add(hitFor(v, folder))
            }
        }
        return titleHits + folderHits
    }

    private fun hitFor(v: Video, folderName: String): SearchHit =
        SearchHit(
            video = v,
            folderName = folderName,
            fraction = libData.fractionByUri[v.uri] ?: 0f,
            finished = libData.finishedUris.contains(v.uri),
        )

    /**
     * Natural-order title comparison: digit runs compare numerically so
     * "Episode 2" sorts before "Episode 10".
     */
    private fun naturalCompare(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
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

    /**
     * Display order inside a folder: season/episode pattern first (mirrors
     * the player's EpisodeQueue ordering), natural title order otherwise.
     */
    private val naturalVideoOrder: Comparator<Video> = Comparator { a, b ->
        val ea = EpisodePattern.find(a.title)
        val eb = EpisodePattern.find(b.title)
        when {
            ea != null && eb != null -> {
                val sa = ea.first ?: 0
                val sb = eb.first ?: 0
                val cmp = if (sa != sb) sa.compareTo(sb) else ea.second.compareTo(eb.second)
                if (cmp != 0) cmp else naturalCompare(a.title, b.title)
            }
            ea != null -> -1
            eb != null -> 1
            else -> naturalCompare(a.title, b.title)
        }
    }

    /** m:ss below one hour; switches to h:mm once it crosses an hour. */
    private fun fmtMin(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val sec = totalSec % 60
        return if (h > 0) "$h:${m.toString().padStart(2, '0')}"
        else "$m:${sec.toString().padStart(2, '0')}"
    }
}
