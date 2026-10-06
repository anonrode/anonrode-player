package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.PlayerPrefs
import dev.anonrode.player.audio.SubtitleStyle
import dev.anonrode.player.core.ui.theme.rememberSkinPalette
import dev.anonrode.player.feature.player.PlaybackEngine
import kotlin.math.abs

/**
 * How long the ribbon's scroll offset must sit still before it is written to
 * SharedPreferences. A fling emits a value per frame; without a settle window
 * that is a disk write per frame. 400ms is long enough to swallow a drag and
 * short enough that the offset is on disk before the user can plausibly kill
 * the app.
 */
private const val RIBBON_SCROLL_PERSIST_DEBOUNCE_MS = 400L

/**
 * Full-bleed video player — v0.7.3 curated-dock chrome.
 *
 *   Top bar (auto-hide)   ‹  Title                     ⧉   🔒   ⋮
 *   Seek row (ALWAYS)     04:12 ━━━━━●━━━━━━━━ 18:30   (buffered shown,
 *                                                     tap time to flip)
 *   Transport (auto-hide)      ‹N  ⏮  ▶(64)  ⏭  N›
 *   Utility  (auto-hide)  [✨ sync]  [speed]  CC  ♫  [aspect]  ↻
 *
 * Layout principles enforced by this redesign (each was a real defect in
 * v0.7.2's chrome — see the round's audit):
 *   • ONE home per control: the right-edge rail, the duplicated rotate /
 *     sync / more buttons, and the Aspect ≡ Zoom sheet aliases are gone.
 *   • ONE layout at every width: no more 540dp two-branch row that
 *     reordered controls by device class and overflowed portrait phones.
 *   • Labeled where it matters: sync (the flagship) carries its state as
 *     text; speed/aspect show their current values; skip pills show the
 *     REAL configured step.
 *   • Real safe-area handling (statusBar / navBar / displayCutout) — the
 *     build is edge-to-edge on targetSdk 37.
 *   • Overlays anchor off the MEASURED chrome heights instead of the old
 *     hand-tuned `top = 70 / bottom = 140 / 210` dp magic that collided.
 *
 * The subtitle cue is high-contrast outlined (bold + black outline, no box) and can
 * be long-press dragged anywhere; its position persists per video with a
 * global default fallback (see [dev.anonrode.player.PlayerPrefs]). While
 * in PiP ([isPipMode]) every overlay hides; while controls are locked
 * only the lock badge stays (long-press anywhere unlocks — unchanged).
 *
 * Structure: this function is the orchestrator only. State lives in the
 * remembered holders in PlayerScreenState.kt, callbacks in
 * PlayerScreenActions.kt, side-effects in PlayerScreenEffects.kt, gestures
 * in PlayerScreenGestures.kt, and each visual chunk in its own file
 * (Video / Subtitles / Controls / BottomBar / Chips / Hud / Sheets).
 */
