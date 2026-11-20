package dev.anonrode.player.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anonrode.player.PlayerPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-height flat right slide-in drawer (`~41%` width, clamped `300dp..360dp`).
 * Smooth horizontal translucent black gradient (`0.80f -> 0.97f`), zero blur, zero card borders,
 * velocity fling-to-dismiss (`>72dp` or `>800dp/s`), clean `<- Title` header.
 */
@Composable
fun PlayerSlideInDrawer(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val panelWidthPx = with(density) { 350.dp.toPx() }
    val dismissThresholdPx = with(density) { 72.dp.toPx() }
    val dismissVelocityPx = with(density) { 800.dp.toPx() }

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
        // Crystal-clear tap-outside catcher
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.42f)
                .widthIn(min = 300.dp, max = 360.dp)
                .fillMaxHeight()
                .graphicsLayer { translationX = dragX.value }
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color(0xCC060608),
                        0.22f to Color(0xF0040406),
                        1.0f to Color(0xF7040406),
                    )
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
            // Clean Borderless Header: [<-] Title ... [Trailing]
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
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (onNavigateBack != null) onNavigateBack() else onDismiss()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                headerTrailing?.invoke()
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun EmeraldToggleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(38.dp)
            .height(20.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (checked) OpticalGlassTokens.EmeraldAccent.copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.22f)
            )
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(if (checked) OpticalGlassTokens.EmeraldAccent else Color(0xFFD4D4D8)),
        )
    }
}

@Composable
private fun FlatDrawerDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.10f))
    )
}

@Composable
private fun FlatDrawerRow(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = Color.White.copy(alpha = 0.54f),
    showChevron: Boolean = false,
    isSelected: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) Color.White.copy(alpha = 0.10f) else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = TextStyle(
                    color = if (isSelected) OpticalGlassTokens.EmeraldSync else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                ),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        color = subtitleColor,
                        fontSize = 11.sp,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (showChevron) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Subtitle Drawer: Flat layout with header toggle switch, hairline dividers,
 * Current subtitle (Tracks), Sync & Timing (fine-trim slider + Resync),
 * Appearance & Position (Position, Size, Color, Bold, Box BG), Open file, and Online download.
 */
