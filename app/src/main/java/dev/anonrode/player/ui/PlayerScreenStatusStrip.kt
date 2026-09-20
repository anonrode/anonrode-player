package dev.anonrode.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * The sync read-out. State machine mirrors [PlayerSubSyncToggle] so the two
 * can never disagree about what the engine is doing:
 *
 *   OFF      → grey dot,   "Sync off"
 *   working  → amber dot,  "Syncing…"
 *   armed    → accent dot, "Sync armed"
 *   locked   → green dot,  "Synced +0.06s"
 *
 * A lock is signalled by a non-zero offset — the same rule the hero chip
 * uses ([liveOffsetMs] moves off 0 when a lock lands, back to 0 on clear).
 */
@UnstableApi
@Composable
private fun StatusSyncPill(
    accent: Color,
    enabled: Boolean,
    running: Boolean,
    offsetMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locked = enabled && !running && offsetMs != 0L
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

    Row(
        modifier = modifier
            .height(StatusPillH)
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.46f))
            .border(
                1.dp,
                if (enabled) contentColor.copy(alpha = 0.38f)
                else Color.White.copy(alpha = 0.12f),
                RoundedCornerShape(999.dp),
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = accent),
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The state dot — the whole point of the strip. Legible before the
        // label is read, distinguishable from a metre away.
        Box(
            modifier = Modifier
                .height(7.dp)
                .widthIn(min = 7.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
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

