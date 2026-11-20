package dev.anonrode.player.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun PlayerScreenBottomBar(
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    chapters: List<ChapterMarker>,
    scrubPreviewBitmap: Bitmap?,
    ui: PlayerUiState,
    quick: QuickRowUiState? = null,
    onPreviewScrubSec: (Float) -> Unit,
    onCommitScrubSec: (Float) -> Unit,
    onStepSeekSec: (Int) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleRotateMode: () -> Unit,
    onStartOverFromZero: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeBottomInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    AnimatedVisibility(
        visible = ui.controlsVisible.value && !ui.locked.value,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(240, easing = OpticalGlassTokens.EaseReveal)) { 8 },
        exit = fadeOut(tween(150)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { ui.bottomBarHeightPx.intValue = it.height }
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.32f to Color.Black.copy(alpha = 0.28f),
                        1.0f to Color.Black.copy(alpha = 0.76f),
                    )
                )
                .windowInsetsPadding(safeBottomInsets)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 1. Transient Status Pills (Resume Prompt 5.0s / Auto-Sync Lock 2.5s / Up-Next)
            TransientStatusPillsRow(
                ui = ui,
                onStartOver = onStartOverFromZero,
                onPlayNext = onNextEpisode,
            )

            // 2. Leaf Composable: SeekBarRow (0 Hz Track Recomp + 1 Hz Tnum Text Recomp)
            SeekBarRow(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                chapters = chapters,
                scrubPreviewBitmap = scrubPreviewBitmap,
                ui = ui,
                quick = quick,
                onPreviewScrubSec = onPreviewScrubSec,
                onCommitScrubSec = onCommitScrubSec,
            )

            // 3. Borderless Transport Controls Cluster
            TransportRow(
                ui = ui,
                onStepSeekSec = onStepSeekSec,
                onPlayPauseToggle = onPlayPauseToggle,
                onPreviousEpisode = onPreviousEpisode,
                onNextEpisode = onNextEpisode,
                onCycleAspectRatio = onCycleAspectRatio,
                onOpenSpeedBar = { ui.speedCardOpen.value = !ui.speedCardOpen.value },
            )
        }
    }
}

