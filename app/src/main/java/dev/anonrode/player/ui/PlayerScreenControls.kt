package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi

/* ── Player controls chrome (v0.7.3 curated dock) ─────────────────────────
 *
 *   Top bar (auto-hide)   ‹  Title                    ⧉  🔒  ⋮
 *   Right-edge rail       GONE — its five slots moved into the dock's
 *                         utility row / the sheet; "more" has one home.
 *   Bottom block          seek (ALWAYS) / transport / utility
 *
 * Refactor (v0.7.3):
 *   • The top bar carries only chrome-level actions: back, PiP, the
 *     controls-lock, and the single ⋮ "more" (the rail's duplicate is
 *     deleted with the rail). Lock/PiP moved here FROM the transport row,
 *     which let the dock rows shrink below portrait widths.
 *   • Insets are real now (statusBarsPadding / navigationBarsPadding /
 *     displayCutoutPadding) — the build is edge-to-edge on targetSdk 37
 *     and the chrome previously sat under the status bar / nav bar /
 *     landscape cutout with hand-tuned dp offsets everywhere.
 *   • Each bar reports its measured height (never shrinking to 0) into
 *     PlayerUiState; overlays anchor off those numbers instead of the old
 *     magic `top = 70 / bottom = 140 / 210` dp couplings.
 * ------------------------------------------------------------------------- */

/** Whole top chrome: the fading top bar + the bottom dock. */
@UnstableApi
@Composable
internal fun PlayerControlsOverlay(
    visible: Boolean,
    showSeekBar: Boolean = true,
    title: String,
    accent: Color,
    /** State-wrapped (v0.7.1 perf pass) — see PlayerScreen's param docs. */
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
    // The seek bar is ALWAYS visible while unlocked (v0.6.1 behaviour the
    // user asked for): only the top bar + transport/utility rows fade.
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(220)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerScreenTopBar(
                title = title,
                accent = accent,
                actions = actions,
                hasSubtitleTrack = hasSubtitleTrack,
                liveOffsetMs = liveOffsetMs,
                onBack = onBack,
                onMore = onMore,
            )
        }
        if (showSeekBar) {
            PlayerScreenBottomBar(
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
                hasSubtitleTrack = hasSubtitleTrack,
                liveOffsetMs = liveOffsetMs,
                onPlayPrevious = onPlayPrevious,
                onPlayNext = onPlayNext,
                actions = actions,
            )
        }
    }
}

/** Top bar — Row 1: back · title · audio · CC · HW/SW · ⋮; Row 2: collapsible scrollable tools ribbon. */
@UnstableApi
@Composable
internal fun PlayerScreenTopBar(
    modifier: Modifier = Modifier,
    title: String,
    accent: Color,
    actions: PlayerScreenActions,
    hasSubtitleTrack: Boolean,
    liveOffsetMs: Long,
    onBack: () -> Unit,
    onMore: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Scrim before insets → the gradient reaches the physical top
            // edge; content sits below the status bar / cutout.
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .displayCutoutPadding()
            .padding(
                horizontal = PlayerDimens.gapSm,
                vertical = PlayerDimens.gapXs,
            )
            .onSizeChanged {
                if (it.height > 0) actions.ui.topBarHeightPx.intValue = it.height
            },
    ) {
        // ── Row 1: Primary header controls ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Text(
                title,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = PlayerDimens.gapXs),
            )
            ControlChip(
                icon = Icons.Filled.MusicNote,
                contentDescription = "Audio track",
                accent = accent,
                onClick = { actions.pickAudioTrack() },
            )
            if (hasSubtitleTrack) {
                ControlChip(
                    icon = Icons.Filled.ClosedCaption,
                    contentDescription = if (actions.ui.showCC.value) "Subtitles on" else "Subtitles off",
                    accent = accent,
                    selected = actions.ui.showCC.value,
                    onClick = { actions.toggleShowCC() },
                )
            }
            TextPill(
                text = if (actions.quick.hwDecoder.value) "HW" else "SW",
                accent = accent,
                selected = actions.quick.hwDecoder.value,
                onClick = { actions.toggleHwDecoder() },
            )
            ControlChip(
                icon = Icons.Filled.MoreVert,
                contentDescription = "More options",
                accent = accent,
                onClick = onMore,
            )
        }

        // ── Row 2: Horizontally scrollable and collapsible Quick Ribbon ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(
                // v0.9: always visible. The collapse chevron is removed — it
                // was a 36dp hit target (under PlayerDimens.touchMin) with a
                // dimmed 0.75-alpha tint, so the control that REVEALED the
                // tools rendered smaller and more "disabled" than the tools it
                // revealed. The rail scrolls instead; nothing hides.
                visible = true,
                modifier = Modifier.weight(1f),
                enter = fadeIn(tween(180)) + expandHorizontally(tween(220)),
                exit = fadeOut(tween(180)) + shrinkHorizontally(tween(220)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(PlayerDimens.gapXs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // v0.9: Lock lives here now — the transport row is pure
                    // transport, and this rail is always visible so the door
                    // in/out of lock mode is never hidden behind a toggle.
                    ControlChip(
                        icon = if (actions.ui.locked.value) Icons.Filled.Lock
                        else Icons.Filled.LockOpen,
                        contentDescription = if (actions.ui.locked.value) {
                            "Controls locked"
                        } else {
                            "Lock controls"
                        },
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
                    // v0.9: subtitle style tunes in place — this is its one
                    // always-visible entry point (the tray replaces the host's
                    // modal sheet, so the cue stays on screen while tuned).
                    ControlChip(
                        icon = Icons.Filled.ClosedCaption,
                        contentDescription = "Subtitle style",
                        accent = accent,
                        selected = actions.quick.showStyleTray.value,
                        onClick = {
                            if (actions.quick.showStyleTray.value) {
                                actions.closeStyleTray()
                            } else {
                                actions.openStyleTray()
                            }
                        },
                    )
                    PlayerScreenRotateButton(
                        mode = actions.quick.rotateMode.value,
                        accent = accent,
                        onCycle = { actions.cycleRotateMode() },
                        onSetMode = { actions.setRotateMode(it) },
                    )
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
        }
    }
}
