package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectDragGestures
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
 * The subtitle cue: high-contrast outlined (bold + black outline, no box), centered
 * on a draggable stage position (persisted per video and globally).
 * Position is percentage-based (10% to 85%), decoupled from chrome height.
 * [modifier] carries the caller's BoxScope alignment.
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
    bottomBarHeightPx: Float = 0f,
    onStyleChanged: (SubtitleStyle) -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    Box(
        modifier = modifier
            .offset {
                // Direct percentage-based Y position, decoupled from chrome height.
                // Horizontally locked to 0 for exact optical and mathematical screen centering.
                val effectiveY = gestures.subY.floatValue * gestures.scrH.floatValue
                IntOffset(
                    0,
                    (effectiveY - gestures.scrH.floatValue / 2f).roundToInt(),
                )
            }
            .graphicsLayer {
                val s = if (gestures.subDragging.value) 1.04f else 1f
                scaleX = s
                scaleY = s
                alpha = if (gestures.subDragging.value) 0.95f else 1f
            }
            .pointerInput(showCC) {
                detectDragGestures(
                    onDragStart = {
                        view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                        gestures.subDragging.value = true
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        gestures.subX.floatValue = SUB_DEFAULT_X
                        val scrH = gestures.scrH.floatValue.takeIf { it > 0f } ?: 1000f
                        gestures.subY.floatValue = (gestures.subY.floatValue + amount.y / scrH).coerceIn(SUB_Y_MIN, SUB_Y_MAX)
                    },
                    onDragEnd = {
                        gestures.subDragging.value = false
                        PlayerPrefs.saveSubtitlePosition(context, mediaId, SUB_DEFAULT_X, gestures.subY.floatValue)
                    },
                    onDragCancel = { gestures.subDragging.value = false },
                )
            }
            .padding(horizontal = 24.dp),
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
 * High-contrast subtitle look: crisp yellow text Color(0xFFFEF08A) on dark blur pill
 * Color.Black.copy(alpha = 0.85f), centered horizontally, at most three lines.
 */
@Composable
internal fun OutlinedSubtitleText(
    text: String,
    modifier: Modifier = Modifier,
    style: SubtitleStyle = SubtitleStyle(),
) {
    val sizeSp = style.size.fontSp
    val lineSp = (sizeSp.value * 1.4f).sp
    val subtitleYellow = Color(0xFFFEF08A)
    val fillColor = if (style.color == dev.anonrode.player.audio.SubtitleColor.WHITE ||
        style.color == dev.anonrode.player.audio.SubtitleColor.YELLOW) {
        subtitleYellow
    } else {
        style.color.value
    }
    val weight = if (style.bold) FontWeight.Bold else FontWeight.Medium
    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        SubtitleOutlineOffsets.forEach { (dx, dy) ->
            Text(
                text = text,
                color = Color.Black.copy(alpha = 0.85f),
                fontWeight = weight,
                fontSize = sizeSp,
                lineHeight = lineSp,
                textAlign = TextAlign.Center,
                maxLines = 3,
                softWrap = true,
                overflow = TextOverflow.Visible,
                modifier = Modifier.offset(dx.dp, dy.dp),
            )
        }
        Text(
            text = text,
            color = fillColor,
            fontWeight = weight,
            fontSize = sizeSp,
            lineHeight = lineSp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
    }
}
