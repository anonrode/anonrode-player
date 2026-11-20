package dev.anonrode.player.ui

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import dev.anonrode.player.PlayerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Stable
class PlayerScreenActions(
    private val context: Context,
    private val activity: Activity?,
    private val livePlayer: ExoPlayer?,
    val ui: PlayerUiState,
    val gestures: GestureUiState,
    val quick: QuickRowUiState,
    private val scope: CoroutineScope,
    val decoderModeLabel: State<String>,
    val isRebuildingDecoder: State<Boolean>,
    private val onRebuildDecoderCycle: () -> Unit,
    private val onApplyEngineSubtitleOffsetSec: (Float) -> Unit,
    private val onTriggerEngineAutoSync: () -> Unit,
    private val onCaptureVideoFrame: () -> Unit,
    private val onPersistSpeed: (Float) -> Unit = {},
    private val onSelectDecoderModeHost: (Int) -> Unit = {},
    private val onOpenEqualizerSheetHost: () -> Unit = {},
    private val onOpenCastPickerHost: () -> Unit = {},
    private val onOpenOnlineSubtitlesHost: () -> Unit = {},
    private val onCycleAbRepeatHost: () -> Unit = {},
    private val onSelectSleepTimerHost: (Int) -> Unit = {},
    private val onToggleBackgroundPlayHost: (Boolean) -> Unit = {},
    private val onToggleAudioEffectHost: (Boolean) -> Unit = {},
    private val onSetAutoSyncEnabledHost: (Boolean) -> Unit = {},
    private val onUpdatePlaylistModeHost: (Boolean, RepeatLoopMode) -> Unit = { _, _ -> },
    private val onSetRotateModeHost: (RotateMode) -> Unit = {},
    private val onOpenFullSettingsHost: () -> Unit = {},
    private val onReorderEpisodesHost: (List<String>) -> Unit = {},
    private val onRemoveEpisodeHost: (String) -> Unit = {},
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxHardwareVolume: Int =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var exactSeekRestoreJob: Job? = null

    fun syncInitialHardwareState() {
        val currentHw = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        ui.volumeBoostPercent.intValue = ((currentHw.toFloat() / maxHardwareVolume) * 100f).roundToInt()
        val windowAttr = activity?.window?.attributes
        if (windowAttr != null && windowAttr.screenBrightness >= 0f) {
            ui.screenBrightnessFraction.floatValue = windowAttr.screenBrightness.coerceIn(0.02f, 1.0f)
        }
    }

    fun onPlayPauseToggle() {
        ui.bumpInteraction()
        val player = livePlayer ?: return
        if (player.isPlaying) {
            player.pause()
            ui.isPlaying.value = false
        } else {
            player.play()
            ui.isPlaying.value = true
        }
    }

    /**
     * Fast 60 FPS keyframe preview seek (`SeekParameters.CLOSEST_SYNC`) during horizontal drags.
     */
    fun onPreviewKeyFrameSeekMs(targetMs: Long) {
        val player = livePlayer ?: return
        exactSeekRestoreJob?.cancel()
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.seekTo(targetMs.coerceAtLeast(0L))
    }

    /**
     * Commits final scrub position and restores `SeekParameters.EXACT` 500ms after touch release.
     */
    fun onCommitExactSeekMs(targetMs: Long) {
        val player = livePlayer ?: return
        exactSeekRestoreJob?.cancel()
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(targetMs.coerceAtLeast(0L))
        exactSeekRestoreJob = scope.launch {
            delay(500L)
            livePlayer?.setSeekParameters(SeekParameters.EXACT)
        }
    }

    /**
     * Gesture Cancellation Recovery: immediately restores `SeekParameters.EXACT`
     * so aborted drags never leave ExoPlayer stuck in coarse keyframe seek mode.
     */
    fun onCancelKeyframeSeek() {
        exactSeekRestoreJob?.cancel()
        gestures.pendingSeekMs.floatValue = -1f
        gestures.scrubDeltaSec.floatValue = 0f
        ui.localSeek.floatValue = -1f
        livePlayer?.setSeekParameters(SeekParameters.EXACT)
    }

    /**
     * Step seek (±10s default) shared between transport buttons and double-tap seek ripples.
     */
    fun onStepSeekSec(deltaSec: Int) {
        ui.bumpInteraction()
        val player = livePlayer ?: return
        val durationMs = player.duration.coerceAtLeast(1000L)
        val targetMs = (player.currentPosition + deltaSec * 1000L).coerceIn(0L, durationMs)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(targetMs)
    }

    /**
     * Continuous 0%..200% Volume + LoudnessEnhancer Audio Boost.
     * - 0..100%: Maps linearly to hardware `AudioManager.STREAM_MUSIC` (0 dB software gain).
     * - 101..200%: Locks hardware volume at 100% and applies up to +1200 mB (+12 dB) via `LoudnessEnhancer`.
     */
    fun onApplyVolumeBoostPercent(percent: Int) {
        val clamped = percent.coerceIn(0, 200)
        ui.volumeBoostPercent.intValue = clamped
        ui.isMuted.value = (clamped == 0)

        val hwFraction = (clamped.coerceAtMost(100) / 100f)
        val targetHwIndex = (hwFraction * maxHardwareVolume).roundToInt().coerceIn(0, maxHardwareVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetHwIndex, 0)
        PlayerPrefs.saveVolume(context, targetHwIndex)

        val boostPct = (clamped - 100).coerceAtLeast(0)
        val playerSessionId = livePlayer?.audioSessionId ?: 0
        if (boostPct > 0 && playerSessionId != 0) {
            try {
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = LoudnessEnhancer(playerSessionId).apply { enabled = true }
                }
                val targetGainMb = boostPct * 12 // +1200 mB (+12 dB) at 200%
                loudnessEnhancer?.setTargetGain(targetGainMb)
                loudnessEnhancer?.enabled = true
                ui.audioEffectOn.value = true
            } catch (_: Throwable) {
                // Hardware audio session unavailable
            }
        } else {
            loudnessEnhancer?.setTargetGain(0)
            loudnessEnhancer?.enabled = false
            ui.audioEffectOn.value = false
        }
    }

    fun onApplyBrightnessFraction(fraction: Float) {
        val clamped = fraction.coerceIn(0.02f, 1.0f)
        ui.screenBrightnessFraction.floatValue = clamped
        PlayerPrefs.saveScreenBrightness(context, clamped)
        val window = activity?.window ?: return
        val params = window.attributes
        params.screenBrightness = clamped
        window.attributes = params
    }

    fun onSetPlaybackSpeed(speed: Float, persist: Boolean = false) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        livePlayer?.playbackParameters = PlaybackParameters(clamped)
        if (persist) {
            ui.currentPlaybackSpeed.floatValue = clamped
            onPersistSpeed(clamped)
        }
    }

    fun onCycleDecoderMode() {
        ui.bumpInteraction()
        onRebuildDecoderCycle()
    }

    fun onSelectDecoderMode(mode: Int) {
        ui.bumpInteraction()
        onSelectDecoderModeHost(mode)
    }

    fun onApplySubtitleOffsetSec(offsetSec: Float) {
        ui.subtitleOffsetSec.floatValue = offsetSec
        onApplyEngineSubtitleOffsetSec(offsetSec)
        ui.syncToastVisible.value = true
    }

    fun onTriggerAutoSync() {
        quick.subSyncRunning.value = true
        onTriggerEngineAutoSync()
    }

    fun onCaptureScreenshot() {
        ui.bumpInteraction()
        onCaptureVideoFrame()
    }

    fun onOpenEqualizerSheet() {
        ui.bumpInteraction()
        onOpenEqualizerSheetHost()
    }

    fun onOpenCastPicker() {
        ui.bumpInteraction()
        onOpenCastPickerHost()
    }

    fun onOpenOnlineSubtitles() {
        ui.bumpInteraction()
        onOpenOnlineSubtitlesHost()
    }

    fun onCycleAbRepeat() {
        ui.bumpInteraction()
        onCycleAbRepeatHost()
    }

    fun onSelectSleepTimer(minutes: Int) {
        ui.bumpInteraction()
        ui.sleepTimerMinutes.intValue = minutes
        onSelectSleepTimerHost(minutes)
    }

    fun onToggleBackgroundPlay(enabled: Boolean) {
        ui.bumpInteraction()
        ui.backgroundPlayOn.value = enabled
        onToggleBackgroundPlayHost(enabled)
    }

    fun onToggleAudioEffect(enabled: Boolean) {
        ui.bumpInteraction()
        ui.audioEffectOn.value = enabled
        onToggleAudioEffectHost(enabled)
    }

    fun onSetAutoSyncEnabled(enabled: Boolean) {
        ui.bumpInteraction()
        quick.subSyncEnabled.value = enabled
        onSetAutoSyncEnabledHost(enabled)
    }

    fun onUpdatePlaylistMode(shuffle: Boolean, repeat: RepeatLoopMode) {
        ui.bumpInteraction()
        ui.shuffleOn.value = shuffle
        ui.repeatMode.value = repeat
        onUpdatePlaylistModeHost(shuffle, repeat)
    }

    fun onSetRotateMode(mode: RotateMode) {
        ui.bumpInteraction()
        quick.rotateMode.value = mode
        onSetRotateModeHost(mode)
    }

    fun onOpenFullSettings() {
        ui.bumpInteraction()
        onOpenFullSettingsHost()
    }

    fun onReorderEpisodes(orderedIds: List<String>) {
        ui.bumpInteraction()
        onReorderEpisodesHost(orderedIds)
    }

    fun onRemoveEpisode(id: String) {
        ui.bumpInteraction()
        onRemoveEpisodeHost(id)
    }

    fun release() {
        exactSeekRestoreJob?.cancel()
        try {
            loudnessEnhancer?.release()
        } catch (_: Throwable) {
        }
        loudnessEnhancer = null
    }
}