@Composable
private fun TransientStatusPillsRow(
    ui: PlayerUiState,
    onStartOver: () -> Unit,
    onPlayNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = ui.resumePromptVisible.value,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
        ) {
            val resumeTime = if (ui.resumedFromSec.floatValue > 0f) {
                "Resumed at ${formatSecondsToHms(ui.resumedFromSec.floatValue.toInt())}"
            } else {
                "Resumed playback"
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xD80A0A0C))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = resumeTime,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                    ),
                )
                Text(
                    text = "Start over",
                    modifier = Modifier.clickable {
                        ui.resumePromptVisible.value = false
                        onStartOver()
                    },
                    style = TextStyle(
                        color = OpticalGlassTokens.EmeraldSync,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = ui.upNextTitle.value != null && ui.upNextCountdownSec.intValue >= 0,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(160)),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xD80A0A0C))
                    .clickable(onClick = onPlayNext)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Up Next in ${ui.upNextCountdownSec.intValue}s",
                    style = TextStyle(
                        color = OpticalGlassTokens.EmeraldSync,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
                Text(
                    text = "Play Now",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SeekBarRow(
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    chapters: List<ChapterMarker>,
    scrubPreviewBitmap: Bitmap?,
    ui: PlayerUiState,
    quick: QuickRowUiState?,
    onPreviewScrubSec: (Float) -> Unit,
    onCommitScrubSec: (Float) -> Unit,
) {
    val elapsedIntSec by remember {
        derivedStateOf {
            val active = if (ui.localSeek.floatValue >= 0f) ui.localSeek.floatValue else positionSec.value
            active.toInt().coerceAtLeast(0)
        }
    }
    val totalIntSec by remember {
        derivedStateOf { durationSec.value.toInt().coerceAtLeast(0) }
    }
    var localRemainingToggle by remember { mutableStateOf(true) }
    val showRemaining = quick?.remainingTimeMode?.value ?: localRemainingToggle

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val viewportWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val isScrubbing = ui.localSeek.floatValue >= 0f

        Column(modifier = Modifier.fillMaxWidth()) {
            if (isScrubbing) {
                val delta = (ui.localSeek.floatValue - positionSec.value).roundToInt()
                ScrubBubble(
                    targetSec = ui.localSeek.floatValue,
                    deltaSec = delta,
                    durationSec = durationSec.value,
                    viewportWidthPx = viewportWidthPx,
                    previewBitmap = scrubPreviewBitmap,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = formatSecondsToHms(elapsedIntSec),
                    modifier = Modifier.width(PlayerDimens.timeLabelMinW),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )

                DrawPhaseScrubberTrack(
                    positionSec = positionSec,
                    durationSec = durationSec,
                    bufferedSec = bufferedSec,
                    chapters = chapters,
                    ui = ui,
                    onPreviewScrubSec = onPreviewScrubSec,
                    onCommitScrubSec = onCommitScrubSec,
                    modifier = Modifier
                        .weight(1f)
                        .height(26.dp),
                )

                val rightLabel = if (showRemaining && totalIntSec > 0) {
                    "-${formatSecondsToHms((totalIntSec - elapsedIntSec).coerceAtLeast(0))}"
                } else {
                    formatSecondsToHms(totalIntSec)
                }
                Text(
                    text = rightLabel,
                    modifier = Modifier
                        .width(PlayerDimens.timeLabelMinW)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (quick != null) {
                                    quick.remainingTimeMode.value = !quick.remainingTimeMode.value
                                } else {
                                    localRemainingToggle = !localRemainingToggle
                                }
                            },
                        ),
                    textAlign = TextAlign.End,
                    style = TextStyle(
                        color = Color.White.copy(alpha = 0.88f),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DrawPhaseScrubberTrack(
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    chapters: List<ChapterMarker>,
    ui: PlayerUiState,
    onPreviewScrubSec: (Float) -> Unit,
    onCommitScrubSec: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val isDragging = ui.localSeek.floatValue >= 0f
    val trackHeightDp by animateDpAsState(
        targetValue = if (isDragging) 4.5.dp else 2.8.dp,
        animationSpec = tween(120),
        label = "trackHeight",
    )
    val thumbRadiusDp by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 6.dp,
        animationSpec = tween(120),
        label = "thumbRadius",
    )

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    ui.bumpInteraction()
                    val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    val targetSec = frac * durationSec.value.coerceAtLeast(1f)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onCommitScrubSec(targetSec)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        ui.bumpInteraction()
                        val frac = (offset.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val targetSec = frac * durationSec.value.coerceAtLeast(1f)
                        ui.localSeek.floatValue = targetSec
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        onPreviewScrubSec(targetSec)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        ui.bumpInteraction()
                        val frac = (change.position.x / size.width.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f)
                        val targetSec = frac * durationSec.value.coerceAtLeast(1f)
                        ui.localSeek.floatValue = targetSec
                        onPreviewScrubSec(targetSec)
                    },
                    onDragEnd = {
                        val finalSec = ui.localSeek.floatValue
                        ui.localSeek.floatValue = -1f
                        if (finalSec >= 0f) {
                            onCommitScrubSec(finalSec)
                        }
                    },
                    onDragCancel = {
                        ui.localSeek.floatValue = -1f
                    },
                )
            }
            .drawWithCache {
                val trackH = trackHeightDp.toPx()
                val thumbR = thumbRadiusDp.toPx()
                val cornerR = CornerRadius(trackH / 2f, trackH / 2f)

                onDrawBehind {
                    val dur = durationSec.value.coerceAtLeast(1f)
                    val activePos = if (ui.localSeek.floatValue >= 0f) ui.localSeek.floatValue else positionSec.value
                    val playFrac = (activePos / dur).coerceIn(0f, 1f)
                    val bufFrac = (bufferedSec.value / dur).coerceIn(0f, 1f)
                    val centerY = size.height / 2f
                    val topY = centerY - trackH / 2f

                    // 1. Unplayed rail
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.30f),
                        topLeft = Offset(0f, topY),
                        size = Size(size.width, trackH),
                        cornerRadius = cornerR,
                    )

                    // 2. Buffered rail
                    if (bufFrac > 0f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.50f),
                            topLeft = Offset(0f, topY),
                            size = Size(size.width * bufFrac, trackH),
                            cornerRadius = cornerR,
                        )
                    }

                    // 3. Played fill (Emerald)
                    if (playFrac > 0f) {
                        drawRoundRect(
                            color = OpticalGlassTokens.EmeraldAccent,
                            topLeft = Offset(0f, topY),
                            size = Size(size.width * playFrac, trackH),
                            cornerRadius = cornerR,
                        )
                    }

                    // 4. Chapter ticks
                    chapters.forEach { ch ->
                        val cFrac = (ch.timeSec / dur).coerceIn(0f, 1f)
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.7f),
                            radius = trackH * 0.6f,
                            center = Offset(size.width * cFrac, centerY),
                        )
                    }

                    // 5. Amber A-B repeat pins
                    if (ui.abStartSec.floatValue >= 0f) {
                        val aX = ((ui.abStartSec.floatValue / dur).coerceIn(0f, 1f)) * size.width
                        drawRoundRect(
                            color = OpticalGlassTokens.AmberBoost,
                            topLeft = Offset(aX - 2f, centerY - trackH * 1.8f),
                            size = Size(4f, trackH * 3.6f),
                            cornerRadius = CornerRadius(2f, 2f),
                        )
                    }
                    if (ui.abEndSec.floatValue >= 0f) {
                        val bX = ((ui.abEndSec.floatValue / dur).coerceIn(0f, 1f)) * size.width
                        drawRoundRect(
                            color = OpticalGlassTokens.AmberBoost,
                            topLeft = Offset(bX - 2f, centerY - trackH * 1.8f),
                            size = Size(4f, trackH * 3.6f),
                            cornerRadius = CornerRadius(2f, 2f),
                        )
                    }

                    // 6. Crisp white thumb
                    val thumbX = (size.width * playFrac).coerceIn(thumbR, (size.width - thumbR).coerceAtLeast(thumbR))
                    if (isDragging) {
                        drawCircle(
                            color = OpticalGlassTokens.EmeraldAccent.copy(alpha = 0.28f),
                            radius = thumbR * 1.75f,
                            center = Offset(thumbX, centerY),
                        )
                    }
                    drawCircle(
                        color = Color.White,
                        radius = thumbR,
                        center = Offset(thumbX, centerY),
                    )
                }
            }
    )
}

