package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The subtitle-sync HERO chip (v0.7.3 chrome redesign) — the single home
 * for the flagship feature. It replaces, in one labeled pill:
 *
 *   - the 56dp icon-only toggle in the old transport row,
 *   - the 48dp icon-only clone in the right rail,
 *   - the top-left SYNCED chip and the "Listening for the speech track…"
 *     calibration banner — both were hand-positioned at the SAME magic
 *     offsets (top=70dp, start=14dp) and could render on top of each
 *     other; their entire job is now this chip's state text.
 *
 * States (the label is the whole point — the v0.7.2 device complaint was
 * "impossible to find the subsync", which icon-only chrome can't fix):
 *
 *   OFF      → "Sync"        white pill, tap enables
 *   working  → "Syncing…"    accent, ring icon spins (live correlation or
 *                             fingerprint pass running — host honesty from
 *                             v0.7.2: the signal only lit while the engine
 *                             is really working)
 *   armed    → "Sync"        accent (enabled, no lock yet)
 *   locked   → "Synced +1.2s" accent, spring-pulses when the offset value
 *                             changes so a lock that lands mid-watch is
 *                             seen, not just read
 *
 * Interactions:
 *   tap         OFF → enable; ON → open the sync popover (nudge / re-sync /
 *                 style / turn off)
 *   long-press  "Resync now" (force a fresh fit, works from any state)
 *
 * [compact] drops the word prefixes ("Syncing…"/"Synced +1.2s" → "…" and
 * "+1.2s") for narrow portrait widths — the utility row passes it when its
 * measured width can't fit the long form. The chip NEVER reads or writes
 * DataStore: it renders the host-flowed state and routes user intents
 * through the callbacks.
 */
@Composable
internal fun PlayerSubSyncToggle(
    enabled: Boolean,
    running: Boolean,
    offsetMs: Long,
    accent: Color,
    onEnable: () -> Unit,
    onOpenPopover: () -> Unit,
    onResync: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val view = LocalView.current
    val locked = offsetMs != 0L
    val active = enabled && (running || locked)

    val label = when {
        enabled && running -> if (compact) "…" else "Syncing…"
        enabled && locked ->
            if (compact) offsetText(offsetMs) else "Synced " + offsetText(offsetMs)
        else -> "Sync"
    }

    // Spinning ring while working: one 360° rotation per 1.6s while the
    // engine runs; snaps back to 0 when it stops so the icon never sits
    // half-rotated.
    val ringRotation by animateFloatAsState(
        targetValue = if (running) 360f else 0f,
        animationSpec = tween(
            durationMillis = if (running) 1600 else 220,
            easing = LinearEasing,
        ),
        label = "subSyncRing",
    )
    // Lock pulse: when the offset value changes (a fresh lock landed, or a
    // nudge applied), pop the chip 0.85 → 1.0 with a medium-bouncy spring.
    // Same effect the retired top-left SYNCED chip carried.
    val pulseKey = (offsetMs / 100).toInt()
    val scaleAnim = remember(pulseKey) { Animatable(0.85f) }
    LaunchedEffect(pulseKey) {
        // Medium-bouncy spring: damping 0.5, stiffness 800 — the values
        // behind the retired chip's Spring.DampingRatioMediumBouncy /
        // Spring.StiffnessMedium (framework constants, API 21+), inlined
        // so this file needs no animation-core constant imports.
        scaleAnim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 800f))
    }

    val tint = if (active || enabled) accent else Color.White.copy(alpha = 0.7f)
    Box(
        modifier = modifier
            .height(PlayerDimens.touchMin)
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
            }
            .clip(RoundedCornerShape(999.dp))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = accent),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (enabled) onOpenPopover() else onEnable()
                },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onResync()
                },
            )
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(PlayerDimens.pillH)
                .clip(RoundedCornerShape(999.dp))
                .background(
                    if (enabled) accent.copy(alpha = 0.20f)
                    else Color.Black.copy(alpha = 0.35f)
                )
                .border(
                    1.dp,
                    if (enabled) accent.copy(alpha = 0.65f)
                    else Color.White.copy(alpha = 0.20f),
                    RoundedCornerShape(999.dp),
                )
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (running) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = accent.copy(alpha = 0.45f),
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(ringRotation),
                    )
                }
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = when {
                        enabled && locked -> "Subtitles auto-synced, offset " +
                            offsetText(offsetMs) + ". Tap for sync tools."
                        running -> "Subtitle auto-sync is working"
                        enabled -> "Subtitles auto-sync armed. Tap for sync tools."
                        else -> "Subtitles auto-sync off. Tap to turn on."
                    },
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                // The shrinkable leaf: when the host's Row constrained this
                // chip (weight fill=false), the icon stays and ONLY the
                // label ellipsizes — the offset remains one long-press
                // (Resync now) / popover tap away from being read exactly.
                modifier = Modifier.weight(1f, fill = false),
                text = label,
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "+1.2s" / "−0.3s" with a real minus glyph. */
private fun offsetText(ms: Long): String {
    val s = ms / 1000f
    return (if (s >= 0f) "+" else "−") + "%.1fs".format(kotlin.math.abs(s))
}
