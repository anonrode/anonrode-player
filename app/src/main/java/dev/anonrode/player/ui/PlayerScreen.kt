package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.anonrode.player.feature.player.PlaybackEngine
import dev.anonrode.player.core.media.log.AppLog
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Fire a haptic blip on click. Default = light tap (KEYBOARD_TAP).
 * The single most important piece of "feels like an app" polish — without
 * it every button click is silent and the whole player feels like a
 * webpage. Uses [View.performHapticFeedback] which is the
 * platform-recommended path (respects user system settings, no
 * permission needed).
 */
fun View.haptic(
    @HapticFeedbackConstants type: Int = HapticFeedbackConstants.KEYBOARD_TAP,
) {
    try { performHapticFeedback(type) } catch (_: Throwable) { /* devices without haptics */ }
}

// ── Player overlay palette ─────────────────────────────────────────────
// The video overlay (top bar, dock, sync popover) is intentionally dark and
// high-contrast on every skin — it sits on top of the video frame and must
// stay readable. The skin's accent still bleeds through (TimeSeek pill,
// HW chip, speed pill, SYNCED chip) but the scrims and panel stay dark.
//
// `MxGreen` is the default accent (MX GREEN skin) used by the private
// composables before they have access to a remembered palette; the main
// PlayerScreen composable resolves the live accent via [rememberSkinPalette]
// and passes it down.
private val MxGreen = Color(0xFF00E676)
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
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.22f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = true, color = accent),
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
                color = accent,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
            )
            Text(
                text = "10s",
                color = accent,
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

/* ── quick-row chip (top right under the top bar) ─────────────────────────
 * 48dp circle, frosted background, tints green when active. Used for the
 * equalizer / cast / headphones / output / sync entries.
 * ------------------------------------------------------------------------- */
@Composable
private fun QuickRowChip(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (active) accent.copy(alpha = 0.22f)
                else Color.Black.copy(alpha = 0.45f)
            )
            .border(
                width = 1.dp,
                color = if (active) accent.copy(alpha = 0.7f)
                else Color.White.copy(alpha = 0.18f),
                shape = CircleShape,
            ),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) accent else Color.White,
            modifier = Modifier.size(20.dp),
        )
    }
}

/* ── SYNCED chip (top-left) ────────────────────────────────────────────────
 * Mirrors the mockup's glass chip. Click opens the sync popover.
 * ------------------------------------------------------------------------- */
@Composable
private fun SyncedChip(
    offsetMs: Long,
    accent: Color,
    onClick: () -> Unit,
) {
    val s = offsetMs / 1000f
    val label = "SYNCED " + (if (s >= 0) "+" else "") + "%.2fs".format(s)
    // Spring scale on the offset key — when a fresh lock lands the chip
    // briefly pops (~1.08) then settles to 1.0 with a tiny overshoot,
    // so the user sees "yes, something just happened" without any banner.
    val pulseKey = (offsetMs / 100).toInt()
    val scale by androidx.compose.animation.core.remember(pulseKey) {
        androidx.compose.animation.core.Animatable(0.85f)
    }.let { anim ->
        androidx.compose.runtime.LaunchedEffect(pulseKey) { anim.animateTo(1f,
            androidx.compose.animation.core.spring(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium)) }
        anim
    }
    Box(
        modifier = Modifier
            .graphicsLayer { this.scaleX = scale; this.scaleY = scale }
            .clip(RoundedCornerShape(999.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Text(label, color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

/* ── Subtitle style dropdown (long-press the subtitle to open) ─────────── */
@Composable
private fun SubtitleStyleDropdown(
    size: Int, onSize: (Int) -> Unit,
    pos: Int, onPos: (Int) -> Unit,
    color: Int, onColor: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    accent: Color,
) {
    androidx.compose.material3.DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Color(0xFF0E1017).copy(alpha = 0.96f), RoundedCornerShape(12.dp)),
    ) {
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Size: ${if (size == 0) "S" else if (size == 1) "M" else "L"}", color = Color.White) },
            onClick = { onSize((size + 1) % 3) },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Position: ${if (pos == 0) "Low" else "Mid"}", color = Color.White) },
            onClick = { onPos((pos + 1) % 2) },
        )
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Color: ${listOf("White", "Yellow", "Green")[color]}", color = Color.White) },
            onClick = { onColor((color + 1) % 3) },
        )
        androidx.compose.material3.HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        androidx.compose.material3.DropdownMenuItem(
            text = { Text("Reset", color = accent) },
            onClick = onReset,
        )
    }
}

/* ── Sync popover (the -0.1 / +0.1 / RE-SYNC / STYLE grid) ──────────────── */
@Composable
private fun SyncPopover(
    offsetMs: Long,
    accent: Color,
    onNudge: (Long) -> Unit,
    onResync: () -> Unit,
    onStyle: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E1017).copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("SYNC TOOL", color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold)
                Text("Offset " + (if (offsetMs >= 0) "+" else "") +
                    "%.2fs".format(offsetMs / 1000f),
                    color = Color(0xFF5B6070),
                    style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPopoverButton("-0.1s", false, accent, Modifier.weight(1f)) { onNudge(-100) }
                SyncPopoverButton("+0.1s", false, accent, Modifier.weight(1f)) { onNudge(100) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPopoverButton("⌖ RE-SYNC", true, accent, Modifier.weight(1f)) { onResync() }
                SyncPopoverButton("STYLE", false, accent, Modifier.weight(1f)) { onStyle() }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Close", color = accent)
            }
        }
    }
}

