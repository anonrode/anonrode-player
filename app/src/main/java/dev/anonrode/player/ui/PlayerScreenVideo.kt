package dev.anonrode.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/**
 * The video surface: PlayerView bound to [player] plus the first-frame
 * poster drawn over it.
 *
 * The key on the AndroidView identity re-binds the PlayerView to the
 * rebuilt ExoPlayer after a HW/SW swap. Without this the surface keeps
 * rendering the released (dead) player instance.
 */
@UnstableApi
@Composable
internal fun PlayerVideoSurface(
    player: Player,
    zoomMode: ZoomMode,
    poster: ImageBitmap?,
    onPlayerView: (PlayerView) -> Unit,
) {
    key(player) {
        // Forced-aspect modes (16:9 / 4:3) letterbox the surface to the
        // target ratio and stretch the frame to fill it; the free modes
        // (FIT / CROP / STR) fill the whole stage.
        val forcedAspect = zoomMode.forcedAspect
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                modifier = if (forcedAspect != null) {
                    Modifier.aspectRatio(forcedAspect)
                } else {
                    Modifier.fillMaxSize()
                },
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        resizeMode = zoomMode.resizeMode
                    }.also { onPlayerView(it) }
                },
                update = { pv ->
                    if (pv.player !== player) pv.player = player
                    pv.resizeMode = zoomMode.resizeMode
                }
            )
        }
    }

    // First-frame poster under the player view (drawn AFTER the
    // AndroidView so it sits on top, but the player surface draws
    // over it as soon as the first frame paints — listener clears
    // the poster in onPlaybackStateChanged(STATE_READY)).
    poster?.let { bmp ->
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