@Composable
fun PlayerSubtitleDrawer(
    visible: Boolean,
    tracks: List<SubtitleTrackItem>,
    selectedTrackId: String?,
    ui: PlayerUiState,
    gestures: GestureUiState,
    actions: PlayerScreenActions? = null,
    onSelectTrack: (String?) -> Unit,
    onOpenLocalFilePicker: () -> Unit,
    onTriggerAutoSync: () -> Unit,
    onApplySubtitleOffsetSec: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPage by remember { mutableStateOf(SubDrawerPage.MAIN) }

    val currentOffset = ui.subtitleOffsetSec.floatValue
    val baseOffset = ui.subtitleBaseOffsetSec.floatValue
    if (abs(currentOffset - baseOffset) > 4.8f) {
        ui.subtitleBaseOffsetSec.floatValue = currentOffset
    }

    val headerTitle = when (currentPage) {
        SubDrawerPage.MAIN -> "Subtitle"
        SubDrawerPage.TRACKS -> "Current subtitle"
        SubDrawerPage.ADJUSTMENT -> "Sync & Timing"
        SubDrawerPage.STYLE -> "Appearance"
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
        headerTrailing = if (currentPage == SubDrawerPage.MAIN) {
            {
                EmeraldToggleSwitch(
                    checked = ui.showCC.value,
                    onCheckedChange = { enabled -> ui.showCC.value = enabled },
                )
            }
        } else null,
    ) {
        when (currentPage) {
            SubDrawerPage.MAIN -> {
                val activeTrack = tracks.firstOrNull { it.id == selectedTrackId }
                FlatDrawerRow(
                    title = "Current subtitle",
                    subtitle = if (ui.showCC.value && activeTrack != null) {
                        "${activeTrack.title} (${activeTrack.formatLabel})"
                    } else if (!ui.showCC.value) {
                        "None"
                    } else {
                        "Auto"
                    },
                    showChevron = true,
                    onClick = { currentPage = SubDrawerPage.TRACKS },
                )
                FlatDrawerRow(
                    title = "Sync & Timing",
                    subtitle = "Offset %+.2fs · Tap to trim or re-sync".format(currentOffset),
                    subtitleColor = OpticalGlassTokens.EmeraldSync,
                    showChevron = true,
                    onClick = { currentPage = SubDrawerPage.ADJUSTMENT },
                )
                FlatDrawerRow(
                    title = "Appearance & Position",
                    subtitle = "${ui.subtitleFontSizeSp.floatValue.toInt()}sp · ${if (ui.subtitleBold.value) "Bold" else "Regular"}${if (ui.subtitleBgEnabled.value) " · Box BG" else ""}",
                    showChevron = true,
                    onClick = { currentPage = SubDrawerPage.STYLE },
                )
                FlatDrawerDivider()
                FlatDrawerRow(
                    title = "Open file",
                    onClick = onOpenLocalFilePicker,
                )
                FlatDrawerRow(
                    title = "Online download",
                    onClick = {
                        if (actions != null) {
                            actions.onOpenOnlineSubtitles()
                        } else {
                            onOpenLocalFilePicker()
                        }
                    },
                )
            }

            SubDrawerPage.TRACKS -> {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    FlatDrawerRow(
                        title = "None",
                        subtitle = "Hide subtitles",
                        isSelected = !ui.showCC.value || selectedTrackId == "none",
                        onClick = {
                            ui.showCC.value = false
                            onSelectTrack("none")
                        },
                    )
                    tracks.forEach { track ->
                        val selected = ui.showCC.value && track.id == selectedTrackId
                        FlatDrawerRow(
                            title = track.title,
                            subtitle = track.formatLabel,
                            isSelected = selected,
                            onClick = {
                                ui.showCC.value = true
                                onSelectTrack(track.id)
                            },
                        )
                    }
                    FlatDrawerDivider()
                    FlatDrawerRow(
                        title = "Open local subtitle file…",
                        onClick = onOpenLocalFilePicker,
                    )
                }
            }

            SubDrawerPage.ADJUSTMENT -> {
                val windowMin = ui.subtitleBaseOffsetSec.floatValue - 5.0f
                val windowMax = ui.subtitleBaseOffsetSec.floatValue + 5.0f

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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
                                fontWeight = FontWeight.Bold,
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
                        text = "Fine-Trim Window [%+.1fs .. %+.1fs]".format(windowMin, windowMax),
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.58f),
                            fontSize = 11.sp,
                            fontFeatureSettings = "tnum",
                        ),
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
                            activeTrackColor = OpticalGlassTokens.EmeraldAccent,
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
                            label = "Re-Sync Now",
                            isActive = true,
                            modifier = Modifier.weight(1f),
                            onClick = onTriggerAutoSync,
                        )
                    }
                }
            }

            SubDrawerPage.STYLE -> {
                val context = LocalContext.current
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Vertical Position", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Bottom" to 0.78f, "Middle" to 0.50f, "Top" to 0.16f).forEach { (lbl, pos) ->
                            OpticalGlassChip(
                                label = lbl,
                                isActive = abs(gestures.subPosFraction.floatValue - pos) < 0.08f,
                                onClick = {
                                    gestures.subPosFraction.floatValue = pos
                                    PlayerPrefs.saveSubtitlePosition(context, "", SUB_DEFAULT_X, pos)
                                },
                            )
                        }
                    }

                    Text("Font Size", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("15sp" to 15f, "18sp" to 18f, "21sp" to 21f, "26sp" to 26f, "32sp" to 32f)
                            .forEach { (label, spVal) ->
                                OpticalGlassChip(
                                    label = label,
                                    isActive = abs(ui.subtitleFontSizeSp.floatValue - spVal) < 1.5f,
                                    onClick = { ui.subtitleFontSizeSp.floatValue = spVal },
                                )
                            }
                    }

                    Text("Text Color", color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "White" to Color.White,
                            "Yellow" to Color(0xFFFDE047),
                            "Emerald" to Color(0xFF3DDBA8),
                        ).forEach { (label, col) ->
                            OpticalGlassChip(
                                label = label,
                                isActive = ui.subtitleTextColor.value == col,
                                onClick = { ui.subtitleTextColor.value = col },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Bold Outline Text", color = Color.White, fontSize = 12.5.sp)
                        EmeraldToggleSwitch(
                            checked = ui.subtitleBold.value,
                            onCheckedChange = { ui.subtitleBold.value = it },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Translucent Background Box", color = Color.White, fontSize = 12.5.sp)
                        EmeraldToggleSwitch(
                            checked = ui.subtitleBgEnabled.value,
                            onCheckedChange = { ui.subtitleBgEnabled.value = it },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Controls / Settings Drawer:
 * 4-column circular thin-outline icon grid + flat toggle rows + precision Brightness & Volume+Boost sliders.
 */
@Composable
fun PlayerSettingsDrawer(
    visible: Boolean,
    ui: PlayerUiState,
    quick: QuickRowUiState,
    actions: PlayerScreenActions? = null,
    onApplySpeed: (Float) -> Unit,
    onOpenAudioTracks: () -> Unit,
    onEnterPip: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSlideInDrawer(
        visible = visible,
        title = "Controls",
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 4-Column Circular Thin-Outline Icon Grid
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    CircularOutlineTool(
                        label = "Playing Queue",
                        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                        isActive = ui.playlistDrawerOpen.value,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            ui.playlistDrawerOpen.value = true
                        },
                    )
                    val zoomLabel = AspectZoomMode.entries[ui.zoomIdx.intValue % AspectZoomMode.entries.size].label
                    CircularOutlineTool(
                        label = "Aspect ($zoomLabel)",
                        icon = Icons.Rounded.AspectRatio,
                        isActive = ui.zoomIdx.intValue != 0,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ui.zoomIdx.intValue = (ui.zoomIdx.intValue + 1) % AspectZoomMode.entries.size
                            ui.aspectBadgeVisible.value = true
                        },
                    )
                    val decLabel = actions?.decoderModeLabel?.value ?: "HW+"
                    CircularOutlineTool(
                        label = "Decoder $decLabel",
                        icon = Icons.Rounded.Memory,
                        isActive = true,
                        modifier = Modifier.weight(1f),
                        onClick = { actions?.onCycleDecoderMode() },
                    )
                    CircularOutlineTool(
                        label = "Audio Track",
                        icon = Icons.Rounded.MusicNote,
                        isActive = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            onOpenAudioTracks()
                        },
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    val sleepMin = ui.sleepTimerMinutes.intValue
                    val sleepLbl = when (sleepMin) {
                        0 -> "Sleep Timer"
                        -1 -> "Sleep: End"
                        else -> "Sleep: ${sleepMin}m"
                    }
                    CircularOutlineTool(
                        label = sleepLbl,
                        icon = Icons.Rounded.Timer,
                        isActive = sleepMin != 0,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val next = when (sleepMin) {
                                0 -> 15
                                15 -> 30
                                30 -> 60
                                60 -> -1
                                else -> 0
                            }
                            if (actions != null) actions.onSelectSleepTimer(next) else ui.sleepTimerMinutes.intValue = next
                        },
                    )
                    CircularOutlineTool(
                        label = "A-B Repeat",
                        icon = Icons.Rounded.Repeat,
                        isActive = ui.abStartSec.floatValue >= 0f,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (actions != null) {
                                actions.onCycleAbRepeat()
                            } else {
                                ui.abStartSec.floatValue = if (ui.abStartSec.floatValue < 0f) 0f else -1f
                            }
                        },
                    )
                    CircularOutlineTool(
                        label = "Equalizer",
                        icon = Icons.Rounded.Equalizer,
                        isActive = quick.equalizerOn.value,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (actions != null) {
                                onDismiss()
                                actions.onOpenEqualizerSheet()
                            } else {
                                quick.equalizerOn.value = !quick.equalizerOn.value
                            }
                        },
                    )
                    CircularOutlineTool(
                        label = "Audio Output",
                        icon = Icons.Rounded.Cast,
                        isActive = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            actions?.onOpenCastPicker()
                        },
                    )
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    CircularOutlineTool(
                        label = "Pop-up (PiP)",
                        icon = Icons.Rounded.PictureInPictureAlt,
                        isActive = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            onEnterPip()
                        },
                    )
                    CircularOutlineTool(
                        label = "Screenshot",
                        icon = Icons.Rounded.CameraAlt,
                        isActive = false,
                        modifier = Modifier.weight(1f),
                        onClick = onCaptureScreenshot,
                    )
                    CircularOutlineTool(
                        label = "Night Mode",
                        icon = Icons.Rounded.Bedtime,
                        isActive = ui.nightMode.value,
                        modifier = Modifier.weight(1f),
                        onClick = { ui.nightMode.value = !ui.nightMode.value },
                    )
                    CircularOutlineTool(
                        label = "All Settings",
                        icon = Icons.Rounded.Settings,
                        isActive = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            actions?.onOpenFullSettings()
                        },
                    )
                }
            }

            FlatDrawerDivider()

            // Decoder Segment Row: [HW | HW+ | SW]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Decoder Engine", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                val decLabel = actions?.decoderModeLabel?.value ?: "HW+SW"
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("HW" to 0, "HW+" to 1, "SW" to 2).forEach { (lbl, modeInt) ->
                        val active = when (modeInt) {
                            0 -> decLabel == "HW"
                            1 -> decLabel.startsWith("HW+")
                            else -> decLabel == "APP" || decLabel == "SW"
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (active) OpticalGlassTokens.EmeraldAccent else Color.White.copy(alpha = 0.10f)
                                )
                                .clickable {
                                    if (actions != null) actions.onSelectDecoderMode(modeInt)
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = lbl,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // Repeat Mode Segment Row: [Off | One | All]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Repeat Mode", color = Color.White, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RepeatLoopMode.entries.forEach { mode ->
                        val active = ui.repeatMode.value == mode
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (active) OpticalGlassTokens.EmeraldAccent else Color.White.copy(alpha = 0.10f)
                                )
                                .clickable {
                                    if (actions != null) {
                                        actions.onUpdatePlaylistMode(ui.shuffleOn.value, mode)
                                    } else {
                                        ui.repeatMode.value = mode
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = mode.name,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            // Auto Subtitle Sync Toggle
            FlatDrawerRow(
                title = "Auto Subtitle Sync",
                trailing = {
                    EmeraldToggleSwitch(
                        checked = quick.subSyncEnabled.value,
                        onCheckedChange = { enabled ->
                            if (actions != null) actions.onSetAutoSyncEnabled(enabled)
                            else quick.subSyncEnabled.value = enabled
                        },
                    )
                },
                onClick = {
                    val next = !quick.subSyncEnabled.value
                    if (actions != null) actions.onSetAutoSyncEnabled(next) else quick.subSyncEnabled.value = next
                },
            )

            // Background Play Toggle
            FlatDrawerRow(
                title = "Background Play",
                trailing = {
                    EmeraldToggleSwitch(
                        checked = ui.backgroundPlayOn.value,
                        onCheckedChange = { enabled ->
                            if (actions != null) actions.onToggleBackgroundPlay(enabled)
                            else ui.backgroundPlayOn.value = enabled
                        },
                    )
                },
                onClick = {
                    val next = !ui.backgroundPlayOn.value
                    if (actions != null) actions.onToggleBackgroundPlay(next) else ui.backgroundPlayOn.value = next
                },
            )

            // Precision Brightness & Volume+Boost Sliders
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val briPct = (ui.screenBrightnessFraction.floatValue * 100f).roundToInt()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.WbSunny, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                        Text("Brightness", color = Color.White.copy(alpha = 0.8f), fontSize = 11.5.sp)
                    }
                    Text(
                        text = "$briPct%",
                        style = TextStyle(color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
                    )
                }
                Slider(
                    value = ui.screenBrightnessFraction.floatValue.coerceIn(0.02f, 1.0f),
                    onValueChange = { frac ->
                        if (actions != null) actions.onApplyBrightnessFraction(frac)
                        else ui.screenBrightnessFraction.floatValue = frac
                    },
                    valueRange = 0.02f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = OpticalGlassTokens.EmeraldAccent,
                    ),
                )

                val volPct = ui.volumeBoostPercent.intValue
                val isBoost = volPct > 100
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                        Text("Volume + Boost", color = Color.White.copy(alpha = 0.8f), fontSize = 11.5.sp)
                    }
                    Text(
                        text = "$volPct%",
                        style = TextStyle(
                            color = if (isBoost) OpticalGlassTokens.AmberBoost else Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
                Slider(
                    value = volPct.toFloat().coerceIn(0f, 200f),
                    onValueChange = { v ->
                        val next = v.roundToInt()
                        if (actions != null) actions.onApplyVolumeBoostPercent(next)
                        else ui.volumeBoostPercent.intValue = next
                    },
                    valueRange = 0f..200f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = if (isBoost) OpticalGlassTokens.AmberBoost else OpticalGlassTokens.EmeraldAccent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun CircularOutlineTool(
    label: String,
    icon: ImageVector,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) OpticalGlassTokens.EmeraldAccent.copy(alpha = 0.18f) else Color.Transparent
                )
                .border(
                    width = 1.dp,
                    color = if (isActive) OpticalGlassTokens.EmeraldAccent else Color.White.copy(alpha = 0.24f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) OpticalGlassTokens.EmeraldSync else Color.White,
                modifier = Modifier.size(19.dp),
            )
        }
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = TextStyle(
                color = if (isActive) OpticalGlassTokens.EmeraldSync else Color.White.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Normal,
            ),
        )
    }
}

