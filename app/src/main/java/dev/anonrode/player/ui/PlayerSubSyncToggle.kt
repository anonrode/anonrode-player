package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width

/**
 * Sub-sync toggle composable (v0.6.2 sub-sync UX pass).
 *
 * Three visible states, mirrored from the user's UX spec:
 *   - OFF       : outlined icon, muted (white @ ~0.6 alpha)
 *   - ON-idle   : filled icon, accent color, no spinner
 *   - ON-running: filled icon, accent color, rotating ring (360° loop),
 *                 "SYNCING" status text under the icon
 *
 * Sizing: 56dp outer diameter (bigger than PiP/rotate for the spinner to
 * breathe) — the bottom-row layout grants this extra size to the sync
 * button specifically.
 *
 * Interactions:
 *   - tap        : toggle ON ↔ OFF (writes to DataStore through the
 *                  supplied callback; UI updates immediately because
 *                  `enabled` is bound to the live Flow snapshot).
 *   - long-press : fire the supplied `onResync` action ("Resync now");
 *                  always available, regardless of toggle state, so a
 *                  user can force a calibration without first flipping
 *                  the toggle ON.
 *
 * The DataStore write is the host's responsibility: the caller supplies a
 * `onSetEnabled: (Boolean) -> Unit` that persists the new value. The
 * composable never reads or writes DataStore directly — it only renders
 * the supplied [enabled] / [running] state.
 */
@Composable
internal fun PlayerSubSyncToggle(
    enabled: Boolean,
    running: Boolean,
    accent: Color,
    onSetEnabled: (Boolean) -> Unit,
    onResync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    // Spinning ring: 0 → 360 in 1.6s, linear, infinite. Only animates
    // while running; when the user pauses (toggle OFF or running=false),
    // the rotation snaps back to 0 so the icon doesn't appear half-rotated.
    val ringRotation by animateFloatAsState(
        targetValue = if (running) 360f else 0f,
        animationSpec = tween(
            durationMillis = if (running) 1600 else 220,
            easing = LinearEasing,
        ),
        label = "subSyncRing",
    )

    val tint = if (enabled) accent else Color.White.copy(alpha = 0.6f)
    val bg = if (enabled) accent.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.35f)
    val border = if (enabled) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.18f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(bg)
                .border(
                    width = if (running) 2.dp else 1.dp,
                    color = border,
                    shape = CircleShape,
                )
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true, radius = 36.dp, color = accent),
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onSetEnabled(!enabled)
                    },
                    onLongClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onResync()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // The "spinning ring" while running: a single icon rotated by
            // the animated value. We render it BEHIND the main icon by
            // stacking it first inside the same Box — the ring sits one
            // layer beneath so the user sees both the ring AND the icon.
            if (running) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.45f),
                    modifier = Modifier
                        .size(40.dp)
                        .rotate(ringRotation),
                )
            }
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = if (enabled) "Sub sync on" else "Sub sync off",
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
        // Status text under the icon: "SYNCING" only while running.
        // Empty string while idle (OFF or ON-idle) so the row stays compact
        // and the bottom bar's height doesn't bounce when running flips.
        Text(
            text = if (running) "SYNCING" else "",
            color = accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            modifier = Modifier
                .padding(top = 2.dp)
                .width(56.dp)
                .alpha(if (running) 1f else 0f)
        )
    }
}

/**
 * No-op stub kept for callers that build a preview or a placeholder
 * toggle. The real toggle always comes from [PlayerSubSyncToggle] above.
 */
@Suppress("unused")
@Composable
internal fun PlayerSubSyncTogglePlaceholder() {
    Text("SubSync", style = MaterialTheme.typography.labelSmall)
}