# PLAYER UI v1.0.0 — BATCH 1 SPECIFICATION
## Subsystem: Core Contracts, State Models & Optical Glass HUD
### Package: `dev.anonrode.player.ui` | TargetSdk: 37 | Android 14+

---

## 1. Batch Overview & Role in Subsystem

Batch 1 establishes the foundational data contracts, reactive state holders, callback dispatchers, and hardware-accelerated optical glass shaders.
All subsequent batches (Gestures, Scrubber Dock, Controls Chrome, Subtitles, and 4-Slot Drawers) depend on the symbols and shaders established here.

### Files in this Batch:
1. **`app/src/main/java/dev/anonrode/player/ui/PlayerScreenState.kt`** (File 1 from Master Blueprint)
   - Stable, zero-allocation state holders (`PlayerUiState`, `GestureUiState`, `QuickRowUiState`).
   - Type-safe gesture enums (`GestureMode.NONE`, `SEEK`, `VOLUME`, `BRIGHTNESS`, `SUBTITLE_*`).
   - 200% audio boost state, dynamic subtitle sync window state, and discrete speed milestones (`0.5x..3.0x`).
2. **`app/src/main/java/dev/anonrode/player/ui/PlayerScreenActions.kt`** (File 9 from Master Blueprint)
   - Callback dispatcher capturing `ExoPlayer`, `AudioManager`, `LoudnessEnhancer`, and `Window` references.
   - Continuous 0–200% volume/boost curve (0–100% hardware `STREAM_MUSIC`, 101–200% `LoudnessEnhancer` up to +12 dB).
   - Fast `SeekParameters.CLOSEST_SYNC` keyframe scrubbing and `SeekParameters.EXACT` cancellation recovery.
3. **`app/src/main/java/dev/anonrode/player/ui/PlayerScreenHud.kt`** (File 2 from Master Blueprint)
   - `Modifier.opticalGlass()` shader engine: 3-stop directional specular rim brush, top luminance highlight, smoked obsidian base over hardware `SurfaceView`.
   - Reusable `OpticalGlassDiscButton` and `OpticalGlassChip` with concentric padding and expanded hit-targets.
   - Edge-hugged 34dp x 176dp vertical glass tubes (Brightness left, 200% Volume right with Amber color shift and haptic detent at 100%).
   - Center HUD pill for Hold-to-Boost discrete speed milestones and progressive scrub readouts.

---

## 2. Full Kotlin Source Code

### FILE 1: `PlayerScreenState.kt`
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

### FILE 9: `PlayerScreenActions.kt`
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

### FILE 2: `PlayerScreenHud.kt`
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
