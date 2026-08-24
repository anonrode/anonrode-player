package dev.anonrode.player

import android.app.PictureInPictureParams
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.media.subtitle.SubtitleParser
import dev.anonrode.player.core.model.SubtitleCue
import dev.anonrode.player.core.model.Video
import dev.anonrode.player.core.ui.theme.AnonrodeTheme
import dev.anonrode.player.ui.PlayerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the PlayerScreen. Playback wiring: restore resume position, apply
 * persisted auto-sync offset + manual delay (additive), resolve sidecar
 * subtitles, and drive the subtitle render loop (binary search + offset).
 * State fields are Compose-backed so only affected UI recomposes.
 *
 * Picture-in-Picture: [onUserLeaveHint] auto-enters PiP (16:9) when the user
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
        private val SUB_EXTS = listOf("srt", "vtt", "ass", "ssa")
        private const val NEXT_COUNTDOWN_SEC = 5
    }

    private val handler = Handler(Looper.getMainLooper())

    private var title by mutableStateOf("")
    private var cueText by mutableStateOf<String?>(null)
    private var positionSec by mutableFloatStateOf(0f)
    private var durationSec by mutableFloatStateOf(0f)
    private var syncSpeedFactor by mutableFloatStateOf(1f)

    /** Playback speed applied to the current video (drives the speed button). */
    private var restoredSpeed by mutableFloatStateOf(1f)

    /** URI of the media the engine is playing (speed persistence target). */
    private var currentUriStr: String? = null

    /** True while the activity renders inside the system PiP window. */
    private var pipMode by mutableStateOf(false)

    /** Speed last applied this session; fallback when an episode has no saved value. */
    private var sessionSpeed = 1f

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

    private val playerEventListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED && !switching) onEpisodeEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // User resumed playback mid-countdown → stay on this episode.
            if (isPlaying && !switching && pendingNext != null) cancelNextCountdown()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) updatePipAutoEnter(isPlaying)
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
        val app = AnonrodeApp.get(this)
        val engine = app.engine

        engine.player.addListener(playerEventListener)

        setContent {
            AnonrodeTheme {
                PlayerScreen(
                    player = engine.player,
                    title = title,
                    cueText = cueText,
                    positionSec = positionSec,
                    durationSec = durationSec,
                    onBack = { finish() },
                    initialSpeed = restoredSpeed,
                    onSpeedChanged = { speed ->
                        // Persist per-video playback speed (Room, media_state).
                        sessionSpeed = speed
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
                )
            }
        }

        openVideo(uriStr, title)
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
        currentUriStr = uriStr

        // Fresh UI state for the new media item.
        cueText = null
        positionSec = 0f
        durationSec = 0f
        title = displayTitle

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppLog.d("PLAY", "opening " + uriStr)
                val state = app.stateStore.get(uriStr)
                val subFile = findSidecarSubtitle(Uri.parse(uriStr))
                AppLog.d("PLAY", "sidecar subtitle: " + (subFile?.first ?: "NONE"))
                val parsed = subFile
                    ?.let { (name, text) -> SubtitleParser.parse(name, text) }
                    ?: emptyList()
                AppLog.d("PLAY", "parsed " + parsed.size + " cues")
                // findCue binary-searches by start time, so cues must be ordered.
                val sortedCues = parsed.sortedBy { it.start }
                val manual = state?.subtitleDelayMs ?: 0L
                val auto = state?.autoSyncOffsetMs ?: 0L

                // Speed persistence: apply this video's saved speed; fall back
                // to the last speed used this session so binge sessions keep
                // momentum when an episode was never individually set.
                val speed = app.stateStore.savedPlaybackSpeed(uriStr) ?: sessionSpeed
                sessionSpeed = speed

                // Episode queue: every MediaStore video sharing this folder,
                // sorted by season/episode number, index resolved to uriStr.
                val queue = EpisodeQueue.build(app.scanner, uriStr)

                withContext(Dispatchers.Main) {
                    restoredSpeed = speed
                    queue?.current?.title?.let { title = it }
                    val player = engine.player
                    player.setPlaybackSpeed(speed)
                    engine.play(MediaItem.fromUri(uriStr), uriStr, sortedCues, manual, auto)
                    // Fully-watched episodes restart from the top instead of
                    // resuming at the final frame; the resulting seek
                    // discontinuity re-anchors the sync processor.
                    if (state?.finished == true) player.seekTo(0)
                    restartRenderLoop(sortedCues)
                    episodeQueue = queue
                    switching = false
                }
            } catch (e: Exception) {
                switching = false
                AppLog.e("PLAY", "FAILED to start playback", e)
            }
        }
    }

    /** Enter Picture-in-Picture with a fixed 16:9 aspect ratio (API 26+). */
    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
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
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(playing)
                    .build()
            )
        } catch (e: Exception) {
            AppLog.e("PIP", "param update failed", e)
        }
    }

    private fun restartRenderLoop(cues: List<SubtitleCue>) {
        renderTick?.let { handler.removeCallbacks(it) }
        val tick = object : Runnable {
            override fun run() {
                val engine = AnonrodeApp.get(this@PlayerActivity).engine
                val p = engine.player
                positionSec = p.currentPosition / 1000f
                durationSec = (p.duration.takeIf { it > 0 } ?: 0L) / 1000f
                val tRaw = p.currentPosition / 1000.0
                val spd = engine.subtitleSpeedFactor.coerceAtLeast(0.5f)
                val t = (tRaw - engine.subtitleOffsetMs / 1000.0) / spd
                cueText = findCue(cues, t)?.lines?.joinToString("\n")
                handler.postDelayed(this, 100)
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

    /**
     * Subtitle resolution: tries exact name match first, then ANY supported
     * subtitle file in the same directory (MediaStore.Files query).
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
        engine.player.removeListener(playerEventListener)
        engine.savePositionNow()
        super.onDestroy()
    }
}
