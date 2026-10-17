# PLAYER UI v1.0.0 — BATCH 2 SPECIFICATION
## Subsystem: Gestures Engine, 0 Hz Scrubber Canvas & Audio/Frame Effects
### Package: `dev.anonrode.player.ui` | TargetSdk: 37 | Android 14+

---

## 1. Batch Overview & Role in Subsystem

Batch 2 implements the high-performance gesture pipeline, the zero-recomposition scrubber canvas, and the background frame/effects engines.

### Files in this Batch:
1. **`app/src/main/java/dev/anonrode/player/ui/PlayerScreenGestures.kt`** (File 3 from Master Blueprint)
   - 4-stage gesture pipeline in Layer 1:
     - Stage 0: Subtitle Bounds Yielding (`gestures.subBounds.contains(offset)` returns early so subtitles own touch).
     - Stage 1: 2-finger pinch-to-zoom mode cycling (`FIT`, `FILL`, `ZOOM`, `16:9`, `4:3`).
     - Stage 2: 3-zone tap & double-tap (Left -10s, Right +10s, Center Play/Pause).
     - Stage 3: 2-axis drag with dead-zones (52dp top/bottom, 24dp left/right), piecewise quadratic scrubbing ($0.15\text{s/dp} \le 50\text{dp}$, quadratic acceleration beyond), and haptic detent at 100% volume-to-boost threshold.
     - Stage 4: Touch-only Hold-to-Boost (500ms stationary hold -> 2.0x boost, 34dp horizontal milestone stepping through `0.5x..3.0x`, strict scrub lockout).
2. **`app/src/main/java/dev/anonrode/player/ui/PlayerScreenBottomBar.kt`** (File 5 from Master Blueprint)
   - Layer 2 Bottom Dock.
   - **Benchmark #1**: `OpticalSeekBarCanvas` runs 100% inside `Modifier.drawWithCache` (0 Hz recomposition during 20 Hz ExoPlayer position ticks).
   - 5-layer canvas: background rail, unclamped buffered rail, played rail, chapter ticks, 14dp white knob with 20dp accent glow.
   - 1 Hz `derivedStateOf` quantized timestamp composables (`displayPosIntSec`, `displayDurIntSec`), eliminating 95% of layout passes.
   - Floating `ScrubBubble` with 128x72dp thumbnail and relative $\pm\Delta\text{s}$ delta.
   - 56dp primary optical play/pause knob, step seek, previous/next episode, aspect ratio cycle, and rotate.
3. **`app/src/main/java/dev/anonrode/player/ui/PlayerScreenEffects.kt`** (File 10 from Master Blueprint)
   - Layer 1 `SeekRippleOverlay`: convex optical glass seek arcs (<35% left, >65% right) with accumulated seconds (`-10s`, `+20s`) and expanding acoustic rings.
   - Background `ScrubPreviewEffect`: off-thread 256x144 frame extraction with 24-frame `LruCache`.
   - `TransientStatusPillTimers`: 5.0s auto-dismiss for Resume Prompt, 2.5s auto-dismiss for Subtitle Sync Toast.

---

## 2. Full Kotlin Source Code

