# MASTER ENGINEERING SPECIFICATION & FULL KOTLIN SOURCE CODE
## Target: `anonrode-player` v1.0.0 — Optical Smoked Glass & Zero-Recomposition Player UI Subsystem
## Package: `dev.anonrode.player.ui` (`targetSdk 37`, Android 14+, 100% Kotlin & Jetpack Compose)

You are a Staff Android Graphics & Jetpack Compose Systems Engineer working on `anonrode-player`. Your task is to upgrade the Player UI Subsystem (`v0.9.14` → `v1.0.0`) by implementing the exact architectural patterns, mathematical formulas, recomposition laws, and Kotlin source code provided in this specification.

---

## 1. MANDATORY ARCHITECTURAL LAWS & BENCHMARKS

1. **UDF 4-Slot Architecture (`PlayerScreenLayout`)**:
   - Strictly decouple `videoSurfaceSlot` (Layer 0), `gestureOverlaySlot` (Layer 1), `hudControlsSlot` (Layer 2), and `sideDrawerSlot` (Layer 3).
2. **Benchmark #1 — 0 Hz Scrubber Track Recomposition & 1 Hz Timestamp Recomposition**:
   - During 60/120 FPS video playback (ExoPlayer position ticks at 10–20 Hz), `PlayerScreen`, `PlayerControlsOverlay`, `PlayerScreenTopBar`, `QuickToolsRibbon`, `TransportRow`, and all `SideDrawers` MUST execute **0 recompositions**.
   - **Upgrade over v0.9.14**: Do NOT overlay a Material 3 `Slider` on top of 3 `Box`es in `SeekBarRow`. Replace M3 `Slider` with `OpticalSeekBarCanvas`, which reads `positionSec.value`, `bufferedSec.value`, and `localSeek.floatValue` **exclusively inside `Modifier.drawWithCache { onDrawBehind { ... } }`** (**0 Hz recomposition**, pure RenderNode Draw Phase).
   - Quantize elapsed and remaining timestamp `Text` reads to **1 Hz (once per whole second)** via `derivedStateOf { (if (localSeek.floatValue >= 0f) localSeek.floatValue else positionSec.value).toInt() }`.
3. **Benchmark #2 — Optical Smoked Glass over Hardware `SurfaceView` Underlays**:
   - Because `SurfaceView` renders in a hardware composer underlay beneath the Compose window, standard view-tree blurs do not blur video frames.
   - To achieve a true transparent feel without sacrificing contrast on blinding `#FFFFFF` scenes:
     - Replace opaque `0.55f–0.84f` black slabs with `Modifier.opticalGlass()`, which caches a **3-Stop Directional Specular Rim Brush** (`White(0.36f)` top-left → `White(0.12f)` center → `White(0.04f)` bottom-right), an **Internal Top-Edge Luminance Highlight** (`White(0.09f)` → `Transparent`), and a smoked obsidian base (`Color(0xFF080C14).copy(alpha = 0.32f)` for chips/discs, `0.50f` for side drawers).
     - Remove the murky `0.42f` full-screen black scrim when side drawers open (`scrimAlpha = 0.06f`), keeping the left `~65%` of the video surface **100% crisp and unobstructed** while tuning subtitles, EQ, or settings.
     - Apply crisp optical drop-shadows (`Shadow(Color.Black.copy(0.88f), Offset(0f, 1.5f), blurRadius = 5f)`) to all HUD typography.
4. **Benchmark #3 — Zero Allocations in 120 Hz Pointer Input**:
   - Replace nullable String gesture modes (`"seek"`, `"vol"`, `"bri"`) with `@Immutable enum class GestureMode`.
   - Maintain `isInsideSubtitle(offset)` bounds yielding (`gestures.subBounds`), system edge dead-zones (`52.dp` top/bottom, `24.dp` left/right), Hold-to-Boost horizontal scrub lockout (`!ui.boostActive.value && !ui.speedScrubHudVisible.value`), and `onDragCancel` restoration of `SeekParameters.EXACT`.

---

## 2. COMPLETE KOTLIN IMPLEMENTATION BY FILE

Apply the following exact Kotlin implementations across `app/src/main/java/dev/anonrode/player/ui/`.

---

### FILE 1: `PlayerScreenState.kt`
**Role**: Stable, zero-allocation state holders, type-safe gesture enums, 200% audio boost state, and dynamic subtitle sync window state.

```kotlin
package dev.anonrode.player.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color

@Immutable
enum class GestureMode {
    NONE,
    SEEK,
    VOLUME,
    BRIGHTNESS,
    SUBTITLE_VERTICAL_DRAG,
    SUBTITLE_HORIZONTAL_SYNC,
    SUBTITLE_PINCH_SCALE,
}

@Immutable
enum class RepeatLoopMode {
    OFF,
    ALL,
    ONE,
}

@Immutable
enum class RotateMode {
    SENSOR,
    LANDSCAPE,
    PORTRAIT,
    LOCKED,
}

@Immutable
enum class AspectZoomMode(val label: String) {
    FIT("FIT"),
    FILL("FILL"),
    ZOOM("ZOOM"),
    SIXTEEN_NINE("16:9"),
    FOUR_THREE("4:3"),
}

@Immutable
enum class SubDrawerPage {
    MAIN,
    TRACKS,
    ADJUSTMENT,
    STYLE,
}

@Immutable
enum class RibbonTool(val label: String) {
    NIGHT_MODE("Night"),
    SPEED("Speed"),
    AB_REPEAT("A-B"),
    AUDIO_EFFECT("Audio FX"),
    EQUALIZER("EQ"),
    SCREENSHOT("Shot"),
    SHUFFLE("Shuffle"),
    LOOP("Loop"),
    MUTE("Mute"),
    SLEEP_TIMER("Sleep"),
    BG_PLAY("Bg Play"),
    ROTATE_LOCK("Rotate"),
    CUSTOMISE("Edit"),
}

@Immutable
data class ChapterMarker(
    val timeSec: Float,
    val title: String,
)

@Immutable
data class SubtitleTrackItem(
    val id: String,
    val title: String,
    val formatLabel: String, // e.g. "SRT • Sidecar", "ASS • Embedded"
    val isExternal: Boolean = false,
)

@Immutable
data class PlaylistEpisodeItem(
    val id: String,
    val title: String,
    val durationSec: Float,
    val watchedFraction: Float = 0f,
    val isPlaying: Boolean = false,
)

@Stable
class PlayerUiState(initialIsPlaying: Boolean = false) {
    val controlsVisible = mutableStateOf(true)
    val locked = mutableStateOf(false)
    val lockScrimVisible = mutableStateOf(false)
    val isPlaying = mutableStateOf(initialIsPlaying)
    val isBuffering = mutableStateOf(false)
    val showCC = mutableStateOf(true)
    val nightMode = mutableStateOf(false)
    val backgroundPlayOn = mutableStateOf(true)
    val audioEffectOn = mutableStateOf(false)
    val shuffleOn = mutableStateOf(false)
    val isMuted = mutableStateOf(false)
    val repeatMode = mutableStateOf(RepeatLoopMode.OFF)
    val zoomIdx = mutableIntStateOf(0)
    val seekStepSec = mutableIntStateOf(10)
    val localSeek = mutableFloatStateOf(-1f)          // Active scrubber thumb position (sec), -1f when idle
    val userInteractionKey = mutableLongStateOf(0L)   // Bumps to reset 5.5s auto-hide timer
    val topBarHeightPx = mutableIntStateOf(0)         // Measured top chrome height
    val bottomBarHeightPx = mutableIntStateOf(0)      // Measured bottom dock height
    val playlistDrawerOpen = mutableStateOf(false)
    val subtitleDrawerOpen = mutableStateOf(false)
    val threeDotsMenuOpen = mutableStateOf(false)
    val speedCardOpen = mutableStateOf(false)
    val nerdStatsVisible = mutableStateOf(false)      // Interactive tech-pill telemetry card
    val boostActive = mutableStateOf(false)
    val speedScrubHudVisible = mutableStateOf(false)
    val speedScrubExpanded = mutableStateOf(false)
    val discreteScrubIndex = mutableIntStateOf(6)     // Index into SpeedMilestones (default 6 -> 2.0x)
    val currentScrubSpeed = mutableFloatStateOf(2.0f)
    val currentPlaybackSpeed = mutableFloatStateOf(1.0f)

    // 0..100% = Hardware AudioManager Volume, 101..200% = LoudnessEnhancer Software Boost
    val volumeBoostPercent = mutableIntStateOf(100)
    val screenBrightnessFraction = mutableFloatStateOf(0.75f)

    // A-B Repeat pins (-1f when unset)
    val abStartSec = mutableFloatStateOf(-1f)
    val abEndSec = mutableFloatStateOf(-1f)

    // Subtitle appearance & dynamic adjustment window state
    val subtitleFontSizeSp = mutableFloatStateOf(21f) // Pinch-scalable 14f..42f
    val subtitleTextColor = mutableStateOf(Color.White)
    val subtitleBold = mutableStateOf(true)
    val subtitleBgEnabled = mutableStateOf(false)
    val subtitleOffsetSec = mutableFloatStateOf(0f)
    val subtitleBaseOffsetSec = mutableFloatStateOf(0f) // Dynamic center for [base - 5f .. base + 5f]

    // Transient bottom pills
    val resumePromptVisible = mutableStateOf(false)
    val syncToastVisible = mutableStateOf(false)

    fun bumpInteraction() {
        userInteractionKey.longValue += 1L
    }

    companion object {
        val SpeedMilestones = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    }
}

@Stable
class GestureUiState {
    val scrW = mutableFloatStateOf(0f)
    val scrH = mutableFloatStateOf(0f)
    val startX = mutableFloatStateOf(0f)
    val startY = mutableFloatStateOf(0f)
    val lastX = mutableFloatStateOf(0f)
    val startPosMs = mutableFloatStateOf(0f)
    val startVolPercent = mutableIntStateOf(100)
    val startBri = mutableFloatStateOf(0.75f)
    val mode = mutableStateOf(GestureMode.NONE)
    val pendingSeekMs = mutableFloatStateOf(-1f)      // Live target for drag-scrub
    val scrubDeltaSec = mutableFloatStateOf(0f)       // Signed relative seek delta (+14.2f / -8.0f)
    val subPosFraction = mutableFloatStateOf(0.78f)   // Vertical subtitle anchor (0.10..0.85, default 0.78f)
    val subBounds = mutableStateOf(Rect.Zero)         // Screen bounds for gesture yield
    val doubleTapSide = mutableIntStateOf(0)          // -1 = Left, 0 = None, +1 = Right
    val doubleTapAccumSec = mutableIntStateOf(0)
}

@Stable
class QuickRowUiState {
    var ribbonOrder by mutableStateOf<List<RibbonTool>>(RibbonTool.entries)
    var ribbonHidden by mutableStateOf<Set<RibbonTool>>(emptySet())
    val ribbonScroll = ScrollState(0)
    val ribbonExpanded = mutableStateOf(false)
    val subSyncEnabled = mutableStateOf(true)
    val subSyncRunning = mutableStateOf(false)
    val isSyncLocked = mutableStateOf(false)
    val rotateMode = mutableStateOf(RotateMode.SENSOR)
    val equalizerOn = mutableStateOf(false)
    val showSyncPopover = mutableStateOf(false)
    val showRibbonCustomise = mutableStateOf(false)
}
```

