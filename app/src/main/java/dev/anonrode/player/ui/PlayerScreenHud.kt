package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ── Small overlay surfaces: double-tap flash, gesture HUD pill, buffering
 * spinner, lock badge, A-B chip, Up Next pill, auto-advance countdown,
 * calibration banner and the in-overlay transient toast. Each is rendered
 * from PlayerScreen's root Box with its BoxScope alignment passed in via
 * [modifier].
 * ------------------------------------------------------------------------- */

/** Double-tap seek flash on the tapped edge. */
@Composable
internal fun DoubleTapFlash(
    modifier: Modifier = Modifier,
    side: Int,
    seekIncrementSec: Int = 10,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(120.dp)
            .background(Color.White.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (side < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                null, tint = Color.White, modifier = Modifier.size(34.dp)
            )
            Text(
                // The configured seek step (5/10/15/30s in Settings), not a
                // hardcoded "10s" — the flash must match what actually seeked.
                (if (side < 0) "−" else "+") + "${seekIncrementSec.coerceAtLeast(1)}s",
                color = Color.White, style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

/** Gesture HUD pill (volume / brightness / seek / zoom / speed feedback). */
@Composable
internal fun GestureHudPill(
    modifier: Modifier = Modifier,
    icon: ImageVector?,
    text: String,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
    }
}

/** Buffering spinner (center, undecorated). */
@Composable
internal fun BufferingSpinner(
    modifier: Modifier = Modifier,
    accent: Color,
) {
    CircularProgressIndicator(
        modifier = modifier.size(48.dp),
        color = accent,
        strokeWidth = 3.dp,
    )
}

/** Lock badge shown while controls are locked; tap unlocks. */
@Composable
internal fun LockBadge(
    modifier: Modifier = Modifier,
    accent: Color,
    onUnlock: () -> Unit,
) {
    IconButton(
        onClick = onUnlock,
        modifier = modifier
            .padding(14.dp)
            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
    ) {
        Icon(Icons.Filled.Lock, null, tint = accent)
    }
}

/** A-B repeat chip (top-center while a region is set/looping); tap advances
 *  the cycle (set B / clear). */
@Composable
internal fun AbRepeatChip(
    modifier: Modifier = Modifier,
    abStartMs: Long,
    abEndMs: Long?,
    accent: Color,
    onTap: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .clickable { onTap() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.Repeat,
            contentDescription = "A-B repeat",
            tint = accent,
            modifier = Modifier.size(15.dp),
        )
        Text(
            if (abEndMs != null) fmtTime(abStartMs) + " – " + fmtTime(abEndMs)
            else "A = " + fmtTime(abStartMs) + " — now set B",
            color = Color.White,
            fontSize = 12.sp,
        )
    }
}

/** Up Next pill (final 30 s of an episode). */
@Composable
internal fun UpNextPill(
    modifier: Modifier = Modifier,
    upNextTitle: String?,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            Icons.Filled.SkipNext,
            contentDescription = "Next episode",
            tint = accent,
            modifier = Modifier.size(18.dp)
        )
        Text(
            if (upNextTitle.isNullOrEmpty()) "Next episode"
            else "Up Next: $upNextTitle",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 280.dp)
        )
    }
}

/** Auto-advance countdown overlay ("Next episode in N..."). */
@Composable
internal fun NextCountdownOverlay(
    modifier: Modifier = Modifier,
    countdownSec: Int,
    onCancel: () -> Unit,
    onPlayNow: () -> Unit,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Next episode in $countdownSec...",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = onPlayNow) { Text("Play now") }
        }
    }
}

/* ── Calibration banner: RETIRED (v0.7.3). Its whole job — "the engine is
 * working right now" — is carried by the sync HERO chip's "Syncing…" state
 * in the dock; it used to render at the exact same top=70/start=14 slot as
 * the (also retired) SYNCED chip and the two could stack on each other. ── */

/* ── Transient toast banner inside the player overlay ───────────────────── */
@Composable
internal fun PlayerOverlayToast(
    message: String?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = message != null, modifier = modifier) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF0D0F16).copy(alpha = 0.92f))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                message ?: "",
                color = Color(0xFFDFFFF4),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
