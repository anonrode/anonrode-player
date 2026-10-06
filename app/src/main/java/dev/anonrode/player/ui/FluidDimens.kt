package dev.anonrode.player.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Fluid responsive sizing engine for the video player.
 * Implements smooth linear interpolation (clamp) between minimum and maximum viewports:
 *
 *   size(W) = min + (max - min) * ((W - minW) / (maxW - minW))
 *   clamped to [min, max]
 *
 * In landscape mode, vertical spacing is compressed by ~30% (base * 0.70f)
 * to maximize visible video area while keeping hit targets >= 48dp.
 */
internal object FluidDimens {
    val MinTouchTarget: Dp = 48.dp

    /**
     * Smoothly scales a [Dp] value between [min] and [max] based on the current screen width.
     */
    @Composable
    fun fluid(
        min: Dp,
        max: Dp,
        minW: Dp = 360.dp,
        maxW: Dp = 920.dp,
    ): Dp {
        val config = LocalConfiguration.current
        val screenW = config.screenWidthDp.dp
        if (screenW <= minW) return min
        if (screenW >= maxW) return max
        val progress = ((screenW.value - minW.value) / (maxW.value - minW.value)).coerceIn(0f, 1f)
        return (min.value + (max.value - min.value) * progress).dp
    }

    /**
     * Orientation-aware vertical scaling:
     * Compresses vertical dimensions by ~30% in landscape mode (`base * 0.70f`)
     * to grant maximum viewport area to video content.
     */
    fun fluidVertical(base: Dp, isLandscape: Boolean): Dp {
        return if (isLandscape) (base.value * 0.70f).dp else base
    }

    /**
     * Orientation-aware vertical scaling reading configuration directly.
     */
    @Composable
    fun fluidVertical(base: Dp): Dp {
        val config = LocalConfiguration.current
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        return fluidVertical(base, isLandscape)
    }

    /**
     * Smoothly scales typography [TextUnit] between [min] and [max] based on screen width.
     */
    @Composable
    fun fluidText(
        min: TextUnit,
        max: TextUnit,
        minW: Dp = 360.dp,
        maxW: Dp = 920.dp,
    ): TextUnit {
        val config = LocalConfiguration.current
        val screenW = config.screenWidthDp.dp
        if (screenW <= minW) return min
        if (screenW >= maxW) return max
        val progress = ((screenW.value - minW.value) / (maxW.value - minW.value)).coerceIn(0f, 1f)
        return (min.value + (max.value - min.value) * progress).sp
    }

    /**
     * Enforce that interactive controls maintain at least the 48dp touch target standard.
     */
    fun clampTouch(target: Dp): Dp = target.coerceAtLeast(MinTouchTarget)
}

@Composable
internal fun fluid(min: Dp, max: Dp, minW: Dp = 360.dp, maxW: Dp = 920.dp): Dp =
    FluidDimens.fluid(min, max, minW, maxW)

internal fun fluidVertical(base: Dp, isLandscape: Boolean): Dp =
    FluidDimens.fluidVertical(base, isLandscape)

@Composable
internal fun fluidVertical(base: Dp): Dp =
    FluidDimens.fluidVertical(base)

@Composable
internal fun fluidText(min: TextUnit, max: TextUnit, minW: Dp = 360.dp, maxW: Dp = 920.dp): TextUnit =
    FluidDimens.fluidText(min, max, minW, maxW)