---

### FILE 2: `PlayerScreenHud.kt`
**Role**: The `Modifier.opticalGlass()` rendering engine, reusable optical glass buttons/chips, edge-hugged 200% Volume/Brightness glass tubes, and the Center Speed/Scrub HUD pill.

```kotlin
package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

object OpticalGlassTokens {
    val EaseReveal = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)
    val CyanAccent = Color(0xFF38BDF8)
    val AmberBoost = Color(0xFFF59E0B)
    val EmeraldSync = Color(0xFF10B981)
    val SmokedChipBase = Color(0xFF080C14).copy(alpha = 0.32f)
    val SmokedDrawerBase = Color(0xFF080B12).copy(alpha = 0.52f)
    val SmokedHudPillBase = Color(0xFF080C14).copy(alpha = 0.42f)

    val CrispTextShadow = Shadow(
        color = Color.Black.copy(alpha = 0.88f),
        offset = Offset(0f, 1.5f),
        blurRadius = 5f,
    )
}

/**
 * Zero-allocation Optical Smoked Glass modifier.
 * Caches Outline, top-edge internal luminance highlight, and directional 3-stop specular rim border
 * inside [drawWithCache] so playback ticks and animations never allocate Brushes.
 */
fun Modifier.opticalGlass(
    shape: Shape = CircleShape,
    baseColor: Color = OpticalGlassTokens.SmokedChipBase,
    isActive: Boolean = false,
    accentColor: Color = OpticalGlassTokens.CyanAccent,
    borderWidth: Dp = 1.dp,
): Modifier = this
    .clip(shape)
    .drawWithCache {
        val strokePx = borderWidth.toPx()
        val outline = shape.createOutline(size, layoutDirection, this)

        val internalHighlightBrush = if (isActive) {
            Brush.radialGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.42f),
                    accentColor.copy(alpha = 0.16f),
                ),
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                radius = (size.maxDimension * 0.75f).coerceAtLeast(1f),
            )
        } else {
            Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.09f),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height * 0.42f,
            )
        }

        val specularRimBrush = if (isActive) {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.75f),
                    accentColor.copy(alpha = 0.90f),
                    accentColor.copy(alpha = 0.38f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.36f),
                    Color.White.copy(alpha = 0.12f),
                    Color.White.copy(alpha = 0.04f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        }

        onDrawBehind {
            drawOutline(outline = outline, color = baseColor)
            drawOutline(outline = outline, brush = internalHighlightBrush)
            drawOutline(
                outline = outline,
                brush = specularRimBrush,
                style = Stroke(width = strokePx),
            )
        }
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpticalGlassDiscButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    discSize: Dp = 36.dp,
    touchTargetSize: Dp = 42.dp,
    accentColor: Color = OpticalGlassTokens.CyanAccent,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(touchTargetSize)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(discSize)
                .opticalGlass(
                    shape = CircleShape,
                    isActive = isActive,
                    accentColor = accentColor,
                ),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OpticalGlassChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isActive: Boolean = false,
    height: Dp = 36.dp,
    accentColor: Color = OpticalGlassTokens.CyanAccent,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .height(height)
            .opticalGlass(
                shape = RoundedCornerShape(50),
                isActive = isActive,
                accentColor = accentColor,
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        leadingIcon?.invoke()
        Text(
            text = label,
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

/**
 * Edge-hugged 34dp x 176dp vertical glass tube for Brightness (left) and 200% Volume Boost (right).
 */
@Composable
fun VerticalGestureHudPill(
    visible: Boolean,
    isVolume: Boolean,
    percentage: Int, // 0..100 for Brightness, 0..200 for Volume+Boost
    modifier: Modifier = Modifier,
) {
    val maxPct = if (isVolume) 200f else 100f
    val targetFraction = (percentage / maxPct).coerceIn(0f, 1f)
    val animatedFill by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 60),
        label = "verticalPillFill",
    )
    val isBoosted = isVolume && percentage > 100
    val barColor = if (isBoosted) OpticalGlassTokens.AmberBoost else OpticalGlassTokens.CyanAccent

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(150)) + scaleIn(tween(200, easing = OpticalGlassTokens.EaseReveal), initialScale = 0.92f),
        exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.92f),
    ) {
        Column(
            modifier = Modifier
                .width(34.dp)
                .height(176.dp)
                .opticalGlass(
                    shape = RoundedCornerShape(50),
                    baseColor = OpticalGlassTokens.SmokedHudPillBase,
                    isActive = isBoosted,
                    accentColor = barColor,
                )
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (isVolume) (if (percentage == 0) "🔇" else "🔊") else "☀️",
                fontSize = 13.sp,
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .width(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(animatedFill)
                        .clip(RoundedCornerShape(50))
                        .background(barColor),
                )
            }

            Text(
                text = "$percentage%",
                style = TextStyle(
                    color = if (isBoosted) OpticalGlassTokens.AmberBoost else Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                    shadow = OpticalGlassTokens.CrispTextShadow,
                ),
            )
        }
    }
}

/**
 * Center HUD pill for Hold-to-Boost discrete milestone scrubbing and horizontal drag-seek readout.
 */
@Composable
fun CenterGestureHudPill(
    ui: PlayerUiState,
    gestures: GestureUiState,
    modifier: Modifier = Modifier,
) {
    val isSeeking = gestures.mode.value == GestureMode.SEEK && gestures.pendingSeekMs.floatValue >= 0f
    val isBoosting = ui.boostActive.value || ui.speedScrubHudVisible.value
    val visible = isSeeking || isBoosting

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)) + scaleIn(tween(180, easing = OpticalGlassTokens.EaseReveal), initialScale = 0.94f),
        exit = fadeOut(tween(140)),
    ) {
        Column(
            modifier = Modifier
                .opticalGlass(
                    shape = RoundedCornerShape(24.dp),
                    baseColor = OpticalGlassTokens.SmokedHudPillBase,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (isBoosting) {
                val activeIdx = ui.discreteScrubIndex.intValue.coerceIn(0, PlayerUiState.SpeedMilestones.lastIndex)
                val spd = PlayerUiState.SpeedMilestones[activeIdx]
                Text(
                    text = "⚡ Speed Boost ${spd}x",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerUiState.SpeedMilestones.forEachIndexed { idx, milestone ->
                        val selected = idx == activeIdx
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (selected) OpticalGlassTokens.CyanAccent else Color.Transparent
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "${milestone}x",
                                style = TextStyle(
                                    color = if (selected) Color.Black else Color.White.copy(alpha = 0.55f),
                                    fontSize = 10.5.sp,
                                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                                    fontFeatureSettings = "tnum",
                                ),
                            )
                        }
                    }
                }
            } else if (isSeeking) {
                val targetSec = (gestures.pendingSeekMs.floatValue / 1000f).coerceAtLeast(0f)
                val delta = gestures.scrubDeltaSec.floatValue.roundToInt()
                val sign = if (delta >= 0) "+" else ""
                Text(
                    text = "${formatSecondsToHms(targetSec.toInt())}  ($sign${delta}s)",
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }
        }
    }
}

fun formatSecondsToHms(totalSec: Int): String {
    val clamped = totalSec.coerceAtLeast(0)
    val hrs = clamped / 3600
    val mins = (clamped % 3600) / 60
    val secs = clamped % 60
    return if (hrs > 0) {
        "%d:%02d:%02d".format(hrs, mins, secs)
    } else {
        "%02d:%02d".format(mins, secs)
    }
}
```

---

### FILE 3: `PlayerScreenGestures.kt`
**Role**: The 4-stage gesture state machine in Layer 1 (`Pinch-to-Zoom` → `Tap/Double-Tap` → `2-Axis Drag` → `Touch-Only Hold-to-Boost`) with subtitle bounds yielding, piecewise quadratic scrubbing, 200% volume boost, and `onDragCancel` `SeekParameters.EXACT` recovery.

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

### FILE 4: `PlayerScreenControls.kt`
**Role**: Layer 2 Top Chrome (`PlayerScreenTopBar`), the isolated leaf `PlayerTopBarTitleAndTechLine` with 1-tap `HW+/SW` decoder switching & inline sync micro-dial, and the Collapsible Quick-Action Ribbon.

