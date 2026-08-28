package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anonrode.player.PlayerPrefs
import dev.anonrode.player.audio.SubtitleStyle
import kotlin.math.roundToInt

/** 8-way offsets for the black subtitle outline. */
private val SubtitleOutlineOffsets = listOf(
    -2f to -2f, -2f to 0f, -2f to 2f,
    0f to -2f, 0f to 2f,
    2f to -2f, 2f to 0f, 2f to 2f,
)

/**
 * The subtitle cue: MX-outlined (bold + black outline, no box), centered
 * on a draggable stage position (persisted per video), long-press opens
 * the style dropdown. [modifier] carries the caller's BoxScope alignment.
 */
@Composable
internal fun PlayerSubtitleOverlay(
    modifier: Modifier = Modifier,
    text: String,
    style: SubtitleStyle,
    accent: Color,
    showCC: Boolean,
    gestures: GestureUiState,
    mediaId: String,
    onStyleChanged: (SubtitleStyle) -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    ((gestures.subX.floatValue * gestures.scrW.floatValue) - gestures.scrW.floatValue / 2f).roundToInt(),
                    ((gestures.subY.floatValue * gestures.scrH.floatValue) - gestures.scrH.floatValue / 2f).roundToInt(),
                )
            }
            .graphicsLayer {
                val s = if (gestures.subDragging.value) 1.08f else 1f
                scaleX = s
                scaleY = s
                alpha = if (gestures.subDragging.value) 0.9f else 1f
            }
            .pointerInput(showCC) {
                // Single long-press entry point (this used to be
                // two competing handlers: a pointerInput menu
                // opener plus a combinedClickable). A stationary
                // long-press opens the style dropdown; a
                // long-press that moves drags the cue anywhere on
                // the stage (persisted per video). Plain taps are
                // swallowed so they don't toggle the HUD.
                detectTapGestures(
                    onTap = { /* no-op: keep taps off the HUD toggle */ },
                    onLongPress = {
                        view.haptic(HapticFeedbackConstants.LONG_PRESS)
                        gestures.subStyleMenuOpen.value = true
                    },
                )
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        gestures.subDragging.value = true
                        gestures.subStyleMenuOpen.value = false
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        gestures.subX.floatValue = (gestures.subX.floatValue + amount.x / gestures.scrW.floatValue).coerceIn(SUB_X_MIN, SUB_X_MAX)
                        gestures.subY.floatValue = (gestures.subY.floatValue + amount.y / gestures.scrH.floatValue).coerceIn(SUB_Y_MIN, SUB_Y_MAX)
                    },
                    onDragEnd = {
                        gestures.subDragging.value = false
                        PlayerPrefs.saveSubtitlePosition(context, mediaId, gestures.subX.floatValue, gestures.subY.floatValue)
                    },
                    onDragCancel = { gestures.subDragging.value = false },
                )
            }
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        OutlinedSubtitleText(text, style = style)
        if (gestures.subStyleMenuOpen.value) {
            SubtitleStyleDropdown(
                style = style,
                onStyle = {
                    onStyleChanged(it)
                    gestures.subStyleMenuOpen.value = false
                },
                onReset = {
                    onStyleChanged(SubtitleStyle())
                    gestures.subX.floatValue = SUB_DEFAULT_X
                    gestures.subY.floatValue = SUB_DEFAULT_Y
                    gestures.subStyleMenuOpen.value = false
                    PlayerPrefs.saveSubtitlePosition(context, mediaId, gestures.subX.floatValue, gestures.subY.floatValue)
                },
                onDismiss = { gestures.subStyleMenuOpen.value = false },
                accent = accent,
            )
        }
    }
}

/**
 * MX subtitle look: bold white text with a black 8-way outline, no
 * background box, centered, at most two lines. Styled from the host-owned
 * [SubtitleStyle] (size + color; placement is drag-driven).
 */
@Composable
internal fun OutlinedSubtitleText(
    text: String,
    modifier: Modifier = Modifier,
    style: SubtitleStyle = SubtitleStyle(),
) {
    val sizeSp = style.size.fontSp
    val lineSp = (sizeSp.value * 1.5f).sp
    val fillColor = style.color.value
    val weight = if (style.bold) FontWeight.Bold else FontWeight.Medium
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        SubtitleOutlineOffsets.forEach { (dx, dy) ->
            Text(
                text,
                color = Color.Black,
                fontWeight = weight,
                fontSize = sizeSp,
                lineHeight = lineSp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(dx.dp, dy.dp),
            )
        }
        Text(
            text,
            color = fillColor,
            fontWeight = weight,
            fontSize = sizeSp,
            lineHeight = lineSp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
