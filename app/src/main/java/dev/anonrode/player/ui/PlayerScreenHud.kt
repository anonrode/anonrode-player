package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import android.view.HapticFeedbackConstants
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/* ── Small overlay surfaces: double-tap flash, gesture HUD pill, buffering
 * spinner, lock badge, A-B chip, Up Next pill, auto-advance countdown,
 * calibration banner and the in-overlay transient toast. Each is rendered
 * from PlayerScreen's root Box with its BoxScope alignment passed in via
 * [modifier].
 * ------------------------------------------------------------------------- */

/** Double-tap seek flash on the tapped edge: delegates to box-free floating SeekRippleOverlay. */
@Composable
internal fun DoubleTapFlash(
    modifier: Modifier = Modifier,
    side: Int,
    seekIncrementSec: Int = 10,
) {
    SeekRippleOverlay(
        side = side,
        seconds = seekIncrementSec.coerceAtLeast(1),
        modifier = modifier,
    )
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
            .border(1.dp, accent.copy(alpha = 0.50f), CircleShape)
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
            tint = accent,
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
    accent: Color,
    onCancel: () -> Unit,
    onPlayNow: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A).copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Next episode in ${countdownSec}s…",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Cancel",
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.25f))
                        .border(1.dp, accent.copy(alpha = 0.70f), RoundedCornerShape(10.dp))
                        .clickable(onClick = onPlayNow)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Play now",
                        color = accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
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
 * Mature obsidian glass aesthetic, dynamic glyphs, and frosted monochrome rail.
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
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xD9080A0E))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
            .padding(horizontal = 11.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val icon = if (type == VerticalHudType.VOLUME) {
                when {
                    progress <= 0.01f -> Icons.AutoMirrored.Filled.VolumeOff
                    progress < 0.50f -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                }
            } else {
                Icons.Filled.WbSunny
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            // Vertical level bar: 7dp width, 88dp height
            Box(
                modifier = Modifier
                    .size(width = 7.dp, height = 88.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(progress.coerceIn(0f, 1f))
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            Text(
                text = valueText,
                color = Color.White,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = Tnum,
            )
        }
    }
}

/**
 * Double-tap seek indicator:
 * Pure floating vector chevrons and label on left half (-10s) or right half (+10s).
 * - Strictly zero bounding box, zero borders, zero background containers.
 * - Almost transparent black (Color(0x73000000)).
 * - Strictly zero blur, razor-sharp vector geometry.
 * - Buttery fluid momentum glide + snappy spring entrance.
 */
@Composable
internal fun SeekRippleOverlay(
    side: Int,
    seconds: Int,
    modifier: Modifier = Modifier,
) {
    if (side == 0) return

    val animAlpha = remember { Animatable(0f) }
    val animOffset = remember { Animatable(if (side < 0) 14f else -14f) }

    LaunchedEffect(side, seconds) {
        animAlpha.snapTo(0f)
        animOffset.snapTo(if (side < 0) 14f else -14f)
        coroutineScope {
            launch {
                animAlpha.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing)
                )
            }
            launch {
                animOffset.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                )
            }
        }
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 44.dp)
                .offset(x = animOffset.value.dp)
                .graphicsLayer { alpha = animAlpha.value },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = if (side < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                contentDescription = null,
                tint = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier.size(38.dp),
            )
            Text(
                text = "${if (side < 0) "−" else "+"}${seconds}s",
                color = Color.Black.copy(alpha = 0.48f),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp,
            )
        }
    }
}

/**
 * Discrete milestone speed scrub HUD (Long-press & horizontal slide).
 *
 * Implements Feature 1 from the reference recording with:
 * - State A (Resting / Finger still): Compact translucent pill "[ ▶▶ 2.0x Speed Playing ]".
 * - State B (Sliding horizontally): Expanded discrete milestone track with strict 1:1
 *   vertical alignment between labels, tick markers, thumb disc, and readout.
 * - Smoky translucent black cinema styling with zero bright blue clutter.
 */
