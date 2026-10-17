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
