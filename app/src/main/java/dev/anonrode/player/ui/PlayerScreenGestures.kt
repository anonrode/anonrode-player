package dev.anonrode.player.ui

import android.media.AudioManager
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player's gesture layer, extracted 1:1 from PlayerScreen's root Box
 * modifier chain. Detector order and pointerInput keys are unchanged:
 *
 *  1. pinch-to-zoom (two fingers) — keyed (locked, isPipMode, pinchZoomEnabled)
 *  2. tap / double-tap / long-press — keyed (locked, isPipMode)
 *  3. vertical / horizontal drag    — keyed (locked, isPipMode)
 *  4. hold-to-2× speed boost        — keyed (locked, isPipMode)
 *
 * The lambdas read state through the remembered holders on [actions]
 * (live reads, exactly like the pre-split `by remember` delegates), while
 * plain values ([isPipMode], the gesture setting gates, the drag player
 * snapshot on [actions]) are frozen at block-restart time — the same
 * capture semantics the inline modifiers had.
 */
@UnstableApi
internal fun Modifier.playerGestureLayer(
    actions: PlayerScreenActions,
    isPipMode: Boolean,
    pinchZoomEnabled: Boolean,
    doubleTapSeekEnabled: Boolean,
    swipeToSeekEnabled: Boolean,
    volumeGestureEnabled: Boolean,
    brightnessGestureEnabled: Boolean,
): Modifier {
    val ui = actions.ui
    val gestures = actions.gestures
    return this
        // ── pinch-to-zoom (two fingers) ────────────────────────────
        // Placed first so that, once two pointers are down, it consumes
        // the gesture before the single-pointer tap/drag/boost detectors
        // can act on it. With a single pointer it never consumes, so the
        // existing gestures are untouched. Gated by the Pinch-zoom setting.
        .pointerInput(ui.locked.value, isPipMode, pinchZoomEnabled) {
            if (!pinchZoomEnabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var startDist = -1f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        val dist = (pressed[0].position - pressed[1].position)
                            .getDistance()
                        if (startDist < 0f) {
                            startDist = dist
                        } else if (startDist > 40f) {
                            val ratio = dist / startDist
                            if (ratio > 1.35f) {
                                actions.view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                                actions.zoomBy(+1)
                                startDist = dist
                            } else if (ratio < 0.74f) {
                                actions.view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                                actions.zoomBy(-1)
                                startDist = dist
                            }
                        }
                        // Two fingers down: swallow the event so seek /
                        // volume / brightness drags don't also fire.
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .pointerInput(ui.locked.value, isPipMode) {
            detectTapGestures(
                onTap = {
                    if (isPipMode) return@detectTapGestures
                    if (!ui.locked.value) {
                        ui.controlsVisible.value = !ui.controlsVisible.value
                    } else {
                        // Single-tap on locked screen: show the lock
                        // badge so the user knows the screen IS locked
                        // (mirrors the HTML mockup's "Controls locked"
                        // toast).
                        actions.showTransientToast("Locked — long-press to unlock")
                    }
                },
                onDoubleTap = { off ->
                    if (isPipMode || ui.locked.value || !doubleTapSeekEnabled) return@detectTapGestures
                    val dir = if (off.x < gestures.scrW.floatValue / 2) -1 else 1
                    actions.seekBy(dir * actions.seekIncrementSec)
                    ui.controlsVisible.value = false
                },
                onLongPress = {
                    if (isPipMode) return@detectTapGestures
                    if (ui.locked.value) {
                        ui.locked.value = false
                        ui.controlsVisible.value = true
                        actions.showTransientToast("Unlocked")
                    }
                },
            )
        }
        .pointerInput(ui.locked.value, isPipMode) {
            detectDragGestures(
                onDragStart = { off ->
                    if (ui.locked.value || isPipMode) return@detectDragGestures
                    gestures.mode.value = null
                    gestures.startX.floatValue = off.x
                    gestures.startY.floatValue = off.y
                    gestures.lastX.floatValue = off.x
                    gestures.startPosMs.floatValue = actions.livePlayer.currentPosition.toFloat()
                    gestures.startVol.intValue = actions.audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    gestures.startBri.floatValue = actions.activity?.window?.attributes?.screenBrightness
                        ?.takeIf { it >= 0 } ?: 0.5f
                },
                onDrag = { change, _ ->
                    if (ui.locked.value || isPipMode) return@detectDragGestures
                    change.consume()
                    val x = change.position.x
                    val y = change.position.y
                    if (gestures.mode.value == null) {
                        val dx = abs(x - gestures.startX.floatValue)
                        val dy = abs(y - gestures.startY.floatValue)
                        if (dx > 24 || dy > 24) {
                            // Each gesture family is individually gated
                            // by its PlayerSettings toggle; a disabled
                            // family simply never engages (mode stays
                            // null and the drag is a no-op).
                            val m = when {
                                dx > dy -> if (swipeToSeekEnabled) "seek" else null
                                gestures.startX.floatValue < gestures.scrW.floatValue / 2 ->
                                    if (brightnessGestureEnabled) "bri" else null
                                else -> if (volumeGestureEnabled) "vol" else null
                            }
                            gestures.mode.value = m
                            if (m != null) ui.controlsVisible.value = false
                        }
                    }
                    when (gestures.mode.value) {
                        "seek" -> {
                            val d = actions.livePlayer.duration.takeIf { it > 0 }
                                ?: return@detectDragGestures
                            // Cumulative delta from the gesture start —
                            // using the per-event increment (lastX) here
                            // re-anchored every tick and the scrub never
                            // moved away from the start position.
                            val deltaFrac = (x - gestures.startX.floatValue) / gestures.scrW.floatValue
                            val target =
                                (gestures.startPosMs.floatValue + deltaFrac * d).coerceIn(0f, d.toFloat())
                            actions.livePlayer.seekTo(target.toLong())
                            actions.showHud(Icons.Filled.FastForward,
                                fmtTime(target.toLong()) + " / " + fmtTime(d))
                        }
                        "vol" -> {
                            val maxV = actions.audioManager
                                .getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val frac = ((gestures.startY.floatValue - y) / (gestures.scrH.floatValue * 0.7f)).coerceIn(-1f, 1f)
                            val nv = (gestures.startVol.intValue + (frac * maxV).roundToInt())
                                .coerceIn(0, maxV)
                            actions.audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                            actions.showHud(Icons.AutoMirrored.Filled.VolumeUp,
                                "${nv * 100 / maxV}%")
                        }
                        "bri" -> {
                            val frac = ((gestures.startY.floatValue - y) / (gestures.scrH.floatValue * 0.7f)).coerceIn(-1f, 1f)
                            val nb = (gestures.startBri.floatValue + frac * 0.9f).coerceIn(0.02f, 1f)
                            actions.activity?.window?.let { w ->
                                val attr = w.attributes
                                attr.screenBrightness = nb
                                w.attributes = attr
                            }
                            actions.showHud(Icons.Filled.WbSunny, "${(nb * 100).roundToInt()}%")
                        }
                    }
                    gestures.lastX.floatValue = x
                },
                onDragEnd = { gestures.mode.value = null },
                onDragCancel = { gestures.mode.value = null },
            )
        }
        // Hold-to-2× speed boost (MX Player / YouTube style): keep a
        // finger pressed on the video and playback jumps to 2× after
        // a long-press; lifting the finger restores whatever speed
        // was active before the hold. detectTapGestures' onLongPress
        // has no release callback, so the hold lives in its own
        // detector: requireUnconsumed = false sees the down without
        // stealing it, and nothing is consumed unless the boost
        // actually engages — taps, double-taps and drags keep working.
        .pointerInput(ui.locked.value, isPipMode) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Locked / PiP / paused / already boosting: leave the
                // pointer to the tap + drag detectors. Only boost while
                // playing so a paused video can't burst audio.
                if (ui.locked.value || isPipMode || ui.boostActive.value || !ui.isPlaying.value) {
                    return@awaitEachGesture
                }
                // Survive the long-press timeout with the finger down,
                // alone, and within touch slop: lifting early falls
                // through to the tap path, moving hands the pointer to
                // the drag detector, a second finger aborts.
                val activated = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var ok = true
                    while (ok) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        ok = when {
                            pressed.isEmpty() -> false // lifted early → tap
                            pressed.size > 1 -> false  // second finger → abort
                            else -> {
                                // Same pointer must survive: a lift+new
                                // down in one event batch is NOT a hold.
                                val p = pressed.first()
                                p.id == down.id &&
                                    (p.position - down.position)
                                        .getDistance() <= viewConfiguration.touchSlop
                            }
                        }
                    }
                    false
                } ?: true
                if (!activated) return@awaitEachGesture
                // Long-press survived — engage the boost.
                ui.boostActive.value = true
                actions.view.haptic(HapticFeedbackConstants.LONG_PRESS)
                (actions.engine?.player ?: actions.livePlayer).setPlaybackSpeed(BOOST_SPEED)
                actions.showHud(Icons.Filled.FastForward, "2× speed")
                try {
                    // Hold until the finger lifts; a second finger going
                    // down aborts the boost. Consume the changes so the
                    // drag detector can't start seeking mid-boost.
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val pressed = event.changes.filter { it.pressed }
                        val held = event.changes.any { it.id == down.id && it.pressed }
                        if (!held || pressed.isEmpty() || pressed.size > 1) break
                    }
                } finally {
                    // Restore exactly once per activation, to the user's
                    // chosen speed (NOT a hardcoded 1×). Read the player
                    // fresh off the engine: a decoder rebuild mid-hold
                    // swaps the ExoPlayer instance under us.
                    ui.boostActive.value = false
                    (actions.engine?.player ?: actions.livePlayer).setPlaybackSpeed(actions.speeds[actions.speedIdx.intValue])
                    actions.showHud(Icons.Filled.FastForward,
                        speedLabel(actions.speeds[actions.speedIdx.intValue]) + " speed")
                }
            }
        }
}
