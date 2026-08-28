package dev.anonrode.player.feature.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaNotificationManager
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
    // Set when onCreate runs before the app has wired PlayerServiceHolder
    // (system restarted the service ahead of AnonrodeApp.onCreate). The
    // service still MUST announce foreground in onStartCommand — Android
    // kills the whole app otherwise — and then stops itself cleanly.
    private var degraded = false
    private var foregroundAnnounced = false
    // Kept so onDestroy can unregister the rebuild hook; a service restart
    // would otherwise stack stale hooks building sessions against a dead
    // service context.
    private var rebuiltHook: ((ExoPlayer) -> Unit)? = null

    /**
     * Main dispatcher: the autosave loop reads ExoPlayer state, and Media3
     * only permits player access on the player's owning (main) thread.
     * Store writes hop to [Dispatchers.IO] inside
     * [PlaybackEngine.persistPositionNow].
     */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        // The system can restart this exported service after process death,
        // before AnonrodeApp.onCreate has re-wired [PlayerServiceHolder].
        // Degrade gracefully (no session, no autosave) instead of crashing.
        val engine = PlayerServiceHolder.engine
        if (engine == null || PlayerServiceHolder.stateStore == null) {
            AppLog.e("SERVICE", "holder not wired yet (service restarted before app init); skipping setup")
            degraded = true
            return
        }

        // Track which player the live media session is bound to so onDestroy
        // doesn't try to release an already-released instance after a rebuild.
        mediaSession = MediaSession.Builder(this, engine.player)
            .build()

        // After a decoder swap the engine creates a fresh ExoPlayer; the
        // MediaSession is bound to the old instance, so rebuild it around
        // the new player to keep the notification + media controls live.
        val hook: (ExoPlayer) -> Unit = { newPlayer ->
            AppLog.d("SERVICE", "player rebuilt — recreating MediaSession")
            mediaSession?.run {
                // The old player was already released by PlaybackEngine.rebuild;
                // release only the session itself.
                release()
            }
            mediaSession = MediaSession.Builder(this, newPlayer).build()
        }
        rebuiltHook = hook
        engine.addRebuiltHook(hook)

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

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() gives us ~5s to call startForeground() or
        // Android kills the WHOLE app (ForegroundServiceDidNotStartInTimeException).
        // Media3's MediaSessionService only promotes us to foreground once the
        // player is non-idle and its notification is ready — that races the
        // deadline on slow devices. Bridge the gap with a minimal placeholder
        // under Media3's own notification id; the session's notification
        // manager replaces it the moment real playback state arrives.
        ensureForeground()
        if (degraded) {
            // Nothing can play in this state; shut down cleanly (still
            // foreground-announced, so no crash on the way out).
            stopSelf()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
                )
            }
        } catch (e: Exception) {
            AppLog.e("SERVICE", "notification channel create failed", e)
        }
    }

    private fun ensureForeground() {
        if (foregroundAnnounced) return
        try {
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Anonrode Player")
                .setOngoing(true)
            startForeground(MediaNotificationManager.DEFAULT_NOTIFICATION_ID, builder.build())
            foregroundAnnounced = true
        } catch (e: Exception) {
            AppLog.e("SERVICE", "startForeground failed", e)
        }
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val engine = PlayerServiceHolder.engine
        if (engine == null) {
            stopSelf()
            return
        }
        val player = engine.player
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == ExoPlayer.STATE_ENDED) {
            // The user swiped the app away: persist progress now so a process
            // kill can't lose everything since the last 5s autosave tick.
            engine.savePositionNow()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveJob?.cancel()
        // Unregister the rebuild hook so a restarted service doesn't stack
        // stale hooks. Null-safe: onDestroy can fire even when onCreate
        // bailed early on missing holder wiring.
        rebuiltHook?.let { PlayerServiceHolder.engine?.removeRebuiltHook(it) }
        rebuiltHook = null
        PlayerServiceHolder.engine?.savePositionNow()
        // Best-effort release. If the media session's player was already torn
        // down by PlaybackEngine.rebuild, release() can throw — never let
        // service teardown crash on the way out.
        try {
            mediaSession?.release()
        } catch (e: Exception) {
            AppLog.e("SERVICE", "media session release failed", e)
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

private const val CHANNEL_ID = "anonrode_playback"
