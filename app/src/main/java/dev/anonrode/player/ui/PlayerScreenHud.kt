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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Icon
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
import kotlin.math.abs
import kotlin.math.roundToInt

object OpticalGlassTokens {
    val EaseReveal = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)
    val EmeraldAccent = Color(0xFF19B444)
    val EmeraldSync = Color(0xFF3DDBA8)
    val AmberBoost = Color(0xFFF5B84B)
    val SmokedChipBase = Color.Black.copy(alpha = 0.50f)
    val SmokedDrawerBase = Color(0xF5040406)
    val SmokedHudPillBase = Color(0xDE0A0A0C)

    val CrispTextShadow = Shadow(
        color = Color.Black.copy(alpha = 0.88f),
        offset = Offset(0f, 1.5f),
        blurRadius = 4f,
    )
}

/**
 * Zero-allocation pure-black glass modifier (zero blur, zero blue).
 */
fun Modifier.opticalGlass(
    shape: Shape = CircleShape,
    baseColor: Color = OpticalGlassTokens.SmokedChipBase,
    isActive: Boolean = false,
    accentColor: Color = OpticalGlassTokens.EmeraldAccent,
    borderWidth: Dp = 0.8.dp,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val strokePx = borderWidth.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)

        val fillColor = if (isActive) {
            accentColor.copy(alpha = 0.16f)
        } else {
            baseColor
        }

        val rimColor = if (isActive) {
            accentColor.copy(alpha = 0.55f)
        } else {
            Color.White.copy(alpha = 0.10f)
        }

        onDrawBehind {
            drawOutline(outline = outline, color = fillColor)
            if (strokePx > 0f) {
                drawOutline(
                    outline = outline,
                    color = rimColor,
                    style = Stroke(width = strokePx),
                )
            }
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpticalGlassDiscButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    discSize: Dp = 38.dp,
    touchTargetSize: Dp = 48.dp,
    accentColor: Color = OpticalGlassTokens.EmeraldAccent,
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
                .clip(CircleShape)
                .background(if (isActive) accentColor else Color.Black.copy(alpha = 0.50f)),
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
    accentColor: Color = OpticalGlassTokens.EmeraldAccent,
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
                color = if (isActive) OpticalGlassTokens.EmeraldSync else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
                shadow = OpticalGlassTokens.CrispTextShadow,
            ),
        )
    }
}

/**
 * Minimal borderless vertical capsule for Brightness (left) and 200% Volume+Boost (right).
 */
@Composable
fun VerticalGestureHudPill(
    visible: Boolean,
    isVolume: Boolean,
    percentage: Int,
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
    val barColor = if (isBoosted) OpticalGlassTokens.AmberBoost else OpticalGlassTokens.EmeraldAccent

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)) + scaleIn(tween(180, easing = OpticalGlassTokens.EaseReveal), initialScale = 0.94f),
        exit = fadeOut(tween(140)) + scaleOut(tween(140), targetScale = 0.94f),
    ) {
        Column(
            modifier = Modifier
                .width(38.dp)
                .height(168.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$percentage%",
                style = TextStyle(
                    color = if (isBoosted) OpticalGlassTokens.AmberBoost else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    shadow = OpticalGlassTokens.CrispTextShadow,
                ),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .width(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.20f)),
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

            Icon(
                imageVector = when {
                    !isVolume -> Icons.Rounded.WbSunny
                    percentage == 0 -> Icons.AutoMirrored.Rounded.VolumeOff
                    else -> Icons.AutoMirrored.Rounded.VolumeUp
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private val HoldBoostStops = listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f)

/**
 * Top-center HUD capsule for Hold-to-Boost discrete milestone scrubbing,
 * horizontal drag-seek readout, and transient aspect-ratio badge.
 */
@Composable
fun CenterGestureHudPill(
    ui: PlayerUiState,
    gestures: GestureUiState,
    modifier: Modifier = Modifier,
) {
    val isSeeking = gestures.mode.value == GestureMode.SEEK && gestures.pendingSeekMs.floatValue >= 0f
    val isBoosting = ui.boostActive.value || ui.speedScrubHudVisible.value
    val isAspect = ui.aspectBadgeVisible.value && !isSeeking && !isBoosting
    val visible = isSeeking || isBoosting || isAspect

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(130)) + scaleIn(tween(170, easing = OpticalGlassTokens.EaseReveal), initialScale = 0.95f),
        exit = fadeOut(tween(130)),
    ) {
        when {
            isBoosting -> {
                val activeIdx = ui.discreteScrubIndex.intValue.coerceIn(0, PlayerUiState.SpeedMilestones.lastIndex)
                val spd = PlayerUiState.SpeedMilestones[activeIdx]
                val speedLabel = if (spd % 1f == 0f) "${spd.toInt()}.0x" else "${spd}x"
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xD80A0A0C))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = "0.25x",
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.80f),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                        Box(
                            modifier = Modifier.width(220.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.24f)),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                HoldBoostStops.forEach { stop ->
                                    val isStopSelected = abs(spd - stop) < 0.13f
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "${stop}x",
                                            style = TextStyle(
                                                color = if (isStopSelected) OpticalGlassTokens.EmeraldSync else Color.White.copy(alpha = 0.72f),
                                                fontSize = 9.5.sp,
                                                fontWeight = if (isStopSelected) FontWeight.Bold else FontWeight.Normal,
                                                fontFeatureSettings = "tnum",
                                            ),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(if (isStopSelected) 10.dp else 5.dp)
                                                .clip(CircleShape)
                                                .background(if (isStopSelected) OpticalGlassTokens.EmeraldAccent else Color.White),
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = "4.0x",
                            style = TextStyle(
                                color = Color.White.copy(alpha = 0.80f),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FastForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "$speedLabel Speed Playing",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFeatureSettings = "tnum",
                            ),
                        )
                    }
                }
            }
            isSeeking -> {
                val targetSec = (gestures.pendingSeekMs.floatValue / 1000f).coerceAtLeast(0f)
                val delta = gestures.scrubDeltaSec.floatValue.roundToInt()
                val sign = if (delta >= 0) "+" else ""
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xD80A0A0C))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "${formatSecondsToHms(targetSec.toInt())}  ($sign${delta}s)",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFeatureSettings = "tnum",
                            shadow = OpticalGlassTokens.CrispTextShadow,
                        ),
                    )
                }
            }
            isAspect -> {
                val mode = AspectZoomMode.entries[ui.zoomIdx.intValue % AspectZoomMode.entries.size]
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xD80A0A0C))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Aspect: ${mode.label}",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            shadow = OpticalGlassTokens.CrispTextShadow,
                        ),
                    )
                }
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
