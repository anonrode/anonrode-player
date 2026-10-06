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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
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
            .padding(horizontal = 32.dp)
            .size(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.20f))
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = if (side < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = "${if (side < 0) "−" else "+"}${seekIncrementSec.coerceAtLeast(1)}s",
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
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
    Box(
        modifier = modifier
            .padding(16.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF2A3647),
                        Color(0xFF1A2330),
                        Color(0xFF111722),
                    )
                )
            )
            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.50f), CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onUnlock,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = "Unlock controls",
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(20.dp),
        )
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

/**
 * Volume / Brightness vertical slider pill shown during vertical drag gestures.
 * Positioned on the left edge (Brightness) or right edge (Volume).
 */
@Composable
internal fun VerticalGestureHudPill(
    type: VerticalHudType?,
    progress: Float,
    valueText: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.88f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val icon = if (type == VerticalHudType.VOLUME) {
                if (progress <= 0.01f) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp
            } else {
                Icons.Filled.WbSunny
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            // Vertical level bar: 6dp width, 80dp height
            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = 80.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(progress.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(Color(0xFF38BDF8))
                )
            }
            Text(
                text = valueText,
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * Double-tap seek ripples: circular indicator on left half (-10s) or right half (+10s).
 */
@Composable
internal fun SeekRippleOverlay(
    side: Int,
    seconds: Int,
    modifier: Modifier = Modifier,
) {
    if (side == 0) return
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 32.dp)
                .size(64.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.20f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (side < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = "${if (side < 0) "−" else "+"}${seconds}s",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/**
 * Press-and-hold 2.0x temporary speed banner at top-center.
 */
@Composable
internal fun SpeedBannerHud(
    speedText: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF2A3647),
                        Color(0xFF1A2330),
                        Color(0xFF111722),
                    )
                )
            )
            .border(1.dp, Color(0xFFF59E0B).copy(alpha = 0.50f), CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⏩", fontSize = 13.sp)
        Text(
            text = speedText,
            color = Color(0xFFFCD34D),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Lock scrim overlay when screen controls are locked. Tap unlocks.
 */
@Composable
internal fun LockScrimOverlay(
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onUnlock,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF2A3647),
                            Color(0xFF1A2330),
                            Color(0xFF111722),
                        )
                    )
                )
                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.85f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onUnlock,
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = Color(0xFF38BDF8),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Screen Locked · Tap to Unlock",
                color = Color(0xFF38BDF8),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Transient aspect ratio badge in top right corner.
 */
@Composable
internal fun AspectBadgeOverlay(
    badgeText: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badgeText,
            color = Color(0xFF38BDF8),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Camera flash white overlay on screenshot capture.
 */
@Composable
internal fun CameraFlashOverlay(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(Color.White)
    )
}
