package dev.anonrode.player.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StayCurrentPortrait
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.anonrode.player.AnonrodeApp
import dev.anonrode.player.PlayerPrefs
import dev.anonrode.player.core.database.MediaDatabase
import dev.anonrode.player.core.datastore.DecoderPriority
import dev.anonrode.player.core.datastore.PlayerSettings
import dev.anonrode.player.core.datastore.ResumeBehavior
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import dev.anonrode.player.core.ui.theme.Skin
import dev.anonrode.player.core.ui.theme.SkinPalette
import dev.anonrode.player.core.ui.theme.ThemePrefs
import dev.anonrode.player.core.ui.theme.rememberSkinPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ── Settings screen ─────────────────────────────────────────────────────
 * Every row reads/writes the PlayerSettings DataStore (or ThemePrefs for
 * the skin), so each choice is persistent and applied live — the player
 * activity collects the same flow. Rows are grouped into sections:
 *
 *   PLAYBACK          resume behavior · default speed · autoplay · sleep
 *                     timer · keep screen on · background playback ·
 *                     decoder priority
 *   SUBTITLES         auto-sync · size · position · color · bold ·
 *                     preferred language
 *   GESTURES          double-tap / swipe seek · volume / brightness
 *                     gestures · pinch zoom · seek step · auto-hide ·
 *                     fast-seek threshold
 *   AUDIO             volume boost
 *   APPEARANCE        theme (skin picker, live preview)
 *   LIBRARY & DATA    rescan library · clear playback progress
 *
 * Per-video state (zoom, audio/subtitle track, position) intentionally
 * lives in Room, not here — the player HUD owns those controls.
 * ------------------------------------------------------------------------- */

