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

/**
 * Layer 0 Decoder Surface (`§3.1`).
 * Binds `SurfaceView` to `ExoPlayer` and applies hardware-accelerated aspect/zoom transforms
 * without triggering view hierarchy relayout stalls.
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
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        16f / 9f
    }

    val targetAspect = when (mode) {
        AspectZoomMode.SIXTEEN_NINE -> 16f / 9f
        AspectZoomMode.FOUR_THREE -> 4f / 3f
        else -> naturalAspect
    }

    val targetScaleX by animateFloatAsState(
        targetValue = when (mode) {
            AspectZoomMode.FILL -> 1.18f
            AspectZoomMode.ZOOM -> 1.34f
            else -> 1.0f
        },
        animationSpec = tween(260, easing = OpticalGlassTokens.EaseReveal),
        label = "videoScaleX",
    )
    val targetScaleY by animateFloatAsState(
        targetValue = when (mode) {
            AspectZoomMode.FILL -> 1.08f
            AspectZoomMode.ZOOM -> 1.34f
            else -> 1.0f
        },
        animationSpec = tween(260, easing = OpticalGlassTokens.EaseReveal),
        label = "videoScaleY",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier
                .aspectRatio(targetAspect, matchHeightConstraintsFirst = false)
                .graphicsLayer {
                    scaleX = targetScaleX
                    scaleY = targetScaleY
                },
        )

        // First-Frame Poster Art Cache Crossfade
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
