package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.media3.ui.AspectRatioFrameLayout

/**
 * Fire a haptic blip on click. Default = light tap (KEYBOARD_TAP).
 * The single most important piece of "feels like an app" polish — without
 * it every button click is silent and the whole player feels like a
 * webpage. Uses [View.performHapticFeedback] which is the
 * platform-recommended path (respects user system settings, no
 * permission needed).
 */
fun View.haptic(
    type: Int = HapticFeedbackConstants.KEYBOARD_TAP,
) {
    try { performHapticFeedback(type) } catch (_: Throwable) { /* devices without haptics */ }
}

// ── Player overlay palette ─────────────────────────────────────────────
// The video overlay (top bar, dock, sync popover) is intentionally dark and
// high-contrast on every skin — it sits on top of the video frame and must
// stay readable. The skin's accent still bleeds through (TimeSeek pill,
// HW chip, speed pill, SYNCED chip) but the scrims and panel stay dark.
//
// `MxGreen` is the default accent (MX GREEN skin) kept for parity with the
// pre-split file; the live accent is resolved via [rememberSkinPalette] in
// PlayerScreen and passed down.
internal val MxGreen = Color(0xFF00E676)
internal val MxPanel = Color(0xFF1C1C24)
internal val MxMenuDivider = Color(0xFF33333B)

/** Subtitle drag safe margins, as fractions of the stage (box center). */
internal const val SUB_X_MIN = 0.06f
internal const val SUB_X_MAX = 0.94f
internal const val SUB_Y_MIN = 0.12f
internal const val SUB_Y_MAX = 0.90f
internal const val SUB_DEFAULT_X = 0.5f
internal const val SUB_DEFAULT_Y = 0.66f

/** Show the Next-Episode shortcut pill within this many seconds of the end. */
internal const val NEXT_BUTTON_WINDOW_SEC = 30f

/** Playback rate applied while the hold-to-boost speed gesture is engaged. */
internal const val BOOST_SPEED = 2f

internal fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** MX-style pill label: "1X", "1.25X", "1.5X", "2X". */
internal fun speedLabel(sp: Float): String =
    if (sp % 1f == 0f) "${sp.toInt()}X" else "${sp}X"

/** Options offered by the Sleep-timer dropdown. [minutes] < 0 = end of episode. */
internal data class SleepOption(val label: String, val minutes: Int)

internal val SleepOptions = listOf(
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
 * PlayerView's AspectRatioFrameLayout constants. When [forcedAspect] is
 * non-null the playback surface is letterboxed to that ratio and stretched
 * to fill it (RESIZE_MODE_FILL inside an aspect-locked container), which is
 * how a "force 16:9 / 4:3" toggle behaves in mainstream players.
 */
internal data class ZoomMode(
    val abbreviation: String,
    val resizeMode: Int,
    val forcedAspect: Float? = null,
)

internal val ZoomModes = listOf(
    ZoomMode("FIT", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    ZoomMode("CROP", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    ZoomMode("STR", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZoomMode("16:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 16f / 9f),
    ZoomMode("4:3", AspectRatioFrameLayout.RESIZE_MODE_FILL, 4f / 3f),
)
