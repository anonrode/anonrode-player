package dev.anonrode.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Right-aligned 350dp Optical Smoked Glass Side Drawer.
 * - Uses a 0.06f transparent tap-catcher instead of a murky 0.42f black full-screen scrim,
 *   keeping the left ~65% of the video viewport 100% crystal-clear during live adjustments.
 * - Uses hardware display-list translation (`graphicsLayer { translationX }`) and VelocityTracker
 *   fling-to-dismiss (>90dp displacement or >600dp/s velocity).
 */
@Composable
fun PlayerSlideInDrawer(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val panelWidthPx = with(density) { 350.dp.toPx() }
    val dismissThresholdPx = with(density) { 90.dp.toPx() }
    val dismissVelocityPx = with(density) { 600.dp.toPx() }

    val dragX = remember { Animatable(panelWidthPx) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(visible) {
        if (visible) {
            dragX.animateTo(0f, tween(240, easing = OpticalGlassTokens.EaseReveal))
        } else {
            dragX.animateTo(panelWidthPx, tween(160, easing = OpticalGlassTokens.EaseReveal))
        }
    }

    if (!visible && dragX.value >= panelWidthPx - 1f) return

    BackHandler(enabled = visible) {
        if (onNavigateBack != null) onNavigateBack() else onDismiss()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Ultra-clear tap-out catcher (0.06f alpha keeps left video surface 100% vivid!)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.06f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )

        // 350dp Optical Smoked Glass Chassis
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(350.dp)
                .fillMaxHeight()
                .graphicsLayer { translationX = dragX.value }
                .opticalGlass(
                    shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                    baseColor = OpticalGlassTokens.SmokedDrawerBase,
                    borderWidth = 1.dp,
                )
                .pointerInput(Unit) {
                    val velocityTracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { velocityTracker.resetTracking() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val nextX = (dragX.value + dragAmount).coerceIn(0f, panelWidthPx)
                            scope.launch { dragX.snapTo(nextX) }
                        },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity().x
                            val displacement = dragX.value
                            if (displacement > dismissThresholdPx || velocity > dismissVelocityPx) {
                                scope.launch {
                                    dragX.animateTo(
                                        panelWidthPx,
                                        tween(160, easing = OpticalGlassTokens.EaseReveal)
                                    )
                                    onDismiss()
                                }
                            } else {
                                scope.launch {
                                    dragX.animateTo(
                                        0f,
                                        tween(200, easing = OpticalGlassTokens.EaseReveal)
                                    )
                                }
                            }
                        },
                    )
                },
        ) {
            // Drawer Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onNavigateBack != null) {
                        OpticalGlassChip(
                            label = "←",
                            height = 28.dp,
                            onClick = onNavigateBack,
                        )
                    }
                    Text(
                        text = title,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = OpticalGlassTokens.CrispTextShadow,
                        ),
                    )
                }
                OpticalGlassChip(
                    label = "✕",
                    height = 28.dp,
                    onClick = onDismiss,
                )
            }

            // Drawer Content Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/**
 * §8.2 Subtitle Drawer with 4-State Navigation Machine (`MAIN`, `TRACKS`, `ADJUSTMENT`, `STYLE`)
 * and Dynamic `baseOffsetSec ± 5.0s` Auto-Recentering Adjustment Window.
 */
