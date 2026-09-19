package dev.anonrode.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.WindowManager
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouter.RouteInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.audio.CastRoutePickerSheet
import dev.anonrode.player.audio.EqualizerManager
import dev.anonrode.player.audio.EqualizerPanelSheet
import dev.anonrode.player.audio.AudioTrackPickerSheet
import dev.anonrode.player.audio.SubtitleColor
import dev.anonrode.player.audio.SubtitlePickerSheet
import dev.anonrode.player.audio.SubtitlePosition
import dev.anonrode.player.audio.SubtitleSize
import dev.anonrode.player.audio.SubtitleStyle
import dev.anonrode.player.audio.SubtitleStyleSheet
import dev.anonrode.player.core.datastore.DecoderPriority
import dev.anonrode.player.core.datastore.PlayerSettings
import dev.anonrode.player.core.datastore.ResumeBehavior
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.media.subtitle.SubtitleSourceResolver
import dev.anonrode.player.core.media.sync.SyncFingerprint
import dev.anonrode.player.core.model.SubtitleCue
import dev.anonrode.player.core.model.Video
import dev.anonrode.player.core.ui.theme.AnonrodeTheme
import dev.anonrode.player.core.ui.theme.rememberSkinPalette
import dev.anonrode.player.feature.player.PlaybackEngine
import dev.anonrode.player.feature.player.PlayerService
import dev.anonrode.player.ui.PlayerScreen
import dev.anonrode.player.ui.SettingsScreen
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the PlayerScreen. Playback wiring: restore resume position, apply
 * persisted auto-sync offset + manual delay (additive), resolve sidecar
 * subtitles, and drive the subtitle render loop (binary search + offset).
 * State fields are Compose-backed so only affected UI recomposes.
 *
 * Picture-in-Picture: [onUserLeaveHint] auto-enters PiP (video aspect) when the user
 * leaves mid-playback; [onPictureInPictureModeChanged] mirrors PiP state into
 * a Compose field that hides all overlay UI while the window is miniaturized.
 *
 * Episodes: after starting playback an [EpisodeQueue] is built from the
 * sibling videos in the same folder (sorted by [dev.anonrode.player.core.model.EpisodePattern]).
 * STATE_ENDED marks the episode finished and counts down to the next one;
 * the screen exposes manual Next/Previous skips and an "Up Next" overlay over
 * the final 30 seconds. Playback speed changes are persisted per-video.
 */
