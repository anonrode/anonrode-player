package dev.anonrode.player.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.anonrode.player.core.media.library.LibrarySnapshot
import dev.anonrode.player.core.media.library.MediaScanner
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.core.model.Series
import dev.anonrode.player.core.model.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Library view model: reactive MediaStore snapshot joined with per-video
 * playback state (continue-watching, watched counts).
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

    data class UiState(
        val loading: Boolean = true,
        val inProgress: List<InProgress> = emptyList(),
        val series: List<Series> = emptyList(),
        val videos: List<Video> = emptyList(),
        val videoCount: Int = 0,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                scanner.observeLibrary(),
                stateStore.getInProgress(),
                stateStore.getAllStates(),
            ) { snap, inProg, allStates ->
                render(snap, inProg, allStates)
            }.collect { _ui.value = it }
        }
    }

    private fun render(
        snap: LibrarySnapshot,
        inProgress: List<dev.anonrode.player.core.model.MediaState>,
        allStates: List<dev.anonrode.player.core.model.MediaState>,
    ): UiState {
        val byUri = snap.videos.associateBy { it.uri }
        // Continue watching: most recently played first (by the persisted
        // playback timestamp, not the file's MediaStore modification date).
        val continueWatching = inProgress
            .sortedByDescending { it.lastPlayedTimeMs ?: 0L }
            .mapNotNull { st ->
                val v = byUri[st.uri] ?: return@mapNotNull null
                val dur = st.durationMs ?: 0L
                val frac = if (dur > 0) {
                    (st.playbackPositionMs.toFloat() / dur).coerceIn(0f, 1f)
                } else 0f
                InProgress(v, frac, "${v.title} · ${fmtMin(st.playbackPositionMs)} / ${fmtMin(dur)}")
            }
        // Watched counts per series from finished flags over ALL states:
        // getInProgress() filters finished = 0 in SQL, so it can never
        // contribute a finished entry here.
        val finishedUris = allStates.filter { it.finished }.map { it.uri }.toSet()
        val seriesWithWatched = snap.series.map { s ->
            val w = s.videos.count { finishedUris.contains(it.uri) }
            s.copy(totalWatched = w)
        }
        return UiState(
            loading = false,
            inProgress = continueWatching,
            series = seriesWithWatched,
            videos = snap.videos,
            videoCount = snap.videos.size,
        )
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
