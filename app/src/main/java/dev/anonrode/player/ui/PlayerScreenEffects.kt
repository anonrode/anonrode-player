package dev.anonrode.player.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Layer 1 Double-Tap Seek Ripple Arcs (§3.1 & §5).
 * Renders an optical glass convex arc on the left or right 34% of the viewport with
 * expanding radial acoustic wave rings and accumulated seek seconds (`-10s`, `+20s`).
 */
@Composable
fun SeekRippleOverlay(
    gestures: GestureUiState,
    modifier: Modifier = Modifier,
) {
    val side = gestures.doubleTapSide.intValue
    val accumSec = gestures.doubleTapAccumSec.intValue
    val pulseAnim = remember { Animatable(0f) }

    LaunchedEffect(side, accumSec) {
        if (side != 0) {
            pulseAnim.snapTo(0f)
            pulseAnim.animateTo(1f, tween(380, easing = OpticalGlassTokens.EaseReveal))
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Left Seek Ripple (<35% width)
        AnimatedVisibility(
            visible = side < 0,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(0.34f)
                .fillMaxHeight(),
        ) {
            SeekArcSurface(
                isLeft = true,
                label = "${accumSec}s",
                progress = pulseAnim.value,
            )
        }

        // Right Seek Ripple (>65% width)
        AnimatedVisibility(
            visible = side > 0,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.34f)
                .fillMaxHeight(),
        ) {
            SeekArcSurface(
                isLeft = false,
                label = "+${abs(accumSec)}s",
                progress = pulseAnim.value,
            )
        }
    }
}

@Composable
private fun SeekArcSurface(
    isLeft: Boolean,
    label: String,
    progress: Float,
) {
    val arcShape = remember(isLeft) {
        GenericShape { size, _ ->
            if (isLeft) {
                moveTo(0f, 0f)
                lineTo(size.width * 0.72f, 0f)
                quadraticBezierTo(size.width * 1.15f, size.height * 0.5f, size.width * 0.72f, size.height)
                lineTo(0f, size.height)
                close()
            } else {
                moveTo(size.width, 0f)
                lineTo(size.width * 0.28f, 0f)
                quadraticBezierTo(-size.width * 0.15f, size.height * 0.5f, size.width * 0.28f, size.height)
                lineTo(size.width, size.height)
                close()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .opticalGlass(
                shape = arcShape,
                baseColor = OpticalGlassTokens.CyanAccent.copy(alpha = 0.14f),
                isActive = true,
                borderWidth = 1.dp,
            )
            .drawWithCache {
                val center = Offset(
                    x = if (isLeft) size.width * 0.38f else size.width * 0.62f,
                    y = size.height * 0.5f,
                )
                onDrawBehind {
                    val radius = (size.minDimension * 0.18f) + (size.minDimension * 0.45f * progress)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                OpticalGlassTokens.CyanAccent.copy(alpha = (1f - progress) * 0.35f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = radius.coerceAtLeast(1f),
                        ),
                        radius = radius,
                        center = center,
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (isLeft) "⏪" else "⏩",
                fontSize = 22.sp,
            )
            Text(
                text = label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFeatureSettings = "tnum",
                    shadow = OpticalGlassTokens.CrispTextShadow,
                ),
            )
        }
    }
}

/**
 * §7.3 Transient Status Pill Auto-Dismiss Timers:
 * - Resume Prompt Pill: fades out after 5.0 seconds.
 * - Subtitle Sync Pill: fades out after 2.5 seconds.
 */
@Composable
fun TransientStatusPillTimers(ui: PlayerUiState) {
    LaunchedEffect(ui.resumePromptVisible.value) {
        if (ui.resumePromptVisible.value) {
            delay(5000L)
            ui.resumePromptVisible.value = false
        }
    }
    LaunchedEffect(ui.syncToastVisible.value, ui.subtitleOffsetSec.floatValue) {
        if (ui.syncToastVisible.value) {
            delay(2500L)
            ui.syncToastVisible.value = false
        }
    }
}

/**
 * §7.2 Background Scrub Frame Extractor with LRU Bitmap Cache.
 * Extracts scaled 256x144 (16:9) thumbnails off the main thread when `ui.localSeek` changes.
 */
@Composable
fun ScrubPreviewEffect(
    context: android.content.Context,
    mediaUri: Uri?,
    localSeekSec: Float,
    onFrameReady: (Bitmap?) -> Unit,
) {
    val frameCache = remember(mediaUri) {
        object : LruCache<Int, Bitmap>(24) {}
    }

    LaunchedEffect(mediaUri, localSeekSec) {
        if (mediaUri == null || localSeekSec < 0f) {
            return@LaunchedEffect
        }
        val bucketKey = (localSeekSec.toInt() / 2) * 2
        val cached = frameCache.get(bucketKey)
        if (cached != null && !cached.isRecycled) {
            onFrameReady(cached)
            return@LaunchedEffect
        }

        delay(32L)
        val extracted = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, mediaUri)
                retriever.getScaledFrameAtTime(
                    bucketKey * 1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    256,
                    144,
                )
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    retriever.release()
                } catch (_: Throwable) {
                }
            }
        }
        if (extracted != null) {
            frameCache.put(bucketKey, extracted)
            onFrameReady(extracted)
        }
    }
}
