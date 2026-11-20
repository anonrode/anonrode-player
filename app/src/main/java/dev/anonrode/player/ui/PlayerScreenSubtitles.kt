package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anonrode.player.PlayerPrefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Subtitle cue overlay with smooth, axis-locked vertical drag (10%..85%),
 * per-video + global position persistence via [PlayerPrefs.saveSubtitlePosition],
 * horizontal fine-sync drag, and two-finger pinch font scaling.
 *
 * [BoxWithConstraints] stays mounted across inter-cue gaps so pointer and layout
 * nodes never tear down between lines.
 */
@Composable
fun PlayerSubtitleOverlay(
    cueText: State<String>,
    isPipMode: Boolean,
    ui: PlayerUiState,
    gestures: GestureUiState,
    onUpdateSubOffsetSec: (Float) -> Unit,
    mediaId: String = "",
    modifier: Modifier = Modifier,
) {
    val text = cueText.value
    val visible = ui.showCC.value && text.isNotBlank()
    if (!visible) {
        gestures.subBounds.value = Rect.Zero
    }

    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current.density

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        if (!visible) return@BoxWithConstraints

        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val anchorFrac = gestures.subPosFraction.floatValue.coerceIn(SUB_Y_MIN, SUB_Y_MAX)
        val rawY = containerH * anchorFrac
        val chromeVisible = ui.controlsVisible.value &&
            !ui.locked.value &&
            !isPipMode &&
            (ui.speedCardOpen.value || (!ui.boostActive.value && !ui.speedScrubHudVisible.value))
        val targetY = if (chromeVisible && anchorFrac > 0.60f) {
            val bottomObstaclePx = when {
                ui.speedCardOpen.value -> 148f * density
                ui.bottomBarHeightPx.intValue > 0 -> ui.bottomBarHeightPx.intValue.toFloat() + 36f * density
                else -> 124f * density
            }
            minOf(rawY, (containerH - bottomObstaclePx).coerceAtLeast(containerH * 0.45f)).roundToInt()
        } else {
            rawY.roundToInt()
        }

        val isInteractingWithSub = gestures.mode.value == GestureMode.SUBTITLE_VERTICAL_DRAG ||
            gestures.mode.value == GestureMode.SUBTITLE_HORIZONTAL_SYNC ||
            gestures.mode.value == GestureMode.SUBTITLE_PINCH_SCALE

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, targetY) }
                .graphicsLayer {
                    val scale = if (isInteractingWithSub && !isPipMode) 1.03f else 1f
                    scaleX = scale
                    scaleY = scale
                }
                .onGloballyPositioned { coords ->
                    gestures.subBounds.value = coords.boundsInRoot()
                }
                // 1. Long-press opens Subtitle Drawer on the Style page
                .pointerInput(isPipMode) {
                    if (isPipMode) return@pointerInput
                    detectTapGestures(
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            ui.subtitleDrawerOpen.value = true
                        }
                    )
                }
                // 2. Axis-locked Drag: Vertical Position (0.10..0.85) OR Horizontal Sync Nudge
                .pointerInput(containerH, isPipMode, mediaId) {
                    if (isPipMode) return@pointerInput
                    var startFrac = gestures.subPosFraction.floatValue
                    var startOffsetSec = ui.subtitleOffsetSec.floatValue
                    var accumDx = 0f
                    var accumDy = 0f
                    var lockedAxis = GestureMode.NONE

                    detectDragGestures(
                        onDragStart = {
                            startFrac = gestures.subPosFraction.floatValue
                            startOffsetSec = ui.subtitleOffsetSec.floatValue
                            accumDx = 0f
                            accumDy = 0f
                            lockedAxis = GestureMode.NONE
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumDx += dragAmount.x
                            accumDy += dragAmount.y

                            if (lockedAxis == GestureMode.NONE) {
                                val slopPx = 10f * density
                                if (abs(accumDy) >= slopPx || abs(accumDx) >= slopPx) {
                                    lockedAxis = if (abs(accumDx) > 1.6f * abs(accumDy)) {
                                        GestureMode.SUBTITLE_HORIZONTAL_SYNC
                                    } else {
                                        GestureMode.SUBTITLE_VERTICAL_DRAG
                                    }
                                    gestures.mode.value = lockedAxis
                                }
                            }

                            when (lockedAxis) {
                                GestureMode.SUBTITLE_HORIZONTAL_SYNC -> {
                                    val stepSec = ((accumDx / (14f * density)).roundToInt()) * 0.05f
                                    val newOffset = startOffsetSec + stepSec
                                    onUpdateSubOffsetSec(newOffset)
                                }
                                GestureMode.SUBTITLE_VERTICAL_DRAG -> {
                                    val newFrac = (startFrac + accumDy / containerH).coerceIn(SUB_Y_MIN, SUB_Y_MAX)
                                    gestures.subPosFraction.floatValue = newFrac
                                }
                                else -> Unit
                            }
                        },
                        onDragEnd = {
                            if (lockedAxis == GestureMode.SUBTITLE_VERTICAL_DRAG) {
                                PlayerPrefs.saveSubtitlePosition(
                                    context,
                                    mediaId,
                                    SUB_DEFAULT_X,
                                    gestures.subPosFraction.floatValue,
                                )
                            }
                            lockedAxis = GestureMode.NONE
                            gestures.mode.value = GestureMode.NONE
                        },
                        onDragCancel = {
                            lockedAxis = GestureMode.NONE
                            gestures.mode.value = GestureMode.NONE
                        },
                    )
                }
                // 3. Two-Finger Pinch on Subtitle to Scale Font Size (14sp..42sp)
                .pointerInput(isPipMode) {
                    if (isPipMode) return@pointerInput
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            gestures.mode.value = GestureMode.SUBTITLE_PINCH_SCALE
                            ui.subtitleFontSizeSp.floatValue =
                                (ui.subtitleFontSizeSp.floatValue * zoom).coerceIn(14f, 42f)
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (isInteractingWithSub) {
                val badgeText = when (gestures.mode.value) {
                    GestureMode.SUBTITLE_HORIZONTAL_SYNC ->
                        "Offset %+.2fs".format(ui.subtitleOffsetSec.floatValue)
                    GestureMode.SUBTITLE_PINCH_SCALE ->
                        "Size ${ui.subtitleFontSizeSp.floatValue.roundToInt()}sp"
                    else ->
                        "Position ${(gestures.subPosFraction.floatValue * 100).roundToInt()}%"
                }
                Box(
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xD80A0A0C))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = badgeText,
                        style = TextStyle(
                            color = OpticalGlassTokens.EmeraldSync,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                }
            }

            Box(
                modifier = if (ui.subtitleBgEnabled.value) {
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.56f))
                } else {
                    Modifier
                }
            ) {
                OutlinedSubtitleText(
                    text = text,
                    isPipMode = isPipMode,
                    fontSizeSp = ui.subtitleFontSizeSp.floatValue,
                    fillColor = ui.subtitleTextColor.value,
                    isBold = ui.subtitleBold.value,
                )
            }
        }
    }
}

/**
 * High-Performance 2-Pass Hardware Stroke Subtitle Renderer.
 */
@Composable
fun OutlinedSubtitleText(
    text: String,
    isPipMode: Boolean,
    fontSizeSp: Float = 21f,
    fillColor: Color = Color.White,
    isBold: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { (if (isPipMode) 2.dp else 3.8.dp).toPx() }
    val sizeSp = (if (isPipMode) fontSizeSp * 0.62f else fontSizeSp).sp
    val lineSp = (if (isPipMode) fontSizeSp * 0.78f else fontSizeSp * 1.28f).sp
    val weight = if (isBold) FontWeight.SemiBold else FontWeight.Medium

    Box(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = Color.Black.copy(alpha = 0.96f),
                fontWeight = weight,
                fontSize = sizeSp,
                lineHeight = lineSp,
                textAlign = TextAlign.Center,
                drawStyle = Stroke(
                    width = strokeWidthPx,
                    join = StrokeJoin.Round,
                ),
            ),
            maxLines = 4,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
        Text(
            text = text,
            style = TextStyle(
                color = fillColor,
                fontWeight = weight,
                fontSize = sizeSp,
                lineHeight = lineSp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 4,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
    }
}
