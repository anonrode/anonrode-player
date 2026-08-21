package dev.anonrode.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.anonrode.player.core.model.Series
import dev.anonrode.player.core.model.Video
import dev.anonrode.player.core.ui.theme.AnonrodeTheme
import dev.anonrode.player.feature.library.LibraryViewModel

class MainActivity : ComponentActivity() {

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.values.any { it }) {
                recreate() // granted → rebuild and rescan the library
            }
        }

    private fun requiredPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasVideoPermission(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

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
                    if (hasVideoPermission()) {
                        LibraryList(
                            modifier = Modifier.padding(padding),
                            scanner = app.scanner,
                            stateStore = app.stateStore,
                            onPlay = { video -> play(video.uri, video.title) },
                        )
                    } else {
                        PermissionGate(
                            modifier = Modifier.padding(padding),
                            onRequest = { permissionLauncher.launch(requiredPermissions()) },
                        )
                    }
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
fun PermissionGate(modifier: Modifier = Modifier, onRequest: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Anonrode needs access to your videos",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "Your library is built from the video files on this device — nothing is uploaded anywhere.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onRequest) {
            Text("Grant video access")
        }
        Text(
            "If you previously picked \"Partial access\", switch to full access for all folders to appear.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
fun LibraryList(
    modifier: Modifier = Modifier,
    scanner: dev.anonrode.player.core.media.library.MediaScanner,
    stateStore: dev.anonrode.player.core.media.state.MediaStateStore,
    onPlay: (Video) -> Unit,
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
fun SeriesCard(series: Series, onPlay: (Video) -> Unit) {
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
