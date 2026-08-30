package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi

/* ── Bottom chrome of the player overlay (post-redesign) ─────────────────
 *
 *   ┌──────────────────────────────────────────────────────────┐
 *   │  seekbar + timestamps  ──○──────────  12:34 / 45:21      │  ALWAYS visible
 *   ├──────────────────────────────────────────────────────────┤
 *   │  transport row  🔒  ⏪10  ⏮  ▶(BIG)  ⏭  ⏩10              │  auto-hides
 *   └──────────────────────────────────────────────────────────┘
 *
 * Sizing (per the user-approved layout):
 *   seek-bar time labels      14sp, white @ 75%
 *   seek-bar thumb            32dp tap target (Material value-change)
 *   transport gaps            12dp between every pair
 *   lock                      40dp (smaller — thumb finds the big play first)
 *   ⏪10 / ⏮ / ⏭ / ⏩10        48dp each
 *   BIG play                  72dp (50% bigger than siblings)
 *
 * PiP is intentionally NOT here — it lives in the overflow sheet (see
 * PlayerScreenOverflowSheet.kt). The transport row already has six
 * icons; adding a seventh would crowd it back into the cramped state
 * the redesign was meant to fix. The rail already has five icons, so
 * PiP also doesn't fit there.
 *
 * The seek bar is ALWAYS visible, even while the chrome is hidden —
 * matches v0.6.1's behaviour and the user's earlier feedback that the
 * seek bar should never disappear.
 *
 * Hosts of this composable align it `BottomCenter` inside their `Box`.
 * The `Spacer(8.dp)` at the bottom is the contract with overlays like
 * Up Next / SYNC popover whose `padding(bottom = …)` was tuned to sit
 * just above this block.
 * ------------------------------------------------------------------------- */

@UnstableApi
@Composable
internal fun PlayerScreenBottomBar(
    visible: Boolean,
    modifier: Modifier = Modifier,
    accent: Color,
    currentPositionMs: Long,
    positionSec: Float,
    durationSec: Float,
    localSeek: MutableFloatState,
    isPlaying: Boolean,
    locked: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onLockToggle: () -> Unit,
    actions: PlayerScreenActions,
    // v0.6.2 sub-sync UX pass: right-side cluster params.
    subSyncEnabled: Boolean,
    subSyncRunning: Boolean,
    onSetSubSyncEnabled: (Boolean) -> Unit,
    onResyncNow: () -> Unit,
    rotationLocked: Boolean,
    onCycleRotation: () -> Unit,
    onEnterPip: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Gradient scrim — same look as v0.6.1.
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                )
            )
            // 16dp horizontal / 8dp vertical — 14dp was off-grid.
            .padding(horizontal = PlayerDimens.gapLg, vertical = PlayerDimens.gapSm)
    ) {
        // ── 1) Seekbar row — ALWAYS visible (no AnimatedVisibility gate) ──
        SeekBarRow(
            accent = accent,
            currentPositionMs = currentPositionMs,
            positionSec = positionSec,
            durationSec = durationSec,
            localSeek = localSeek,
            onSeekCommitted = { sec ->
                actions.livePlayer.seekTo((sec * 1000).toLong())
            },
        )
        Spacer(Modifier.height(PlayerDimens.gapSm))

        // ── 2) Transport row — auto-hides with the chrome ──
        // Only fades; does not collapse space (that would jump the seek
        // bar up/down when the chrome hides.
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(220)),
        ) {
            TransportRow(
                accent = accent,
                isPlaying = isPlaying,
                locked = locked,
                hasPreviousEpisode = hasPreviousEpisode,
                hasNextEpisode = hasNextEpisode,
                onPlayPrevious = onPlayPrevious,
                onPlayNext = onPlayNext,
                onPlayPause = {
                    actions.view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    actions.togglePlayPause()
                },
                onSeekBack = {
                    actions.view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    actions.seekBy(-seekIncrementSec)
                },
                onSeekForward = {
                    actions.view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    actions.seekBy(seekIncrementSec)
                },
                onLockToggle = onLockToggle,
                // v0.6.2 right-side cluster: PiP / sub-sync / rotate.
                // The lock button stays at the FAR LEFT of the row; the
                // right cluster fills the right end so the BIG play
                // stays visually centred.
                subSyncEnabled = subSyncEnabled,
                subSyncRunning = subSyncRunning,
                onSetSubSyncEnabled = onSetSubSyncEnabled,
                onResyncNow = onResyncNow,
                rotationLocked = rotationLocked,
                onCycleRotation = onCycleRotation,
                onEnterPip = onEnterPip,
            )
        }

        // Bottom breathing room so the block never kisses the system
        // gesture / nav-bar inset — matches v0.6.1's 8dp.
        Spacer(Modifier.height(PlayerDimens.gapSm))
    }
}