@Composable
fun PlayerSubtitleDrawer(
    visible: Boolean,
    tracks: List<SubtitleTrackItem>,
    selectedTrackId: String?,
    ui: PlayerUiState,
    gestures: GestureUiState,
    onSelectTrack: (String?) -> Unit,
    onOpenLocalFilePicker: () -> Unit,
    onTriggerAutoSync: () -> Unit,
    onApplySubtitleOffsetSec: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPage by remember { mutableStateOf(SubDrawerPage.MAIN) }

    // Dynamic baseOffsetSec ± 5.0s recentering check:
    // Whenever offset drifts > 4.8s from baseOffsetSec, recenter baseOffsetSec so large auto-sync
    // offsets (e.g. +35.0s) are NEVER clamped to [-5s, +5s].
    val currentOffset = ui.subtitleOffsetSec.floatValue
    val baseOffset = ui.subtitleBaseOffsetSec.floatValue
    if (abs(currentOffset - baseOffset) > 4.8f) {
        ui.subtitleBaseOffsetSec.floatValue = currentOffset
    }

    val headerTitle = when (currentPage) {
        SubDrawerPage.MAIN -> "Subtitles"
        SubDrawerPage.TRACKS -> "Subtitles • Tracks"
        SubDrawerPage.ADJUSTMENT -> "Subtitles • Sync Adjustment"
        SubDrawerPage.STYLE -> "Subtitles • Style"
    }

    PlayerSlideInDrawer(
        visible = visible,
        title = headerTitle,
        onDismiss = {
            currentPage = SubDrawerPage.MAIN
            onDismiss()
        },
        onNavigateBack = if (currentPage != SubDrawerPage.MAIN) {
            { currentPage = SubDrawerPage.MAIN }
        } else null,
    ) {
        when (currentPage) {
            SubDrawerPage.MAIN -> {
                val activeTrack = tracks.firstOrNull { it.id == selectedTrackId }
                DrawerNavRow(
                    title = "Subtitle Track",
                    subtitle = if (ui.showCC.value && activeTrack != null) {
                        "${activeTrack.title} (${activeTrack.formatLabel})"
                    } else "Disabled (Off)",
                    trailing = "›",
                    onClick = { currentPage = SubDrawerPage.TRACKS },
                )
                DrawerNavRow(
                    title = "Open Local File…",
                    subtitle = "Load .srt, .ass, .ssa, .vtt",
                    trailing = "📂",
                    onClick = onOpenLocalFilePicker,
                )
                DrawerNavRow(
                    title = "Online Download / Auto-Sync",
                    subtitle = "Acoustic VAD lock",
                    trailing = "⚡ Sync",
                    onClick = onTriggerAutoSync,
                )
                DrawerNavRow(
                    title = "Timing Adjustment",
                    subtitle = "Signed Offset: %+.2fs".format(currentOffset),
                    trailing = "›",
                    onClick = { currentPage = SubDrawerPage.ADJUSTMENT },
                )
                DrawerNavRow(
                    title = "Subtitle Style & Appearance",
                    subtitle = "2-Pass 4dp Stroke • ${ui.subtitleFontSizeSp.floatValue.toInt()}sp",
                    trailing = "›",
                    onClick = { currentPage = SubDrawerPage.STYLE },
                )
            }

            SubDrawerPage.TRACKS -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DrawerNavRow(
                        title = if (!ui.showCC.value) "● Disabled (Off)" else "○ Disabled (Off)",
                        subtitle = "Hide subtitle overlay",
                        trailing = "",
                        onClick = {
                            ui.showCC.value = false
                            onSelectTrack(null)
                        },
                    )
                    tracks.forEach { track ->
                        val selected = ui.showCC.value && track.id == selectedTrackId
                        DrawerNavRow(
                            title = (if (selected) "● " else "○ ") + track.title,
                            subtitle = track.formatLabel,
                            trailing = "",
                            onClick = {
                                ui.showCC.value = true
                                onSelectTrack(track.id)
                            },
                        )
                    }
                    DrawerNavRow(
                        title = "+ Load External Subtitle File…",
                        subtitle = ".srt / .ass / .vtt",
                        trailing = "📂",
                        onClick = onOpenLocalFilePicker,
                    )
                }
            }

            SubDrawerPage.ADJUSTMENT -> {
                val windowMin = ui.subtitleBaseOffsetSec.floatValue - 5.0f
                val windowMax = ui.subtitleBaseOffsetSec.floatValue + 5.0f

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpticalGlassChip(
                        label = "−0.1s",
                        onClick = {
                            val next = currentOffset - 0.1f
                            ui.subtitleOffsetSec.floatValue = next
                            onApplySubtitleOffsetSec(next)
                        },
                    )
                    Text(
                        text = "%+.2fs".format(currentOffset),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    OpticalGlassChip(
                        label = "+0.1s",
                        onClick = {
                            val next = currentOffset + 0.1f
                            ui.subtitleOffsetSec.floatValue = next
                            onApplySubtitleOffsetSec(next)
                        },
                    )
                }

                Text(
                    text = "Dynamic Fine-Trim Window [%+.1fs .. %+.1fs]".format(windowMin, windowMax),
                    style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp),
                )

                Slider(
                    value = currentOffset.coerceIn(windowMin, windowMax),
                    onValueChange = { newVal ->
                        ui.subtitleOffsetSec.floatValue = newVal
                        if (abs(newVal - ui.subtitleBaseOffsetSec.floatValue) > 4.8f) {
                            ui.subtitleBaseOffsetSec.floatValue = newVal
                        }
                        onApplySubtitleOffsetSec(newVal)
                    },
                    valueRange = windowMin..windowMax,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = OpticalGlassTokens.CyanAccent,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpticalGlassChip(
                        label = "Reset (0s)",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ui.subtitleBaseOffsetSec.floatValue = 0f
                            ui.subtitleOffsetSec.floatValue = 0f
                            onApplySubtitleOffsetSec(0f)
                        },
                    )
                    OpticalGlassChip(
                        label = "⚡ Re-Sync",
                        isActive = true,
                        modifier = Modifier.weight(1f),
                        onClick = onTriggerAutoSync,
                    )
                }
            }

            SubDrawerPage.STYLE -> {
                Text("Vertical Position Preset", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpticalGlassChip("Bottom (78%)", onClick = { gestures.subPosFraction.floatValue = 0.78f })
                    OpticalGlassChip("Middle (50%)", onClick = { gestures.subPosFraction.floatValue = 0.50f })
                    OpticalGlassChip("Top (16%)", onClick = { gestures.subPosFraction.floatValue = 0.16f })
                }

                Text("Font Size Preset", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Tiny" to 15f, "Small" to 18f, "Norm" to 21f, "Large" to 26f, "Huge" to 32f)
                        .forEach { (label, spVal) ->
                            OpticalGlassChip(
                                label = label,
                                isActive = abs(ui.subtitleFontSizeSp.floatValue - spVal) < 1.5f,
                                onClick = { ui.subtitleFontSizeSp.floatValue = spVal },
                            )
                        }
                }

                Text("Fill Color (4dp Stroke Active)", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpticalGlassChip("⚪ White", onClick = { ui.subtitleTextColor.value = Color.White })
                    OpticalGlassChip("🟡 Yellow", onClick = { ui.subtitleTextColor.value = Color(0xFFFDE047) })
                    OpticalGlassChip("🔵 Cyan", onClick = { ui.subtitleTextColor.value = Color(0xFF67E8F9) })
                }
            }
        }
    }
}

