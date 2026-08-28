package dev.anonrode.player

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import dev.anonrode.player.core.database.MediaDatabase
import dev.anonrode.player.core.media.library.MediaScanner
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.feature.player.PlaybackEngine
import dev.anonrode.player.feature.player.PlayerServiceHolder

/** Manual DI container — replaced by the UI redesign later if wanted. */
class AnonrodeApp : Application() {

    lateinit var scanner: MediaScanner
        private set
    lateinit var stateStore: MediaStateStore
        private set
    lateinit var engine: PlaybackEngine
        private set

    /** True when [onCreate] init threw; MainActivity shows the crash
     *  report dialog instead of touching the half-built DI container. */
    var startupBroken = false
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // First thing in the process — before ContentProviders (WorkManager's
        // androidx.startup init) run — so even the earliest crash is captured.
        CrashReporter.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        try {
            initApp()
        } catch (t: Throwable) {
            // Don't die silently: record the failure and let MainActivity
            // show the report dialog (startupBroken gates off the DI refs).
            startupBroken = true
            try {
                CrashReporter.writeReport(this, "main", t)
            } catch (_: Throwable) {
            }
        }
    }

    private fun initApp() {
        AppLog.init(this)
        AppLog.d("APP", "app starting, sdk=" + android.os.Build.VERSION.SDK_INT)
        // Coil: register the video-frame decoder so library thumbnails
        // (PosterArt) render real frames from content:// video URIs.
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(VideoFrameDecoder.Factory()) }
                .build()
        }
        scanner = MediaScanner(this)
        stateStore = MediaStateStore(MediaDatabase.get(this).mediaStateDao())

        engine = PlaybackEngine(
            context = this,
            positionRestore = { uri ->
                stateStore.get(uri)?.playbackPositionMs
            },
            onPositionSave = { uri, pos, dur, finished ->
                AppLog.d("PLAYER", "save pos=" + pos + "ms dur=" + dur + " finished=" + finished)
                stateStore.updatePosition(uri, pos, dur, finished)
            },
            onAutoSyncSave = { uri, offsetMs, speedF ->
                AppLog.d("SYNC", "persist auto offset=" + offsetMs + "ms speed=" + speedF)
                // One statement, and clears stale piecewise segments: a live
                // scalar lock supersedes any fitted cut-map (whose betas were
                // relative to the OLD alpha/beta). Leaving it would make the
                // row self-inconsistent.
                stateStore.updateAutoSync(uri, offsetMs, speedF, "")
            },
        )
        PlayerServiceHolder.engine = engine
        PlayerServiceHolder.stateStore = stateStore
    }

    companion object {
        fun get(context: Context): AnonrodeApp = context.applicationContext as AnonrodeApp
    }
}
