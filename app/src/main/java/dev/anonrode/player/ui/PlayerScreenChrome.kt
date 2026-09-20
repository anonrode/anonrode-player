package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs

/* ── Single-Plane Chrome — v0.9 ────────────────────────────────────────────
 * The v0.9 chrome layer: ONE top row, ONE bottom zone
 * (status strip → seek → pure transport → always-visible tool rail).
 *
 * Replaces [PlayerControlsOverlay] / [PlayerScreenTopBar] and the
 * transport+utility half of [PlayerScreenBottomBar]. Those composables are
 * left in place and simply unreferenced, so reverting is one call site.
 *
 * Every API here was read before use — no invented symbols.
 * ------------------------------------------------------------------------- */

/** Single horizontal row: back · title · CC · audio · ⋮. No second row. */
@UnstableApi
@Composable
internal fun V9TopBar(
    title: String,
    accent: Color,
    actions: PlayerScreenActions,
    hasSubtitleTrack: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onMore: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(
                start = PlayerDimens.gapSm,
                end = PlayerDimens.gapSm,
                top = PlayerDimens.gapXs,
                bottom = PlayerDimens.gapSm,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlChip(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            accent = accent,
            onClick = onBack,
        )
        Text(
            text = title.substringAfterLast('/'),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = PlayerDimens.gapXs),
        )
        if (hasSubtitleTrack) {
            ControlChip(
                icon = Icons.Filled.ClosedCaption,
                contentDescription = "Toggle subtitles",
                accent = accent,
                selected = actions.ui.showCC.value,
                onClick = { actions.toggleShowCC() },
            )
        }
        ControlChip(
            icon = Icons.Filled.MusicNote,
            contentDescription = "Audio track",
            accent = accent,
            onClick = { actions.pickAudioTrack() },
        )
        ControlChip(
            icon = Icons.Filled.MoreVert,
            contentDescription = "Control center",
            accent = accent,
            onClick = onMore,
        )
    }
}

/**
 * Bottom zone: status strip → seek → pure transport → always-visible rail.
 * Publishes its measured height into [PlayerUiState.bottomBarHeightPx] the
 * same way the old block did, so the Up-Next pill and the sync popover keep
 * anchoring correctly instead of drifting.
 */
@UnstableApi
@Composable
internal fun V9BottomZone(
    visible: Boolean,
    modifier: Modifier = Modifier,
    accent: Color,
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    localSeek: MutableFloatState,
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    liveOffsetMs: Long,
    hasSubtitleTrack: Boolean,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    actions: PlayerScreenActions,
) {
    var showSpeedStrip by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = PlayerDimens.gapSm)
            .padding(bottom = PlayerDimens.gapXs)
            .onSizeChanged {
                // Anchoring contract: Up Next + the sync popover ride off this.
                if (it.height > 0) actions.ui.bottomBarHeightPx.intValue = it.height
            },
    ) {
        // Zone 2 — information, not buttons.
        if (!actions.ui.locked.value && !actions.gestures.subDragging.value) {
            StatusStrip(
                accent = accent,
                syncEnabled = actions.quick.subSyncEnabled.value,
                syncRunning = actions.quick.subSyncRunning.value,
                offsetMs = liveOffsetMs,
                speed = actions.speeds.getOrElse(actions.speedIdx.intValue) { 1f },
                positionSec = positionSec.value,
                durationSec = durationSec.value,
                onTapSync = { actions.openSyncPopover() },
            )
        }

        // Seek row — always visible while unlocked (unchanged geometry).
        SeekBarRow(
            accent = accent,
            positionSec = positionSec,
            durationSec = durationSec,
            bufferedSec = bufferedSec,
            localSeek = localSeek,
            pendingSeekMs = actions.gestures.pendingSeekMs,
            scrubPreview = actions.ui.scrubPreview,
            onSeekCommitted = { sec -> actions.livePlayer.seekTo((sec * 1000).toLong()) },
        )

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(220)) +
                slideInVertically(animationSpec = tween(220)) { it / 2 },
            exit = fadeOut(animationSpec = tween(220)) +
                slideOutVertically(animationSpec = tween(220)) { it / 2 },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (showSpeedStrip) {
                    SpeedSelectorRow(
                        speeds = actions.speeds,
                        selectedSpeed = actions.speeds[actions.speedIdx.intValue],
                        accent = accent,
                        onSelectSpeed = { sp ->
                            actions.setSpeed(sp)
                            showSpeedStrip = false
                        },
                    )
                    Spacer(Modifier.height(PlayerDimens.gapXs))
                }
                Spacer(Modifier.height(PlayerDimens.gapSm))
                V9TransportRow(
                    accent = accent,
                    actions = actions,
                    isPlaying = isPlaying,
                    hasPreviousEpisode = hasPreviousEpisode,
                    hasNextEpisode = hasNextEpisode,
                    seekIncrementSec = seekIncrementSec,
                    onPlayPrevious = onPlayPrevious,
                    onPlayNext = onPlayNext,
                )
                Spacer(Modifier.height(PlayerDimens.gapXs))
                V9ToolRail(
                    accent = accent,
                    actions = actions,
                    hasSubtitleTrack = hasSubtitleTrack,
                    showSpeedStrip = showSpeedStrip,
                    onToggleSpeedStrip = { showSpeedStrip = !showSpeedStrip },
                    liveOffsetMs = liveOffsetMs,
                )
            }
        }
    }
}