/**
 * §8.3 Settings Drawer (`4×2` Quick Tools Grid + `4×1` Play Option Row + Speed & Inline Sliders).
 */
@Composable
fun PlayerSettingsDrawer(
    visible: Boolean,
    ui: PlayerUiState,
    quick: QuickRowUiState,
    onApplySpeed: (Float) -> Unit,
    onOpenAudioTracks: () -> Unit,
    onEnterPip: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSlideInDrawer(
        visible = visible,
        title = "Settings & Quick Tools",
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("QUICK TOOLS (4×2)", color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpticalGlassChip("🔊 Audio", modifier = Modifier.weight(1f), onClick = onOpenAudioTracks)
                OpticalGlassChip("💬 Sub", isActive = ui.showCC.value, modifier = Modifier.weight(1f), onClick = {
                    onDismiss()
                    ui.subtitleDrawerOpen.value = true
                })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpticalGlassChip("🎧 Bg Play", isActive = ui.backgroundPlayOn.value, modifier = Modifier.weight(1f), onClick = {
                    ui.backgroundPlayOn.value = !ui.backgroundPlayOn.value
                })
                OpticalGlassChip("🖼 PiP", modifier = Modifier.weight(1f), onClick = onEnterPip)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpticalGlassChip("📸 Shot", modifier = Modifier.weight(1f), onClick = onCaptureScreenshot)
                OpticalGlassChip("🔒 Lock", modifier = Modifier.weight(1f), onClick = {
                    onDismiss()
                    ui.locked.value = true
                    ui.lockScrimVisible.value = true
                })
            }

            Text("PLAY OPTIONS (4×1)", color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpticalGlassChip("🌙 Night", isActive = ui.nightMode.value, modifier = Modifier.weight(1f), onClick = {
                    ui.nightMode.value = !ui.nightMode.value
                })
                OpticalGlassChip("🎛 EQ", isActive = quick.equalizerOn.value, modifier = Modifier.weight(1f), onClick = {
                    quick.equalizerOn.value = !quick.equalizerOn.value
                })
                OpticalGlassChip(
                    label = "🚀 ${ui.volumeBoostPercent.intValue}%",
                    isActive = ui.volumeBoostPercent.intValue > 100,
                    accentColor = OpticalGlassTokens.AmberBoost,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        ui.volumeBoostPercent.intValue = if (ui.volumeBoostPercent.intValue > 100) 100 else 150
                    }
                )
            }

            Text("REPEAT MODE", color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RepeatLoopMode.entries.forEach { mode ->
                    OpticalGlassChip(
                        label = mode.name,
                        isActive = ui.repeatMode.value == mode,
                        modifier = Modifier.weight(1f),
                        onClick = { ui.repeatMode.value = mode },
                    )
                }
            }

            Text(
                text = "PLAYBACK SPEED (${"%.2f".format(ui.currentPlaybackSpeed.floatValue)}x)",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                    OpticalGlassChip(
                        label = "${spd}x",
                        isActive = abs(ui.currentPlaybackSpeed.floatValue - spd) < 0.03f,
                        onClick = {
                            ui.currentPlaybackSpeed.floatValue = spd
                            onApplySpeed(spd)
                        },
                    )
                }
            }
        }
    }
}

