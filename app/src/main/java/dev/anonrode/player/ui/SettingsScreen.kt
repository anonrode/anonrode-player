package dev.anonrode.player.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anonrode.player.PlayerPrefs
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import dev.anonrode.player.core.ui.theme.Skin
import dev.anonrode.player.core.ui.theme.SkinPalette
import dev.anonrode.player.core.ui.theme.ThemePrefs
import dev.anonrode.player.core.ui.theme.rememberSkinPalette
import kotlinx.coroutines.launch

/* ── Settings screen ─────────────────────────────────────────────────────
 * Every row reads/writes the PlayerSettings DataStore, so each choice is
 * persistent and applied live to the player (the activity collects the
 * same flow). Rows: Resume / Auto-sync / Seek step / Autoplay / Sleep /
 * gestures (double-tap, swipe, volume, brightness) / Auto-hide /
 * Background playback / Volume boost / Decoder priority / Subtitle
 * language / Fast-seek threshold / Theme.
 *
 * Per-video state (zoom, audio/subtitle track, position) intentionally
 * lives in Room, not here — the player HUD owns those controls.
 * ------------------------------------------------------------------------- */

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
    val settings by dataStore.data.collectAsState(initial = dev.anonrode.player.core.datastore.PlayerSettings())

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
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    Text(
                        activeSkin.displayName,
                        color = palette.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            // ── Resume position ─────────────────────────────────
            item("resume") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.History,
                    title = "Resume position",
                    subtitle = "Remember where you stopped",
                    trailing = {
                        ValueText(
                            palette = palette,
                            text = when (settings.resumeBehavior) {
                                dev.anonrode.player.core.datastore.ResumeBehavior.ALWAYS_RESUME -> "RESUME"
                                dev.anonrode.player.core.datastore.ResumeBehavior.ALWAYS_START_OVER -> "START OVER"
                                else -> "ASK"
                            }
                        )
                    },
                    onClick = {
                        coroutineScope.launch {
                            val next = when (settings.resumeBehavior) {
                                dev.anonrode.player.core.datastore.ResumeBehavior.ALWAYS_ASK ->
                                    dev.anonrode.player.core.datastore.ResumeBehavior.ALWAYS_RESUME
                                dev.anonrode.player.core.datastore.ResumeBehavior.ALWAYS_RESUME ->
                                    dev.anonrode.player.core.datastore.ResumeBehavior.ALWAYS_START_OVER
                                else -> dev.anonrode.player.core.datastore.ResumeBehavior.ALWAYS_ASK
                            }
                            dataStore.updateData { it.copy(resumeBehavior = next) }
                        }
                    },
                )
            }

            // ── Subtitle auto-sync ──────────────────────────────
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
                            onToggle = {
                                coroutineScope.launch {
                                    dataStore.updateData { it.copy(autoSyncEnabled = !settings.autoSyncEnabled) }
                                }
                            },
                        )
                    },
                )
            }

            // ── Seek step (± buttons + double-tap distance) ───────
            item("suboffset") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.ClosedCaption,
                    title = "Seek step",
                    subtitle = "Buttons & double-tap distance",
                    trailing = {
                        ValueText(palette = palette, text = "${settings.seekIncrementSec}s")
                    },
                    onClick = {
                        coroutineScope.launch {
                            val steps = intArrayOf(5, 10, 15, 30, 60)
                            val next = steps[((steps.indexOf(settings.seekIncrementSec) + 1).coerceAtLeast(0)) % steps.size]
                            dataStore.updateData { it.copy(seekIncrementSec = next) }
                        }
                    },
                )
            }

            // ── Autoplay next episode ───────────────────────────
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
                            onToggle = {
                                coroutineScope.launch {
                                    dataStore.updateData { it.copy(autoAdvance = !settings.autoAdvance) }
                                }
                            },
                        )
                    },
                )
            }

            // ── Sleep timer ─────────────────────────────────────
            item("sleep") {
                val cycle = intArrayOf(0, 5, 10, 15, 30, 60, -1)
                val labels = mapOf(0 to "Off", 5 to "5m", 10 to "10m",
                    15 to "15m", 30 to "30m", 60 to "60m", -1 to "End")
                val current = settings.sleepTimerMinutes
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Timer,
                    title = "Sleep timer",
                    subtitle = "Off by default",
                    trailing = {
                        ValueText(palette = palette,
                            text = labels[current] ?: "Off")
                    },
                    onClick = {
                        // Cycle to the next option and persist.
                        val idx = cycle.indexOf(current).coerceAtLeast(0)
                        val next = cycle[(idx + 1) % cycle.size]
                        coroutineScope.launch {
                            dataStore.updateData { it.copy(sleepTimerMinutes = next) }
                        }
                    },
                )
            }

            // ── Gestures: each row mirrors a PlayerSettings toggle that
            //    gates the matching player gesture live. ──────────────
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
                            onToggle = {
                                coroutineScope.launch {
                                    dataStore.updateData { it.copy(doubleTapSeek = !settings.doubleTapSeek) }
                                }
                            },
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
                            onToggle = {
                                coroutineScope.launch {
                                    dataStore.updateData { it.copy(swipeToSeek = !settings.swipeToSeek) }
                                }
                            },
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
                            onToggle = {
                                coroutineScope.launch {
                                    dataStore.updateData { it.copy(volumeGesture = !settings.volumeGesture) }
                                }
                            },
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
                            onToggle = {
                                coroutineScope.launch {
                                    dataStore.updateData { it.copy(brightnessGesture = !settings.brightnessGesture) }
                                }
                            },
                        )
                    },
                )
            }

            item("autoHide") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Timer,
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
                        coroutineScope.launch {
                            val steps = longArrayOf(2500L, 3500L, 5000L, 8000L)
                            val idx = steps.indexOf(settings.autoHideControlsMs).coerceAtLeast(0)
                            dataStore.updateData { it.copy(autoHideControlsMs = steps[(idx + 1) % steps.size]) }
                        }
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
                            onToggle = {
                                coroutineScope.launch {
                                    dataStore.updateData { it.copy(backgroundPlayback = !settings.backgroundPlayback) }
                                }
                            },
                        )
                    },
                )
            }

            // ── Volume boost (DSP gain above system max) ─────────
            item("volumeBoost") {
                val cycle = intArrayOf(0, 50, 100, 200)
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
                    onClick = {
                        val idx = cycle.indexOf(settings.volumeBoostPct).coerceAtLeast(0)
                        val next = cycle[(idx + 1) % cycle.size]
                        coroutineScope.launch {
                            dataStore.updateData { it.copy(volumeBoostPct = next) }
                        }
                    },
                )
            }

            // ── Decoder priority ────────────────────────────────
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
                                dev.anonrode.player.core.datastore.DecoderPriority.PREFER_APP -> "APP SW"
                                dev.anonrode.player.core.datastore.DecoderPriority.DEVICE_ONLY -> "HW ONLY"
                                else -> "HW + SW"
                            },
                        )
                    },
                    onClick = {
                        coroutineScope.launch {
                            val next = when (settings.decoderPriority) {
                                dev.anonrode.player.core.datastore.DecoderPriority.PREFER_DEVICE ->
                                    dev.anonrode.player.core.datastore.DecoderPriority.PREFER_APP
                                dev.anonrode.player.core.datastore.DecoderPriority.PREFER_APP ->
                                    dev.anonrode.player.core.datastore.DecoderPriority.DEVICE_ONLY
                                else -> dev.anonrode.player.core.datastore.DecoderPriority.PREFER_DEVICE
                            }
                            dataStore.updateData { it.copy(decoderPriority = next) }
                        }
                    },
                )
            }

            // ── Preferred subtitle language ─────────────────────
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
                                else -> "中 + EN"
                            },
                        )
                    },
                    onClick = {
                        coroutineScope.launch {
                            val next = when (settings.defaultSubtitleLanguage) {
                                null -> "chi"
                                "chi" -> "eng"
                                else -> null
                            }
                            dataStore.updateData { it.copy(defaultSubtitleLanguage = next) }
                        }
                    },
                )
            }

            // ── Fast seek threshold ─────────────────────────────
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
                        coroutineScope.launch {
                            val steps = longArrayOf(60L, 120L, 300L)
                            val idx = steps.indexOf(settings.fastSeekThresholdSec).coerceAtLeast(0)
                            dataStore.updateData { it.copy(fastSeekThresholdSec = steps[(idx + 1) % steps.size]) }
                        }
                    },
                )
            }

            // ── Theme (cycles MX / SIGNAL / LIGHT / BLACK) ──────
            item("theme") {
                SettingsRow(
                    palette = palette,
                    icon = Icons.Filled.Palette,
                    title = "Theme",
                    subtitle = "Active skin · affects player + settings",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ValueText(palette = palette, text = activeSkin.displayName)
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(palette.accent)
                            )
                        }
                    },
                    onClick = { themePrefs.cycleSkin() },
                )
            }

            // ── footer build version ────────────────────────────
            item("footer") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "ANONRODE PLAYER · v0.3.0",
                        color = palette.textDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/* ── Row primitive ───────────────────────────────────────────────────── */

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
                modifier = Modifier
                    .size(24.dp),
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
                        maxLines = 1,
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
                .graphicsLayerScale(rotationZ = 180f),
        )
    }
}

/** Tiny shim so we can call .align() inside the toggle's Box from anywhere. */
private fun Modifier.graphicsLayerScale(rotationZ: Float): Modifier =
    this.graphicsLayer { this.rotationZ = rotationZ }
