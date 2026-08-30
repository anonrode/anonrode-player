package dev.anonrode.player.ui

import android.view.View
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/* ── remembered UI state holders ───────────────────────────────────────────
 * PlayerScreen used to declare ~50 locals that one giant lambda captured.
 * The state now lives in small, theme-focused holder classes created with
 * remember{} in PlayerScreen, so every holder (and the MutableState objects
 * inside it) keeps a stable identity across recompositions. Lambdas passed
 * into pointerInput blocks capture the holders — NOT snapshot values — so
 * reads inside gesture handlers stay live exactly like the pre-split `by
 * remember` delegates did.
 * ------------------------------------------------------------------------- */

/**
 * Core transport state: controls visibility, play/buffer/lock flags, zoom,
 * the seekbar drag position and the first-frame poster.
 *
 * Marked [Stable] because Compose can rely on the holder identity (the
 * remember{} in PlayerScreen) and on every contained [androidx.compose.runtime.MutableState]
 * being created exactly once with that holder, making `equals` effectively
 * referential. The compiler skips the parameter-equality check on parameters
 * of type PlayerUiState across recompositions, which is the difference
 * between PlayerControlsOverlay getting skipped vs. recomposed every tick.
 */
@Stable
@UnstableApi
internal class PlayerUiState(initialIsPlaying: Boolean) {
    val controlsVisible = mutableStateOf(true)
    val isPlaying = mutableStateOf(initialIsPlaying)

    /** True while the player is stalled/buffering — drives the spinner. */
    val isBuffering = mutableStateOf(false)
    val locked = mutableStateOf(false)

    /**
     * True while the hold-to-2× speed boost gesture is engaged. Guards
     * against re-entrant gestures double-applying the speed change.
     */
    val boostActive = mutableStateOf(false)
    val showCC = mutableStateOf(true)
    val menuOpen = mutableStateOf(false)

    /** Index into [ZoomModes]: FIT → CROP → STR → 16:9 → 4:3. */
    val zoomIdx = mutableIntStateOf(0)
    val playerViewRef = mutableStateOf<PlayerView?>(null)

    /**
     * First-frame poster. Drawn over the player view so the gap between
     * "video opens" and "first frame paints" doesn't show as a black
     * flash. Set asynchronously by FirstFramePosterEffect; cleared when
     * the player's [androidx.media3.common.Player.Listener] reports
     * STATE_READY.
     */
    val posterBitmap = mutableStateOf<ImageBitmap?>(null)

    /**
     * Seekbar drag position in seconds; -1 = not dragging. Seeking is
     * applied once on release instead of firing player.seekTo() per pixel
     * of drag.
     */
    val localSeek = mutableFloatStateOf(-1f)

    /** -1 left, +1 right, 0 none — double-tap seek flash side. */
    val flashSide = mutableIntStateOf(0)

    /**
     * Rotation-lock flag mirrored into the activity orientation by
     * RotationLockEffect. Kept for parity with the pre-split state set.
     */
    val rotationLocked = mutableStateOf(false)
}

/**
 * Gesture HUD pill + in-overlay transient toast.
 *
 * Audit #15 (preserved): both auto-hide runnables are created ONCE with the
 * holder (equivalent to remember{}) so their identity is stable across
 * recompositions — a fresh Runnable per composition made
 * view.removeCallbacks() miss the previously posted instance, letting a
 * stale timeout hide the HUD / clear the toast early.
 *
 * Marked [Stable] so Compose treats the holder reference as a stable
 * parameter — the GestureHudPill / PlayerOverlayToast call sites skip
 * recomposition unless the contained MutableState objects actually change.
 */
@Stable
internal class HudUiState {
    val icon = mutableStateOf<ImageVector?>(null)
    val text = mutableStateOf("")
    val visible = mutableStateOf(false)

    /** Generic in-overlay toast banner (also used for small action feedback). */
    val transientToast = mutableStateOf<String?>(null)

    /** Single hide runnable so back-to-back showHud calls (drag ticks, the
     *  boost keep-alive) can't have a stale timeout hide the pill early. */
    val hideHudRunnable = Runnable { visible.value = false }
    val clearToastRunnable = Runnable { transientToast.value = null }

    fun showHud(view: View, hudIcon: ImageVector, hudText: String) {
        icon.value = hudIcon
        text.value = hudText
        visible.value = true
        view.removeCallbacks(hideHudRunnable)
        view.postDelayed(hideHudRunnable, 900)
    }

    /**
     * In-screen toast banner that lives inside the player overlay (so the
     * in-pip / system-toast gap doesn't pop while the video is playing).
     * Auto-clears after 1.6s.
     */
    fun showTransientToast(view: View, msg: String) {
        transientToast.value = msg
        view.removeCallbacks(clearToastRunnable)
        view.postDelayed(clearToastRunnable, 1600L)
    }
}

/**
 * Sleep timer: wall-clock expiry so re-arming mid-countdown simply moves
 * the deadline. Null endMs = no countdown armed.
 *
 * Marked [Stable] — the PlayerOverflowMenu / SleepTimerEffect both see a
 * stable holder; only its contained MutableState objects can change, so
 * the sleep dropdown doesn't recompose when unrelated UI state changes.
 */
