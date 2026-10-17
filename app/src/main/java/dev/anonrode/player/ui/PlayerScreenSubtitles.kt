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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerSubtitleOverlay(
    cueText: State<String>,
    isPipMode: Boolean,
    ui: PlayerUiState,
    gestures: GestureUiState,
    onUpdateSubOffsetSec: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = cueText.value
    if (!ui.showCC.value || text.isBlank()) {
        gestures.subBounds.value = Rect.Zero
        return
    }

    val view = LocalView.current
    val density = LocalDensity.current.density

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val anchorFrac = gestures.subPosFraction.floatValue.coerceIn(0.10f, 0.85f)
        val targetY = (containerH * anchorFrac).roundToInt()

        val isInteractingWithSub = gestures.mode.value == GestureMode.SUBTITLE_VERTICAL_DRAG ||
            gestures.mode.value == GestureMode.SUBTITLE_HORIZONTAL_SYNC ||
            gestures.mode.value == GestureMode.SUBTITLE_PINCH_SCALE

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, targetY) }
                .onGloballyPositioned { coords ->
                    gestures.subBounds.value = coords.boundsInRoot()
                }
                .then(
                    if (isInteractingWithSub) {
                        Modifier.opticalGlass(
                            shape = RoundedCornerShape(10.dp),
                            baseColor = OpticalGlassTokens.SmokedHudPillBase,
                            isActive = true,
                        )
                    } else if (ui.subtitleBgEnabled.value) {
                        Modifier
                            .background(
                                Color.Black.copy(alpha = 0.48f),
                                RoundedCornerShape(8.dp),
                            )
                    } else {
                        Modifier
                    }
                )
                // 1. Long-press opens Subtitle Drawer Style page
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            ui.subtitleDrawerOpen.value = true
                        }
                    )
                }
                // 2. Vertical Drag (Anchor 0.10..0.85) & Horizontal Drag (Live Cue-Sync Nudge)
                .pointerInput(containerH) {
                    var startFrac = gestures.subPosFraction.floatValue
                    var startOffsetSec = ui.subtitleOffsetSec.floatValue
                    var accumDx = 0f
                    var accumDy = 0f

                    detectDragGestures(
                        onDragStart = {
                            startFrac = gestures.subPosFraction.floatValue
                            startOffsetSec = ui.subtitleOffsetSec.floatValue
                            accumDx = 0f
                            accumDy = 0f
                            gestures.mode.value = GestureMode.SUBTITLE_VERTICAL_DRAG
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumDx += dragAmount.x
                            accumDy += dragAmount.y

                            if (abs(accumDx) > 1.4f * abs(accumDy) && abs(accumDx) > 18f * density) {
                                gestures.mode.value = GestureMode.SUBTITLE_HORIZONTAL_SYNC
                                val stepSec = ((accumDx / (12f * density)).roundToInt()) * 0.05f
                                val newOffset = startOffsetSec + stepSec
                                onUpdateSubOffsetSec(newOffset)
                            } else {
                                gestures.mode.value = GestureMode.SUBTITLE_VERTICAL_DRAG
                                val newFrac = (startFrac + accumDy / containerH).coerceIn(0.10f, 0.85f)
                                gestures.subPosFraction.floatValue = newFrac
                            }
                        },
                        onDragEnd = {
                            gestures.mode.value = GestureMode.NONE
                        },
                        onDragCancel = {
                            gestures.mode.value = GestureMode.NONE
                        },
                    )
                }
                // 3. Two-Finger Pinch on Subtitle to Scale Font Size (14sp..42sp)
                .pointerInput(Unit) {
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
            OutlinedSubtitleText(
                text = text,
                isPipMode = isPipMode,
                fontSizeSp = ui.subtitleFontSizeSp.floatValue,
                fillColor = ui.subtitleTextColor.value,
                isBold = ui.subtitleBold.value,
            )

            if (isInteractingWithSub) {
                val badgeText = when (gestures.mode.value) {
                    GestureMode.SUBTITLE_HORIZONTAL_SYNC ->
                        "↔ Sync Offset: %+.2fs".format(ui.subtitleOffsetSec.floatValue)
                    GestureMode.SUBTITLE_PINCH_SCALE ->
                        "🔍 Size: ${ui.subtitleFontSizeSp.floatValue.roundToInt()}sp"
                    else ->
                        "↕ Anchor: ${(gestures.subPosFraction.floatValue * 100).roundToInt()}%"
                }
                Text(
                    text = badgeText,
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = TextStyle(
                        color = OpticalGlassTokens.CyanAccent,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/**
 * High-Performance 2-Pass Hardware Stroke Subtitle Renderer (§9.1).
 * Converts 4.dp (or 2.dp in PiP) via LocalDensity to guarantee an honest 2.0dp outer border
 * on 3.0x–3.5x high-DPI screens with 75% fewer layout passes than legacy 8-offset shadows.
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
    val strokeWidthPx = with(density) { (if (isPipMode) 2.dp else 4.dp).toPx() }
    val sizeSp = (if (isPipMode) fontSizeSp * 0.62f else fontSizeSp).sp
    val lineSp = (if (isPipMode) fontSizeSp * 0.78f else fontSizeSp * 1.28f).sp
    val weight = if (isBold) FontWeight.Bold else FontWeight.Medium

    Box(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Pass 1: Density-scaled outline stroke pass (Round join eliminates corner spikes)
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
            maxLines = 3,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
        // Pass 2: Interior fill pass
        Text(
            text = text,
            style = TextStyle(
                color = fillColor,
                fontWeight = weight,
                fontSize = sizeSp,
                lineHeight = lineSp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 3,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
    }
}
