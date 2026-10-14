package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/* ── Shared control-chip vocabulary (v0.7.3 chrome redesign) ──────────────
 * ONE primitive for every circular icon control in the player: top bar
 * (PiP / lock / more), transport row, utility row. Before this round the
 * same circle was re-implemented four times (GhostChip in the bottom bar,
 * RailIcon in the rail, an inline Box in the top bar, LockToggleButton
 * with its own alpha set) and they had already drifted apart in fill,
 * border and ripple colors.
 *
 * Touch discipline (Material: 48dp minimum on Android): the clickable cell
 * is ALWAYS ≥48dp; the painted circle may be smaller (chipVisual 40dp) so
 * dense rows don't crowd the frame. Callers pass the VISUAL size; the cell
 * grows itself.
 * ------------------------------------------------------------------------- */

/**
 * Circular icon control. [size] is the painted circle; the touch cell is
 * `max(size, 48dp)`. [selected] paints the accent state — accent is the
 * ONLY active color in the chrome (a selected control matches the user's skin).
 */
@Composable
internal fun ControlChip(
    icon: ImageVector,
    contentDescription: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = PlayerDimens.chipVisual,
    selected: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    /** Haptic kind on tap; VIRTUAL_KEY for transport-ish primaries. */
    haptic: Int = HapticFeedbackConstants.KEYBOARD_TAP,
) {
    val view = LocalView.current
    // Press feel: the painted circle springs down to 0.90 and settles back
    // with a slight overshoot. The scale lives on the VISUAL only — the
    // clickable cell keeps its full ≥48dp hit area for the whole gesture.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "chipPress",
    )
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        selected -> accent
        else -> Color.White
    }
    val bg = if (selected) accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.08f)
    val border = if (selected) accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.15f)
    val cell = size.coerceAtLeast(PlayerDimens.touchMin)
    Box(
        modifier = modifier
            .size(cell)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true, color = tint),
                        enabled = enabled,
                        onClick = {
                            view.performHapticFeedback(haptic)
                            onClick()
                        },
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true, color = tint),
                        enabled = enabled,
                        onClick = {
                            view.performHapticFeedback(haptic)
                            onClick()
                        },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onLongClick()
                        },
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(PlayerDimens.glyphMd),
            )
        }
    }
}

/** 64dp play/pause — the only oversized control in the dock, so the thumb
 *  finds it first. Animated icon flip on the isPlaying toggle, and a deep
 *  springy press (the primary control gets the deepest travel). */
@Composable
internal fun BigPlayPauseButton(
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val cinemaGradient = Brush.verticalGradient(
        listOf(
            Color(0xFF2A3647),
            Color(0xFF1A2330),
            Color(0xFF111722),
        )
    )
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = 0.5f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "playPress",
    )
    Box(
        modifier = modifier
            .size(PlayerDimens.playBig)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 40.dp, color = accent),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Visual layer only — the clickable cell stays at the full 64dp for
        // the entire gesture so the thumb never loses the button mid-press.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(cinemaGradient)
                .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = isPlaying,
                transitionSpec = {
                    (slideInVertically { it } + fadeIn()) togetherWith
                        (slideOutVertically { -it } + fadeOut())
                },
                label = "playPause",
            ) { playing ->
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier
                        .size(PlayerDimens.glyphPlay)
                        .then(if (!playing) Modifier.padding(start = 2.dp) else Modifier),
                )
            }
        }
    }
}
