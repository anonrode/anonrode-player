package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Layer 1 Gesture Pipeline.
 * Enforces strict evaluation priority:
 * 0. Subtitle Bounds Yielding (`gestures.subBounds`)
 * 1. 2-Finger Pinch-to-Zoom (cycles aspect ratio with transient center badge)
 * 2. Touch-Only Hold-to-Boost on `PointerEventPass.Initial` (consumes events once activated so
 *    it never races `detectDragGestures` on `Main`; 8dp horizontal slide deadzone -> 34dp/milestone)
 * 3. Single-Tap / 3-Zone Double-Tap
 * 4. 2-Axis Drag (Left Brightness, Right 200% Volume+Boost, Horizontal Piecewise Quadratic Scrub)
 */
fun Modifier.playerGestureLayer(
    ui: PlayerUiState,
    gestures: GestureUiState,
    durationSecProvider: () -> Float,
    currentPositionMsProvider: () -> Float,
    isPipMode: Boolean = false,
    pinchZoomEnabled: Boolean = true,
    doubleTapSeekEnabled: Boolean = true,
    swipeToSeekEnabled: Boolean = true,
    volumeGestureEnabled: Boolean = true,
    brightnessGestureEnabled: Boolean = true,
    onToggleControls: () -> Unit,
    onDoubleTapSeek: (deltaSec: Int, isLeft: Boolean) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPreviewKeyFrameSeekMs: (targetMs: Long) -> Unit,
    onCommitExactSeekMs: (targetMs: Long) -> Unit,
    onCancelKeyframeSeek: () -> Unit,
    onApplyVolumeBoostPercent: (percent: Int) -> Unit,
    onApplyBrightnessFraction: (fraction: Float) -> Unit,
    onSetPlaybackSpeed: (speed: Float) -> Unit,
    onCycleZoomMode: (zoomOut: Boolean) -> Unit,
): Modifier = composed {
    val density = LocalDensity.current.density
    val view = LocalView.current

    fun isInsideSubtitle(offset: Offset): Boolean {
        if (!ui.showCC.value) return false
        val rect = gestures.subBounds.value
        return !rect.isEmpty && rect.contains(offset)
    }

    this
        .onSizeChanged { size ->
            gestures.scrW.floatValue = size.width.toFloat()
            gestures.scrH.floatValue = size.height.toFloat()
        }
        // 1. TWO-FINGER PINCH-TO-ZOOM
        .pointerInput(ui.locked.value, isPipMode, pinchZoomEnabled) {
            if (ui.locked.value || isPipMode || !pinchZoomEnabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (isInsideSubtitle(down.position)) return@awaitEachGesture
                var accZoom = 1f
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.size >= 2) {
                        val zoomChange = event.calculateZoom()
                        accZoom *= zoomChange
                        if (accZoom > 1.22f) {
                            onCycleZoomMode(false)
                            ui.aspectBadgeVisible.value = true
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            accZoom = 1f
                        } else if (accZoom < 0.80f) {
                            onCycleZoomMode(true)
                            ui.aspectBadgeVisible.value = true
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            accZoom = 1f
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        // 2. TOUCH-ONLY HOLD-TO-BOOST & 34dp DISCRETE MILESTONE SCRUB (PointerEventPass.Initial)
        .pointerInput(ui.locked.value, isPipMode) {
            if (ui.locked.value || isPipMode) return@pointerInput
            val stepPx = 34f * density
            val slideDeadzonePx = 8f * density
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Touch ||
                    !ui.isPlaying.value ||
                    ui.boostActive.value ||
                    isInsideSubtitle(down.position)
                ) {
                    return@awaitEachGesture
                }
                val startX = down.position.x

                val activated = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var ok = true
                    while (ok) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.filter { it.pressed }
                        ok = when {
                            pressed.isEmpty() -> false
                            pressed.size > 1 -> false
                            else -> {
                                val p = pressed.first()
                                p.id == down.id &&
                                    (p.position - down.position).getDistance() <= viewConfiguration.touchSlop
                            }
                        }
                    }
                    false
                } ?: true

                if (!activated || gestures.mode.value != GestureMode.NONE) {
                    return@awaitEachGesture
                }

                // Activate 2.0x Hold-to-Boost
                val savedSpeed = ui.currentPlaybackSpeed.floatValue
                val baseIndex = 6 // 2.0x in SpeedMilestones
                ui.boostActive.value = true
                ui.speedScrubHudVisible.value = true
                ui.discreteScrubIndex.intValue = baseIndex
                ui.currentScrubSpeed.floatValue = PlayerUiState.SpeedMilestones[baseIndex]
                onSetPlaybackSpeed(2.0f)
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                        val pressed = event.changes.filter { it.pressed }
                        val held = event.changes.firstOrNull { it.id == down.id && it.pressed }
                        if (held == null || pressed.isEmpty() || pressed.size > 1) break

                        val dx = held.position.x - startX
                        if (abs(dx) > slideDeadzonePx) {
                            val effectiveDx = if (dx > 0f) dx - slideDeadzonePx else dx + slideDeadzonePx
                            val indexStep = (effectiveDx / stepPx).roundToInt()
                            val newIdx = (baseIndex + indexStep)
                                .coerceIn(0, PlayerUiState.SpeedMilestones.lastIndex)
                            if (newIdx != ui.discreteScrubIndex.intValue) {
                                ui.discreteScrubIndex.intValue = newIdx
                                val newSpd = PlayerUiState.SpeedMilestones[newIdx]
                                ui.currentScrubSpeed.floatValue = newSpd
                                onSetPlaybackSpeed(newSpd)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                        }
                    }
                } finally {
                    ui.boostActive.value = false
                    ui.speedScrubHudVisible.value = false
                    onSetPlaybackSpeed(savedSpeed)
                }
            }
        }
        // 3. SINGLE TAP & 3-ZONE DOUBLE-TAP
        .pointerInput(ui.locked.value, isPipMode, doubleTapSeekEnabled) {
            if (isPipMode) return@pointerInput
            detectTapGestures(
                onTap = { offset ->
                    if (isInsideSubtitle(offset)) return@detectTapGestures
                    if (ui.locked.value) {
                        ui.lockScrimVisible.value = !ui.lockScrimVisible.value
                    } else {
                        ui.bumpInteraction()
                        if (ui.speedCardOpen.value) {
                            ui.speedCardOpen.value = false
                        } else {
                            onToggleControls()
                        }
                    }
                },
                onDoubleTap = { offset ->
                    if (ui.locked.value || isInsideSubtitle(offset)) return@detectTapGestures
                    ui.bumpInteraction()
                    val w = gestures.scrW.floatValue.coerceAtLeast(1f)
                    val step = ui.seekStepSec.intValue
                    when {
                        doubleTapSeekEnabled && offset.x < w * 0.35f -> {
                            gestures.doubleTapSide.intValue = -1
                            gestures.doubleTapAccumSec.intValue -= step
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onDoubleTapSeek(-step, true)
                        }
                        doubleTapSeekEnabled && offset.x > w * 0.65f -> {
                            gestures.doubleTapSide.intValue = 1
                            gestures.doubleTapAccumSec.intValue += step
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onDoubleTapSeek(step, false)
                        }
                        else -> {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onPlayPauseToggle()
                        }
                    }
                },
            )
        }
        // 4. TWO-AXIS DRAGS (Brightness, 200% Volume+Boost, Piecewise Quadratic Scrub)
        .pointerInput(ui.locked.value, isPipMode, swipeToSeekEnabled, volumeGestureEnabled, brightnessGestureEnabled) {
            if (ui.locked.value || isPipMode) return@pointerInput
            val topDeadZone = 52f * density
            val bottomDeadZone = 52f * density
            val sideDeadZone = 24f * density

            detectDragGestures(
                onDragStart = { off ->
                    val w = gestures.scrW.floatValue
                    val h = gestures.scrH.floatValue
                    if (ui.boostActive.value ||
                        isInsideSubtitle(off) ||
                        off.y < topDeadZone || off.y > h - bottomDeadZone ||
                        off.x < sideDeadZone || off.x > w - sideDeadZone
                    ) {
                        gestures.mode.value = GestureMode.NONE
                        return@detectDragGestures
                    }
                    gestures.startX.floatValue = off.x
                    gestures.startY.floatValue = off.y
                    gestures.lastX.floatValue = off.x
                    gestures.startPosMs.floatValue = currentPositionMsProvider()
                    gestures.startVolPercent.intValue = ui.volumeBoostPercent.intValue
                    gestures.startBri.floatValue = ui.screenBrightnessFraction.floatValue
                    gestures.mode.value = GestureMode.NONE
                },
                onDrag = { change, _ ->
                    if (ui.boostActive.value) return@detectDragGestures
                    val w = gestures.scrW.floatValue.coerceAtLeast(1f)
                    val h = gestures.scrH.floatValue.coerceAtLeast(1f)
                    val sx = gestures.startX.floatValue
                    val sy = gestures.startY.floatValue
                    if (sx < sideDeadZone || sx > w - sideDeadZone ||
                        sy < topDeadZone || sy > h - bottomDeadZone
                    ) {
                        return@detectDragGestures
                    }

                    val totalDx = change.position.x - sx
                    val totalDy = change.position.y - sy
                    val absDx = abs(totalDx)
                    val absDy = abs(totalDy)

                    if (gestures.mode.value == GestureMode.NONE) {
                        if (absDx < 18f && absDy < 18f) return@detectDragGestures
                        gestures.mode.value = when {
                            absDx > 1.5f * absDy -> {
                                if (swipeToSeekEnabled && !ui.boostActive.value && !ui.speedScrubHudVisible.value) {
                                    GestureMode.SEEK
                                } else {
                                    GestureMode.NONE
                                }
                            }
                            sx <= w * 0.45f && brightnessGestureEnabled -> GestureMode.BRIGHTNESS
                            sx >= w * 0.55f && volumeGestureEnabled -> GestureMode.VOLUME
                            else -> GestureMode.NONE
                        }
                    }

                    when (gestures.mode.value) {
                        GestureMode.SEEK -> {
                            change.consume()
                            val deltaDp = absDx / density
                            val sign = if (totalDx >= 0f) 1f else -1f
                            val deltaSec = if (deltaDp <= 50f) {
                                deltaDp * 0.15f
                            } else {
                                val excess = deltaDp - 50f
                                7.5f + (excess * 0.15f) + (0.008f * excess.pow(2))
                            }
                            val signedDeltaSec = sign * deltaSec
                            val durMs = (durationSecProvider() * 1000f).coerceAtLeast(1000f)
                            val targetMs = (gestures.startPosMs.floatValue + signedDeltaSec * 1000f)
                                .coerceIn(0f, durMs)
                            gestures.scrubDeltaSec.floatValue = signedDeltaSec
                            gestures.pendingSeekMs.floatValue = targetMs
                            ui.localSeek.floatValue = targetMs / 1000f
                            onPreviewKeyFrameSeekMs(targetMs.toLong())
                        }
                        GestureMode.BRIGHTNESS -> {
                            change.consume()
                            val dragSpan = h * 0.62f
                            val deltaFrac = -totalDy / dragSpan
                            val newBri = (gestures.startBri.floatValue + deltaFrac).coerceIn(0.02f, 1.0f)
                            ui.screenBrightnessFraction.floatValue = newBri
                            onApplyBrightnessFraction(newBri)
                        }
                        GestureMode.VOLUME -> {
                            change.consume()
                            val dragSpan = h * 0.65f
                            val deltaPct = ((-totalDy / dragSpan) * 200f).roundToInt()
                            val prevPct = ui.volumeBoostPercent.intValue
                            val newPct = (gestures.startVolPercent.intValue + deltaPct).coerceIn(0, 200)
                            if ((prevPct <= 100 && newPct > 100) || (prevPct > 100 && newPct <= 100) ||
                                (prevPct > 0 && newPct == 0)
                            ) {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            ui.volumeBoostPercent.intValue = newPct
                            onApplyVolumeBoostPercent(newPct)
                        }
                        else -> Unit
                    }
                },
                onDragEnd = {
                    if (gestures.mode.value == GestureMode.SEEK && gestures.pendingSeekMs.floatValue >= 0f) {
                        onCommitExactSeekMs(gestures.pendingSeekMs.floatValue.toLong())
                    }
                    gestures.pendingSeekMs.floatValue = -1f
                    gestures.scrubDeltaSec.floatValue = 0f
                    ui.localSeek.floatValue = -1f
                    gestures.mode.value = GestureMode.NONE
                },
                onDragCancel = {
                    gestures.pendingSeekMs.floatValue = -1f
                    gestures.scrubDeltaSec.floatValue = 0f
                    ui.localSeek.floatValue = -1f
                    gestures.mode.value = GestureMode.NONE
                    onCancelKeyframeSeek()
                },
            )
        }
}