@Stable
internal class SleepTimerUiState {
    val endMs = mutableStateOf<Long?>(null)

    /** True when armed for "end of episode" instead of a countdown. */
    val atEpisodeEnd = mutableStateOf(false)

    /** Last ticked remainder, purely for badge display. */
    val remainingMs = mutableLongStateOf(0L)

    /** Chosen dropdown entry, for the checkmark (reset to Off on fire). */
    val selection = mutableStateOf(SleepOptions.first())

    val active: Boolean get() = endMs.value != null || atEpisodeEnd.value

    fun selectSleep(opt: SleepOption) {
        when {
            opt.minutes > 0 -> {
                atEpisodeEnd.value = false
                remainingMs.value = opt.minutes * 60_000L
                endMs.value = System.currentTimeMillis() + remainingMs.value
            }
            opt.minutes < 0 -> { // End of episode
                endMs.value = null
                atEpisodeEnd.value = true
            }
            else -> { // Off
                endMs.value = null
                atEpisodeEnd.value = false
            }
        }
        selection.value = opt
    }

    /** Checkmark condition for the dropdown entry matching the armed timer. */
    fun isSelected(opt: SleepOption): Boolean = when {
        opt.minutes > 0 ->
            endMs.value != null && !atEpisodeEnd.value && selection.value == opt
        opt.minutes < 0 -> atEpisodeEnd.value
        else -> !active
    }
}

/**
 * Gesture scratch state (drag mode anchors, stage size) plus the subtitle
 * cue placement (box center as stage fractions) and drag state.
 *
 * Marked [Stable]. Pointer-input blocks in PlayerScreenGestures.kt capture
 * the holder via [PlayerScreenActions.gestures]; with a stable identity the
 * captured lambdas re-read scratch state via `gestures.foo.floatValue`
 * without forcing a relaunch. (Constructor stability matters here too —
 * the gesture holders are held inside the now-remembered actions object.)
 */
@Stable
internal class GestureUiState {
    val scrW = mutableFloatStateOf(1000f)
    val scrH = mutableFloatStateOf(1000f)

    /** Active drag family: "seek" | "vol" | "bri", null = none. */
    val mode = mutableStateOf<String?>(null)
    val startX = mutableFloatStateOf(0f)
    val startY = mutableFloatStateOf(0f)
    val lastX = mutableFloatStateOf(0f)
    val startPosMs = mutableFloatStateOf(0f)
    val startVol = mutableIntStateOf(0)
    val startBri = mutableFloatStateOf(0.5f)

    val subX = mutableFloatStateOf(SUB_DEFAULT_X)
    val subY = mutableFloatStateOf(SUB_DEFAULT_Y)
    val subDragging = mutableStateOf(false)

    /** Long-press dropdown (Size / Position / Color / Reset) is open. */
    val subStyleMenuOpen = mutableStateOf(false)

    /**
     * First-invocation skip flag for the style Position preset effect, so
     * the per-video saved position wins on entry.
     */
    val subPosInitialized = mutableStateOf(false)
}

/**
 * Quick-row + overflow feature state. Each backs a button with at least one
 * observable side-effect on tap (toast / overlay / log line) so the user
 * can tell the click registered.
 *
 * Marked [Stable] — the PlayerQuickRow reads chips (equalizerOn, castRouteName,
 * etc.) directly off this holder; stability means a chip flipping doesn't
 * recompose its unrelated neighbours.
 */
@Stable
internal class QuickRowUiState(initialHwDecoder: Boolean) {
    val equalizerOn = mutableStateOf(false)

    /** When true, shows the EqualizerPanelSheet with band sliders. */
    val eqPanelOpen = mutableStateOf(false)
    val headphonesOn = mutableStateOf(false)
    val hwDecoder = mutableStateOf(initialHwDecoder)

    /** True = locked to portrait, false = sensor/landscape. */
    val portraitForced = mutableStateOf(false)

    /**
     * Three-state rotation mode (sensor / landscape / portrait). Set by
     * the right-rail rotate button (cycle on tap, jump on long-press).
     * When the user is in [RotateMode.PORTRAIT] this stays in sync with
     * [portraitForced]; [RotateMode.LANDSCAPE] is the new state the
     * redesigned rail introduces.
     */
    val rotateMode = mutableStateOf(RotateMode.SENSOR)

    val showSyncPopover = mutableStateOf(false)

    /** The currently active audio track label, for the audio-track popover. */
    val audioTrackToast = mutableStateOf<String?>(null)

    /**
     * User-driven subtitle sync toggle. Mirrors
     * [dev.anonrode.player.core.datastore.PlayerSettings.subtitleAutoSyncEnabled]
     * so the toggle icon flips instantly without a DataStore round-trip.
     * Persisted to DataStore on every change.
     */
    val subSyncEnabled = mutableStateOf(false)

    /**
     * True while a live re-lock is in progress. Drives the spinning ring
     * on the right-rail sync icon. Set by the Activity's openVideo path
     * around the AudioSyncProcessor's setCues call; cleared when the
     * first lock lands or the engine gives up.
     */
    val subSyncRunning = mutableStateOf(false)
}
