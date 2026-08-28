package dev.anonrode.player

import android.app.PictureInPictureParams
import android.content.ContentUris
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
import android.widget.Toast
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
        private val SUB_EXTS = listOf("srt", "vtt", "ass", "ssa")
        private const val NEXT_COUNTDOWN_SEC = 5
        // Selector used for both the activity-level route-name observer
        // and the picker composable. Covers Cast, Bluetooth, HDMI, Miracast.
        private val MediaRouteSelectorLite = androidx.mediarouter.media.MediaRouteSelector.Builder()
            .addControlCategory(androidx.mediarouter.media.MediaControlIntent.CATEGORY_LIVE_AUDIO)
            .addControlCategory(androidx.mediarouter.media.MediaControlIntent.CATEGORY_LIVE_VIDEO)
            .addControlCategory(androidx.mediarouter.media.MediaControlIntent.CATEGORY_REMOTE_PLAYBACK)
            .build()
    }

    private val handler = Handler(Looper.getMainLooper())

    private var title by mutableStateOf("")
    private var cueText by mutableStateOf<String?>(null)
    private var positionSec by mutableFloatStateOf(0f)
    private var durationSec by mutableFloatStateOf(0f)

    /** Playback speed applied to the current video (drives the speed button). */
    private var restoredSpeed by mutableFloatStateOf(1f)

    /** URI of the media the engine is playing (speed persistence target). */
    private var currentUriStr: String? = null

    /** In-flight [openVideo] coroutine; cancelled when a newer open starts. */
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

    /** True while the in-player settings screen is on top of the player. */
    private var settingsOpen by mutableStateOf(false)

    /** True while the in-player calibration banner is showing. */
    private var isCalibrating by mutableStateOf(false)

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

    /** Cumulative manual nudge in ms (persisted in Room). */
    private var manualNudgeMs by mutableStateOf(0L)

    /** Latest [PlayerSettings] snapshot for imperative (non-Compose) paths:
     *  resume behavior, auto-sync gate, background-playback gate. The
     *  Compose tree collects its own live snapshot at the call site. */
    @Volatile
    private var currentSettings: PlayerSettings = PlayerSettings()

    /** Everything [commitPlay] needs, stashed while the resume prompt is up. */
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

        override fun onTracksChanged(tracks: Tracks) {
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

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val uriStr = intent.getStringExtra(EXTRA_URI)
        if (uriStr == null) {
            finish()
            return
        }
        title = intent.getStringExtra(EXTRA_TITLE) ?: uriStr
        explicitQueueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS)
        val app = AnonrodeApp.get(this)
        val engine = app.engine

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
            }
        }

        // MediaRouter is an application service; grab it once and hold a
        // reference for the activity's lifetime. The picker composable
        // subscribes to its callback while visible; we release on destroy.
        mediaRouter = getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
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
                            positionSec = positionSec,
                            durationSec = durationSec,
                            onBack = { finish() },
                            initialSpeed = restoredSpeed,
                            onSpeedChanged = { speed ->
                                // Persist per-video playback speed (Room, media_state)
                                // plus the global play_speed preference.
                                sessionSpeed = speed
                                PlayerPrefs.saveGlobalSpeed(this@PlayerActivity, speed)
                                val targetUri = currentUriStr
                                if (targetUri != null) {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        app.stateStore.updatePlaybackSpeed(targetUri, speed)
                                        AppLog.d("SPEED", "persisted $speed for $targetUri")
                                    }
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
                            upNextTitle = episodeQueue?.next()
                                ?.let { it.title.substringAfterLast('/').substringBeforeLast('.') },
                            onOpenSettings = { settingsOpen = true },
                            liveOffsetMs = liveOffsetMs,
                            isCalibrating = isCalibrating,
                            onStartCalibration = {
                                isCalibrating = true
                                AppLog.d("PLAYER", "calibration started")
                                // Auto-clear the banner after ~4.3s to mirror
                                // the mockup's animation. Real sync work is
                                // driven by the engine, not the UI.
                                handler.postDelayed({
                                    isCalibrating = false
                                    AppLog.d("PLAYER", "calibration done")
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
                            castRouteName = castRouteName,
                            subtitleStyle = subStyle,
                            onSubtitleStyleChanged = { applySubtitleStyle(it) },
                            seekIncrementSec = settings.seekIncrementSec,
                            doubleTapSeekEnabled = settings.doubleTapSeek,
                            swipeToSeekEnabled = settings.swipeToSeek,
                            volumeGestureEnabled = settings.volumeGesture,
                            brightnessGestureEnabled = settings.brightnessGesture,
                            autoHideControlsMs = settings.autoHideControlsMs,
                            initialSleepTimerMinutes = settings.sleepTimerMinutes,
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
        // Supersede any in-flight open: cancel its coroutine and bump the
        // generation guard so work already past its last suspension point
        // aborts before touching the engine, shared state, or the queue.
        openJob?.cancel()
        openGeneration++
        val gen = openGeneration
        currentUriStr = uriStr

        // Fresh UI state for the new media item.
        cueText = null
        positionSec = 0f
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
                val zoomIdx = ((state?.videoScale ?: 1f) - 1f).toInt().coerceIn(0, 2)
                // Subtitle source: the picker's persisted choice wins;
                // empty choice = legacy auto-pick of the best sidecar.
                val choice = state?.subtitleChoice.orEmpty()
                val videoPath = resolveVideoPath(uriStr)
                currentVideoPath = videoPath
                val parsed = SubtitleSourceResolver.resolveCues(
                    applicationContext, uriStr, videoPath, choice,
                )
                AppLog.d(
                    "PLAY",
                    "subtitle choice='" + choice.ifEmpty { "auto" } +
                        "' parsed " + parsed.size + " cues"
                )
                // findCue binary-searches by start time, so cues must be ordered.
                val sortedCues = parsed.sortedBy { it.start }
                val manual = state?.subtitleDelayMs ?: 0L
                val auto = state?.autoSyncOffsetMs ?: 0L
                val autoSpeed = state?.autoSyncSpeedFactor ?: 1f
                // Generation guard: a newer openVideo supersedes this one.
                if (gen != openGeneration) return@launch
                piecewiseSegments = parsePiecewise(state?.autoSyncPiecewise ?: "")

                // Background fingerprint: produce the persisted (alpha, beta)
                // lock used on the NEXT play. The job dedupes by uri and
                // skips internally when a lock already exists, so only
                // schedule when there is nothing stored yet. Gated by the
                // auto-sync setting: off means the user wants raw timing.
                if (currentSettings.autoSyncEnabled &&
                    choice != "none" && parsed.isNotEmpty() && auto == 0L && autoSpeed == 1f) {
                    SyncFingerprint.schedule(applicationContext, uriStr)
                }

                withContext(Dispatchers.Main) {
                    manualNudgeMs = manual
                    subtitleChoice = choice
                }

                // Speed persistence: apply this video's saved speed, then the
                // global play_speed preference, then the session speed.
                // (this@PlayerActivity: inside launch{} `this` is the scope)
                val speed = app.stateStore.savedPlaybackSpeed(uriStr)
                    ?: PlayerPrefs.globalSpeed(this@PlayerActivity)
                    ?: sessionSpeed
                if (gen != openGeneration) return@launch
                sessionSpeed = speed

                // Episode queue: an explicit multi-select list (library) wins;
                // otherwise every MediaStore video sharing this folder, sorted
                // by season/episode number, index resolved to uriStr.
                val queueUris = explicitQueueUris
                val queue = if (queueUris != null && queueUris.size > 1) {
                    EpisodeQueue.fromExplicitUris(app.scanner, queueUris, uriStr)
                        ?: EpisodeQueue.build(app.scanner, uriStr)
                } else {
                    EpisodeQueue.build(app.scanner, uriStr)
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
                    Toast.makeText(this@PlayerActivity, "Failed to play video", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Final step of [openVideo] — directly, or once the user answers the
     * resume prompt. Applies the speed, starts the engine (restoring the
     * persisted audio track), and launches the subtitle render loop.
     */
    private fun commitPlay(pending: PendingPlay, resume: Boolean) {
        val app = AnonrodeApp.get(this)
        val engine = app.engine
        resumePromptMs = null
        pendingAudioTrackIdx = pending.audioTrackIdx
        lifecycleScope.launch {
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
            engine.play(
                MediaItem.fromUri(pending.uriStr), pending.uriStr, pending.cues,
                pending.manual, pending.auto, pending.autoSpeed,
                resume = resume,
                syncEnabled = currentSettings.autoSyncEnabled,
            )
            // Fully-watched episodes restart from the top instead of
            // resuming at the final frame; the resulting seek
            // discontinuity re-anchors the sync processor.
            if (pending.finished) player.seekTo(0)
            restartRenderLoop(pending.cues)
            episodeQueue = pending.queue
            switching = false
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
     * PiP aspect ratio from the real video size, clamped to Android's
     * allowed [0.418, 2.39] range. Player videoSize first, MediaStore
     * metadata for [currentUriStr] second; 16:9 fallback when unknown.
     */
    private fun pipAspect(): Rational {
        val size = AnonrodeApp.get(this).engine.player.videoSize
        var w = size.width
        var h = size.height
        if (w <= 0 || h <= 0) {
            // Player hasn't reported a size yet — try MediaStore metadata.
            currentUriStr?.let { uriStr ->
                try {
                    contentResolver.query(
                        Uri.parse(uriStr),
                        arrayOf(android.provider.MediaStore.Video.Media.WIDTH,
                            android.provider.MediaStore.Video.Media.HEIGHT),
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
            }
        }
        if (w <= 0 || h <= 0) return Rational(16, 9)
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
        // Persist the current speed so the rebuilt player comes up at the
        // same rate the user picked. sessionSpeed is the live value (the
        // speed pill updates it on every change); restoredSpeed is frozen
        // at open time and would roll back mid-session speed changes.
        engine.pendingSpeedOnRebuild = sessionSpeed.takeIf { it > 0f } ?: 1f
        isRebuildingDecoder = true
        try {
            val newSessionId = engine.rebuild(newHw)
            AppLog.d("PLAYER", "decoder rebuild complete: hw=" + newHw + " session=" + newSessionId)
            // Re-bind the Equalizer to the rebuilt player's session id. We
            // use the returned id (which may be 0 if the new player hasn't
            // attached a session yet) and re-apply on the next state-ready
            // tick via the same hook the PlayerService uses.
            equalizer.setSessionId(newSessionId)
            // Mirror the prior enabled state — the user expects "EQ on" to
            // stay on after a decoder swap, not silently flip off.
            if (equalizerOn) equalizer.setEnabled(true)
            return newSessionId
        } catch (e: Exception) {
            AppLog.e("PLAYER", "decoder rebuild FAILED", e)
            return 0
        } finally {
            // Fallback only: STATE_READY in [playerEventListener] normally
            // clears the flag sooner; this bounds the never-ready case.
            handler.postDelayed({ isRebuildingDecoder = false }, 800L)
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
        val tick = object : Runnable {
            override fun run() {
                val engine = AnonrodeApp.get(this@PlayerActivity).engine
                val p = engine.player
                positionSec = p.currentPosition / 1000f
                durationSec = (p.duration.takeIf { it > 0 } ?: 0L) / 1000f
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
     * Subtitle resolution: tries exact name match first, then ANY supported
     * subtitle file in the same directory (MediaStore.Files query).
     * LEGACY: superseded by SubtitleSourceResolver (kept for reference).
     */
    private fun findSidecarSubtitle(videoUri: Uri): Pair<String, String>? = try {
        val videoPath = contentResolver.query(
            videoUri, arrayOf(android.provider.MediaStore.Video.Media.DATA), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null

        AppLog.d("SUB", "video path: $videoPath")
        val parentDir = videoPath.substringBeforeLast('/')
        val base = videoPath.substringAfterLast('/').substringBeforeLast('.')
        val filesUri = android.provider.MediaStore.Files.getContentUri("external")

        // Collect all candidate subtitle files in the same tree
        data class Candidate(val uri: Uri, val name: String, val path: String)

        val candidates = mutableListOf<Candidate>()
        // Filter at the query level: never pull the whole Files table.
        val selection = buildString {
            SUB_EXTS.forEachIndexed { i, ext ->
                if (i > 0) append(" OR ")
                append(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
                    .append(" LIKE '%.").append(ext).append("'")
            }
        }
        contentResolver.query(
            filesUri,
            arrayOf(android.provider.MediaStore.Files.FileColumns._ID,
                android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME,
                android.provider.MediaStore.Files.FileColumns.DATA),
            selection, null, null
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DISPLAY_NAME)
            val dataCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Files.FileColumns.DATA)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in SUB_EXTS) continue
                val path = c.getString(dataCol) ?: continue
                // Must be in same directory as the video
                if (path.substringBeforeLast('/') != parentDir) continue
                candidates.add(Candidate(
                    uri = ContentUris.withAppendedId(filesUri, c.getLong(idCol)),
                    name = name, path = path
                ))
            }
        }

        AppLog.d("SUB", "found ${candidates.size} subtitle files in $parentDir")
        if (candidates.isEmpty()) return null

        val chosen = candidates.firstOrNull { it.name.substringBeforeLast('.').equals(base, ignoreCase = true) }
            ?: candidates.firstOrNull { it.name.substringBeforeLast('.').startsWith(base, ignoreCase = true) }
            ?: candidates.firstOrNull()

        if (chosen == null) return null
        AppLog.d("SUB", "chosen: " + chosen.name)
        val text = contentResolver.openInputStream(chosen.uri)
            ?.bufferedReader()?.use { it.readText() }
        if (text.isNullOrEmpty()) null else chosen.name to text
    } catch (e: Exception) {
        AppLog.e("SUB", "subtitle resolution failed", e)
        null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        renderTick = null
        pendingNext = null
        val engine = AnonrodeApp.get(this).engine
        engine.removeListener(playerEventListener)
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