@Composable
fun ScrubBubble(
    targetSec: Float,
    deltaSec: Int,
    durationSec: Float,
    viewportWidthPx: Float,
    previewBitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val bubbleWidthPx = with(density) { 124.dp.toPx() }
    val sidePadPx = with(density) { 12.dp.toPx() }
    val frac = (targetSec / durationSec.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val rawCenterX = frac * viewportWidthPx
    val clampedX = (rawCenterX - bubbleWidthPx / 2f)
        .coerceIn(sidePadPx, (viewportWidthPx - bubbleWidthPx - sidePadPx).coerceAtLeast(sidePadPx))

    Box(
        modifier = modifier
            .offset { IntOffset(clampedX.roundToInt(), 0) }
            .size(width = 124.dp, height = 70.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xE60A0A0C))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
    ) {
        if (previewBitmap != null && !previewBitmap.isRecycled) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatSecondsToHms(targetSec.toInt()),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
            val sign = if (deltaSec >= 0) "+" else ""
            Text(
                text = "$sign${deltaSec}s",
                style = TextStyle(
                    color = OpticalGlassTokens.EmeraldSync,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

/**
 * Borderless Transport Row:
 * - Left Wing: bare Lock icon
 * - Center Cluster: Replay10, SkipPrevious, 44dp Ringed Play/Pause, SkipNext, Forward10
 * - Right Wing: Speed text trigger + Aspect Ratio icon & label
 */
@Composable
private fun TransportRow(
    ui: PlayerUiState,
    onStepSeekSec: (Int) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onOpenSpeedBar: () -> Unit,
) {
    val stepSec = ui.seekStepSec.intValue
    val zoomLabel = AspectZoomMode.entries[ui.zoomIdx.intValue % AspectZoomMode.entries.size].label
    val spd = ui.currentPlaybackSpeed.floatValue
    val speedBtnText = if (abs(spd - 1.0f) < 0.02f) "Speed" else "%.2fx".format(spd).replace(".00x", ".0x")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Wing: Bare Lock Button
        Row(
            modifier = Modifier.width(116.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable {
                        ui.locked.value = true
                        ui.lockScrimVisible.value = true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.LockOpen,
                    contentDescription = "Lock Screen",
                    tint = Color.White,
                    modifier = Modifier.size(21.dp),
                )
            }
        }

        // Center Cluster: -10s, Prev, Ringed Play/Pause, Next, +10s
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onStepSeekSec(-stepSec) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Replay10,
                    contentDescription = "Rewind ${stepSec}s",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPreviousEpisode),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }

            // 46dp Clean Thin-Ringed Play/Pause Hero
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .border(1.6.dp, Color.White.copy(alpha = 0.90f), CircleShape)
                    .clickable(onClick = onPlayPauseToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (ui.isPlaying.value) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (ui.isPlaying.value) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(25.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onNextEpisode),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable { onStepSeekSec(+stepSec) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Forward10,
                    contentDescription = "Forward ${stepSec}s",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // Right Wing: Speed trigger + Unmistakable Aspect Ratio control
        Row(
            modifier = Modifier.width(116.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenSpeedBar)
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = speedBtnText,
                    style = TextStyle(
                        color = if (abs(spd - 1.0f) > 0.02f) OpticalGlassTokens.EmeraldSync else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }

            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        onCycleAspectRatio()
                        ui.aspectBadgeVisible.value = true
                    }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.AspectRatio,
                    contentDescription = "Aspect Ratio ($zoomLabel)",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = zoomLabel,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }
        }
    }
}