/** Pure transport: ±N · ⏮ · ▶/⏸ · ⏭ · N→ — nothing else in the row. */
@UnstableApi
@Composable
internal fun V9TransportRow(
    accent: Color,
    actions: PlayerScreenActions,
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TimeSeekButton(
            direction = TimeSeekDirection.BACK,
            seconds = seekIncrementSec,
            accent = accent,
            onClick = { actions.seekBy(-seekIncrementSec) },
        )
        Spacer(Modifier.width(PlayerDimens.gapXs))
        EpisodeJumpButton(
            direction = EpisodeJumpDirection.PREVIOUS,
            enabled = hasPreviousEpisode,
            onClick = onPlayPrevious,
        )
        Spacer(Modifier.width(PlayerDimens.gapMd))
        BigPlayPauseButton(
            isPlaying = isPlaying,
            accent = accent,
            onClick = { actions.togglePlayPause() },
        )
        Spacer(Modifier.width(PlayerDimens.gapMd))
        EpisodeJumpButton(
            direction = EpisodeJumpDirection.NEXT,
            enabled = hasNextEpisode,
            onClick = onPlayNext,
        )
        Spacer(Modifier.width(PlayerDimens.gapXs))
        TimeSeekButton(
            direction = TimeSeekDirection.FORWARD,
            seconds = seekIncrementSec,
            accent = accent,
            onClick = { actions.seekBy(seekIncrementSec) },
        )
    }
}

/**
 * The rail: Lock · Sync · Equalizer · Style · Rotate · Speed · Aspect · PiP ·
 * Capture · Cast. Always visible and horizontally scrollable — it replaces
 * BOTH the old Row-2 ribbon and the redundant bottom dock, so every control
 * has exactly one home. No collapse chevron: nothing hides behind a toggle.
 */
