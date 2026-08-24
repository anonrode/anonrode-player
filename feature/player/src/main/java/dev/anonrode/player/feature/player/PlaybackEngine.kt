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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
    private val onAutoSyncSave: suspend (uri: String, offsetMs: Long, speedFactor: Float) -> Unit = { _, _, _ -> },
) : SyncListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentUri: String? = null
    private var manualDelayMs: Long = 0L

    /** Applied subtitle offset in ms: persisted auto lock + manual delay.
     *  @Volatile: written from the audio processor thread ([onSyncLocked]
     *  fires there) and read from the main render loop — without it changes
     *  may never become visible across threads. */
    @Volatile var subtitleOffsetMs: Long = 0L
        private set
    /** Speed correction factor from drift detection (1.0 = no drift). Same
     *  cross-thread visibility requirement as [subtitleOffsetMs]. */
    @Volatile var subtitleSpeedFactor: Float = 1f
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

    override fun onSyncLocked(offsetSeconds: Float, speedFactor: Float) {
        AppLog.d("SYNC", "LOCKED offset=" + offsetSeconds + "s speed=" + speedFactor)
        val autoMs = (offsetSeconds * 1000f).toLong()
        subtitleOffsetMs = autoMs + manualDelayMs
        subtitleSpeedFactor = speedFactor
        val uri = currentUri
        if (uri != null) {
            scope.launch { onAutoSyncSave(uri, autoMs, speedFactor) }
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
        persistedSpeedFactor: Float = 1f,
    ) {
        currentUri = uri
        this.manualDelayMs = manualDelayMs
        subtitleOffsetMs = persistedAutoOffsetMs + manualDelayMs
        subtitleSpeedFactor = persistedSpeedFactor
        attachSyncProcessor(cues, 0L)

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

    private data class PositionSnapshot(
        val uri: String,
        val positionMs: Long,
        val durationMs: Long?,
        val finished: Boolean,
    )

    /**
     * Reads ExoPlayer state into an immutable snapshot. MUST be called on the
     * player's owning thread (main) — Media3 throws off-main-thread access.
     */
    private fun capturePositionSnapshot(): PositionSnapshot? {
        val uri = currentUri ?: return null
        val pos = player.currentPosition
        // Null (not 0!) when unknown: storing 0 would clobber a previously
        // persisted good duration for this URI.
        val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        val finished = dur != null && pos >= dur - 1000
        return PositionSnapshot(uri, pos, dur, finished)
    }

    /** Fire-and-forget save; caller must be on the main thread. */
    fun savePositionNow() {
        val snap = capturePositionSnapshot() ?: return
        scope.launch {
            onPositionSave(snap.uri, snap.positionMs, snap.durationMs, snap.finished)
        }
    }

    /**
     * Suspending save for the service's periodic autosave loop: caller must
     * already be on the main thread so the [capturePositionSnapshot] reads
     * are legal; only the store write hops to [Dispatchers.IO].
     */
    suspend fun persistPositionNow() {
        val snap = capturePositionSnapshot() ?: return
        withContext(Dispatchers.IO) {
            onPositionSave(snap.uri, snap.positionMs, snap.durationMs, snap.finished)
        }
    }

    fun stopAndSave() {
        savePositionNow()
        player.stop()
        subtitleOffsetMs = manualDelayMs
    }

    fun release() {
        // Save synchronously BEFORE teardown: any async save launched here
        // would be cancelled by scope.cancel() below before its DB write
        // lands. Blocking is acceptable on this teardown path — one write.
        capturePositionSnapshot()?.let { snap ->
            runBlocking {
                onPositionSave(snap.uri, snap.positionMs, snap.durationMs, snap.finished)
            }
        }
        player.release()
        scope.cancel()
    }
}
