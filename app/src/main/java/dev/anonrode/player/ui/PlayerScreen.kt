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
import dev.anonrode.player.audio.SubtitleStyle
import dev.anonrode.player.core.ui.theme.rememberSkinPalette
import dev.anonrode.player.feature.player.PlaybackEngine
import kotlin.math.abs

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
 * The subtitle cue is MX-outlined (bold + black outline, no box) and can
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
     * Request a real HW/SW decoder swap. The host rebuilds the ExoPlayer
     * via [dev.anonrode.player.feature.player.PlaybackEngine.rebuild] and
     * returns the new audio session id (0 if the swap is still in flight).
     * The screen keeps the [quick.hwDecoder] state in sync with the
     * requested value and shows a transient "Rebuilding…" banner until the
     * host confirms the new player is ready.
     */
    onRebuildDecoder: (Boolean) -> Int = { _ -> 0 },
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
    subtitleAutoSyncEnabled: Boolean = false,
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
    val quick = remember { QuickRowUiState(initialHwDecoder = engine?.isHw ?: true) }

    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    // Keyed on initialSpeed so the pill re-syncs when the activity restores
    // a different persisted speed (e.g. after an auto-advance episode switch).
    val speedIdx = remember(initialSpeed) {
        mutableIntStateOf(speeds.indexOfFirst { abs(it - initialSpeed) < 0.05f }.takeIf { it >= 0 } ?: 2)
    }

    // Shared "more" state — the top bar's ⋮ opens the Control Center
    // (v0.7.3: the rail's second "more" is deleted, this is the only one).
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
    val captureScope = rememberCoroutineScope()
    val actions = remember(livePlayer, engine, ui, hud, sleep, gestures, quick, captureScope) {
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
        )
    }

    // ── side-effects: same keys and order as before the split ──
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
    LaunchedEffect(subSyncRunning, isCalibrating) {
        quick.subSyncRunning.value = subSyncRunning || isCalibrating
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
            gestures.subStyleMenuOpen.value || hostSheetOpen,
        autoHideControlsMs = autoHideControlsMs,
        onHide = { ui.controlsVisible.value = false },
    )
    BoostHudKeepAliveEffect(ui.boostActive.value, hud, view)
    SubtitlePositionRestoreEffect(mediaId, context, gestures)
    SubtitlePositionPresetEffect(subtitleStyle.position, context, mediaId, gestures)
    FirstFramePosterEffect(mediaId, context, ui)
    // v0.7.1: throttled frame previews for the scrub bubble.
    ScrubPreviewEffect(mediaId, context, ui, gestures)

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
                    onStyleChanged = onSubtitleStyleChanged,
                )
            }
        }

        // ── double-tap flash ──
        if (ui.flashSide.value != 0 && !isPipMode) {
            DoubleTapFlash(
                modifier = Modifier.align(
                    if (ui.flashSide.value < 0) Alignment.CenterStart else Alignment.CenterEnd
                ),
                side = ui.flashSide.value,
                seekIncrementSec = seekIncrementSec,
            )
        }

        // ── gesture HUD pill ──
        if (hud.visible.value && !isPipMode) {
            GestureHudPill(
                modifier = Modifier.align(Alignment.Center),
                icon = hud.icon.value,
                text = hud.text.value,
            )
        }

        // ── buffering spinner (center, undecorated) ──
        if (ui.isBuffering.value && !isPipMode) {
            BufferingSpinner(modifier = Modifier.align(Alignment.Center), accent = accent)
        }

        // ── lock badge — the ONLY chrome that survives locking; it lives
        //    where the top bar was, so it carries the status-bar inset. ──
        if (ui.locked.value && !isPipMode) {
            LockBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .displayCutoutPadding(),
                accent = accent,
                onUnlock = { ui.locked.value = false },
            )
        }

        // ── controls overlay — the seek bar row inside stays visible even
        //    when the chrome is hidden (UI-4 fix); hidden entirely only
        //    while in PiP / locked.
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
            onMore = { overflowOpen.value = true },
        )

        // ── Control Center (was the flat overflow sheet; v0.7.3 sectioned
        //    hub with live values + the wired dead ends). Single "more"
        //    destination: the top bar's ⋮.
        PlayerControlCenterSheet(
            visible = overflowOpen.value,
            state = ControlCenterState(
                abStartMs = abStartMs,
                abEndMs = abEndMs,
                sleep = sleep,
                skipIncrementSec = seekIncrementSec,
                equalizerOn = quick.equalizerOn.value,
                castRouteName = castRouteName,
                subtitleChoiceLabel = subtitleChoiceLabel,
                decoderModeLabel = engine?.decoderModeLabel ?: "HW+SW",
                rebuildingDecoder = isRebuildingDecoder,
                volumeBoostPct = volumeBoostPct,
            ),
            accent = accent,
            onDismiss = { overflowOpen.value = false },
            onAbRepeat = onAbRepeatTap,
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
            onDecoder = { actions.toggleHwDecoder() },
            onSubtitleSource = onOpenSubtitlePicker,
            onSubtitleStyle = onOpenSubStyle,
            // v0.7.2: shares the device's own sync decisions (see SyncLogShare).
            onShareSyncLog = onShareSyncLog,
            onOpenSettings = onOpenSettings,
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
                    onOpenSubStyle()
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
