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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
        enter = fadeIn(tween(240)) + slideInVertically(tween(280, easing = OpticalGlassTokens.EaseReveal)) { 8 },
        exit = fadeOut(tween(160)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { ui.bottomBarHeightPx.intValue = it.height }
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.32f to Color.Black.copy(alpha = 0.22f),
                        1.0f to Color.Black.copy(alpha = 0.48f),
                    )
                )
                .windowInsetsPadding(safeBottomInsets)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 1. Transient Status Pills (Resume Prompt 5.0s / Auto-Sync Lock 2.5s)
            TransientStatusPillsRow(
                ui = ui,
                onStartOver = onStartOverFromZero,
            )

            // 2. Leaf Composable: SeekBarRow (0 Hz Track Recomp + 1 Hz Tnum Text Recomp)
            SeekBarRow(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                chapters = chapters,
                scrubPreviewBitmap = scrubPreviewBitmap,
                ui = ui,
                onPreviewScrubSec = onPreviewScrubSec,
                onCommitScrubSec = onCommitScrubSec,
            )

            // 3. Transport Controls Cluster
            TransportRow(
                ui = ui,
                onStepSeekSec = onStepSeekSec,
                onPlayPauseToggle = onPlayPauseToggle,
                onPreviousEpisode = onPreviousEpisode,
                onNextEpisode = onNextEpisode,
                onCycleAspectRatio = onCycleAspectRatio,
                onToggleRotateMode = onToggleRotateMode,
            )
        }
    }
}

