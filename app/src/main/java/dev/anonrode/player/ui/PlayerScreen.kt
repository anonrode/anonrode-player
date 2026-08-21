package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFF6C63FF)
private val Teal = Color(0xFF00D4AA)

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Full-bleed player: gradient-scrim overlay controls, auto-hide while playing,
 * double-tap ±10s with flash, left/right vertical swipe = brightness/volume
 * with HUD pill, horizontal swipe = live seek. MX-style, per Section 3 spec.
 */
@UnstableApi
@Composable
fun PlayerScreen(
    player: Player,
    title: String,
    cueText: String?,
    positionSec: Float,
    durationSec: Float,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val activity = context as? Activity

    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var locked by remember { mutableStateOf(false) }
    var showCC by remember { mutableStateOf(true) }
    var speedIdx by remember { mutableIntStateOf(2) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    var hudIcon by remember { mutableStateOf<ImageVector?>(null) }
    var hudText by remember { mutableStateOf("") }
    var hudVisible by remember { mutableStateOf(false) }
    var flashSide by remember { mutableIntStateOf(0) } // -1 left, +1 right, 0 none

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) {
                isPlaying = p
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    fun showHud(icon: ImageVector, text: String) {
        hudIcon = icon
        hudText = text
        hudVisible = true
        view.postDelayed({ hudVisible = false }, 900)
    }

    fun seekBy(sec: Int) {
        val d = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((player.currentPosition + sec * 1000L).coerceIn(0L, d))
        flashSide = if (sec < 0) -1 else 1
        view.postDelayed({ flashSide = 0 }, 420)
    }

    LaunchedEffect(controlsVisible, isPlaying, locked) {
        if (controlsVisible && isPlaying && !locked) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }

    var scrW by remember { mutableFloatStateOf(1000f) }
    var scrH by remember { mutableFloatStateOf(1000f) }

    var mode by remember { mutableStateOf<String?>(null) } // "seek" | "vol" | "bri"
    var startX by remember { mutableFloatStateOf(0f) }
    var startY by remember { mutableFloatStateOf(0f) }
    var lastX by remember { mutableFloatStateOf(0f) }
    var startPosMs by remember { mutableFloatStateOf(0f) }
    var startVol by remember { mutableIntStateOf(0) }
    var startBri by remember { mutableFloatStateOf(0.5f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { scrW = it.width.toFloat(); scrH = it.height.toFloat() }
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = {
                        if (!locked) controlsVisible = !controlsVisible else locked = false
                    },
                    onDoubleTap = { off ->
                        if (!locked) {
                            val dir = if (off.x < scrW / 2) -1 else 1
                            seekBy(dir * 10)
                            controlsVisible = false
                        }
                    }
                )
            }
            .pointerInput(locked) {
                detectDragGestures(
                    onDragStart = { off ->
                        if (locked) return@detectDragGestures
                        mode = null
                        startX = off.x
                        startY = off.y
                        lastX = off.x
                        startPosMs = player.currentPosition.toFloat()
                        startVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                        startBri = activity?.window?.attributes?.screenBrightness
                            ?.takeIf { it >= 0 } ?: 0.5f
                    },
                    onDrag = { change, _ ->
                        if (locked) return@detectDragGestures
                        change.consume()
                        val x = change.position.x
                        val y = change.position.y
                        if (mode == null) {
                            val dx = abs(x - startX)
                            val dy = abs(y - startY)
                            if (dx > 24 || dy > 24) {
                                mode = when {
                                    dx > dy -> "seek"
                                    startX < scrW / 2 -> "bri"
                                    else -> "vol"
                                }
                                controlsVisible = false
                            }
                        }
                        when (mode) {
                            "seek" -> {
                                val d = player.duration.takeIf { it > 0 }
                                    ?: return@detectDragGestures
                                val deltaFrac = (x - lastX) / scrW
                                val target =
                                    (startPosMs + deltaFrac * d).coerceIn(0f, d.toFloat())
                                player.seekTo(target.toLong())
                                showHud(Icons.Filled.FastForward,
                                    fmtTime(target.toLong()) + " / " + fmtTime(d))
                            }
                            "vol" -> {
                                val maxV = audioManager
                                    .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val frac = ((startY - y) / (scrH * 0.7f)).coerceIn(-1f, 1f)
                                val nv = (startVol + (frac * maxV).roundToInt())
                                    .coerceIn(0, maxV)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                                showHud(Icons.AutoMirrored.Filled.VolumeUp,
                                    "${nv * 100 / maxV}%")
                            }
                            "bri" -> {
                                val frac = ((startY - y) / (scrH * 0.7f)).coerceIn(-1f, 1f)
                                val nb = (startBri + frac * 0.9f).coerceIn(0.02f, 1f)
                                activity?.window?.let { w ->
                                    val attr = w.attributes
                                    attr.screenBrightness = nb
                                    w.attributes = attr
                                }
                                showHud(Icons.Filled.WbSunny, "${(nb * 100).roundToInt()}%")
                            }
                        }
                        lastX = x
                    },
                    onDragEnd = { mode = null },
                    onDragCancel = { mode = null },
                )
            }
    ) {
        // ── video ────────────────────────────────────────────────────
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            }
        )

        // ── subtitle ─────────────────────────────────────────────────
        if (showCC) {
            cueText?.let { txt ->
                Text(
                    txt,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (controlsVisible) 150.dp else 48.dp)
                        .padding(horizontal = 32.dp)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }

        // ── double-tap flash ─────────────────────────────────────────
        if (flashSide != 0) {
            Box(
                modifier = Modifier
                    .align(if (flashSide < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(120.dp)
                    .background(Color.White.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (flashSide < 0) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                        null, tint = Color.White, modifier = Modifier.size(34.dp)
                    )
                    Text(
                        (if (flashSide < 0) "−" else "+") + "10s",
                        color = Color.White, style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        // ── gesture HUD pill ─────────────────────────────────────────
        if (hudVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                hudIcon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                Text(hudText, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }

        // ── lock badge ───────────────────────────────────────────────
        if (locked) {
            IconButton(
                onClick = { locked = false },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(14.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Filled.Lock, null, tint = Teal)
            }
        }

        // ── controls overlay ─────────────────────────────────────────
        if (controlsVisible && !locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(130.dp)
                    .background(Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                }
                Text(
                    title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    speeds[speedIdx].toString() + "×",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                IconButton(onClick = { locked = true }) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White)
                }
            }

            IconButton(
                onClick = { if (player.isPlaying) player.pause() else player.play() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
                    .background(Color.White.copy(alpha = 0.14f), CircleShape)
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    null, tint = Color.White, modifier = Modifier.size(40.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmtTime(player.currentPosition), color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = positionSec.coerceIn(0f, durationSec.coerceAtLeast(1f)),
                        onValueChange = { player.seekTo((it * 1000).toLong()) },
                        valueRange = 0f..durationSec.coerceAtLeast(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Accent,
                            inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(fmtTime(durationSec.toLong()), color = Color.White,
                        style = MaterialTheme.typography.labelSmall)
                }

                Row(modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ClosedCaption, null,
                        tint = if (showCC) Teal else Color.White.copy(alpha = 0.35f),
                        modifier = Modifier.size(18.dp))
                    Text(
                        title.substringAfterLast('/').substringBeforeLast('.'),
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp)
                    )
                    IconButton(onClick = { showCC = !showCC }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.ClosedCaption, null,
                            tint = if (showCC) Color.White else Color.White.copy(alpha = 0.35f))
                    }
                    IconButton(onClick = {
                        speedIdx = (speedIdx + 1) % speeds.size
                        player.setPlaybackSpeed(speeds[speedIdx])
                    }, modifier = Modifier.size(34.dp)) {
                        Text(speeds[speedIdx].toString() + "×", color = Color.White,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { /* sleep timer v2 */ }, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Filled.Bedtime, null, tint = Color.White)
                    }
                }
            }
        }
    }
}