private val EpisodeThumbnailCache = object : LruCache<String, Bitmap>(48) {}

/**
 * Episodes / Playlist Drawer:
 * - Header: `<- SERIES (N)` + `Order` reverse/reorder toggle.
 * - Full-bleed flat rows (`rgba(255,255,255,0.13)` on playing row, zero card borders).
 * - Left `=` drag handle with working vertical drag-to-reorder (`actions.onReorderEpisodes`).
 * - Real 78x44dp 16:9 frame thumbnails with `▶ 51:10` duration pill or emerald equalizer bars.
 * - 2-line title + `x` remove button (`actions.onRemoveEpisode`).
 */
@Composable
fun PlayerPlaylistDrawer(
    visible: Boolean,
    episodes: List<PlaylistEpisodeItem>,
    seriesTitle: String = "Episodes",
    actions: PlayerScreenActions? = null,
    onSelectEpisode: (PlaylistEpisodeItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current.density
    val rowHeightPx = 62f * density

    val localEpisodes = remember(episodes) {
        mutableStateListOf<PlaylistEpisodeItem>().apply { addAll(episodes) }
    }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    PlayerSlideInDrawer(
        visible = visible,
        title = "$seriesTitle (${localEpisodes.size})",
        onDismiss = onDismiss,
        headerTrailing = {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        if (localEpisodes.size > 1) {
                            val reversed = localEpisodes.reversed()
                            localEpisodes.clear()
                            localEpisodes.addAll(reversed)
                            actions?.onReorderEpisodes(localEpisodes.map { it.id })
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "Order",
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Icon(
                    imageVector = Icons.Rounded.SwapVert,
                    contentDescription = "Reverse Order",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp),
                )
            }
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(
                items = localEpisodes,
                key = { _, ep -> ep.id },
                contentType = { _, _ -> "playlist_episode_row" },
            ) { index, ep ->
                val isBeingDragged = index == draggingIndex
                var thumbBitmap by remember(ep.id) {
                    mutableStateOf(EpisodeThumbnailCache.get(ep.id))
                }

                LaunchedEffect(ep.id, ep.videoPath) {
                    if (thumbBitmap != null && !thumbBitmap!!.isRecycled) return@LaunchedEffect
                    val extracted = withContext(Dispatchers.IO) {
                        val retriever = MediaMetadataRetriever()
                        try {
                            if (!ep.videoPath.isNullOrBlank()) {
                                retriever.setDataSource(ep.videoPath)
                            } else {
                                retriever.setDataSource(context, Uri.parse(ep.id))
                            }
                            val sampleUs = ((ep.durationSec * 0.15f).coerceAtLeast(2f) * 1_000_000L).toLong()
                            retriever.getScaledFrameAtTime(
                                sampleUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                160,
                                90,
                            )
                        } catch (_: Throwable) {
                            null
                        } finally {
                            try {
                                retriever.release()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    if (extracted != null) {
                        EpisodeThumbnailCache.put(ep.id, extracted)
                        thumbBitmap = extracted
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .graphicsLayer {
                            translationY = if (isBeingDragged) dragOffsetY else 0f
                            scaleX = if (isBeingDragged) 1.02f else 1f
                            scaleY = if (isBeingDragged) 1.02f else 1f
                        }
                        .background(
                            when {
                                isBeingDragged -> Color.White.copy(alpha = 0.20f)
                                ep.isPlaying -> Color.White.copy(alpha = 0.13f)
                                else -> Color.Transparent
                            }
                        )
                        .clickable { onSelectEpisode(ep) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // `=` Drag Reorder Handle
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .pointerInput(localEpisodes.size) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        draggingIndex = index
                                        dragOffsetY = 0f
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount
                                        val currentIdx = draggingIndex
                                        if (currentIdx in localEpisodes.indices) {
                                            val steps = (dragOffsetY / rowHeightPx).roundToInt()
                                            val targetIdx = (currentIdx + steps).coerceIn(0, localEpisodes.lastIndex)
                                            if (targetIdx != currentIdx) {
                                                val item = localEpisodes.removeAt(currentIdx)
                                                localEpisodes.add(targetIdx, item)
                                                draggingIndex = targetIdx
                                                dragOffsetY -= (targetIdx - currentIdx) * rowHeightPx
                                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = -1
                                        dragOffsetY = 0f
                                        actions?.onReorderEpisodes(localEpisodes.map { it.id })
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        dragOffsetY = 0f
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DragHandle,
                            contentDescription = "Reorder",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    // 78dp x 44dp 16:9 Thumbnail Box with Duration Badge or Playing Equalizer Bars
                    Box(
                        modifier = Modifier
                            .size(width = 78.dp, height = 44.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF16161A)),
                    ) {
                        if (thumbBitmap != null && !thumbBitmap!!.isRecycled) {
                            Image(
                                bitmap = thumbBitmap!!.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        if (ep.isPlaying) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(2.dp))
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Box(Modifier.width(2.5.dp).height(6.dp).background(OpticalGlassTokens.EmeraldAccent))
                                Box(Modifier.width(2.5.dp).height(11.dp).background(OpticalGlassTokens.EmeraldAccent))
                                Box(Modifier.width(2.5.dp).height(8.dp).background(OpticalGlassTokens.EmeraldAccent))
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(3.dp)
                                    .background(Color.Black.copy(alpha = 0.76f), RoundedCornerShape(2.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "▶ ${formatSecondsToHms(ep.durationSec.toInt())}",
                                    style = TextStyle(
                                        color = Color.White,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        fontFeatureSettings = "tnum",
                                    ),
                                )
                            }
                        }

                        if (ep.watchedFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(ep.watchedFraction.coerceIn(0f, 1f))
                                    .height(2.dp)
                                    .background(OpticalGlassTokens.EmeraldAccent),
                            )
                        }
                    }

                    // 2-Line Title
                    Text(
                        text = ep.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        style = TextStyle(
                            color = if (ep.isPlaying) Color.White else Color.White.copy(alpha = 0.80f),
                            fontSize = 12.sp,
                            fontWeight = if (ep.isPlaying) FontWeight.SemiBold else FontWeight.Normal,
                            lineHeight = 15.5.sp,
                        ),
                    )

                    // Bare `x` Remove Button
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (localEpisodes.size > 1) {
                                    localEpisodes.removeAt(index)
                                    actions?.onRemoveEpisode(ep.id)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Remove from queue",
                            tint = Color.White.copy(alpha = 0.55f),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Floating Bottom Precision Speed Bar (Screenshot 000207):
 * - Top row: `-` circle (`-0.05x`), centered `1.00x` readout pill, `+` circle (`+0.05x`).
 * - Bottom row: continuous `0.25x..4.0x` slider with milestone ticks (`0.25x`, `1.0x`, `2.0x`, `3.0x`, `4.0x`)
 *   + circular reset button (`1.0x`), plus quick milestone chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrecisionSpeedCard(
    visible: Boolean,
    ui: PlayerUiState,
    onApplySpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val current = ui.currentPlaybackSpeed.floatValue.coerceIn(0.25f, 4.0f)

    Box(
        modifier = modifier
            .fillMaxWidth(0.84f)
            .widthIn(min = 310.dp, max = 560.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE80A0A0C))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Row 1: [-] ... [1.00x] ... [+]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable {
                            val next = ((current - 0.05f) * 20f).roundToInt() / 20f
                            val clamped = next.coerceIn(0.25f, 4.0f)
                            ui.currentPlaybackSpeed.floatValue = clamped
                            onApplySpeed(clamped)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("−", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.2fx", current),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable {
                            val next = ((current + 0.05f) * 20f).roundToInt() / 20f
                            val clamped = next.coerceIn(0.25f, 4.0f)
                            ui.currentPlaybackSpeed.floatValue = clamped
                            onApplySpeed(clamped)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                }
            }

            // Row 2: Continuous 0.25x..4.0x Slider + Milestone Ticks + Reset Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = current,
                        onValueChange = { raw ->
                            val snapped = ((raw * 20f).roundToInt() / 20f).coerceIn(0.25f, 4.0f)
                            ui.currentPlaybackSpeed.floatValue = snapped
                            onApplySpeed(snapped)
                        },
                        valueRange = 0.25f..4.0f,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = OpticalGlassTokens.EmeraldAccent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.24f),
                        ),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf("0.25x" to 0.25f, "1.0x" to 1.0f, "2.0x" to 2.0f, "3.0x" to 3.0f, "4.0x" to 4.0f)
                            .forEach { (lbl, spdVal) ->
                                Text(
                                    text = lbl,
                                    modifier = Modifier.clickable {
                                        ui.currentPlaybackSpeed.floatValue = spdVal
                                        onApplySpeed(spdVal)
                                    },
                                    style = TextStyle(
                                        color = if (abs(current - spdVal) < 0.03f) OpticalGlassTokens.EmeraldSync else Color.White.copy(alpha = 0.78f),
                                        fontSize = 10.5.sp,
                                        fontWeight = if (abs(current - spdVal) < 0.03f) FontWeight.Bold else FontWeight.Normal,
                                        fontFeatureSettings = "tnum",
                                    ),
                                )
                            }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable {
                            ui.currentPlaybackSpeed.floatValue = 1.0f
                            onApplySpeed(1.0f)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset (1.0x)",
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }

            // Row 3: Quick Milestone Preset Chips
            val milestones = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                milestones.forEach { chipSpeed ->
                    val isActive = abs(current - chipSpeed) < 0.02f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isActive) OpticalGlassTokens.EmeraldAccent else Color.White.copy(alpha = 0.10f)
                            )
                            .clickable {
                                ui.currentPlaybackSpeed.floatValue = chipSpeed
                                onApplySpeed(chipSpeed)
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = "${chipSpeed}x",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Quick-Tools Ribbon Customise Drawer:
 * Lets the user toggle visibility or move tools up/down, persisted via [PlayerPrefs].
 */
@Composable
fun PlayerRibbonCustomiseDrawer(
    visible: Boolean,
    quick: QuickRowUiState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    PlayerSlideInDrawer(
        visible = visible,
        title = "Customise Quick Tools",
        onDismiss = onDismiss,
        headerTrailing = {
            Text(
                text = "Reset",
                modifier = Modifier
                    .clickable {
                        quick.ribbonOrder = RibbonTool.entries
                        quick.ribbonHidden = emptySet()
                        PlayerPrefs.saveRibbonOrder(context, RibbonTool.entries.map { it.name })
                        PlayerPrefs.saveRibbonHidden(context, emptySet())
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                style = TextStyle(
                    color = OpticalGlassTokens.EmeraldSync,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        },
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            itemsIndexed(
                items = quick.ribbonOrder,
                key = { _, tool -> tool.name },
                contentType = { _, _ -> "ribbon_customise_row" },
            ) { index, tool ->
                val isHidden = tool in quick.ribbonHidden
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (tool != RibbonTool.CUSTOMISE) {
                                val nextHidden = if (isHidden) {
                                    quick.ribbonHidden - tool
                                } else {
                                    quick.ribbonHidden + tool
                                }
                                quick.ribbonHidden = nextHidden
                                PlayerPrefs.saveRibbonHidden(context, nextHidden.map { it.name }.toSet())
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = if (isHidden) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            contentDescription = null,
                            tint = if (isHidden) Color.White.copy(alpha = 0.35f) else OpticalGlassTokens.EmeraldAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = tool.label,
                            style = TextStyle(
                                color = if (isHidden) Color.White.copy(alpha = 0.45f) else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (index > 0) {
                            Text(
                                text = "↑",
                                modifier = Modifier
                                    .clickable {
                                        val mutable = quick.ribbonOrder.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index - 1, item)
                                        quick.ribbonOrder = mutable
                                        PlayerPrefs.saveRibbonOrder(context, mutable.map { it.name })
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 14.sp,
                            )
                        }
                        if (index < quick.ribbonOrder.lastIndex) {
                            Text(
                                text = "↓",
                                modifier = Modifier
                                    .clickable {
                                        val mutable = quick.ribbonOrder.toMutableList()
                                        val item = mutable.removeAt(index)
                                        mutable.add(index + 1, item)
                                        quick.ribbonOrder = mutable
                                        PlayerPrefs.saveRibbonOrder(context, mutable.map { it.name })
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
