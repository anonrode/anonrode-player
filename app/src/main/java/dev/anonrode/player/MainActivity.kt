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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.anonrode.player.core.ui.theme.AnonrodeTheme
import dev.anonrode.player.ui.LibraryScreen

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
                        TopAppBar(title = { Text("Anonrode") })
                    }
                ) { padding ->
                    if (hasVideoPermission()) {
                        LibraryScreen(
                            modifier = Modifier.padding(padding),
                            loading = false,
                            viewModelFactory = LibraryVmFactory(app.scanner, app.stateStore),
                            onOpenVideo = { video -> play(video.uri, video.title) },
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
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URI, uri)
            putExtra(PlayerActivity.EXTRA_TITLE, title)
        })
    }
}

class LibraryVmFactory(
    private val scanner: dev.anonrode.player.core.media.library.MediaScanner,
    private val stateStore: dev.anonrode.player.core.media.state.MediaStateStore,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        dev.anonrode.player.feature.library.LibraryViewModel(scanner, stateStore) as T
}

@Composable
fun PermissionGate(modifier: Modifier = Modifier, onRequest: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Anonrode needs access to your videos", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your library is built from the video files on this device — nothing is uploaded anywhere.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onRequest) { Text("Grant video access") }
        Text(
            "If you previously picked \"Partial access\", switch to full access for all folders to appear.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}