```kotlin
package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun PlayerScreenTopBar(
    title: String,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    episodeCount: Int,
    ui: PlayerUiState,
    quick: QuickRowUiState,
    onNavigateBack: () -> Unit,
    onCycleDecoderMode: () -> Unit,
    onNudgeSubtitleOffsetSec: (deltaSec: Float) -> Unit,
    onResetSubtitleOffset: () -> Unit,
    onOpenLocalSubtitlePicker: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeTopInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)

    AnimatedVisibility(
        visible = ui.controlsVisible.value && !ui.locked.value,
        modifier = modifier,
        enter = fadeIn(tween(240)) + slideInVertically(tween(280, easing = OpticalGlassTokens.EaseReveal)) { -8 },
        exit = fadeOut(tween(160)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { ui.topBarHeightPx.intValue = it.height }
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.44f),
                        0.68f to Color.Black.copy(alpha = 0.14f),
                        1.0f to Color.Transparent,
                    )
                )
                .windowInsetsPadding(safeTopInsets)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ROW 1: 48dp Centerline (Back Button + Isolated Title/Tech Leaf + Right Chips)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OpticalGlassDiscButton(
                    onClick = onNavigateBack,
                    discSize = 36.dp,
                    touchTargetSize = 48.dp,
                ) {
                    Text(
                        text = "←",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = OpticalGlassTokens.CrispTextShadow,
                        ),
                    )
                }

                // LEAF COMPOSABLE: Isolates decoderModeLabel & liveOffsetMs recomposition
                PlayerTopBarTitleAndTechLine(
                    title = title,
                    videoResolutionFpsLabel = videoResolutionFpsLabel,
                    decoderModeLabel = decoderModeLabel,
                    liveOffsetMs = liveOffsetMs,
                    onToggleNerdStats = {
                        ui.nerdStatsVisible.value = !ui.nerdStatsVisible.value
                    },
                    onCycleDecoderMode = onCycleDecoderMode,
                    onToggleSyncPopover = {
                        quick.showSyncPopover.value = !quick.showSyncPopover.value
                    },
                    modifier = Modifier.weight(1f),
                )

                // Right Chips: [CC], [Playlist], [⋮]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpticalGlassChip(
                        label = "CC",
                        isActive = ui.showCC.value,
                        onClick = { ui.subtitleDrawerOpen.value = true },
                        onLongClick = onOpenLocalSubtitlePicker,
                    )
                    if (episodeCount > 1) {
                        OpticalGlassChip(
                            label = "$episodeCount Ep",
                            isActive = ui.playlistDrawerOpen.value,
                            onClick = { ui.playlistDrawerOpen.value = true },
                        )
                    }
                    OpticalGlassDiscButton(
                        onClick = { ui.threeDotsMenuOpen.value = true },
                        isActive = ui.threeDotsMenuOpen.value,
                    ) {
                        Text(
                            text = "⋮",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                        )
                    }
                }
            }

            // ROW 2: Collapsible Quick-Action Ribbon (First 5 tools + Chevron)
            QuickToolsRibbon(
                ui = ui,
                quick = quick,
                onCaptureScreenshot = onCaptureScreenshot,
            )

            // INLINE SYNC MICRO-POPOVER (1-tap nudge without opening drawer)
            AnimatedVisibility(visible = quick.showSyncPopover.value) {
                InlineSyncMicroPopover(
                    liveOffsetMs = liveOffsetMs,
                    onNudgeSec = onNudgeSubtitleOffsetSec,
                    onReset = onResetSubtitleOffset,
                )
            }

            // LIVE DECODER TELEMETRY OVERLAY
            AnimatedVisibility(visible = ui.nerdStatsVisible.value) {
                NerdTelemetryCard(
                    resolutionFps = videoResolutionFpsLabel,
                    decoderMode = decoderModeLabel,
                )
            }
        }
    }
}

/**
 * Isolated Leaf Composable per §6.2 Recomposition Isolation Law.
 * Reads [decoderModeLabel] and [liveOffsetMs] inside its own scope so changes never recompose
 * the Back button, right chips, or QuickToolsRibbon.
 */
@Composable
fun PlayerTopBarTitleAndTechLine(
    title: String,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    onToggleNerdStats: () -> Unit,
    onCycleDecoderMode: () -> Unit,
    onToggleSyncPopover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetSec = liveOffsetMs.value / 1000f
    val hasOffset = abs(offsetSec) >= 0.01f
    val signedOffsetText = if (offsetSec >= 0f) {
        "Sync +%.2fs".format(offsetSec)
    } else {
        "Sync %.2fs".format(offsetSec)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                shadow = OpticalGlassTokens.CrispTextShadow,
            ),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 1. Resolution & FPS Micro-Pill (Toggles Nerd Telemetry)
            TechMicroPill(
                text = videoResolutionFpsLabel.value,
                onClick = onToggleNerdStats,
            )
            Text(
                text = "•",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
            )
            // 2. Interactive Decoder Hot-Swap Micro-Pill (HW+ / HW / SW)
            TechMicroPill(
                text = decoderModeLabel.value,
                isHighlighted = true,
                accentColor = OpticalGlassTokens.CyanAccent,
                onClick = onCycleDecoderMode,
            )
            Text(
                text = "•",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
            )
            // 3. Interactive Subtitle Sync Micro-Pill
            TechMicroPill(
                text = signedOffsetText,
                isHighlighted = hasOffset,
                accentColor = OpticalGlassTokens.EmeraldSync,
                onClick = onToggleSyncPopover,
            )
        }
    }
}

@Composable
private fun TechMicroPill(
    text: String,
    onClick: () -> Unit,
    isHighlighted: Boolean = false,
    accentColor: Color = OpticalGlassTokens.CyanAccent,
) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .opticalGlass(
                shape = RoundedCornerShape(5.dp),
                isActive = isHighlighted,
                accentColor = accentColor,
                borderWidth = 0.8.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
                shadow = OpticalGlassTokens.CrispTextShadow,
            ),
        )
    }
}

@Composable
private fun QuickToolsRibbon(
    ui: PlayerUiState,
    quick: QuickRowUiState,
    onCaptureScreenshot: () -> Unit,
) {
    val visibleTools = quick.ribbonOrder.filterNot { it in quick.ribbonHidden }
    val expanded = quick.ribbonExpanded.value
    val displayedTools = if (expanded) visibleTools else visibleTools.take(5)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier.horizontalScroll(quick.ribbonScroll) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        displayedTools.forEach { tool ->
            val isActive = when (tool) {
                RibbonTool.NIGHT_MODE -> ui.nightMode.value
                RibbonTool.SPEED -> ui.currentPlaybackSpeed.floatValue != 1.0f
                RibbonTool.AB_REPEAT -> ui.abStartSec.floatValue >= 0f
                RibbonTool.AUDIO_EFFECT -> ui.audioEffectOn.value
                RibbonTool.EQUALIZER -> quick.equalizerOn.value
                RibbonTool.SHUFFLE -> ui.shuffleOn.value
                RibbonTool.LOOP -> ui.repeatMode.value != RepeatLoopMode.OFF
                RibbonTool.MUTE -> ui.isMuted.value
                RibbonTool.BG_PLAY -> ui.backgroundPlayOn.value
                else -> false
            }

            OpticalGlassDiscButton(
                isActive = isActive,
                discSize = 36.dp,
                touchTargetSize = 38.dp,
                onClick = {
                    ui.bumpInteraction()
                    when (tool) {
                        RibbonTool.NIGHT_MODE -> ui.nightMode.value = !ui.nightMode.value
                        RibbonTool.SPEED -> ui.speedCardOpen.value = !ui.speedCardOpen.value
                        RibbonTool.EQUALIZER -> quick.equalizerOn.value = !quick.equalizerOn.value
                        RibbonTool.AUDIO_EFFECT -> ui.audioEffectOn.value = !ui.audioEffectOn.value
                        RibbonTool.SHUFFLE -> ui.shuffleOn.value = !ui.shuffleOn.value
                        RibbonTool.MUTE -> ui.isMuted.value = !ui.isMuted.value
                        RibbonTool.BG_PLAY -> ui.backgroundPlayOn.value = !ui.backgroundPlayOn.value
                        RibbonTool.SCREENSHOT -> onCaptureScreenshot()
                        RibbonTool.CUSTOMISE -> quick.showRibbonCustomise.value = true
                        else -> Unit
                    }
                },
            ) {
                val glyph = when (tool) {
                    RibbonTool.NIGHT_MODE -> "🌙"
                    RibbonTool.SPEED -> "${ui.currentPlaybackSpeed.floatValue}x"
                    RibbonTool.AB_REPEAT -> "A-B"
                    RibbonTool.AUDIO_EFFECT -> "FX"
                    RibbonTool.EQUALIZER -> "🎛"
                    RibbonTool.SCREENSHOT -> "📸"
                    RibbonTool.SHUFFLE -> "🔀"
                    RibbonTool.LOOP -> "🔁"
                    RibbonTool.MUTE -> "🔇"
                    RibbonTool.SLEEP_TIMER -> "⏲"
                    RibbonTool.BG_PLAY -> "🎧"
                    RibbonTool.ROTATE_LOCK -> "🔄"
                    RibbonTool.CUSTOMISE -> "✏"
                }
                Text(
                    text = glyph,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }
        }

        // Expand / Collapse Chevron Disc
        OpticalGlassDiscButton(
            discSize = 36.dp,
            touchTargetSize = 38.dp,
            onClick = { quick.ribbonExpanded.value = !expanded },
        ) {
            Text(
                text = if (expanded) "‹" else "›",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

@Composable
private fun InlineSyncMicroPopover(
    liveOffsetMs: State<Long>,
    onNudgeSec: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val sec = liveOffsetMs.value / 1000f
    Row(
        modifier = Modifier
            .opticalGlass(
                shape = RoundedCornerShape(12.dp),
                baseColor = OpticalGlassTokens.SmokedHudPillBase,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Sub Sync: %+.2fs".format(sec),
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
        )
        TechMicroPill(text = "−0.05s", onClick = { onNudgeSec(-0.05f) })
        TechMicroPill(text = "+0.05s", onClick = { onNudgeSec(+0.05f) })
        TechMicroPill(text = "Reset", onClick = onReset)
    }
}

@Composable
private fun NerdTelemetryCard(
    resolutionFps: State<String>,
    decoderMode: State<String>,
) {
    Column(
        modifier = Modifier
            .opticalGlass(
                shape = RoundedCornerShape(12.dp),
                baseColor = OpticalGlassTokens.SmokedHudPillBase,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val monoStyle = TextStyle(
            color = Color.White.copy(alpha = 0.90f),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            shadow = OpticalGlassTokens.CrispTextShadow,
        )
        Text("SURFACE : SurfaceView (Hardware Underlay Layer 0)", style = monoStyle)
        Text("STREAM  : ${resolutionFps.value} • Mode: ${decoderMode.value}", style = monoStyle)
        Text("HUD LAW : 0 Hz Track Recomp (Draw-Phase) | 1 Hz Tnum", style = monoStyle)
    }
}
```

---

