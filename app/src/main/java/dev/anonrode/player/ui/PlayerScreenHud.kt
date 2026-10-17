package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

object OpticalGlassTokens {
    val EaseReveal = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)
    val CyanAccent = Color(0xFF38BDF8)
    val AmberBoost = Color(0xFFF59E0B)
    val EmeraldSync = Color(0xFF10B981)
    val SmokedChipBase = Color(0xFF080C14).copy(alpha = 0.32f)
    val SmokedDrawerBase = Color(0xFF080B12).copy(alpha = 0.52f)
    val SmokedHudPillBase = Color(0xFF080C14).copy(alpha = 0.42f)

    val CrispTextShadow = Shadow(
        color = Color.Black.copy(alpha = 0.88f),
        offset = Offset(0f, 1.5f),
        blurRadius = 5f,
    )
}

/**
 * Zero-allocation Optical Smoked Glass modifier.
 * Caches Outline, top-edge internal luminance highlight, and directional 3-stop specular rim border
 * inside [drawWithCache] so playback ticks and animations never allocate Brushes.
 */
fun Modifier.opticalGlass(
    shape: Shape = CircleShape,
    baseColor: Color = OpticalGlassTokens.SmokedChipBase,
    isActive: Boolean = false,
    accentColor: Color = OpticalGlassTokens.CyanAccent,
    borderWidth: Dp = 1.dp,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val strokePx = borderWidth.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)

        val internalHighlightBrush = if (isActive) {
            Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.42f),
                    accentColor.copy(alpha = 0.16f),
                ),
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                radius = (size.maxDimension * 0.75f).coerceAtLeast(1f),
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.09f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height * 0.42f,
            )
        }

        val specularRimBrush = if (isActive) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.75f),
                    accentColor.copy(alpha = 0.90f),
                    accentColor.copy(alpha = 0.38f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.36f),
                    Color.White.copy(alpha = 0.12f),
                    Color.White.copy(alpha = 0.04f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        }

        onDrawBehind {
            drawOutline(outline = outline, color = baseColor)
            drawOutline(outline = outline, brush = internalHighlightBrush)
            drawOutline(
                outline = outline,
                brush = specularRimBrush,
                style = Stroke(width = strokePx),
            )
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpticalGlassDiscButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    discSize: Dp = 36.dp,
    touchTargetSize: Dp = 42.dp,
    accentColor: Color = OpticalGlassTokens.CyanAccent,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(touchTargetSize)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(discSize)
                .opticalGlass(
                    shape = CircleShape,
                    isActive = isActive,
                    accentColor = accentColor,
                ),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpticalGlassChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    height: Dp = 36.dp,
    accentColor: Color = OpticalGlassTokens.CyanAccent,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(height)
            .opticalGlass(
                shape = RoundedCornerShape(50),
                isActive = isActive,
                accentColor = accentColor,
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leadingIcon?.invoke()
        Text(
            text = label,
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                shadow = OpticalGlassTokens.CrispTextShadow,
            ),
        )
    }
}

/**
 * Edge-hugged 34dp x 176dp vertical glass tube for Brightness (left) and 200% Volume Boost (right).
 */
@Composable
fun VerticalGestureHudPill(
    visible: Boolean,
    isVolume: Boolean,
    percentage: Int, // 0..100 for Brightness, 0..200 for Volume+Boost
    modifier: Modifier = Modifier,
) {
    val maxPct = if (isVolume) 200f else 100f
    val targetFraction = (percentage / maxPct).coerceIn(0f, 1f)
    val animatedFill by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 60),
        label = "verticalPillFill",
    )
    val isBoosted = isVolume && percentage > 100
    val barColor = if (isBoosted) OpticalGlassTokens.AmberBoost else OpticalGlassTokens.CyanAccent

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(150)) + scaleIn(tween(200, easing = OpticalGlassTokens.EaseReveal), initialScale = 0.92f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.92f),
    ) {
        Column(
            modifier = Modifier
                .width(34.dp)
                .height(176.dp)
                .opticalGlass(
                    shape = RoundedCornerShape(50),
                    baseColor = OpticalGlassTokens.SmokedHudPillBase,
                    isActive = isBoosted,
                    accentColor = barColor,
                )
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (isVolume) (if (percentage == 0) "🔇" else "🔊") else "☀️",
                fontSize = 13.sp,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .width(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedFill)
                        .clip(RoundedCornerShape(50))
                        .background(barColor),
                )
            }

            Text(
                text = "$percentage%",
                style = TextStyle(
                    color = if (isBoosted) OpticalGlassTokens.AmberBoost else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                    shadow = OpticalGlassTokens.CrispTextShadow,
                ),
            )
        }
    }
}

/**
 * Center HUD pill for Hold-to-Boost discrete milestone scrubbing and horizontal drag-seek readout.
 */
@Composable
fun CenterGestureHudPill(
    ui: PlayerUiState,
    gestures: GestureUiState,
    modifier: Modifier = Modifier,
) {
    val isSeeking = gestures.mode.value == GestureMode.SEEK && gestures.pendingSeekMs.floatValue >= 0f
    val isBoosting = ui.boostActive.value || ui.speedScrubHudVisible.value
    val visible = isSeeking || isBoosting

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)) + scaleIn(tween(180, easing = OpticalGlassTokens.EaseReveal), initialScale = 0.94f),
        exit = fadeOut(tween(140)),
    ) {
        Column(
            modifier = Modifier
                .opticalGlass(
                    shape = RoundedCornerShape(24.dp),
                    baseColor = OpticalGlassTokens.SmokedHudPillBase,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (isBoosting) {
                val activeIdx = ui.discreteScrubIndex.intValue.coerceIn(0, PlayerUiState.SpeedMilestones.lastIndex)
                val spd = PlayerUiState.SpeedMilestones[activeIdx]
                Text(
                    text = "⚡ Speed Boost ${spd}x",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerUiState.SpeedMilestones.forEachIndexed { idx, milestone ->
                        val selected = idx == activeIdx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) OpticalGlassTokens.CyanAccent else Color.Transparent
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${milestone}x",
                                style = TextStyle(
                                    color = if (selected) Color.Black else Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.5.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                    fontFeatureSettings = "tnum",
                                ),
                            )
                        }
                    }
                }
            } else if (isSeeking) {
                val targetSec = (gestures.pendingSeekMs.floatValue / 1000f).coerceAtLeast(0f)
                val delta = gestures.scrubDeltaSec.floatValue.roundToInt()
                val sign = if (delta >= 0) "+" else ""
                Text(
                    text = "${formatSecondsToHms(targetSec.toInt())}  ($sign${delta}s)",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }
        }
    }
}

fun formatSecondsToHms(totalSec: Int): String {
    val clamped = totalSec.coerceAtLeast(0)
    val hrs = clamped / 3600
    val mins = (clamped % 3600) / 60
    val secs = clamped % 60
    return if (hrs > 0) {
        "%d:%02d:%02d".format(hrs, mins, secs)
    } else {
        "%02d:%02d".format(mins, secs)
    }
}
