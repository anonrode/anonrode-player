package dev.anonrode.player.feature.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.media.state.MediaStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground playback service. Media3 manages the notification + media
 * session; we own the periodic position autosave (every 5s) and the
 * end-of-media completion sentinel.
 */
@UnstableApi
class PlayerService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var saveJob: Job? = null

    /**
     * Main dispatcher: the autosave loop reads ExoPlayer state, and Media3
     * only permits player access on the player's owning (main) thread.
     * Store writes hop to [Dispatchers.IO] inside
     * [PlaybackEngine.persistPositionNow].
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        // The system can restart this exported service after process death,
        // before AnonrodeApp.onCreate has re-wired [PlayerServiceHolder].
        // Degrade gracefully (no session, no autosave) instead of crashing.
        val engine = PlayerServiceHolder.engine
        if (engine == null || PlayerServiceHolder.stateStore == null) {
            AppLog.e("SERVICE", "holder not wired yet (service restarted before app init); skipping setup")
            return
        }

        mediaSession = MediaSession.Builder(this, engine.player)
            .build()

        // Periodic position persistence — a process kill loses at most 5s.
        saveJob = saveScope.launch {
            while (isActive) {
                delay(5000)
                // Re-check each tick: wiring can appear/disappear across
                // process restarts.
                val e = PlayerServiceHolder.engine ?: continue
                if (!e.player.playWhenReady) continue
                e.persistPositionNow() // player reads on Main here; DB write on IO
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val engine = PlayerServiceHolder.engine
        if (engine == null) {
            stopSelf()
            return
        }
        val player = engine.player
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == ExoPlayer.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveJob?.cancel()
        // Null-safe: onDestroy can fire even when onCreate bailed early on
        // missing holder wiring.
        PlayerServiceHolder.engine?.savePositionNow()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}

/**
 * Simple holder so the app can wire the engine before the service starts.
 * Nullable (not lateinit): Android may recreate this exported service after
 * process death before [dev.anonrode.player.AnonrodeApp.onCreate] runs, so
 * every access site must tolerate missing wiring instead of crashing on an
 * uninitialized property. Replaced by proper DI later.
 */
object PlayerServiceHolder {
    var engine: PlaybackEngine? = null
    var stateStore: MediaStateStore? = null
}