### FILE 5: `PlayerScreenBottomBar.kt`
**Role**: Layer 2 Bottom Scrubber Dock, **0 Hz Recomposition `OpticalSeekBarCanvas`** (`Modifier.drawWithCache`), **1 Hz `derivedStateOf` quantized timestamps**, `128dp × 72dp` `ScrubBubble` with live relative delta (`±Δs`), Measured-Height Transient Status Pills, and the `56dp` Optical Play/Pause Transport Cluster.

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

### FILE 6: `PlayerScreenSubtitles.kt`
**Role**: Layer 1 `PlayerSubtitleOverlay` (sits above `playerGestureLayer` for pointer hit-test priority, tracks `gestures.subBounds`, and supports direct Vertical Drag, Horizontal Sync Nudge, and 2-Finger Pinch-to-Scale) + `OutlinedSubtitleText` (density-scaled `4.dp` round-join 2-pass hardware stroke).

```kotlin
package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerSubtitleOverlay(
    cueText: State<String>,
    isPipMode: Boolean,
    ui: PlayerUiState,
    gestures: GestureUiState,
    onUpdateSubOffsetSec: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = cueText.value
    if (!ui.showCC.value || text.isBlank()) {
        gestures.subBounds.value = Rect.Zero
        return
    }

    val view = LocalView.current
    val density = LocalDensity.current.density

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val anchorFrac = gestures.subPosFraction.floatValue.coerceIn(0.10f, 0.85f)
        val targetY = (containerH * anchorFrac).roundToInt()

        val isInteractingWithSub = gestures.mode.value == GestureMode.SUBTITLE_VERTICAL_DRAG ||
            gestures.mode.value == GestureMode.SUBTITLE_HORIZONTAL_SYNC ||
            gestures.mode.value == GestureMode.SUBTITLE_PINCH_SCALE

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, targetY) }
                .onGloballyPositioned { coords ->
                    gestures.subBounds.value = coords.boundsInRoot()
                }
                .then(
                    if (isInteractingWithSub) {
                        Modifier.opticalGlass(
                            shape = RoundedCornerShape(10.dp),
                            baseColor = OpticalGlassTokens.SmokedHudPillBase,
                            isActive = true,
                        )
                    } else if (ui.subtitleBgEnabled.value) {
                        Modifier
                            .background(
                                Color.Black.copy(alpha = 0.48f),
                                RoundedCornerShape(8.dp),
                            )
                    } else {
                        Modifier
                    }
                )
                // 1. Long-press opens Subtitle Drawer Style page
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            ui.subtitleDrawerOpen.value = true
                        }
                    )
                }
                // 2. Vertical Drag (Anchor 0.10..0.85) & Horizontal Drag (Live Cue-Sync Nudge)
                .pointerInput(containerH) {
                    var startFrac = gestures.subPosFraction.floatValue
                    var startOffsetSec = ui.subtitleOffsetSec.floatValue
                    var accumDx = 0f
                    var accumDy = 0f

                    detectDragGestures(
                        onDragStart = {
                            startFrac = gestures.subPosFraction.floatValue
                            startOffsetSec = ui.subtitleOffsetSec.floatValue
                            accumDx = 0f
                            accumDy = 0f
                            gestures.mode.value = GestureMode.SUBTITLE_VERTICAL_DRAG
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumDx += dragAmount.x
                            accumDy += dragAmount.y

                            if (abs(accumDx) > 1.4f * abs(accumDy) && abs(accumDx) > 18f * density) {
                                gestures.mode.value = GestureMode.SUBTITLE_HORIZONTAL_SYNC
                                val stepSec = ((accumDx / (12f * density)).roundToInt()) * 0.05f
                                val newOffset = startOffsetSec + stepSec
                                onUpdateSubOffsetSec(newOffset)
                            } else {
                                gestures.mode.value = GestureMode.SUBTITLE_VERTICAL_DRAG
                                val newFrac = (startFrac + accumDy / containerH).coerceIn(0.10f, 0.85f)
                                gestures.subPosFraction.floatValue = newFrac
                            }
                        },
                        onDragEnd = {
                            gestures.mode.value = GestureMode.NONE
                        },
                        onDragCancel = {
                            gestures.mode.value = GestureMode.NONE
                        },
                    )
                }
                // 3. Two-Finger Pinch on Subtitle to Scale Font Size (14sp..42sp)
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        if (zoom != 1f) {
                            gestures.mode.value = GestureMode.SUBTITLE_PINCH_SCALE
                            ui.subtitleFontSizeSp.floatValue =
                                (ui.subtitleFontSizeSp.floatValue * zoom).coerceIn(14f, 42f)
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedSubtitleText(
                text = text,
                isPipMode = isPipMode,
                fontSizeSp = ui.subtitleFontSizeSp.floatValue,
                fillColor = ui.subtitleTextColor.value,
                isBold = ui.subtitleBold.value,
            )

            if (isInteractingWithSub) {
                val badgeText = when (gestures.mode.value) {
                    GestureMode.SUBTITLE_HORIZONTAL_SYNC ->
                        "↔ Sync Offset: %+.2fs".format(ui.subtitleOffsetSec.floatValue)
                    GestureMode.SUBTITLE_PINCH_SCALE ->
                        "🔍 Size: ${ui.subtitleFontSizeSp.floatValue.roundToInt()}sp"
                    else ->
                        "↕ Anchor: ${(gestures.subPosFraction.floatValue * 100).roundToInt()}%"
                }
                Text(
                    text = badgeText,
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = TextStyle(
                        color = OpticalGlassTokens.CyanAccent,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/**
 * High-Performance 2-Pass Hardware Stroke Subtitle Renderer (§9.1).
 * Converts 4.dp (or 2.dp in PiP) via LocalDensity to guarantee an honest 2.0dp outer border
 * on 3.0x–3.5x high-DPI screens with 75% fewer layout passes than legacy 8-offset shadows.
 */
@Composable
fun OutlinedSubtitleText(
    text: String,
    isPipMode: Boolean,
    fontSizeSp: Float = 21f,
    fillColor: Color = Color.White,
    isBold: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { (if (isPipMode) 2.dp else 4.dp).toPx() }
    val sizeSp = (if (isPipMode) fontSizeSp * 0.62f else fontSizeSp).sp
    val lineSp = (if (isPipMode) fontSizeSp * 0.78f else fontSizeSp * 1.28f).sp
    val weight = if (isBold) FontWeight.Bold else FontWeight.Medium

    Box(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Pass 1: Density-scaled outline stroke pass (Round join eliminates corner spikes)
        Text(
            text = text,
            style = TextStyle(
                color = Color.Black.copy(alpha = 0.96f),
                fontWeight = weight,
                fontSize = sizeSp,
                lineHeight = lineSp,
                textAlign = TextAlign.Center,
                drawStyle = Stroke(
                    width = strokeWidthPx,
                    join = StrokeJoin.Round,
                ),
            ),
            maxLines = 3,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
        // Pass 2: Interior fill pass
        Text(
            text = text,
            style = TextStyle(
                color = fillColor,
                fontWeight = weight,
                fontSize = sizeSp,
                lineHeight = lineSp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 3,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
    }
}
```

---

### FILE 7: `PlayerScreenSheets.kt`
**Role**: Layer 3 `PlayerSlideInDrawer` (`350dp` optical glass chassis, **`0.06f` unscrimmed left video viewport**, hardware `graphicsLayer { translationX }`, velocity fling dismissal $>600\,\text{dp/s}$ or $>90\,\text{dp}$), the 4-State `PlayerSubtitleDrawer` (with the dynamic `baseOffsetSec ± 5.0s` auto-recentering slider), `PlayerSettingsDrawer` (`4×2` + `4×1` grids), and `PlayerPlaylistDrawer` (`60dp` episode rows).

