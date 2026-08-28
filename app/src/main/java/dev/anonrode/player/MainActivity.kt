package dev.anonrode.player

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.ui.theme.AnonrodeTheme
import dev.anonrode.player.ui.LibraryScreen
import dev.anonrode.player.ui.SettingsScreen

class MainActivity : ComponentActivity() {

    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            AppLog.d("PERM", "permission result: " + grants)
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

    /**
     * Sidecar subtitle files (.srt/.ass…) are non-media: on Android 11+
     * they're invisible without all-files access. Videos still play
     * fine without it, so this only drives a banner, not a gate.
     */
    private var allFilesGranted by mutableStateOf(true)

    private fun refreshAllFilesGranted() {
        allFilesGranted = if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun openAllFilesSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllFilesGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = AnonrodeApp.get(this)
        // Startup self-diagnosis: if the previous run crashed (or this one's
        // Application init failed), show the captured report instead of the
        // normal UI — it renders even when the startup path itself is broken.
        val crash = CrashReporter.readLastCrash(this)
        if (crash != null || app.startupBroken) {
            val report = crash ?: "Startup failed before a report could be written."
            val canRecover = !app.startupBroken
            setContent {
                CrashReportDialog(
                    report = report,
                    onDismiss = {
                        if (canRecover) {
                            CrashReporter.clearLastCrash(this@MainActivity)
                            recreate()
                        } else {
                            finishAffinity()
                        }
                    },
                )
            }
            return
        }
        AppLog.d("MAIN", "activity create, hasVideoPermission=" + hasVideoPermission())

        setContent {
            AnonrodeTheme {
                var settingsOpen by androidx.compose.runtime.remember { mutableStateOf(false) }
                if (settingsOpen) {
                    SettingsScreen(onBack = { settingsOpen = false })
                } else if (hasVideoPermission()) {
                    // LibraryScreen draws its own top bar and bottom navigation.
                    Box(Modifier.fillMaxSize()) {
                        LibraryScreen(
                            viewModelFactory = LibraryVmFactory(app.scanner, app.stateStore),
                            onOpenVideo = { video -> play(video.uri, video.title) },
                            onOpenSettings = { settingsOpen = true },
                        )
                        if (!allFilesGranted) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth(),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Text(
                                        "Enable all-files access to find subtitle files " +
                                            "(.srt/.ass) next to your videos.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    TextButton(onClick = { openAllFilesSettings() }) {
                                        Text("Open settings")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    PermissionGate(
                        onRequest = { permissionLauncher.launch(requiredPermissions()) },
                    )
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
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(32.dp),
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

/**
 * Full-screen crash report shown instead of the normal UI after a startup
 * crash. Deliberately dependency-free (plain dark Material theme, no skin
 * prefs) so it renders even when the startup path itself is broken.
 */
@Composable
private fun CrashReportDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(colorScheme = darkColorScheme()) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Anonrode crashed") },
            text = {
                Column {
                    Text(
                        "Copy this report and send it to the dev:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            report,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash report", report))
                    Toast.makeText(context, "Report copied to clipboard", Toast.LENGTH_SHORT)
                        .show()
                }) { Text("Copy") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            },
        )
    }
}
