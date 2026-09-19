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

    /** Index into [ZoomModes]: FIT → CROP → STR → 16:9 → 4:3. */
    val zoomIdx = mutableIntStateOf(0)
    val playerViewRef = mutableStateOf<PlayerView?>(null)

    /**
     * Measured heights (px) of the top bar and the bottom chrome block,
     * v0.7.3 overlay anchoring. Overlays (A-B chip, toast, Up Next pill,
     * sync popover) sit off these numbers instead of the old hand-tuned
     * `top = 70 / bottom = 140 / 210` dp magic that collided with the
     * chrome and broke on every shape change. Bars publish into them via
     * onSizeChanged and NEVER shrink them back to 0 (the values stay stale
     * while the chrome auto-hides so anchored overlays don't jump).
     */
    val topBarHeightPx = mutableIntStateOf(0)
    val bottomBarHeightPx = mutableIntStateOf(0)

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

    /**
     * Scrub frame preview (v0.7.1 MX-style scrub): the throttled, scaled
     * frame at the current scrub target — slider drag OR swipe gesture —
     * decoded by ScrubPreviewEffect while scrubbing, null otherwise.
     * Read only by the scrub bubble in the seek bar row.
     */
    val scrubPreview = mutableStateOf<ImageBitmap?>(null)

    /** -1 left, +1 right, 0 none — double-tap seek flash side. */
    val flashSide = mutableIntStateOf(0)
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

    /**
     * Pending scrub target of an in-flight seek gesture (ms; −1 = none).
     * The drag itself no longer seeks per pointer event — the single
     * commit fires on drag end (PlayerScreenGestures onDragEnd).
     */
    val pendingSeekMs = mutableFloatStateOf(-1f)
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
 * Marked [Stable] — the dock's utility row reads these directly; stability
 * means a chip flipping doesn't recompose its unrelated neighbours.
 *
 * v0.7.3 purge: `eqPanelOpen` (never read — the EQ panel is a host sheet),
 * `portraitForced` + the legacy two-state rotation path (the 3-state
 * [rotateMode] is the single source of truth), and `audioTrackToast`
 * (never read) are gone.
 */
@Stable
internal class QuickRowUiState(initialHwDecoder: Boolean) {
    val equalizerOn = mutableStateOf(false)
    val hwDecoder = mutableStateOf(initialHwDecoder)

    /**
     * Three-state rotation mode (sensor / landscape / portrait) — the
     * single rotation truth. The dock's rotate button cycles on tap and
     * jumps on long-press; RotationLockEffect mirrors it into the activity
     * orientation.
     */
    val rotateMode = mutableStateOf(RotateMode.SENSOR)

    val showSyncPopover = mutableStateOf(false)

    /**
     * User-driven subtitle sync toggle. Mirrors
     * [dev.anonrode.player.core.datastore.PlayerSettings.subtitleAutoSyncEnabled]
     * so the toggle icon flips instantly without a DataStore round-trip.
     * Persisted to DataStore on every change.
     */
    val subSyncEnabled = mutableStateOf(true)

    /**
     * True while a sync pass is actually working (live correlation window
     * or a running fingerprint). Drives the sync hero chip's "Syncing…"
     * state. Set by the host; cleared when a lock lands or the engine
     * gives up — never decorative.
     */
    val subSyncRunning = mutableStateOf(false)
}