```kotlin
package dev.anonrode.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Right-aligned 350dp Optical Smoked Glass Side Drawer.
 * - Uses a 0.06f transparent tap-catcher instead of a murky 0.42f black full-screen scrim,
 *   keeping the left ~65% of the video viewport 100% crystal-clear during live adjustments.
 * - Uses hardware display-list translation (`graphicsLayer { translationX }`) and VelocityTracker
 *   fling-to-dismiss (>90dp displacement or >600dp/s velocity).
 */
@Composable
fun PlayerSlideInDrawer(
    visible: Boolean,
    title: String,
    onDismiss: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val panelWidthPx = with(density) { 350.dp.toPx() }
    val dismissThresholdPx = with(density) { 90.dp.toPx() }
    val dismissVelocityPx = with(density) { 600.dp.toPx() }

    val dragX = remember { Animatable(panelWidthPx) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(visible) {
        if (visible) {
            dragX.animateTo(0f, tween(240, easing = OpticalGlassTokens.EaseReveal))
        } else {
            dragX.animateTo(panelWidthPx, tween(160, easing = OpticalGlassTokens.EaseReveal))
        }
    }

    if (!visible && dragX.value >= panelWidthPx - 1f) return

    BackHandler(enabled = visible) {
        if (onNavigateBack != null) onNavigateBack() else onDismiss()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Ultra-clear tap-out catcher (0.06f alpha keeps left video surface 100% vivid!)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.06f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )

        // 350dp Optical Smoked Glass Chassis
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(350.dp)
                .fillMaxHeight()
                .graphicsLayer { translationX = dragX.value }
                .opticalGlass(
                    shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                    baseColor = OpticalGlassTokens.SmokedDrawerBase,
                    borderWidth = 1.dp,
                )
                .pointerInput(Unit) {
                    val velocityTracker = VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = { velocityTracker.resetTracking() },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val nextX = (dragX.value + dragAmount).coerceIn(0f, panelWidthPx)
                            scope.launch { dragX.snapTo(nextX) }
                        },
                        onDragEnd = {
                            val velocity = velocityTracker.calculateVelocity().x
                            val displacement = dragX.value
                            if (displacement > dismissThresholdPx || velocity > dismissVelocityPx) {
                                scope.launch {
                                    dragX.animateTo(
                                        panelWidthPx,
                                        tween(160, easing = OpticalGlassTokens.EaseReveal)
                                    )
                                    onDismiss()
                                }
                            } else {
                                scope.launch {
                                    dragX.animateTo(
                                        0f,
                                        tween(200, easing = OpticalGlassTokens.EaseReveal)
                                    )
                                }
                            }
                        },
                    )
                },
        ) {
            // Drawer Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onNavigateBack != null) {
                        OpticalGlassChip(
                            label = "←",
                            height = 28.dp,
                            onClick = onNavigateBack,
                        )
                    }
                    Text(
                        text = title,
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = OpticalGlassTokens.CrispTextShadow,
                        ),
                    )
                }
                OpticalGlassChip(
                    label = "✕",
                    height = 28.dp,
                    onClick = onDismiss,
                )
            }

            // Drawer Content Body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

/**
 * §8.2 Subtitle Drawer with 4-State Navigation Machine (`MAIN`, `TRACKS`, `ADJUSTMENT`, `STYLE`)
 * and Dynamic `baseOffsetSec ± 5.0s` Auto-Recentering Adjustment Window.
 */
@Composable
fun PlayerSubtitleDrawer(
    visible: Boolean,
    tracks: List<SubtitleTrackItem>,
    selectedTrackId: String?,
    ui: PlayerUiState,
    gestures: GestureUiState,
    onSelectTrack: (String?) -> Unit,
    onOpenLocalFilePicker: () -> Unit,
    onTriggerAutoSync: () -> Unit,
    onApplySubtitleOffsetSec: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentPage by remember { mutableStateOf(SubDrawerPage.MAIN) }

    // Dynamic baseOffsetSec ± 5.0s recentering check:
    // Whenever offset drifts > 4.8s from baseOffsetSec, recenter baseOffsetSec so large auto-sync
    // offsets (e.g. +35.0s) are NEVER clamped to [-5s, +5s].
    val currentOffset = ui.subtitleOffsetSec.floatValue
    val baseOffset = ui.subtitleBaseOffsetSec.floatValue
    if (abs(currentOffset - baseOffset) > 4.8f) {
        ui.subtitleBaseOffsetSec.floatValue = currentOffset
    }

    val headerTitle = when (currentPage) {
        SubDrawerPage.MAIN -> "Subtitles"
        SubDrawerPage.TRACKS -> "Subtitles • Tracks"
        SubDrawerPage.ADJUSTMENT -> "Subtitles • Sync Adjustment"
        SubDrawerPage.STYLE -> "Subtitles • Style"
    }

    PlayerSlideInDrawer(
        visible = visible,
        title = headerTitle,
        onDismiss = {
            currentPage = SubDrawerPage.MAIN
            onDismiss()
        },
        onNavigateBack = if (currentPage != SubDrawerPage.MAIN) {
            { currentPage = SubDrawerPage.MAIN }
        } else null,
    ) {
        when (currentPage) {
            SubDrawerPage.MAIN -> {
                val activeTrack = tracks.firstOrNull { it.id == selectedTrackId }
                DrawerNavRow(
                    title = "Subtitle Track",
                    subtitle = if (ui.showCC.value && activeTrack != null) {
                        "${activeTrack.title} (${activeTrack.formatLabel})"
                    } else "Disabled (Off)",
                    trailing = "›",
                    onClick = { currentPage = SubDrawerPage.TRACKS },
                )
                DrawerNavRow(
                    title = "Open Local File…",
                    subtitle = "Load .srt, .ass, .ssa, .vtt",
                    trailing = "📂",
                    onClick = onOpenLocalFilePicker,
                )
                DrawerNavRow(
                    title = "Online Download / Auto-Sync",
                    subtitle = "Acoustic VAD lock",
                    trailing = "⚡ Sync",
                    onClick = onTriggerAutoSync,
                )
                DrawerNavRow(
                    title = "Timing Adjustment",
                    subtitle = "Signed Offset: %+.2fs".format(currentOffset),
                    trailing = "›",
                    onClick = { currentPage = SubDrawerPage.ADJUSTMENT },
                )
                DrawerNavRow(
                    title = "Subtitle Style & Appearance",
                    subtitle = "2-Pass 4dp Stroke • ${ui.subtitleFontSizeSp.floatValue.toInt()}sp",
                    trailing = "›",
                    onClick = { currentPage = SubDrawerPage.STYLE },
                )
            }

            SubDrawerPage.TRACKS -> {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DrawerNavRow(
                        title = if (!ui.showCC.value) "● Disabled (Off)" else "○ Disabled (Off)",
                        subtitle = "Hide subtitle overlay",
                        trailing = "",
                        onClick = {
                            ui.showCC.value = false
                            onSelectTrack(null)
                        },
                    )
                    tracks.forEach { track ->
                        val selected = ui.showCC.value && track.id == selectedTrackId
                        DrawerNavRow(
                            title = (if (selected) "● " else "○ ") + track.title,
                            subtitle = track.formatLabel,
                            trailing = "",
                            onClick = {
                                ui.showCC.value = true
                                onSelectTrack(track.id)
                            },
                        )
                    }
                    DrawerNavRow(
                        title = "+ Load External Subtitle File…",
                        subtitle = ".srt / .ass / .vtt",
                        trailing = "📂",
                        onClick = onOpenLocalFilePicker,
                    )
                }
            }

            SubDrawerPage.ADJUSTMENT -> {
                val windowMin = ui.subtitleBaseOffsetSec.floatValue - 5.0f
                val windowMax = ui.subtitleBaseOffsetSec.floatValue + 5.0f

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpticalGlassChip(
                        label = "−0.1s",
                        onClick = {
                            val next = currentOffset - 0.1f
                            ui.subtitleOffsetSec.floatValue = next
                            onApplySubtitleOffsetSec(next)
                        },
                    )
                    Text(
                        text = "%+.2fs".format(currentOffset),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    OpticalGlassChip(
                        label = "+0.1s",
                        onClick = {
                            val next = currentOffset + 0.1f
                            ui.subtitleOffsetSec.floatValue = next
                            onApplySubtitleOffsetSec(next)
                        },
                    )
                }

                Text(
                    text = "Dynamic Fine-Trim Window [%+.1fs .. %+.1fs]".format(windowMin, windowMax),
                    style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp),
                )

                Slider(
                    value = currentOffset.coerceIn(windowMin, windowMax),
                    onValueChange = { newVal ->
                        ui.subtitleOffsetSec.floatValue = newVal
                        if (abs(newVal - ui.subtitleBaseOffsetSec.floatValue) > 4.8f) {
                            ui.subtitleBaseOffsetSec.floatValue = newVal
                        }
                        onApplySubtitleOffsetSec(newVal)
                    },
                    valueRange = windowMin..windowMax,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = OpticalGlassTokens.CyanAccent,
                    ),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpticalGlassChip(
                        label = "Reset (0s)",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ui.subtitleBaseOffsetSec.floatValue = 0f
                            ui.subtitleOffsetSec.floatValue = 0f
                            onApplySubtitleOffsetSec(0f)
                        },
                    )
                    OpticalGlassChip(
                        label = "⚡ Re-Sync",
                        isActive = true,
                        modifier = Modifier.weight(1f),
                        onClick = onTriggerAutoSync,
                    )
                }
            }

            SubDrawerPage.STYLE -> {
                Text("Vertical Position Preset", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpticalGlassChip("Bottom (78%)", onClick = { gestures.subPosFraction.floatValue = 0.78f })
                    OpticalGlassChip("Middle (50%)", onClick = { gestures.subPosFraction.floatValue = 0.50f })
                    OpticalGlassChip("Top (16%)", onClick = { gestures.subPosFraction.floatValue = 0.16f })
                }

                Text("Font Size Preset", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Tiny" to 15f, "Small" to 18f, "Norm" to 21f, "Large" to 26f, "Huge" to 32f)
                        .forEach { (label, spVal) ->
                            OpticalGlassChip(
                                label = label,
                                isActive = abs(ui.subtitleFontSizeSp.floatValue - spVal) < 1.5f,
                                onClick = { ui.subtitleFontSizeSp.floatValue = spVal },
                            )
                        }
                }

                Text("Fill Color (4dp Stroke Active)", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OpticalGlassChip("⚪ White", onClick = { ui.subtitleTextColor.value = Color.White })
                    OpticalGlassChip("🟡 Yellow", onClick = { ui.subtitleTextColor.value = Color(0xFFFDE047) })
                    OpticalGlassChip("🔵 Cyan", onClick = { ui.subtitleTextColor.value = Color(0xFF67E8F9) })
                }
            }
        }
    }
}

/**
 * §8.3 Settings Drawer (`4×2` Quick Tools Grid + `4×1` Play Option Row + Speed & Inline Sliders).
 */
@Composable
fun PlayerSettingsDrawer(
    visible: Boolean,
    ui: PlayerUiState,
    quick: QuickRowUiState,
    onApplySpeed: (Float) -> Unit,
    onOpenAudioTracks: () -> Unit,
    onEnterPip: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSlideInDrawer(
        visible = visible,
        title = "Settings & Quick Tools",
        onDismiss = onDismiss,
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("QUICK TOOLS (4×2)", color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpticalGlassChip("🔊 Audio", modifier = Modifier.weight(1f), onClick = onOpenAudioTracks)
                OpticalGlassChip("💬 Sub", isActive = ui.showCC.value, modifier = Modifier.weight(1f), onClick = {
                    onDismiss()
                    ui.subtitleDrawerOpen.value = true
                })
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpticalGlassChip("🎧 Bg Play", isActive = ui.backgroundPlayOn.value, modifier = Modifier.weight(1f), onClick = {
                    ui.backgroundPlayOn.value = !ui.backgroundPlayOn.value
                })
                OpticalGlassChip("🖼 PiP", modifier = Modifier.weight(1f), onClick = onEnterPip)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpticalGlassChip("📸 Shot", modifier = Modifier.weight(1f), onClick = onCaptureScreenshot)
                OpticalGlassChip("🔒 Lock", modifier = Modifier.weight(1f), onClick = {
                    onDismiss()
                    ui.locked.value = true
                    ui.lockScrimVisible.value = true
                })
            }

            Text("PLAY OPTIONS (4×1)", color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OpticalGlassChip("🌙 Night", isActive = ui.nightMode.value, modifier = Modifier.weight(1f), onClick = {
                    ui.nightMode.value = !ui.nightMode.value
                })
                OpticalGlassChip("🎛 EQ", isActive = quick.equalizerOn.value, modifier = Modifier.weight(1f), onClick = {
                    quick.equalizerOn.value = !quick.equalizerOn.value
                })
                OpticalGlassChip(
                    label = "🚀 ${ui.volumeBoostPercent.intValue}%",
                    isActive = ui.volumeBoostPercent.intValue > 100,
                    accentColor = OpticalGlassTokens.AmberBoost,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        ui.volumeBoostPercent.intValue = if (ui.volumeBoostPercent.intValue > 100) 100 else 150
                    }
                )
            }

            Text("REPEAT MODE", color = Color.White.copy(alpha = 0.55f), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RepeatLoopMode.entries.forEach { mode ->
                    OpticalGlassChip(
                        label = mode.name,
                        isActive = ui.repeatMode.value == mode,
                        modifier = Modifier.weight(1f),
                        onClick = { ui.repeatMode.value = mode },
                    )
                }
            }

            Text(
                text = "PLAYBACK SPEED (${"%.2f".format(ui.currentPlaybackSpeed.floatValue)}x)",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                    OpticalGlassChip(
                        label = "${spd}x",
                        isActive = abs(ui.currentPlaybackSpeed.floatValue - spd) < 0.03f,
                        onClick = {
                            ui.currentPlaybackSpeed.floatValue = spd
                            onApplySpeed(spd)
                        },
                    )
                }
            }
        }
    }
}

/**
 * §8.4 Playlist Drawer (60dp Episode Rows, 72x42dp 16:9 Thumbnails, Duration Pill).
 */
@Composable
fun PlayerPlaylistDrawer(
    visible: Boolean,
    episodes: List<PlaylistEpisodeItem>,
    onSelectEpisode: (PlaylistEpisodeItem) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSlideInDrawer(
        visible = visible,
        title = "${episodes.size} Episodes",
        onDismiss = onDismiss,
    ) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(episodes, key = { _, ep -> ep.id }) { _, ep ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .opticalGlass(
                            shape = RoundedCornerShape(12.dp),
                            isActive = ep.isPlaying,
                        )
                        .clickable { onSelectEpisode(ep) }
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 72dp x 42dp 16:9 Thumbnail Box with bottom progress bar & duration pill
                    Box(
                        modifier = Modifier
                            .size(width = 72.dp, height = 42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E293B)),
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(3.dp))
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = formatSecondsToHms(ep.durationSec.toInt()),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        if (ep.watchedFraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .fillMaxWidth(ep.watchedFraction.coerceIn(0f, 1f))
                                    .height(2.5.dp)
                                    .background(OpticalGlassTokens.CyanAccent),
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = ep.title,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        if (ep.isPlaying) {
                            Text(
                                text = "● Playing",
                                color = OpticalGlassTokens.CyanAccent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Text(
                        text = "≡",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerNavRow(
    title: String,
    subtitle: String,
    trailing: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .opticalGlass(
                shape = RoundedCornerShape(12.dp),
                baseColor = Color.White.copy(alpha = 0.06f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(text = subtitle, color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp)
        }
        if (trailing.isNotEmpty()) {
            Text(text = trailing, color = OpticalGlassTokens.CyanAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
```