/* ── Seek-bar row — 14sp timestamps, 32dp thumb, drag-to-seek ─────────────
 *
 * Tap-to-toggle: tapping the LEFT label flips between "current" and
 * "−remaining" (e.g. 12:34 / −32:47). Tapping the RIGHT label flips
 * between "remaining" and "total". NextPlayer / VLC / NewPipe all do
 * this. Two remembered booleans hold the user's per-side preference
 * across recompositions.
 */

@UnstableApi
@Composable
private fun SeekBarRow(
    accent: Color,
    currentPositionMs: Long,
    positionSec: Float,
    durationSec: Float,
    localSeek: MutableFloatState,
    onSeekCommitted: (Float) -> Unit,
) {
    var showRemainingOnLeft by remember { mutableStateOf(false) }
    var showCurrentOnRight by remember { mutableStateOf(false) }
    val remainingSec = (durationSec - positionSec).coerceAtLeast(0f)
    val leftLabel = if (showRemainingOnLeft) "−${fmtTime(remainingSec.toLong())}" else fmtTime(currentPositionMs)
    val rightLabel = if (showCurrentOnRight) fmtTime(currentPositionMs) else fmtTime(remainingSec.toLong())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            leftLabel,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .widthIn(min = 52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 32.dp, color = accent),
                ) { showRemainingOnLeft = !showRemainingOnLeft }
        )
        // Animate the visible thumb position between the host's 10Hz tick
        // so the slider doesn't strobe.
        val visualPos by animateFloatAsState(
            targetValue = if (localSeek.floatValue >= 0f) localSeek.floatValue
            else positionSec,
            animationSpec = tween(durationMillis = 100, easing = LinearEasing),
            label = "seekbar",
        )
        Slider(
            value = visualPos.coerceIn(0f, durationSec.coerceAtLeast(1f)),
            onValueChange = { localSeek.floatValue = it },
            onValueChangeFinished = {
                if (localSeek.floatValue >= 0f) {
                    onSeekCommitted(localSeek.floatValue)
                }
                localSeek.floatValue = -1f
            },
            valueRange = 0f..durationSec.coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .heightIn(min = 32.dp)
        )
        Text(
            rightLabel,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = 52.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 32.dp, color = accent),
                ) { showCurrentOnRight = !showCurrentOnRight }
        )
    }
}

/* ── Transport row — 6 icons, centred, BIG play ─────────────────────────── */