/**
 * §8.4 Playlist Drawer (60dp Episode Rows, 72x42dp 16:9 Thumbnails, Duration Pill).
 */
@Composable
fun PlayerPlaylistDrawer(
    visible: Boolean,
    episodes: List<PlaylistEpisodeItem>,
    onSelectEpisode: (PlaylistEpisodeItem) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSlideInDrawer(
        visible = visible,
        title = "${episodes.size} Episodes",
        onDismiss = onDismiss,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(episodes, key = { _, ep -> ep.id }) { _, ep ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .opticalGlass(
                            shape = RoundedCornerShape(12.dp),
                            isActive = ep.isPlaying,
                        )
                        .clickable { onSelectEpisode(ep) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 72dp x 42dp 16:9 Thumbnail Box with bottom progress bar & duration pill
                    Box(
                        modifier = Modifier
                            .size(width = 72.dp, height = 42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B)),
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = formatSecondsToHms(ep.durationSec.toInt()),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (ep.watchedFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(ep.watchedFraction.coerceIn(0f, 1f))
                                    .height(2.5.dp)
                                    .background(OpticalGlassTokens.CyanAccent),
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ep.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        if (ep.isPlaying) {
                            Text(
                                text = "● Playing",
                                color = OpticalGlassTokens.CyanAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Text(
                        text = "≡",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerNavRow(
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .opticalGlass(
                shape = RoundedCornerShape(12.dp),
                baseColor = Color.White.copy(alpha = 0.06f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
        }
        if (trailing.isNotEmpty()) {
            Text(text = trailing, color = OpticalGlassTokens.CyanAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