@Composable
private fun TransientStatusPillsRow(
    ui: PlayerUiState,
    onStartOver: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = ui.resumePromptVisible.value,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(200)),
        ) {
            Row(
                modifier = Modifier
                    .opticalGlass(
                        shape = RoundedCornerShape(50),
                        baseColor = OpticalGlassTokens.SmokedHudPillBase,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Continue playing from where you stopped.",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
                Text(
                    text = "Start Over",
                    modifier = Modifier.clickable {
                        ui.resumePromptVisible.value = false
                        onStartOver()
                    },
                    style = TextStyle(
                        color = OpticalGlassTokens.CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = ui.syncToastVisible.value,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(200)),
        ) {
            val offset = ui.subtitleOffsetSec.floatValue
            Box(
                modifier = Modifier
                    .opticalGlass(
                        shape = RoundedCornerShape(50),
                        isActive = true,
                        accentColor = OpticalGlassTokens.EmeraldSync,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "⟳ Synced %+.2fs".format(offset),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/**
 * Leaf Composable: SeekBarRow.
 * - Text timestamps are quantized to 1 Hz via [derivedStateOf] (95% reduction in leaf recompositions).
 * - Track rails, buffer rail, chapter ticks, and thumb knob render inside [Modifier.drawWithCache]
 *   at 0 Hz recomposition!
 */
@Composable
fun SeekBarRow(
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    chapters: List<ChapterMarker>,
    scrubPreviewBitmap: Bitmap?,
    ui: PlayerUiState,
    onPreviewScrubSec: (Float) -> Unit,
    onCommitScrubSec: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRemaining by remember { mutableStateOf(true) }

    // 1 Hz Quantized Timestamp Reads (Only recomposes when the whole second integer changes!)
    val displayPosIntSec by remember {
        derivedStateOf {
            val activeSeek = ui.localSeek.floatValue
            (if (activeSeek >= 0f) activeSeek else positionSec.value).toInt().coerceAtLeast(0)
        }
    }
    val displayDurIntSec by remember {
        derivedStateOf { durationSec.value.toInt().coerceAtLeast(1) }
    }

    val isScrubbing by remember {
        derivedStateOf { ui.localSeek.floatValue >= 0f }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportWidthPx = constraints.maxWidth.toFloat()

        // ScrubBubble 128dp x 72dp floating directly above the active thumb
        if (isScrubbing) {
            val dur = durationSec.value.coerceAtLeast(1f)
            val seekSec = ui.localSeek.floatValue.coerceIn(0f, dur)
            val deltaSec = (seekSec - positionSec.value).roundToInt()
            ScrubBubble(
                targetSec = seekSec,
                deltaSec = deltaSec,
                durationSec = dur,
                viewportWidthPx = viewportWidthPx,
                previewBitmap = scrubPreviewBitmap,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 36.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Left Elapsed Time (Tnum)
            Text(
                text = formatSecondsToHms(displayPosIntSec),
                modifier = Modifier.width(52.dp),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    shadow = OpticalGlassTokens.CrispTextShadow,
                ),
            )

            // Center 5-Layer Optical Scrubber Canvas (0 Hz Recomposition on Playback Ticks!)
            OpticalSeekBarCanvas(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                chapters = chapters,
                ui = ui,
                onPreviewScrubSec = onPreviewScrubSec,
                onCommitScrubSec = onCommitScrubSec,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .padding(horizontal = 7.dp),
            )

            // Right Duration / Remaining Time (Tnum)
            val rightLabel = if (showRemaining) {
                "-${formatSecondsToHms((displayDurIntSec - displayPosIntSec).coerceAtLeast(0))}"
            } else {
                formatSecondsToHms(displayDurIntSec)
            }
            Text(
                text = rightLabel,
                modifier = Modifier
                    .width(56.dp)
                    .clickable { showRemaining = !showRemaining },
                textAlign = TextAlign.End,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    shadow = OpticalGlassTokens.CrispTextShadow,
                ),
            )
        }
    }
}

@Composable
private fun OpticalSeekBarCanvas(
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
    val isScrubbing by remember { derivedStateOf { ui.localSeek.floatValue >= 0f } }
    val railHeightDp by animateDpAsState(
        targetValue = if (isScrubbing) 5.dp else 3.dp,
        animationSpec = tween(140),
        label = "railHeight",
    )

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    ui.bumpInteraction()
                    val dur = durationSec.value.coerceAtLeast(1f)
                    val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val targetSec = frac * dur
                    onCommitScrubSec(targetSec)
                }
            }
            .pointerInput(chapters) {
                var lastChapterIdx = -1
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        ui.bumpInteraction()
                        val dur = durationSec.value.coerceAtLeast(1f)
                        val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val targetSec = frac * dur
                        ui.localSeek.floatValue = targetSec
                        onPreviewScrubSec(targetSec)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val dur = durationSec.value.coerceAtLeast(1f)
                        val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val targetSec = frac * dur

                        // Magnetic haptic tick when crossing chapter boundaries
                        val chIdx = chapters.indexOfLast { it.timeSec <= targetSec }
                        if (chIdx != lastChapterIdx && lastChapterIdx != -1) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        lastChapterIdx = chIdx

                        ui.localSeek.floatValue = targetSec
                        onPreviewScrubSec(targetSec)
                    },
                    onDragEnd = {
                        val finalSec = ui.localSeek.floatValue
                        if (finalSec >= 0f) {
                            onCommitScrubSec(finalSec)
                        }
                        ui.localSeek.floatValue = -1f
                        lastChapterIdx = -1
                    },
                    onDragCancel = {
                        ui.localSeek.floatValue = -1f
                        lastChapterIdx = -1
                    },
                )
            }
            .drawWithCache {
                val trackH = railHeightDp.toPx()
                val cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
                val thumbRadiusPx = 7.dp.toPx()
                val glowRadiusPx = 10.dp.toPx()
                val chapterTickW = 2.dp.toPx()

                onDrawBehind {
                    val w = size.width
                    val cy = size.height / 2f
                    val topY = cy - trackH / 2f

                    val dur = durationSec.value.coerceAtLeast(1f)
                    val activeSeek = ui.localSeek.floatValue
                    val currentPos = if (activeSeek >= 0f) activeSeek else positionSec.value
                    val posFrac = (currentPos / dur).coerceIn(0f, 1f)
                    // INVARIANT: Buffered rail is NEVER coerced up to played fraction!
                    val bufFrac = (bufferedSec.value / dur).coerceIn(0f, 1f)

                    // 1. Background Rail (0.22f white)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.22f),
                        topLeft = Offset(0f, topY),
                        size = Size(w, trackH),
                        cornerRadius = cornerRadius,
                    )

                    // 2. Honest Unclamped Buffered Rail (0.45f white)
                    if (bufFrac > 0f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.45f),
                            topLeft = Offset(0f, topY),
                            size = Size(w * bufFrac, trackH),
                            cornerRadius = cornerRadius,
                        )
                    }

                    // 3. Active Played Rail (Accent)
                    if (posFrac > 0f) {
                        drawRoundRect(
                            color = OpticalGlassTokens.CyanAccent,
                            topLeft = Offset(0f, topY),
                            size = Size(w * posFrac, trackH),
                            cornerRadius = cornerRadius,
                        )
                    }

                    // 4. Chapter Boundary Ticks & A-B Repeat Pins
                    chapters.forEach { ch ->
                        val chFrac = (ch.timeSec / dur).coerceIn(0f, 1f)
                        if (chFrac in 0.01f..0.99f) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.78f),
                                topLeft = Offset(w * chFrac - chapterTickW / 2f, topY),
                                size = Size(chapterTickW, trackH),
                            )
                        }
                    }

                    // 5. 14dp White Thumb with 20dp Accent Glow
                    val thumbCenter = Offset(w * posFrac, cy)
                    drawCircle(
                        color = OpticalGlassTokens.CyanAccent.copy(alpha = 0.26f),
                        radius = glowRadiusPx,
                        center = thumbCenter,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadiusPx,
                        center = thumbCenter,
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
    val bubbleWidthPx = with(density) { 128.dp.toPx() }
    val sidePadPx = with(density) { 16.dp.toPx() }
    val frac = (targetSec / durationSec.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val rawCenterX = frac * viewportWidthPx
    val clampedX = (rawCenterX - bubbleWidthPx / 2f)
        .coerceIn(sidePadPx, (viewportWidthPx - bubbleWidthPx - sidePadPx).coerceAtLeast(sidePadPx))

    Box(
        modifier = modifier
            .offset { IntOffset(clampedX.roundToInt(), 0) }
            .size(width = 128.dp, height = 72.dp)
            .opticalGlass(
                shape = RoundedCornerShape(10.dp),
                baseColor = Color(0xFF080C14).copy(alpha = 0.72f),
                isActive = true,
                accentColor = OpticalGlassTokens.CyanAccent,
                borderWidth = 1.5.dp,
            ),
    ) {
        if (previewBitmap != null && !previewBitmap.isRecycled) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Bottom glass metadata strip: Timestamp + Signed Delta
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
                    color = OpticalGlassTokens.CyanAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

@Composable
private fun TransportRow(
    ui: PlayerUiState,
    onStepSeekSec: (Int) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleRotateMode: () -> Unit,
) {
    val stepSec = ui.seekStepSec.intValue
    val zoomLabel = AspectZoomMode.entries[ui.zoomIdx.intValue % AspectZoomMode.entries.size].label

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Wing: Screen Lock
        Row(
            modifier = Modifier.width(116.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpticalGlassDiscButton(
                onClick = {
                    ui.locked.value = true
                    ui.lockScrimVisible.value = true
                },
            ) {
                Text("🔒", fontSize = 14.sp)
            }
        }

        // Center Cluster: Prev, -Step, 56dp Primary Play/Pause Knob, +Step, Next
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpticalGlassDiscButton(onClick = onPreviousEpisode) {
                Text("⏮", color = Color.White, fontSize = 14.sp)
            }
            OpticalGlassDiscButton(onClick = { onStepSeekSec(-stepSec) }) {
                Text(
                    text = "-${stepSec}s",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }

            // 56dp Primary Optical Play/Pause Knob
            OpticalGlassDiscButton(
                onClick = onPlayPauseToggle,
                isActive = true,
                discSize = 56.dp,
                touchTargetSize = 60.dp,
                accentColor = OpticalGlassTokens.CyanAccent,
            ) {
                Text(
                    text = if (ui.isPlaying.value) "⏸" else "▶",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }

            OpticalGlassDiscButton(onClick = { onStepSeekSec(+stepSec) }) {
                Text(
                    text = "+${stepSec}s",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
            OpticalGlassDiscButton(onClick = onNextEpisode) {
                Text("⏭", color = Color.White, fontSize = 14.sp)
            }
        }

        // Right Wing: Aspect Ratio Cycle (`FIT`, `FILL`, `ZOOM`, `16:9`, `4:3`) + Rotate
        Row(
            modifier = Modifier.width(116.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpticalGlassChip(
                label = zoomLabel,
                onClick = onCycleAspectRatio,
                height = 36.dp,
            )
            OpticalGlassDiscButton(onClick = onToggleRotateMode) {
                Text("🔄", fontSize = 14.sp)
            }
        }
    }
}
