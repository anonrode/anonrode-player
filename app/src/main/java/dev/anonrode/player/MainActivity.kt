package dev.anonrode.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anonrode.player.core.model.Series
import dev.anonrode.player.core.ui.theme.AnonrodeTheme
import dev.anonrode.player.feature.library.LibraryViewModel

/**
 * Minimal library screen — functional placeholder.
 * The UI is being redesigned separately; keep this compiling and usable.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = AnonrodeApp.get(this)
        setContent {
            AnonrodeTheme {
                Scaffold(
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(title = { Text("Anonrode Player") })
                    }
                ) { padding ->
                    LibraryList(
                        modifier = Modifier.padding(padding),
                        scanner = app.scanner,
                        stateStore = app.stateStore,
                        onPlay = { video -> play(video.uri, video.title) },
                    )
                }
            }
        }
    }

    private fun play(uri: String, title: String) {
        val i = Intent(this, PlayerActivity::class.java)
        i.putExtra(PlayerActivity.EXTRA_URI, uri)
        i.putExtra(PlayerActivity.EXTRA_TITLE, title)
        startActivity(i)
    }
}

@Composable
fun LibraryList(
    modifier: Modifier = Modifier,
    scanner: dev.anonrode.player.core.media.library.MediaScanner,
    stateStore: dev.anonrode.player.core.media.state.MediaStateStore,
    onPlay: (dev.anonrode.player.core.model.Video) -> Unit,
) {
    val vm: LibraryViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(scanner, stateStore) as T
    })
    val state by vm.ui.collectAsState()

    if (state.loading) {
        Column(modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text("Scanning library…", modifier = Modifier.padding(12.dp))
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.series.isNotEmpty()) {
            item { SectionLabel("SERIES") }
            items(state.series) { s -> SeriesCard(s, onPlay) }
        }
        item { SectionLabel("ALL VIDEOS (${state.videoCount})") }
        items(state.videos) { v ->
            Card(modifier = Modifier.fillMaxWidth()
                .clickable { onPlay(v) }) {
                Column(Modifier.padding(12.dp)) {
                    Text(v.title, style = MaterialTheme.typography.bodyMedium)
                    Text("${v.durationMs / 60000} min · ${v.sizeBytes / 1048576} MB",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun SeriesCard(series: Series, onPlay: (dev.anonrode.player.core.model.Video) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()
        .clickable {
            series.videos.firstOrNull()?.let { onPlay(it) }
        }) {
        Column(Modifier.padding(12.dp)) {
            Text(series.name, style = MaterialTheme.typography.titleSmall)
            Text("${series.totalEpisodes} episodes · ${series.totalWatched} watched",
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
