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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
 * Full-height flat right slide-in drawer (responsive width: ~45% in landscape = 374dp,
 * ~86% in portrait = 337dp, never crushed to 164dp).
 * Smooth horizontal translucent black gradient, zero blur, zero card borders,
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
    val panelWidthPx = with(density) { 380.dp.toPx() }
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val drawerWidth = if (maxWidth >= 600.dp) {
            (maxWidth * 0.45f).coerceIn(350.dp, 410.dp)
        } else {
            (maxWidth * 0.86f).coerceIn(310.dp, 360.dp)
        }

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
                .width(drawerWidth)
                .fillMaxHeight()
                .graphicsLayer { translationX = dragX.value }
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color(0x8C040406),
                        0.18f to Color(0xE8040406),
                        1.0f to Color(0xF8040406),
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
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
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
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 15.5.sp,
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
                    .padding(bottom = 14.dp),
                content = content,
            )
        }
    }
}

/**
 * Razor-thin 2.5dp horizontal precision line slider with optional milestone dots
 * and a clean 14dp solid white circle thumb (`CircleShape`).
 * Replaces Material 3's thick 20dp sausage track and 44dp vertical stick thumb.
 */
@Composable
fun ThinPrecisionLineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    tickValues: FloatArray = FloatArray(0),
    showActiveFill: Boolean = true,
    activeColor: Color = OpticalGlassTokens.EmeraldAccent,
) {
    val view = LocalView.current
    var isDragging by remember { mutableStateOf(false) }
    val minV = valueRange.start
    val maxV = valueRange.endInclusive
    val span = (maxV - minV).coerceAtLeast(1e-4f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(minV, maxV) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onValueChange(minV + frac * span)
                }
            }
            .pointerInput(minV, maxV) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onValueChange(minV + frac * span)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        onValueChange(minV + frac * span)
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                )
            }
            .drawWithCache {
                val trackH = 2.6.dp.toPx()
                val thumbR = 7.dp.toPx()
                val dotR = 2.1.dp.toPx()
                val padX = thumbR
                val cornerR = CornerRadius(trackH / 2f, trackH / 2f)

                onDrawBehind {
                    val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
                    val centerY = size.height / 2f
                    val topY = centerY - trackH / 2f
                    val frac = ((value - minV) / span).coerceIn(0f, 1f)
                    val thumbX = padX + usableW * frac

                    // 1. Razor-thin unplayed track line
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.28f),
                        topLeft = Offset(padX, topY),
                        size = Size(usableW, trackH),
                        cornerRadius = cornerR,
                    )

                    // 2. Active filled segment
                    if (showActiveFill && thumbX > padX) {
                        drawRoundRect(
                            color = activeColor,
                            topLeft = Offset(padX, topY),
                            size = Size(thumbX - padX, trackH),
                            cornerRadius = cornerR,
                        )
                    }

                    // 3. Milestone tick dots along the line (Screenshot 000207)
                    for (tVal in tickValues) {
                        val tFrac = ((tVal - minV) / span).coerceIn(0f, 1f)
                        val tickX = padX + usableW * tFrac
                        drawCircle(
                            color = Color.White.copy(alpha = 0.88f),
                            radius = dotR,
                            center = Offset(tickX, centerY),
                        )
                    }

                    // 4. Clean round white circle thumb
                    if (isDragging) {
                        drawCircle(
                            color = activeColor.copy(alpha = 0.28f),
                            radius = thumbR * 1.65f,
                            center = Offset(thumbX, centerY),
                        )
                    }
                    drawCircle(
                        color = Color.White,
                        radius = thumbR,
                        center = Offset(thumbX, centerY),
                    )
                }
            },
    )
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
            .padding(horizontal = 22.dp, vertical = 8.dp)
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
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = TextStyle(
                    color = if (isSelected) OpticalGlassTokens.EmeraldSync else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                ),
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        color = subtitleColor,
                        fontSize = 11.5.sp,
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

                    ThinPrecisionLineSlider(
                        value = currentOffset.coerceIn(windowMin, windowMax),
                        onValueChange = { newVal ->
                            ui.subtitleOffsetSec.floatValue = newVal
                            if (abs(newVal - ui.subtitleBaseOffsetSec.floatValue) > 4.8f) {
                                ui.subtitleBaseOffsetSec.floatValue = newVal
                            }
                            onApplySubtitleOffsetSec(newVal)
                        },
                        valueRange = windowMin..windowMax,
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
 * Controls / Settings Drawer (Screenshot 000202):
 * 4-column circular thin-outline icon grid hosting all abstracted player tools & drawers,
 * followed by a hairline divider and clean flat toggle rows.
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 4-Column Circular Thin-Outline Icon Grid (Screenshot 000202)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Row 1: Playing Queue, Aspect Ratio, Decoder, Audio Track
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
                        label = "Aspect Ratio",
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

                // Row 2: Sleep Timer, Equalizer, Audio FX, Loop Mode (abstracted from Ribbon)
                Row(modifier = Modifier.fillMaxWidth()) {
                    val sleepMin = ui.sleepTimerMinutes.intValue
                    val sleepLbl = when (sleepMin) {
                        0 -> "Sleep Timer"
                        -1 -> "Sleep: End"
                        else -> "Sleep ${sleepMin}m"
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
                        label = "Audio FX",
                        icon = Icons.Rounded.GraphicEq,
                        isActive = ui.audioEffectOn.value || ui.volumeBoostPercent.intValue > 100,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val next = !ui.audioEffectOn.value
                            if (actions != null) actions.onToggleAudioEffect(next) else ui.audioEffectOn.value = next
                        },
                    )
                    val loopLabel = when (ui.repeatMode.value) {
                        RepeatLoopMode.OFF -> "Loop"
                        RepeatLoopMode.ONE -> "Loop One"
                        RepeatLoopMode.ALL -> "Loop All"
                    }
                    CircularOutlineTool(
                        label = loopLabel,
                        icon = if (ui.repeatMode.value == RepeatLoopMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        isActive = ui.repeatMode.value != RepeatLoopMode.OFF,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val modes = RepeatLoopMode.entries
                            val next = modes[(ui.repeatMode.value.ordinal + 1) % modes.size]
                            if (actions != null) {
                                actions.onUpdatePlaylistMode(ui.shuffleOn.value, next)
                            } else {
                                ui.repeatMode.value = next
                            }
                        },
                    )
                }

                // Row 3: Shuffle, Pop-up Play, Information, All Settings
                Row(modifier = Modifier.fillMaxWidth()) {
                    CircularOutlineTool(
                        label = "Shuffle",
                        icon = Icons.Rounded.Shuffle,
                        isActive = ui.shuffleOn.value,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val next = !ui.shuffleOn.value
                            if (actions != null) {
                                actions.onUpdatePlaylistMode(next, ui.repeatMode.value)
                            } else {
                                ui.shuffleOn.value = next
                            }
                        },
                    )
                    CircularOutlineTool(
                        label = "Pop-up Play",
                        icon = Icons.Rounded.PictureInPictureAlt,
                        isActive = false,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onDismiss()
                            onEnterPip()
                        },
                    )
                    CircularOutlineTool(
                        label = "Information",
                        icon = Icons.Rounded.Info,
                        isActive = ui.nerdStatsVisible.value,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ui.nerdStatsVisible.value = !ui.nerdStatsVisible.value
                            onDismiss()
                        },
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

            // Quick Shortcuts Customise Row (matches "Shortcuts" row in Screenshot 000202)
            FlatDrawerRow(
                title = "Shortcuts",
                trailing = {
                    EmeraldToggleSwitch(
                        checked = quick.ribbonExpanded.value,
                        onCheckedChange = { expanded -> quick.ribbonExpanded.value = expanded },
                    )
                },
                onClick = {
                    onDismiss()
                    quick.showRibbonCustomise.value = true
                },
            )
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
            .padding(horizontal = 2.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
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
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = TextStyle(
                color = if (isActive) OpticalGlassTokens.EmeraldSync else Color.White.copy(alpha = 0.88f),
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
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
 * - Top row: `-` circle (`-0.05x`), centered `1.00x` readout pill with pencil glyph, `+` circle (`+0.05x`).
 * - Bottom row: razor-thin 2.5dp `0.25x..4.0x` line slider with small white milestone dots
 *   and 14dp round white circle thumb, `0.25x..4.0x` scale labels underneath, and circular reset icon.
 */
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
            .fillMaxWidth(0.72f)
            .widthIn(min = 320.dp, max = 540.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE80A0A0C))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 22.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Row 1: [-] ... [1.00x ✎] ... [+]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
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

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.11f))
                        .clickable {
                            // Tapping readout pill cycles common presets (1.0x -> 1.25x -> 1.5x -> 2.0x -> 1.0x)
                            val presets = floatArrayOf(1.0f, 1.25f, 1.5f, 2.0f)
                            val idx = presets.indexOfFirst { abs(it - current) < 0.03f }
                            val next = presets[(idx + 1) % presets.size]
                            ui.currentPlaybackSpeed.floatValue = next
                            onApplySpeed(next)
                        }
                        .padding(horizontal = 22.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.2fx", current),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.90f),
                        modifier = Modifier.size(14.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
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

            // Row 2: Thin 2.5dp Line Slider + Milestone Dots + Scale Labels + Reset Icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    ThinPrecisionLineSlider(
                        value = current,
                        onValueChange = { raw ->
                            val snapped = ((raw * 20f).roundToInt() / 20f).coerceIn(0.25f, 4.0f)
                            ui.currentPlaybackSpeed.floatValue = snapped
                            onApplySpeed(snapped)
                        },
                        valueRange = 0.25f..4.0f,
                        tickValues = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f),
                        showActiveFill = true,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf("0.25x" to 0.25f, "1.0x" to 1.0f, "2.0x" to 2.0f, "3.0x" to 3.0f, "4.0x" to 4.0f)
                            .forEach { (lbl, spdVal) ->
                                val isSel = abs(current - spdVal) < 0.03f
                                Text(
                                    text = lbl,
                                    modifier = Modifier.clickable {
                                        ui.currentPlaybackSpeed.floatValue = spdVal
                                        onApplySpeed(spdVal)
                                    },
                                    style = TextStyle(
                                        color = if (isSel) Color.White else Color.White.copy(alpha = 0.80f),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSel) FontWeight.SemiBold else FontWeight.Normal,
                                        fontFeatureSettings = "tnum",
                                    ),
                                )
                            }
                    }
                }

                Box(
                    modifier = Modifier
                        .size(34.dp)
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
                        quick.ribbonOrder = RibbonTool.defaultVisibleOrder
                        quick.ribbonHidden = RibbonTool.defaultHiddenSet
                        PlayerPrefs.saveRibbonOrder(context, RibbonTool.defaultVisibleOrder.map { it.name })
                        PlayerPrefs.saveRibbonHidden(context, RibbonTool.defaultHiddenSet.map { it.name }.toSet())
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