@Composable
private fun TransportRow(
    accent: Color,
    isPlaying: Boolean,
    locked: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onLockToggle: () -> Unit,
    subSyncEnabled: Boolean,
    subSyncRunning: Boolean,
    onSetSubSyncEnabled: (Boolean) -> Unit,
    onResyncNow: () -> Unit,
    rotationLocked: Boolean,
    onCycleRotation: () -> Unit,
    onEnterPip: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left cluster: lock + transport
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 1) Lock — 40dp, far left of transport
            LockToggleButton(locked = locked, accent = accent, onClick = onLockToggle)
            Spacer(Modifier.width(PlayerDimens.gapMd))

            // 2) ⏪10 — 48dp squared pill
            TimeSeekButton(
                direction = TimeSeekDirection.BACK,
                accent = accent,
                onClick = onSeekBack,
            )
            Spacer(Modifier.width(PlayerDimens.gapMd))

            // 3) ⏮ episode — 48dp round
            EpisodeJumpButton(
                direction = EpisodeJumpDirection.PREVIOUS,
                enabled = hasPreviousEpisode,
                onClick = onPlayPrevious,
            )
            Spacer(Modifier.width(PlayerDimens.gapMd))

            // 4) BIG play — 72dp (50% bigger than siblings), accent ripple
            BigPlayPauseButton(
                isPlaying = isPlaying,
                accent = accent,
                onClick = onPlayPause,
            )
            Spacer(Modifier.width(PlayerDimens.gapMd))

            // 5) ⏭ episode — 48dp round
            EpisodeJumpButton(
                direction = EpisodeJumpDirection.NEXT,
                enabled = hasNextEpisode,
                onClick = onPlayNext,
            )
            Spacer(Modifier.width(PlayerDimens.gapMd))

            // 6) ⏩10 — 48dp squared pill
            TimeSeekButton(
                direction = TimeSeekDirection.FORWARD,
                accent = accent,
                onClick = onSeekForward,
            )
        }
        // Right cluster: PiP (48dp) · sub-sync (56dp) · rotate (48dp).
        // 8dp gaps keep this cluster visually tighter than the left so the
        // sync toggle's 56dp ring doesn't crowd the 48dp siblings.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 7) PiP — 48dp ghost (per spec)
            GhostChip(
                size = PlayerDimens.chipMd,
                icon = Icons.Filled.PictureInPictureAlt,
                contentDescription = "Picture-in-picture",
                tint = Color.White,
                onClick = onEnterPip,
            )
            Spacer(Modifier.width(PlayerDimens.gapSm))
            // 8) Sub-sync — 56dp (bigger for the spinning ring)
            PlayerSubSyncToggle(
                enabled = subSyncEnabled,
                running = subSyncRunning,
                accent = accent,
                onSetEnabled = { onSetSubSyncEnabled(it) },
                onResync = onResyncNow,
            )
            Spacer(Modifier.width(PlayerDimens.gapSm))
            // 9) Rotate — 48dp, cycles sensor → landscape → portrait
            GhostChip(
                size = PlayerDimens.chipMd,
                icon = if (rotationLocked) Icons.Filled.ScreenLockRotation
                else Icons.Filled.ScreenRotation,
                contentDescription = if (rotationLocked) "Rotation locked" else "Rotation auto",
                tint = if (rotationLocked) accent else Color.White,
                onClick = onCycleRotation,
            )
        }
    }
}

/** 40/48dp ghost circle button (per-spec size override) — used for
 *  the right-cluster PiP / rotate icons in the v0.6.2 sub-sync UX pass. */
@Composable
private fun GhostChip(
    size: androidx.compose.ui.unit.Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.35f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.20f),
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = tint),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/* ── Dock-button visuals ───────────────────────────────────────────────── */

internal enum class TimeSeekDirection { BACK, FORWARD }
internal enum class EpisodeJumpDirection { PREVIOUS, NEXT }

/** 40dp lock toggle — sits at the far left of the transport row. Filled
 *  black + accent border when locked (the active state), hollow outline
 *  when unlocked. */
@Composable
internal fun LockToggleButton(
    locked: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (locked) accent.copy(alpha = 0.22f)
                else Color.Black.copy(alpha = 0.35f)
            )
            .border(
                width = 1.dp,
                color = if (locked) accent
                else Color.White.copy(alpha = 0.40f),
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true,
                    color = if (locked) accent else Color.White),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = if (locked) "Locked" else "Lock controls",
            tint = if (locked) accent else Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 64dp play/pause — bigger than its siblings, accent ripple so the
 *  thumb finds it instantly. Sized down from the original 72dp per the
 *  v0.7 design research (5-player survey): 72dp crowds on 720p budget
 *  screens, 64dp is the survey median (between mpvKt's 72dp and
 *  NewPipe's 60dp) and keeps the side icons from feeling cramped.
 *  Animated icon flip on isPlaying toggle. */
@Composable
internal fun BigPlayPauseButton(
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 40.dp, color = accent),
                onClick = onClick,
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
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/** 48dp squared pill with the "10" label INSIDE the icon (no chevron +
 *  text). The label is the universal pattern across VLC / ReVanced /
 *  NextPlayer and is the cheapest way to make skip-10 discoverable.
 *  Accent-tinted pill, white text. */
@Composable
internal fun TimeSeekButton(
    direction: TimeSeekDirection,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.22f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = accent),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // "10" inside the pill, big and bold, with a small chevron hint
        // to convey direction without taking extra width.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            if (direction == TimeSeekDirection.BACK) {
                Text(
                    text = "‹",
                    color = accent,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                )
            }
            Text(
                text = "10",
                color = accent,
                fontWeight = FontWeight.Black,
                fontSize = 14.sp,
            )
            if (direction == TimeSeekDirection.FORWARD) {
                Text(
                    text = "›",
                    color = accent,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** 48dp round episode jump button. Disabled-tint when no neighbour. */
@Composable
internal fun EpisodeJumpButton(
    direction: EpisodeJumpDirection,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Color.White),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (direction == EpisodeJumpDirection.PREVIOUS) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous episode",
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next episode",
                tint = tint,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}