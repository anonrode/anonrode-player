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
            combine(scanner.observeLibrary(), stateStore.getInProgress()) { snap, inProg ->
                render(snap, inProg)
            }.collect { _ui.value = it }
        }
    }

    private fun render(snap: LibrarySnapshot, inProg: List<dev.anonrode.player.core.model.MediaState>): UiState {
        val byUri = snap.videos.associateBy { it.uri }
        val continueWatching = inProg.mapNotNull { st ->
            val v = byUri[st.uri] ?: return@mapNotNull null
            val frac = if (st.durationMs != null && st.durationMs > 0) {
                (st.playbackPositionMs.toFloat() / st.durationMs).coerceIn(0f, 1f)
            } else 0f
            InProgress(v, frac, "${v.title} · ${fmtMin(st.playbackPositionMs)} / ${fmtMin(st.durationMs ?: 0)}")
        }
        // Watched counts per series from finished flags.
        val finishedUris = inProg.filter { it.finished }.map { it.uri }.toSet()
        val seriesWithWatched = snap.series.map { s ->
            val w = s.videos.count {
                finishedUris.contains(it.uri) ||
                    (byUri[it.uri]?.let { bv -> finishedUris.contains(bv.uri) } == true)
            }
            s.copy(totalWatched = w)
        }
        return UiState(
            loading = false,
            inProgress = continueWatching.sortedByDescending { it.video.lastModifiedMs },
            series = seriesWithWatched,
            videos = snap.videos,
            videoCount = snap.videos.size,
        )
    }

    private fun fmtMin(ms: Long): String = "${ms / 60000}:${String.format("%02d", (ms % 60000) / 1000)}"
}