@Composable
fun rememberPlayerScreenActions(
    context: Context,
    activity: Activity?,
    livePlayer: ExoPlayer?,
    ui: PlayerUiState,
    gestures: GestureUiState,
    quick: QuickRowUiState,
    scope: CoroutineScope,
    decoderModeLabel: State<String>,
    isRebuildingDecoder: State<Boolean>,
    onRebuildDecoderCycle: () -> Unit,
    onApplyEngineSubtitleOffsetSec: (Float) -> Unit,
    onTriggerEngineAutoSync: () -> Unit,
    onCaptureVideoFrame: () -> Unit,
    onPersistSpeed: (Float) -> Unit = {},
    onSelectDecoderModeHost: (Int) -> Unit = {},
    onOpenEqualizerSheetHost: () -> Unit = {},
    onOpenCastPickerHost: () -> Unit = {},
    onOpenOnlineSubtitlesHost: () -> Unit = {},
    onCycleAbRepeatHost: () -> Unit = {},
    onSelectSleepTimerHost: (Int) -> Unit = {},
    onToggleBackgroundPlayHost: (Boolean) -> Unit = {},
    onToggleAudioEffectHost: (Boolean) -> Unit = {},
    onSetAutoSyncEnabledHost: (Boolean) -> Unit = {},
    onUpdatePlaylistModeHost: (Boolean, RepeatLoopMode) -> Unit = { _, _ -> },
    onSetRotateModeHost: (RotateMode) -> Unit = {},
    onOpenFullSettingsHost: () -> Unit = {},
    onReorderEpisodesHost: (List<String>) -> Unit = {},
    onRemoveEpisodeHost: (String) -> Unit = {},
): PlayerScreenActions = remember(
    livePlayer,
    ui,
    gestures,
    quick,
    scope,
    decoderModeLabel,
    isRebuildingDecoder,
) {
    PlayerScreenActions(
        context = context,
        activity = activity,
        livePlayer = livePlayer,
        ui = ui,
        gestures = gestures,
        quick = quick,
        scope = scope,
        decoderModeLabel = decoderModeLabel,
        isRebuildingDecoder = isRebuildingDecoder,
        onRebuildDecoderCycle = onRebuildDecoderCycle,
        onApplyEngineSubtitleOffsetSec = onApplyEngineSubtitleOffsetSec,
        onTriggerEngineAutoSync = onTriggerEngineAutoSync,
        onCaptureVideoFrame = onCaptureVideoFrame,
        onPersistSpeed = onPersistSpeed,
        onSelectDecoderModeHost = onSelectDecoderModeHost,
        onOpenEqualizerSheetHost = onOpenEqualizerSheetHost,
        onOpenCastPickerHost = onOpenCastPickerHost,
        onOpenOnlineSubtitlesHost = onOpenOnlineSubtitlesHost,
        onCycleAbRepeatHost = onCycleAbRepeatHost,
        onSelectSleepTimerHost = onSelectSleepTimerHost,
        onToggleBackgroundPlayHost = onToggleBackgroundPlayHost,
        onToggleAudioEffectHost = onToggleAudioEffectHost,
        onSetAutoSyncEnabledHost = onSetAutoSyncEnabledHost,
        onUpdatePlaylistModeHost = onUpdatePlaylistModeHost,
        onSetRotateModeHost = onSetRotateModeHost,
        onOpenFullSettingsHost = onOpenFullSettingsHost,
        onReorderEpisodesHost = onReorderEpisodesHost,
        onRemoveEpisodeHost = onRemoveEpisodeHost,
    )
}