@UnstableApi
@Composable
fun PlayerScreen(
    /**
     * Playback engine that owns the [Player]. We read [PlaybackEngine.player]
     * off the engine every render so that decoder swaps (which tear down the
     * ExoPlayer and create a fresh one) are picked up by the Compose tree
     * without having to re-invoke the whole [PlayerScreen] composable. The
     * [player] parameter is kept for backwards compatibility with the
     * legacy call sites that pass a [Player] directly, but if [engine] is
     * provided it takes precedence.
     */
    player: Player,
    engine: PlaybackEngine? = null,
    title: String,
    cueText: String?,
    /**
     * Live playback position (seconds) and duration, as STATE — v0.7.1 perf
     * pass. Plain Float params made every 10Hz render-tick write a new value
     * into the whole 65-parameter PlayerScreen body, recomposing top bar,
     * dock and pills 10x/second. As State params, a tick recomposes ONLY the
     * composables that read .value (the seek bar + its labels); everything
     * else stays skipped.
     */
    positionSec: State<Float>,
    durationSec: State<Float>,
    /** Buffered position (seconds) — the seek bar's second track (v0.7.3). */
    bufferedSec: State<Float>,
    onBack: () -> Unit,
    initialSpeed: Float = 1f,
    onSpeedChanged: (Float) -> Unit = {},
    isPipMode: Boolean = false,
    onEnterPip: () -> Unit = {},
    hasNextEpisode: Boolean = false,
    hasPreviousEpisode: Boolean = false,
    onPlayNext: () -> Unit = {},
    onPlayPrevious: () -> Unit = {},
    nextCountdownSec: Int = -1,
    onCancelNext: () -> Unit = {},
    onHoldAutoAdvance: () -> Unit = {},
    /** Clean display name of the next episode, for the Up Next surfaces. */
    upNextTitle: String? = null,
    /** Stable per-video id (content uri) for per-video preferences. */
    mediaId: String = "",
    /** Open the global settings screen. */
    onOpenSettings: () -> Unit = {},
    /** Live subtitle offset (ms, signed) reported by the sync engine. */
    liveOffsetMs: Long = 0L,
    /**
     * v0.7.1: true while the sync engine is actually working (live
     * correlation in flight or a forced fingerprint running). v0.7.3:
     * merged with [isCalibrating] into the sync hero chip's "Syncing…"
     * state (the old top-left banner is retired).
     */
    subSyncRunning: Boolean = false,
    /** True when a verified sync lock is active (live or persisted). */
    isSyncLocked: Boolean = false,
    /** True while a fresh calibration pass is running. */
    isCalibrating: Boolean = false,
    /** Start a new calibration pass (popover's RE-SYNC row). */
    onStartCalibration: () -> Unit = {},
    /** Apply a manual ±0.1s nudge to the subtitle offset. */
    onNudgeSubtitle: (Long) -> Unit = { _ -> },
    /**
     * v0.6.2 sub-sync UX pass: persist the user-facing sync toggle
     * (DataStore + fingerprint job gate). The hero chip's state flips
     * instantly via [quick.subSyncEnabled]; this callback is for
     * persistence and the gate side-effects (cancel pending jobs when
     * turning OFF; schedule one when turning ON).
     */
    onSetSubSyncEnabled: (Boolean) -> Unit = {},
    /** Long-press on the sync hero chip: "Resync now". */
    onResyncNow: () -> Unit = {},
    /**
     * Request a real decoder profile swap. The host rebuilds the ExoPlayer
     * via [dev.anonrode.player.feature.player.PlaybackEngine.rebuild] and
     * returns the new audio session id (0 if the swap is still in flight).
     *
     * The engine has THREE profiles (HW+SW -> APP -> HW) and the host always
     * advances one step, so the `Boolean` this callback is handed is advisory
     * only: the screen derives its chip from [decoderModeLabel] instead, which
     * the host refreshes from the engine after every swap. The argument is kept
     * for the existing signature.
     */
    onRebuildDecoder: (Boolean) -> Int = { _ -> 0 },
    /**
     * The decoder profile the engine is actually on: "HW+SW", "APP" or "HW".
     *
     * The screen no longer keeps its own HW/SW boolean. The engine has three
     * profiles and a two-state flag cannot name them, so the chip used to
     * disagree with the engine — it could read "SW" while the engine ran the
     * default HW+SW hybrid. Mirrors `PlaybackEngine.decoderModeLabel`; passed
     * down because the engine's own field is not observable and would not
     * trigger a recomposition. The Boolean argument of [onRebuildDecoder] is
     * retained only so existing call sites keep compiling; it is ignored.
     */
    decoderModeLabel: String = "HW+SW",
    /**
     * Tell the host whether the user has pinned rotation. When false, the
     * host's auto-landscape-on-widescreen behaviour stays out of the way —
     * otherwise it overwrote an explicit lock that nothing ever restored.
     */
    onAutoRotateChanged: (Boolean) -> Unit = {},
    /**
     * Toggle the system equalizer (Control Center tile). The host creates /
     * enables / disables the [android.media.audiofx.Equalizer] bound to the
     * current audio session and reports back the new on/off state; the
     * screen mirrors it into [quick.equalizerOn] for the tile's visual.
     */
    onToggleEqualizer: (Boolean) -> Boolean = { it },
    /**
     * Open the Cast (MediaRouter) route picker — the Control Center's one
     * "Audio output" tile (the old Cast + Speaker + Headphones triple is
     * merged; MediaRouter covers every real output).
     */
    onOpenCastPicker: () -> Unit = {},
    /**
     * Open the 5-band equalizer panel (Control Center EQ tile long-press).
     * Dead end no more: wired since v0.6, never reachable since.
     */
    onOpenEqPanel: () -> Unit = {},
    /**
     * Open the subtitle style picker (size/position/color) — Control
     * Center Subtitles section.
     */
    onOpenSubStyle: () -> Unit = {},
    /**
     * Subtitle style (size / position / color) the cue is rendered from.
     * Owned by the host and shared with its SubtitleStyleSheet so the
     * sheet's live preview matches the on-screen cue. In-screen mutations
     * (long-press dropdown) are reported via [onSubtitleStyleChanged].
     */
    subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /**
     * Called when the user mutates the subtitle style from inside the
     * screen (long-press dropdown). The host should persist the value and
     * flow it back through [subtitleStyle].
     */
    onSubtitleStyleChanged: (SubtitleStyle) -> Unit = {},
    /**
     * Open the subtitle source picker (embedded tracks / sidecar files /
     * downloaded / online search) — Control Center Subtitles section.
     */
    onOpenSubtitlePicker: () -> Unit = {},
    /** Label of the active subtitle source choice ("" = none) — renders
     *  the Subtitle-source tile's current value honestly. */
    subtitleChoiceLabel: String = "",
    /**
     * Open the audio track picker. The host reads [Player.getCurrentTracks]
     * and shows a bottom sheet of available audio tracks for the current
     * media.
     */
    onOpenAudioTrackPicker: () -> Unit = {},
    /** Seek step (seconds) for the ±skip buttons and double-tap seek. */
    seekIncrementSec: Int = 10,
    /**
     * Control Center "Skip length" cycle: report a new 5/10/15/30 step so
     * the host persists it; the pill labels re-render on the way back.
     */
    onSkipLengthChanged: (Int) -> Unit = {},
    /** Settings gates: each mirrors a PlayerSettings toggle. */
    doubleTapSeekEnabled: Boolean = true,
    swipeToSeekEnabled: Boolean = true,
    volumeGestureEnabled: Boolean = true,
    brightnessGestureEnabled: Boolean = true,
    /** Gates the two-finger pinch-to-zoom gesture (Settings → Pinch zoom). */
    pinchZoomEnabled: Boolean = true,
    /**
     * v0.6.2 sub-sync UX pass: the persisted subtitle auto-sync toggle,
     * flowed from the host's DataStore. Mirrored into
     * [quick.subSyncEnabled] below so the hero chip reflects restarts /
     * external Settings edits immediately.
     */
    subtitleAutoSyncEnabled: Boolean = true,
    /**
     * True while ANY host-owned sheet is open (cast picker, EQ panel, audio
     * track, subtitle style, subtitle source, settings). The chrome's
     * auto-hide pauses while this holds — v0.7.2 only honored a
     * permanently-false menuOpen, so chrome faded out under open sheets.
     */
    hostSheetOpen: Boolean = false,
    /** True when the media carries any subtitle source (track or sidecar).
     *  Drives the CC chip's existence — was keyed on "a cue is on screen",
     *  making the toggle blink out between cues. */
    hasSubtitleTrack: Boolean = false,
    /** HUD auto-hide delay while playing (ms). */
    autoHideControlsMs: Long = 3500L,
    /** Sleep timer armed from the settings screen (0=off, -1=end of episode). */
    initialSleepTimerMinutes: Int = 0,
    /** Seeks bigger than this use fast (keyframe) seeking; smaller = exact. */
    fastSeekThresholdSec: Long = 120L,
    /**
     * Per-video zoom mode (index into the screen's ZoomModes), persisted by
     * the host via Room. Restored on entry; [onZoomChanged] reports picks.
     */
    initialZoomIdx: Int = 0,
    onZoomChanged: (Int) -> Unit = {},
    /** Volume boost over system max (0/50/100/200 %); cycled in the sheet. */
    volumeBoostPct: Int = 0,
    onVolumeBoostCycle: () -> Unit = {},
    /**
     * Control Center "Sync log" tile: the host shares the app's own file log
     * filtered to the subtitle-sync decisions of this session
     * ([dev.anonrode.player.SyncLogShare]). Deliberately host-owned — it is
     * the only place that can read app-private storage and it needs the
     * activity's lifecycle scope for the logger's write delay.
     */
    onShareSyncLog: () -> Unit = {},
    /**
     * A-B repeat region (ms). null start = inactive. The host enforces the
     * loop (seek back to A at B); the screen only surfaces state + the tap.
     * Tap cycle: set A → set B (loop starts) → clear.
     */
    abStartMs: Long? = null,
    abEndMs: Long? = null,
    onAbRepeatTap: () -> Unit = {},
    /** True if a decoder swap is currently in flight; the tile dims. */
    isRebuildingDecoder: Boolean = false,
    /** Name of the currently selected Cast route, for the output tile. */
    castRouteName: String? = null,
    /**
     * Persist the background-playback preference. The ribbon's
     * "Background Play" tool routes here so the toggle writes the same
     * field the Settings screen owns (and that onStop consults) instead of
     * flipping a private flag that nothing read.
     */
    onSetBackgroundPlayback: (Boolean) -> Unit = {},
    /**
     * Persist the "Audio Effect" (voice clarity) preference and apply it to
     * the audio pipeline. Default no-op so a caller that doesn't care keeps
     * compiling; PlayerActivity supplies the real handler.
     */
    onSetAudioEffect: (Boolean) -> Unit = {},
    /**
     * Persisted state for the two ribbon toggles that are now backed by
     * real settings, seeded so their icons are right on the first frame
     * instead of flashing the default and correcting a tick later.
     */
    initialBackgroundPlay: Boolean = true,
    initialAudioEffect: Boolean = false,
    /**
     * Persisted playlist mode for the video being opened, so Shuffle/Loop
     * restore their previous state instead of silently resetting to OFF on
     * every open.
     */
    initialShuffle: Boolean = false,
    initialRepeatMode: Int = 0,
    /**
     * Persist the playlist shuffle/repeat pair for a video URI. Supplied by
     * the host because only it can reach the Room-backed state store.
     */
    onPersistPlaylistMode: (uri: String, shuffle: Boolean, repeatMode: Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val activity = context as? Activity
    // Re-derived off the engine every recomposition so decoder swaps are
    // picked up without re-invoking the whole screen.
    val livePlayer: Player = engine?.player ?: player
    val accent = rememberSkinPalette().accent

    // ── remembered UI state (stable identity across recompositions) ──
    val ui = remember { PlayerUiState(initialIsPlaying = livePlayer.isPlaying) }
    val hud = remember { HudUiState() }
    val sleep = remember { SleepTimerUiState() }
    val gestures = remember { GestureUiState() }
    val quick = remember { QuickRowUiState() }

    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    // Keyed on initialSpeed so the pill re-syncs when the activity restores
    // a different persisted speed (e.g. after an auto-advance episode switch).
    val speedIdx = remember(initialSpeed) {
        mutableIntStateOf(speeds.indexOfFirst { abs(it - initialSpeed) < 0.05f }.takeIf { it >= 0 } ?: 2)
    }

    // Shared "more" state — the top bar's ⋮ opens the Control Center and it
    // is the ONLY thing that does: the rail's second "more" was deleted in
    // v0.7.3, so the overflow control has exactly one home. That is a claim
    // about the overflow control, not about every control — the ribbon is a
    // shortcut strip over the sheet's full inventory, so a handful of tools
    // legitimately show up in both (see the file header in
    // PlayerScreenControls.kt for the list).
    val overflowOpen = remember { mutableStateOf(false) }

    // Action surface — INTENTIONALLY REMEMBERED on (livePlayer, engine, ui,
    // hud, sleep, gestures, quick, captureScope). The holder fields above are
    // stable across recompositions, so wrapping PlayerScreenActions in
    // remember(...) keeps a single stable instance per livePlayer swap. The
    // four pointerInput blocks in PlayerScreenGestures.kt capture this same
    // actions reference; with a stable actions identity they do NOT relaunch
    // on every PlayerControlsOverlay recomposition (8–100 ms render-loop tick),
    // which is what was making gestures feel janky during seek/drag.
    //
    // Freshness guarantee: PlayerScreenActions captures `livePlayer` by
    // reference and method bodies read `ui.foo.value` / `hud.showHud(...)` etc.
    // through the holder, so a gesture invocation always sees the latest
    // MutableState snapshot — the same live-read semantics the pre-split
    // local functions had. The keys cover everything that should force a
    // fresh actions instance (decoder swap → new player, lost engine, new
    // holders from a remount). When those keys stay stable, actions stays
    // stable; pointerInput blocks see no relaunch; gestures stay smooth.
    //
    // decoderModeLabel MUST be a key: actions captures it as a plain String,
    // not as State, so without this key the chip would keep rendering the
    // label from before the swap — exactly the staleness this wiring replaced.
    val captureScope = rememberCoroutineScope()
    // `isRebuildingDecoder` MUST be a key. It is the only thing gating
    // cycleDecoderMode(), and the host toggles it without touching any other
    // key here — so a recomposition landing while the flag was true rebuilt
    // `actions` with the stale `true`, and because the guard is what triggers
    // the rebuild, nothing would ever change the value again: the decoder chip
    // stayed locked out until the screen was left and re-entered.
    val actions = remember(
        livePlayer, engine, ui, hud, sleep, gestures, quick, captureScope,
        decoderModeLabel, isRebuildingDecoder,
    ) {
        PlayerScreenActions(
            context = context,
            view = view,
            audioManager = audioManager,
            activity = activity,
            engine = engine,
            livePlayer = livePlayer,
            captureScope = captureScope,
            ui = ui,
            hud = hud,
            sleep = sleep,
            gestures = gestures,
            quick = quick,
            decoderModeLabel = decoderModeLabel,
            onAutoRotateChanged = onAutoRotateChanged,
            speedIdx = speedIdx,
            speeds = speeds,
            seekIncrementSec = seekIncrementSec,
            fastSeekThresholdSec = fastSeekThresholdSec,
            isRebuildingDecoder = isRebuildingDecoder,
            title = title,
            onSpeedChanged = onSpeedChanged,
            onZoomChanged = onZoomChanged,
            onToggleEqualizer = onToggleEqualizer,
            onOpenCastPicker = onOpenCastPicker,
            onOpenAudioTrackPicker = onOpenAudioTrackPicker,
            onRebuildDecoder = onRebuildDecoder,
            onNudgeSubtitle = onNudgeSubtitle,
            onEnterPip = onEnterPip,
            onSetSubSyncEnabled = { onSetSubSyncEnabled(it) },
            onResyncNow = { onResyncNow() },
            onSetBackgroundPlayback = { onSetBackgroundPlayback(it) },
            onSetAudioEffect = { onSetAudioEffect(it) },
            onPersistPlaylistMode = { u, sh, rm -> onPersistPlaylistMode(u, sh, rm) },
            persistUri = { mediaId.ifBlank { null } },
        )
    }

    // ── side-effects: same keys and order as before the split ──

    // Ribbon scroll restore + persistence.
    //
    // This sits here, ABOVE the chrome AnimatedVisibility, on purpose. A
    // LaunchedEffect started inside the controls subtree is disposed the
    // moment the controls auto-hide, which cancels the debounce delay
    // mid-flight and drops the pending write. The most common way to end a
    // ribbon scroll is exactly that — scroll, then let the chrome fade — so a
    // collector living down there would lose the offset precisely when it
    // matters most.
    //
    // Phase 1 restores. ScrollState.maxValue is 0 until layout runs and
    // scrollTo() clamps into that range, so restoring before the row has been
    // measured would silently throw the stored offset away. In a window too
    // wide to scroll, maxValue never rises and the restore simply stays
    // pending for the next narrow one.
    //
    // Phase 2 follows, started only once the restore has landed so the
    // pre-layout value can never be persisted over it. collectLatest + delay
    // debounces a fling into a single write.
    LaunchedEffect(quick.ribbonScroll) {
        snapshotFlow { quick.ribbonScroll.maxValue }.first { it > 0 }
        quick.tryRestoreRibbonScroll()
        snapshotFlow { quick.ribbonScroll.value }
            .distinctUntilChanged()
            .collectLatest { px ->
                delay(RIBBON_SCROLL_PERSIST_DEBOUNCE_MS)
                actions.onRibbonScrolled(px)
            }
    }

    // Restore the persisted ribbon arrangement AND its scroll offset ONCE
    // per screen entry. Keyed on nothing (runs a single time) so a user's
    // reorder survives without re-reading SharedPreferences on every
    // recomposition. Unknown/stale names are dropped and any newly added
    // tool is appended in catalogue order inside PlayerPrefs, so this can
    // never yield a partial ribbon.
    //
    // The offset is handed to loadRibbon rather than scrolled here: this
    // runs before the ribbon row has been measured, and a scrollTo into an
    // unmeasured (maxValue == 0) row would silently discard it. The ribbon's
    // own effect applies it once layout has run.
    LaunchedEffect(Unit) {
        val canonical = RibbonTool.entries.map { it.name }
        val order = PlayerPrefs.ribbonOrder(context, canonical)
            .mapNotNull { name -> RibbonTool.entries.firstOrNull { it.name == name } }
        val hidden = PlayerPrefs.ribbonHidden(context)
            .mapNotNull { name -> RibbonTool.entries.firstOrNull { it.name == name } }
            .toSet()
        // A tool can't be both visible and hidden; visible wins.
        quick.loadRibbon(
            order = order,
            hidden = hidden - order.toSet(),
            scrollPx = PlayerPrefs.ribbonScrollPx(context),
        )
    }

    // Seed the two settings-backed ribbon toggles from the persisted values
    // the host passes in, so the icons reflect reality on the first frame.
    // Keyed on the values themselves, so a Settings-screen edit (or a
    // process restart) re-syncs without a manual round trip.
    LaunchedEffect(initialBackgroundPlay) {
        ui.backgroundPlayOn.value = initialBackgroundPlay
    }
    LaunchedEffect(initialAudioEffect) {
        ui.audioEffectOn.value = initialAudioEffect
    }

    // Restore the persisted playlist mode. Keyed on BOTH values so an
    // episode switch re-applies the newly opened video's saved choice,
    // and a decoder rebuild (which does not change them) does not —
    // the latter matters because it would otherwise clobber a toggle the
    // user made this session.
    LaunchedEffect(initialShuffle, initialRepeatMode, mediaId) {
        ui.shuffleOn.value = initialShuffle
        ui.repeatMode.value = RepeatLoopMode.entries
            .getOrElse(initialRepeatMode) { RepeatLoopMode.OFF }
        livePlayer.shuffleModeEnabled = initialShuffle
        livePlayer.repeatMode = when (ui.repeatMode.value) {
            RepeatLoopMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatLoopMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatLoopMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }


    ZoomRestoreEffect(initialZoomIdx, ui)
    // Mirror the restored speed into the player; the livePlayer key re-runs
    // this on every decoder swap.
    // Bug-8 fix: re-apply speed on decoder swaps from the LIVE speed index,
    // not the open-time initialSpeed. The old (initialSpeed, livePlayer)
    // keys rolled mid-session speed changes (user set 1.5x, swapped the
    // decoder, got 1.0x back) because initialSpeed is frozen at open.
    // speedIdx is updated by the speed pill as the user changes speed, so
    // reading it here preserves the session value across player rebuilds.
    LaunchedEffect(livePlayer) {
        livePlayer.setPlaybackSpeed(speeds[speedIdx.intValue])
    }
    // v0.6.2 sub-sync UX pass: mirror the live DataStore toggle into the
    // Compose state the hero chip reads from. The host writes through
    // [onSetSubSyncEnabled] on tap; this collector ensures a process
    // restart (or an external DataStore edit from Settings) is picked up
    // immediately, without waiting for the next tap.
    LaunchedEffect(subtitleAutoSyncEnabled) {
        quick.subSyncEnabled.value = subtitleAutoSyncEnabled
    }
    // v0.7.1: mirror the host's sync-running signal into the Compose state
    // the hero chip's spinner reads. The host sets it around the live
    // correlation window and while a forced fingerprint is queued/running.
    // v0.7.3: the separate calibration banner is retired — the manual
    // calibration window keeps the SAME "Syncing…" state alive.
    LaunchedEffect(subSyncRunning, isCalibrating, isSyncLocked) {
        quick.subSyncRunning.value = subSyncRunning || isCalibrating
        quick.isSyncLocked.value = isSyncLocked
    }
    ZoomApplyEffect(ui.zoomIdx.intValue, ui)
    RotationLockEffect(activity, quick.rotateMode.value)
    SleepTimerEffect(sleep) { engine?.player ?: livePlayer }
    PlayerEventListenerEffect(
        player = livePlayer,
        ui = ui,
        sleep = sleep,
        readySpeed = { if (ui.boostActive.value) BOOST_SPEED else speeds[speedIdx.intValue] },
        onHoldAutoAdvance = onHoldAutoAdvance,
    )
    InitialSleepTimerEffect(initialSleepTimerMinutes, sleep)
    // v0.7.3 auto-hide gate: the old menuOpen brake was permanently false
    // (nobody set it), so chrome faded under open sheets. stayAwake unions
    // every surface that can be open right now.
    AutoHideControlsEffect(
        controlsVisible = ui.controlsVisible.value,
        isPlaying = ui.isPlaying.value,
        locked = ui.locked.value,
        stayAwake = overflowOpen.value || quick.showSyncPopover.value ||
            quick.showStyleTray.value ||
            gestures.subStyleMenuOpen.value || hostSheetOpen ||
            ui.threeDotsMenuOpen.value || ui.audioSheetOpen.value ||
            ui.subStyleSheetOpen.value || ui.equalizerSheetOpen.value,
        autoHideControlsMs = autoHideControlsMs,
        onHide = { ui.controlsVisible.value = false },
    )
    BoostHudKeepAliveEffect(ui.boostActive.value, hud, view)
    SubtitlePositionRestoreEffect(mediaId, context, gestures)
    SubtitlePositionPresetEffect(subtitleStyle.position, context, mediaId, gestures)
    FirstFramePosterEffect(mediaId, context, ui)
    // v0.7.1: throttled frame previews for the scrub bubble.
    ScrubPreviewEffect(mediaId, context, ui, gestures)
    AbRepeatEffect(
        abStartMs = ui.abStartMs.value,
        abEndMs = ui.abEndMs.value,
        player = livePlayer,
        positionSec = positionSec,
    )

    // Overlays anchor off the MEASURED chrome heights (see the bars'
    // onSizeChanged publishers) — never below the status bar, never under
    // the dock, with no magic dp coupling to the bars' internal composition.
    val density = LocalDensity.current
    val topAnchor = with(density) { ui.topBarHeightPx.intValue.toDp() } + PlayerDimens.gapSm
    val bottomAnchor = with(density) { ui.bottomBarHeightPx.intValue.toDp() } + PlayerDimens.gapSm

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged {
                gestures.scrW.floatValue = it.width.toFloat()
                gestures.scrH.floatValue = it.height.toFloat()
            }
            .playerGestureLayer(
                actions = actions,
                isPipMode = isPipMode,
                pinchZoomEnabled = pinchZoomEnabled,
                doubleTapSeekEnabled = doubleTapSeekEnabled,
                swipeToSeekEnabled = swipeToSeekEnabled,
                volumeGestureEnabled = volumeGestureEnabled,
                brightnessGestureEnabled = brightnessGestureEnabled,
            )
    ) {
        // ── video + first-frame poster ──
        PlayerVideoSurface(
            player = livePlayer,
            zoomMode = ZoomModes[ui.zoomIdx.intValue],
            poster = ui.posterBitmap.value,
            onPlayerView = { ui.playerViewRef.value = it },
        )

        // ── night mode eye-comfort scrim ──
        if (ui.nightMode.value) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x40FF9800))
            )
        }

        // ── aspect ratio transient badge (top-right) ──
        if (ui.aspectBadge.value != null && !isPipMode) {
            AspectBadgeOverlay(
                badgeText = ui.aspectBadge.value ?: "",
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 16.dp, end = 16.dp),
            )
        }

        // ── subtitle: high-contrast outline, draggable ──
        if (ui.showCC.value && !isPipMode) {
            cueText?.let { txt ->
                PlayerSubtitleOverlay(
                    modifier = Modifier.align(Alignment.Center),
                    text = txt,
                    style = subtitleStyle,
                    accent = accent,
                    showCC = ui.showCC.value,
                    gestures = gestures,
                    mediaId = mediaId,
                    bottomBarHeightPx = if (ui.controlsVisible.value) ui.bottomBarHeightPx.intValue.toFloat() else 0f,
                    onStyleChanged = onSubtitleStyleChanged,
                )
            }
        }

        // ── seek ripple overlay (double-tap seek) ──
        if (ui.seekRippleSide.intValue != 0 && !isPipMode) {
            SeekRippleOverlay(
                side = ui.seekRippleSide.intValue,
                seconds = ui.seekRippleSeconds.intValue,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── double-tap flash (fallback) ──
        if (ui.flashSide.value != 0 && !isPipMode && ui.seekRippleSide.intValue == 0) {
            DoubleTapFlash(
                modifier = Modifier.align(
                    if (ui.flashSide.value < 0) Alignment.CenterStart else Alignment.CenterEnd
                ),
                side = ui.flashSide.value,
                seekIncrementSec = seekIncrementSec,
            )
        }

        // ── gesture HUD pill (top-center, never covers subtitles) ──
        if (hud.visible.value && !isPipMode) {
            GestureHudPill(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 16.dp),
                icon = hud.icon.value,
                text = hud.text.value,
            )
        }

        // ── vertical gesture HUD pill (Volume on right, Brightness on left) ──
        if (ui.verticalHudVisible.value && !isPipMode) {
            VerticalGestureHudPill(
                type = ui.verticalHudType.value,
                progress = ui.verticalHudProgress.floatValue,
                valueText = ui.verticalHudValueText.value,
                accent = accent,
                modifier = Modifier
                    .align(if (ui.verticalHudType.value == VerticalHudType.VOLUME) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 24.dp),
            )
        }

        // ── speed banner HUD (hold-to-2x top-center) ──
        if (ui.speedBannerVisible.value && !isPipMode) {
            SpeedBannerHud(
                speedText = ui.speedBannerText.value,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = 16.dp),
            )
        }

        // ── buffering spinner (center, undecorated) ──
        if (ui.isBuffering.value && !isPipMode) {
            BufferingSpinner(modifier = Modifier.align(Alignment.Center), accent = accent)
        }

        // ── camera flash white overlay (screenshot capture) ──
        if (ui.screenshotFlash.value) {
            CameraFlashOverlay(modifier = Modifier.fillMaxSize())
        }

        // ── lock scrim overlay ──
        if (ui.locked.value && ui.lockScrimVisible.value && !isPipMode) {
            LockScrimOverlay(
                onUnlock = { actions.unlockControls() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── lock badge ──
        if (ui.locked.value && !isPipMode) {
            LockBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .displayCutoutPadding(),
                accent = accent,
                onUnlock = { actions.unlockControls() },
            )
        }

        // ── controls overlay ──
        PlayerControlsOverlay(
            visible = ui.controlsVisible.value && !ui.locked.value && !isPipMode,
            showSeekBar = !ui.locked.value && !isPipMode,
            title = title,
            accent = accent,
            positionSec = positionSec,
            durationSec = durationSec,
            bufferedSec = bufferedSec,
            localSeek = ui.localSeek,
            isPlaying = ui.isPlaying.value,
            hasPreviousEpisode = hasPreviousEpisode,
            hasNextEpisode = hasNextEpisode,
            seekIncrementSec = seekIncrementSec,
            hasSubtitleTrack = hasSubtitleTrack,
            liveOffsetMs = liveOffsetMs,
            actions = actions,
            onBack = onBack,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onMore = { actions.toggleThreeDotsMenu() },
        )

        // ── Tier 2: Three Dots Floating Card ──
        if (ui.threeDotsMenuOpen.value && !isPipMode) {
            ThreeDotsMenuCard(
                visible = ui.threeDotsMenuOpen.value,
                accent = accent,
                isSyncLocked = quick.isSyncLocked.value,
                isSyncRunning = quick.subSyncRunning.value,
                syncEnabled = quick.subSyncEnabled.value,
                decoderModeLabel = decoderModeLabel,
                onSubtitleSyncClick = {
                    actions.closeThreeDotsMenu()
                    actions.openSyncPopover()
                },
                onSubtitleStyleClick = {
                    actions.closeThreeDotsMenu()
                    actions.openStyleTray()
                },
                onDecoderPipelineClick = {
                    actions.cycleDecoderMode()
                },
                onResumeBehaviorClick = {
                    actions.closeThreeDotsMenu()
                    actions.showTransientToast("Resume behavior: from last position")
                },
                onOpenSettingsClick = {
                    actions.closeThreeDotsMenu()
                    overflowOpen.value = true
                },
                onShareSyncLog = {
                    actions.closeThreeDotsMenu()
                    onShareSyncLog()
                },
                onDismiss = { actions.closeThreeDotsMenu() },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .displayCutoutPadding()
                    .padding(top = topAnchor, end = 16.dp),
            )
        }

        // ── Tier 3: Audio Track Picker Sheet ──
        if (ui.audioSheetOpen.value && !isPipMode) {
            AudioTrackPickerSheet(
                visible = ui.audioSheetOpen.value,
                accent = accent,
                onDismiss = { actions.closeAudioSheet() },
                onSelectTrack = { trackName ->
                    actions.closeAudioSheet()
                    actions.showTransientToast("Audio Track: $trackName")
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        // ── Control Center (was the flat overflow sheet; v0.7.3 sectioned
        //    hub with live values + the wired dead ends). Single "more"
        //    destination: the top bar's ⋮.
        PlayerControlCenterSheet(
            visible = overflowOpen.value,
            state = ControlCenterState(
                abStartMs = abStartMs ?: ui.abStartMs.value,
                abEndMs = abEndMs ?: ui.abEndMs.value,
                sleep = sleep,
                skipIncrementSec = seekIncrementSec,
                equalizerOn = quick.equalizerOn.value,
                castRouteName = castRouteName,
                subtitleChoiceLabel = subtitleChoiceLabel,
                // Read the same host-mirrored value the dock chip uses, not the
                // engine directly: two surfaces reading two sources is how they
                // came to disagree in the first place.
                decoderModeLabel = decoderModeLabel,
                rebuildingDecoder = isRebuildingDecoder,
                volumeBoostPct = volumeBoostPct,
            ),
            accent = accent,
            onDismiss = { overflowOpen.value = false },
            onAbRepeat = {
                actions.cycleAbRepeat()
                onAbRepeatTap()
            },
            onSkipLengthCycle = {
                // Same four steps the Settings screen offers. Stays open so
                // the pill's value visibly advances.
                val steps = listOf(5, 10, 15, 30)
                val next = steps.firstOrNull { it > seekIncrementSec } ?: steps.first()
                onSkipLengthChanged(next)
            },
            onSleepOption = { opt -> actions.selectSleep(opt) },
            onEqualizerToggle = { actions.toggleEqualizer() },
            onOpenEqPanel = onOpenEqPanel,
            onAudioTrack = { actions.pickAudioTrack() },
            onAudioOutput = { actions.openCastPicker() },
            onVolumeBoost = onVolumeBoostCycle,
            onCaptureFrame = { actions.captureFrame() },
            onDecoder = { actions.cycleDecoderMode() },
            onSubtitleSource = onOpenSubtitlePicker,
            // v0.9: subtitle style now tunes IN PLACE (the inline tray) rather
            // than handing off to the host's modal sheet, so the cue the user
            // is styling stays on screen the whole time.
            onSubtitleStyle = { actions.openStyleTray() },
            // v0.7.2: shares the device's own sync decisions (see SyncLogShare).
            onShareSyncLog = onShareSyncLog,
            onOpenSettings = onOpenSettings,
        )

        // ── Ribbon customise sheet — backs the ribbon's "Customise" tool.
        //    A sheet (not an inline tray) because reordering needs a tall
        //    scrollable list; the video keeps playing behind it.
        RibbonCustomiseSheet(
            visible = quick.showRibbonCustomise.value,
            actions = actions,
            accent = accent,
            onDismiss = { /* state is already closed by the action */ },
        )

        // ── A-B repeat chip (tap advances the cycle: set B / clear) ──
        if (!isPipMode && abStartMs != null) {
            AbRepeatChip(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = topAnchor),
                abStartMs = abStartMs,
                abEndMs = abEndMs,
                accent = accent,
                onTap = onAbRepeatTap,
            )
        }

        // ── Up Next pill (final 30 s of an episode) ──
        // Reads the position STATE: recomposes only in the final 30s window
        // (the condition itself gates it — Compose skips until the boolean
        // flips, not on every tick). Anchored off the measured dock height,
        // never a magic 140dp again.
        if (!isPipMode && hasNextEpisode && durationSec.value > 0f &&
            durationSec.value - positionSec.value <= NEXT_BUTTON_WINDOW_SEC && nextCountdownSec < 0
        ) {
            UpNextPill(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomAnchor),
                upNextTitle = upNextTitle,
                accent = accent,
                onClick = onPlayNext,
            )
        }

        // ── auto-advance countdown overlay ("Next episode in N...") ──
        if (!isPipMode && nextCountdownSec > 0) {
            NextCountdownOverlay(
                modifier = Modifier.align(Alignment.Center),
                countdownSec = nextCountdownSec,
                onCancel = onCancelNext,
                onPlayNow = onPlayNext,
            )
        }

        // ── v0.9 inline subtitle-style tray — replaces the host's MODAL
        //    SubtitleStyleSheet. The 09-19 log records ~40 style edits in one
        //    session; they were happening behind a sheet that covered the very
        //    frame the subtitles render on. This renders in place, anchored
        //    above the transport (same slot the sync popover uses), so the
        //    live cue stays visible while every dimension is tuned. ──
        if (quick.showStyleTray.value && !isPipMode) {
            SubtitleStyleTray(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomAnchor + PlayerDimens.gapMd)
                    .widthIn(max = 420.dp)
                    .padding(horizontal = PlayerDimens.gapLg),
                style = subtitleStyle,
                accent = accent,
                onStyleChanged = { newStyle ->
                    onSubtitleStyleChanged(newStyle)
                },
            )
        }

        // ── sync popover (nudge / re-sync / style) — opens ONLY from the
        //    hero chip in the dock, so it anchors directly above that row.
        //    The old top-left SYNCED chip + calibration banner layers are
        //    retired: the chip IS the status now. ──
        if (quick.showSyncPopover.value && !isPipMode) {
            SyncPopover(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomAnchor + PlayerDimens.gapMd)
                    .widthIn(max = 360.dp)
                    .padding(horizontal = PlayerDimens.gapLg),
                offsetMs = liveOffsetMs,
                accent = accent,
                onNudge = { actions.nudgeSubtitle(it) },
                onResync = {
                    actions.closeSyncPopover()
                    onStartCalibration()
                },
                onStyle = {
                    actions.closeSyncPopover()
                    actions.openStyleTray()
                },
                onDisable = {
                    actions.closeSyncPopover()
                    actions.setSubSyncEnabled(false)
                },
                onDismiss = { actions.closeSyncPopover() },
            )
        }

        // ── transient toast (in-overlay feedback) ──
        if (hud.transientToast.value != null && !isPipMode) {
            PlayerOverlayToast(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = topAnchor),
                message = hud.transientToast.value,
                accent = accent,
            )
        }
    }
}
