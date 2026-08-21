package dev.anonrode.player.feature.player

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.common.util.UnstableApi
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val engine = PlayerServiceHolder.engine
        val store = PlayerServiceHolder.stateStore
        mediaSession = MediaSession.Builder(this, engine.player)
            .build()

        // Periodic position persistence — a process kill loses at most 5s.
        saveJob = scope.launch {
            while (isActive) {
                delay(5000)
                if (engine.player.playWhenReady) {
                    engine.savePositionNow()
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = PlayerServiceHolder.engine.player
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == ExoPlayer.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveJob?.cancel()
        PlayerServiceHolder.engine.savePositionNow()
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
 * Replaced by proper DI later.
 */
object PlayerServiceHolder {
    lateinit var engine: PlaybackEngine
    lateinit var stateStore: MediaStateStore
}
