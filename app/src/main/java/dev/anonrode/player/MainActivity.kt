package dev.anonrode.player

import android.Manifest
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
                permissionGranted = true
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
     * Last known permission state, mirrored in [onResume]: if the user
     * granted access while we were in system settings (i.e. outside the
     * activity-result flow), rebuild instead of stranding them on the gate.
     */
    private var permissionGranted = false

    /**
     * Sidecar subtitle files (.srt/.ass…) are non-media: on Android 11+
     * they're invisible without all-files access. Videos still play
     * fine without it, so this only drives a banner, not a gate.
     */
    private var allFilesGranted by mutableStateOf(true)

    private fun refreshAllFilesGranted() {
        allFilesGranted = if (Build.VERSION.SDK_INT >= 30) {
            try {
                Environment.isExternalStorageManager()
            } catch (_: Throwable) {
                true
            }
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
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                openAppDetailsSettings()
            }
        }
    }

    private fun openAppDetailsSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (_: Exception) {
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllFilesGranted()
        // Permission granted while we were away (system settings path):
        // rebuild so the library appears without another tap.
        if (!permissionGranted && hasVideoPermission()) {
            permissionGranted = true
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = AnonrodeApp.get(this)
        permissionGranted = hasVideoPermission()
        // Startup self-diagnosis: if the previous run crashed (or this one's
        // Application init failed), show the captured report instead of the
        // normal UI — it renders even when the startup path itself is broken.
        // The DI-readiness check is belt-and-braces for a half-initialized
        // container that never flipped startupBroken.
        val crash = CrashReporter.readLastCrash(this)
        val diReady = app.isReady
        if (crash != null || app.startupBroken || !diReady) {
            val report = crash ?: "Startup failed before a report could be written."
            val canRecover = !app.startupBroken && diReady
            setContent {
                CrashReportDialog(
                    report = report,
                    canRecover = canRecover,
                    onDismiss = {
                        if (canRecover) {
                            CrashReporter.clearLastCrash(this@MainActivity)
                            recreate()
                        } else {
                            CrashReporter.restartApp(this@MainActivity)
                        }
                    },
                )
            }
            return
        }
        AppLog.d("MAIN", "activity create, hasVideoPermission=" + hasVideoPermission())

        setContent {
            AnonrodeTheme {
                // Saveable so a configuration change (rotation, dark-mode
                // switch) doesn't drop the user out of whichever screen
                // they were on.
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                if (settingsOpen) {
                    SettingsScreen(onBack = { settingsOpen = false })
                } else if (hasVideoPermission()) {
                    // LibraryScreen draws its own top bar and bottom navigation.
                    Box(Modifier.fillMaxSize()) {
                        LibraryScreen(
                            viewModelFactory = LibraryVmFactory(
                                app.scanner,
                                app.stateStore,
                                app.playerSettingsDataStore,
                            ),
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
                        onOpenAppSettings = { openAppDetailsSettings() },
                        allFilesGranted = allFilesGranted,
                        onAllFilesSettings = { openAllFilesSettings() },
                    )
                }
            }
        }
    }

    private fun play(uri: String, title: String) {
        try {
            startActivity(Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URI, uri)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
            })
        } catch (e: Exception) {
            // Never white-screen on a launch failure — surface it instead.
            AppLog.e("MAIN", "failed to launch player", e)
            Toast.makeText(this, "Could not open video", Toast.LENGTH_SHORT).show()
        }
    }
}

class LibraryVmFactory(
    private val scanner: dev.anonrode.player.core.media.library.MediaScanner,
    private val stateStore: dev.anonrode.player.core.media.state.MediaStateStore,
    private val settings: androidx.datastore.core.DataStore<dev.anonrode.player.core.datastore.PlayerSettings>,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        dev.anonrode.player.feature.library.LibraryViewModel(scanner, stateStore, settings) as T
}

@Composable
fun PermissionGate(
    modifier: Modifier = Modifier,
    onRequest: () -> Unit,
    onOpenAppSettings: () -> Unit = {},
    allFilesGranted: Boolean = true,
    onAllFilesSettings: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Anonrode needs access to your videos", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your library is built from the video files on this device — nothing is uploaded anywhere.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text("Grant video access") }
        Text(
            "If you previously picked \"Partial access\", switch to full access for all folders to appear.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 16.dp),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onOpenAppSettings) { Text("Open app settings") }

        // Soft second step: videos play fine without all-files access, but
        // sidecar subtitle files (.srt/.ass) are non-media and stay invisible
        // on Android 11+ without it. Warn + offer the settings shortcut.
        if (!allFilesGranted) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "Optional: all-files access for subtitles",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Subtitle files (.srt/.ass) stored next to your videos are not " +
                            "media, so Android hides them from this app unless all-files " +
                            "access is enabled. Playback works without it — sidecar " +
                            "subtitles and auto-sync won't.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    TextButton(onClick = onAllFilesSettings) { Text("Enable all-files access") }
                }
            }
        }
    }
}

/**
 * Full-screen crash report shown instead of the normal UI after a startup
 * crash. Deliberately dependency-free (plain dark Material theme, no skin
 * prefs) so it renders even when the startup path itself is broken.
 *
 * Shows the exception class + message up top and the full report (stack
 * frames included) in scrollable monospace text, with Copy / Share /
 * Restart actions. Every action is null-safe: a failure only shows a
 * toast, never another crash.
 */
@Composable
private fun CrashReportDialog(
    report: String,
    canRecover: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    MaterialTheme(colorScheme = darkColorScheme()) {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF16161C),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text(
                        "Anonrode crashed",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        CrashReporter.summarize(report),
                        color = Color(0xFFFFB454),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (canRecover) {
                            "Copy or share this report with the dev, then continue — " +
                                "the app will try to carry on normally."
                        } else {
                            "Startup failed before the app could initialize. Copy or " +
                                "share this report, then restart the app."
                        },
                        color = Color(0xFFAAAAAA),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 300.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0B0B0F))
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                report,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = Color(0xFFD8D8E0),
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                val ok = CrashReporter.copyReport(context, report)
                                Toast.makeText(
                                    context,
                                    if (ok) "Report copied to clipboard" else "Copy failed",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Copy") }
                        OutlinedButton(
                            onClick = {
                                if (!CrashReporter.shareReport(context, report)) {
                                    Toast.makeText(context, "No app available to share", Toast.LENGTH_SHORT)
                                        .show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Share") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = { CrashReporter.restartApp(context) },
                            modifier = Modifier.weight(1f),
                        ) { Text("Restart app") }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (canRecover) "Continue" else "Exit")
                        }
                    }
                }
            }
        }
    }
}