---

### FILE 8: `PlayerScreen.kt` (Top-Level 4-Slot Orchestrator)
**Role**: Enforces the strict 4-slot architecture (`PlayerScreenLayout`), coordinates the 5.5s auto-hide timer via `userInteractionKey`, binds Layer 1 hit-test ordering (`PlayerSubtitleOverlay` above `playerGestureLayer`), renders `SeekRippleOverlay`, and passes `State<Float>` playback position down to `SeekBarRow` without reading `.value`.

```kotlin
package dev.anonrode.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * §3. Four-Slot Screen Architecture (`AGENT.md` §2.2).
 */
@Composable
fun PlayerScreenLayout(
    videoSurfaceSlot: @Composable BoxScope.() -> Unit,
    gestureOverlaySlot: @Composable BoxScope.() -> Unit,
    hudControlsSlot: @Composable BoxScope.() -> Unit,
    sideDrawerSlot: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        videoSurfaceSlot()      // Layer 0: SurfaceView decoder, poster art cache, night mode scrim
        gestureOverlaySlot()    // Layer 1: Gesture layer, ripples, vertical pills, lock scrim, subtitle overlay
        hudControlsSlot()       // Layer 2: Top bar, quick ribbon, scrubber dock, transport
        sideDrawerSlot()        // Layer 3: 350dp optical glass right-aligned drawers
    }
}

/**
 * Top-Level Orchestrator.
 * ZERO FULL-HUD RECOMPOSITION INVARIANT:
 * [positionSec], [durationSec], and [bufferedSec] are passed as [State<Float>] references and are
 * NEVER read inside [PlayerScreen] or [hudControlsSlot].
 */
@Composable
fun PlayerScreen(
    title: String,
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    cueText: State<String>,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    chapters: List<ChapterMarker>,
    subtitleTracks: List<SubtitleTrackItem>,
    selectedSubtitleTrackId: String?,
    episodes: List<PlaylistEpisodeItem>,
    scrubPreviewBitmap: Bitmap?,
    isPipMode: Boolean,
    ui: PlayerUiState,
    gestures: GestureUiState,
    quick: QuickRowUiState,
    actions: PlayerScreenActions,
    videoSurfaceContent: @Composable BoxScope.() -> Unit,
    onNavigateBack: () -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onOpenLocalSubtitlePicker: () -> Unit,
    onSelectEpisode: (PlaylistEpisodeItem) -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 5.5-second Auto-Hide Timer reset by userInteractionKey
    LaunchedEffect(
        ui.controlsVisible.value,
        ui.isPlaying.value,
        ui.userInteractionKey.longValue,
        ui.localSeek.floatValue >= 0f,
    ) {
        if (ui.controlsVisible.value && ui.isPlaying.value && ui.localSeek.floatValue < 0f) {
            delay(5500L)
            ui.controlsVisible.value = false
            quick.showSyncPopover.value = false
        }
    }

    // Reset double-tap ripple indicator after 450ms
    LaunchedEffect(gestures.doubleTapAccumSec.intValue) {
        if (gestures.doubleTapSide.intValue != 0) {
            delay(450L)
            gestures.doubleTapSide.intValue = 0
            gestures.doubleTapAccumSec.intValue = 0
        }
    }

    // 5.0s Resume Prompt & 2.5s Sync Toast Auto-Dismiss Timers
    TransientStatusPillTimers(ui = ui)

    PlayerScreenLayout(
        modifier = modifier,
        // =====================================================================
        // LAYER 0: VIDEO SURFACE + EYE-COMFORT NIGHT SCRIM
        // =====================================================================
        videoSurfaceSlot = {
            videoSurfaceContent()
            if (ui.nightMode.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFF7314).copy(alpha = 0.22f))
                )
            }
        },
        // =====================================================================
        // LAYER 1: GESTURE DETECTOR, RIPPLES, EDGE PILLS, SUBTITLE OVERLAY
        // =====================================================================
        gestureOverlaySlot = {
            // 1A. Base Gesture Interception Layer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerGestureLayer(
                        ui = ui,
                        gestures = gestures,
                        durationSecProvider = { durationSec.value },
                        currentPositionMsProvider = { positionSec.value * 1000f },
                        onToggleControls = { ui.controlsVisible.value = !ui.controlsVisible.value },
                        onDoubleTapSeek = { deltaSec, _ -> actions.onStepSeekSec(deltaSec) },
                        onPlayPauseToggle = actions::onPlayPauseToggle,
                        onPreviewKeyFrameSeekMs = actions::onPreviewKeyFrameSeekMs,
                        onCommitExactSeekMs = actions::onCommitExactSeekMs,
                        onCancelKeyframeSeek = actions::onCancelKeyframeSeek,
                        onApplyVolumeBoostPercent = actions::onApplyVolumeBoostPercent,
                        onApplyBrightnessFraction = actions::onApplyBrightnessFraction,
                        onSetPlaybackSpeed = actions::onSetPlaybackSpeed,
                        onCycleZoomMode = {
                            ui.zoomIdx.intValue = (ui.zoomIdx.intValue + 1) % AspectZoomMode.entries.size
                        },
                    )
            )

            // 1B. Double-Tap Seek Ripple Arcs (Left <35% / Right >65%)
            SeekRippleOverlay(gestures = gestures)

            // 1C. Edge-Hugged Vertical Brightness Pill (Left) & 200% Volume+Boost Pill (Right)
            VerticalGestureHudPill(
                visible = gestures.mode.value == GestureMode.BRIGHTNESS,
                isVolume = false,
                percentage = (ui.screenBrightnessFraction.floatValue * 100f).toInt(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp),
            )
            VerticalGestureHudPill(
                visible = gestures.mode.value == GestureMode.VOLUME,
                isVolume = true,
                percentage = ui.volumeBoostPercent.intValue,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp),
            )

            // 1D. Center Hold-to-Boost Milestones & Progressive Scrub Pill
            CenterGestureHudPill(
                ui = ui,
                gestures = gestures,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp),
            )

            // 1E. CRITICAL HIT-TEST INVARIANT: Subtitle Overlay sits ABOVE playerGestureLayer
            PlayerSubtitleOverlay(
                cueText = cueText,
                isPipMode = isPipMode,
                ui = ui,
                gestures = gestures,
                onUpdateSubOffsetSec = actions::onApplySubtitleOffsetSec,
            )

            // 1F. Screen Lock Scrim Unlock Chip
            AnimatedVisibility(
                visible = ui.locked.value && ui.lockScrimVisible.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp),
            ) {
                OpticalGlassChip(
                    label = "🔒 Locked • Tap to Unlock",
                    isActive = true,
                    onClick = {
                        ui.locked.value = false
                        ui.lockScrimVisible.value = false
                        ui.controlsVisible.value = true
                    },
                )
            }
        },
        // =====================================================================
        // LAYER 2: HUD CHROME (TOP BAR + BOTTOM DOCK)
        // =====================================================================
        hudControlsSlot = {
            PlayerScreenTopBar(
                title = title,
                videoResolutionFpsLabel = videoResolutionFpsLabel,
                decoderModeLabel = decoderModeLabel,
                liveOffsetMs = liveOffsetMs,
                episodeCount = episodes.size,
                ui = ui,
                quick = quick,
                onNavigateBack = onNavigateBack,
                onCycleDecoderMode = actions::onCycleDecoderMode,
                onNudgeSubtitleOffsetSec = { delta ->
                    actions.onApplySubtitleOffsetSec(ui.subtitleOffsetSec.floatValue + delta)
                },
                onResetSubtitleOffset = {
                    ui.subtitleBaseOffsetSec.floatValue = 0f
                    actions.onApplySubtitleOffsetSec(0f)
                },
                onOpenLocalSubtitlePicker = onOpenLocalSubtitlePicker,
                onCaptureScreenshot = actions::onCaptureScreenshot,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            PlayerScreenBottomBar(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                chapters = chapters,
                scrubPreviewBitmap = scrubPreviewBitmap,
                ui = ui,
                onPreviewScrubSec = { sec -> actions.onPreviewKeyFrameSeekMs((sec * 1000f).toLong()) },
                onCommitScrubSec = { sec -> actions.onCommitExactSeekMs((sec * 1000f).toLong()) },
                onStepSeekSec = actions::onStepSeekSec,
                onPlayPauseToggle = actions::onPlayPauseToggle,
                onPreviousEpisode = onPreviousEpisode,
                onNextEpisode = onNextEpisode,
                onCycleAspectRatio = {
                    ui.zoomIdx.intValue = (ui.zoomIdx.intValue + 1) % AspectZoomMode.entries.size
                },
                onToggleRotateMode = {
                    val modes = RotateMode.entries
                    quick.rotateMode.value = modes[(quick.rotateMode.value.ordinal + 1) % modes.size]
                },
                onStartOverFromZero = { actions.onCommitExactSeekMs(0L) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
        // =====================================================================
        // LAYER 3: 350dp OPTICAL GLASS RIGHT-ALIGNED DRAWERS
        // =====================================================================
        sideDrawerSlot = {
            PlayerSubtitleDrawer(
                visible = ui.subtitleDrawerOpen.value,
                tracks = subtitleTracks,
                selectedTrackId = selectedSubtitleTrackId,
                ui = ui,
                gestures = gestures,
                onSelectTrack = onSelectSubtitleTrack,
                onOpenLocalFilePicker = onOpenLocalSubtitlePicker,
                onTriggerAutoSync = actions::onTriggerAutoSync,
                onApplySubtitleOffsetSec = actions::onApplySubtitleOffsetSec,
                onDismiss = { ui.subtitleDrawerOpen.value = false },
            )

            PlayerPlaylistDrawer(
                visible = ui.playlistDrawerOpen.value,
                episodes = episodes,
                onSelectEpisode = onSelectEpisode,
                onDismiss = { ui.playlistDrawerOpen.value = false },
            )

            PlayerSettingsDrawer(
                visible = ui.threeDotsMenuOpen.value,
                ui = ui,
                quick = quick,
                onApplySpeed = actions::onSetPlaybackSpeed,
                onOpenAudioTracks = onOpenAudioTracks,
                onEnterPip = onEnterPip,
                onCaptureScreenshot = actions::onCaptureScreenshot,
                onDismiss = { ui.threeDotsMenuOpen.value = false },
            )
        },
    )
}
```

