package dev.anonrode.player.feature.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dev.anonrode.player.core.media.audio.VolumeBoostProcessor
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
 *
 * Decoder selection ([decoderMode]): MODE_DEVICE_ONLY builds the player with
 * the standard [DefaultRenderersFactory] and extension renderers OFF (pure
 * MediaCodec). MODE_PREFER_DEVICE uses [NextRenderersFactory] with extensions
 * ON — device decoders win, FFmpeg is the fallback. MODE_PREFER_APP uses
 * [NextRenderersFactory] with extensions PREFERRED, forcing FFmpeg software
 * decoding. The mode survives across the player's lifetime and is reapplied
 * on every [rebuildMode].
 */
@UnstableApi
class PlaybackEngine(
    context: Context,
    private val positionRestore: suspend (String) -> Long?,
    private val onPositionSave: suspend (uri: String, positionMs: Long, durationMs: Long?, finished: Boolean) -> Unit,
    private val onAutoSyncSave: suspend (uri: String, offsetMs: Long, speedFactor: Float) -> Unit = { _, _, _ -> },
) : SyncListener {

    companion object {
        /** Stock Media3 only; FFmpeg extension renderers disabled. */
        const val MODE_DEVICE_ONLY = 0

        /** Device decoders preferred; FFmpeg available as fallback. */
        const val MODE_PREFER_DEVICE = 1

        /** FFmpeg software decoders preferred. */
        const val MODE_PREFER_APP = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appContext: Context = context.applicationContext
    private var currentUri: String? = null
    private var manualDelayMs: Long = 0L

    /**
     * Cached media item + start position from the most recent [play] call.
     * Used by [rebuild] to restore the same content after the player is
     * torn down. We capture [MediaItem] rather than just the URI so any
     * attached subtitle / clipping config survives a decoder swap.
     */
    @Volatile private var currentMediaItem: MediaItem? = null
    @Volatile private var startPositionMs: Long = 0L
    @Volatile private var wasPlaying: Boolean = false

    /** Active decoder profile; one of the MODE_ constants. */
    @Volatile var decoderMode: Int = MODE_PREFER_DEVICE
        private set

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

    /** Invoked when a LIVE auto-sync lock lands mid-playback. Fires on the
     *  sync-eval worker thread — receivers must post to their own thread.
     *  The UI uses it to drop in-memory piecewise segments, which the
     *  persistence side clears in the same lock write. */
    @Volatile var onLiveSyncLocked: (() -> Unit)? = null

    private val syncProcessor = AudioSyncProcessor(this)

    /** VLC-style gain stage after the sync analyzer (see its KDoc). */
    private val boostProcessor = VolumeBoostProcessor()

    /**
     * Volume boost: 1.0 = off up to 3.0 (+9.5 dB). Runtime-safe — the audio
     * thread reads the processor's volatile gain on every buffer.
     */
    fun setVolumeBoost(gain: Float) {
        boostProcessor.gain = gain.coerceIn(1f, 3f)
        AppLog.d("ENGINE", "volume boost gain=" + boostProcessor.gain)
    }

    /**
     * Listeners that follow the player across rebuilds. External callers
     * register through [addListener]; on every [rebuild] we detach them
     * from the old player and re-attach to the new one. Main-thread only
     * (matching Media3's own constraint on add/removeListener).
     */
    private val replayListeners = mutableListOf<Player.Listener>()

    /**
     * The current [ExoPlayer] instance. Backing a `val` with a `var` so
     * [rebuild] can swap it out; all accessors read the field fresh so a
     * decoder swap is transparent to callers that just hold an engine ref.
     */
    @Volatile var player: ExoPlayer = buildInitialPlayer(context.applicationContext)
        private set

    /**
     * First player built at engine construction. Falls back to pure platform
     * decoders if the default (FFmpeg-backed) renderer factory can't be built
     * (e.g. native libs unavailable on this ABI), so a missing lib can't break
     * app startup. Called from the [player] initializer — only touches fields
     * declared above it ([decoderMode], [syncProcessor], [boostProcessor]).
     */
    private fun buildInitialPlayer(ctx: Context): ExoPlayer = try {
        buildPlayer(ctx, decoderMode)
    } catch (t: Throwable) {
        AppLog.e("ENGINE", "initial buildPlayer failed for mode=" + decoderMode +
            "; falling back to device-only", t)
        decoderMode = MODE_DEVICE_ONLY
        buildPlayer(ctx, MODE_DEVICE_ONLY)
    }

    /** Add a listener that survives [rebuild] — re-attached automatically. */
    fun addListener(listener: Player.Listener) {
        synchronized(replayListeners) {
            replayListeners.add(listener)
        }
        player.addListener(listener)
    }

    /** Remove a previously added listener. */
    fun removeListener(listener: Player.Listener) {
        synchronized(replayListeners) {
            replayListeners.remove(listener)
        }
        player.removeListener(listener)
    }

    /**
     * The current player's audio session id, or 0 if Media3 hasn't attached
     * one yet (it returns [C.AUDIO_SESSION_ID_UNSET] until the first
     * prepared playback). Reading is cheap; callers should treat 0 as
     * "session not ready, retry on next state change".
     */
    val currentAudioSessionId: Int
        get() = if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) player.audioSessionId else 0

    /** Whether the engine is currently on a device-decoder profile
     *  (anything except the FFmpeg-preferred mode). */
    val isHw: Boolean get() = decoderMode != MODE_PREFER_APP

    /**
     * Speed the host wants applied to the rebuilt player. The host sets
     * this immediately before [rebuild] (the Compose host reads its own
     * restored speed on every onSpeedChanged), so the freshly-created
     * ExoPlayer comes up at the same rate the user picked.
     */
    @Volatile var pendingSpeedOnRebuild: Float = 1f

    /**
     * One-shot hook fired immediately after a successful [rebuild]. The
     * [PlayerService] uses this to rebuild its [androidx.media3.session.MediaSession]
     * around the new player. The list is not synchronised — registration
     * is expected to happen once at service init.
     */
    private val onPlayerRebuiltHooks = mutableListOf<(ExoPlayer) -> Unit>()

    fun addRebuiltHook(hook: (ExoPlayer) -> Unit) {
        synchronized(onPlayerRebuiltHooks) { onPlayerRebuiltHooks.add(hook) }
    }

    fun removeRebuiltHook(hook: (ExoPlayer) -> Unit) {
        synchronized(onPlayerRebuiltHooks) { onPlayerRebuiltHooks.remove(hook) }
    }

    private fun fireRebuiltHooks(newPlayer: ExoPlayer) {
        val hooks = synchronized(onPlayerRebuiltHooks) { onPlayerRebuiltHooks.toList() }
        hooks.forEach { it(newPlayer) }
    }

    /** Audio sink shared by every decoder profile: sync analyzer first,
     *  then the volume-boost gain stage. */
    private fun buildSharedAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(syncProcessor, boostProcessor))
            .build()

    private fun buildPlayer(context: Context, mode: Int): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context)
        val renderersFactory: androidx.media3.exoplayer.RenderersFactory =
            if (mode == MODE_DEVICE_ONLY) {
                // Pure platform path: stock Media3 + Android MediaCodec,
                // extension renderers fully disabled. The audio sink still
                // carries the sync + boost processors.
                object : DefaultRenderersFactory(context) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = buildSharedAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                }.apply {
                    setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                }
            } else {
                // nextlib path: FFmpeg extension renderers are registered.
                // ON = device decoders win, FFmpeg is the fallback;
                // PREFER = FFmpeg wins the selection race.
                object : NextRenderersFactory(context) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean,
                    ): AudioSink = buildSharedAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                }.apply {
                    // The MODE_ constants are declared on DefaultRenderersFactory;
                    // Kotlin won't resolve them through the nextlib subclass.
                    setExtensionRendererMode(
                        if (mode == MODE_PREFER_APP) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                        else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    )
                }
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
        onLiveSyncLocked?.invoke()
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
        resume: Boolean = true,
        syncEnabled: Boolean = true,
    ) {
        currentUri = uri
        currentMediaItem = mediaItem
        this.manualDelayMs = manualDelayMs
        if (syncEnabled) {
            subtitleOffsetMs = persistedAutoOffsetMs + manualDelayMs
            subtitleSpeedFactor = persistedSpeedFactor
            attachSyncProcessor(cues, 0L)
        } else {
            // Auto-sync off: subs render exactly as timed in the file —
            // no persisted lock, no live listening (empty cues = the
            // processor's evaluate() never runs).
            subtitleOffsetMs = manualDelayMs
            subtitleSpeedFactor = 1f
            attachSyncProcessor(emptyList(), 0L)
        }

        // Read the saved position BEFORE setMediaItem/prepare: the Room read
        // suspends, and doing it after prepare would let the player start
        // rendering at 0 and flash frame 0 before the seek lands.
        // resume=false = "start over" — skip the stored position entirely.
        val saved = if (resume) positionRestore(uri) else null

        AppLog.d("ENGINE", "play: setMediaItem+prepare uri=" + uri)
        player.setMediaItem(mediaItem)
        player.prepare()

        if (saved != null && saved > 0) {
            player.seekTo(saved)
            syncProcessor.setStartPosition(saved)
            startPositionMs = saved
        } else {
            startPositionMs = 0L
        }
        AppLog.d("ENGINE", "calling play()")
        // Bring up the foreground playback service: media session (lock-screen
        // controls), the media notification, and the 5s position autosave.
        // Media3's MediaSessionService takes over startForeground once the
        // player is actually playing. Re-starting a running service is a
        // cheap no-op, so this is safe on every play(). Wrapped so a service
        // start failure (background-start restriction, SecurityException)
        // degrades to "no notification/autosave" instead of killing playback.
        try {
            ContextCompat.startForegroundService(
                appContext, Intent(appContext, PlayerService::class.java)
            )
        } catch (e: Exception) {
            AppLog.e("ENGINE", "foreground service start failed; continuing without it", e)
        }
        player.play()
        wasPlaying = true
    }

    /**
     * Swap the active ExoPlayer for a new one built with the requested
     * decoder profile, preserving the current media item + playback
     * position. Must be called on the main thread (the engine's own
     * `player` reads throw off-main otherwise). The state fields on the
     * engine — [subtitleOffsetMs], [subtitleSpeedFactor], [manualDelayMs],
     * [syncProcessor], [currentMediaItem], [currentUri] — are NOT reset;
     * they survive the rebuild so the user's sync lock and episode stay
     * intact across a decoder swap.
     *
     * @return the new player's audio session id, or 0 if rebuild was a
     *   no-op (same [decoderMode]).
     */
    fun rebuild(hw: Boolean): Int =
        rebuildMode(if (hw) MODE_DEVICE_ONLY else MODE_PREFER_APP)

    /** Three-mode variant of [rebuild] driven by the MODE_ constants. */
    fun rebuildMode(mode: Int): Int {
        if (mode == decoderMode) {
            AppLog.d("ENGINE", "rebuild: no-op (mode=" + mode + ")")
            return if (player.audioSessionId != C.AUDIO_SESSION_ID_UNSET) player.audioSessionId else 0
        }
        val ctx = appContext
        val pos = player.currentPosition
        val wasPlayWhenReady = player.playWhenReady
        val playState = player.playbackState
        val item = currentMediaItem

        AppLog.d("ENGINE", "rebuild: tearing down player (mode=" + decoderMode + " -> " + mode +
            ") pos=" + pos + "ms state=" + playState + " item=" + (item?.mediaId ?: "null"))

        // Tear down on the main thread, in the same order Media3's docs
        // recommend: stop, clear, release. We skip the explicit position
        // save here because the host (PlayerActivity) re-saves on every
        // periodic tick; a rebuild is rare and we don't want a blocked IO
        // hop on the main thread inside the engine.
        player.stop()
        player.clearMediaItems()
        player.release()

        decoderMode = mode
        val newPlayer = try {
            buildPlayer(ctx, mode)
        } catch (t: Throwable) {
            // A failed renderer build (e.g. the FFmpeg native libs being
            // unavailable on this ABI) must not leave the engine without a
            // player. Fall back to pure platform decoders; if even that
            // fails, rethrow so the caller's catch surfaces the error.
            AppLog.e("ENGINE", "buildPlayer failed for mode=" + mode +
                "; falling back to device-only", t)
            decoderMode = MODE_DEVICE_ONLY
            if (mode == MODE_DEVICE_ONLY) throw t
            buildPlayer(ctx, MODE_DEVICE_ONLY)
        }
        player = newPlayer

        // Re-attach every registered listener to the new player. We hold
        // the same lock used by add/removeListener so a concurrent
        // registration can't double-add.
        val toReplay: List<Player.Listener> = synchronized(replayListeners) {
            replayListeners.toList()
        }
        toReplay.forEach { newPlayer.addListener(it) }

        if (item != null) {
            newPlayer.setMediaItem(item)
            newPlayer.prepare()
            if (pos > 0L) {
                newPlayer.seekTo(pos)
                syncProcessor.setStartPosition(pos)
                startPositionMs = pos
            }
            // Restore transport state. STATE_READY/STATE_BUFFERING mean the
            // user was actively watching; we re-enter play() to mirror the
            // prior playWhenReady.
            if (wasPlayWhenReady || wasPlaying) {
                newPlayer.play()
                wasPlaying = true
            } else {
                wasPlaying = false
            }
            // Re-apply the live speed so a decoder swap doesn't reset the
            // user's chosen 1.25x to 1.0x. Speed is owned by the host and
            // re-fed via the rebuilt hook below.
            val hookSpeed = pendingSpeedOnRebuild
            if (hookSpeed > 0f) newPlayer.setPlaybackSpeed(hookSpeed)
        }

        val sid = if (newPlayer.audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
            newPlayer.audioSessionId
        } else 0
        AppLog.d("ENGINE", "rebuild: new player ready, sessionId=" + sid)
        fireRebuiltHooks(newPlayer)
        return sid
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
        wasPlaying = false
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
