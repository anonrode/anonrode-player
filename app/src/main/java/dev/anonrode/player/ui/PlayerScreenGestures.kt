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
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
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
                        actions.toggleHud()
                    } else {
                        // Single-tap on locked screen: show the lock
                        // scrim so the user knows the screen IS locked
                        ui.lockScrimVisible.value = true
                        actions.showTransientToast("Controls locked")
                    }
                },
                onDoubleTap = { off ->
                    if (isPipMode || ui.locked.value) return@detectTapGestures
                    val w = gestures.scrW.floatValue
                    val x = off.x
                    when {
                        x < w * 0.35f -> {
                            if (doubleTapSeekEnabled) actions.seekDelta(-actions.seekIncrementSec)
                        }
                        x > w * 0.65f -> {
                            if (doubleTapSeekEnabled) actions.seekDelta(actions.seekIncrementSec)
                        }
                        else -> {
                            actions.togglePlayPause()
                        }
                    }
                    ui.controlsVisible.value = false
                },
                onLongPress = {
                    if (isPipMode) return@detectTapGestures
                    if (ui.locked.value) {
                        actions.unlockControls()
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
                        if (dx > 20 || dy > 20) {
                            val scrW = gestures.scrW.floatValue
                            val m = when {
                                dy > 1.5f * dx -> {
                                    when {
                                        gestures.startX.floatValue <= scrW * 0.45f ->
                                            if (brightnessGestureEnabled) "bri" else null
                                        gestures.startX.floatValue >= scrW * 0.55f ->
                                            if (volumeGestureEnabled) "vol" else null
                                        else -> null
                                    }
                                }
                                dx > 1.5f * dy -> {
                                    if (swipeToSeekEnabled) "seek" else null
                                }
                                else -> null
                            }
                            gestures.mode.value = m
                            if (m != null) ui.controlsVisible.value = false
                        }
                    }
                    when (gestures.mode.value) {
                        "seek" -> {
                            val d = actions.livePlayer.duration.takeIf { it > 0 }
                                ?: return@detectDragGestures
                            val density = actions.view.resources.displayMetrics.density.coerceAtLeast(1f)
                            val deltaPx = x - gestures.startX.floatValue
                            val deltaDp = deltaPx / density
                            val absDp = abs(deltaDp)
                            val sign = if (deltaPx >= 0f) 1f else -1f
                            // Progressive scrub scaling: fine scrub for small movements (~1.5s per 10dp),
                            // smooth velocity acceleration beyond 50dp.
                            val deltaSec = if (absDp <= 50f) {
                                absDp * 0.15f
                            } else {
                                val excess = absDp - 50f
                                7.5f + excess * 0.15f + 0.008f * excess * excess
                            }
                            val deltaMs = sign * deltaSec * 1000f
                            val target = (gestures.startPosMs.floatValue + deltaMs).coerceIn(0f, d.toFloat())
                            gestures.pendingSeekMs.floatValue = target
                            val diffSec = ((target - gestures.startPosMs.floatValue) / 1000f).roundToInt()
                            val diffSign = if (diffSec >= 0) "+" else ""
                            actions.showHud(
                                if (deltaPx >= 0f) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                                "${fmtTime(target.toLong())} (${diffSign}${diffSec}s) / ${fmtTime(d)}"
                            )
                        }
                        "vol" -> {
                            val dy = gestures.startY.floatValue - y
                            actions.updateVolumeGesture(dy)
                        }
                        "bri" -> {
                            val dy = gestures.startY.floatValue - y
                            actions.updateBrightnessGesture(dy)
                        }
                    }
                    gestures.lastX.floatValue = x
                },
                onDragEnd = {
                    // Commit the ONE pending scrub seek (fast/keyframe seek —
                    // scrubbing is coarse navigation; the seekbar handles
                    // exact seeks). Same setSeekParameters pattern as seekBy.
                    if (gestures.mode.value == "seek" && gestures.pendingSeekMs.floatValue >= 0f) {
                        val target = gestures.pendingSeekMs.floatValue.toLong()
                        val p = actions.engine?.player ?: actions.livePlayer
                        (p as? ExoPlayer)?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                        p.seekTo(target)
                        actions.view.postDelayed({
                            ((actions.engine?.player ?: actions.livePlayer) as? ExoPlayer)
                                ?.setSeekParameters(SeekParameters.EXACT)
                        }, 500)
                        gestures.pendingSeekMs.floatValue = -1f
                    }
                    gestures.mode.value = null
                },
                onDragCancel = { gestures.mode.value = null },
            )
        }
        // Hold-to-2× speed boost: keep a
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
                // Long-press survived — engage the rock-solid 2.0x speed boost.
                actions.startSpeedBoost()
                try {
                    // Hold until the finger lifts; a second finger going
                    // down aborts the boost. Consume changes so no other gesture fires.
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val pressed = event.changes.filter { it.pressed }
                        val heldPointer = event.changes.firstOrNull { it.id == down.id && it.pressed }
                        if (heldPointer == null || pressed.isEmpty() || pressed.size > 1) break
                    }
                } finally {
                    actions.stopSpeedBoost()
                }
            }
        }
}
