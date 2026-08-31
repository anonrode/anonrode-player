package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.audio.SubtitleStyle
import dev.anonrode.player.core.ui.theme.rememberSkinPalette
import dev.anonrode.player.feature.player.PlaybackEngine
import kotlin.math.abs

/**
 * Full-bleed MX-style player: top row (back, title, audio/CC/HW/overflow),
 * quick-action row (equalizer, cast, headphones, speaker, speed pill,
 * chevron), and a bottom block (lock · ⏪10 · prev · BIG play · next ·
 * ⏩10 · PiP · aspect) over gradient scrims. Auto-hide while playing,
 * double-tap ±10s with flash, left/right vertical swipe = brightness/volume
 * with HUD pill, horizontal swipe = live seek.
 *
 * The subtitle cue is MX-outlined (bold + black outline, no box) and can be
 * long-press dragged anywhere; its position persists per video with a
 * global default fallback (see [dev.anonrode.player.PlayerPrefs]). The
 * overflow menu hosts the sleep timer, aspect cycling, and rotation lock;
 * the resize button cycles FIT → CROP → STR. While in PiP ([isPipMode])
 * every overlay (controls, subtitles, badges) hides.
 *
 * Structure: this function is the orchestrator only. State lives in the
 * remembered holders in PlayerScreenState.kt, callbacks in
 * PlayerScreenActions.kt, side-effects in PlayerScreenEffects.kt, gestures
 * in PlayerScreenGestures.kt, and each visual chunk in its own file
 * (Video / Subtitles / Controls / Hud / Sheets) — so no single composable
 * captures dozens of locals and the compiler/JIT stay cheap on low-end
 * devices.
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
    positionSec: Float,
    durationSec: Float,
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
    /** True while a fresh calibration pass is running. */
    isCalibrating: Boolean = false,
    /** Start a new calibration pass (CALIB button in the sync popover). */
    onStartCalibration: () -> Unit = {},
    /** Apply a manual ±0.1s nudge to the subtitle offset. */
    onNudgeSubtitle: (Long) -> Unit = { _ -> },
    /**
     * v0.6.2 sub-sync UX pass: persist the user-facing sync toggle
     * (DataStore + fingerprint job gate). The toggle's icon flips
     * instantly via [quick.subSyncEnabled]; this callback is for
     * persistence and the gate side-effects (cancel pending jobs when
     * turning OFF; schedule one when turning ON).
     */
    onSetSubSyncEnabled: (Boolean) -> Unit = {},
    /** Long-press on the sync toggle: "Resync now". */
    onResyncNow: () -> Unit = {},
    /**
     * Request a real HW/SW decoder swap. The host rebuilds the ExoPlayer
     * via [dev.anonrode.player.feature.player.PlaybackEngine.rebuild] and
     * returns the new audio session id (0 if the swap is still in flight).
     * The screen keeps the [hwDecoder] state in sync with the requested
     * value and shows a transient "Rebuilding…" banner until the host
     * confirms the new player is ready.
     */
    onRebuildDecoder: (Boolean) -> Int = { _ -> 0 },
    /**
     * Toggle the system equalizer. Called when the EQ quick-row chip is
     * tapped. The host creates / enables / disables the
     * [android.media.audiofx.Equalizer] bound to the current audio session
     * and reports back the new on/off state. The screen mirrors the
     * returned value into [equalizerOn] for the chip's visual.
     */
    onToggleEqualizer: (Boolean) -> Boolean = { it },
    /**
     * Open the Cast (MediaRouter) route picker. The host shows a bottom
     * sheet of available routes and calls [mediaRouter] select on pick.
     */
    onOpenCastPicker: () -> Unit = {},
    /**
     * Open the 5-band equalizer panel. The host (PlayerActivity) shows
     * [EqualizerPanelSheet] when this is invoked. Tap on the EQ quick-row
     * chip toggles on/off; long-press opens the panel.
     */
    onOpenEqPanel: () -> Unit = {},
    /**
     * Open the subtitle style picker (size/position/color). The host shows
     * a bottom sheet that mutates the live subtitle style and persists
     * via PlayerSettings DataStore.
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
     * downloaded / online search). The host shows SubtitlePickerSheet and
     * reloads the chosen source.
     */
    onOpenSubtitlePicker: () -> Unit = {},
    /**
     * Open the audio track picker. The host reads [Player.getCurrentTracks]
     * and shows a bottom sheet of available audio tracks for the current
     * media.
     */
    onOpenAudioTrackPicker: () -> Unit = {},
    /** Seek step (seconds) for the ±seek buttons and double-tap seek. */
    seekIncrementSec: Int = 10,
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
     * [quick.subSyncEnabled] below so the bottom-bar chip reflects
     * restarts / external Settings edits immediately.
     */
    subtitleAutoSyncEnabled: Boolean = false,
    /** HUD auto-hide delay while playing (ms). */
    autoHideControlsMs: Long = 3500L,
    /** Sleep timer armed from the settings screen (0=off, -1=end of episode). */
    initialSleepTimerMinutes: Int = 0,
    /** Seeks bigger than this use fast (keyframe) seeking; smaller = exact. */
    fastSeekThresholdSec: Long = 120L,
    /**
     * Per-video zoom mode (index into the screen's ZoomModes), persisted by
     * the host via Room. Restored on entry; [onZoomChanged] reports cycles.
     */
    initialZoomIdx: Int = 0,
    onZoomChanged: (Int) -> Unit = {},
    /** Volume boost over system max (0/50/100/200 %); cycled in the menu. */
    volumeBoostPct: Int = 0,
    onVolumeBoostCycle: () -> Unit = {},
    /**
     * A-B repeat region (ms). null start = inactive. The host enforces the
     * loop (seek back to A at B); the screen only surfaces state + the tap.
     * Tap cycle: set A → set B (loop starts) → clear.
     */
    abStartMs: Long? = null,
    abEndMs: Long? = null,
    onAbRepeatTap: () -> Unit = {},
    /** True if a decoder swap is currently in flight; hides the HW chip. */
    isRebuildingDecoder: Boolean = false,
    /** Name of the currently selected Cast route, for the chip tooltip. */
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
    // Keyed on initialSpeed so the button re-syncs when the activity restores
    // a different persisted speed (e.g. after an auto-advance episode switch).
    val speedIdx = remember(initialSpeed) {
        mutableIntStateOf(speeds.indexOfFirst { abs(it - initialSpeed) < 0.05f }.takeIf { it >= 0 } ?: 2)
    }

    // Shared "more" state — both the top-bar "more" and the right-rail
    // "more" buttons open the same overflow sheet. One state, two call
    // sites; the sheet reads the same flag.
    val overflowOpen = remember { mutableStateOf(false) }

    // Sync toggle slot. Agent 5 supplies the actual composable; for now
    // we provide a placeholder so the rail can be rendered without an
    // undefined-symbol error. Agent 5's wiring replaces this lambda in
    // its own commit; the lambda's signature must stay stable.
    val syncSlot: SyncSlot = remember { { mod, ac ->
        androidx.compose.foundation.layout.Box(
            modifier = mod,
            contentAlignment = Alignment.Center,
        ) { /* agent-5 fills */ }
    } }

    // Rotation mode (3-state: sensor / landscape / portrait). Stored on
    // QuickRowUiState so the button's tap cycle + long-press menu both
    // see the same value. The activity orientation is reapplied by
    // RotationLockEffect below whenever this changes.
    val rotateMode = quick.rotateMode

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
    LaunchedEffect(initialSpeed, livePlayer) {
        livePlayer.setPlaybackSpeed(initialSpeed)
    }
    // v0.6.2 sub-sync UX pass: mirror the live DataStore toggle into the
    // Compose state the bottom-bar reads from. The host writes through
    // [onSetSubSyncEnabled] on tap; this collector ensures a process
    // restart (or an external DataStore edit from Settings) is picked up
    // immediately, without waiting for the next tap.
    LaunchedEffect(subtitleAutoSyncEnabled) {
        quick.subSyncEnabled.value = subtitleAutoSyncEnabled
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
    AutoHideControlsEffect(
        controlsVisible = ui.controlsVisible.value,
        isPlaying = ui.isPlaying.value,
        locked = ui.locked.value,
        menuOpen = ui.menuOpen.value,
        autoHideControlsMs = autoHideControlsMs,
        onHide = { ui.controlsVisible.value = false },
    )
    BoostHudKeepAliveEffect(ui.boostActive.value, hud, view)
    SubtitlePositionRestoreEffect(mediaId, context, gestures)
    SubtitlePositionPresetEffect(subtitleStyle.position, context, mediaId, gestures)
    FirstFramePosterEffect(mediaId, context, ui)

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

        // ── subtitle: MX outline, long-press draggable ──
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

        // ── lock badge ──
        if (ui.locked.value && !isPipMode) {
            LockBadge(
                modifier = Modifier.align(Alignment.TopStart),
                accent = accent,
                onUnlock = { ui.locked.value = false },
            )
        }

        // ── controls overlay (hidden entirely while in PiP / locked) ──
        PlayerControlsOverlay(
            visible = ui.controlsVisible.value && !ui.locked.value && !isPipMode,
            title = title,
            accent = accent,
            currentPositionMs = livePlayer.currentPosition,
            positionSec = positionSec,
            durationSec = durationSec,
            localSeek = ui.localSeek,
            isPlaying = ui.isPlaying.value,
            locked = ui.locked.value,
            hasPreviousEpisode = hasPreviousEpisode,
            hasNextEpisode = hasNextEpisode,
            seekIncrementSec = seekIncrementSec,
            actions = actions,
            onBack = onBack,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onMore = { overflowOpen.value = true },
        )

        // ── right-edge vertical action rail (CC / audio / sync slot /
        //    rotate / more). Sibling of the controls overlay so the
        //    rail can be tuned independently of the top+bottom bars.
        //    Auto-hides with the chrome (same condition as the overlay),
        //    so when the user taps once to show controls, the rail
        //    appears too — but the seek bar underneath stays.
        androidx.compose.animation.AnimatedVisibility(
            visible = ui.controlsVisible.value && !ui.locked.value && !isPipMode,
            enter = androidx.compose.animation.fadeIn(animationSpec = androidx.compose.animation.core.tween(220)),
            exit = androidx.compose.animation.fadeOut(animationSpec = androidx.compose.animation.core.tween(220)),
        ) {
            PlayerScreenActionRail(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                accent = accent,
                showCC = ui.showCC.value,
                hasSubtitle = cueText != null,
                rotateMode = rotateMode.value,
                onCycleRotate = { actions.cycleRotateMode() },
                onSetRotate = { actions.setRotateMode(it) },
                onSubtitleToggle = { actions.toggleShowCC() },
                onAudioTrack = { actions.pickAudioTrack() },
                onMore = { overflowOpen.value = true },
                syncSlot = syncSlot,
            )
        }

        // ── overflow sheet (bottom). Single destination for both
        //    "more" buttons (top bar + rail). Renders the 11-tile
        //    grid (Aspect, Zoom, AB-repeat, Sleep, Audio, Speed, EQ,
        //    Cast, Headphones, Speaker, Capture frame) plus the PiP
        //    tile. The sheet is opaque enough to read independently
        //    of the underlying video frame.
        PlayerOverflowSheet(
            visible = overflowOpen.value,
            state = OverflowState(
                zoomAbbreviation = ZoomModes[ui.zoomIdx.intValue].abbreviation,
                showCC = ui.showCC.value,
                abStartMs = abStartMs,
                abEndMs = abEndMs,
                sleep = sleep,
                speedLabelText = speedLabel(speeds[speedIdx.intValue]),
                equalizerOn = quick.equalizerOn.value,
                headphonesOn = quick.headphonesOn.value,
                castRouteName = castRouteName,
            ),
            onDismiss = { overflowOpen.value = false },
            onAspect = { actions.cycleZoom() },
            onZoom = { actions.cycleZoom() },
            onAbRepeat = onAbRepeatTap,
            onSleep = {
                // Cycles Off → end-of-episode. The detail list lives in
                // the legacy menu — this entry-point is the rail /
                // overflow tap.
                val next = if (sleep.active) SleepOptions.first()
                else SleepOptions.last()
                actions.selectSleep(next)
            },
            onAudioTrack = { actions.pickAudioTrack() },
            onSpeed = { actions.cycleSpeed() },
            onEqualizer = { actions.toggleEqualizer() },
            onCast = { actions.openCastPicker() },
            onHeadphones = { actions.toggleHeadphones() },
            onSpeaker = { actions.openAudioOutputPicker() },
            onCaptureFrame = { actions.captureFrame() },
        )

        // ── A-B repeat chip (tap advances the cycle: set B / clear) ──
        if (!isPipMode && abStartMs != null) {
            AbRepeatChip(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp),
                abStartMs = abStartMs,
                abEndMs = abEndMs,
                accent = accent,
                onTap = onAbRepeatTap,
            )
        }

        // ── Up Next pill (final 30 s of an episode) ──
        if (!isPipMode && hasNextEpisode && durationSec > 0f &&
            durationSec - positionSec <= NEXT_BUTTON_WINDOW_SEC && nextCountdownSec < 0
        ) {
            UpNextPill(
                // Sits above the new two-row bottom block
                // (seekbar + transport, ~140dp). Previous layout used
                // 130dp — bumped to 140dp so the pill clears the new
                // 72dp BIG play button without colliding with it.
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp),
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

        // ── SYNCED chip + sync popover (top-left, just below the top bar) ──
        if (!isPipMode) {
            SyncedChip(
                modifier = Modifier.align(Alignment.TopStart).padding(top = 70.dp, start = 14.dp),
                offsetMs = liveOffsetMs,
                accent = accent,
                onClick = { actions.openSyncPopover() },
            )
        }
        if (quick.showSyncPopover.value && !isPipMode) {
            SyncPopover(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Sits above the new two-row bottom block
                    // (seekbar + transport, ~140dp) with clearance.
                    .padding(bottom = 210.dp)
                    .widthIn(max = 360.dp)
                    .padding(horizontal = 14.dp),
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
                onDismiss = { actions.closeSyncPopover() },
            )
        }

        // ── calibration banner (auto / manual) ──
        if (isCalibrating && !isPipMode) {
            CalibrationBanner(
                modifier = Modifier.align(Alignment.TopStart).padding(top = 70.dp, start = 14.dp, end = 14.dp),
                visible = true,
                accent = accent,
                onClick = { onStartCalibration() },
            )
        }

        // ── transient toast (in-overlay feedback) ──
        if (hud.transientToast.value != null && !isPipMode) {
            PlayerOverlayToast(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp),
                message = hud.transientToast.value,
                accent = accent,
            )
        }
    }
}
