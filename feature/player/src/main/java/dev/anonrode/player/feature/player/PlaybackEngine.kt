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
import dev.anonrode.player.core.media.sync.SyncFingerprint
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

    /** Persisted auto-sync offset (fingerprint lock) applied at [play]. Kept
     *  so [onSyncNoMatch] can fall back to it instead of dropping the lock:
     *  a failed LIVE re-lock must not undo a good persisted one. Written on
     *  the main thread, read from the sync-eval worker thread. */
    @Volatile private var persistedAutoMs: Long = 0L

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
     * Live re-lock gate for the audio sync processor (v0.6.2 sub-sync UX
     * pass). Mirrors
     * [dev.anonrode.player.core.datastore.PlayerSettings.subtitleAutoSyncEnabled]
     * into the processor so the user toggle from the player chrome can
     * enable/disable the live re-lock at runtime. False = processor
     * stays dormant (no evaluations, no lock publishing). True =
     * normal re-lock behaviour. Safe to call from any thread — the
     * underlying flag is volatile.
     */
    fun setSubSyncEnabled(enabled: Boolean) {
        syncProcessor.setEnabled(enabled)
        AppLog.d("ENGINE", "sub sync enabled=$enabled")
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
     *
     * Initialized lazily on first access — see [buildInitialPlayer]. The
     * constructor MUST stay cheap: AnonrodeApp.onCreate constructs this on
     * the main thread before any UI exists, and an ExoPlayer build pulls
     * in DefaultTrackSelector, the FFmpeg renderer factory (or its
     * device-only fallback), the audio sink (with our two custom audio
     * processors), and the first MediaCodec probe. Doing that on Main is
     * an ANR risk on cold start. The lazy delegate below defers the build
     * to the first call to [player] — which happens on the main thread in
     * the activity's onCreate path, still synchronously before playback
     * starts, so the playback hot path is unchanged.
     */
    @Volatile
    private var playerInstance: ExoPlayer? = null

    /**
     * The active [ExoPlayer]. Builds the first one on first access (lazy
     * — see the field kdoc above). After a [rebuild] this returns the
     * freshly-built instance. Callers must be on the main thread once the
     * player is in use (Media3's own constraint).
     */
    var player: ExoPlayer
        get() {
            val existing = playerInstance
            if (existing != null) return existing
            return synchronized(this) {
                val again = playerInstance
                if (again != null) again
                else buildInitialPlayer(appContext).also { playerInstance = it }
            }
        }
        private set(value) {
            playerInstance = value
        }

    /**
     * Constructor-time budget. This MUST stay cheap — see [player].
     * What runs here:
     *  - [scope] (SupervisorJob on Dispatchers.Default, cheap)
     *  - [appContext] (applicationContext lookup, cheap)
     *  - primitive field initializers (currentUri, manualDelayMs, …)
     *  - [syncProcessor] / [boostProcessor] (constructors only; no IO)
     *  - lazy [player] (first access builds ExoPlayer; later hits return
     *    the same instance)
     *
     * What must NOT run here: MediaStore reads, DataStore reads, Room
     * queries, ExoPlayer.build() outside the lazy delegate, file IO.
     */
    init {
        // Intentionally empty: the field initializers above already do the
        // minimum. [player] is built lazily; nothing else belongs in the
        // constructor.
    }

    /**
     * First player built on first access to [player]. Falls back to pure
     * platform decoders if the default (FFmpeg-backed) renderer factory
     * can't be built (e.g. native libs unavailable on this ABI), so a
     * missing lib can't break app startup. Only touches fields declared
     * above it ([decoderMode], [syncProcessor], [boostProcessor]).
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
     * Speed the host intends to apply after a [rebuild]. The host sets
     * this immediately before [rebuild] so the value is captured at the
     * moment of the swap. The engine itself does NOT read this — playback
     * speed is single-source-owned by the Compose host
     * (PlayerScreen.LaunchedEffect(initialSpeed, livePlayer) →
     * setPlaybackSpeed), which fires on the new [Player] instance as soon
     * as the rebuild returns and Compose recomposes. The field is kept as
     * a public API for backward compatibility with host call sites that
     * still write to it before invoking [rebuild].
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
        // Live re-lock gave up: keep the persisted fingerprint lock (if any)
        // rather than dropping back to the bare manual delay — undoing a good
        // stored lock mid-episode would desync already-correct subtitles.
        subtitleOffsetMs = persistedAutoMs + manualDelayMs
    }

    /**
     * Start playback of [mediaItem].
     *
     * [savedPositionMs] is the caller's pre-read resume position. When it is
     * provided this function never suspends — the whole
     * setMediaItem → seekTo → prepare → play pipeline runs in one go, so the
     * player never renders frame 0 before the resume seek lands. Callers
     * without a pre-read value leave it null; the [positionRestore] store
     * read then happens BEFORE setMediaItem (it suspends, but never inside
     * the pipeline).
     */
    suspend fun play(
        mediaItem: MediaItem,
        uri: String,
        cues: List<SubtitleCue>,
        manualDelayMs: Long,
        persistedAutoOffsetMs: Long = 0L,
        persistedSpeedFactor: Float = 1f,
        resume: Boolean = true,
        syncEnabled: Boolean = true,
        savedPositionMs: Long? = null,
    ) {
        currentUri = uri
        currentMediaItem = mediaItem
        this.manualDelayMs = manualDelayMs
        persistedAutoMs = if (syncEnabled) persistedAutoOffsetMs else 0L
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

        // Resume position: caller's pre-read value wins (suspension-free hot
        // path); otherwise fall back to the store read. resume=false =
        // "start over" — skip the stored position entirely.
        val saved = if (!resume) null else savedPositionMs ?: positionRestore(uri)

        AppLog.d("ENGINE", "play: setMediaItem+prepare uri=" + uri)
        // setMediaItem → seekTo → prepare with no suspension in between:
        // setting the start position BEFORE prepare means buffering begins
        // at the resume point and the first rendered frame is the resume
        // frame — no frame-0 flash.
        player.setMediaItem(mediaItem)
        if (saved != null && saved > 0) {
            player.seekTo(saved)
            syncProcessor.setStartPosition(saved)
            startPositionMs = saved
        } else {
            startPositionMs = 0L
        }
        player.prepare()
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
        // recommend: detach listeners (so callbacks can't fire into a
        // half-released instance), stop, clear, release. We skip the
        // explicit position save here because the host (PlayerActivity)
        // re-saves on every periodic tick; a rebuild is rare and we don't
        // want a blocked IO hop on the main thread inside the engine.
        val oldPlayer = player
        // Copy the listener list once: re-attaching below needs the same
        // instances, but we want them off the old player BEFORE release()
        // so a late callback (e.g. onPlaybackStateChanged fired by the
        // stop() above) can't land on a listener that will be re-added to
        // the new player and double-fire.
        val listenersToReattach: List<Player.Listener> = synchronized(replayListeners) {
            replayListeners.toList()
        }
        listenersToReattach.forEach { oldPlayer.removeListener(it) }
        try {
            oldPlayer.stop()
            oldPlayer.clearMediaItems()
        } catch (t: Throwable) {
            // The MediaSession may already have released the player
            // reference; a half-dead ExoPlayer throws on stop(). Log and
            // continue — release() below is the authoritative teardown.
            AppLog.e("ENGINE", "old player stop/clear failed during rebuild", t)
        }
        try {
            oldPlayer.release()
        } catch (t: Throwable) {
            AppLog.e("ENGINE", "old player release failed during rebuild", t)
        }

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

        // Re-attach every registered listener to the new player. The
        // list was snapshotted BEFORE the old player was released
        // (above) so we don't re-add to a dead instance. We hold the
        // same lock used by add/removeListener so a concurrent
        // registration can't double-add.
        listenersToReattach.forEach { newPlayer.addListener(it) }

        if (item != null) {
            // Same order as [play]: setMediaItem → seekTo → prepare, so the
            // rebuilt player starts buffering at the old position instead of
            // flashing frame 0 before a post-prepare seek lands.
            newPlayer.setMediaItem(item)
            if (pos > 0L) {
                newPlayer.seekTo(pos)
                syncProcessor.setStartPosition(pos)
                startPositionMs = pos
            }
            newPlayer.prepare()
            // Restore transport state. STATE_READY/STATE_BUFFERING mean the
            // user was actively watching; we re-enter play() to mirror the
            // prior playWhenReady.
            if (wasPlayWhenReady || wasPlaying) {
                newPlayer.play()
                wasPlaying = true
            } else {
                wasPlaying = false
            }
            // Speed re-application is owned by the Compose host
            // (PlayerScreen.LaunchedEffect(initialSpeed, livePlayer) ->
            // setPlaybackSpeed). The engine no longer touches
            // playbackParameters here so the speed-write is single-source.
            // pendingSpeedOnRebuild is kept as a host-facing write API
            // (PlayerActivity still sets it before calling rebuild) but
            // is intentionally not consumed inside the engine.
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

    /**
     * Cancel the background subtitle-sync fingerprint job for [videoUri], if
     * one is in flight. Call from the host when the user is permanently
     * done with a video (e.g. PlayerActivity.onDestroy after a back press)
     * so a long-running ffmpeg decode pass doesn't keep retrying against a
     * media item the user has abandoned.
     *
     * The job is unique-per-URI with [androidx.work.ExistingWorkPolicy.KEEP],
     * so WorkManager will keep retrying it on its own schedule even if the
     * engine's own coroutine scope is cancelled. This method hands the
     * cancel to [dev.anonrode.player.core.media.sync.SyncFingerprint], the
     * single owner of the WorkManager request name for that URI.
     */
    fun cancelSyncFingerprintJob(videoUri: String) {
        SyncFingerprint.cancel(appContext, videoUri)
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
        // Detach every registered listener BEFORE release: callbacks that
        // fire from the impending release() would otherwise be dispatched
        // into a soon-to-be-dead player. Mirrors the listener cleanup in
        // rebuildMode so the teardown order is consistent across both
        // paths.
        synchronized(replayListeners) {
            val snap = replayListeners.toList()
            val toRelease = playerInstance
            if (toRelease != null) snap.forEach { toRelease.removeListener(it) }
        }
        try {
            player.release()
        } catch (t: Throwable) {
            AppLog.e("ENGINE", "player release failed in engine.release()", t)
        }
        playerInstance = null
        scope.cancel()
    }
}
