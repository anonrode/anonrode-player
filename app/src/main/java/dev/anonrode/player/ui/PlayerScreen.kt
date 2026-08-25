package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.anonrode.player.PlayerPrefs
import dev.anonrode.player.core.media.log.AppLog
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// ── MX Player palette ──────────────────────────────────────────────────
private val MxGreen = Color(0xFF009103)
private val MxPanel = Color(0xFF1C1C24)
private val MxMenuDivider = Color(0xFF33333B)

/** Subtitle drag safe margins, as fractions of the stage (box center). */
private const val SUB_X_MIN = 0.06f
private const val SUB_X_MAX = 0.94f
private const val SUB_Y_MIN = 0.12f
private const val SUB_Y_MAX = 0.90f
private const val SUB_DEFAULT_X = 0.5f
private const val SUB_DEFAULT_Y = 0.66f

/** Show the Next-Episode shortcut pill within this many seconds of the end. */
private const val NEXT_BUTTON_WINDOW_SEC = 30f

private fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** MX-style pill label: "1X", "1.25X", "1.5X", "2X". */
private fun speedLabel(sp: Float): String =
    if (sp % 1f == 0f) "${sp.toInt()}X" else "${sp}X"

/** Options offered by the Sleep-timer dropdown. [minutes] < 0 = end of episode. */
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

/** 8-way offsets for the black subtitle outline. */
private val SubtitleOutlineOffsets = listOf(
    -2f to -2f, -2f to 0f, -2f to 2f,
    0f to -2f, 0f to 2f,
    2f to -2f, 2f to 0f, 2f to 2f,
)

/* ── dock button visual language ──────────────────────────────────────────
 * Two distinct button families live in the bottom transport:
 *
 * 1. Time-seek ±10s — SQUARED 40×40 pill, brand-green tint, label baked
 *    into the icon. The square silhouette + green-tint background is
 *    intentionally loud; the eye is trained to read "this is a time
 *    action, not an episode action".
 *
 * 2. Episode-jump ‹ep / ep› — ROUND 44dp ghost, full-white double-arrow.
 *    Same silhouette as the play button, so they read as "transport
 *    navigation" rather than "time navigation".
 *
 * Spacing (14dp) between the two families makes them read as separate
 * groups even on a quick glance.
 * ------------------------------------------------------------------------- */

private enum class TimeSeekDirection { BACK, FORWARD }
private enum class EpisodeJumpDirection { PREVIOUS, NEXT }

