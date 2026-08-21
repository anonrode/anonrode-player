package dev.anonrode.player

import android.app.Application
import android.content.Context
import dev.anonrode.player.core.database.MediaDatabase
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import dev.anonrode.player.core.media.library.MediaScanner
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.feature.player.PlaybackEngine
import dev.anonrode.player.feature.player.PlayerServiceHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

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
        scanner = MediaScanner(this)
        stateStore = MediaStateStore(MediaDatabase.get(this).mediaStateDao())

        val settingsFlow = playerSettingsDataStore.data
        engine = PlaybackEngine(
            context = this,
            settingsProvider = { runBlocking { settingsFlow.first() } },
            positionRestore = { uri ->
                stateStore.get(uri)?.playbackPositionMs
            },
            onPositionSave = { uri, pos, dur, finished ->
                stateStore.updatePosition(uri, pos, dur, finished)
            },
            onAutoSyncSave = { uri, offsetMs ->
                stateStore.updateAutoSyncOffset(uri, offsetMs)
            },
        )
        PlayerServiceHolder.engine = engine
        PlayerServiceHolder.stateStore = stateStore
    }

    companion object {
        fun get(context: Context): AnonrodeApp = context.applicationContext as AnonrodeApp
    }
}
