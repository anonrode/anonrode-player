package dev.anonrode.player.ui

import android.graphics.Bitmap
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max
import kotlin.math.min

/**
 * Layer 0 Decoder Surface.
 * Binds `SurfaceView` to `ExoPlayer` and computes exact viewport-to-video aspect transforms
 * (`FIT`, `FILL`, `ZOOM` center-crop, `16:9`, `4:3`) via hardware-accelerated `graphicsLayer`.
 */
@Composable
fun PlayerVideoSurface(
    player: ExoPlayer?,
    videoWidth: Int,
    videoHeight: Int,
    zoomIdx: Int,
    posterArtBitmap: Bitmap?,
    hasRenderedFirstFrame: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surfaceView = remember(context) {
        SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    DisposableEffect(player, surfaceView) {
        player?.setVideoSurfaceView(surfaceView)
        onDispose {
            player?.clearVideoSurfaceView(surfaceView)
        }
    }

    val mode = AspectZoomMode.entries[zoomIdx.mod(AspectZoomMode.entries.size)]
    val naturalAspect = if (videoWidth > 0 && videoHeight > 0) {
        (videoWidth.toFloat() / videoHeight.toFloat()).coerceIn(0.3f, 3.5f)
    } else {
        16f / 9f
    }

    val baseAspect = when (mode) {
        AspectZoomMode.SIXTEEN_NINE -> 16f / 9f
        AspectZoomMode.FOUR_THREE -> 4f / 3f
        else -> naturalAspect
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val containerW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val containerAspect = containerW / containerH

        val fittedW: Float
        val fittedH: Float
        if (containerAspect > baseAspect) {
            fittedH = containerH
            fittedW = containerH * baseAspect
        } else {
            fittedW = containerW
            fittedH = containerW / baseAspect
        }

        val rawScaleX: Float
        val rawScaleY: Float
        when (mode) {
            AspectZoomMode.FIT,
            AspectZoomMode.SIXTEEN_NINE,
            AspectZoomMode.FOUR_THREE -> {
                rawScaleX = 1f
                rawScaleY = 1f
            }
            AspectZoomMode.ZOOM -> {
                // True center-crop (fills entire 20:9 screen without distortion)
                val cropScale = max(containerW / fittedW.coerceAtLeast(1f), containerH / fittedH.coerceAtLeast(1f))
                rawScaleX = cropScale
                rawScaleY = cropScale
            }
            AspectZoomMode.FILL -> {
                // True full-screen stretch
                rawScaleX = containerW / fittedW.coerceAtLeast(1f)
                rawScaleY = containerH / fittedH.coerceAtLeast(1f)
            }
        }

        val targetScaleX by animateFloatAsState(
            targetValue = rawScaleX,
            animationSpec = tween(220, easing = OpticalGlassTokens.EaseReveal),
            label = "videoScaleX",
        )
        val targetScaleY by animateFloatAsState(
            targetValue = rawScaleY,
            animationSpec = tween(220, easing = OpticalGlassTokens.EaseReveal),
            label = "videoScaleY",
        )

        val matchHeightFirst = containerAspect > baseAspect

        AndroidView(
            factory = { surfaceView },
            modifier = Modifier
                .aspectRatio(baseAspect, matchHeightConstraintsFirst = matchHeightFirst)
                .graphicsLayer {
                    scaleX = targetScaleX
                    scaleY = targetScaleY
                },
        )

        AnimatedVisibility(
            visible = !hasRenderedFirstFrame && posterArtBitmap != null && !posterArtBitmap.isRecycled,
            exit = fadeOut(tween(220)),
        ) {
            if (posterArtBitmap != null && !posterArtBitmap.isRecycled) {
                Image(
                    bitmap = posterArtBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