### FILE 3: `PlayerScreenGestures.kt`
```kotlin
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
import androidx.compose.ui.input.pointer.positionChange
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
 * 1. 2-Finger Pinch-to-Zoom
 * 2. Single-Tap / 3-Zone Double-Tap
 * 3. 2-Axis Drag (Left Brightness, Right 200% Volume+Boost, Horizontal Piecewise Quadratic Scrub)
 * 4. Touch-Only Hold-to-Boost (500ms -> 2.0x + 34dp horizontal milestone stepping)
 */
fun Modifier.playerGestureLayer(
    ui: PlayerUiState,
    gestures: GestureUiState,
    durationSecProvider: () -> Float,
    currentPositionMsProvider: () -> Float,
    swipeToSeekEnabled: Boolean = true,
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
        .pointerInput(ui.locked.value) {
            if (ui.locked.value) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (isInsideSubtitle(down.position)) return@awaitEachGesture
                var accZoom = 1f
                do {
                    val event = awaitPointerEvent()
                    if (event.changes.size >= 2) {
                        val zoomChange = event.calculateZoom()
                        accZoom *= zoomChange
                        if (accZoom > 1.18f) {
                            onCycleZoomMode(false)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            accZoom = 1f
                            event.changes.forEach { it.consume() }
                        } else if (accZoom < 0.84f) {
                            onCycleZoomMode(true)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            accZoom = 1f
                            event.changes.forEach { it.consume() }
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        // 2. SINGLE TAP & 3-ZONE DOUBLE-TAP
        .pointerInput(ui.locked.value) {
            detectTapGestures(
                onTap = { offset ->
                    if (isInsideSubtitle(offset)) return@detectTapGestures
                    if (ui.locked.value) {
                        ui.lockScrimVisible.value = !ui.lockScrimVisible.value
                    } else {
                        ui.bumpInteraction()
                        onToggleControls()
                    }
                },
                onDoubleTap = { offset ->
                    if (ui.locked.value || isInsideSubtitle(offset)) return@detectTapGestures
                    ui.bumpInteraction()
                    val w = gestures.scrW.floatValue.coerceAtLeast(1f)
                    val step = ui.seekStepSec.intValue
                    when {
                        offset.x < w * 0.35f -> {
                            gestures.doubleTapSide.intValue = -1
                            gestures.doubleTapAccumSec.intValue -= step
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onDoubleTapSeek(-step, true)
                        }
                        offset.x > w * 0.65f -> {
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
        // 3. TWO-AXIS DRAGS (Brightness, 200% Volume+Boost, Piecewise Quadratic Scrub)
        .pointerInput(ui.locked.value, swipeToSeekEnabled) {
            if (ui.locked.value) return@pointerInput
            val topDeadZone = 52f * density
            val bottomDeadZone = 52f * density
            val sideDeadZone = 24f * density

            detectDragGestures(
                onDragStart = { off ->
                    val w = gestures.scrW.floatValue
                    val h = gestures.scrH.floatValue
                    if (isInsideSubtitle(off) ||
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
                                // LOCKOUT: Never enter SEEK mode if Hold-to-Boost is active!
                                if (swipeToSeekEnabled &&
                                    !ui.boostActive.value &&
                                    !ui.speedScrubHudVisible.value
                                ) {
                                    GestureMode.SEEK
                                } else {
                                    GestureMode.NONE
                                }
                            }
                            sx <= w * 0.45f -> GestureMode.BRIGHTNESS
                            sx >= w * 0.55f -> GestureMode.VOLUME
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
                            if ((prevPct <= 100 && newPct > 100) || (prevPct > 100 && newPct <= 100)) {
                                // Haptic detent at 100% hardware-to-boost threshold
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
                    // CRITICAL INVARIANT: Always restore SeekParameters.EXACT if system cancels drag
                    gestures.pendingSeekMs.floatValue = -1f
                    gestures.scrubDeltaSec.floatValue = 0f
                    ui.localSeek.floatValue = -1f
                    gestures.mode.value = GestureMode.NONE
                    onCancelKeyframeSeek()
                },
            )
        }
        // 4. TOUCH-ONLY HOLD-TO-BOOST & 34dp DISCRETE MILESTONE SCRUB
        .pointerInput(ui.locked.value) {
            if (ui.locked.value) return@pointerInput
            val stepPx = 34f * density
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Strict Touch-Only filter + Subtitle bounds yield
                if (down.type != PointerType.Touch || isInsideSubtitle(down.position)) {
                    return@awaitEachGesture
                }
                val startX = down.position.x
                val startY = down.position.y
                var movedBeforeTimeout = false

                val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) return@withTimeoutOrNull false
                        val dx = abs(change.position.x - startX)
                        val dy = abs(change.position.y - startY)
                        if (dx > viewConfiguration.touchSlop || dy > viewConfiguration.touchSlop) {
                            movedBeforeTimeout = true
                            return@withTimeoutOrNull false
                        }
                    }
                } == null

                if (!timedOut || movedBeforeTimeout || gestures.mode.value != GestureMode.NONE) {
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
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) break
                        val dx = change.position.x - startX
                        val indexStep = (dx / stepPx).roundToInt()
                        val newIdx = (baseIndex + indexStep)
                            .coerceIn(0, PlayerUiState.SpeedMilestones.lastIndex)
                        if (newIdx != ui.discreteScrubIndex.intValue) {
                            ui.discreteScrubIndex.intValue = newIdx
                            val newSpd = PlayerUiState.SpeedMilestones[newIdx]
                            ui.currentScrubSpeed.floatValue = newSpd
                            onSetPlaybackSpeed(newSpd)
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        if (change.positionChange() != Offset.Zero) {
                            change.consume()
                        }
                    }
                } finally {
                    ui.boostActive.value = false
                    ui.speedScrubHudVisible.value = false
                    onSetPlaybackSpeed(savedSpeed)
                }
            }
        }
}
```

