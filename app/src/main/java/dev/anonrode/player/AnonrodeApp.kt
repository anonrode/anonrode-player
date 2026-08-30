package dev.anonrode.player

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import dev.anonrode.player.core.database.MediaDatabase
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import dev.anonrode.player.core.media.library.MediaScanner
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.feature.player.PlaybackEngine
import dev.anonrode.player.feature.player.PlayerServiceHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Manual DI container — replaced by the UI redesign later if wanted. */
class AnonrodeApp : Application() {

    lateinit var scanner: MediaScanner
        private set
    lateinit var stateStore: MediaStateStore
        private set
    lateinit var engine: PlaybackEngine
        private set

    /**
     * Process-scoped background scope for fire-and-forget startup work
     * (DataStore pre-warm, etc.) that must not block the main thread but
     * also must not be cancelled by an activity tear-down.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** True when [onCreate] init threw; MainActivity shows the crash
     *  report dialog instead of touching the half-built DI container. */
    var startupBroken = false
        private set

    /**
     * True once [initApp] completed fully. Checked from MainActivity before
     * touching the DI refs — belt-and-braces next to [startupBroken] for a
     * half-initialized container. (isInitialized is only legal on lateinit
     * properties from inside their declaring class, hence this accessor.)
     */
    val isReady: Boolean
        get() = ::scanner.isInitialized &&
            ::stateStore.isInitialized &&
            ::engine.isInitialized

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
        // v0.6.2 incremental pass: seed the in-memory cache from the disk
        // snapshot BEFORE setContent runs. This is synchronous (a JSON read
        // of ~100KB on the calling thread) and is the entire reason the
        // first frame of MainActivity already shows the library rather than
        // a blank grid waiting for the MediaStore query to finish. The
        // MediaStore scan still happens in the background via
        // scanner.observeLibrary(); the disk snapshot is just the head start.
        scanner.loadFromDisk()
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

        // Pre-warm the PlayerSettings DataStore so PlayerActivity.onCreate's
        // `data.first()` call (keepScreenOn, autoAdvance) hits the in-memory
        // cache instead of paying the first-time JSON deserialize on Main.
        // Failure here is harmless — the next call will retry.
        appScope.launch {
            try {
                playerSettingsDataStore.data.first()
                AppLog.d("APP", "playerSettingsDataStore pre-warm ok")
            } catch (t: Throwable) {
                AppLog.e("APP", "playerSettingsDataStore pre-warm failed", t)
            }
        }
    }

    companion object {
        fun get(context: Context): AnonrodeApp = context.applicationContext as AnonrodeApp
    }
}
