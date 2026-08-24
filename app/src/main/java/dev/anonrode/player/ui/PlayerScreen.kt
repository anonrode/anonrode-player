package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.anonrode.player.core.media.log.AppLog
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val Accent = Color(0xFF6C63FF)
private val Teal = Color(0xFF00D4AA)

/** Show the Next-Episode shortcut pill within this many seconds of the end. */
private const val NEXT_BUTTON_WINDOW_SEC = 30f

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Short m:ss formatting for the sleep-timer badge (remaining minutes:seconds). */
private fun fmtCountdown(ms: Long): String {
    val s = ms / 1000
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/** Options offered by the Bedtime (sleep timer) dropdown. [minutes] < 0 = end of episode. */
private data class SleepOption(val label: String, val minutes: Int)

private val SleepOptions = listOf(
    SleepOption("Off", 0),
    SleepOption("5min", 5),
    SleepOption("10min", 10),
    SleepOption("15min", 15),
    SleepOption("30min", 30),
    SleepOption("60min", 60),
    SleepOption("End of episode", -1),
)

/**
 * Zoom modes cycled by the resize button; [resizeMode] maps directly onto
 * PlayerView's AspectRatioFrameLayout constants.
 */
private data class ZoomMode(val abbreviation: String, val resizeMode: Int)

private val ZoomModes = listOf(
    ZoomMode("FIT", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZoomMode("CROP", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    ZoomMode("STR", AspectRatioFrameLayout.RESIZE_MODE_FILL),
)

/**
 * Full-bleed player: gradient-scrim overlay controls, auto-hide while playing,
 * double-tap ±10s with flash, left/right vertical swipe = brightness/volume
 * with HUD pill, horizontal swipe = live seek. MX-style, per Section 3 spec.
 *
 * Extras: Bedtime dropdown arms a wall-clock sleep timer (or "end of
 * episode" pause), the resize button cycles FIT → CROP → STR, and a PiP
 * button delegates to the activity; while in PiP ([isPipMode]) every overlay
 * (controls, subtitles, badges, diagnostics) hides.
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
    initialSpeed: Float = 1f,
    onSpeedChanged: (Float) -> Unit = {},
    isPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    hasNextEpisode: Boolean = false,
    hasPreviousEpisode: Boolean = false,
    onPlayNext: () -> Unit = {},
    onPlayPrevious: () -> Unit = {},
    nextCountdownSec: Int = -1,
    onCancelNext: () -> Unit = {},
    onHoldAutoAdvance: () -> Unit = {},
    /** Clean display name of the next episode, for the Up Next surfaces. */
    upNextTitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val activity = context as? Activity

    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var locked by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var stateText by remember { mutableStateOf("IDLE") }
    var showCC by remember { mutableStateOf(true) }
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    // Keyed on initialSpeed so the button re-syncs when the activity restores
    // a different persisted speed (e.g. after an auto-advance episode switch).
    var speedIdx by remember(initialSpeed) {
        mutableIntStateOf(speeds.indexOfFirst { abs(it - initialSpeed) < 0.05f }.takeIf { it >= 0 } ?: 2)
    }

    var hudIcon by remember { mutableStateOf<ImageVector?>(null) }
    var hudText by remember { mutableStateOf("") }
    var hudVisible by remember { mutableStateOf(false) }
    var flashSide by remember { mutableIntStateOf(0) } // -1 left, +1 right, 0 none

    // ── feature state ────────────────────────────────────────────────
    // Sleep timer: wall-clock expiry so re-arming mid-countdown simply moves
    // the deadline. Null = no countdown armed.
    var sleepTimerEndMs by remember { mutableStateOf<Long?>(null) }
    /** True when armed for "end of episode" instead of a countdown. */
    var sleepAtEpisodeEnd by remember { mutableStateOf(false) }
    /** Last ticked remainder, purely for badge display. */
    var sleepRemainingMs by remember { mutableLongStateOf(0L) }
    /** Chosen dropdown entry, for the checkmark (reset to Off on fire). */
    var sleepSelection by remember { mutableStateOf(SleepOptions.first()) }
    var sleepMenuOpen by remember { mutableStateOf(false) }
    val sleepTimerActive = sleepTimerEndMs != null || sleepAtEpisodeEnd

    var rotationLocked by remember { mutableStateOf(false) }
    /** Index into [ZoomModes]: FIT → CROP → STR. */
    var zoomIdx by remember { mutableIntStateOf(0) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

    // Speed persistence: mirror the restored per-video speed into the player
    // whenever it changes (also applied explicitly by the host before each
    // episode; unconditional so a saved 1x resets a faster prior episode).
    LaunchedEffect(initialSpeed) {
        player.setPlaybackSpeed(initialSpeed)
    }

    // Apply the active zoom mode to the surface frame even if the PlayerView
    // was created before this index changed.
    LaunchedEffect(zoomIdx) {
        playerViewRef?.resizeMode = ZoomModes[zoomIdx].resizeMode
    }

    // Rotation lock: sensor landscape while engaged, full sensor otherwise.
    DisposableEffect(rotationLocked) {
        activity?.requestedOrientation = if (rotationLocked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        onDispose {
            // Leaving the screen always restores free rotation.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    // Sleep-timer countdown: checks every second; pause + clear past expiry.
    LaunchedEffect(sleepTimerEndMs) {
        while (sleepTimerEndMs != null) {
            delay(1000)
            val endMs = sleepTimerEndMs ?: break
            val remaining = endMs - System.currentTimeMillis()
            sleepRemainingMs = remaining.coerceAtLeast(0L)
            if (remaining <= 0L) {
                player.pause()
                sleepTimerEndMs = null
                sleepSelection = SleepOptions.first()
            }
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(p: Boolean) {
                isPlaying = p
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                AppLog.d("PLAYER", "state=" + playbackState)
                stateText = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?"
                }
                // "End of episode" sleep timer fires when playback finishes.
                if (playbackState == Player.STATE_ENDED && sleepAtEpisodeEnd) {
                    player.pause()
                    sleepAtEpisodeEnd = false
                    sleepSelection = SleepOptions.first()
                    // Tell the activity to hold auto-advance for this finish.
                    onHoldAutoAdvance()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMsg = error.errorCodeName + ": " + (error.cause?.message ?: error.message ?: "unknown")
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

    fun selectSleep(opt: SleepOption) {
        sleepMenuOpen = false
        when {
            opt.minutes > 0 -> {
                sleepAtEpisodeEnd = false
                sleepRemainingMs = opt.minutes * 60_000L
                sleepTimerEndMs = System.currentTimeMillis() + sleepRemainingMs
            }
            opt.minutes < 0 -> { // End of episode
                sleepTimerEndMs = null
                sleepAtEpisodeEnd = true
            }
            else -> { // Off
                sleepTimerEndMs = null
                sleepAtEpisodeEnd = false
            }
        }
        sleepSelection = opt
    }

    /** Checkmark condition for the dropdown entry matching the armed timer. */
    val isSleepSelected: (SleepOption) -> Boolean = { opt ->
        when {
            opt.minutes > 0 ->
                sleepTimerEndMs != null && !sleepAtEpisodeEnd && sleepSelection == opt
            opt.minutes < 0 -> sleepAtEpisodeEnd
            else -> !sleepTimerActive
        }
    }

    fun seekBy(sec: Int) {
        val d = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((player.currentPosition + sec * 1000L).coerceIn(0L, d))
        flashSide = if (sec < 0) -1 else 1
        view.postDelayed({ flashSide = 0 }, 420)
    }

    LaunchedEffect(controlsVisible, isPlaying, locked, sleepMenuOpen) {
        if (controlsVisible && isPlaying && !locked && !sleepMenuOpen) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }

    var scrW by remember { mutableFloatStateOf(1000f) }
    var scrH by remember { mutableFloatStateOf(1000f) }

    // Seekbar drag position in seconds; -1 = not dragging. Seeking is applied
    // once on release instead of firing player.seekTo() per pixel of drag.
    var localSeek by remember { mutableFloatStateOf(-1f) }

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
            .pointerInput(locked, isPipMode) {
                detectTapGestures(
                    onTap = {
                        if (isPipMode) return@detectTapGestures
                        if (!locked) controlsVisible = !controlsVisible else locked = false
                    },
                    onDoubleTap = { off ->
                        if (isPipMode || locked) return@detectTapGestures
                        val dir = if (off.x < scrW / 2) -1 else 1
                        seekBy(dir * 10)
                        controlsVisible = false
                    }
                )
            }
            .pointerInput(locked, isPipMode) {
                detectDragGestures(
                    onDragStart = { off ->
                        if (locked || isPipMode) return@detectDragGestures
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
                        if (locked || isPipMode) return@detectDragGestures
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
                    resizeMode = ZoomModes[zoomIdx].resizeMode
                }.also { playerViewRef = it }
            },
            update = { pv ->
                pv.resizeMode = ZoomModes[zoomIdx].resizeMode
            }
        )

        // ── subtitle ─────────────────────────────────────────────────
        if (showCC && !isPipMode) {
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
        if (flashSide != 0 && !isPipMode) {
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
        if (hudVisible && !isPipMode) {
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
        if (locked && !isPipMode) {
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

        // diagnostics overlay (temporary - engine debugging)
        if (!isPipMode && (errorMsg != null || stateText != "READY")) {
            val diag = buildString {
                errorMsg?.let { append("ERROR: ").append(it).append('\n') }
                append("state=").append(stateText)
                append(" pos=").append(player.currentPosition / 1000f).append("s")
                append(" video=").append(player.videoSize.width)
                    .append("x").append(player.videoSize.height)
            }
            Text(
                diag,
                color = Color(0xFFFFD166),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        // ── controls overlay (hidden entirely while in PiP) ──────────
        if (controlsVisible && !locked && !isPipMode) {
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
                IconButton(onClick = { rotationLocked = !rotationLocked }) {
                    Icon(
                        if (rotationLocked) Icons.Filled.ScreenLockPortrait
                        else Icons.Filled.ScreenRotation,
                        contentDescription = if (rotationLocked) "Rotation locked"
                        else "Rotation unlocked",
                        tint = if (rotationLocked) Teal else Color.White
                    )
                }
                IconButton(onClick = { locked = true }) {
                    Icon(Icons.Filled.Lock, null, tint = Color.White)
                }
            }

            // ── center transport: previous · play/pause · next ────────
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(26.dp)
            ) {
                if (hasPreviousEpisode) {
                    IconButton(
                        onClick = onPlayPrevious,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Previous episode",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                IconButton(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color.White.copy(alpha = 0.14f), CircleShape)
                ) {
                    Icon(
                        if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        null, tint = Color.White, modifier = Modifier.size(40.dp)
                    )
                }
                if (hasNextEpisode) {
                    IconButton(
                        onClick = onPlayNext,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = "Next episode",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
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
                        value = (if (localSeek >= 0f) localSeek else positionSec)
                            .coerceIn(0f, durationSec.coerceAtLeast(1f)),
                        onValueChange = { localSeek = it },
                        onValueChangeFinished = {
                            if (localSeek >= 0f) player.seekTo((localSeek * 1000).toLong())
                            localSeek = -1f
                        },
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
                    // ── zoom mode cycle: FIT → CROP → STR ─────────────
                    IconButton(
                        onClick = {
                            zoomIdx = (zoomIdx + 1) % ZoomModes.size
                            showHud(Icons.Filled.AspectRatio, ZoomModes[zoomIdx].abbreviation)
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Text(
                            ZoomModes[zoomIdx].abbreviation,
                            color = if (zoomIdx != 0) Color.White
                            else Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // ── picture-in-picture ────────────────────────────
                    IconButton(onClick = onEnterPip, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Filled.PictureInPictureAlt,
                            contentDescription = "Picture-in-picture",
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = {
                        speedIdx = (speedIdx + 1) % speeds.size
                        val sp = speeds[speedIdx]
                        player.setPlaybackSpeed(sp)
                        onSpeedChanged(sp)
                    }, modifier = Modifier.size(34.dp)) {
                        Text(speeds[speedIdx].toString() + "×", color = Color.White,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    // ── sleep timer ───────────────────────────────────
                    Box {
                        IconButton(
                            onClick = { sleepMenuOpen = true },
                            modifier = Modifier.size(34.dp)
                        ) {
                            BadgedBox(
                                badge = {
                                    if (sleepTimerActive) {
                                        Badge(containerColor = Accent) {
                                            Text(
                                                if (sleepAtEpisodeEnd) "END"
                                                else fmtCountdown(sleepRemainingMs),
                                                color = Color.White,
                                                fontSize = 9.sp,
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Bedtime,
                                    contentDescription = "Sleep timer",
                                    tint = if (sleepTimerActive) Teal else Color.White
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = sleepMenuOpen,
                            onDismissRequest = { sleepMenuOpen = false },
                            containerColor = Color(0xFF1C1C24),
                        ) {
                            SleepOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label, color = Color.White) },
                                    trailingIcon = {
                                        if (isSleepSelected(opt)) {
                                            Icon(Icons.Filled.Check, null,
                                                tint = Teal, modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = { selectSleep(opt) }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Up Next pill (final 30 s of an episode) ──────────────────
        if (!isPipMode && hasNextEpisode && durationSec > 0f &&
            durationSec - positionSec <= NEXT_BUTTON_WINDOW_SEC && nextCountdownSec < 0
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 110.dp)
                    .background(Accent.copy(alpha = 0.92f), RoundedCornerShape(20.dp))
                    .clickable { onPlayNext() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next episode",
                    tint = Color.White,
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

        // ── auto-advance countdown overlay ("Next episode in N...") ──
        if (!isPipMode && nextCountdownSec > 0) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Next episode in $nextCountdownSec...",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onCancelNext) { Text("Cancel") }
                    TextButton(onClick = onPlayNext) { Text("Play now") }
                }
            }
        }
    }
}