---

### FILE 5: `PlayerScreenBottomBar.kt`
```kotlin
package dev.anonrode.player.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun PlayerScreenBottomBar(
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    chapters: List<ChapterMarker>,
    scrubPreviewBitmap: Bitmap?,
    ui: PlayerUiState,
    onPreviewScrubSec: (Float) -> Unit,
    onCommitScrubSec: (Float) -> Unit,
    onStepSeekSec: (Int) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleRotateMode: () -> Unit,
    onStartOverFromZero: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeBottomInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

    AnimatedVisibility(
        visible = ui.controlsVisible.value && !ui.locked.value,
        modifier = modifier,
        enter = fadeIn(tween(240)) + slideInVertically(tween(280, easing = OpticalGlassTokens.EaseReveal)) { 8 },
        exit = fadeOut(tween(160)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { ui.bottomBarHeightPx.intValue = it.height }
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.32f to Color.Black.copy(alpha = 0.22f),
                        1.0f to Color.Black.copy(alpha = 0.48f),
                    )
                )
                .windowInsetsPadding(safeBottomInsets)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 1. Transient Status Pills (Resume Prompt 5.0s / Auto-Sync Lock 2.5s)
            TransientStatusPillsRow(
                ui = ui,
                onStartOver = onStartOverFromZero,
            )

            // 2. Leaf Composable: SeekBarRow (0 Hz Track Recomp + 1 Hz Tnum Text Recomp)
            SeekBarRow(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                chapters = chapters,
                scrubPreviewBitmap = scrubPreviewBitmap,
                ui = ui,
                onPreviewScrubSec = onPreviewScrubSec,
                onCommitScrubSec = onCommitScrubSec,
            )

            // 3. Transport Controls Cluster
            TransportRow(
                ui = ui,
                onStepSeekSec = onStepSeekSec,
                onPlayPauseToggle = onPlayPauseToggle,
                onPreviousEpisode = onPreviousEpisode,
                onNextEpisode = onNextEpisode,
                onCycleAspectRatio = onCycleAspectRatio,
                onToggleRotateMode = onToggleRotateMode,
            )
        }
    }
}

@Composable
private fun TransientStatusPillsRow(
    ui: PlayerUiState,
    onStartOver: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = ui.resumePromptVisible.value,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(200)),
        ) {
            Row(
                modifier = Modifier
                    .opticalGlass(
                        shape = RoundedCornerShape(50),
                        baseColor = OpticalGlassTokens.SmokedHudPillBase,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Continue playing from where you stopped.",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
                Text(
                    text = "Start Over",
                    modifier = Modifier.clickable {
                        ui.resumePromptVisible.value = false
                        onStartOver()
                    },
                    style = TextStyle(
                        color = OpticalGlassTokens.CyanAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = ui.syncToastVisible.value,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(200)),
        ) {
            val offset = ui.subtitleOffsetSec.floatValue
            Box(
                modifier = Modifier
                    .opticalGlass(
                        shape = RoundedCornerShape(50),
                        isActive = true,
                        accentColor = OpticalGlassTokens.EmeraldSync,
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "⟳ Synced %+.2fs".format(offset),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/**
 * Leaf Composable: SeekBarRow.
 * - Text timestamps are quantized to 1 Hz via [derivedStateOf] (95% reduction in leaf recompositions).
 * - Track rails, buffer rail, chapter ticks, and thumb knob render inside [Modifier.drawWithCache]
 *   at 0 Hz recomposition!
 */
@Composable
fun SeekBarRow(
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    chapters: List<ChapterMarker>,
    scrubPreviewBitmap: Bitmap?,
    ui: PlayerUiState,
    onPreviewScrubSec: (Float) -> Unit,
    onCommitScrubSec: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRemaining by remember { mutableStateOf(true) }

    // 1 Hz Quantized Timestamp Reads (Only recomposes when the whole second integer changes!)
    val displayPosIntSec by remember {
        derivedStateOf {
            val activeSeek = ui.localSeek.floatValue
            (if (activeSeek >= 0f) activeSeek else positionSec.value).toInt().coerceAtLeast(0)
        }
    }
    val displayDurIntSec by remember {
        derivedStateOf { durationSec.value.toInt().coerceAtLeast(1) }
    }

    val isScrubbing by remember {
        derivedStateOf { ui.localSeek.floatValue >= 0f }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportWidthPx = constraints.maxWidth.toFloat()

        // ScrubBubble 128dp x 72dp floating directly above the active thumb
        if (isScrubbing) {
            val dur = durationSec.value.coerceAtLeast(1f)
            val seekSec = ui.localSeek.floatValue.coerceIn(0f, dur)
            val deltaSec = (seekSec - positionSec.value).roundToInt()
            ScrubBubble(
                targetSec = seekSec,
                deltaSec = deltaSec,
                durationSec = dur,
                viewportWidthPx = viewportWidthPx,
                previewBitmap = scrubPreviewBitmap,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 36.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Left Elapsed Time (Tnum)
            Text(
                text = formatSecondsToHms(displayPosIntSec),
                modifier = Modifier.width(52.dp),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    shadow = OpticalGlassTokens.CrispTextShadow,
                ),
            )

            // Center 5-Layer Optical Scrubber Canvas (0 Hz Recomposition on Playback Ticks!)
            OpticalSeekBarCanvas(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                chapters = chapters,
                ui = ui,
                onPreviewScrubSec = onPreviewScrubSec,
                onCommitScrubSec = onCommitScrubSec,
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .padding(horizontal = 7.dp),
            )

            // Right Duration / Remaining Time (Tnum)
            val rightLabel = if (showRemaining) {
                "-${formatSecondsToHms((displayDurIntSec - displayPosIntSec).coerceAtLeast(0))}"
            } else {
                formatSecondsToHms(displayDurIntSec)
            }
            Text(
                text = rightLabel,
                modifier = Modifier
                    .width(56.dp)
                    .clickable { showRemaining = !showRemaining },
                textAlign = TextAlign.End,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFeatureSettings = "tnum",
                    shadow = OpticalGlassTokens.CrispTextShadow,
                ),
            )
        }
    }
}

@Composable
private fun OpticalSeekBarCanvas(
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    chapters: List<ChapterMarker>,
    ui: PlayerUiState,
    onPreviewScrubSec: (Float) -> Unit,
    onCommitScrubSec: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val isScrubbing by remember { derivedStateOf { ui.localSeek.floatValue >= 0f } }
    val railHeightDp by animateDpAsState(
        targetValue = if (isScrubbing) 5.dp else 3.dp,
        animationSpec = tween(140),
        label = "railHeight",
    )

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    ui.bumpInteraction()
                    val dur = durationSec.value.coerceAtLeast(1f)
                    val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    val targetSec = frac * dur
                    onCommitScrubSec(targetSec)
                }
            }
            .pointerInput(chapters) {
                var lastChapterIdx = -1
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        ui.bumpInteraction()
                        val dur = durationSec.value.coerceAtLeast(1f)
                        val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val targetSec = frac * dur
                        ui.localSeek.floatValue = targetSec
                        onPreviewScrubSec(targetSec)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val dur = durationSec.value.coerceAtLeast(1f)
                        val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        val targetSec = frac * dur

                        // Magnetic haptic tick when crossing chapter boundaries
                        val chIdx = chapters.indexOfLast { it.timeSec <= targetSec }
                        if (chIdx != lastChapterIdx && lastChapterIdx != -1) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        lastChapterIdx = chIdx

                        ui.localSeek.floatValue = targetSec
                        onPreviewScrubSec(targetSec)
                    },
                    onDragEnd = {
                        val finalSec = ui.localSeek.floatValue
                        if (finalSec >= 0f) {
                            onCommitScrubSec(finalSec)
                        }
                        ui.localSeek.floatValue = -1f
                        lastChapterIdx = -1
                    },
                    onDragCancel = {
                        ui.localSeek.floatValue = -1f
                        lastChapterIdx = -1
                    },
                )
            }
            .drawWithCache {
                val trackH = railHeightDp.toPx()
                val cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
                val thumbRadiusPx = 7.dp.toPx()
                val glowRadiusPx = 10.dp.toPx()
                val chapterTickW = 2.dp.toPx()

                onDrawBehind {
                    val w = size.width
                    val cy = size.height / 2f
                    val topY = cy - trackH / 2f

                    val dur = durationSec.value.coerceAtLeast(1f)
                    val activeSeek = ui.localSeek.floatValue
                    val currentPos = if (activeSeek >= 0f) activeSeek else positionSec.value
                    val posFrac = (currentPos / dur).coerceIn(0f, 1f)
                    // INVARIANT: Buffered rail is NEVER coerced up to played fraction!
                    val bufFrac = (bufferedSec.value / dur).coerceIn(0f, 1f)

                    // 1. Background Rail (0.22f white)
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.22f),
                        topLeft = Offset(0f, topY),
                        size = Size(w, trackH),
                        cornerRadius = cornerRadius,
                    )

                    // 2. Honest Unclamped Buffered Rail (0.45f white)
                    if (bufFrac > 0f) {
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.45f),
                            topLeft = Offset(0f, topY),
                            size = Size(w * bufFrac, trackH),
                            cornerRadius = cornerRadius,
                        )
                    }

                    // 3. Active Played Rail (Accent)
                    if (posFrac > 0f) {
                        drawRoundRect(
                            color = OpticalGlassTokens.CyanAccent,
                            topLeft = Offset(0f, topY),
                            size = Size(w * posFrac, trackH),
                            cornerRadius = cornerRadius,
                        )
                    }

                    // 4. Chapter Boundary Ticks & A-B Repeat Pins
                    chapters.forEach { ch ->
                        val chFrac = (ch.timeSec / dur).coerceIn(0f, 1f)
                        if (chFrac in 0.01f..0.99f) {
                            drawRect(
                                color = Color.Black.copy(alpha = 0.78f),
                                topLeft = Offset(w * chFrac - chapterTickW / 2f, topY),
                                size = Size(chapterTickW, trackH),
                            )
                        }
                    }

                    // 5. 14dp White Thumb with 20dp Accent Glow
                    val thumbCenter = Offset(w * posFrac, cy)
                    drawCircle(
                        color = OpticalGlassTokens.CyanAccent.copy(alpha = 0.26f),
                        radius = glowRadiusPx,
                        center = thumbCenter,
                    )
                    drawCircle(
                        color = Color.White,
                        radius = thumbRadiusPx,
                        center = thumbCenter,
                    )
                }
            }
    )
}

@Composable
fun ScrubBubble(
    targetSec: Float,
    deltaSec: Int,
    durationSec: Float,
    viewportWidthPx: Float,
    previewBitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val bubbleWidthPx = with(density) { 128.dp.toPx() }
    val sidePadPx = with(density) { 16.dp.toPx() }
    val frac = (targetSec / durationSec.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val rawCenterX = frac * viewportWidthPx
    val clampedX = (rawCenterX - bubbleWidthPx / 2f)
        .coerceIn(sidePadPx, (viewportWidthPx - bubbleWidthPx - sidePadPx).coerceAtLeast(sidePadPx))

    Box(
        modifier = modifier
            .offset { IntOffset(clampedX.roundToInt(), 0) }
            .size(width = 128.dp, height = 72.dp)
            .opticalGlass(
                shape = RoundedCornerShape(10.dp),
                baseColor = Color(0xFF080C14).copy(alpha = 0.72f),
                isActive = true,
                accentColor = OpticalGlassTokens.CyanAccent,
                borderWidth = 1.5.dp,
            ),
    ) {
        if (previewBitmap != null && !previewBitmap.isRecycled) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Bottom glass metadata strip: Timestamp + Signed Delta
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                    )
                )
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatSecondsToHms(targetSec.toInt()),
                style = TextStyle(
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
            val sign = if (deltaSec >= 0) "+" else ""
            Text(
                text = "$sign${deltaSec}s",
                style = TextStyle(
                    color = OpticalGlassTokens.CyanAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
    }
}

@Composable
private fun TransportRow(
    ui: PlayerUiState,
    onStepSeekSec: (Int) -> Unit,
    onPlayPauseToggle: () -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onCycleAspectRatio: () -> Unit,
    onToggleRotateMode: () -> Unit,
) {
    val stepSec = ui.seekStepSec.intValue
    val zoomLabel = AspectZoomMode.entries[ui.zoomIdx.intValue % AspectZoomMode.entries.size].label

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left Wing: Screen Lock
        Row(
            modifier = Modifier.width(116.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpticalGlassDiscButton(
                onClick = {
                    ui.locked.value = true
                    ui.lockScrimVisible.value = true
                },
            ) {
                Text("🔒", fontSize = 14.sp)
            }
        }

        // Center Cluster: Prev, -Step, 56dp Primary Play/Pause Knob, +Step, Next
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpticalGlassDiscButton(onClick = onPreviousEpisode) {
                Text("⏮", color = Color.White, fontSize = 14.sp)
            }
            OpticalGlassDiscButton(onClick = { onStepSeekSec(-stepSec) }) {
                Text(
                    text = "-${stepSec}s",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }

            // 56dp Primary Optical Play/Pause Knob
            OpticalGlassDiscButton(
                onClick = onPlayPauseToggle,
                isActive = true,
                discSize = 56.dp,
                touchTargetSize = 60.dp,
                accentColor = OpticalGlassTokens.CyanAccent,
            ) {
                Text(
                    text = if (ui.isPlaying.value) "⏸" else "▶",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }

            OpticalGlassDiscButton(onClick = { onStepSeekSec(+stepSec) }) {
                Text(
                    text = "+${stepSec}s",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
            OpticalGlassDiscButton(onClick = onNextEpisode) {
                Text("⏭", color = Color.White, fontSize = 14.sp)
            }
        }

        // Right Wing: Aspect Ratio Cycle (`FIT`, `FILL`, `ZOOM`, `16:9`, `4:3`) + Rotate
        Row(
            modifier = Modifier.width(116.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OpticalGlassChip(
                label = zoomLabel,
                onClick = onCycleAspectRatio,
                height = 36.dp,
            )
            OpticalGlassDiscButton(onClick = onToggleRotateMode) {
                Text("🔄", fontSize = 14.sp)
            }
        }
    }
}
```

---

### FILE 10: `PlayerScreenEffects.kt`
```kotlin
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
```