---

### FILE 9: `PlayerScreenActions.kt`
**Role**: Stable callback dispatcher (§2.1) that captures ExoPlayer, AudioManager, LoudnessEnhancer, and Window references without triggering pointerInput relaunches on playback ticks. Implements the 0–200% continuous Hardware Volume + Software Boost curve, SeekParameters.CLOSEST_SYNC keyframe scrubbing, and SeekParameters.EXACT cancellation recovery.

```kotlin
package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
class PlayerScreenActions(
    private val context: Context,
    private val activity: Activity?,
    private val livePlayer: ExoPlayer?,
    private val ui: PlayerUiState,
    private val gestures: GestureUiState,
    private val quick: QuickRowUiState,
    private val scope: CoroutineScope,
    val decoderModeLabel: State<String>,
    val isRebuildingDecoder: State<Boolean>,
    private val onRebuildDecoderCycle: () -> Unit,
    private val onApplyEngineSubtitleOffsetSec: (Float) -> Unit,
    private val onTriggerEngineAutoSync: () -> Unit,
    private val onCaptureVideoFrame: () -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxHardwareVolume: Int =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var exactSeekRestoreJob: Job? = null

    fun syncInitialHardwareState() {
        val currentHw = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        ui.volumeBoostPercent.intValue = ((currentHw.toFloat() / maxHardwareVolume) * 100f).roundToInt()
        val windowAttr = activity?.window?.attributes
        if (windowAttr != null && windowAttr.screenBrightness >= 0f) {
            ui.screenBrightnessFraction.floatValue = windowAttr.screenBrightness.coerceIn(0.02f, 1.0f)
        }
    }

    fun onPlayPauseToggle() {
        ui.bumpInteraction()
        val player = livePlayer ?: return
        if (player.isPlaying) {
            player.pause()
            ui.isPlaying.value = false
        } else {
            player.play()
            ui.isPlaying.value = true
        }
    }

    /**
     * Fast 60 FPS keyframe preview seek (`SeekParameters.CLOSEST_SYNC`) during horizontal drags.
     */
    fun onPreviewKeyFrameSeekMs(targetMs: Long) {
        val player = livePlayer ?: return
        exactSeekRestoreJob?.cancel()
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.seekTo(targetMs.coerceAtLeast(0L))
    }

    /**
     * Commits final scrub position and restores `SeekParameters.EXACT` 500ms after touch release (§5.2.1).
     */
    fun onCommitExactSeekMs(targetMs: Long) {
        val player = livePlayer ?: return
        exactSeekRestoreJob?.cancel()
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(targetMs.coerceAtLeast(0L))
        exactSeekRestoreJob = scope.launch {
            delay(500L)
            livePlayer?.setSeekParameters(SeekParameters.EXACT)
        }
    }

    /**
     * §5.2.2 Gesture Cancellation Recovery: immediately restores `SeekParameters.EXACT`
     * so aborted drags never leave ExoPlayer stuck in coarse keyframe seek mode.
     */
    fun onCancelKeyframeSeek() {
        exactSeekRestoreJob?.cancel()
        gestures.pendingSeekMs.floatValue = -1f
        gestures.scrubDeltaSec.floatValue = 0f
        ui.localSeek.floatValue = -1f
        livePlayer?.setSeekParameters(SeekParameters.EXACT)
    }

    /**
     * Step seek (±10s default) shared between transport buttons and double-tap seek ripples (§7.4).
     */
    fun onStepSeekSec(deltaSec: Int) {
        ui.bumpInteraction()
        val player = livePlayer ?: return
        val durationMs = player.duration.coerceAtLeast(1000L)
        val targetMs = (player.currentPosition + deltaSec * 1000L).coerceIn(0L, durationMs)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(targetMs)
    }

    /**
     * Continuous 0%..200% Volume + LoudnessEnhancer Audio Boost.
     * - 0..100%: Maps linearly to hardware `AudioManager.STREAM_MUSIC` (0 dB software gain).
     * - 101..200%: Locks hardware volume at 100% and applies up to +1200 mB (+12 dB) via `LoudnessEnhancer`.
     */
    fun onApplyVolumeBoostPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        ui.volumeBoostPercent.intValue = clamped
        ui.isMuted.value = (clamped == 0)

        val hwFraction = (clamped.coerceAtMost(100) / 100f)
        val targetHwIndex = (hwFraction * maxHardwareVolume).roundToInt().coerceIn(0, maxHardwareVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetHwIndex, 0)

        val boostPct = (clamped - 100).coerceAtLeast(0)
        val playerSessionId = livePlayer?.audioSessionId ?: 0
        if (boostPct > 0 && playerSessionId != 0) {
            try {
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = LoudnessEnhancer(playerSessionId).apply { enabled = true }
                }
                val targetGainMb = boostPct * 12 // +1200 mB (+12 dB) at 200%
                loudnessEnhancer?.setTargetGain(targetGainMb)
                loudnessEnhancer?.enabled = true
                ui.audioEffectOn.value = true
            } catch (_: Throwable) {
                // Hardware audio session unavailable
            }
        } else {
            loudnessEnhancer?.setTargetGain(0)
            loudnessEnhancer?.enabled = false
            ui.audioEffectOn.value = false
        }
    }

    fun onApplyBrightnessFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0.02f, 1.0f)
        ui.screenBrightnessFraction.floatValue = clamped
        val window = activity?.window ?: return
        val params = window.attributes
        params.screenBrightness = clamped
        window.attributes = params
    }

    fun onSetPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        livePlayer?.playbackParameters = PlaybackParameters(clamped)
    }

    fun onCycleDecoderMode() {
        ui.bumpInteraction()
        onRebuildDecoderCycle()
    }

    fun onApplySubtitleOffsetSec(offsetSec: Float) {
        ui.subtitleOffsetSec.floatValue = offsetSec
        onApplyEngineSubtitleOffsetSec(offsetSec)
        ui.syncToastVisible.value = true
    }

    fun onTriggerAutoSync() {
        quick.subSyncRunning.value = true
        onTriggerEngineAutoSync()
    }

    fun onCaptureScreenshot() {
        ui.bumpInteraction()
        onCaptureVideoFrame()
    }

    fun release() {
        exactSeekRestoreJob?.cancel()
        try {
            loudnessEnhancer?.release()
        } catch (_: Throwable) {
        }
        loudnessEnhancer = null
    }
}

@Composable
fun rememberPlayerScreenActions(
    context: Context,
    activity: Activity?,
    livePlayer: ExoPlayer?,
    ui: PlayerUiState,
    gestures: GestureUiState,
    quick: QuickRowUiState,
    scope: CoroutineScope,
    decoderModeLabel: State<String>,
    isRebuildingDecoder: State<Boolean>,
    onRebuildDecoderCycle: () -> Unit,
    onApplyEngineSubtitleOffsetSec: (Float) -> Unit,
    onTriggerEngineAutoSync: () -> Unit,
    onCaptureVideoFrame: () -> Unit,
): PlayerScreenActions = remember(
    livePlayer,
    ui,
    gestures,
    quick,
    scope,
    decoderModeLabel,
    isRebuildingDecoder,
) {
    PlayerScreenActions(
        context = context,
        activity = activity,
        livePlayer = livePlayer,
        ui = ui,
        gestures = gestures,
        quick = quick,
        scope = scope,
        decoderModeLabel = decoderModeLabel,
        isRebuildingDecoder = isRebuildingDecoder,
        onRebuildDecoderCycle = onRebuildDecoderCycle,
        onApplyEngineSubtitleOffsetSec = onApplyEngineSubtitleOffsetSec,
        onTriggerEngineAutoSync = onTriggerEngineAutoSync,
        onCaptureVideoFrame = onCaptureVideoFrame,
    )
}
```

