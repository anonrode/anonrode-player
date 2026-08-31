package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/* ── Right-edge vertical action rail ──────────────────────────────────────
 * Borrowed from the modern short-video app pattern (YouTube Shorts / IG
 * Reels / TikTok): always-visible column of 48dp icons pinned to the
 * right edge of the video surface so the user's right thumb doesn't have
 * to chase them. The transport row underneath gets to breathe.
 *
 * Vertical order (top → bottom):
 *   1. CC          — subtitle toggle. Hidden entirely if there is no
 *                    subtitle for the current media (see [hasSubtitle]).
 *   2. audio       — MusicNote — opens the audio-track picker.
 *   3. sync        — slot composable the subtitle agent fills (with the
 *                    spinning ring when running). Slot is reserved
 *                    regardless so the rail position is stable.
 *   4. rotate      — [PlayerScreenRotateButton] — 3-state cycle + long-
 *                    press menu. 48dp like its siblings.
 *   5. more        — opens the [PlayerOverflowSheet]; same destination
 *                    as the top-bar "more" (shared state).
 *
 * Sizing (v0.6.2 polish — was cramped at 56dp / 22dp glyph):
 *   rail width      64dp pill (was 56dp — gives 8dp breathing room
 *                   on each side of the 48dp circles, matches the rail's
 *                   outer padding visually)
 *   inner padding   8dp on every side (was 4dp — feels less crowded
 *                   while still keeping the rail compact)
 *   icon size       48dp circle + 22dp icon glyph (matches the rest of
 *                   the player; Material icon guidance for the 48dp tap)
 *   gap             12dp between icons (vertical pitch 60dp)
 *   background      black @ 38% alpha, 1dp white @ 14% border, 28dp corners
 *
 * The rail does NOT participate in the controls-auto-hide cycle — the
 * rail stays visible whenever the chrome is visible (i.e. when the user
 * tapped to show it), so the subtitle toggle and sync button are one tap
 * away regardless of auto-hide. The host controls visibility with the
 * rest of the chrome.
 * ------------------------------------------------------------------------- */

/** Slot the subtitle agent (Agent 5) fills. Receives the standard 48dp
 *  circle footprint (Modifier already applied) plus the accent color. The
 *  agent decides what glyph to draw (sync icon, spinning ring, etc.). */
internal typealias SyncSlot = @Composable (modifier: Modifier, accent: Color) -> Unit

@Composable
internal fun PlayerScreenActionRail(
    accent: Color,
    showCC: Boolean,
    hasSubtitle: Boolean,
    rotateMode: RotateMode,
    onCycleRotate: () -> Unit,
    onSetRotate: (RotateMode) -> Unit,
    onSubtitleToggle: () -> Unit,
    onAudioTrack: () -> Unit,
    onMore: () -> Unit,
    syncSlot: SyncSlot,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current

    Box(
        modifier = modifier
            // Outer pill — frosted, never opaque so the video underneath
            // bleeds through enough that the rail doesn't feel like it's
            // "fighting" the picture.
            .clip(RoundedCornerShape(28.dp))
            .background(Color.Black.copy(alpha = 0.38f))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.14f),
                shape = RoundedCornerShape(28.dp),
            )
            // Inner padding — 8dp on every side so the 48dp circles sit
            // flush inside a 64dp-wide pill (8+48+8). Previously 4dp —
            // the sync slot's 48dp circle ended up hugging the pill edge
            // while the other icons had the same inner margin, so it
            // looked asymmetric.
            .padding(PaddingValues(PlayerDimens.gapSm)),
    ) {
        Column(
            // 12dp gap between icons — matches the YouTube Shorts visual
            // rhythm; tight enough to keep the rail compact, loose enough
            // for fat-finger taps.
            verticalArrangement = Arrangement.spacedBy(PlayerDimens.gapMd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1) CC — subtitle toggle. Only render if a subtitle is
            // available; without one the button is dead weight.
            if (hasSubtitle) {
                RailIcon(
                    icon = Icons.Filled.ClosedCaption,
                    contentDescription = if (showCC) "Subtitles on"
                    else "Subtitles off",
                    tint = if (showCC) accent else Color.White,
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSubtitleToggle()
                    },
                )
            }

            // 2) audio — open the audio-track picker sheet on the host.
            RailIcon(
                icon = Icons.Filled.MusicNote,
                contentDescription = "Audio track",
                tint = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onAudioTrack()
                },
            )

            // 3) sync — slot for Agent 5. We render the agent's slot
            // composable inside our 48dp wrapper so the rail visuals
            // (size / ripple / padding) stay consistent.
            syncSlot(
                Modifier
                    .size(PlayerDimens.chipMd)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.20f),
                        shape = CircleShape,
                    ),
                accent,
            )

            // 4) rotate — reuses PlayerScreenRotateButton (which already
            // implements the 48dp circle + 3-state cycle + long-press
            // menu). No extra wrapping needed.
            PlayerScreenRotateButton(
                mode = rotateMode,
                accent = accent,
                onCycle = onCycleRotate,
                onSetMode = onSetRotate,
            )

            // 5) more — overflow sheet. Same destination as the top-bar
            // "more"; the host passes the same toggle callback.
            RailIcon(
                icon = Icons.Filled.MoreVert,
                contentDescription = "More options",
                tint = Color.White,
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onMore()
                },
            )
        }
    }
}

/** Single 48dp circle icon used inside the rail. Centralised here so the
 *  CC / audio / more buttons all match the rail's look exactly. The sync
 *  slot is its own composable (Agent 5 decides the visual). */
@Composable
private fun RailIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(PlayerDimens.chipMd)
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
                onClick = onClick,
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