package dev.anonrode.player.ui

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.runtime.MutableIntState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.feature.player.PlaybackEngine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The player screen's action surface — what used to be ~16 local functions
 * inside one giant composable. The construction site in PlayerScreen.kt
 * now wraps this in remember(livePlayer, engine, ui, hud, sleep, gestures,
 * quick, captureScope), giving the four pointerInput blocks in
 * PlayerScreenGestures.kt a stable actions reference across control-overlay
 * recompositions — so they don't relaunch on every 8–100 ms render-loop
 * tick. Freshness is preserved: this class captures [livePlayer] and the
 * holder references, and method bodies read `ui.foo.value` / `hud.showHud()`
 * live off those holders — the same live-read semantics as before. Only the
 * construction identity changes; field types and method signatures are
 * untouched (intentionally — see the perf audit).
 */
@UnstableApi
internal class PlayerScreenActions(
    val context: Context,
    val view: View,
    val audioManager: AudioManager,
    val activity: Activity?,
    val engine: PlaybackEngine?,
    val livePlayer: Player,
    private val captureScope: CoroutineScope,
    val ui: PlayerUiState,
    private val hud: HudUiState,
    val sleep: SleepTimerUiState,
    val gestures: GestureUiState,
    val quick: QuickRowUiState,
    val speedIdx: MutableIntState,
    val speeds: List<Float>,
    val seekIncrementSec: Int,
    private val fastSeekThresholdSec: Long,
    private val isRebuildingDecoder: Boolean,
    private val title: String,
    private val onSpeedChanged: (Float) -> Unit,
    private val onZoomChanged: (Int) -> Unit,
    private val onToggleEqualizer: (Boolean) -> Boolean,
    private val onOpenCastPicker: () -> Unit,
    private val onOpenAudioTrackPicker: () -> Unit,
    private val onRebuildDecoder: (Boolean) -> Int,

    /**
     * The decoder profile the engine is on, mirrored from the host so the chip
     * shows the truth rather than a local guess. See [cycleDecoderMode].
     */
    val decoderModeLabel: String = "HW+SW",
    /**
     * Reports whether the user has pinned rotation, so the host can stop its
     * auto-landscape-on-widescreen behaviour from overriding an explicit lock.
     * True means "auto-rotate is welcome" (mode == SENSOR).
     */
    private val onAutoRotateChanged: (Boolean) -> Unit = {},
    private val onNudgeSubtitle: (Long) -> Unit,
    private val onEnterPip: () -> Unit,
    /**
     * v0.6.2 sub-sync UX pass: DataStore write for the user-facing sync
     * toggle. The action also mirrors the new state into [quick.subSyncEnabled]
     * so the toggle's icon flips instantly (DataStore is async).
     */
    private val onSetSubSyncEnabled: (Boolean) -> Unit,
    /** "Resync now" — long-press on the toggle. */
    private val onResyncNow: () -> Unit,
    /**
     * Persist + apply the user's background-playback preference.
     *
     * The ribbon's "Background Play" tool used to flip a Compose flag that
     * nothing read, so the tap produced a toast and no behaviour change. It
     * now routes through to the same [dev.anonrode.player.core.datastore.PlayerSettings.backgroundPlayback]
     * field that PlayerActivity.onStop consults — one source of truth, so
     * the ribbon tool and the Settings screen can never disagree.
     */
    private val onSetBackgroundPlayback: (Boolean) -> Unit = {},
    /**
     * Persist + apply the "Audio Effect" (voice clarity) preference. The
     * host owns the DSP stage; this action only owns the UI state + haptics
     * so the red active dot flips instantly.
     */
    private val onSetAudioEffect: (Boolean) -> Unit = {},
    /**
     * Persist the playlist shuffle/repeat pair for [persistUri] (the video
     * currently open). Null when no video is open, in which case the
     * toggle still applies to the live player but is not written.
     */
    private val onPersistPlaylistMode: (uri: String, shuffle: Boolean, repeatMode: Int) -> Unit = { _, _, _ -> },
    /** The video whose playlist mode is currently being edited. */
    private val persistUri: () -> String? = { null },
) {

    fun showHud(icon: ImageVector, text: String) =
        hud.showHud(view, icon, text)

    fun showTransientToast(msg: String) = hud.showTransientToast(view, msg)

    fun togglePlayPause() {
        if (livePlayer.isPlaying) livePlayer.pause() else livePlayer.play()
    }

    fun lockControls() {
        ui.locked.value = true
    }

    fun toggleShowCC() {
        ui.showCC.value = !ui.showCC.value
    }

    fun toggleNightMode() {
        ui.nightMode.value = !ui.nightMode.value
        view.haptic()
        showTransientToast(if (ui.nightMode.value) "Night mode on" else "Night mode off")
    }

    fun toggleShuffle() {
        ui.shuffleOn.value = !ui.shuffleOn.value
        view.haptic()
        (livePlayer as? ExoPlayer)?.shuffleModeEnabled = ui.shuffleOn.value
        showTransientToast(if (ui.shuffleOn.value) "Shuffle on" else "Shuffle off")
        persistPlaylistMode()
    }

    fun cycleRepeatLoopMode() {
        val next = ui.repeatMode.value.next()
        ui.repeatMode.value = next
        view.haptic()
        val exoMode = when (next) {
            RepeatLoopMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatLoopMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatLoopMode.ALL -> Player.REPEAT_MODE_ALL
        }
        livePlayer.repeatMode = exoMode
        showTransientToast(next.label)
        persistPlaylistMode()
    }

    /**
     * Write the shuffle/repeat pair through to the host.
     *
     * Both are persisted in ONE statement so they can never land as a
     * half-applied pair, and skipped entirely when no video is open (the
     * live player still honours the change for this session).
     */
    private fun persistPlaylistMode() {
        val uri = persistUri() ?: return
        onPersistPlaylistMode(
            uri,
            ui.shuffleOn.value,
            ui.repeatMode.value.ordinal,
        )
    }

    fun toggleMute() {
        val maxV = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentV = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (ui.isMuted.value) {
            val restoreV = if (ui.savedVolume.intValue > 0) ui.savedVolume.intValue else (maxV / 2)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreV, 0)
            ui.isMuted.value = false
            view.haptic()
            showTransientToast("Unmuted")
        } else {
            ui.savedVolume.intValue = currentV
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            ui.isMuted.value = true
            view.haptic()
            showTransientToast("Muted")
        }
    }

    fun cycleSleepTimer() {
        val options = SleepOptions
        val currentIdx = options.indexOfFirst { sleep.isSelected(it) }.coerceAtLeast(0)
        val nextIdx = (currentIdx + 1) % options.size
        val nextOpt = options[nextIdx]
        selectSleep(nextOpt)
        view.haptic()
        showTransientToast("Sleep: ${nextOpt.label}")
    }

    fun cycleAbRepeat() {
        val curPos = livePlayer.currentPosition
        when {
            ui.abStartMs.value == null -> {
                ui.abStartMs.value = curPos
                view.haptic()
                showTransientToast("Point A set: ${fmtTime(curPos)}")
            }
            ui.abEndMs.value == null -> {
                if (curPos > ui.abStartMs.value!!) {
                    ui.abEndMs.value = curPos
                    view.haptic()
                    showTransientToast("Point B set: ${fmtTime(curPos)} · Looping A-B")
                } else {
                    showTransientToast("Point B must be after Point A")
                }
            }
            else -> {
                ui.abStartMs.value = null
                ui.abEndMs.value = null
                view.haptic()
                showTransientToast("A-B repeat cleared")
            }
        }
    }

    /**
     * Audio Effect / voice clarity.
     *
     * This used to flip [PlayerUiState.audioEffectOn] and stop there — the
     * flag was only ever read back to colour its own icon, so the tap was
     * decorative. It now persists the preference through the host, which
     * owns the actual DSP stage, so the red active dot reflects a real
     * audible change.
     */
    fun toggleAudioEffect() {
        val next = !ui.audioEffectOn.value
        ui.audioEffectOn.value = next
        view.haptic()
        onSetAudioEffect(next)
        showTransientToast(if (next) "Voice clarity on" else "Audio effect off")
    }

    /**
     * Reset the persistent rate to 1x without cycling — the reference
     * player's long-press on the speed pill. [RibbonToolItem] already
     * accepts an `onLongClick`; the ribbon's Speed tool now uses it.
     */
    fun resetSpeed() {
        val normal = speeds.indexOfFirst { abs(it - 1f) < 0.05f }
        if (normal < 0 || speedIdx.intValue == normal) {
            showTransientToast("Speed already " + speedLabel(1f))
            return
        }
        speedIdx.intValue = normal
        livePlayer.setPlaybackSpeed(1f)
        onSpeedChanged(1f)
        view.haptic()
        showTransientToast("Speed reset to " + speedLabel(1f))
    }

    fun cycleSpeed() {
        val nextIdx = (speedIdx.intValue + 1) % speeds.size
        setSpeed(speeds[nextIdx])
        view.haptic()
    }

    /**
     * Background playback.
     *
     * Previously a no-op flag. Now writes the real setting: with it OFF,
     * PlayerActivity.onStop pauses the player when the activity leaves the
     * foreground; with it ON, playback (and the foreground service) continue.
     */
    fun toggleBackgroundPlay() {
        val next = !ui.backgroundPlayOn.value
        ui.backgroundPlayOn.value = next
        view.haptic()
        onSetBackgroundPlayback(next)
        showTransientToast(if (next) "Background play on" else "Background play off")
    }

    fun openRibbonCustomise() {
        quick.openRibbonCustomise()
        view.haptic()
    }

    fun closeRibbonCustomise() {
        quick.closeRibbonCustomise()
    }

    fun toggleRibbonToolVisible(tool: RibbonTool) {
        quick.toggleRibbonToolVisible(tool)
        view.haptic()
        persistRibbon()
    }

    fun moveRibbonTool(tool: RibbonTool, dir: Int) {
        quick.moveRibbonTool(tool, dir)
        view.haptic()
        persistRibbon()
    }

    /**
     * Write the current arrangement through to SharedPreferences.
     *
     * Only the ORDER of the visible tools is stored; the hidden set is
     * stored separately, so a hidden tool is simply absent from the order
     * and reappears at its catalogue position when unhidden.
     */
    private fun persistRibbon() {
        dev.anonrode.player.PlayerPrefs.saveRibbonOrder(
            context,
            quick.ribbonOrder.map { it.name },
        )
        dev.anonrode.player.PlayerPrefs.saveRibbonHidden(
            context,
            quick.ribbonHiddenTools().map { it.name }.toSet(),
        )
    }

    /**
     * Persist the ribbon's horizontal scroll offset.
     *
     * Separate from [persistRibbon] because the trigger is different in kind:
     * the arrangement writes are user-initiated and rare, whereas this one
     * arrives on every settled scroll. The collector in
     * `PlayerScreenTopBar` debounces it, and [QuickRowUiState.onRibbonScrolled]
     * reports whether the value actually moved, so a no-op scroll costs
     * nothing.
     */
    fun onRibbonScrolled(px: Int) {
        if (quick.onRibbonScrolled(px)) {
            dev.anonrode.player.PlayerPrefs.saveRibbonScrollPx(context, px)
        }
    }

    fun openSyncPopover() {
        // v0.9: only one tray may cover the transport at a time.
        quick.showStyleTray.value = false
        quick.showSyncPopover.value = true
    }

    fun closeSyncPopover() {
        quick.showSyncPopover.value = false
    }

    /**
     * v0.9 "Single-Plane Chrome": open the inline subtitle-style tray. This
     * is where subtitle tuning lives now — in-place, over the transport,
     * cue still visible — instead of the host's modal bottom sheet that
     * covered the frame the subtitles render on.
     */
    fun openStyleTray() {
        quick.showSyncPopover.value = false
        quick.showStyleTray.value = true
    }

    fun closeStyleTray() {
        quick.showStyleTray.value = false
    }

    fun toggleEqualizer() {
        val requested = !quick.equalizerOn.value
        // The host owns the android.media.audiofx.Equalizer instance bound
        // to the current audio session id; on each tap it enables/disables
        // that effect and reports back the actual on/off state.
        val actual = onToggleEqualizer(requested)
        quick.equalizerOn.value = actual
        AppLog.d("PLAYER", "equalizer request=" + requested + " actual=" + actual)
        showTransientToast(
            if (actual) "Equalizer on"
            else "Equalizer off"
        )
    }

    fun openCastPicker() {
        AppLog.d("PLAYER", "cast: opening route picker")
        onOpenCastPicker()
    }

    /**
     * Advance the decoder one step through the engine's real three profiles
     * (HW+SW -> APP -> HW -> HW+SW).
     *
     * This used to be `toggleHwDecoder`, which flipped a local `hwDecoder`
     * boolean and handed it to the host. The host ignored that boolean and
     * called `cycleDecoderMode()` anyway, so the chip could land on any of
     * three engine states while showing two — including reading "SW" while
     * the engine sat in the default HW+SW hybrid. The label the chip renders
     * now comes from the host, which reads it back off the engine.
     */
    fun cycleDecoderMode() {
        if (isRebuildingDecoder) {
            showTransientToast("Decoder swap in progress…")
            return
        }
        AppLog.d("PLAYER", "decoder request: cycle, current=" + decoderModeLabel)
        showTransientToast("Switching decoder profile…")
        // Fire the real rebuild via the host. The host tears down the
        // ExoPlayer, builds a new one with the next renderers factory, and
        // re-anchors the sync processor at the saved position. The chip shows
        // "…" while isRebuildingDecoder is true; the host clears it once the
        // new player reports STATE_READY and refreshes decoderModeLabel.
        onRebuildDecoder(decoderModeLabel != "APP")
    }

    /** Step the 3-state rotation mode forward (sensor → landscape →
     *  portrait → sensor). The activity orientation is reapplied by
     *  RotationLockEffect, which is keyed on this state. */
    fun cycleRotateMode() {
        val next = quick.rotateMode.value.next()
        applyRotateMode(next)
    }

    /** Jump to a specific rotation mode (called by the long-press menu). */
    fun setRotateMode(mode: RotateMode) {
        applyRotateMode(mode)
    }

    private fun applyRotateMode(mode: RotateMode) {
        quick.rotateMode.value = mode
        // Tell the host whether it may auto-orient. Without this, the host's
        // onVideoSizeChanged keeps forcing landscape for widescreen video and
        // the explicit lock here is silently overwritten (the DisposableEffect
        // in RotationLockEffect is keyed on `mode` and never re-fires).
        onAutoRotateChanged(mode == RotateMode.SENSOR)
        AppLog.d("PLAYER", "rotate mode=" + mode)
        showTransientToast(
            when (mode) {
                RotateMode.SENSOR -> "Auto-rotate"
                RotateMode.LANDSCAPE -> "Landscape locked"
                RotateMode.PORTRAIT -> "Portrait locked"
            }
        )
    }

    fun nudgeSubtitle(deltaMs: Long) {
        onNudgeSubtitle(deltaMs)
        showTransientToast(
            "Subtitle " + (if (deltaMs > 0) "+" else "") +
                "%.1fs".format(deltaMs / 1000f)
        )
    }

    /**
     * v0.6.2 sub-sync UX pass. Mirror the new toggle state into
     * [quick.subSyncEnabled] (instant icon flip) then persist via the
     * host callback (DataStore + fingerprint job enqueue / cancel). The
     * host callback is responsible for side effects (cancel pending
     * fingerprint jobs when toggling OFF, etc.).
     */
    fun setSubSyncEnabled(enabled: Boolean) {
        quick.subSyncEnabled.value = enabled
        onSetSubSyncEnabled(enabled)
        AppLog.d("PLAYER", "sub-sync toggle=$enabled")
        showTransientToast(if (enabled) "Sub sync on" else "Sub sync off")
    }

    /**
     * "Resync now" — fires the host callback that triggers an immediate
     * fingerprint / calibration pass. Always available, regardless of the
     * toggle state, so a user can force a calibration without first
     * flipping the toggle ON.
     */
    fun resyncNow() {
        onResyncNow()
    }

    /** Enter PiP (host hook). Bottom-bar PiP chip path. */
    fun enterPip() {
        view.haptic()
        onEnterPip()
    }

    fun pickAudioTrack() {
        // Opens the audio track picker in the host (PlayerActivity). The
        // host reads Player.getCurrentTracks() and shows a bottom sheet
        // listing every available audio track.
        view.haptic()
        onOpenAudioTrackPicker()
    }

    fun selectSleep(opt: SleepOption) = sleep.selectSleep(opt)

    /**
     * Pick an exact playback rate (v0.7.3): the speed pill's dropdown and
     * the Control Center both set a VALUE now instead of blindly cycling
     * (a 0.5→2.0 cycle through six stops made "what am I at?" a lookup
     * task). Applies live, persists per-video via the host, flips the
     * pill's index.
     */
    fun setSpeed(sp: Float) {
        val idx = speeds.indexOfFirst { abs(it - sp) < 0.05f }
        if (idx < 0) return
        speedIdx.intValue = idx
        livePlayer.setPlaybackSpeed(sp)
        onSpeedChanged(sp)
        showTransientToast("Speed " + speedLabel(sp))
    }

    /**
     * Pick an exact zoom/aspect mode (v0.7.3): direct mode selection from
     * the aspect pill's dropdown replaces the old double-blind cycle — and
     * the HUD pill still flashes the abbreviation for touch feedback.
     */
    fun setZoom(idx: Int) {
        if (idx !in ZoomModes.indices || idx == ui.zoomIdx.intValue) return
        ui.zoomIdx.intValue = idx
        onZoomChanged(idx)
        showHud(Icons.Filled.AspectRatio, ZoomModes[idx].abbreviation)
    }

    /**
     * Step the zoom mode by [dir] (+1 / -1), clamped to the mode list. Used
     * by the pinch gesture; a no-op at either end. Persists like [setZoom].
     */
    fun zoomBy(dir: Int) {
        val next = (ui.zoomIdx.intValue + dir).coerceIn(0, ZoomModes.size - 1)
        if (next == ui.zoomIdx.intValue) return
        ui.zoomIdx.intValue = next
        onZoomChanged(ui.zoomIdx.intValue)
        showHud(Icons.Filled.AspectRatio, ZoomModes[ui.zoomIdx.intValue].abbreviation)
    }

    fun seekBy(sec: Int) {
        val p = engine?.player ?: livePlayer
        val d = p.duration.takeIf { it > 0 } ?: return
        // Big jumps snap to the nearest keyframe (instant); small ones
        // stay frame-exact. EXACT is restored shortly after so drags and
        // swipes are unaffected by the temporary parameter.
        // (setSeekParameters/SeekParameters live on ExoPlayer, not Player.)
        val fast = abs(sec) >= fastSeekThresholdSec
        if (fast) {
            (p as? ExoPlayer)?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
        p.seekTo((p.currentPosition + sec * 1000L).coerceIn(0L, d))
        if (fast) {
            view.postDelayed({
                ((engine?.player ?: livePlayer) as? ExoPlayer)
                    ?.setSeekParameters(SeekParameters.EXACT)
            }, 500)
        }
        ui.flashSide.value = if (sec < 0) -1 else 1
        view.postDelayed({ ui.flashSide.value = 0 }, 420)
    }

    /**
     * Save the current video frame: PixelCopy the PlayerView's SurfaceView
     * (which holds the decoded frame — a plain view screenshot would be
     * black), then write a PNG to Pictures/AnonPlayer (MediaStore on
     * API 29+, app-specific dir below that — no permission needed either
     * way).
     */
    fun captureFrame() {
        val pv = ui.playerViewRef.value ?: return
        if (Build.VERSION.SDK_INT < 24) {
            showTransientToast("Screenshot needs Android 7.0+")
            return
        }
        val surfaceView = pv.videoSurfaceView as? SurfaceView
        if (surfaceView == null || surfaceView.width <= 0 || surfaceView.height <= 0) {
            showTransientToast("Screenshot failed — no video surface")
            return
        }
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) {
            showTransientToast("Screenshot failed — surface not ready")
            return
        }
        val bmp = Bitmap.createBitmap(
            surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888
        )
        PixelCopy.request(surface, bmp, { result ->
            if (result == PixelCopy.SUCCESS) saveFrame(bmp)
            else showTransientToast("Screenshot failed (code " + result + ")")
        }, Handler(Looper.getMainLooper()))
    }

    private fun saveFrame(bmp: Bitmap) {
        val base = title.substringAfterLast('/').substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9 ._\\-]"), "")
            .ifEmpty { "frame" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = base + "_" + stamp + ".png"
        captureScope.launch(Dispatchers.IO) {
            try {
                val bytes = ByteArrayOutputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                val where: String
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(
                            MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/AnonPlayer",
                        )
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: throw IOException("MediaStore insert failed")
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("no output stream")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    where = "Pictures/AnonPlayer"
                } else {
                    // App-specific public dir: no WRITE_EXTERNAL_STORAGE
                    // needed, visible to file managers.
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                        ?: throw IOException("no external files dir")
                    File(dir, name).writeBytes(bytes)
                    where = dir.absolutePath
                }
                AppLog.d("SHOT", "saved " + name + " to " + where)
                view.post { showTransientToast("Saved to " + where + "/" + name) }
            } catch (e: Exception) {
                AppLog.e("SHOT", "screenshot save failed", e)
                view.post { showTransientToast("Screenshot save failed") }
            }
        }
    }
}
