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
    private val sleep: SleepTimerUiState,
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

    fun toggleHwDecoder() {
        if (isRebuildingDecoder) {
            showTransientToast("Decoder swap in progress…")
            return
        }
        val newHw = !quick.hwDecoder.value
        quick.hwDecoder.value = newHw
        AppLog.d("PLAYER", "decoder request hw=" + newHw)
        showTransientToast(if (newHw) "Switching to hardware decoder…" else "Switching to software decoder…")
        // Fire the real rebuild via the host. The host tears down the
        // ExoPlayer, builds a new one with the requested renderers factory,
        // and re-anchors the sync processor at the saved position. The
        // on-screen chip shows "…" while isRebuildingDecoder is true; the
        // host clears it after the new player reports STATE_READY.
        onRebuildDecoder(newHw)
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
