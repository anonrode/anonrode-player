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
import kotlinx.coroutines.launch

/**
 * Library view model: reactive MediaStore snapshot joined with per-video
 * playback state (watched counts, progress) for the series grid.
 *
 * Minimal on purpose — the UI layer will be redesigned separately.
 */
class LibraryViewModel(
    private val scanner: MediaScanner,
    private val stateStore: MediaStateStore,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val series: List<Series> = emptyList(),
        val videos: List<Video> = emptyList(),
        val videoCount: Int = 0,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            scanner.observeLibrary().collect { snap ->
                _ui.value = render(snap)
            }
        }
    }

    private fun render(snap: LibrarySnapshot): UiState =
        UiState(loading = false, series = snap.series, videos = snap.videos,
            videoCount = snap.videos.size)

    companion object {
        /** Episode index for ordering within a series detail screen. */
        fun episodeIndex(title: String): Int? =
            dev.anonrode.player.core.model.EpisodePattern.episodeIndex(title)
    }
}
