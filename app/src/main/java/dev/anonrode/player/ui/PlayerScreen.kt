package dev.anonrode.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Four-Slot Screen Architecture (`AGENT.md` §2.2).
 */
@Composable
fun PlayerScreenLayout(
    videoSurfaceSlot: @Composable BoxScope.() -> Unit,
    gestureOverlaySlot: @Composable BoxScope.() -> Unit,
    hudControlsSlot: @Composable BoxScope.() -> Unit,
    sideDrawerSlot: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        videoSurfaceSlot()      // Layer 0: SurfaceView decoder, poster art cache, night mode scrim
        gestureOverlaySlot()    // Layer 1: Gesture layer, ripples, vertical pills, lock scrim, subtitle overlay
        hudControlsSlot()       // Layer 2: Top bar, quick ribbon, scrubber dock, transport, floating speed bar
        sideDrawerSlot()        // Layer 3: Full-height flat right-aligned drawers
    }
}

/**
 * Top-Level Orchestrator.
 * ZERO FULL-HUD RECOMPOSITION INVARIANT:
 * [positionSec], [durationSec], and [bufferedSec] are passed as [State<Float>] references and are
 * NEVER read inside [PlayerScreen] or [hudControlsSlot].
 */
@Composable
fun PlayerScreen(
    title: String,
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    cueText: State<String>,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    chapters: List<ChapterMarker>,
    subtitleTracks: List<SubtitleTrackItem>,
    selectedSubtitleTrackId: String?,
    episodes: List<PlaylistEpisodeItem>,
    scrubPreviewBitmap: Bitmap?,
    isPipMode: Boolean,
    ui: PlayerUiState,
    gestures: GestureUiState,
    quick: QuickRowUiState,
    actions: PlayerScreenActions,
    videoSurfaceContent: @Composable BoxScope.() -> Unit,
    onNavigateBack: () -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onOpenLocalSubtitlePicker: () -> Unit,
    onSelectEpisode: (PlaylistEpisodeItem) -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onEnterPip: () -> Unit,
    mediaId: String = "",
    autoHideTimeoutMs: Long = 5500L,
    pinchZoomEnabled: Boolean = true,
    doubleTapSeekEnabled: Boolean = true,
    swipeToSeekEnabled: Boolean = true,
    volumeGestureEnabled: Boolean = true,
    brightnessGestureEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val anyDrawerOrSheetOpen = ui.playlistDrawerOpen.value ||
        ui.subtitleDrawerOpen.value ||
        ui.threeDotsMenuOpen.value ||
        ui.speedCardOpen.value ||
        quick.showRibbonCustomise.value

    // Auto-Hide Timer reset by userInteractionKey (paused while any drawer/speed bar/scrub is open)
    LaunchedEffect(
        ui.controlsVisible.value,
        ui.isPlaying.value,
        ui.userInteractionKey.longValue,
        ui.localSeek.floatValue >= 0f,
        anyDrawerOrSheetOpen,
        autoHideTimeoutMs,
    ) {
        if (ui.controlsVisible.value &&
            ui.isPlaying.value &&
            ui.localSeek.floatValue < 0f &&
            !anyDrawerOrSheetOpen
        ) {
            delay(autoHideTimeoutMs.coerceAtLeast(2500L))
            ui.controlsVisible.value = false
            quick.showSyncPopover.value = false
        }
    }

    // Reset double-tap ripple indicator after 450ms
    LaunchedEffect(gestures.doubleTapAccumSec.intValue) {
        if (gestures.doubleTapSide.intValue != 0) {
            delay(450L)
            gestures.doubleTapSide.intValue = 0
            gestures.doubleTapAccumSec.intValue = 0
        }
    }

    // 5.0s Resume Prompt, 2.5s Sync Toast & 1.1s Aspect Badge Auto-Dismiss Timers
    TransientStatusPillTimers(ui = ui)

    PlayerScreenLayout(
        modifier = modifier,
        // =====================================================================
        // LAYER 0: VIDEO SURFACE + EYE-COMFORT NIGHT SCRIM
        // =====================================================================
        videoSurfaceSlot = {
            videoSurfaceContent()
            if (ui.nightMode.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFF7314).copy(alpha = 0.22f))
                )
            }
        },
        // =====================================================================
        // LAYER 1: GESTURE DETECTOR, RIPPLES, EDGE PILLS, SUBTITLE OVERLAY
        // =====================================================================
        gestureOverlaySlot = {
            // 1A. Base Gesture Interception Layer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerGestureLayer(
                        ui = ui,
                        gestures = gestures,
                        durationSecProvider = { durationSec.value },
                        currentPositionMsProvider = { positionSec.value * 1000f },
                        isPipMode = isPipMode,
                        pinchZoomEnabled = pinchZoomEnabled,
                        doubleTapSeekEnabled = doubleTapSeekEnabled,
                        swipeToSeekEnabled = swipeToSeekEnabled,
                        volumeGestureEnabled = volumeGestureEnabled,
                        brightnessGestureEnabled = brightnessGestureEnabled,
                        onToggleControls = { ui.controlsVisible.value = !ui.controlsVisible.value },
                        onDoubleTapSeek = { deltaSec, _ -> actions.onStepSeekSec(deltaSec) },
                        onPlayPauseToggle = actions::onPlayPauseToggle,
                        onPreviewKeyFrameSeekMs = actions::onPreviewKeyFrameSeekMs,
                        onCommitExactSeekMs = actions::onCommitExactSeekMs,
                        onCancelKeyframeSeek = actions::onCancelKeyframeSeek,
                        onApplyVolumeBoostPercent = actions::onApplyVolumeBoostPercent,
                        onApplyBrightnessFraction = actions::onApplyBrightnessFraction,
                        onSetPlaybackSpeed = { spd -> actions.onSetPlaybackSpeed(spd, persist = false) },
                        onCycleZoomMode = {
                            ui.zoomIdx.intValue = (ui.zoomIdx.intValue + 1) % AspectZoomMode.entries.size
                        },
                    )
            )

            // 1B. Double-Tap Seek Ripple Arcs (Left <35% / Right >65%)
            SeekRippleOverlay(gestures = gestures)

            // 1C. Edge-Hugged Vertical Brightness Pill (Left) & 200% Volume+Boost Pill (Right)
            VerticalGestureHudPill(
                visible = gestures.mode.value == GestureMode.BRIGHTNESS,
                isVolume = false,
                percentage = (ui.screenBrightnessFraction.floatValue * 100f).toInt(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp),
            )
            VerticalGestureHudPill(
                visible = gestures.mode.value == GestureMode.VOLUME,
                isVolume = true,
                percentage = ui.volumeBoostPercent.intValue,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp),
            )

            // 1D. Top-Center Hold-to-Boost Milestone Capsule, Seek Readout & Aspect Badge
            CenterGestureHudPill(
                ui = ui,
                gestures = gestures,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 18.dp),
            )

            // 1E. CRITICAL HIT-TEST INVARIANT: Subtitle Overlay sits ABOVE playerGestureLayer
            PlayerSubtitleOverlay(
                cueText = cueText,
                isPipMode = isPipMode,
                ui = ui,
                gestures = gestures,
                onUpdateSubOffsetSec = actions::onApplySubtitleOffsetSec,
                mediaId = mediaId,
            )

            // 1F. Screen Lock Unlock Pill (Clean vector Lock icon, zero emoji)
            AnimatedVisibility(
                visible = ui.locked.value && ui.lockScrimVisible.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp),
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xD80A0A0C))
                        .border(1.dp, OpticalGlassTokens.EmeraldAccent.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .clickable {
                            ui.locked.value = false
                            ui.lockScrimVisible.value = false
                            ui.controlsVisible.value = true
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = "Unlock",
                        tint = OpticalGlassTokens.EmeraldSync,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Locked · Tap to Unlock",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        },
        // =====================================================================
        // LAYER 2: HUD CHROME (TOP BAR + BOTTOM DOCK + FLOATING BOTTOM SPEED BAR)
        // =====================================================================
        hudControlsSlot = {
            PlayerScreenTopBar(
                title = title,
                videoResolutionFpsLabel = videoResolutionFpsLabel,
                decoderModeLabel = decoderModeLabel,
                liveOffsetMs = liveOffsetMs,
                episodeCount = episodes.size,
                ui = ui,
                quick = quick,
                actions = actions,
                onNavigateBack = onNavigateBack,
                onCycleDecoderMode = actions::onCycleDecoderMode,
                onNudgeSubtitleOffsetSec = { delta ->
                    actions.onApplySubtitleOffsetSec(ui.subtitleOffsetSec.floatValue + delta)
                },
                onResetSubtitleOffset = {
                    ui.subtitleBaseOffsetSec.floatValue = 0f
                    actions.onApplySubtitleOffsetSec(0f)
                },
                onOpenLocalSubtitlePicker = onOpenLocalSubtitlePicker,
                onCaptureScreenshot = actions::onCaptureScreenshot,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            PlayerScreenBottomBar(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                chapters = chapters,
                scrubPreviewBitmap = scrubPreviewBitmap,
                ui = ui,
                quick = quick,
                onPreviewScrubSec = { sec -> actions.onPreviewKeyFrameSeekMs((sec * 1000f).toLong()) },
                onCommitScrubSec = { sec -> actions.onCommitExactSeekMs((sec * 1000f).toLong()) },
                onStepSeekSec = actions::onStepSeekSec,
                onPlayPauseToggle = actions::onPlayPauseToggle,
                onPreviousEpisode = onPreviousEpisode,
                onNextEpisode = onNextEpisode,
                onCycleAspectRatio = {
                    ui.zoomIdx.intValue = (ui.zoomIdx.intValue + 1) % AspectZoomMode.entries.size
                    ui.aspectBadgeVisible.value = true
                },
                onToggleRotateMode = {
                    val modes = RotateMode.entries
                    val next = modes[(quick.rotateMode.value.ordinal + 1) % modes.size]
                    actions.onSetRotateMode(next)
                },
                onStartOverFromZero = { actions.onCommitExactSeekMs(0L) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            // Floating Bottom Precision Speed Bar
            PrecisionSpeedCard(
                visible = ui.speedCardOpen.value,
                ui = ui,
                onApplySpeed = { spd -> actions.onSetPlaybackSpeed(spd, persist = true) },
                onDismiss = { ui.speedCardOpen.value = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp),
            )
        },
        // =====================================================================
        // LAYER 3: FULL-HEIGHT FLAT RIGHT-ALIGNED DRAWERS
        // =====================================================================
        sideDrawerSlot = {
            PlayerSubtitleDrawer(
                visible = ui.subtitleDrawerOpen.value,
                tracks = subtitleTracks,
                selectedTrackId = selectedSubtitleTrackId,
                ui = ui,
                gestures = gestures,
                actions = actions,
                onSelectTrack = onSelectSubtitleTrack,
                onOpenLocalFilePicker = onOpenLocalSubtitlePicker,
                onTriggerAutoSync = actions::onTriggerAutoSync,
                onApplySubtitleOffsetSec = actions::onApplySubtitleOffsetSec,
                onDismiss = { ui.subtitleDrawerOpen.value = false },
            )

            PlayerPlaylistDrawer(
                visible = ui.playlistDrawerOpen.value,
                episodes = episodes,
                seriesTitle = title.substringBeforeLast('.').take(22).ifBlank { "Episodes" },
                actions = actions,
                onSelectEpisode = onSelectEpisode,
                onDismiss = { ui.playlistDrawerOpen.value = false },
            )

            PlayerSettingsDrawer(
                visible = ui.threeDotsMenuOpen.value,
                ui = ui,
                quick = quick,
                actions = actions,
                onApplySpeed = { spd -> actions.onSetPlaybackSpeed(spd, persist = true) },
                onOpenAudioTracks = onOpenAudioTracks,
                onEnterPip = onEnterPip,
                onCaptureScreenshot = actions::onCaptureScreenshot,
                onDismiss = { ui.threeDotsMenuOpen.value = false },
            )

            PlayerRibbonCustomiseDrawer(
                visible = quick.showRibbonCustomise.value,
                quick = quick,
                onDismiss = { quick.showRibbonCustomise.value = false },
            )
        },
    )
}
