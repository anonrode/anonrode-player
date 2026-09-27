package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs

/* ── Status strip — v0.9 "Single-Plane Chrome" ─────────────────────────────
 * A thin READ-OUT line that sits above the seek row: sync state, playback
 * speed (only when it differs from 1.0×) and remaining time.
 *
 * Why this exists: sub-sync is the app's flagship feature and v0.7.3 buried
 * it as one icon among six in the utility dock, with its state only readable
 * inside the label of a hero chip that competes with five neighbours. The
 * 09-19 device log is the owner repeatedly hunting for whether sync actually
 * worked. A status line answers that question before it is asked — and it
 * costs one 26dp row, not a dock.
 *
 * This is deliberately NOT buttons. Every action lives in the rail / tray /
 * Control Centre; this strip only reports and routes (the sync pill opens
 * the sync tray, exactly what the old hero chip's tap did).
 * ------------------------------------------------------------------------- */

/** Height of one status pill — deliberately slimmer than [PlayerDimens.pillH]. */
private val StatusPillH = 26.dp

@UnstableApi
@Composable
internal fun StatusStrip(
    accent: Color,
    syncEnabled: Boolean,
    syncRunning: Boolean,
    offsetMs: Long,
    speed: Float,
    positionSec: Float,
    durationSec: Float,
    onTapSync: () -> Unit,
    onResync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = PlayerDimens.gapXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PlayerDimens.gapXs),
    ) {
        StatusSyncPill(
            accent = accent,
            enabled = syncEnabled,
            running = syncRunning,
            offsetMs = offsetMs,
            onClick = onTapSync,
            onResync = onResync,
        )

        // Speed only earns space when it differs from normal — a permanent
        // "1.0×" is noise the user has to parse every time the chrome opens.
        if (abs(speed - 1f) >= 0.05f) {
            StatusTextPill(text = speedLabel(speed), accent = accent)
        }

        Spacer(Modifier.weight(1f))

        val remaining = (durationSec - positionSec).coerceAtLeast(0f)
        if (durationSec > 0f) {
            StatusTextPill(
                text = "−" + fmtClock((remaining * 1000).toLong()),
                accent = accent,
                emphasized = false,
            )
        }
    }
}
/**
 * The sync read-out — the one surface that states what the subtitle-sync
 * engine is doing, so the answer is legible without opening anything.
 * Four states derived from [enabled], [running] and [offsetMs] alone:
 *
 *   OFF      → grey dot,      "Sync off"
 *   working  → spinning ring, "Syncing…"
 *   armed    → accent dot,    "Sync armed"
 *   locked   → green dot,     "Synced +0.06s" (spring-bounces on lock)
 *
 * Interactions:
 *   tap         → open sync popover (nudge, settings, presets)
 *   long-press  → "Resync now" (immediate fresh calibration)
 */
@UnstableApi
@Composable
private fun StatusSyncPill(
    accent: Color,
    enabled: Boolean,
    running: Boolean,
    offsetMs: Long,
    onClick: () -> Unit,
    onResync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val locked = enabled && offsetMs != 0L
    val dotColor = when {
        !enabled -> Color.White.copy(alpha = 0.28f)
        running -> Color(0xFFFFC247)
        locked -> Color(0xFF49D6A0)
        else -> accent
    }
    val label = when {
        !enabled -> "Sync off"
        running -> "Syncing…"
        locked -> "Synced " + offsetText(offsetMs)
        else -> "Sync armed"
    }
    val contentColor = when {
        !enabled -> Color.White.copy(alpha = 0.55f)
        locked -> Color(0xFF49D6A0)
        else -> if (running) Color(0xFFFFC247) else accent
    }

    // Spinning ring while working: 360° rotation per 1.6s while engine runs
    val ringRotation by animateFloatAsState(
        targetValue = if (running) 360f else 0f,
        animationSpec = tween(
            durationMillis = if (running) 1600 else 220,
            easing = LinearEasing,
        ),
        label = "statusSyncRing",
    )

    // Lock pulse: when offset value changes, pop the chip 0.88 -> 1.0 with a spring
    val pulseKey = (offsetMs / 100).toInt()
    val scaleAnim = remember(pulseKey) { Animatable(if (locked) 0.88f else 1f) }
    LaunchedEffect(pulseKey) {
        if (locked) {
            scaleAnim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 800f))
        }
    }

    Row(
        modifier = modifier
            .height(StatusPillH)
            .graphicsLayer {
                scaleX = scaleAnim.value
                scaleY = scaleAnim.value
            }
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.46f))
            .border(
                1.dp,
                if (enabled) contentColor.copy(alpha = 0.38f)
                else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(999.dp),
            )
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = accent),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onResync()
                },
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (running) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = "Syncing…",
                tint = dotColor,
                modifier = Modifier
                    .size(13.dp)
                    .rotate(ringRotation),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Text(
            text = label,
            color = contentColor,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Remaining-time / plain read-out pill — no click surface, purely informative. */
@UnstableApi
@Composable
private fun StatusTextPill(
    text: String,
    accent: Color,
    emphasized: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = if (emphasized) accent else Color.White.copy(alpha = 0.66f),
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = modifier
            .height(StatusPillH)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.46f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp)
            .widthIn(min = 44.dp),
    )
}

/** "+1.2s" / "−0.3s" with a real minus glyph — matches the hero chip's format. */
private fun offsetText(ms: Long): String {
    val s = ms / 1000f
    return (if (s >= 0f) "+" else "−") + "%.1fs".format(abs(s))
}

/** mm:ss (or h:mm:ss past an hour) — self-contained so this file has no
 *  dependency on the bottom bar's private formatter. */
private fun fmtClock(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