@UnstableApi
@Composable
internal fun V9ToolRail(
    accent: Color,
    actions: PlayerScreenActions,
    hasSubtitleTrack: Boolean,
    showSpeedStrip: Boolean,
    onToggleSpeedStrip: () -> Unit,
    liveOffsetMs: Long,
    modifier: Modifier = Modifier,
) {
    var aspectMenu by remember { mutableStateOf(false) }
    val speed = actions.speeds.getOrElse(actions.speedIdx.intValue) { 1f }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PlayerDimens.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlChip(
            icon = if (actions.ui.locked.value) Icons.Filled.Lock
            else Icons.Filled.LockOpen,
            contentDescription = if (actions.ui.locked.value) "Controls locked"
            else "Lock controls",
            accent = accent,
            selected = actions.ui.locked.value,
            onClick = { actions.lockControls() },
        )
        PlayerSubSyncToggle(
            enabled = actions.quick.subSyncEnabled.value,
            running = actions.quick.subSyncRunning.value,
            offsetMs = liveOffsetMs,
            accent = accent,
            onEnable = { actions.setSubSyncEnabled(true) },
            onOpenPopover = { actions.openSyncPopover() },
            onResync = { actions.resyncNow() },
            compact = true,
        )
        ControlChip(
            icon = Icons.Filled.Equalizer,
            contentDescription = "Equalizer",
            accent = accent,
            selected = actions.quick.equalizerOn.value,
            onClick = { actions.toggleEqualizer() },
        )
        if (hasSubtitleTrack) {
            ControlChip(
                icon = Icons.Filled.ClosedCaption,
                contentDescription = "Subtitle style",
                accent = accent,
                selected = actions.quick.showStyleTray.value,
                onClick = {
                    if (actions.quick.showStyleTray.value) actions.closeStyleTray()
                    else actions.openStyleTray()
                },
            )
        }
        PlayerScreenRotateButton(
            mode = actions.quick.rotateMode.value,
            accent = accent,
            onCycle = { actions.cycleRotateMode() },
            onSetMode = { actions.setRotateMode(it) },
        )
        TextPill(
            text = speedLabel(speed),
            accent = accent,
            onClick = onToggleSpeedStrip,
            selected = abs(speed - 1f) >= 0.05f || showSpeedStrip,
            onLongClick = { actions.setSpeed(1f) },
        )
        Box {
            ControlChip(
                icon = Icons.Filled.AspectRatio,
                contentDescription = "Aspect ratio",
                accent = accent,
                onClick = {
                    actions.setZoom((actions.ui.zoomIdx.intValue + 1) % ZoomModes.size)
                },
                onLongClick = { aspectMenu = true },
            )
            DropdownMenu(
                expanded = aspectMenu,
                onDismissRequest = { aspectMenu = false },
                containerColor = OverlayPanelBg,
            ) {
                ZoomModes.forEachIndexed { idx, mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                mode.abbreviation,
                                color = if (idx == actions.ui.zoomIdx.intValue) accent
                                else Color.White,
                            )
                        },
                        leadingIcon = {
                            if (idx == actions.ui.zoomIdx.intValue) {
                                Icon(
                                    Icons.Filled.Check,
                                    null,
                                    tint = accent,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        onClick = {
                            aspectMenu = false
                            actions.setZoom(idx)
                        },
                    )
                }
            }
        }
        ControlChip(
            icon = Icons.Filled.PictureInPictureAlt,
            contentDescription = "Picture-in-picture",
            accent = accent,
            onClick = { actions.enterPip() },
        )
        ControlChip(
            icon = Icons.Filled.PhotoCamera,
            contentDescription = "Capture frame",
            accent = accent,
            onClick = { actions.captureFrame() },
        )
        ControlChip(
            icon = Icons.Filled.Cast,
            contentDescription = "Cast",
            accent = accent,
            onClick = { actions.openCastPicker() },
        )
    }
}

/**
 * v0.9 chrome overlay. Same parameter list as [PlayerControlsOverlay] so the
 * swap in PlayerScreen is a single word.
 */
@UnstableApi
@Composable
internal fun V9ControlsOverlay(
    visible: Boolean,
    showSeekBar: Boolean = true,
    title: String,
    accent: Color,
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    localSeek: MutableFloatState,
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    hasSubtitleTrack: Boolean,
    liveOffsetMs: Long,
    actions: PlayerScreenActions,
    onBack: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onMore: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(220)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            V9TopBar(
                title = title,
                accent = accent,
                actions = actions,
                hasSubtitleTrack = hasSubtitleTrack,
                onBack = onBack,
                onMore = onMore,
            )
        }
        if (showSeekBar) {
            V9BottomZone(
                visible = visible,
                modifier = Modifier.align(Alignment.BottomCenter),
                accent = accent,
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                localSeek = localSeek,
                isPlaying = isPlaying,
                hasPreviousEpisode = hasPreviousEpisode,
                hasNextEpisode = hasNextEpisode,
                seekIncrementSec = seekIncrementSec,
                liveOffsetMs = liveOffsetMs,
                hasSubtitleTrack = hasSubtitleTrack,
                onPlayPrevious = onPlayPrevious,
                onPlayNext = onPlayNext,
                actions = actions,
            )
        }
    }
}




