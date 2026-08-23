package dev.anonrode.player.feature.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.anonrode.player.core.datastore.PlayerSettings
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.media.sync.AudioSyncProcessor
import dev.anonrode.player.core.media.sync.SyncListener
import dev.anonrode.player.core.model.SubtitleCue
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer instance: nextlib FFmpeg renderers (open class) with a
 * buildAudioSink override that injects the auto-sync [AudioSyncProcessor]
 * into the audio pipeline, decoder fallback, audio-focus handling, resume
 * restore/save, and per-episode offset persistence.
 *
 * Offset semantics (validated): applied offset = persisted auto lock +
 * manual delay (additive). The live engine re-listens on every playback and
 * refines the persisted auto offset.
 */
@UnstableApi
class PlaybackEngine(
    context: Context,
    private val settingsProvider: () -> PlayerSettings,
    private val positionRestore: suspend (String) -> Long?,
    private val onPositionSave: suspend (uri: String, positionMs: Long, durationMs: Long?, finished: Boolean) -> Unit,
    private val onAutoSyncSave: suspend (uri: String, offsetMs: Long) -> Unit = { _, _ -> },
) : SyncListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentUri: String? = null
    private var manualDelayMs: Long = 0L

    /** Applied subtitle offset in ms: persisted auto lock + manual delay. */
    var subtitleOffsetMs: Long = 0L
        private set
    var subtitleSpeedFactor: Float = 1f
        private set

    private val syncProcessor = AudioSyncProcessor(this)

    val player: ExoPlayer = buildPlayer(context)

    private fun buildPlayer(context: Context): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context)
        val renderersFactory = object : NextRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(syncProcessor))
                    .build()
        }
        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .also {
                it.setHandleAudioBecomingNoisy(true)
                // Seek re-anchor: the sync processor's sample-count clock must
                // be re-set from the MAIN thread on every discontinuity.
                it.addListener(object : Player.Listener {
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        syncProcessor.setStartPosition(newPosition.positionMs)
                    }
                })
            }
    }

    /** Point the sync engine at the new episode's cues; anchor media time. */
    fun attachSyncProcessor(cues: List<SubtitleCue>, startPositionMs: Long) {
        syncProcessor.setCues(cues)
        syncProcessor.setStartPosition(startPositionMs)
    }

    fun detachSyncProcessor() {
        syncProcessor.setCues(emptyList())
        subtitleOffsetMs = manualDelayMs
    }

    override fun onSyncLocked(offsetSeconds: Float) {
        AppLog.d("SYNC", "LOCKED at " + offsetSeconds + "s")
        // Additive semantics: applied = persisted/refined auto lock + manual.
        val autoMs = (offsetSeconds * 1000f).toLong()
        subtitleOffsetMs = autoMs + manualDelayMs
        val uri = currentUri
        if (uri != null) {
            scope.launch { onAutoSyncSave(uri, autoMs) }
        }
    }

    override fun onSyncNoMatch() {
        subtitleOffsetMs = manualDelayMs
    }

    suspend fun play(
        mediaItem: MediaItem,
        uri: String,
        cues: List<SubtitleCue>,
        manualDelayMs: Long,
        persistedAutoOffsetMs: Long = 0L,
    ) {
        currentUri = uri
        this.manualDelayMs = manualDelayMs
        // Instant sync from the persisted offset; the engine refines live.
        subtitleOffsetMs = persistedAutoOffsetMs + manualDelayMs
        attachSyncProcessor(cues, /* startPositionMs = */ 0L)

        AppLog.d("ENGINE", "play: setMediaItem+prepare")
        player.setMediaItem(mediaItem)
        player.prepare()

        val saved = positionRestore(uri)
        if (saved != null && saved > 0) {
            player.seekTo(saved)
            syncProcessor.setStartPosition(saved)
        }
        AppLog.d("ENGINE", "calling play()")
        player.play()
    }

    fun savePositionNow() {
        val uri = currentUri ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val finished = dur != null && pos >= dur - 1000
        scope.launch {
            onPositionSave(uri, pos, dur ?: 0L, finished)
        }
    }

    fun stopAndSave() {
        savePositionNow()
        player.stop()
        subtitleOffsetMs = manualDelayMs
    }

    fun release() {
        stopAndSave()
        player.release()
        scope.cancel()
    }
}
