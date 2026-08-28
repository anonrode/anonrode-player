package dev.anonrode.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anonrode.player.audio.SubtitleColor
import dev.anonrode.player.audio.SubtitlePosition
import dev.anonrode.player.audio.SubtitleSize
import dev.anonrode.player.audio.SubtitleStyle

/* ── Popup surfaces anchored over the player: the SYNCED chip, the sync
 * popover (-0.1 / +0.1 / RE-SYNC / STYLE grid) and the subtitle style
 * dropdown opened by long-pressing the cue.
 * ------------------------------------------------------------------------- */

/* ── SYNCED chip (top-left) ────────────────────────────────────────────────
 * Mirrors the mockup's glass chip. Click opens the sync popover.
 * ------------------------------------------------------------------------- */
@Composable
internal fun SyncedChip(
    offsetMs: Long,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = offsetMs / 1000f
    val label = "SYNCED " + (if (s >= 0) "+" else "") + "%.2fs".format(s)
    // Spring scale on the offset key — when a fresh lock lands the chip
    // briefly pops (~1.08) then settles to 1.0 with a tiny overshoot,
    // so the user sees "yes, something just happened" without any banner.
    val pulseKey = (offsetMs / 100).toInt()
    val scaleAnim = remember(pulseKey) {
        Animatable(0.85f)
    }
    LaunchedEffect(pulseKey) {
        scaleAnim.animateTo(1f,
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium))
    }
    Box(
        modifier = modifier
            .graphicsLayer {
                this.scaleX = scaleAnim.value
                this.scaleY = scaleAnim.value
            }
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(label, color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

/* ── Sync popover (the -0.1 / +0.1 / RE-SYNC / STYLE grid) ──────────────── */
@Composable
internal fun SyncPopover(
    offsetMs: Long,
    accent: Color,
    onNudge: (Long) -> Unit,
    onResync: () -> Unit,
    onStyle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E1017).copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("SYNC TOOL", color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold)
                Text("Offset " + (if (offsetMs >= 0) "+" else "") +
                    "%.2fs".format(offsetMs / 1000f),
                    color = Color(0xFF5B6070),
                    style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPopoverButton("-0.1s", false, accent, Modifier.weight(1f)) { onNudge(-100) }
                SyncPopoverButton("+0.1s", false, accent, Modifier.weight(1f)) { onNudge(100) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPopoverButton("⌖ RE-SYNC", true, accent, Modifier.weight(1f)) { onResync() }
                SyncPopoverButton("STYLE", false, accent, Modifier.weight(1f)) { onStyle() }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close", color = accent)
            }
        }
    }
}

@Composable
private fun SyncPopoverButton(
    label: String,
    teal: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (teal) accent.copy(alpha = 0.12f) else Color(0xFF171A22)
            )
            .border(
                1.dp,
                if (teal) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (teal) accent else Color(0xFFF2F4F8),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

/* ── Subtitle style dropdown (long-press the subtitle to open) ───────────
 * Operates on the host-owned [SubtitleStyle] — every mutation is routed
 * through [onStyle] so the host persists it and flows it back into the
 * screen (same value the SubtitleStyleSheet live-previews).
 * ------------------------------------------------------------------------- */
@Composable
internal fun SubtitleStyleDropdown(
    style: SubtitleStyle,
    onStyle: (SubtitleStyle) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    accent: Color,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Color(0xFF0E1017).copy(alpha = 0.96f), RoundedCornerShape(12.dp)),
    ) {
        DropdownMenuItem(
            text = { Text("Size: ${style.size.label}", color = Color.White) },
            onClick = {
                val next = SubtitleSize.entries[(style.size.ordinal + 1) % SubtitleSize.entries.size]
                onStyle(style.copy(size = next))
            },
        )
        DropdownMenuItem(
            text = { Text("Position: ${style.position.label}", color = Color.White) },
            onClick = {
                val next = SubtitlePosition.entries[(style.position.ordinal + 1) % SubtitlePosition.entries.size]
                onStyle(style.copy(position = next))
            },
        )
        DropdownMenuItem(
            text = { Text("Color: ${style.color.label}", color = Color.White) },
            onClick = {
                val next = SubtitleColor.entries[(style.color.ordinal + 1) % SubtitleColor.entries.size]
                onStyle(style.copy(color = next))
            },
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        DropdownMenuItem(
            text = { Text("Reset", color = accent) },
            onClick = onReset,
        )
    }
}