@Composable
private fun TimeSeekButton(
    direction: TimeSeekDirection,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MxGreen.copy(alpha = 0.22f))
            .border(1.dp, MxGreen.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = true, color = MxGreen),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = if (direction == TimeSeekDirection.BACK) "«" else "»",
                color = MxGreen,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
            )
            Text(
                text = "10s",
                color = MxGreen,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun EpisodeJumpButton(
    direction: EpisodeJumpDirection,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = true, color = Color.White),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (direction == EpisodeJumpDirection.PREVIOUS) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous episode",
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next episode",
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/**
 * MX subtitle look: bold white text with a black 8-way outline, no
 * background box, centered, at most two lines.
 */
@Composable
private fun OutlinedSubtitleText(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        SubtitleOutlineOffsets.forEach { (dx, dy) ->
            Text(
                text,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 30.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(dx.dp, dy.dp),
            )
        }
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            lineHeight = 30.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Full-bleed MX-style player: top row (back, title, audio/CC/HW/overflow),
 * quick-action row (equalizer, cast, headphones, speaker, speed pill,
 * chevron), and a bottom block (lock · ⏪10 · prev · BIG play · next ·
 * ⏩10 · PiP · aspect) over gradient scrims. Auto-hide while playing,
 * double-tap ±10s with flash, left/right vertical swipe = brightness/volume
 * with HUD pill, horizontal swipe = live seek.
 *
 * The subtitle cue is MX-outlined (bold + black outline, no box) and can be
 * long-press dragged anywhere; its position persists per video with a
 * global default fallback (see [PlayerPrefs]). The overflow menu hosts the
 * sleep timer, aspect cycling, and rotation lock; the resize button cycles
 * FIT → CROP → STR. While in PiP ([isPipMode]) every overlay (controls,
 * subtitles, badges, diagnostics) hides.
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
    /** Stable per-video id (content uri) for per-video preferences. */
    mediaId: String = "",
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
    var menuOpen by remember { mutableStateOf(false) }
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
                // Re-assert the chosen speed when a new media item is ready.
                if (playbackState == Player.STATE_READY) {
                    player.setPlaybackSpeed(speeds[speedIdx])
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

    fun toast(msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun selectSleep(opt: SleepOption) {
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

    fun cycleSpeed() {
        speedIdx = (speedIdx + 1) % speeds.size
        val sp = speeds[speedIdx]
        player.setPlaybackSpeed(sp)
        onSpeedChanged(sp)
    }

    fun cycleZoom() {
        zoomIdx = (zoomIdx + 1) % ZoomModes.size
        showHud(Icons.Filled.AspectRatio, ZoomModes[zoomIdx].abbreviation)
    }

    fun seekBy(sec: Int) {
        val d = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((player.currentPosition + sec * 1000L).coerceIn(0L, d))
        flashSide = if (sec < 0) -1 else 1
        view.postDelayed({ flashSide = 0 }, 420)
    }

    LaunchedEffect(controlsVisible, isPlaying, locked, menuOpen) {
        if (controlsVisible && isPlaying && !locked && !menuOpen) {
            kotlinx.coroutines.delay(3500)
            controlsVisible = false
        }
    }

    var scrW by remember { mutableFloatStateOf(1000f) }
    var scrH by remember { mutableFloatStateOf(1000f) }

    // Subtitle placement (box center as stage fractions) + drag state.
    var subX by remember { mutableFloatStateOf(SUB_DEFAULT_X) }
    var subY by remember { mutableFloatStateOf(SUB_DEFAULT_Y) }
    var subDragging by remember { mutableStateOf(false) }

    // Restore the saved position for this video (global default fallback)
    // whenever the media item changes.
    LaunchedEffect(mediaId) {
        val saved = PlayerPrefs.subtitlePosition(context, mediaId)
        subX = saved?.first ?: SUB_DEFAULT_X
        subY = saved?.second ?: SUB_DEFAULT_Y
    }

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

        // ── subtitle: MX outline, long-press draggable ───────────────
        if (showCC && !isPipMode) {
            cueText?.let { txt ->
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset {
                            IntOffset(
                                ((subX * scrW) - scrW / 2f).roundToInt(),
                                ((subY * scrH) - scrH / 2f).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            val s = if (subDragging) 1.08f else 1f
                            scaleX = s
                            scaleY = s
                            alpha = if (subDragging) 0.9f else 1f
                        }
                        .pointerInput(showCC) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { subDragging = true },
                                onDrag = { change, amount ->
                                    change.consume()
                                    subX = (subX + amount.x / scrW).coerceIn(SUB_X_MIN, SUB_X_MAX)
                                    subY = (subY + amount.y / scrH).coerceIn(SUB_Y_MIN, SUB_Y_MAX)
                                },
                                onDragEnd = {
                                    subDragging = false
                                    PlayerPrefs.saveSubtitlePosition(context, mediaId, subX, subY)
                                },
                                onDragCancel = { subDragging = false },
                            )
                        }
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OutlinedSubtitleText(txt)
                }
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
                Icon(Icons.Filled.Lock, null, tint = MxGreen)
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
            // ── top row + quick row over a gradient scrim ────────────
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
                    .padding(bottom = 18.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    )
                    IconButton(onClick = { toast("Audio track") }) {
                        Icon(Icons.Filled.MusicNote,
                            contentDescription = "Audio track", tint = Color.White)
                    }
                    IconButton(onClick = { showCC = !showCC }) {
                        Icon(
                            Icons.Filled.ClosedCaption,
                            contentDescription = if (showCC) "Subtitles on" else "Subtitles off",
                            tint = if (showCC) MxGreen else Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable { toast("Decoder: HW") },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "HW", color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = !menuOpen }) {
                            Icon(Icons.Filled.MoreVert,
                                contentDescription = "More options", tint = Color.White)
                        }
                        // ── overflow menu: subtitles / sleep / aspect / lock ──
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = MxPanel,
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (showCC) "Subtitles: On" else "Subtitles: Off",
                                        color = Color.White)
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.ClosedCaption, null,
                                        tint = if (showCC) MxGreen
                                        else Color.White.copy(alpha = 0.7f))
                                },
                                onClick = { showCC = !showCC; menuOpen = false }
                            )
                            HorizontalDivider(color = MxMenuDivider)
                            DropdownMenuItem(
                                text = {
                                    Text("Sleep timer",
                                        color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                                },
                                onClick = {},
                                enabled = false
                            )
                            SleepOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt.label, color = Color.White) },
                                    leadingIcon = {
                                        if (isSleepSelected(opt)) {
                                            Icon(Icons.Filled.Check, null,
                                                tint = MxGreen, modifier = Modifier.size(18.dp))
                                        }
                                    },
                                    onClick = { selectSleep(opt); menuOpen = false }
                                )
                            }
                            HorizontalDivider(color = MxMenuDivider)
                            DropdownMenuItem(
                                text = {
                                    Text("Aspect ratio: " + ZoomModes[zoomIdx].abbreviation,
                                        color = Color.White)
                                },
                                onClick = { cycleZoom() }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text("Rotation lock: " +
                                        if (rotationLocked) "On" else "Off", color = Color.White)
                                },
                                onClick = { rotationLocked = !rotationLocked }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { toast("Equalizer") }) {
                        Icon(Icons.Filled.Equalizer, contentDescription = "Equalizer",
                            tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { toast("Cast") }) {
                        Icon(Icons.Filled.Cast, contentDescription = "Cast",
                            tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { toast("Headphone mode") }) {
                        Icon(Icons.Filled.Headphones, contentDescription = "Headphones",
                            tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { toast("Speaker") }) {
                        Icon(Icons.Filled.Speaker, contentDescription = "Audio device",
                            tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .height(38.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MxGreen)
                            .clickable { cycleSpeed() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(speedLabel(speeds[speedIdx]), color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    IconButton(onClick = { menuOpen = !menuOpen }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "More",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp))
                    }
                }
            }

        // ── bottom: times + seekbar + transport over a scrim ──────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fmtTime(player.currentPosition),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.widthIn(min = 48.dp)
                )
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
                        activeTrackColor = MxGreen,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text(
                    fmtTime((durationSec - positionSec).coerceAtLeast(0f).toLong()),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 48.dp)
                )
            }
            // ── dock controls ──
            //
            // Visual language (intentional, do not change without UX review):
            //   * TIME-SEEK ±10s   =  SQUARED pill, 36×36, green-tint background,
            //                        label "10s" baked into the icon
            //   * EPISODE-JUMP ‹‹ ›› =  ROUND ghost 44dp, pure-white arrows,
            //                        disabled state = 35% alpha
            //   * Spacing: 14dp gap separates the time-seek group from the
            //     episode-jump group, with the BIG PLAY in the middle. The eye
            //     locks onto the play button and the two pairs read as
            //     "near: time / far: episode" with one extra row of breathing
            //     room between them — a mis-tap now needs an aimed reach.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = 4.dp)
            ) {
                // Left rail: lock
                IconButton(
                    onClick = { locked = true },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = "Lock controls",
                        tint = if (locked) MxGreen else Color.White,
                    )
                }

                // Centre transport: time-seek 10s | ‹ep | ▶ | ep› | 10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // ── time-seek group (LEFT side) ──
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TimeSeekButton(
                            direction = TimeSeekDirection.BACK,
                            onClick = { seekBy(-10) },
                        )
                    }
                    // 14dp divider gap between time-seek and episode-jump groups
                    Spacer(Modifier.width(14.dp))
                    // ── episode-jump group ──
                    EpisodeJumpButton(
                        direction = EpisodeJumpDirection.PREVIOUS,
                        enabled = hasPreviousEpisode,
                        onClick = onPlayPrevious,
                    )
                    Spacer(Modifier.width(6.dp))
                    // ── BIG play / pause (unchanged) ──
                    IconButton(
                        onClick = {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                        modifier = Modifier
                            .size(60.dp)
                            .border(2.dp, Color.White, CircleShape)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    EpisodeJumpButton(
                        direction = EpisodeJumpDirection.NEXT,
                        enabled = hasNextEpisode,
                        onClick = onPlayNext,
                    )
                    Spacer(Modifier.width(14.dp))
                    // ── time-seek group (RIGHT side) ──
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TimeSeekButton(
                            direction = TimeSeekDirection.FORWARD,
                            onClick = { seekBy(10) },
                        )
                    }
                }

                // Right rail: PiP + aspect
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = onEnterPip) {
                        Icon(
                            Icons.Filled.PictureInPictureAlt,
                            contentDescription = "Picture-in-picture",
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = { cycleZoom() }) {
                        Icon(
                            Icons.Filled.AspectRatio,
                            contentDescription = "Aspect ratio",
                            tint = Color.White,
                        )
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
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(20.dp))
                    .clickable { onPlayNext() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = "Next episode",
                    tint = MxGreen,
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
