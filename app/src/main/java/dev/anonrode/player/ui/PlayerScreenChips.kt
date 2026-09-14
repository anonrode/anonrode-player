package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

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
 * ONLY active color in the chrome (the sheet's hardcoded MxGreen is gone
 * with this redesign, so a selected control matches the user's skin).
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
    val tint = when {
        !enabled -> Color.White.copy(alpha = 0.35f)
        selected -> accent
        else -> Color.White
    }
    val bg = if (selected) accent.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.35f)
    val border = if (selected) accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.20f)
    val cell = max(size, PlayerDimens.touchMin)
    Box(
        modifier = modifier
            .size(cell)
            .clip(CircleShape)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = tint),
                        enabled = enabled,
                        onClick = {
                            view.performHapticFeedback(haptic)
                            onClick()
                        },
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
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

/**
 * Rounded pill carrying TEXT instead of an icon: the speed and aspect chips
 * in the utility row, and the sync state chip's shell. The outer Box is
 * the touch cell (48dp tall, width wraps the label); the inner Box paints
 * the [PlayerDimens.pillH] pill. The visible text IS the accessible name
 * (unlike icon-only controls, no contentDescription needed).
 */
@Composable
internal fun TextPill(
    text: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Accent-colored pill (active value, e.g. a non-1.0× speed). */
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .height(PlayerDimens.touchMin)
            .clip(RoundedCornerShape(999.dp))
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = accent),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClick()
                        },
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = accent),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClick()
                        },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onLongClick()
                        },
                    )
                },
            )
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .height(PlayerDimens.pillH)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (selected) accent.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.35f)
                )
                .border(
                    1.dp,
                    if (selected) accent.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.20f),
                    RoundedCornerShape(999.dp),
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                color = if (selected) accent else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

internal enum class TimeSeekDirection { BACK, FORWARD }

/** 48×44 accent pill with the REAL configured step: "‹5" "30›". The v0.7.2
 *  audit caught the label hardcoded as "10" while Settings offers
 *  5/10/15/30 — the pill renders [seconds] now, so it can't lie. */
@Composable
internal fun TimeSeekButton(
    direction: TimeSeekDirection,
    seconds: Int,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .size(PlayerDimens.chipMd)
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 36.dp, color = accent),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = PlayerDimens.skipW, height = PlayerDimens.skipH)
                .clip(RoundedCornerShape(10.dp))
                .background(accent.copy(alpha = 0.22f))
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (direction == TimeSeekDirection.BACK) {
                    Text(
                        "‹",
                        color = accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                    )
                }
                Text(
                    seconds.coerceAtLeast(1).toString(),
                    color = accent,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                )
                if (direction == TimeSeekDirection.FORWARD) {
                    Text(
                        "›",
                        color = accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}

internal enum class EpisodeJumpDirection { PREVIOUS, NEXT }

/** Episode jump — 44dp visual in a 48dp cell; disabled state is visibly
 *  flat (ControlChip's disabled tint, no click response). */
@Composable
internal fun EpisodeJumpButton(
    direction: EpisodeJumpDirection,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ControlChip(
        icon = if (direction == EpisodeJumpDirection.PREVIOUS) {
            Icons.Filled.SkipPrevious
        } else {
            Icons.Filled.SkipNext
        },
        contentDescription = if (direction == EpisodeJumpDirection.PREVIOUS) {
            "Previous episode"
        } else {
            "Next episode"
        },
        accent = Color.White,
        onClick = onClick,
        modifier = modifier,
        size = 44.dp,
        enabled = enabled,
        haptic = HapticFeedbackConstants.VIRTUAL_KEY,
    )
}

/** 64dp play/pause — the only oversized control in the dock, so the thumb
 *  finds it first. Animated icon flip on the isPlaying toggle. */
@Composable
internal fun BigPlayPauseButton(
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .size(PlayerDimens.playBig)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 40.dp, color = accent),
                onClick = {
                    // The old transport row wrapped this call in a
                    // VIRTUAL_KEY blip; the primitive owns it now so no
                    // caller can forget it.
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
            ),
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
                modifier = Modifier.size(PlayerDimens.glyphPlay),
            )
        }
    }
}