@UnstableApi
class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_TITLE = "title"

        /** Ordered URI list from library multi-select; plays in this order. */
        const val EXTRA_QUEUE_URIS = "queue_uris"
        private const val NEXT_COUNTDOWN_SEC = 5
        // Selector used for both the activity-level route-name observer
        // and the picker composable. Covers Cast, Bluetooth, HDMI, Miracast.
        private val MediaRouteSelectorLite = androidx.mediarouter.media.MediaRouteSelector.Builder()
            .addControlCategory(androidx.mediarouter.media.MediaControlIntent.CATEGORY_LIVE_AUDIO)
            .addControlCategory(androidx.mediarouter.media.MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .addControlCategory(androidx.mediarouter.media.MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()

        // Bundle keys for onSaveInstanceState / onRestoreInstanceState.
        // Centralised so a typo can't silently drop a field across the
        // save/restore round-trip; names are stable across versions.
        private const val KEY_SETTINGS_OPEN = "pa.settingsOpen"
        private const val KEY_PIP_MODE = "pa.pipMode"
        private const val KEY_IS_CALIBRATING = "pa.isCalibrating"
        private const val KEY_PLAYBACK_ERROR = "pa.playbackError"
        private const val KEY_IS_REBUILDING_DECODER = "pa.isRebuildingDecoder"
        private const val KEY_AB_START_MS = "pa.abStartMs"
        private const val KEY_AB_END_MS = "pa.abEndMs"
        private const val KEY_NEXT_COUNTDOWN_SEC = "pa.nextCountdownSec"
        private const val KEY_PENDING_NEXT_URI = "pa.pendingNextUri"
        private const val KEY_PENDING_NEXT_TITLE = "pa.pendingNextTitle"
        /** Current episode's display title (config-change reopen shows it). */
        private const val KEY_CURRENT_TITLE = "pa.currentTitle"
        private const val KEY_HOLD_AUTO_ADVANCE_ONCE = "pa.holdAutoAdvanceOnce"
        private const val KEY_SUBTITLE_CHOICE = "pa.subtitleChoice"
        private const val KEY_MANUAL_NUDGE_MS = "pa.manualNudgeMs"
        private const val KEY_SESSION_SPEED = "pa.sessionSpeed"
        private const val KEY_SAVED_ZOOM_IDX = "pa.savedZoomIdx"
        private const val KEY_CURRENT_URI_STR = "pa.currentUriStr"
        private const val KEY_CURRENT_VIDEO_PATH = "pa.currentVideoPath"
        private const val KEY_EXPLICIT_QUEUE_URIS = "pa.explicitQueueUris"
        private const val KEY_PENDING_AUDIO_TRACK_IDX = "pa.pendingAudioTrackIdx"
        private const val KEY_SWITCHING = "pa.switching"
    }

    private val handler = Handler(Looper.getMainLooper())

    private var title by mutableStateOf("")
    private var cueText by mutableStateOf<String?>(null)
    // Backing states exposed AS State to PlayerScreen (v0.7.1 perf pass):
    // the delegate `positionSec` stays for imperative reads in this class.
    private val positionState = mutableFloatStateOf(0f)
    private val durationState = mutableFloatStateOf(0f)
    private var positionSec by positionState
    private var durationSec by durationState

    /** Buffered position (seconds) — the v0.7.3 seek bar's second track.
     *  Written from the same render tick; equal-value writes to a float
     *  state snapshot are no-ops, so the bar only recomposes when the
     *  buffer actually advances. */
    private val bufferedState = mutableFloatStateOf(0f)

    /** Playback speed applied to the current video (drives the speed button). */
    private var restoredSpeed by mutableFloatStateOf(1f)

    /** URI of the media the engine is playing (speed persistence target). */
    private var currentUriStr: String? = null

    /** In-flight open pipeline: the [openVideo] coroutine, later replaced by
     *  its [commitPlay] coroutine. Cancelled when a newer open starts. */
    private var openJob: Job? = null

    /** Monotonic guard: a stale [openVideo] must not commit over a newer one. */
    private var openGeneration = 0

    /** True while the activity renders inside the system PiP window. */
    private var pipMode by mutableStateOf(false)

    /** Speed last applied this session; fallback when an episode has no saved value. */
    private var sessionSpeed = 1f

    /** Piecewise cut segments (audioSec to betaSec) from a persisted
     *  auto-sync lock. Empty = single affine lock (one offset everywhere). */
    private var piecewiseSegments: List<Pair<Double, Double>> = emptyList()

    // ── auto-advance (next episode) state ────────────────────────────

    /** Sibling episodes of the playing video; built once per [openVideo]. */
    private var episodeQueue by mutableStateOf<EpisodeQueue?>(null)

    /** Seconds left in the auto-advance countdown; -1 = inactive. */
    private var nextCountdownSec by mutableIntStateOf(-1)

    @Volatile
    private var pendingNext: Video? = null

    @Volatile
    private var switching = false

    /** One-shot set by the End-of-Episode sleep timer to stop auto-advance. */
    @Volatile
    private var holdAutoAdvanceOnce = false

    /** Render-loop runnable so an episode switch replaces the old loop. */
    private var renderTick: Runnable? = null

    /** Last cue list handed to [restartRenderLoop]; [onStart] resumes from it. */
    private var lastCues: List<SubtitleCue> = emptyList()

    /**
     * v0.6.2 sub-sync UX pass: deferred sidecar parse. Video plays first
     * with empty cues; the sidecar parse is scheduled ~200 ms after
     * playback starts so the user sees the first frame instantly instead
     * of waiting on a multi-MB sidecar parse. When the cues land, we
     * restart the render loop with them — the binary-search findCue
     * gracefully handles the empty-cues window.
     */
    private var deferredSidecarJob: Job? = null

    /**
     * Threshold (0.0..1.0) above which an existing persisted lock is
     * reused verbatim on next open. The SyncOrchestrator publishes the
     * recall on the persisted lock (see MediaStateStore.updateAutoSync
     * caller in SyncFingerprintJob); if recall ≥ this value we skip
     * fingerprinting and reuse. Below this, fingerprint re-runs (when
     * the user toggle is ON) so a poor prior lock gets refined.
     */
    private val persistedLockRecallReuseThreshold = 0.7

    /** True while the in-player settings screen is on top of the player. */
    private var settingsOpen by mutableStateOf(false)

    /** True while the in-player calibration banner is showing. */
    private var isCalibrating by mutableStateOf(false)

    /**
     * Human-readable playback error. Non-null shows the recoverable
     * Retry/Close dialog instead of leaving a frozen/black frame. Set from
     * [Player.Listener.onPlayerError] and from the [openVideo] failure path.
     */
    private var playbackError by mutableStateOf<String?>(null)

    /** True while [dev.anonrode.player.feature.player.PlaybackEngine.rebuild]
     *  is rebuilding the ExoPlayer around a new renderers factory. Mirrored
     *  into the HW chip in PlayerScreen to disable the button + show "…". */
    private var isRebuildingDecoder by mutableStateOf(false)

    /**
     * Bound to the current audio session id; rebound on every decoder
     * rebuild. Persists across the activity so the EQ toggle state
     * survives a screen rotation.
     */
    private val equalizer = EqualizerManager()
    private var equalizerOn by mutableStateOf(false)
    private var eqPanelOpen by mutableStateOf(false)
    private var audioTrackPickerOpen by mutableStateOf(false)
    private var subStyleSheetOpen by mutableStateOf(false)
    private var subStyle by mutableStateOf(SubtitleStyle())

    /** Subtitle source picker (embedded / sidecar / online). */
    private var subtitlePickerOpen by mutableStateOf(false)
    /** Persisted subtitle choice grammar ("" auto / none / embedded:N /
     *  sidecar:name / online:name) for the playing video. */
    private var subtitleChoice by mutableStateOf("")

    /** v0.7.3: number of supported text tracks in the current media,
     *  refreshed on every onTracksChanged. ORed with "sidecar cues loaded"
     *  into the dock CC chip's existence predicate (the old rail button
     *  was keyed on the transient cue text and blinked between cues). */
    private var subtitleTrackCount by mutableIntStateOf(0)

    /** Real file path of the playing video; null when unresolvable
     *  (SAF/network URIs), which disables embedded tracks + hash search. */
    @Volatile
    private var currentVideoPath: String? = null

    /**
     * Android system MediaRouter. We use it (instead of the Google Cast
     * SDK) to enumerate and pick audio output routes — it covers Cast
     * devices, Bluetooth audio, wired headsets, HDMI, and Miracast under
     * one API, with no new dependency. Lives for the activity's lifetime;
     * [onDestroy] releases the callback.
     */
    private lateinit var mediaRouter: MediaRouter
    private var castRouteCallback: MediaRouter.Callback? = null
    private var castPickerOpen by mutableStateOf(false)
    private var castRouteName by mutableStateOf<String?>(null)

    /**
     * Live subtitle offset for the SYNCED chip. Mirrors the engine's
     * computed offset (auto-lock + manual delay) plus any ±0.1s nudge the
     * user fires from the sync popover. Re-read every render tick.
     */
    private var liveOffsetMs by mutableStateOf(0L)

    /**
     * v0.7.1: true while the sync engine is genuinely working — the live
     * correlation is armed (toggle ON + cues attached + not yet locked or
     * given up) or a forced "Resync now" fingerprint is pending. Drives
     * the bottom-row toggle spinner and the SYNCED chip's visibility;
     * before this the spinner state existed but NOBODY ever set it.
     */
    private var subSyncRunning by mutableStateOf(false)

    /**
     * v0.7.4 P1-2 companion: true while a background fingerprint job for
     * the CURRENT video has actually been enqueued — i.e., there is a real
     * verdict in flight. The Room collector uses it to tell "a negative
     * verdict just landed" (nothing else will ever stop the "Syncing…"
     * indicator) from "this video was already checked in a past session"
     * (the flow's FIRST emission must not stop the spinner the live engine
     * is honestly earning). Reset on video switch and on every verdict.
     */
    private var syncAwaitingBackgroundVerdict = false

    /** Cumulative manual nudge in ms (persisted in Room). */
    private var manualNudgeMs by mutableStateOf(0L)

    /** Latest [PlayerSettings] snapshot for imperative (non-Compose) paths:
     *  resume behavior, auto-sync gate, background-playback gate. The
     *  Compose tree collects its own live snapshot at the call site. */
    @Volatile
    private var currentSettings: PlayerSettings = PlayerSettings(subtitleAutoSyncEnabled = true)

    /** Everything [commitPlay] needs, stashed while the resume prompt is up.
     *  [gen] is the [openGeneration] that produced it: commitPlay refuses to
     *  run for a superseded generation (stale resume-prompt clicks). */
    private data class PendingPlay(
        val uriStr: String,
        val cues: List<SubtitleCue>,
        val manual: Long,
        val auto: Long,
        val autoSpeed: Float,
        val speed: Float,
        val queue: EpisodeQueue?,
        val finished: Boolean,
        val savedPosMs: Long,
        val audioTrackIdx: Int?,
        val gen: Int,
    )

    private var pendingPlay: PendingPlay? = null

    /** Non-null while the "Resume from …?" prompt is showing (position ms). */
    private var resumePromptMs by mutableStateOf<Long?>(null)

    /** Persisted audio-track index to re-apply on the next onTracksChanged. */
    private var pendingAudioTrackIdx: Int? = null

    /** A-B repeat region (ms). Tap cycle via [advanceAbRepeat]:
     *  set A → set B (loop runs) → clear. Enforced in the render tick. */
    private var abStartMs by mutableStateOf<Long?>(null)
    private var abEndMs by mutableStateOf<Long?>(null)

    /** Per-video zoom index restored from Room (0=FIT 1=CROP 2=STR). */
    private var savedZoomIdx by mutableIntStateOf(0)

    /** Explicit ordered queue from library multi-select ([EXTRA_QUEUE_URIS]);
     *  null = derive the queue from folder siblings as usual. */
    private var explicitQueueUris: List<String>? = null

    private val playerEventListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && !switching) onEpisodeEnded()
            // Re-bind the EQ every time the player transitions to a new
            // session id (decoder rebuild, or first prepared playback).
            if (playbackState == Player.STATE_READY) {
                val sid = AnonrodeApp.get(this@PlayerActivity).engine.currentAudioSessionId
                if (sid != 0) equalizer.setSessionId(sid)
                // The (rebuilt) player reached READY — unblock the HW chip
                // right away; the 800ms postDelayed is only a fallback.
                isRebuildingDecoder = false
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // User resumed playback mid-countdown → stay on this episode.
            if (isPlaying && !switching && pendingNext != null) cancelNextCountdown()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updatePipAutoEnter(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            // Surface a recoverable dialog instead of freezing on the last
            // frame. The render loop keeps ticking harmlessly; Retry re-runs
            // openVideo which re-prepares the player from scratch.
            AppLog.e(
                "PLAYER",
                "playback error " + error.errorCodeName + ": " +
                    (error.cause?.message ?: error.message ?: "unknown"),
                error,
            )
            switching = false
            // A countdown tick firing under the error dialog would start
            // the NEXT episode underneath it — cancel the auto-advance.
            cancelNextCountdown()
            playbackError = friendlyPlaybackError(error)
        }

    override fun onTracksChanged(tracks: Tracks) {
        // v0.7.3: the dock's CC chip exists iff the media has subtitle
        // TRACKS (the screen ORs in "a sidecar is loaded" itself). Before
        // this, the rail's CC button was keyed on the on-screen cue text
        // and vanished between cues — a control blinking out of existence
        // mid-watch. Counted on EVERY tracks change (the audio-restore
        // early-return below must not skip it).
        subtitleTrackCount = tracks.groups.count {
            it.type == androidx.media3.common.C.TRACK_TYPE_TEXT && it.isSupported
        }
        // One-shot restore of the persisted audio-track choice: a
        // TrackSelectionOverride needs the real MediaTrackGroup, which
        // only exists once the manifest is ready — hence here rather
        // than at play() time. Index refers to the first audio group
        // (virtually all files have exactly one).
        val idx = pendingAudioTrackIdx ?: return
            pendingAudioTrackIdx = null
            val player = AnonrodeApp.get(this@PlayerActivity).engine.player
            for (group in player.currentTracks.groups) {
                if (group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue
                if (idx < group.mediaTrackGroup.length) {
                    val builder = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                    builder.clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
                    builder.addOverride(
                        androidx.media3.common.TrackSelectionOverride(
                            group.mediaTrackGroup, listOf(idx),
                        )
                    )
                    player.trackSelectionParameters = builder.build()
                    AppLog.d("TRACKS", "restored audio track index " + idx)
                }
                break
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bug-17 fix: after a config-change destroy (uiMode, density, locale
        // — not intercepted by the manifest's configChanges), the recreated
        // activity used to re-open the ORIGINAL intent URI — yanking the
        // user back to the first video of the session mid-episode. The
        // saved instance state round-trips the CURRENT uri; prefer it.
        val uriStr = savedInstanceState?.getString(KEY_CURRENT_URI_STR)
            ?: intent.getStringExtra(EXTRA_URI)
        if (uriStr == null) {
            finish()
            return
        }
        title = savedInstanceState?.getString(KEY_CURRENT_TITLE)
            ?: intent.getStringExtra(EXTRA_TITLE) ?: uriStr
        explicitQueueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS)
        val app = AnonrodeApp.get(this)
        val engine = app.engine

        // A live re-lock clears the persisted piecewise curve (persistence
        // side does it in the same write); drop our in-memory copy too so
        // the render loop stops applying a stale piecewise beta. Callback
        // fires on the sync-eval worker thread — post to main.
        engine.onLiveSyncLocked = {
            handler.post {
                piecewiseSegments = emptyList()
                // A live lock just landed: stop the spinner / calibration
                // banner immediately rather than waiting for their timeouts.
                subSyncRunning = false
                isCalibrating = false
            }
        }

        // The live engine spent its evaluation budget without finding a
        // fit. That is not the end of the story: hand the video to the
        // whole-file fingerprint engine (the one validated against real
        // content), which runs in the background and applies its lock to
        // this session through the Room collector below when it lands.
        // keepSpinner = the sync story continues (the background pass is
        // now the thing working on it), so "SYNCING" stays honest instead
        // of going dark the moment the live pass gives up.
        // Fires on the sync-eval worker thread.
        engine.onLiveSyncNoMatch = {
            handler.post {
                if (currentSettings.subtitleAutoSyncEnabled) {
                    scheduleFingerprintIfNeverChecked(keepSpinner = true, immediate = true)
                } else {
                    subSyncRunning = false
                }
            }
        }

        // v0.8 A-B: the engine's discontinuity listener needs to know the
        // loop region to recognise its own loop-back seeks (quiet
        // re-anchor instead of window discard). Provider, not mirror —
        // this class stays the single owner of the AB state.
        engine.abRegionProvider = {
            val a = abStartMs
            val b = abEndMs
            if (a != null && b != null && b > a) Pair(a, b) else null
        }

        // v0.7.1: apply a persisted fingerprint lock to the LIVE session.
        // The fingerprint job runs in the background (whole-file decode)
        // and writes its lock to Room; before this collector the lock only
        // took effect on the NEXT open of the video. Now, while this video
        // is playing and the user's toggle is ON, a lock landing in Room is
        // applied to the engine immediately — subs snap into place mid-
        // watch. Skips non-locks (0/1f sentinel); the live lock path
        // (onSyncLocked) overwrites cleanly if both land.
        lifecycleScope.launch {
            var collected = currentUriStr
            while (true) {
                val uri = collected ?: break
                app.stateStore.getAsFlow(uri).collect { st ->
                    val s = st ?: return@collect
                    if (s.autoSyncOffsetMs == 0L && s.autoSyncSpeedFactor == 1f) {
                        // v0.7.4 P1-2: a row change carrying the no-lock
                        // SENTINEL with a checked timestamp is the engine's
                        // NEGATIVE verdict landing while we watch (refused
                        // fit, or a no-usable-subtitle file at first open).
                        // Nothing will ever produce a lock for this video on
                        // its own — stop the "Syncing…" indicator instead of
                        // burning it forever. GATED on a verdict we actually
                        // scheduled: an ALREADY-checked video's first flow
                        // emission must not preempt the live engine's own
                        // honest attempt. Force "Resync now" re-arms.
                        if (syncAwaitingBackgroundVerdict) {
                            syncAwaitingBackgroundVerdict = false
                            if (s.autoSyncCheckedAtMs != 0L && subSyncRunning) {
                                withContext(Dispatchers.Main) {
                                    if (uri == currentUriStr) subSyncRunning = false
                                }
                            }
                        }
                        return@collect
                    }
                    if (!currentSettings.subtitleAutoSyncEnabled) return@collect
                    // The verdict we were waiting for (if any) is this lock.
                    syncAwaitingBackgroundVerdict = false
                    withContext(Dispatchers.Main) {
                        if (uri != currentUriStr) return@withContext
                        AnonrodeApp.get(this@PlayerActivity).engine
                            .applyPersistedLock(s.autoSyncOffsetMs, s.autoSyncSpeedFactor)
                        piecewiseSegments = parsePiecewise(s.autoSyncPiecewise)
                        // A real lock just landed for the video being watched
                        // (the sentinel values are filtered above): sync work
                        // for this video is done, so the "SYNCING" indicator
                        // must stop — the toggle stays ON, the subs are now
                        // corrected.
                        subSyncRunning = false
                    }
                }
                // getAsFlow completes only if the Activity scope is torn
                // down; loop back in case currentUriStr changed meanwhile.
                collected = currentUriStr
            }
        }

        // A video player keeps the screen on while it's up; the DataStore
        // setting can opt out once its async read lands (default = on).
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            val keepOn = try {
                app.playerSettingsDataStore.data.first().keepScreenOn
            } catch (e: Exception) {
                true
            }
            if (!keepOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Single live settings collector for the imperative paths (resume
        // behavior, auto-sync gate, background playback). It also mirrors
        // the persisted subtitle style into [subStyle] — style changes are
        // persisted immediately by applySubtitleStyle, so this echo is
        // idempotent and keeps sheet + cue + settings in agreement.
        lifecycleScope.launch {
            app.playerSettingsDataStore.data.collect { s ->
                currentSettings = s
                subStyle = s.toSubtitleStyle()
                // Live volume boost: the processor's gain is read per-buffer
                // on the audio thread, so this applies mid-playback.
                engine.setVolumeBoost(1f + s.volumeBoostPct / 100f)
                engine.setSubSyncEnabled(s.subtitleAutoSyncEnabled)
            }
        }

        // MediaRouter is an application service; grab it once and hold a
        // reference for the activity's lifetime. The picker composable
        // subscribes to its callback while visible; we release on destroy.
        //
        // Don't use getSystemService(MEDIA_ROUTER_SERVICE) here — on
        // Android 11+ that returns the framework android.media.MediaRouter
        // and casting it to androidx.mediarouter.media.MediaRouter throws
        // ClassCastException on every tap. The AndroidX wrapper exposes
        // its singleton through getInstance(context).
        mediaRouter = MediaRouter.getInstance(this)
        refreshCastRouteName()
        // (SimpleCallback was removed in mediarouter 1.8 — subclass Callback
        // directly; its many hooks are open with empty defaults.)
        val cb = object : MediaRouter.Callback() {
            override fun onRouteSelected(router: MediaRouter, route: RouteInfo) {
                refreshCastRouteName()
            }
            override fun onRouteUnselected(router: MediaRouter, route: RouteInfo) {
                refreshCastRouteName()
            }
        }
        castRouteCallback = cb
        // Passive callback only: activity-lifetime ACTIVE discovery would
        // scan for Cast/BT routes continuously; the picker sheet runs
        // its own active discovery while it is open.
        mediaRouter.addCallback(MediaRouteSelectorLite, cb)

        engine.addListener(playerEventListener)

        setContent {
            AnonrodeTheme {
                // Live settings snapshot: edits made in the in-player
                // settings screen apply to the player underneath it
                // immediately (seek step, gestures, auto-hide, style).
                val settings by app.playerSettingsDataStore.data
                    .collectAsState(initial = PlayerSettings())
                if (settingsOpen) {
                    SettingsScreen(onBack = { settingsOpen = false })
                } else {
                    val palette = rememberSkinPalette()
                    Box(modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .fillMaxSize()) {
                        PlayerScreen(
                            player = engine.player,
                            engine = engine,
                            title = title,
                            mediaId = currentUriStr ?: "",
                            cueText = cueText,
                            // v0.7.1 perf pass: pass the backing STATE objects
                            // (activity fields are mutableFloatStateOf) so a
                            // 10Hz tick recomposes only the seek bar, not the
                            // whole PlayerScreen body.
                            positionSec = positionState,
                            durationSec = durationState,
                            bufferedSec = bufferedState,
                            onBack = { finish() },
                            initialSpeed = restoredSpeed,
                            onSpeedChanged = { speed ->
                                sessionSpeed = speed
                                PlayerPrefs.saveGlobalSpeed(this@PlayerActivity, speed)
                                val targetUri = currentUriStr
                                lifecycleScope.launch(Dispatchers.IO) {
                                    if (targetUri != null) {
                                        app.stateStore.updatePlaybackSpeed(targetUri, speed)
                                        AppLog.d("SPEED", "persisted $speed for $targetUri")
                                    }
                                    try {
                                        app.playerSettingsDataStore.updateData { it.copy(defaultPlaybackSpeed = speed) }
                                    } catch (_: Throwable) {}
                                }
                            },
                            isPipMode = pipMode,
                            onEnterPip = { enterPip() },
                            hasNextEpisode = episodeQueue?.next() != null,
                            hasPreviousEpisode = episodeQueue?.previous() != null,
                            onPlayNext = { playNextNow() },
                            onPlayPrevious = { playPreviousNow() },
                            nextCountdownSec = nextCountdownSec,
                            onCancelNext = { cancelNextCountdown() },
                            onHoldAutoAdvance = { holdAutoAdvance() },
                            // Scanner already cleans titles (no extension,
                            // no path) — stripping here would truncate
                            // titles with internal dots ("Mr. Robot").
                            upNextTitle = episodeQueue?.next()?.title,
                            onOpenSettings = { settingsOpen = true },
                            liveOffsetMs = liveOffsetMs,
                            isCalibrating = isCalibrating,
                            onStartCalibration = {
                                // Real resync, not a placebo banner: the
                                // popover's RE-SYNC routes here and must
                                // actually (re)run the fingerprint — the
                                // old 4.3s auto-clearing banner did nothing.
                                isCalibrating = true
                                AppLog.d("PLAYER", "calibration started")
                                onResyncNow()
                                // Auto-clear the banner if no lock lands;
                                // a real lock clears it via the offset
                                // change (liveOffsetMs moves off 0).
                                handler.postDelayed({
                                    if (isCalibrating) {
                                        isCalibrating = false
                                        AppLog.d("PLAYER", "calibration done (timeout)")
                                    }
                                }, 4300L)
                            },
                            onNudgeSubtitle = { deltaMs ->
                                manualNudgeMs += deltaMs
                                val uri = currentUriStr
                                if (uri != null) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        app.stateStore.updateSubtitleDelay(uri, manualNudgeMs)
                                    }
                                }
                            },
                            isRebuildingDecoder = isRebuildingDecoder,
                            onRebuildDecoder = { newHw -> requestDecoderRebuild(newHw) },
                            onToggleEqualizer = { request -> requestToggleEqualizer(request) },
                            onOpenCastPicker = { requestOpenCastPicker() },
                            onOpenEqPanel = { eqPanelOpen = true },
                            onOpenAudioTrackPicker = { requestOpenAudioTrackPicker() },
                            onOpenSubStyle = { subStyleSheetOpen = true },
                            onOpenSubtitlePicker = { subtitlePickerOpen = true },
                            // v0.6.2 sub-sync UX pass: sync toggle callbacks.
                            // The toggle's icon flips instantly via
                            // PlayerScreenActions; these are the persistence
                            // and side-effect callbacks (engine gate, fingerprint
                            // job cancel on OFF, "resync now" force-schedule).
                            onSetSubSyncEnabled = { enabled -> onSetSubSyncEnabled(enabled) },
                            onResyncNow = { onResyncNow() },
                            subSyncRunning = subSyncRunning,
                            castRouteName = castRouteName,
                            subtitleStyle = subStyle,
                            onSubtitleStyleChanged = { applySubtitleStyle(it) },
                            seekIncrementSec = settings.seekIncrementSec,
                            // v0.7.3 dock wiring: the CC chip exists iff the
                            // media has subtitle tracks OR a sidecar is
                            // loaded; every host sheet pauses auto-hide; the
                            // Subtitle-source tile shows the ACTIVE choice.
                            hasSubtitleTrack = subtitleTrackCount > 0 ||
                                lastCues.isNotEmpty(),
                            hostSheetOpen = castPickerOpen || eqPanelOpen ||
                                audioTrackPickerOpen || subStyleSheetOpen ||
                                subtitlePickerOpen,
                            subtitleChoiceLabel = when {
                                subtitleChoice.isEmpty() -> "Auto"
                                subtitleChoice == "none" -> "None"
                                subtitleChoice.startsWith("embedded") -> "Embedded"
                                subtitleChoice.startsWith("sidecar") -> "Sidecar file"
                                else -> "Downloaded"
                            },
                            onSkipLengthChanged = { s ->
                                lifecycleScope.launch {
                                    app.playerSettingsDataStore.updateData {
                                        it.copy(seekIncrementSec = s)
                                    }
                                }
                            },
                            doubleTapSeekEnabled = settings.doubleTapSeek,
                            swipeToSeekEnabled = settings.swipeToSeek,
                            volumeGestureEnabled = settings.volumeGesture,
                            brightnessGestureEnabled = settings.brightnessGesture,
                            pinchZoomEnabled = settings.pinchZoom,
                            autoHideControlsMs = settings.autoHideControlsMs,
                            initialSleepTimerMinutes = settings.sleepTimerMinutes,
                            subtitleAutoSyncEnabled = settings.subtitleAutoSyncEnabled,
                            abStartMs = abStartMs,
                            abEndMs = abEndMs,
                            onAbRepeatTap = { advanceAbRepeat() },
                            initialZoomIdx = savedZoomIdx,
                            onZoomChanged = { idx ->
                                savedZoomIdx = idx
                                val uri = currentUriStr
                                if (uri != null) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        // Convention: stored value = idx + 1
                                        // (entity default 1.0 means "unset" = FIT).
                                        app.stateStore.updateZoom(uri, (idx + 1).toFloat())
                                    }
                                }
                            },
                            fastSeekThresholdSec = settings.fastSeekThresholdSec,
                            volumeBoostPct = settings.volumeBoostPct,
                            onShareSyncLog = { shareSyncLog() },
                            onVolumeBoostCycle = {
                                lifecycleScope.launch {
                                    app.playerSettingsDataStore.updateData { s ->
                                        s.copy(
                                            volumeBoostPct = when (s.volumeBoostPct) {
                                                0 -> 50
                                                50 -> 100
                                                100 -> 200
                                                else -> 0
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                    // ── Resume prompt (Settings → Resume behavior = Ask) ──
                    val resumePos = resumePromptMs
                    val pending = pendingPlay
                    if (resumePos != null && pending != null) {
                        AlertDialog(
                            onDismissRequest = { commitPlay(pending, resume = true) },
                            title = { Text("Resume playback?") },
                            text = { Text("You left off at " + fmtClock(resumePos) + ".") },
                            confirmButton = {
                                TextButton(onClick = { commitPlay(pending, resume = true) }) {
                                    Text("Resume")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { commitPlay(pending, resume = false) }) {
                                    Text("Start over")
                                }
                            },
                        )
                    }
                    // ── Cast route picker (audio output) ───────────────
                    if (castPickerOpen) {
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { castPickerOpen = false },
                            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .clickable(enabled = false) { /* swallow */ }
                                    .padding(12.dp),
                            ) {
                                CastRoutePickerSheet(
                                    mediaRouter = mediaRouter,
                                    accent = palette.accent,
                                    onSelectRoute = { onCastRouteSelected(it) },
                                    onDismiss = { castPickerOpen = false },
                                )
                            }
                        }
                    }
                    // ── Equalizer panel (5-band) ─────────────────────────
                    if (eqPanelOpen) {
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { eqPanelOpen = false },
                            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .clickable(enabled = false) { /* swallow */ }
                                    .padding(12.dp),
                            ) {
                                EqualizerPanelSheet(
                                    equalizer = equalizer,
                                    accent = palette.accent,
                                    onDismiss = { eqPanelOpen = false },
                                )
                            }
                        }
                    }
                    // ── Audio track picker ──────────────────────────────
                    if (audioTrackPickerOpen) {
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { audioTrackPickerOpen = false },
                            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .clickable(enabled = false) { /* swallow */ }
                                    .padding(12.dp),
                            ) {
                                AudioTrackPickerSheet(
                                    player = AnonrodeApp.get(this@PlayerActivity).engine.player,
                                    accent = palette.accent,
                                    onSelectTrack = { onAudioTrackSelected(it) },
                                    onDismiss = { audioTrackPickerOpen = false },
                                )
                            }
                        }
                    }
                    // ── Subtitle style picker ───────────────────────────
                    if (subStyleSheetOpen) {
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { subStyleSheetOpen = false },
                            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .clickable(enabled = false) { /* swallow */ }
                                    .padding(12.dp),
                            ) {
                                SubtitleStyleSheet(
                                    style = subStyle,
                                    accent = palette.accent,
                                    onStyleChanged = { applySubtitleStyle(it) },
                                    onDismiss = { subStyleSheetOpen = false },
                                )
                            }
                        }
                    }
                    // ── Subtitle source picker (embedded/sidecar/online) ─
                    if (subtitlePickerOpen) {
                        Box(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f))
                                .clickable { subtitlePickerOpen = false },
                            contentAlignment = androidx.compose.ui.Alignment.BottomCenter,
                        ) {
                            Box(
                                modifier = androidx.compose.ui.Modifier
                                    .clickable(enabled = false) { /* swallow */ }
                                    .padding(12.dp),
                            ) {
                                SubtitlePickerSheet(
                                    videoUri = currentUriStr ?: "",
                                    videoPath = currentVideoPath,
                                    currentChoice = subtitleChoice,
                                    accent = palette.accent,
                                    preferredLangs = settings.defaultSubtitleLanguage ?: "",
                                    onSelect = { choice -> onSubtitleChoiceSelected(choice) },
                                    onDismiss = { subtitlePickerOpen = false },
                                )
                            }
                        }
                    }
                }
                // ── Recoverable playback error (Retry / Close) ──────────
                // Shown for a Media3 onPlayerError or an openVideo failure.
                // Retry re-runs the open pipeline for the same URI; Close
                // backs out to the library instead of leaving a frozen frame.
                val errMsg = playbackError
                if (errMsg != null) {
                    AlertDialog(
                        onDismissRequest = { playbackError = null },
                        title = { Text("Can't play this video") },
                        text = { Text(errMsg) },
                        confirmButton = {
                            TextButton(onClick = {
                                playbackError = null
                                currentUriStr?.let { u -> openVideo(u, title) }
                            }) { Text("Retry") }
                        },
                        dismissButton = {
                            TextButton(onClick = { finish() }) { Text("Close") }
                        },
                    )
                }
            }
        }

        openVideo(uriStr, title)
    }

    /**
     * singleTask relaunch: the library tapped another video while this
     * activity is still alive (PiP dismissed, Home, or back-to-library
     * without destroy). Save progress on the current item, then switch.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newUri = intent.getStringExtra(EXTRA_URI) ?: return
        val newTitle = intent.getStringExtra(EXTRA_TITLE) ?: newUri
        if (newUri == currentUriStr) return
        explicitQueueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS)
        AnonrodeApp.get(this).engine.savePositionNow()
        openVideo(newUri, newTitle)
    }

    /**
     * Resolve sidecar subtitles + persisted state off the main thread, build
     * the [EpisodeQueue] from sibling videos sharing the folder, then start
     * playback. Reused for the initial open and every episode switch; resets
     * Compose-backed state for a fresh start each time.
     */
    private fun openVideo(uriStr: String, displayTitle: String) {
        val app = AnonrodeApp.get(this)
        val engine = app.engine
        val prefSync = PlayerPrefs.autoSyncEnabled(this)
        currentSettings = currentSettings.copy(subtitleAutoSyncEnabled = prefSync)
        // Supersede any in-flight open: cancel its coroutine and bump the
        // generation guard so work already past its last suspension point
        // aborts before touching the engine, shared state, or the queue.
        openJob?.cancel()
        openGeneration++
        val gen = openGeneration
        currentUriStr = uriStr
        // v0.7.4 P1-2: a background verdict for the PREVIOUS video can no
        // longer reach this session — disarm the collector's verdict gate so
        // the fresh video's own scheduling arms it anew.
        syncAwaitingBackgroundVerdict = false
        // Kick off the MediaStore aspect lookup off the main thread so the
        // next PiP enter has a cached (w,h) and never blocks on a query.
        refreshPipAspectAsync(uriStr)

        // Fresh UI state for the new media item.
        cueText = null
        positionSec = 0f
        // v0.7.3: the new media's onTracksChanged repopulates this; zero it
        // at open so the CC chip doesn't inherit the previous file's
        // subtitle tracks until the fresh manifest arrives.
        subtitleTrackCount = 0
        durationSec = 0f
        title = displayTitle
        resumePromptMs = null
        pendingPlay = null
        abStartMs = null
        abEndMs = null

        openJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppLog.d("PLAY", "opening " + uriStr)
                val state = app.stateStore.get(uriStr)
                // Per-video zoom: stored value = idx + 1 (1.0 = unset = FIT).
                // Clamp to the PlayerScreen ZoomModes range (FIT, CROP, STR,
                // 16:9, 4:3 → indices 0..4) so a stale/large value can't
                // index out of bounds.
                val zoomIdx = ((state?.videoScale ?: 1f) - 1f).toInt().coerceIn(0, 4)
                // Subtitle source: the picker's persisted choice wins;
                // empty choice = MKV embedded fast-path, else auto-pick.
                val choice = state?.subtitleChoice.orEmpty()
                val videoPath = resolveVideoPath(uriStr)
                // resolveVideoPath blocks (not cancellable) — a newer open
                // may have landed while it ran; don't publish stale state.
                if (gen != openGeneration) return@launch
                currentVideoPath = videoPath
                // v0.6.2 SUB-SYNC UX PASS — DEFERRED SIDECAR PARSE.
                // The sidecar parse used to block openVideo on Dispatchers.IO
                // before commit, so a multi-MB sub file delayed first-frame.
                // New flow: video plays first with empty cues; the sidecar
                // parse is scheduled ~200ms after playback starts so the user
                // sees the first frame instantly. The binary-search findCue
                // gracefully handles the empty-cues window (returns null
                // until cues land) and the render loop restarts with the
                // late cues via [restartRenderLoop].
                //
                // Embedded tracks resolve synchronously (they're small
                // metadata reads on MediaExtractor) so we keep the sync path
                // for that case — only the I/O-heavy sidecar parse is
                // deferred.
                val sortedCues: List<SubtitleCue>
                val resolvedSource: String
                if (choice.startsWith("embedded:") || choice.startsWith("sidecar:") ||
                    choice.startsWith("online:") || choice == "none") {
                    val parsed = SubtitleSourceResolver.resolveCues(
                        applicationContext, uriStr, videoPath, choice,
                    )
                    sortedCues = parsed.sortedBy { it.start }
                    resolvedSource = if (choice.isEmpty()) "auto-embedded" else choice
                } else {
                    // AUTO path with no embedded fast-path hit: defer the
                    // sidecar parse. Empty cues for now; they land ~200ms
                    // after commit via [scheduleDeferredSidecarParse].
                    sortedCues = emptyList()
                    resolvedSource = "auto-deferred"
                }
                AppLog.d(
                    "PLAY",
                    "subtitle source='" + resolvedSource +
                        "' committed " + sortedCues.size + " cues"
                )
                val manual = state?.subtitleDelayMs ?: 0L
                val auto = state?.autoSyncOffsetMs ?: 0L
                val autoSpeed = state?.autoSyncSpeedFactor ?: 1f
                // Generation guard: a newer openVideo supersedes this one.
                if (gen != openGeneration) return@launch
                piecewiseSegments = parsePiecewise(state?.autoSyncPiecewise ?: "")

                // Policy A (v0.6.2, revised v0.7.1, trust rule v0.8): gate
                // the background fingerprint on TWO signals.
                //   1. User toggle [subtitleAutoSyncEnabled] = ON.
                //   2. The fingerprint has never reached a verdict for this
                //      video (autoSyncCheckedAtMs == 0). The job marks the
                //      row when it decides, so the whole-file decode runs
                //      once per video (choice changes clear the mark;
                //      "Resync now" bypasses both gates).
                // A STORED LOCK no longer suppresses scheduling (v0.8
                // P1-7): live locks also write auto_sync_offset_ms, and the
                // unvalidated live value must not permanently block the
                // engine whose gates were validated on real content — the
                // fingerprint runs and its verdict supersedes through the
                // Room collector. play() still applies the stored lock at
                // open so subs are corrected immediately meanwhile.
                val userToggleOn = currentSettings.subtitleAutoSyncEnabled
                val alreadyChecked = (state?.autoSyncCheckedAtMs ?: 0L) != 0L
                val shouldScheduleFingerprint = userToggleOn &&
                    !alreadyChecked &&
                    choice != "none"
                if (shouldScheduleFingerprint) {
                    AppLog.d(
                        "PLAY",
                        "scheduling fingerprint: toggleOn=$userToggleOn " +
                            "checked=$alreadyChecked choice='$choice'",
                    )
                    // v0.7.4 P1-2: a background verdict is now genuinely in
                    // flight for this video — arm the collector gate so its
                    // sentinel+checked emission (refused fit / no usable
                    // subtitle) can stop the "Syncing…" indicator. (The
                    // log line above had a missing comma in the concat; the
                    // 09-13 Anon incident taught us a missing comma eats the
                    // NEXT call separator, so brace-check after this.)
                    syncAwaitingBackgroundVerdict = true
                    SyncFingerprint.schedule(applicationContext, uriStr)
                }

                withContext(Dispatchers.Main) {
                    // A newer open may have landed during the hop to Main.
                    if (gen != openGeneration) return@withContext
                    manualNudgeMs = manual
                    subtitleChoice = choice
                }

                // Speed persistence: apply this video's saved speed, then the
                // user's default playback speed (settings), then the global
                // last-used play_speed preference, then the session speed.
                // (this@PlayerActivity: inside launch{} `this` is the scope)
                val speed = app.stateStore.savedPlaybackSpeed(uriStr)
                    ?: PlayerPrefs.globalSpeed(this@PlayerActivity)
                    ?: sessionSpeed
                    ?: currentSettings.defaultPlaybackSpeed.takeIf { it > 0f }
                    ?: 1f
                if (gen != openGeneration) return@launch
                sessionSpeed = speed

                // Episode queue: an explicit multi-select list (library) wins;
                // otherwise every video sharing this folder, sorted by
                // season/episode number, index resolved to uriStr. Built from
                // the already-observed library snapshot — the library screen's
                // observer keeps the scanner cache fresh, so opens don't pay
                // for a MediaStore round-trip. A scan happens only when no
                // snapshot exists yet (cold open), with one retry when the
                // video is missing from a stale snapshot.
                val queueUris = explicitQueueUris
                var snapshot = app.scanner.cachedSnapshot()?.videos
                    ?: app.scanner.scan().videos
                var currentVideo = snapshot.firstOrNull { it.uri == uriStr }
                if (currentVideo == null) {
                    // Absent from the (possibly stale) snapshot — refresh
                    // once; scan() is a cheap no-op when already fresh.
                    snapshot = app.scanner.scan().videos
                    currentVideo = snapshot.firstOrNull { it.uri == uriStr }
                }
                val queue = if (queueUris != null && queueUris.size > 1) {
                    EpisodeQueue.fromExplicitVideos(snapshot, queueUris, uriStr)
                        ?: currentVideo?.let { EpisodeQueue.fromVideos(snapshot, it) }
                } else {
                    currentVideo?.let { EpisodeQueue.fromVideos(snapshot, it) }
                }

                // Stale-open guard: re-check right before the commit AND on
                // the main thread — a newer open may have landed mid-hop.
                if (gen != openGeneration) return@launch
                withContext(Dispatchers.Main) {
                    if (gen != openGeneration) return@withContext
                    restoredSpeed = speed
                    savedZoomIdx = zoomIdx
                    queue?.current?.title?.let { title = it }
                    val pending = PendingPlay(
                        uriStr = uriStr,
                        cues = sortedCues,
                        manual = manual,
                        auto = auto,
                        autoSpeed = autoSpeed,
                        speed = speed,
                        queue = queue,
                        finished = state?.finished == true,
                        savedPosMs = state?.playbackPositionMs ?: 0L,
                        audioTrackIdx = state?.audioTrackIndex,
                        gen = gen,
                    )
                    pendingPlay = pending
                    // Resume behavior (Settings): ask once per open when a
                    // real resume point exists; otherwise obey the stored
                    // preference silently.
                    val behavior = currentSettings.resumeBehavior
                    if (behavior == ResumeBehavior.ALWAYS_ASK &&
                        pending.savedPosMs > 5000L && !pending.finished) {
                        switching = false
                        resumePromptMs = pending.savedPosMs
                    } else {
                        commitPlay(pending, resume = behavior != ResumeBehavior.ALWAYS_START_OVER)
                    }
                }
            } catch (e: Exception) {
                // A superseded (cancelled) open must not clobber shared
                // state or surface its error over the newer video.
                if (gen != openGeneration || e is CancellationException) return@launch
                switching = false
                AppLog.e("PLAY", "FAILED to start playback", e)
                withContext(Dispatchers.Main) {
                    // Recoverable dialog (Retry re-runs openVideo) instead of
                    // a transient Toast over a black, unresponsive frame.
                    playbackError = "Couldn't start playback. " +
                        "The file may be missing, corrupt, or unsupported."
                }
            }
        }
    }

    /**
     * Final step of [openVideo] — directly, or once the user answers the
     * resume prompt. Applies the speed, starts the engine (restoring the
     * persisted audio track), and launches the subtitle render loop.
     *
     * Serialized with [openVideo] through [openGeneration] + [openJob]: a
     * commit produced by a superseded open never reaches the engine, and a
     * newer openVideo cancels this commit while it is still queued.
     */
    private fun commitPlay(pending: PendingPlay, resume: Boolean) {
        // Generation guard: a newer [openVideo] supersedes this commit.
        // Covers the resume-prompt path too — a click already in flight when
        // the user switches videos must not start the stale episode.
        if (pending.gen != openGeneration) return
        val app = AnonrodeApp.get(this)
        val engine = app.engine
        resumePromptMs = null
        pendingAudioTrackIdx = pending.audioTrackIdx
        // Point-of-need: the foreground playback service (and thus its
        // notification) starts inside engine.play() below, so ask for the
        // notification permission here rather than cold in onCreate.
        ensureNotificationPermission()
        // Tracked as [openJob] so a newer openVideo cancels this commit.
        openJob = lifecycleScope.launch {
            if (pending.gen != openGeneration) return@launch
            // Persistent decoder priority (Settings): three real engine
            // profiles — HW+SW (device wins, FFmpeg fallback), APP SW
            // (FFmpeg preferred), HW ONLY (no extension renderers).
            // Rebuild BEFORE capturing the player — rebuild swaps the
            // instance out from under us.
            val wantMode = when (currentSettings.decoderPriority) {
                DecoderPriority.PREFER_APP -> PlaybackEngine.MODE_PREFER_APP
                DecoderPriority.DEVICE_ONLY -> PlaybackEngine.MODE_DEVICE_ONLY
                else -> PlaybackEngine.MODE_PREFER_DEVICE
            }
            if (engine.decoderMode != wantMode) {
                engine.pendingSpeedOnRebuild = pending.speed
                engine.rebuildMode(wantMode)
            }
            val player = engine.player
            player.setPlaybackSpeed(pending.speed)
            // Fresh resume point, read BEFORE the engine starts: a position
            // save may have landed mid-open (episode switches and subtitle
            // reloads persist progress right before re-opening). This is the
            // last suspension before playback — the value is handed to
            // engine.play, which then runs the whole setMediaItem → seekTo →
            // prepare pipeline without suspending (no frame-0 flash, and no
            // window where a newer open could interleave).
            val savedPosMs = if (resume) {
                app.stateStore.get(pending.uriStr)?.playbackPositionMs ?: 0L
            } else {
                0L
            }
            if (pending.gen != openGeneration) return@launch
            engine.play(
                MediaItem.fromUri(pending.uriStr), pending.uriStr, pending.cues,
                pending.manual, pending.auto, pending.autoSpeed,
                resume = resume,
                // v0.6.2 sub-sync UX pass: live re-lock is now gated by
                // [subtitleAutoSyncEnabled] (user toggle, default OFF).
                // The legacy autoSyncEnabled kept its v0.6.1 default-true
                // semantics for any code paths that still read it, but
                // the engine itself only needs the new field.
                syncEnabled = currentSettings.subtitleAutoSyncEnabled,
                savedPositionMs = savedPosMs,
            )
            // v0.6.2: also apply the new toggle to the audio processor
            // directly so the live re-lock is gated even before
            // engine.play's syncEnabled reaches it.
            engine.setSubSyncEnabled(currentSettings.subtitleAutoSyncEnabled)
            // v0.6.2: deferred sidecar parse for the AUTO path — video
            // plays first with empty cues, cues land ~200ms later.
            if (sortedCuesWasDeferred(pending.uriStr, pending.cues)) {
                scheduleDeferredSidecarParse(pending.uriStr, currentVideoPath, subtitleChoice)
            }
            // Belt-and-braces: skip publishing state if a newer open landed.
            if (pending.gen != openGeneration) return@launch
            // Fully-watched episodes restart from the top instead of
            // resuming at the final frame; the resulting seek
            // discontinuity re-anchors the sync processor.
            if (pending.finished) player.seekTo(0)
            restartRenderLoop(pending.cues)
            episodeQueue = pending.queue
            switching = false
        }
    }

    /**
     * POST_NOTIFICATIONS, asked once per activity instance at the moment the
     * foreground playback service (and its notification) actually starts —
     * not cold in [onCreate]. On API < 33 the permission doesn't exist, so
     * this is a no-op.
     */
    private var notificationPermissionAsked = false
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33 || notificationPermissionAsked) return
        notificationPermissionAsked = true
        if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    /** Map persisted settings onto the renderer's style envelope. */
    private fun PlayerSettings.toSubtitleStyle() = SubtitleStyle(
        size = SubtitleSize.values().getOrElse(subtitleSize) { SubtitleSize.MEDIUM },
        position = SubtitlePosition.values().getOrElse(subtitlePosition) { SubtitlePosition.LOW },
        color = SubtitleColor.values().getOrElse(subtitleColor) { SubtitleColor.WHITE },
        bold = subtitleBold,
    )

    /**
     * Single mutation path for the subtitle style (style sheet AND in-screen
     * long-press dropdown): update live state and persist to the DataStore
     * so the choice survives player restarts.
     */
    private fun applySubtitleStyle(newStyle: SubtitleStyle) {
        subStyle = newStyle
        val app = AnonrodeApp.get(this)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                app.playerSettingsDataStore.updateData { s ->
                    s.copy(
                        subtitleSize = newStyle.size.ordinal,
                        subtitlePosition = newStyle.position.ordinal,
                        subtitleColor = newStyle.color.ordinal,
                        subtitleBold = newStyle.bold,
                    )
                }
            } catch (e: Exception) {
                AppLog.e("STYLE", "persist subtitle style failed", e)
            }
        }
    }

    /** Short clock label for the resume prompt: 12:34 or 1:02:03. */
    private fun fmtClock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * Map a Media3 [PlaybackException] onto a short, human-readable message
     * for the recoverable error dialog. Groups the many ERROR_CODE_* values
     * into the few things a user can actually act on.
     */
    private fun friendlyPlaybackError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "The file can't be found. It may have been moved, renamed, or deleted."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "No permission to read this file."
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_TIMEOUT ->
            "Network problem while loading this video."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "This video's format or codec isn't supported on this device. " +
                "Try switching the decoder (HW/SW) from the player."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
            "Couldn't start audio output for this video."
        else -> "Playback failed (" + error.errorCodeName + ")."
    }

    /**
     * A-B repeat tap cycle: first call marks A at the current position,
     * second marks B and the loop runs (enforced by the render tick),
     * third clears. A B that would land less than 1s after A restarts the
     * region instead of creating an unplayably tight loop.
     */
    private fun advanceAbRepeat() {
        val posMs = AnonrodeApp.get(this).engine.player.currentPosition
        when {
            abStartMs == null -> {
                abStartMs = posMs
                AppLog.d("AB", "A set at " + posMs + "ms")
            }
            abEndMs == null -> {
                if (posMs > (abStartMs ?: 0L) + 1000L) {
                    abEndMs = posMs
                    AppLog.d("AB", "B set at " + posMs + "ms — looping")
                } else {
                    abStartMs = posMs
                    AppLog.d("AB", "B too close to A — restarted A at " + posMs + "ms")
                }
            }
            else -> {
                abStartMs = null
                abEndMs = null
                AppLog.d("AB", "repeat cleared")
            }
        }
    }

    /** Enter Picture-in-Picture using the video's real aspect (API 26+). */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26 && !isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder()
                        .setAspectRatio(pipAspect())
                        .build()
                )
            } catch (e: Exception) {
                // Already-in-PiP or a non-resizable window throws here.
                AppLog.e("PIP", "enterPictureInPictureMode failed", e)
            }
        }
    }

    /**
     * Cached MediaStore-derived (w,h) pair for PiP aspect lookup. Populated
     * by [refreshPipAspectAsync] on a background dispatcher; read by
     * [pipAspect] so the fast path stays synchronous and never blocks the
     * main thread on a contentResolver.query.
     */
    private var pipAspectCacheW by mutableIntStateOf(0)
    private var pipAspectCacheH by mutableIntStateOf(0)

    /**
     * Kick off the MediaStore aspect lookup off the main thread. The query
     * is wrapped in try/catch — the path may not exist on some emulators
     * and a failure here must never block playback or PiP entry.
     */
    private fun refreshPipAspectAsync(uriStr: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            var w = 0
            var h = 0
            try {
                contentResolver.query(
                    Uri.parse(uriStr),
                    arrayOf(
                        android.provider.MediaStore.Video.Media.WIDTH,
                        android.provider.MediaStore.Video.Media.HEIGHT,
                    ),
                    null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        w = c.getInt(0)
                        h = c.getInt(1)
                    }
                }
            } catch (e: Exception) {
                AppLog.e("PIP", "aspect lookup failed", e)
            }
            if (w > 0 && h > 0) {
                // Snapshot writes — safe from any thread (Compose routes
                // them through the main looper).
                pipAspectCacheW = w
                pipAspectCacheH = h
            }
        }
    }

    /**
     * PiP aspect ratio from the real video size, clamped to Android's
     * allowed [0.418, 2.39] range. Player videoSize first, MediaStore
     * cache (populated by [refreshPipAspectAsync]) second, 16:9 fallback
     * when unknown. The actual contentResolver.query is always off the
     * main thread so PiP entry never blocks on disk.
     */
    private fun pipAspect(): Rational {
        val size = AnonrodeApp.get(this).engine.player.videoSize
        var w = size.width
        var h = size.height
        // Player hasn't reported a size yet — fall back to the cached
        // MediaStore result (populated by [refreshPipAspectAsync] off
        // the main thread); 16:9 if we don't have that either.
        if (w <= 0 || h <= 0) {
            if (pipAspectCacheW > 0 && pipAspectCacheH > 0) {
                w = pipAspectCacheW
                h = pipAspectCacheH
            } else {
                return Rational(16, 9)
            }
        }
        val ratio = w.toDouble() / h.toDouble()
        return when {
            ratio < 0.418 -> Rational(418, 1000)
            ratio > 2.39 -> Rational(239, 100)
            else -> Rational(w, h)
        }
    }

    /**
     * Real HW/SW decoder swap. Drives [PlaybackEngine.rebuild] on the main
     * thread (Media3 requires player access on the application's main
     * looper), then waits for the rebuilt player to report STATE_READY
     * before unblocking the HW chip. The render loop's next tick re-reads
     * the engine's new [PlaybackEngine.player] automatically.
     */
    private fun requestDecoderRebuild(newHw: Boolean): Int {
        val engine = AnonrodeApp.get(this).engine
        if (engine.isHw == newHw) {
            return engine.currentAudioSessionId
        }
        return performDecoderCycle(engine)
    }

    /**
     * v0.7.1: the chip now CYCLES the three real profiles
     * (HW+SW → APP SW → HW ONLY) instead of ping-ponging a boolean between
     * two of them. Shared by the chip cycle and any legacy boolean caller.
     */
    private fun performDecoderCycle(engine: PlaybackEngine): Int {
        // Persist the current speed so the rebuilt player comes up at the
        // same rate the user picked. sessionSpeed is the live value (the
        // speed pill updates it on every change); restoredSpeed is frozen
        // at open time and would roll back mid-session speed changes.
        engine.pendingSpeedOnRebuild = sessionSpeed.takeIf { it > 0f } ?: 1f
        isRebuildingDecoder = true
        return try {
            val newSessionId = engine.cycleDecoderMode()
            AppLog.d("PLAYER", "decoder cycle complete: mode=" + engine.decoderModeLabel + " session=" + newSessionId)
            // Re-bind the Equalizer to the rebuilt player's session id. We
            // use the returned id (which may be 0 if the new player hasn't
            // attached a session yet) and re-apply on the next state-ready
            // tick via the same hook the PlayerService uses.
            equalizer.setSessionId(newSessionId)
            // Mirror the prior enabled state — the user expects "EQ on" to
            // stay on after a decoder swap, not silently flip off.
            if (equalizerOn) equalizer.setEnabled(true)
            newSessionId
        } catch (e: Exception) {
            AppLog.e("PLAYER", "decoder rebuild FAILED", e)
            0
        } finally {
            // Fallback only: STATE_READY in [playerEventListener] normally
            // clears the flag sooner; this bounds the never-ready case.
            // Use lifecycleScope (not the Handler) so a configuration
            // change that recreates the activity cancels the stale delay
            // instead of letting it fire on a dead instance.
            lifecycleScope.launch {
                delay(800L)
                isRebuildingDecoder = false
            }
        }
    }

    /**
     * Real EQ toggle. The host owns the [EqualizerManager] so the effect
     * survives across Compose recompositions; the screen merely asks for
     * a desired on/off and we report back the actual state (the
     * `audiofx` stack silently refuses on devices that don't support
     * effects, e.g. some emulator images).
     */
    private fun requestToggleEqualizer(requested: Boolean): Boolean {
        // If the player is still warming up, attempt to bind the effect
        // off the current session id. setSessionId(0) is a safe no-op.
        equalizer.setSessionId(AnonrodeApp.get(this).engine.currentAudioSessionId)
        if (!equalizer.isBound) {
            AppLog.d("EQ", "toggle requested but effect not bound to a session id")
            equalizerOn = false
            return false
        }
        val ok = equalizer.setEnabled(requested)
        equalizerOn = ok && equalizer.isEnabled
        return equalizerOn
    }

    /**
     * Re-read the currently selected route off the MediaRouter. Mirrored
     * into Compose state so the Cast chip's tooltip + active tint stay
     * live. Returns null when the user is back on the phone speaker.
     */
    private fun refreshCastRouteName() {
        if (!::mediaRouter.isInitialized) return
        val sel = mediaRouter.selectedRoute ?: return
        castRouteName = if (sel.isDefault) null else sel.name
    }

    private fun requestOpenCastPicker() {
        AppLog.d("CAST", "opening route picker")
        castPickerOpen = true
    }

    private fun onCastRouteSelected(route: RouteInfo) {
        AppLog.d("CAST", "selecting route: " + route.name + " (default=" + route.isDefault + ")")
        mediaRouter.selectRoute(route)
        // The MediaRouter callback will fire onRouteSelected and update
        // [castRouteName] via [refreshCastRouteName], but write it eagerly
        // so the chip turns green instantly.
        castRouteName = if (route.isDefault) null else route.name
        castPickerOpen = false
    }

    private fun requestOpenAudioTrackPicker() {
        AppLog.d("TRACKS", "opening audio track picker")
        audioTrackPickerOpen = true
    }

    private fun onAudioTrackSelected(trackId: String) {
        // trackId is "groupIndex:trackIndexInGroup" (the picker builds it
        // from the same currentTracks snapshot). Resolve the group and
        // apply via TrackSelectionOverride — the supported public way to
        // switch tracks at runtime.
        val parts = trackId.split(":")
        if (parts.size != 2) {
            AppLog.e("TRACKS", "bad trackId format: " + trackId)
            return
        }
        val player = AnonrodeApp.get(this).engine.player
        val groupIdx = parts[0].toIntOrNull()
        val targetIndex = parts[1].toIntOrNull()
        if (groupIdx == null || targetIndex == null) {
            AppLog.e("TRACKS", "non-int trackId parts: " + trackId)
            return
        }
        val group = player.currentTracks.groups.getOrNull(groupIdx)
        if (group == null || group.type != androidx.media3.common.C.TRACK_TYPE_AUDIO) {
            AppLog.e("TRACKS", "no audio group at index: " + groupIdx)
            return
        }
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
        builder.clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
        builder.addOverride(
            androidx.media3.common.TrackSelectionOverride(
                group.mediaTrackGroup, listOf(targetIndex),
            )
        )
        player.trackSelectionParameters = builder.build()
        AppLog.d("TRACKS", "selected track: $trackId")
        // Persist so the same track is restored on the next open (#30).
        val uri = currentUriStr
        if (uri != null) {
            val app = AnonrodeApp.get(this)
            lifecycleScope.launch(Dispatchers.IO) {
                app.stateStore.updateAudioTrack(uri, targetIndex)
            }
        }
        audioTrackPickerOpen = false
    }

    /**
     * Home gesture / app switch while playing: hand playback off to the
     * floating PiP window instead of stopping it.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val engine = AnonrodeApp.get(this).engine
        if (Build.VERSION.SDK_INT >= 26 && engine.player.isPlaying) {
            enterPip()
        }
    }

    /** Mirror PiP transitions into Compose state; PlayerScreen hides overlays. */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode = isInPictureInPictureMode
        AppLog.d("PIP", "pip mode = " + isInPictureInPictureMode)
    }

    override fun onStart() {
        super.onStart()
        // Resume the subtitle render loop paused in onStop — only when
        // cues are loaded (PiP kept it running, so nothing to resume).
        if (!pipMode && lastCues.isNotEmpty()) {
            renderTick?.let { tick ->
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Pause the render loop while the activity is invisible — except
        // in PiP, where subtitles keep rendering in the floating window.
        if (!pipMode) {
            renderTick?.let { handler.removeCallbacks(it) }
            // Settings gate: with background playback disabled, leaving
            // the activity (Home / app switch) pauses the video. The
            // foreground service keeps the session warm for the return.
            if (!currentSettings.backgroundPlayback) {
                AnonrodeApp.get(this).engine.player.pause()
            }
        }
    }

    // ── auto-advance: queue-driven navigation + countdown ────────────

    /** STATE_ENDED: persist completion, then maybe queue the next episode. */
    private fun onEpisodeEnded() {
        val app = AnonrodeApp.get(this)
        // Position == duration here, so savePositionNow marks it finished.
        app.engine.savePositionNow()
        lifecycleScope.launch {
            val autoAdvance = try {
                app.playerSettingsDataStore.data.first().autoAdvance
            } catch (e: Exception) {
                AppLog.e("NEXT", "settings read failed", e)
                true
            }
            val next = episodeQueue?.next()
            if (autoAdvance && next != null && !consumeAutoAdvanceHold()) {
                AppLog.d("NEXT", "queuing " + next.title)
                beginNextCountdown(next)
            }
        }
    }

    private fun consumeAutoAdvanceHold(): Boolean =
        holdAutoAdvanceOnce.also { holdAutoAdvanceOnce = false }

    /** Sleep timer chose "End of episode": stop instead of advancing. */
    private fun holdAutoAdvance() {
        holdAutoAdvanceOnce = true
        cancelNextCountdown()
    }

    private fun beginNextCountdown(ep: Video) {
        pendingNext = ep
        nextCountdownSec = NEXT_COUNTDOWN_SEC
        handler.removeCallbacks(countdownTick)
        handler.postDelayed(countdownTick, 1000L)
    }

    private val countdownTick = object : Runnable {
        override fun run() {
            val ep = pendingNext ?: run {
                nextCountdownSec = -1
                return
            }
            val n = nextCountdownSec - 1
            if (n <= 0) {
                performSwitch(ep)
            } else {
                nextCountdownSec = n
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private fun cancelNextCountdown() {
        pendingNext = null
        nextCountdownSec = -1
        handler.removeCallbacks(countdownTick)
    }

    /** Jump straight to the next episode (Up Next pill or "Play now"). */
    private fun playNextNow() {
        val ep = pendingNext ?: episodeQueue?.next() ?: return
        performSwitch(ep)
    }

    /** Jump to the previous episode (transport skip-back). */
    private fun playPreviousNow() {
        val ep = episodeQueue?.previous() ?: return
        performSwitch(ep)
    }

    /**
     * Switch to [ep]. Saving progress first means an episode that genuinely
     * reached the end persists as finished, while early manual skips keep
     * their resume position unmarked.
     */
    private fun performSwitch(ep: Video) {
        AppLog.d("NEXT", "switching to " + ep.title)
        pendingNext = null
        nextCountdownSec = -1
        handler.removeCallbacks(countdownTick)
        switching = true
        // Capture + persist current progress before currentUriStr moves on.
        AnonrodeApp.get(this).engine.savePositionNow()
        openVideo(ep.uri, ep.title)
    }

    // ── Picture-in-Picture helpers ───────────────────────────────────

    /**
     * Keep the S+ gesture-nav auto-enter flag in sync with play/pause:
     * leaving the app while playing pops PiP automatically; leaving while
     * paused does not.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun updatePipAutoEnter(playing: Boolean) {
        if (pipMode) return
        try {
            setPictureInPictureParams(
                PictureInPictureParams.Builder()
                    .setAspectRatio(pipAspect())
                    .setAutoEnterEnabled(playing)
                    .build()
            )
        } catch (e: Exception) {
            AppLog.e("PIP", "param update failed", e)
        }
    }

    private fun restartRenderLoop(cues: List<SubtitleCue>) {
        renderTick?.let { handler.removeCallbacks(it) }
        lastCues = cues
        if (cues.isNotEmpty()) {
            val eng = AnonrodeApp.get(this).engine
            if (eng.activeSyncCues.isEmpty()) {
                eng.attachSyncCues(cues)
            }
        }
        val tick = object : Runnable {
            override fun run() {
                val engine = AnonrodeApp.get(this@PlayerActivity).engine
                val p = engine.player
                positionSec = p.currentPosition / 1000f
                durationSec = (p.duration.takeIf { it > 0 } ?: 0L) / 1000f
                // v0.7.3 buffered track: read every tick, published only
                // when it actually moved (equal float writes are snapshot
                // no-ops) — the slider's thin white progress-under-fill.
                bufferedState.floatValue = p.bufferedPosition / 1000f
                // A-B repeat: snap back to A the moment B is reached. The
                // boundary scheduling below also treats B as a wake point,
                // so overshoot stays within one short tick.
                val abEnd = abEndMs
                if (abEnd != null && p.currentPosition >= abEnd) {
                    p.seekTo(abStartMs ?: 0L)
                }
                val tRaw = p.currentPosition / 1000.0
                val spd = engine.subtitleSpeedFactor.coerceAtLeast(0.5f)
                // Piecewise cut lock: the offset depends on position (each
                // segment carries its own beta). Scalar lock: one offset.
                val offsetSec = piecewiseSegments.lastOrNull { it.first <= tRaw }?.second
                    ?: (engine.subtitleOffsetMs / 1000.0)
                val t = (tRaw - offsetSec) / spd
                val cue = findCue(cues, t)
                cueText = cue?.lines?.joinToString("\n")
                // Mirror the offset ACTUALLY applied at the current position
                // into Compose state for the SYNCED chip: on cut content
                // that is the piecewise beta of the active segment, not the
                // engine's scalar offset.
                liveOffsetMs = (offsetSec * 1000).roundToLong()
                // Boundary-aware scheduling: wake exactly when the showing
                // cue ends or the next cue starts, mapped back onto the raw
                // position timeline (tRaw = t * spd + offsetSec) so drift
                // correction doesn't skew the delay. Clamped: never spin
                // faster than 8ms (touching cues), never sleep past 100ms.
                var boundarySec = cue?.end ?: nextCueAfter(cues, t)?.start
                abEnd?.let { endMs ->
                    val endSec = endMs / 1000.0
                    if (boundarySec == null || endSec < boundarySec) boundarySec = endSec
                }
                val delayMs = if (boundarySec == null) 100L else {
                    val delayRaw = (boundarySec * spd + offsetSec - tRaw) * 1000.0
                    ceil(delayRaw).toLong().coerceIn(8L, 100L)
                }
                handler.postDelayed(this, delayMs)
            }
        }
        renderTick = tick
        handler.post(tick)
    }

    /** Binary-search cue lookup with offset applied (ported from web player). */
    private fun findCue(cues: List<SubtitleCue>, t: Double): SubtitleCue? {
        var lo = 0
        var hi = cues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            when {
                t < c.start -> hi = mid - 1
                t > c.end -> lo = mid + 1
                else -> return c
            }
        }
        return null
    }

    /** First cue whose start is after [t] (insertion point over the
     *  start-sorted list); null once [t] is past the last cue. */
    private fun nextCueAfter(cues: List<SubtitleCue>, t: Double): SubtitleCue? {
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= t) lo = mid + 1 else hi = mid
        }
        return cues.getOrNull(lo)
    }

    /** Parse stored piecewise segments "startSec:betaSec;startSec:betaSec"
     *  (see SyncFinder.piecewiseToStorage) into (start, beta) pairs. */
    private fun parsePiecewise(storage: String): List<Pair<Double, Double>> =
        if (storage.isBlank()) emptyList()
        else storage.split(';').mapNotNull { seg ->
            val parts = seg.split(':')
            if (parts.size != 2) return@mapNotNull null
            val s = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val b = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            s to b
        }.sortedBy { it.first }

    /**
     * Subtitle source picker result. Persists the choice, clears the old
     * auto-sync lock (it was fitted to the PREVIOUS source's timing and
     * would misplace the new one), then reloads the video — openVideo
     * restores the saved position, re-resolves cues for the new choice,
     * and re-schedules the fingerprint job so the new source gets its own
     * lock.
     */
    private fun onSubtitleChoiceSelected(choice: String) {
        subtitlePickerOpen = false
        val uri = currentUriStr ?: return
        if (choice == subtitleChoice) return
        lifecycleScope.launch(Dispatchers.IO) {
            val app = AnonrodeApp.get(this@PlayerActivity)
            app.stateStore.updateSubtitleChoice(uri, choice)
            app.stateStore.updateAutoSync(uri, 0L, 1f, "")
            // A different cue source invalidates the previous fingerprint
            // verdict (it was fitted to a different file): clear the
            // "already checked" mark so the whole-file engine re-fits this
            // source on the next open.
            app.stateStore.clearAutoSyncChecked(uri)
            AppLog.d("SUB", "subtitle choice -> '$choice', reloading")
            withContext(Dispatchers.Main) {
                app.engine.savePositionNow()
                openVideo(uri, title)
            }
        }
    }

    /** content:// video URI → real file path (MediaStore DATA column). */
    private fun resolveVideoPath(videoUri: String): String? {
        val uri = Uri.parse(videoUri)
        if (uri.scheme == "file") return uri.path
        return try {
            contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            AppLog.e("SUB", "path resolution failed", e)
            null
        }
    }

    /**
     * v0.6.2 sub-sync UX pass: deferred sidecar parse.
     * Schedules a parse of the auto-picked sidecar ~200 ms after the
     * current playback start. When cues land, we restart the render loop
     * with them so the user sees subtitles right after first-frame.
     * Cancelled automatically by the next [openVideo] (which assigns
     * [deferredSidecarJob]).
     *
     * v0.8.1 [choice]: "" keeps the AUTO semantics (embedded-first, then
     * the scored sidecar pick); a "sidecar:…" choice that produced zero
     * cues at open (transiently — a cold SAF-tree provider, most likely)
     * is re-resolved through the SAME canonical path, honoring the user's
     * explicit pick without the embedded probe an explicit choice outranks.
     */
    private fun scheduleDeferredSidecarParse(
        uriStr: String,
        videoPath: String?,
        choice: String = "",
    ) {
        deferredSidecarJob?.cancel()
        val genAtSchedule = openGeneration
        deferredSidecarJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(200L)
                if (genAtSchedule != openGeneration) return@launch
                if (videoPath == null) return@launch
                if (choice.startsWith("sidecar:")) {
                    val chosen = try {
                        SubtitleSourceResolver.resolveCues(
                            applicationContext, uriStr, videoPath, choice,
                        ).sortedBy { it.start }
                    } catch (t: Throwable) {
                        AppLog.e("SUB", "deferred sidecar-choice retry failed", t)
                        emptyList()
                    }
                    if (genAtSchedule != openGeneration) return@launch
                    if (chosen.isEmpty()) return@launch
                    AppLog.d("PLAY", "deferred cues landed: chosen ${choice} (${chosen.size})")
                    withContext(Dispatchers.Main) {
                        if (genAtSchedule != openGeneration) return@withContext
                        restartRenderLoop(chosen)
                        // Same cues-only attach as the AUTO landing below:
                        // playback already re-anchored the clock at play();
                        // a fresh anchor here would mislabel every bin.
                        AnonrodeApp.get(this@PlayerActivity).engine
                            .attachSyncCues(chosen)
                    }
                    return@launch
                }
                // Embedded tracks outrank sidecars on the AUTO path — the
                // same priority resolveAutoCues applies. The old deferred
                // job only ever looked at sidecars, so an MKV with internal
                // subs and no sidecar showed NO subtitles in AUTO mode.
                val embeddedCues = try {
                    val tracks = SubtitleSourceResolver.listEmbedded(
                        applicationContext, uriStr, videoPath,
                    )
                    if (tracks.isEmpty()) emptyList()
                    else SubtitleSourceResolver.resolveCues(
                        applicationContext, uriStr, videoPath, "embedded:${tracks.first().index}",
                    ).sortedBy { it.start }
                } catch (t: Throwable) {
                    AppLog.e("SUB", "deferred embedded probe failed", t)
                    emptyList()
                }
                if (genAtSchedule != openGeneration) return@launch
                val cues = if (embeddedCues.isNotEmpty()) {
                    AppLog.d("PLAY", "deferred cues landed: embedded track (${embeddedCues.size})")
                    embeddedCues
                } else {
                    val sidecar = try {
                        SubtitleSourceResolver.pickAutoSidecar(applicationContext, videoPath)
                    } catch (t: Throwable) {
                        AppLog.e("SUB", "deferred pick failed", t)
                        null
                    } ?: return@launch
                    if (genAtSchedule != openGeneration) return@launch
                    // v0.8 P0-1: read through the resolver, not File(path).
                    // SAF-tree sidecars carry document URIs whose .path is
                    // not openable by java.io.File (and can be null — the
                    // old `path!!` threw NPE that this block swallowed, so
                    // the sidecar silently never rendered). parseSidecar
                    // routes file://, content:// and tree URIs alike.
                    val parsed = try {
                        SubtitleSourceResolver.parseSidecar(applicationContext, sidecar)
                            .sortedBy { it.start }
                    } catch (t: Throwable) {
                        AppLog.e("SUB", "deferred parse failed", t)
                        emptyList()
                    }
                    if (parsed.isEmpty()) return@launch
                    AppLog.d("PLAY", "deferred cues landed: ${sidecar.name} (${parsed.size})")
                    parsed
                }
                if (genAtSchedule != openGeneration) return@launch
                withContext(Dispatchers.Main) {
                    if (genAtSchedule != openGeneration) return@withContext
                    restartRenderLoop(cues)
                    // Hand the late cues to the LIVE sync engine too: the
                    // deferred path committed play() with empty cues, so
                    // AudioSyncProcessor had nothing to correlate against
                    // and the live re-lock could never fire — attach now,
                    // CUES ONLY. This must NOT re-anchor the position
                    // clock: playback has been running (and on a resume,
                    // often minutes in) since commitPlay, and an anchor of
                    // 0 here mislabeled every audio bin by the resume
                    // position — the live engine could then never lock
                    // (±40 s search window) on any resumed episode.
                    AnonrodeApp.get(this@PlayerActivity).engine
                        .attachSyncCues(cues)
                }
            } catch (e: CancellationException) {
                // Normal: a newer openVideo superseded us.
            }
        }
    }

    /**
     * True when the cues handed to commitPlay were the empty placeholder
     * the deferred-parse path uses. The cue list size + a state-store
     * lookup together identify the deferred path: when the persisted
     * subtitle choice is empty AND there are no embedded text tracks,
     * the cues are by definition the deferred placeholder.
     */
    private fun sortedCuesWasDeferred(uriStr: String, cues: List<SubtitleCue>): Boolean {
        if (cues.isNotEmpty()) return false
        // Re-read the persisted choice cheaply from the in-memory cache;
        // openVideo already wrote [subtitleChoice] before this commit.
        // v0.8.1: an explicit sidecar choice that produced NOTHING at open
        // joins the retry path too. Before v0.8 a sidecar read was a local
        // File read that either worked in ms or never would; with SAF-tree
        // sidecars the same lookup is a binder round-trip to an external
        // DocumentProvider that can fail transiently (cold provider) — the
        // deferred job retries it ~200 ms later, OFF the first-frame path,
        // instead of silently committing zero cues.
        return subtitleChoice.isEmpty() || subtitleChoice.startsWith("sidecar:")
    }

    /**
     * v0.6.2 sub-sync UX pass: callback wired from the bottom-row
     * sub-sync toggle. Persists the new state to DataStore and adjusts
     * the live re-lock gate on the AudioSyncProcessor. When toggling
     * OFF, also cancels any in-flight fingerprint jobs for this video
     * (WorkManager skips enqueues if the toggle is off — see
     * [SyncFingerprint.scheduleSuspending] — but pending jobs from
     * earlier are killed explicitly here so the user sees the change
     * immediately).
     */
    private fun onSetSubSyncEnabled(enabled: Boolean) {
        val app = AnonrodeApp.get(this)
        PlayerPrefs.saveAutoSyncEnabled(this, enabled)
        currentSettings = currentSettings.copy(subtitleAutoSyncEnabled = enabled)
        app.engine.setSubSyncEnabled(enabled)
        // v0.7.1 honest spinner: ON with no lock yet in place = the engine
        // is (or will be) correlating; OFF or a lock already applied = idle.
        subSyncRunning = enabled && liveOffsetMs == 0L
        if (enabled) {
            // ON mid-playback with cues attached: the re-armed live engine
            // starts evaluating at the next slot; a persisted lock (if
            // one lands from the background job) clears this via the Room
            // collector + onLiveSyncLocked.
            scheduleSyncNowIfCuesMissing()
        }
        lifecycleScope.launch {
            try {
                app.playerSettingsDataStore.updateData { it.copy(subtitleAutoSyncEnabled = enabled) }
            } catch (e: Exception) {
                AppLog.e("SUB", "sub-sync toggle persist failed", e)
            }
        }
        if (!enabled) {
            currentUriStr?.let { SyncFingerprint.cancel(applicationContext, it) }
        }
    }

    /**
     * v0.7.1: turning the toggle ON mid-playback should sync THIS session,
     * not just future ones.
     *
     * Two engines, two jobs here:
     *   1. Live — attach the current render-loop cues ([lastCues] holds
     *      exactly what the render loop is drawing) so the processor can
     *      correlate from the next evaluation slot. Cues ONLY: the audio
     *      position clock has been correct since play(), and re-anchoring
     *      it to 0 mid-episode is what made the live engine unable to lock
     *      after a mid-playback toggle flip.
     *   2. Background — schedule the whole-file fingerprint. It is the
     *      engine whose confidence gates were validated against real
     *      content (PC tests), and the live one only ever listens from
     *      "now" with a cheap VAD; the fingerprint is what reliably fixes
     *      an out-of-sync pair. Skipped when it already ran for this video
     *      or a lock is persisted (the job's own guards would no-op, but
     *      we avoid enqueueing pointless work).
     */
    private fun scheduleSyncNowIfCuesMissing() {
        val app = AnonrodeApp.get(this)
        val cues = lastCues
        if (cues.isNotEmpty()) {
            app.engine.attachSyncCues(cues)
            AppLog.d("SYNC", "toggle ON mid-playback: re-attached ${cues.size} cues")
        }
        scheduleFingerprintIfNeverChecked(immediate = true)
    }

    /**
     * Enqueue the whole-file fingerprint for the video being watched, unless
     * it already ran for this video (see MediaState.autoSyncCheckedAtMs) or a
     * lock is persisted. Safe to call from any mid-playback hook: the store
     * read happens on IO and a WorkManager unique-work KEEP makes repeat
     * calls cheap.
     *
     * [keepSpinner] makes the call also responsible for the "SYNCING"
     * indicator: true when the caller is handing the video over because the
     * live pass just ended (give-up) — the spinner stays lit while the
     * background pass is queued/running and drops when there is nothing left
     * to run (already checked, or a lock in place).
     */
    private fun scheduleFingerprintIfNeverChecked(keepSpinner: Boolean = false, immediate: Boolean = false) {
        val uri = currentUriStr ?: return
        val app = AnonrodeApp.get(this)
        lifecycleScope.launch(Dispatchers.IO) {
            val st = try {
                app.stateStore.get(uri)
            } catch (t: Throwable) {
                AppLog.e("SYNC", "state read before fingerprint schedule failed", t)
                null
            }
            val checked = (st?.autoSyncCheckedAtMs ?: 0L) != 0L
            val hasLock = st != null &&
                (st.autoSyncOffsetMs != 0L || st.autoSyncSpeedFactor != 1f)
            if (checked || hasLock) {
                AppLog.d("SYNC", "verdict/lock state: checked=$checked lock=$hasLock")
                if (hasLock) {
                    // v0.8 P1-7: apply whatever lock exists (live OR
                    // fingerprint — subs get corrected NOW either way) and
                    // stop burning the spinner on it.
                    handler.post {
                        if (currentSettings.subtitleAutoSyncEnabled && uri == currentUriStr) {
                            AnonrodeApp.get(this@PlayerActivity).engine
                                .applyPersistedLock(st!!.autoSyncOffsetMs, st.autoSyncSpeedFactor)
                            piecewiseSegments = parsePiecewise(st.autoSyncPiecewise)
                        }
                        subSyncRunning = false
                    }
                    if (!checked) {
                        // A LIVE (unvalidated) lock is not a verdict — the
                        // whole-file fingerprint is still owed and falls
                        // through to the schedule below; its result
                        // supersedes this value via the Room collector.
                        AppLog.d("SYNC", "pre-fingerprint lock applied; verdict still owed")
                    } else {
                        syncAwaitingBackgroundVerdict = false
                        return@launch
                    }
                } else {
                    // v0.7.4 P1-2: checked-without-lock is the engine's
                    // NEGATIVE verdict (refused fit / no usable subtitle) —
                    // nothing will ever land, stop the spinner.
                    AppLog.d("SYNC", "fingerprint already decided, not rescheduled")
                    syncAwaitingBackgroundVerdict = false
                    handler.post { subSyncRunning = false }
                    return@launch
                }
            }
            AppLog.d("SYNC", "scheduling fingerprint for current video (immediate=$immediate)")
            // v0.7.4 P1-2: we are now genuinely waiting on a background
            // verdict — arm the collector so its sentinel+checked emission
            // (a refused fit / no-usable-subtitle) stops the spinner, while
            // the very first emission for an ALREADY-checked video (nothing
            // scheduled here) stays gated off.
            syncAwaitingBackgroundVerdict = true
            if (keepSpinner) handler.post { subSyncRunning = true }
            SyncFingerprint.scheduleSuspending(
                applicationContext,
                uri,
                force = false,
                immediate = immediate,
                overrideEnabled = true,
            )
        }
    }

    /**
     * Overflow-sheet "Sync log" tile: hand the user the device's own account
     * of what the sync engines did, so "it didn't lock" becomes an evidence
     * question. Runs on the activity scope because [SyncLogShare] flushes the
     * logger and waits for its single writer thread before reading the file.
     */
    private fun shareSyncLog() {
        lifecycleScope.launch {
            try {
                SyncLogShare.shareSyncLog(this@PlayerActivity)
            } catch (t: Throwable) {
                AppLog.e("APP", "sync log share failed", t)
            }
        }
    }

    /**
     * "Resync now" (long-press on the sync toggle): run a fingerprint
     * schedule immediately, regardless of the legacy schedule gates
     * inside [openVideo]. We bypass the Policy A score/lock checks so
     * a user can force a re-fingerprint after editing a subtitle.
     */
    private fun onResyncNow() {
        val uri = currentUriStr ?: return
        val app = AnonrodeApp.get(this)
        // Force-enable live re-lock too — "resync now" implies the user
        // wants the sync engine active.
        app.engine.setSubSyncEnabled(true)
        lifecycleScope.launch {
            try {
                app.playerSettingsDataStore.updateData {
                    it.copy(subtitleAutoSyncEnabled = true)
                }
            } catch (e: Exception) {
                AppLog.e("SUB", "resync persist failed", e)
            }
            // Schedule without the score / persisted-lock gate that
            // openVideo applies — the user explicitly asked for it. force=
            // true also bypasses the JOB's own skip-if-locked guard (and
            // replaces any pending non-force job), so this really re-fits.
            SyncFingerprint.scheduleSuspending(applicationContext, uri, force = true)
        }
    }

    // ── instance-state save / restore ────────────────────────────────
    //
    // The mutableStateOf fields on the activity are NOT rememberSaveable
    // (they're owned by the activity, not the Compose tree, and several
    // mirror imperative engine state). On a configuration change that the
    // manifest doesn't intercept (e.g. uiMode / dark-mode toggle since
    // the activity only lists screenSize|orientation|...), the activity
    // is destroyed and recreated with no saveable backup — every dialog,
    // A-B repeat, queued next episode, etc. silently vanishes. This
    // Bundle round-trips the user-visible subset through that window.

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SETTINGS_OPEN, settingsOpen)
        outState.putBoolean(KEY_PIP_MODE, pipMode)
        outState.putBoolean(KEY_IS_CALIBRATING, isCalibrating)
        outState.putString(KEY_PLAYBACK_ERROR, playbackError)
        outState.putBoolean(KEY_IS_REBUILDING_DECODER, isRebuildingDecoder)
        abStartMs?.let { outState.putLong(KEY_AB_START_MS, it) }
        abEndMs?.let { outState.putLong(KEY_AB_END_MS, it) }
        outState.putInt(KEY_NEXT_COUNTDOWN_SEC, nextCountdownSec)
        pendingNext?.let {
            outState.putString(KEY_PENDING_NEXT_URI, it.uri)
            outState.putString(KEY_PENDING_NEXT_TITLE, it.title)
        }
        outState.putBoolean(KEY_HOLD_AUTO_ADVANCE_ONCE, holdAutoAdvanceOnce)
        outState.putString(KEY_SUBTITLE_CHOICE, subtitleChoice)
        outState.putLong(KEY_MANUAL_NUDGE_MS, manualNudgeMs)
        outState.putFloat(KEY_SESSION_SPEED, sessionSpeed)
        outState.putInt(KEY_SAVED_ZOOM_IDX, savedZoomIdx)
        outState.putString(KEY_CURRENT_URI_STR, currentUriStr)
        outState.putString(KEY_CURRENT_TITLE, title)
        outState.putString(KEY_CURRENT_VIDEO_PATH, currentVideoPath)
        explicitQueueUris?.let { outState.putStringArrayList(KEY_EXPLICIT_QUEUE_URIS, ArrayList(it)) }
        pendingAudioTrackIdx?.let { outState.putInt(KEY_PENDING_AUDIO_TRACK_IDX, it) }
        outState.putBoolean(KEY_SWITCHING, switching)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        settingsOpen = savedInstanceState.getBoolean(KEY_SETTINGS_OPEN, settingsOpen)
        pipMode = savedInstanceState.getBoolean(KEY_PIP_MODE, pipMode)
        isCalibrating = savedInstanceState.getBoolean(KEY_IS_CALIBRATING, isCalibrating)
        playbackError = savedInstanceState.getString(KEY_PLAYBACK_ERROR)
        isRebuildingDecoder = savedInstanceState.getBoolean(KEY_IS_REBUILDING_DECODER, isRebuildingDecoder)
        if (savedInstanceState.containsKey(KEY_AB_START_MS)) {
            abStartMs = savedInstanceState.getLong(KEY_AB_START_MS)
        }
        if (savedInstanceState.containsKey(KEY_AB_END_MS)) {
            abEndMs = savedInstanceState.getLong(KEY_AB_END_MS)
        }
        nextCountdownSec = savedInstanceState.getInt(KEY_NEXT_COUNTDOWN_SEC, nextCountdownSec)
        val pendingUri = savedInstanceState.getString(KEY_PENDING_NEXT_URI)
        val pendingTitle = savedInstanceState.getString(KEY_PENDING_NEXT_TITLE)
        if (pendingUri != null && pendingTitle != null) {
            // Video isn't Parcelable; rebuild a minimal stub holding only
            // uri + title. playNextNow / performSwitch only read those two
            // fields when invoking openVideo.
            pendingNext = Video(
                uri = pendingUri, path = "", title = pendingTitle,
                durationMs = 0L, width = 0, height = 0, sizeBytes = 0L,
                lastModifiedMs = 0L, mediaStoreId = 0L, parentPath = "",
            )
            // Bug-11: a restored countdown ≥1 with no tick posted left the
            // "Next episode in N" overlay frozen — re-arm the ticker.
            if (nextCountdownSec > 0) {
                handler.removeCallbacks(countdownTick)
                handler.postDelayed(countdownTick, 1000L)
            }
        }
        holdAutoAdvanceOnce = savedInstanceState.getBoolean(KEY_HOLD_AUTO_ADVANCE_ONCE, holdAutoAdvanceOnce)
        subtitleChoice = savedInstanceState.getString(KEY_SUBTITLE_CHOICE, subtitleChoice)
        manualNudgeMs = savedInstanceState.getLong(KEY_MANUAL_NUDGE_MS, manualNudgeMs)
        sessionSpeed = savedInstanceState.getFloat(KEY_SESSION_SPEED, sessionSpeed)
        savedZoomIdx = savedInstanceState.getInt(KEY_SAVED_ZOOM_IDX, savedZoomIdx)
        currentUriStr = savedInstanceState.getString(KEY_CURRENT_URI_STR, currentUriStr)
        currentVideoPath = savedInstanceState.getString(KEY_CURRENT_VIDEO_PATH, currentVideoPath)
        savedInstanceState.getStringArrayList(KEY_EXPLICIT_QUEUE_URIS)?.let {
            explicitQueueUris = it
        }
        if (savedInstanceState.containsKey(KEY_PENDING_AUDIO_TRACK_IDX)) {
            pendingAudioTrackIdx = savedInstanceState.getInt(KEY_PENDING_AUDIO_TRACK_IDX)
        }
        switching = savedInstanceState.getBoolean(KEY_SWITCHING, switching)
    }

    override fun onDestroy() {
        // Bug-17b: a config-change destroy (uiMode/density/locale — not
        // covered by the manifest's configChanges) is NOT the user leaving.
        // The recreated instance re-opens the saved URI immediately; tearing
        // the engine and foreground service down here would audibly gap
        // playback and flash the notification away on every dark-mode toggle
        // or locale change. Only a real finish (back button, system kill of
        // the task) stops the engine.
        if (!isFinishing) {
            castRouteCallback?.let { mediaRouter.removeCallback(it) }
            castRouteCallback = null
            super.onDestroy()
            return
        }
        handler.removeCallbacksAndMessages(null)
        renderTick = null
        pendingNext = null
        val engine = AnonrodeApp.get(this).engine
        engine.removeListener(playerEventListener)
        // v0.8 P2-4: the user is gone — a still-pending 10-min whole-file
        // fingerprint decode for THIS video must not keep running (and
        // backoff-retrying) behind the next session. Cancel is idempotent
        // for videos with no job. (The documented-but-never-wired hook.)
        currentUriStr?.let { engine.cancelSyncFingerprintJob(it) }
        // Stop the app-scoped engine: finishing the activity (back button)
        // must not leave audio playing with no UI. stopAndSave persists
        // the position first, then stops the player.
        engine.stopAndSave()
        // The engine is stopped, so the foreground playback service
        // (media session, notification, autosave) has nothing left to
        // host — tear it down with the activity.
        stopService(Intent(this, PlayerService::class.java))
        // Release the audio effect on activity destroy so the native
        // equalizer instance doesn't outlive the screen.
        equalizer.release()
        // Drop the MediaRouter callback so the framework doesn't keep a
        // strong ref to the (now-dying) activity through its selector.
        castRouteCallback?.let { mediaRouter.removeCallback(it) }
        castRouteCallback = null
        super.onDestroy()
    }
}