/** Dialog identifiers for [SettingsScreen]'s single dialog slot. */
private const val DLG_RESUME = "resume"
private const val DLG_SPEED = "speed"
private const val DLG_SLEEP = "sleep"
private const val DLG_DECODER = "decoder"
private const val DLG_SUB_SIZE = "subsize"
private const val DLG_SUB_POS = "subpos"
private const val DLG_SUB_COLOR = "subcolor"
private const val DLG_SUB_LANG = "sublang"
private const val DLG_BOOST = "boost"
private const val DLG_THEME = "theme"
private const val DLG_CLEAR = "clear"

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val palette = rememberSkinPalette()
    val themePrefs = remember { ThemePrefs.get(context) }
    val activeSkin by themePrefs.skin.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val dataStore = context.playerSettingsDataStore
    val settings by dataStore.data.collectAsState(initial = PlayerSettings())

    // Only one dialog at a time; null = none.
    var openDialog by remember { mutableStateOf<String?>(null) }
    var rescanning by remember { mutableStateOf(false) }

    fun persist(transform: (PlayerSettings) -> PlayerSettings) {
        coroutineScope.launch { dataStore.updateData { transform(it) } }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(palette.background)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            // ── header ───────────────────────────────────────────
            item("header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 18.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SettingsBackButton(palette = palette, onClick = onBack)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Settings",
                        color = palette.text,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp),
                    )
                    Text(
                        activeSkin.displayName,
                        color = palette.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ══ PLAYBACK ═════════════════════════════════════════
            item("sec-playback") { SectionHeader(palette, "Playback") }

            item("resume") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.History,
                    title = "Resume position",
                    subtitle = "What to do with a saved position",
                    trailing = {
                        ValueText(
                            palette = palette,
                            text = when (settings.resumeBehavior) {
                                ResumeBehavior.ALWAYS_RESUME -> "Resume"
                                ResumeBehavior.ALWAYS_START_OVER -> "Start over"
                                else -> "Ask"
                            },
                        )
                    },
                    onClick = { openDialog = DLG_RESUME },
                )
            }

            item("speed") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Speed,
                    title = "Default playback speed",
                    subtitle = "Used until a video has its own speed",
                    trailing = {
                        ValueText(palette = palette, text = "${settings.defaultPlaybackSpeed}×")
                    },
                    onClick = { openDialog = DLG_SPEED },
                )
            }

            item("autoplay") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.PlayCircle,
                    title = "Autoplay next episode",
                    subtitle = "Auto-advance with countdown",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.autoAdvance,
                            onToggle = { persist { it.copy(autoAdvance = !it.autoAdvance) } },
                        )
                    },
                )
            }

            item("sleep") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Timer,
                    title = "Sleep timer",
                    subtitle = "Stop playback automatically",
                    trailing = {
                        ValueText(
                            palette = palette,
                            text = sleepLabel(settings.sleepTimerMinutes),
                        )
                    },
                    onClick = { openDialog = DLG_SLEEP },
                )
            }

            item("keepScreenOn") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.StayCurrentPortrait,
                    title = "Keep screen on",
                    subtitle = "Prevent the display sleeping during playback",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.keepScreenOn,
                            onToggle = { persist { it.copy(keepScreenOn = !it.keepScreenOn) } },
                        )
                    },
                )
            }

            item("backgroundPlayback") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Headphones,
                    title = "Background playback",
                    subtitle = "Keep playing when the app is hidden",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.backgroundPlayback,
                            onToggle = { persist { it.copy(backgroundPlayback = !it.backgroundPlayback) } },
                        )
                    },
                )
            }

            item("decoder") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Memory,
                    title = "Decoder priority",
                    subtitle = "Which video decoder to prefer",
                    trailing = {
                        ValueText(
                            palette = palette,
                            text = when (settings.decoderPriority) {
                                DecoderPriority.PREFER_APP -> "App SW"
                                DecoderPriority.DEVICE_ONLY -> "HW only"
                                else -> "HW + SW"
                            },
                        )
                    },
                    onClick = { openDialog = DLG_DECODER },
                )
            }

            // ══ SUBTITLES ════════════════════════════════════════
            item("sec-subs") { SectionHeader(palette, "Subtitles") }

            item("autosync") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.AutoAwesome,
                    title = "Subtitle auto-sync",
                    subtitle = "Speech-match · drift-aware",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.autoSyncEnabled,
                            onToggle = { persist { it.copy(autoSyncEnabled = !it.autoSyncEnabled) } },
                        )
                    },
                )
            }

            item("subsize") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.FormatSize,
                    title = "Subtitle size",
                    subtitle = "Font size of rendered subtitles",
                    trailing = {
                        ValueText(palette = palette, text = subtitleSizeLabel(settings.subtitleSize))
                    },
                    onClick = { openDialog = DLG_SUB_SIZE },
                )
            }

            item("subpos") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.ArrowDownward,
                    title = "Subtitle position",
                    subtitle = "Vertical placement on screen",
                    trailing = {
                        ValueText(palette = palette, text = subtitlePosLabel(settings.subtitlePosition))
                    },
                    onClick = { openDialog = DLG_SUB_POS },
                )
            }

            item("subcolor") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.FormatColorText,
                    title = "Subtitle color",
                    subtitle = "Text color of rendered subtitles",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ValueText(palette = palette, text = subtitleColorLabel(settings.subtitleColor))
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(subtitleColorValue(settings.subtitleColor))
                                    .border(1.dp, palette.rowLine, CircleShape),
                            )
                        }
                    },
                    onClick = { openDialog = DLG_SUB_COLOR },
                )
            }

            item("subbold") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.FormatBold,
                    title = "Bold subtitles",
                    subtitle = "MX-style bold outlined look",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.subtitleBold,
                            onToggle = { persist { it.copy(subtitleBold = !it.subtitleBold) } },
                        )
                    },
                )
            }

            item("sublang") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Translate,
                    title = "Subtitle language",
                    subtitle = "Preferred language for online search",
                    trailing = {
                        ValueText(
                            palette = palette,
                            text = when (settings.defaultSubtitleLanguage) {
                                "chi" -> "中文"
                                "eng" -> "English"
                                else -> "Any"
                            },
                        )
                    },
                    onClick = { openDialog = DLG_SUB_LANG },
                )
            }

            // ══ GESTURES & CONTROLS ══════════════════════════════
            item("sec-gestures") { SectionHeader(palette, "Gestures & controls") }

            item("doubleTapSeek") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.TouchApp,
                    title = "Double-tap to seek",
                    subtitle = "Tap left / right side to jump",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.doubleTapSeek,
                            onToggle = { persist { it.copy(doubleTapSeek = !it.doubleTapSeek) } },
                        )
                    },
                )
            }

            item("swipeToSeek") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Swipe,
                    title = "Swipe to seek",
                    subtitle = "Horizontal drag scrubs the timeline",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.swipeToSeek,
                            onToggle = { persist { it.copy(swipeToSeek = !it.swipeToSeek) } },
                        )
                    },
                )
            }

            item("volumeGesture") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.VolumeUp,
                    title = "Volume gesture",
                    subtitle = "Vertical drag on the right side",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.volumeGesture,
                            onToggle = { persist { it.copy(volumeGesture = !it.volumeGesture) } },
                        )
                    },
                )
            }

            item("brightnessGesture") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.WbSunny,
                    title = "Brightness gesture",
                    subtitle = "Vertical drag on the left side",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.brightnessGesture,
                            onToggle = { persist { it.copy(brightnessGesture = !it.brightnessGesture) } },
                        )
                    },
                )
            }

            item("pinchZoom") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.ZoomIn,
                    title = "Pinch to zoom",
                    subtitle = "Scale the video with a pinch gesture",
                    trailing = {
                        ToggleSwitch(
                            palette = palette,
                            on = settings.pinchZoom,
                            onToggle = { persist { it.copy(pinchZoom = !it.pinchZoom) } },
                        )
                    },
                )
            }

            item("seekstep") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.SwapHoriz,
                    title = "Seek step",
                    subtitle = "Buttons & double-tap distance",
                    trailing = {
                        ValueText(palette = palette, text = "${settings.seekIncrementSec}s")
                    },
                    onClick = {
                        val steps = intArrayOf(5, 10, 15, 30, 60)
                        val idx = steps.indexOf(settings.seekIncrementSec).coerceAtLeast(0)
                        persist { it.copy(seekIncrementSec = steps[(idx + 1) % steps.size]) }
                    },
                )
            }

            item("autoHide") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.HourglassEmpty,
                    title = "Auto-hide controls",
                    subtitle = "Delay before the HUD disappears",
                    trailing = {
                        ValueText(
                            palette = palette,
                            text = when (settings.autoHideControlsMs) {
                                2500L -> "2.5s"
                                5000L -> "5s"
                                8000L -> "8s"
                                else -> "3.5s"
                            },
                        )
                    },
                    onClick = {
                        val steps = longArrayOf(2500L, 3500L, 5000L, 8000L)
                        val idx = steps.indexOf(settings.autoHideControlsMs).coerceAtLeast(0)
                        persist { it.copy(autoHideControlsMs = steps[(idx + 1) % steps.size]) }
                    },
                )
            }

            item("fastseek") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.FastForward,
                    title = "Fast seek threshold",
                    subtitle = "Long jumps snap to keyframes",
                    trailing = {
                        ValueText(
                            palette = palette,
                            text = when (settings.fastSeekThresholdSec) {
                                60L -> "1m"
                                300L -> "5m"
                                else -> "2m"
                            },
                        )
                    },
                    onClick = {
                        val steps = longArrayOf(60L, 120L, 300L)
                        val idx = steps.indexOf(settings.fastSeekThresholdSec).coerceAtLeast(0)
                        persist { it.copy(fastSeekThresholdSec = steps[(idx + 1) % steps.size]) }
                    },
                )
            }

            // ══ AUDIO ════════════════════════════════════════════
            item("sec-audio") { SectionHeader(palette, "Audio") }

            item("volumeBoost") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.GraphicEq,
                    title = "Volume boost",
                    subtitle = "Extra loudness above system max",
                    trailing = {
                        ValueText(
                            palette = palette,
                            text = if (settings.volumeBoostPct == 0) "Off"
                            else "+${settings.volumeBoostPct}%",
                        )
                    },
                    onClick = { openDialog = DLG_BOOST },
                )
            }

            // ══ APPEARANCE ═══════════════════════════════════════
            item("sec-appearance") { SectionHeader(palette, "Appearance") }

            item("theme") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Palette,
                    title = "Theme",
                    subtitle = "Skin for the library, player & settings",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ValueText(palette = palette, text = activeSkin.displayName)
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(palette.accent)
                            )
                        }
                    },
                    onClick = { openDialog = DLG_THEME },
                )
            }

            // ══ LIBRARY & DATA ═══════════════════════════════════
            item("sec-library") { SectionHeader(palette, "Library & data") }

            item("rescan") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Refresh,
                    title = "Rescan library",
                    subtitle = "Rebuild the video list from MediaStore now",
                    trailing = {
                        if (rescanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = palette.accent,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            ValueText(palette = palette, text = "RUN")
                        }
                    },
                    onClick = {
                        if (!rescanning) {
                            rescanning = true
                            coroutineScope.launch {
                                val count = withContext(Dispatchers.IO) {
                                    try {
                                        AnonrodeApp.get(context).scanner.scan(force = true).videos.size
                                    } catch (t: Throwable) {
                                        -1
                                    }
                                }
                                rescanning = false
                                toast(
                                    if (count >= 0) "Library refreshed · $count videos"
                                    else "Rescan failed — try again",
                                )
                            }
                        }
                    },
                )
            }

            item("clearProgress") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.DeleteSweep,
                    title = "Clear playback progress",
                    subtitle = "Reset positions, speeds & sync locks for all videos",
                    trailing = { ValueText(palette = palette, text = "CLEAR") },
                    onClick = { openDialog = DLG_CLEAR },
                )
            }

            // ── about footer ─────────────────────────────────────
            item("footer") {
                val version = remember {
                    try {
                        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                        val code = if (Build.VERSION.SDK_INT >= 28) {
                            pi.longVersionCode
                        } else {
                            @Suppress("DEPRECATION")
                            pi.versionCode.toLong()
                        }
                        "v" + pi.versionName + " (" + code + ")"
                    } catch (_: Throwable) {
                        ""
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, top = 26.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "ANONRODE PLAYER",
                        color = palette.textDim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (version.isNotEmpty()) {
                        Text(
                            version,
                            color = palette.textDim,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        "Local-only playback · nothing leaves your device",
                        color = palette.textDim,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // ── dialogs (one slot) ───────────────────────────────────
        when (openDialog) {
            DLG_RESUME -> RadioDialog(
                palette = palette,
                title = "Resume position",
                onDismiss = { openDialog = null },
                options = listOf(
                    OptionItem(ResumeBehavior.ALWAYS_ASK, "Ask each time", "Show a resume prompt when a saved position exists"),
                    OptionItem(ResumeBehavior.ALWAYS_RESUME, "Always resume", "Jump straight to the saved position"),
                    OptionItem(ResumeBehavior.ALWAYS_START_OVER, "Always start over", "Ignore saved positions"),
                ),
                isSelected = { it == settings.resumeBehavior },
                onSelect = { value ->
                    persist { it.copy(resumeBehavior = value) }
                    openDialog = null
                },
            )

            DLG_SPEED -> RadioDialog(
                palette = palette,
                title = "Default playback speed",
                onDismiss = { openDialog = null },
                options = SPEED_OPTIONS.map { OptionItem(it, "${it}×", null) },
                isSelected = { kotlin.math.abs(it - settings.defaultPlaybackSpeed) < 0.001f },
                onSelect = { value ->
                    persist { it.copy(defaultPlaybackSpeed = value) }
                    // Mirror into the legacy global-speed pref so the player
                    // applies it through its existing fallback chain today.
                    PlayerPrefs.saveGlobalSpeed(context, value)
                    openDialog = null
                },
            )

            DLG_SLEEP -> RadioDialog(
                palette = palette,
                title = "Sleep timer",
                onDismiss = { openDialog = null },
                options = listOf(0, 5, 10, 15, 30, 60, -1).map {
                    OptionItem(it, sleepLabel(it), if (it == 0) "Timer is off" else null)
                },
                isSelected = { it == settings.sleepTimerMinutes },
                onSelect = { value ->
                    persist { it.copy(sleepTimerMinutes = value) }
                    openDialog = null
                },
            )

            DLG_DECODER -> RadioDialog(
                palette = palette,
                title = "Decoder priority",
                onDismiss = { openDialog = null },
                options = listOf(
                    OptionItem(DecoderPriority.PREFER_DEVICE, "Hardware + software", "Device decoders first, FFmpeg as fallback"),
                    OptionItem(DecoderPriority.PREFER_APP, "App software (FFmpeg)", "Prefer the bundled FFmpeg decoder"),
                    OptionItem(DecoderPriority.DEVICE_ONLY, "Hardware only", "Never use FFmpeg — best battery life"),
                ),
                isSelected = { it == settings.decoderPriority },
                onSelect = { value ->
                    persist { it.copy(decoderPriority = value) }
                    toast("Decoder preference applies on the next video open")
                    openDialog = null
                },
            )

            DLG_SUB_SIZE -> RadioDialog(
                palette = palette,
                title = "Subtitle size",
                onDismiss = { openDialog = null },
                options = listOf(
                    OptionItem(0, "S", "Small"),
                    OptionItem(1, "M", "Medium — default"),
                    OptionItem(2, "L", "Large"),
                    OptionItem(3, "XL", "Extra large"),
                ),
                isSelected = { it == settings.subtitleSize },
                onSelect = { value ->
                    persist { it.copy(subtitleSize = value) }
                    openDialog = null
                },
            )

            DLG_SUB_POS -> RadioDialog(
                palette = palette,
                title = "Subtitle position",
                onDismiss = { openDialog = null },
                options = listOf(
                    OptionItem(0, "Low", "Just above the transport bar"),
                    OptionItem(1, "Mid", "Lower third"),
                    OptionItem(2, "High", "Middle of the frame"),
                    OptionItem(3, "Top", "Top of the frame"),
                ),
                isSelected = { it == settings.subtitlePosition },
                onSelect = { value ->
                    persist { it.copy(subtitlePosition = value) }
                    openDialog = null
                },
            )

            DLG_SUB_COLOR -> RadioDialog(
                palette = palette,
                title = "Subtitle color",
                onDismiss = { openDialog = null },
                options = listOf(
                    OptionItem(0, "White", null),
                    OptionItem(1, "Yellow", null),
                    OptionItem(2, "Green", null),
                    OptionItem(3, "Cyan", null),
                ),
                isSelected = { it == settings.subtitleColor },
                trailing = { value ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(subtitleColorValue(value))
                            .border(1.dp, palette.rowLine, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                },
                onSelect = { value ->
                    persist { it.copy(subtitleColor = value) }
                    openDialog = null
                },
            )

            DLG_SUB_LANG -> RadioDialog(
                palette = palette,
                title = "Subtitle language",
                onDismiss = { openDialog = null },
                options = listOf(
                    OptionItem<String?>(null, "Any", "No language preference"),
                    OptionItem<String?>("chi", "中文", "Chinese"),
                    OptionItem<String?>("eng", "English", "English"),
                ),
                isSelected = { it == settings.defaultSubtitleLanguage },
                onSelect = { value ->
                    persist { it.copy(defaultSubtitleLanguage = value) }
                    openDialog = null
                },
            )

            DLG_BOOST -> RadioDialog(
                palette = palette,
                title = "Volume boost",
                onDismiss = { openDialog = null },
                options = listOf(
                    OptionItem(0, "Off", "System volume only"),
                    OptionItem(50, "+50%", "1.5× gain — mild"),
                    OptionItem(100, "+100%", "2× gain — may distort"),
                    OptionItem(200, "+200%", "3× gain — loud, expect clipping"),
                ),
                isSelected = { it == settings.volumeBoostPct },
                onSelect = { value ->
                    persist { it.copy(volumeBoostPct = value) }
                    openDialog = null
                },
            )

            DLG_THEME -> ThemeDialog(
                palette = palette,
                activeSkin = activeSkin,
                onSelect = { skin -> themePrefs.setSkin(skin) },
                onDismiss = { openDialog = null },
            )

            DLG_CLEAR -> ConfirmDialog(
                palette = palette,
                title = "Clear playback progress?",
                message = "This resets resume positions, per-video speeds, audio/subtitle " +
                    "choices and auto-sync locks for every video. Your video files are " +
                    "not touched. This cannot be undone.",
                confirmLabel = "Clear all",
                onConfirm = {
                    openDialog = null
                    coroutineScope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            try {
                                MediaDatabase.get(context).mediaStateDao().clear()
                                true
                            } catch (t: Throwable) {
                                false
                            }
                        }
                        toast(
                            if (ok) "Playback progress cleared"
                            else "Could not clear progress — try again",
                        )
                    }
                },
                onDismiss = { openDialog = null },
            )
        }
    }
}

/* ── option data ─────────────────────────────────────────────────────── */

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

private data class OptionItem<T>(
    val value: T,
    val label: String,
    val detail: String?,
)

private fun sleepLabel(minutes: Int): String = when (minutes) {
    0 -> "Off"
    -1 -> "End of episode"
    else -> "${minutes}m"
}

private fun subtitleSizeLabel(size: Int): String = when (size) {
    0 -> "S"
    2 -> "L"
    3 -> "XL"
    else -> "M"
}

private fun subtitlePosLabel(pos: Int): String = when (pos) {
    0 -> "Low"
    2 -> "High"
    3 -> "Top"
    else -> "Mid"
}

private fun subtitleColorLabel(color: Int): String = when (color) {
    1 -> "Yellow"
    2 -> "Green"
    3 -> "Cyan"
    else -> "White"
}

/** Mirrors the SubtitleColor values used by the renderer. */
private fun subtitleColorValue(color: Int): Color = when (color) {
    1 -> Color(0xFFFFD75E)
    2 -> Color(0xFF7CE487)
    3 -> Color(0xFF7AD8E8)
    else -> Color.White
}

/* ── dialogs ─────────────────────────────────────────────────────────── */

/**
 * Skin-aware radio dialog. Options render as tappable rows with a radio
 * dot; [trailing] lets an option add extra decoration (e.g. a color dot)
 * before the dot.
 */
@Composable
private fun <T> RadioDialog(
    palette: SkinPalette,
    title: String,
    options: List<OptionItem<T>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
    trailing: (@Composable (T) -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            shape = RoundedCornerShape(16.dp),
            color = palette.surface,
        ) {
            Column(modifier = Modifier.padding(vertical = 14.dp)) {
                Text(
                    title,
                    color = palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        val selected = isSelected(option.value)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option.value) }
                                .padding(horizontal = 20.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    option.label,
                                    color = if (selected) palette.accent else palette.text,
                                    fontSize = 13.5.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                if (option.detail != null) {
                                    Text(
                                        option.detail,
                                        color = palette.textDim,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                }
                            }
                            if (trailing != null) trailing(option.value)
                            RadioDot(palette = palette, selected = selected)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Skin picker: every skin renders with its own accent swatch and applies
 * live (the dialog itself re-skins as you tap), so the choice is visible
 * before dismissing.
 */
@Composable
private fun ThemeDialog(
    palette: SkinPalette,
    activeSkin: Skin,
    onSelect: (Skin) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = palette.surface,
        ) {
            Column(modifier = Modifier.padding(vertical = 14.dp)) {
                Text(
                    "Theme",
                    color = palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
                Skin.entries.forEach { skin ->
                    val skinPalette = SkinPalette.forSkin(skin)
                    val selected = skin == activeSkin
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(skin) }
                            .padding(horizontal = 20.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(skinPalette.background)
                                .border(1.dp, skinPalette.surfaceLine, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(skinPalette.accent),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            skin.displayName,
                            color = if (selected) palette.accent else palette.text,
                            fontSize = 13.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        RadioDot(palette = palette, selected = selected)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Done", color = palette.accent)
                    }
                }
            }
        }
    }
}

/** Destructive-action confirmation styled with the active skin. */
@Composable
private fun ConfirmDialog(
    palette: SkinPalette,
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = palette.surface,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    title,
                    color = palette.text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    color = palette.textDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = palette.textDim)
                    }
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFE5484D),
                            contentColor = Color.White,
                        ),
                    ) { Text(confirmLabel) }
                }
            }
        }
    }
}

/* ── row primitives ──────────────────────────────────────────────────── */

@Composable
private fun SectionHeader(palette: SkinPalette, title: String) {
    Text(
        title.uppercase(),
        color = palette.accent,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.3.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 7.dp),
    )
}

@Composable
private fun SettingsRow(
    palette: SkinPalette,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.rowBg)
                .border(1.dp, palette.rowLine, RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = palette.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = palette.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        color = palette.textDim,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

@Composable
private fun ValueText(palette: SkinPalette, text: String) {
    Text(
        text,
        color = palette.textDim,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ToggleSwitch(palette: SkinPalette, on: Boolean, onToggle: () -> Unit) {
    val bg = if (on) palette.accent else palette.toggleOff
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onToggle),
    ) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
                .align(if (on) Alignment.CenterEnd else Alignment.CenterStart)
        )
    }
}

@Composable
private fun RadioDot(palette: SkinPalette, selected: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(2.dp, if (selected) palette.accent else palette.toggleOff, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
            )
        }
    }
}

@Composable
private fun SettingsBackButton(palette: SkinPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Back",
            tint = palette.text,
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer { rotationZ = 180f },
        )
    }
}