---

### FILE 10: `PlayerScreenEffects.kt`
**Role**: Layer 1 SeekRippleOverlay (double-tap optical glass seek arcs on the left <35% and right >65% screen zones), background ScrubPreviewEffect frame bitmap extractor with 24-frame LruCache, and transient status pill auto-hide timers (5.0s Resume Prompt, 2.5s Subtitle Sync Toast).

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

---

### FILE 11: `PlayerScreenVideo.kt`
**Role**: Layer 0 PlayerVideoSurface binding hardware SurfaceView to ExoPlayer, first-frame poster art cache crossfade, and hardware-accelerated graphicsLayer aspect ratio & zoom mode transformations (FIT, FILL, ZOOM, 16:9, 4:3).

```kotlin
package dev.anonrode.player.ui

import android.graphics.Bitmap
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer

/**
 * Layer 0 Decoder Surface (`§3.1`).
 * Binds `SurfaceView` to `ExoPlayer` and applies hardware-accelerated aspect/zoom transforms
 * without triggering view hierarchy relayout stalls.
 */
@Composable
fun PlayerVideoSurface(
    player: ExoPlayer?,
    videoWidth: Int,
    videoHeight: Int,
    zoomIdx: Int,
    posterArtBitmap: Bitmap?,
    hasRenderedFirstFrame: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surfaceView = remember(context) {
        SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    DisposableEffect(player, surfaceView) {
        player?.setVideoSurfaceView(surfaceView)
        onDispose {
            player?.clearVideoSurfaceView(surfaceView)
        }
    }

    val mode = AspectZoomMode.entries[zoomIdx.mod(AspectZoomMode.entries.size)]
    val naturalAspect = if (videoWidth > 0 && videoHeight > 0) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        16f / 9f
    }

    val targetAspect = when (mode) {
        AspectZoomMode.SIXTEEN_NINE -> 16f / 9f
        AspectZoomMode.FOUR_THREE -> 4f / 3f
        else -> naturalAspect
    }

    val targetScaleX by animateFloatAsState(
        targetValue = when (mode) {
            AspectZoomMode.FILL -> 1.18f
            AspectZoomMode.ZOOM -> 1.34f
            else -> 1.0f
        },
        animationSpec = tween(260, easing = OpticalGlassTokens.EaseReveal),
        label = "videoScaleX",
    )
    val targetScaleY by animateFloatAsState(
        targetValue = when (mode) {
            AspectZoomMode.FILL -> 1.08f
            AspectZoomMode.ZOOM -> 1.34f
            else -> 1.0f
        },
        animationSpec = tween(260, easing = OpticalGlassTokens.EaseReveal),
        label = "videoScaleY",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier
                .aspectRatio(targetAspect, matchHeightConstraintsFirst = false)
                .graphicsLayer {
                    scaleX = targetScaleX
                    scaleY = targetScaleY
                },
        )

        // First-Frame Poster Art Cache Crossfade
        AnimatedVisibility(
            visible = !hasRenderedFirstFrame && posterArtBitmap != null && !posterArtBitmap.isRecycled,
            exit = fadeOut(tween(220)),
        ) {
            if (posterArtBitmap != null && !posterArtBitmap.isRecycled) {
                Image(
                    bitmap = posterArtBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
```

---

### FILE 12: `LibraryScreen.kt` (Section 10 Library Hub Cards)
**Role**: Implements §10.1 ContinueCard (pure 140.dp × 80.dp 16:9 card with zero extraneous text below, bottom-left duration pill, and 3.dp progress bar) and §10.2 SeriesCard (strict modifier ordering .fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(88.dp), theme-aware BorderStroke(1.dp, text.copy(alpha = 0.08f)), 112.dp × 68.dp thumbnail progress bar, and Color.White play icon tint).

```kotlin
package dev.anonrode.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * §10.1 Continue Watching (`ContinueCard`):
 * - Pure 140dp x 80dp (16:9) card (`AGENT.md` §4.1).
 * - All extraneous text labels below the card are removed.
 * - Overlays: Bottom-left duration pill (`▶ 24:12`) and bottom edge 3dp progress bar.
 */
@Composable
fun ContinueCard(
    thumbnail: Bitmap?,
    remainingOrDurationLabel: String,
    progressFraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 140.dp, height = 80.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111827))
            .clickable(onClick = onClick),
    ) {
        if (thumbnail != null && !thumbnail.isRecycled) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Bottom-left duration pill (`▶ 24:12`)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 7.dp)
                .opticalGlass(
                    shape = RoundedCornerShape(5.dp),
                    baseColor = Color.Black.copy(alpha = 0.58f),
                    borderWidth = 0.8.dp,
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "▶ $remainingOrDurationLabel",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }

        // Bottom edge 3dp progress bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(OpticalGlassTokens.CyanAccent),
            )
        }
    }
}

/**
 * §10.2 Series Hub (`SeriesCard`):
 * - Strict Modifier Ordering: `.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(88.dp)`
 *   so vertical padding never reduces the 88dp row height or clips thumbnails.
 * - Theme-Aware Border: `BorderStroke(1.dp, paletteTextColor.copy(alpha = 0.08f))`.
 * - Thumbnail Progress Bar along the bottom edge of the 112dp x 68dp thumbnail.
 * - Play Button Contrast: Icon tint explicitly set to `Color.White`.
 */
@Composable
fun SeriesCard(
    title: String,
    episodeCountLabel: String,
    lastWatchedSubtitle: String,
    thumbnail: Bitmap?,
    watchedFraction: Float,
    paletteSurfaceColor: Color,
    paletteTextColor: Color,
    onCardClick: () -> Unit,
    onQuickPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(88.dp),
        shape = RoundedCornerShape(14.dp),
        color = paletteSurfaceColor,
        border = BorderStroke(1.dp, paletteTextColor.copy(alpha = 0.08f)),
        onClick = onCardClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 112dp x 68dp Thumbnail with bottom watched progress bar
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B)),
            ) {
                if (thumbnail != null && !thumbnail.isRecycled) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (watchedFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(watchedFraction.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(OpticalGlassTokens.CyanAccent),
                        )
                    }
                }
            }

            // Series Title & Episode Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = paletteTextColor,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = episodeCountLabel,
                    maxLines = 1,
                    style = TextStyle(
                        color = paletteTextColor.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = lastWatchedSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = OpticalGlassTokens.CyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }

            // High-Contrast Play Button (Explicit Color.White glyph tint)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(OpticalGlassTokens.CyanAccent)
                    .clickable(onClick = onQuickPlayClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

---

## 3. MANDATORY VERIFICATION & BENCHMARK MATRIX FOR THE AI AGENT

Execute and verify every item in this matrix before marking the task complete:

| # | Subsystem Invariant | Implementation Mechanism | Required Verification |
| :--- | :--- | :--- | :--- |
| **1** | **0 Hz Scrubber Track Recomposition** | `OpticalSeekBarCanvas` reads `positionSec.value` & `bufferedSec.value` strictly inside `Modifier.drawWithCache { onDrawBehind { ... } }` | Layout Inspector shows 0 recompositions on `OpticalSeekBarCanvas` during active 60fps playback. |
| **2** | **1 Hz Timestamp Recomposition** | `displayPosIntSec` wrapped in `remember { derivedStateOf { positionSec.value.toInt() } }` | `SeekBarRow` elapsed/remaining `Text` recomposes at most once per second (down from 20 Hz). |
| **3** | **Top Bar & Ribbon 0 Hz Skip** | `PlayerScreenTopBar` & `QuickToolsRibbon` do not read `positionSec`, `decoderModeLabel`, or `liveOffsetMs` | Layout Inspector shows 100% skipped ticks for Back button, `[CC]`, `[Playlist]`, `[⋮]`, and Ribbon discs. |
| **4** | **Tech Truth Leaf Isolation** | `PlayerTopBarTitleAndTechLine` encapsulates `videoResolutionFpsLabel`, `decoderModeLabel`, and `liveOffsetMs` ($\ge 50\,\text{ms}$ quantized) | Tapping `[HW+SW]` or slewing audio-sub sync only recomposes `PlayerTopBarTitleAndTechLine`. |
| **5** | **Optical Glass over SurfaceView** | `Modifier.opticalGlass()` caches Outline, 3-stop directional specular border, and top luminance highlight | Zero `Brush` or `Path` allocations per frame; 100% readable over pure `#FFFFFF` video frames. |
| **6** | **Unscrimmed Left Video on Drawers** | `PlayerSlideInDrawer` full-screen backdrop uses `Color.Black.copy(alpha = 0.06f)` | Left $\sim 65\%$ of the video surface stays 100% bright and unobstructed while $350\,\text{dp}$ drawers are open. |
| **7** | **Subtitle Hit-Test & Bounds Yield** | `PlayerSubtitleOverlay` sits above `playerGestureLayer` in Layer 1; `isInsideSubtitle(offset)` guards all 4 video gesture detectors | Vertical anchor drag, horizontal sync drag, and 2-finger pinch on subtitles never trigger video seeks or Hold-to-Boost. |
| **8** | **Hold-to-Boost Scrub Lockout** | `absDx > 1.5f * absDy` checks `!ui.boostActive.value && !ui.speedScrubHudVisible.value` before entering `GestureMode.SEEK` | Horizontal finger sliding during 2.0x Hold-to-Boost steps `[0.5x..3.0x]` milestones ($34\,\text{dp/step}$) and never seeks the timeline. |
| **9** | **Keyframe Seek Cancellation Recovery** | `onDragCancel` invokes `actions.onCancelKeyframeSeek() -> player.setSeekParameters(SeekParameters.EXACT)` | System gesture interruption during horizontal scrub never leaves ExoPlayer in `CLOSEST_SYNC` mode. |
| **10** | **Dynamic Sync Adjustment Window** | `abs(currentOffset - baseOffset) > 4.8f` auto-recenters `ui.subtitleBaseOffsetSec` | Large auto-sync offsets (e.g. $+35.0\,\text{s}$) are preserved in $[+30.0\text{s} .. +40.0\text{s}]$ and never clamped to $[-5\text{s}, +5\text{s}]$. |