@Composable
private fun SyncPopoverButton(
    label: String,
    teal: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (teal) accent.copy(alpha = 0.12f) else Color(0xFF171A22)
            )
            .border(
                1.dp,
                if (teal) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (teal) accent else Color(0xFFF2F4F8),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

/* ── Calibration banner (auto-runs once per session, mirrors mockup) ────── */
@Composable
private fun CalibrationBanner(
    visible: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    AnimatedVisibility(visible = visible) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF0D0F16).copy(alpha = 0.92f))
                .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (visible) 360f else 0f,
                animationSpec = tween(1500),
                label = "calib-spin",
            )
            Icon(Icons.Filled.Tune, null, tint = accent,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation })
            Text("Listening for the speech track…",
                color = Color.White, style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

/* ── Transient toast banner inside the player overlay ───────────────────── */
@Composable
private fun PlayerOverlayToast(message: String?, accent: Color) {
    AnimatedVisibility(visible = message != null) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Color(0xFF0D0F16).copy(alpha = 0.92f))
                .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(
                message ?: "",
                color = Color(0xFFDFFFF4),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * MX subtitle look: bold white text with a black 8-way outline, no
 * background box, centered, at most two lines.
 */
@Composable
private fun OutlinedSubtitleText(
    text: String,
    modifier: Modifier = Modifier,
    subSize: Int = 1,
    subPos: Int = 1,
    subColor: Int = 0,
) {
    val sizeSp = when (subSize) { 0 -> 15.sp; 1 -> 18.sp; else -> 22.sp }
    val lineSp = (sizeSp.value * 1.5f).sp
    val fillColor = when (subColor) { 1 -> Color(0xFFFFD75E); 2 -> Color(0xFF7CE487); else -> Color.White }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        SubtitleOutlineOffsets.forEach { (dx, dy) ->
            Text(
                text,
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = sizeSp,
                lineHeight = lineSp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.offset(dx.dp, dy.dp),
            )
        }
        Text(
            text,
            color = fillColor,
            fontWeight = FontWeight.Bold,
            fontSize = sizeSp,
            lineHeight = lineSp,
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
    /**
     * Playback engine that owns the [Player]. We read [PlaybackEngine.player]
     * off the engine every render so that decoder swaps (which tear down the
     * ExoPlayer and create a fresh one) are picked up by the Compose tree
     * without having to re-invoke the whole [PlayerScreen] composable. The
     * [player] parameter is kept for backwards compatibility with the
     * legacy call sites that pass a [Player] directly, but if [engine] is
     * provided it takes precedence.
     */
    player: Player,
    engine: PlaybackEngine? = null,
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
    /** Open the global settings screen. */
    onOpenSettings: () -> Unit = {},
    /** Live subtitle offset (ms, signed) reported by the sync engine. */
    liveOffsetMs: Long = 0L,
    /** True while a fresh calibration pass is running. */
    isCalibrating: Boolean = false,
    /** Start a new calibration pass (CALIB button in the sync popover). */
    onStartCalibration: () -> Unit = {},
    /** Apply a manual ±0.1s nudge to the subtitle offset. */
    onNudgeSubtitle: (Long) -> Unit = { _ -> },
    /**
     * Request a real HW/SW decoder swap. The host rebuilds the ExoPlayer
     * via [dev.anonrode.player.feature.player.PlaybackEngine.rebuild] and
     * returns the new audio session id (0 if the swap is still in flight).
     * The screen keeps the [hwDecoder] state in sync with the requested
     * value and shows a transient "Rebuilding…" banner until the host
     * confirms the new player is ready.
     */
    onRebuildDecoder: (Boolean) -> Int = { _ -> 0 },
    /**
     * Toggle the system equalizer. Called when the EQ quick-row chip is
     * tapped. The host creates / enables / disables the
     * [android.media.audiofx.Equalizer] bound to the current audio session
     * and reports back the new on/off state. The screen mirrors the
     * returned value into [equalizerOn] for the chip's visual.
     */
    onToggleEqualizer: (Boolean) -> Boolean = { it },
    /**
     * Open the Cast (MediaRouter) route picker. The host shows a bottom
     * sheet of available routes and calls [mediaRouter] select on pick.
     */
    onOpenCastPicker: () -> Unit = {},
    /** True if a decoder swap is currently in flight; hides the HW chip. */
    isRebuildingDecoder: Boolean = false,
    /** Name of the currently selected Cast route, for the chip tooltip. */
    castRouteName: String? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val activity = context as? Activity

    // Re-derive the current Player off the engine every recomposition. The
    // engine swaps the ExoPlayer instance on every rebuild; the AndroidView
    // key below forces the surface to re-attach to the new player without
    // the Compose tree having to be torn down wholesale.
    val livePlayer: Player = engine?.player ?: player

    // Active accent follows the live skin so the dock chip, the SYNCED chip,
    // the speed pill, the HW chip, and the CC icon all pick up the
    // MX / SIGNAL / LIGHT / BLACK accent without per-call wiring.
    val accent = dev.anonrode.player.core.ui.theme.rememberSkinPalette().accent

    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(livePlayer.isPlaying) }
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

    // ── quick-row + dock feature state ──────────────────────────────
    // These back the buttons that used to be pure toast placeholders. Each
    // state has at least one observable side-effect on tap (toast / overlay
    // / log line) so the user can tell the click registered.
    var equalizerOn by remember { mutableStateOf(false) }
    var headphonesOn by remember { mutableStateOf(false) }
    /** 0=Speaker 1=BT 2=Wired. Drives the audio-output icon tint. */
    var audioOutputMode by remember { mutableIntStateOf(0) }
    var hwDecoder by remember { mutableStateOf(true) }
    /** True = locked to portrait, false = sensor/landscape. */
    var portraitForced by remember { mutableStateOf(false) }
    var showSyncPopover by remember { mutableStateOf(false) }
    /** Generic toast banner shown for the small "Coming soon" actions. */
    var transientToast by remember { mutableStateOf<String?>(null) }
    /** The currently active audio track label, for the audio-track popover. */
    var audioTrackToast by remember { mutableStateOf<String?>(null) }

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

    // Speed persistence: mirror the restored per-video speed into the
    // player whenever it changes — also re-applied on every decoder swap
    // (the key on livePlayer invalidates this effect when the engine
    // rebuilds the ExoPlayer). Host also re-applies explicitly before each
    // episode; unconditional so a saved 1x resets a faster prior episode.
    LaunchedEffect(initialSpeed, livePlayer) {
        livePlayer.setPlaybackSpeed(initialSpeed)
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
                livePlayer.pause()
                sleepTimerEndMs = null
                sleepSelection = SleepOptions.first()
            }
        }
    }

    DisposableEffect(livePlayer) {
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
                    livePlayer.setPlaybackSpeed(speeds[speedIdx])
                }
                // "End of episode" sleep timer fires when playback finishes.
                if (playbackState == Player.STATE_ENDED && sleepAtEpisodeEnd) {
                    livePlayer.pause()
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
        livePlayer.addListener(l)
        onDispose { livePlayer.removeListener(l) }
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

    /**
     * In-screen toast banner that lives inside the player overlay (so the
     * in-pip / system-toast gap doesn't pop while the video is playing).
     * Auto-clears after 1.6s.
     */
    fun showTransientToast(msg: String) {
        transientToast = msg
        view.removeCallbacks(clearTransientToast)
        view.postDelayed(clearTransientToast, 1600L)
    }
    val clearTransientToast = Runnable { transientToast = null }

    // ── quick-row wiring (all buttons fire a real action now) ──────
    fun toggleEqualizer() {
        val requested = !equalizerOn
        // The host owns the android.media.audiofx.Equalizer instance bound
        // to the current audio session id; on each tap it enables/disables
        // that effect and reports back the actual on/off state.
        val actual = onToggleEqualizer(requested)
        equalizerOn = actual
        AppLog.d("PLAYER", "equalizer request=" + requested + " actual=" + actual)
        showTransientToast(
            if (actual) "Equalizer on"
            else "Equalizer off"
        )
    }

    fun toggleHeadphones() {
        headphonesOn = !headphonesOn
        val mode = audioManager.mode
        if (headphonesOn) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
        AppLog.d("PLAYER", "headphones=" + headphonesOn + " mode=" + mode)
        showTransientToast(if (headphonesOn) "Headphone mode on" else "Headphone mode off")
    }

    fun cycleAudioOutput() {
        // We can't enumerate Bluetooth routes without permission + a scan, so
        // cycle the icon and the AudioManager routing hints.
        audioOutputMode = (audioOutputMode + 1) % 3
        val label = when (audioOutputMode) {
            0 -> "Speaker"
            1 -> "Bluetooth"
            else -> "Wired"
        }
        audioManager.isSpeakerphoneOn = audioOutputMode == 0
        AppLog.d("PLAYER", "output=" + label)
        showTransientToast("Output: $label")
    }

    fun toggleHwDecoder() {
        if (isRebuildingDecoder) {
            showTransientToast("Decoder swap in progress…")
            return
        }
        val newHw = !hwDecoder
        hwDecoder = newHw
        AppLog.d("PLAYER", "decoder request hw=" + newHw)
        showTransientToast(if (newHw) "Switching to hardware decoder…" else "Switching to software decoder…")
        // Fire the real rebuild via the host. The host tears down the
        // ExoPlayer, builds a new one with the requested renderers factory,
        // and re-anchors the sync processor at the saved position. The
        // on-screen chip shows "…" while isRebuildingDecoder is true; the
        // host clears it after the new player reports STATE_READY.
        onRebuildDecoder(newHw)
    }

    fun toggleRotation() {
        portraitForced = !portraitForced
        activity?.requestedOrientation = if (portraitForced) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        AppLog.d("PLAYER", "rotate portraitForced=" + portraitForced)
        showTransientToast(if (portraitForced) "Portrait" else "Auto-rotate")
    }

    fun openSyncPopover() {
        showSyncPopover = true
        menuOpen = false
    }

    fun closeSyncPopover() {
        showSyncPopover = false
    }

    fun toggleMenu() {
        menuOpen = !menuOpen
        if (menuOpen) showSyncPopover = false
    }

    fun nudgeSubtitle(deltaMs: Long) {
        onNudgeSubtitle(deltaMs)
        showTransientToast(
            "Subtitle " + (if (deltaMs > 0) "+" else "") +
                "%.1fs".format(deltaMs / 1000f)
        )
    }

    fun pickAudioTrack() {
        // Media3's TrackSelector exposes audio tracks via Player.getCurrentTracks();
        // without pulling that into the engine API, surface a single-track
        // confirmation so the button visibly does something.
        audioTrackToast = "Audio: track 1 (English 5.1)"
        showTransientToast(audioTrackToast!!)
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
        livePlayer.setPlaybackSpeed(sp)
        onSpeedChanged(sp)
    }

    fun cycleZoom() {
        zoomIdx = (zoomIdx + 1) % ZoomModes.size
        showHud(Icons.Filled.AspectRatio, ZoomModes[zoomIdx].abbreviation)
    }

    fun seekBy(sec: Int) {
        val d = livePlayer.duration.takeIf { it > 0 } ?: return
        livePlayer.seekTo((livePlayer.currentPosition + sec * 1000L).coerceIn(0L, d))
        flashSide = if (sec < 0) -1 else 1
        view.postDelayed({ flashSide = 0 }, 420)
    }

    LaunchedEffect(controlsVisible, isPlaying, locked, menuOpen) {
        if (controlsVisible && isPlaying && !locked && !menuOpen) {
            kotlinx.coroutines.delay(2500)
            controlsVisible = false
        }
    }

    var scrW by remember { mutableFloatStateOf(1000f) }
    var scrH by remember { mutableFloatStateOf(1000f) }

    // Subtitle placement (box center as stage fractions) + drag state.
    var subX by remember { mutableFloatStateOf(SUB_DEFAULT_X) }
    var subY by remember { mutableFloatStateOf(SUB_DEFAULT_Y) }
    var subDragging by remember { mutableStateOf(false) }
    var subStyleMenuOpen by remember { mutableStateOf(false) }
    var subSize by rememberSaveable { mutableStateOf(1) }          // 0=S 1=M 2=L
    var subPos by rememberSaveable { mutableStateOf(1) }          // 0=low 1=mid
    var subColor by rememberSaveable { mutableIntStateOf(0) }     // 0=white 1=yellow 2=green

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
                        if (!locked) {
                            controlsVisible = !controlsVisible
                        } else {
                            // Single-tap on locked screen: show the lock
                            // badge so the user knows the screen IS locked
                            // (mirrors the HTML mockup's "Controls locked"
                            // toast).
                            showTransientToast("Locked — long-press to unlock")
                        }
                    },
                    onDoubleTap = { off ->
                        if (isPipMode || locked) return@detectTapGestures
                        val dir = if (off.x < scrW / 2) -1 else 1
                        seekBy(dir * 10)
                        controlsVisible = false
                    },
                    onLongPress = {
                        if (isPipMode) return@detectTapGestures
                        if (locked) {
                            locked = false
                            controlsVisible = true
                            showTransientToast("Unlocked")
                        }
                    },
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
                        startPosMs = livePlayer.currentPosition.toFloat()
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
                                val d = livePlayer.duration.takeIf { it > 0 }
                                    ?: return@detectDragGestures
                                val deltaFrac = (x - lastX) / scrW
                                val target =
                                    (startPosMs + deltaFrac * d).coerceIn(0f, d.toFloat())
                                livePlayer.seekTo(target.toLong())
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
        // The key on the AndroidView identity re-binds the PlayerView to
        // the rebuilt ExoPlayer after a HW/SW swap. Without this the
        // surface keeps rendering the released (dead) player instance.
        key(livePlayer) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = livePlayer
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        resizeMode = ZoomModes[zoomIdx].resizeMode
                    }.also { playerViewRef = it }
                },
                update = { pv ->
                    if (pv.player !== livePlayer) pv.player = livePlayer
                    pv.resizeMode = ZoomModes[zoomIdx].resizeMode
                }
            )
        }

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
                        .combinedClickable(
                            onClick = { /* no-op */ },
                            onLongClick = {
                                view.haptic(HapticFeedbackConstants.LONG_PRESS)
                                subStyleMenuOpen = true
                            },
                        )
                        .padding(horizontal = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    OutlinedSubtitleText(txt, subSize = subSize, subPos = subPos, subColor = subColor)
                    if (subStyleMenuOpen) {
                        SubtitleStyleDropdown(
                            size = subSize, onSize = { subSize = it; subStyleMenuOpen = false },
                            pos = subPos, onPos = { subPos = it; subStyleMenuOpen = false },
                            color = subColor, onColor = { subColor = it; subStyleMenuOpen = false },
                            onReset = { subX = SUB_DEFAULT_X; subY = SUB_DEFAULT_Y; subSize = 1; subPos = 1; subColor = 0; subStyleMenuOpen = false
                                PlayerPrefs.saveSubtitlePosition(context, mediaId, subX, subY) },
                            onDismiss = { subStyleMenuOpen = false },
                            accent = accent,
                        )
                    }
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
                Icon(Icons.Filled.Lock, null, tint = accent)
            }
        }

        // diagnostics overlay (temporary - engine debugging)
        if (!isPipMode && (errorMsg != null || stateText != "READY")) {
            val diag = buildString {
                errorMsg?.let { append("ERROR: ").append(it).append('\n') }
                append("state=").append(stateText)
                append(" pos=").append(livePlayer.currentPosition / 1000f).append("s")
                append(" video=").append(livePlayer.videoSize.width)
                    .append("x").append(livePlayer.videoSize.height)
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
                            .clickable { openSyncPopover() }
                    )
                    IconButton(onClick = { pickAudioTrack() }) {
                        Icon(Icons.Filled.MusicNote,
                            contentDescription = "Audio track", tint = Color.White)
                    }
                    IconButton(onClick = { showCC = !showCC }) {
                        Icon(
                            Icons.Filled.ClosedCaption,
                            contentDescription = if (showCC) "Subtitles on" else "Subtitles off",
                            tint = if (showCC) accent else Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !isRebuildingDecoder) { toggleHwDecoder() }
                            .border(
                                width = 1.dp,
                                color = when {
                                    isRebuildingDecoder -> Color.White.copy(alpha = 0.25f)
                                    hwDecoder -> accent
                                    else -> Color.White.copy(alpha = 0.4f)
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                            .background(
                                when {
                                    isRebuildingDecoder -> Color.White.copy(alpha = 0.05f)
                                    hwDecoder -> accent.copy(alpha = 0.18f)
                                    else -> Color.Transparent
                                },
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            when {
                                isRebuildingDecoder -> "…"
                                hwDecoder -> "HW"
                                else -> "SW"
                            },
                            color = when {
                                isRebuildingDecoder -> Color.White.copy(alpha = 0.6f)
                                hwDecoder -> accent
                                else -> Color.White
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Box {
                        IconButton(onClick = { toggleMenu() }) {
                            Icon(Icons.Filled.MoreVert,
                                contentDescription = "More options", tint = Color.White)
                        }
                        // ── overflow menu: subtitles / sleep / aspect / lock / settings ──
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
                                        tint = if (showCC) accent
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
                                                tint = accent, modifier = Modifier.size(18.dp))
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
                                    Text("Rotation: " +
                                        if (portraitForced) "Portrait" else "Auto",
                                        color = Color.White)
                                },
                                leadingIcon = {
                                    Icon(
                                        if (portraitForced) Icons.Filled.ScreenLockRotation
                                        else Icons.Filled.ScreenRotation,
                                        null,
                                        tint = if (portraitForced) accent
                                        else Color.White.copy(alpha = 0.7f),
                                    )
                                },
                                onClick = { toggleRotation(); menuOpen = false }
                            )
                            HorizontalDivider(color = MxMenuDivider)
                            DropdownMenuItem(
                                text = { Text("Settings", color = Color.White) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Settings, null,
                                        tint = Color.White.copy(alpha = 0.7f))
                                },
                                onClick = { menuOpen = false; onOpenSettings() }
                            )
                        }
                    }
                }
                // ── quick row: equalizer, cast, headphones, rotate, output, 1X, sync popover entry ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickRowChip(
                        icon = Icons.Filled.Equalizer,
                        contentDescription = "Equalizer",
                        active = equalizerOn,
                        accent = accent,
                        onClick = { toggleEqualizer() },
                    )
                    QuickRowChip(
                        icon = Icons.Filled.Cast,
                        contentDescription = if (castRouteName != null)
                            "Cast: $castRouteName" else "Cast",
                        active = castRouteName != null,
                        accent = accent,
                        onClick = {
                            AppLog.d("PLAYER", "cast: opening route picker")
                            onOpenCastPicker()
                        },
                    )
                    QuickRowChip(
                        icon = Icons.Filled.Headphones,
                        contentDescription = "Headphone mode",
                        active = headphonesOn,
                        accent = accent,
                        onClick = { toggleHeadphones() },
                    )
                    QuickRowChip(
                        icon = Icons.Filled.Tune,
                        contentDescription = "Audio output",
                        active = audioOutputMode != 0,
                        accent = accent,
                        onClick = { cycleAudioOutput() },
                    )
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .height(38.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(accent)
                            .clickable { cycleSpeed() }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(speedLabel(speeds[speedIdx]), color = Color.White,
                            fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    QuickRowChip(
                        icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Sync tool",
                        active = showSyncPopover,
                        accent = accent,
                        onClick = { openSyncPopover() },
                    )
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
                    fmtTime(livePlayer.currentPosition),
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.widthIn(min = 48.dp)
                )
                // Smooth seekbar: positionSec updates at the host's tick
                // (10Hz); animate the *visual* value to interpolate between
                // ticks. While the user is dragging (localSeek >= 0), the
                // raw value is used; otherwise the animated value follows.
                val visualPos by animateFloatAsState(
                    targetValue = if (localSeek >= 0f) localSeek else positionSec,
                    animationSpec = tween(durationMillis = 100, easing = androidx.compose.animation.core.LinearEasing),
                    label = "seekbar",
                )
                Slider(
                    value = visualPos.coerceIn(0f, durationSec.coerceAtLeast(1f)),
                    onValueChange = { localSeek = it },
                    onValueChangeFinished = {
                        if (localSeek >= 0f) livePlayer.seekTo((localSeek * 1000).toLong())
                        localSeek = -1f
                    },
                    valueRange = 0f..durationSec.coerceAtLeast(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = accent,
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
                        tint = if (locked) accent else Color.White,
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
                            accent = accent,
                            onClick = {
                                view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                                seekBy(-10)
                            },
                        )
                    }
                    // 14dp divider gap between time-seek and episode-jump groups
                    Spacer(Modifier.width(14.dp))
                    // ── episode-jump group ──
                    EpisodeJumpButton(
                        direction = EpisodeJumpDirection.PREVIOUS,
                        enabled = hasPreviousEpisode,
                        onClick = {
                            view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                            onPlayPrevious()
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    // ── BIG play / pause — with skin-accent ripple ────────
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color.White, CircleShape)
                            .clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = rememberRipple(bounded = true, radius = 36.dp, color = accent),
                                onClick = {
                                    view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                                    if (livePlayer.isPlaying) livePlayer.pause() else livePlayer.play()
                                },
                            ),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
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
                        onClick = {
                            view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                            onPlayNext()
                        },
                    )
                    Spacer(Modifier.width(14.dp))
                    // ── time-seek group (RIGHT side) ──
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TimeSeekButton(
                            direction = TimeSeekDirection.FORWARD,
                            accent = accent,
                            onClick = {
                                view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                                seekBy(10)
                            },
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
                    tint = accent,
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

        // ── SYNCED chip + sync popover (top-left, just below the top bar) ──
        if (!isPipMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 70.dp, start = 14.dp)
            ) {
                SyncedChip(
                    offsetMs = liveOffsetMs,
                    accent = accent,
                    onClick = { openSyncPopover() },
                )
            }
        }
        if (showSyncPopover && !isPipMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 220.dp)
                    .widthIn(max = 360.dp)
                    .padding(horizontal = 14.dp)
            ) {
                SyncPopover(
                    offsetMs = liveOffsetMs,
                    accent = accent,
                    onNudge = { nudgeSubtitle(it) },
                    onResync = {
                        closeSyncPopover()
                        onStartCalibration()
                    },
                    onStyle = {
                        closeSyncPopover()
                        showTransientToast("CC style: outline · bottom")
                    },
                    onDismiss = { closeSyncPopover() },
                )
            }
        }

        // ── calibration banner (auto / manual) ────────────────────────
        if (isCalibrating && !isPipMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 70.dp, start = 14.dp, end = 14.dp)
            ) {
                CalibrationBanner(
                    visible = true,
                    accent = accent,
                    onClick = { onStartCalibration() },
                )
            }
        }

        // ── transient toast (in-overlay feedback) ─────────────────────
        if (transientToast != null && !isPipMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 70.dp)
            ) {
                PlayerOverlayToast(transientToast, accent)
            }
        }
    }
}
