package dev.anonrode.player.feature.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultTrackSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioAttributes
import dev.anonrode.player.core.datastore.PlayerSettings
import dev.anonrode.player.core.media.sync.AudioSyncProcessor
import dev.anonrode.player.core.media.sync.SyncListener
import dev.anonrode.player.core.model.SubtitleCue
import io.github.anilbeesetti.nextlib.media3ext.NextRenderersFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Owns the ExoPlayer instance: nextlib FFmpeg renderers, decoder fallback,
 * audio-focus handling, resume restore/save, and the live auto-sync
 * processor on the audio path.
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
    private var audioSyncProcessor: AudioSyncProcessor? = null
    private var syncOffsetMs = 0L
    private var currentUri: String? = null

    /** Current total subtitle offset in ms (manual delay + auto-sync lock). */
    var subtitleOffsetMs: Long = 0L
        private set

    val player: ExoPlayer = buildPlayer(context)

    private fun buildPlayer(context: Context): ExoPlayer {
        val renderersFactory = NextRenderersFactory(context)
            .setEnableDecoderFallback(true)
        val trackSelector = DefaultTrackSelector(context)
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
            .also { it.setHandleAudioBecomingNoisy(true) }
    }

    /**
     * Attach the auto-sync analysis chain for the current media.
     * `initialOffsetMs` = persisted auto offset + manual delay, applied
     * immediately so re-watches start in sync; the live engine keeps
     * listening and refines (on lock: auto + manual, additive).
     */
    fun attachSyncProcessor(cues: List<SubtitleCue>, initialOffsetMs: Long, force: Boolean = false) {
        audioSyncProcessor?.let { player.removeAudioProcessor(it) }
        audioSyncProcessor = null
        subtitleOffsetMs = initialOffsetMs

        if (!force && (!settingsProvider().autoSyncEnabled || cues.size < 5)) return

        val processor = AudioSyncProcessor(
            positionProvider = { player.currentPosition },
            listener = this,
        )
        processor.setCues(cues)
        audioSyncProcessor = processor
        player.addAudioProcessor(processor)
    }

    fun detachSyncProcessor() {
        audioSyncProcessor?.let { player.removeAudioProcessor(it) }
        audioSyncProcessor = null
        subtitleOffsetMs = manualDelayMs
    }

    override fun onSyncLocked(offsetSeconds: Float) {
        // Auto-sync offset on top of any manual delay (additive, per spec).
        subtitleOffsetMs = ((offsetSeconds * 1000f).toLong()) + manualDelayMs
        // Persist the auto lock so re-watches start instantly in sync.
        val uri = currentUri
        if (uri != null) {
            scope.launch { onAutoSyncSave(uri, ((offsetSeconds * 1000f).toLong()) ) }
        }
    }

    override fun onSyncNoMatch() {
        subtitleOffsetMs = manualDelayMs
    }

    private var manualDelayMs: Long = 0L

    suspend fun play(
        mediaItem: MediaItem,
        uri: String,
        cues: List<SubtitleCue>,
        manualDelayMs: Long,
        persistedAutoOffsetMs: Long = 0L,
    ) {
        currentUri = uri
        this.manualDelayMs = manualDelayMs
        attachSyncProcessor(cues, persistedAutoOffsetMs + manualDelayMs)

        player.setMediaItem(mediaItem)
        player.prepare()

        val saved = positionRestore(uri)
        if (saved != null && saved > 0) {
            player.seekTo(saved)
        }
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
        detachSyncProcessor()
    }

    fun release() {
        stopAndSave()
        player.release()
        scope.cancel()
    }
}