@Composable
internal fun DiscreteSpeedScrubHud(
    visible: Boolean,
    isSliding: Boolean,
    stepIndex: Int,
    milestones: List<Float>,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val speed = milestones.getOrElse(stepIndex) { 2.0f }

    AnimatedContent(
        targetState = isSliding,
        transitionSpec = {
            fadeIn(animationSpec = tween(150)) togetherWith fadeOut(animationSpec = tween(150))
        },
        modifier = modifier,
        label = "DiscreteSpeedHudAnim",
    ) { sliding ->
        if (!sliding) {
            // State A: Compact Translucent Pill
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xD9080A0E))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                val formattedSpeed = if (speed % 1f == 0f) "${speed.toInt()}.0x" else "${speed}x"
                Text(
                    text = "$formattedSpeed Speed Playing",
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = Tnum,
                )
            }
        } else {
            // State B: Expanded Discrete Milestone Track with Strict 1:1 Vertical Alignment
            val count = milestones.size.coerceAtLeast(2)
            val thumbRadius = 8.dp
            val horizontalPadding = 16.dp

            BoxWithConstraints(
                modifier = Modifier
                    .width(304.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xEB080A0E))
                    .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                    .padding(horizontal = horizontalPadding, vertical = 12.dp),
            ) {
                val availableWidth = maxWidth
                val trackWidth = availableWidth - (thumbRadius * 2)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Row 1: Milestone Labels precisely positioned at X_i = thumbRadius + (fraction * trackWidth)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp)
                    ) {
                        milestones.forEachIndexed { idx, m ->
                            val fraction = idx.toFloat() / (count - 1).toFloat()
                            val isActive = idx == stepIndex
                            val label = if (m == 0.25f) "¼" else if (m % 1f == 0f) "${m.toInt()}x" else "${m}x"
                            val centerX = thumbRadius + (trackWidth * fraction)

                            Box(
                                modifier = Modifier
                                    .offset(x = centerX - 14.dp)
                                    .width(28.dp)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    color = if (isActive) Color.White else Color.White.copy(alpha = 0.38f),
                                    fontSize = if (isActive) 11.5.sp else 10.sp,
                                    fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }

                    // Row 2: Track with Tick Dots and Thumb (Strict vertical alignment with Row 1)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(20.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        // Inactive Rail
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                        )

                        // Milestone Tick Dots
                        milestones.forEachIndexed { idx, _ ->
                            val fraction = idx.toFloat() / (count - 1).toFloat()
                            val centerX = thumbRadius + (trackWidth * fraction)
                            Box(
                                modifier = Modifier
                                    .offset(x = centerX - 1.5.dp)
                                    .size(width = 3.dp, height = 8.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(Color.White.copy(alpha = 0.30f))
                            )
                        }

                        // Active Glow Fill (up to active thumb center)
                        val activeFraction = stepIndex.toFloat() / (count - 1).toFloat()
                        val activeFillWidth = thumbRadius + (trackWidth * activeFraction)
                        Box(
                            modifier = Modifier
                                .width(activeFillWidth)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.85f))
                        )

                        // Pure White Thumb Disc exactly centered at centerX
                        Box(
                            modifier = Modifier
                                .offset(x = activeFillWidth - thumbRadius)
                                .size(thumbRadius * 2)
                                .clip(CircleShape)
                                .background(Color.White)
                                .border(1.5.dp, Color.Black.copy(alpha = 0.3f), CircleShape)
                        )
                    }

                    // Row 3: Numeric Readout and Instructions
                    val displaySpeed = if (speed % 1f == 0f) "${speed.toInt()}.0x" else "${speed}x"
                    Text(
                        text = displaySpeed,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        style = Tnum,
                    )
                    Text(
                        text = "◀ Slide between milestones · Release to restore ▶",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

/**
 * Lock scrim overlay when screen controls are locked. Tap unlocks.
 */
@Composable
internal fun LockScrimOverlay(
    accent: Color,
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
                .border(1.dp, accent.copy(alpha = 0.85f), CircleShape)
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
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Screen Locked · Tap to Unlock",
                color = accent,
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
    accent: Color,
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
            color = accent,
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

/**
 * Visible live subtitle sync mini-pill HUD requested at 03:40 of screen recording.
 * Non-intrusively shows [ ⟳ Syncing… ] while correlating, and [ ✓ Synced +0.12s ]
 * when locked. Clicking it triggers the sync detail popover.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubSyncStatusHud(
    visible: Boolean,
    isSyncRunning: Boolean,
    isSyncLocked: Boolean,
    liveOffsetMs: Long,
    accent: Color,
    syncEnabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val shouldShow = visible
    AnimatedVisibility(
        visible = shouldShow,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = modifier,
    ) {
        val lockedColor = Color(0xFF10B981)
        val offColor = Color(0xFF94A3B8)
        val activeAccent = when {
            !syncEnabled -> offColor
            isSyncLocked -> lockedColor
            else -> accent
        }
        val view = LocalView.current

        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xE60E1017))
                .border(
                    width = 1.dp,
                    color = activeAccent.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp),
                )
                .then(
                    if (onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                onClick()
                            },
                            onLongClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                onLongClick()
                            },
                        )
                    } else {
                        Modifier.clickable(onClick = onClick)
                    }
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!syncEnabled) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "Sync Off",
                    tint = offColor,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "Sync Off",
                    color = offColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (isSyncRunning) {
                CircularProgressIndicator(
                    color = accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "Syncing…",
                    color = Color.White.copy(alpha = 0.90f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            } else if (isSyncLocked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Synced",
                    tint = lockedColor,
                    modifier = Modifier.size(13.dp),
                )
                val offsetSec = liveOffsetMs / 1000.0
                val sign = if (offsetSec > 0) "+" else ""
                Text(
                    text = "Synced ${sign}${String.format(java.util.Locale.US, "%.2f", offsetSec)}s",
                    color = lockedColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    style = Tnum,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = "Sub Sync",
                    tint = accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    text = "Sub Sync",
                    color = accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
