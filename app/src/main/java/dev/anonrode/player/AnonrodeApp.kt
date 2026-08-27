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

    override fun onCreate() {
        super.onCreate()
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
