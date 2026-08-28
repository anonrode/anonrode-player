package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi

/* ── The controls chrome: top bar + quick row + bottom bar (seekbar and
 * transport dock). Rendered inside PlayerScreen's AnimatedVisibility so the
 * whole chrome fades as one; [modifier] carries the BoxScope alignment.
 * ------------------------------------------------------------------------- */

/**
 * The whole controls chrome behind one fade: top bar + quick row anchored
 * top, seekbar + transport dock anchored bottom. [visible] already encodes
 * the PiP / lock gates (computed by the caller).
 */
@UnstableApi
@Composable
internal fun PlayerControlsOverlay(
    visible: Boolean,
    title: String,
    accent: Color,
    showCC: Boolean,
    hwDecoder: Boolean,
    isRebuildingDecoder: Boolean,
    menuOpen: Boolean,
    zoomAbbreviation: String,
    portraitForced: Boolean,
    abStartMs: Long?,
    abEndMs: Long?,
    volumeBoostPct: Int,
    sleep: SleepTimerUiState,
    equalizerOn: Boolean,
    headphonesOn: Boolean,
    castRouteName: String?,
    showSyncPopover: Boolean,
    speedLabelText: String,
    currentPositionMs: Long,
    positionSec: Float,
    durationSec: Float,
    localSeek: MutableFloatState,
    isPlaying: Boolean,
    locked: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    actions: PlayerScreenActions,
    onBack: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenSubtitlePicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onAbRepeatTap: () -> Unit,
    onVolumeBoostCycle: () -> Unit,
    onOpenEqPanel: () -> Unit,
) {
    // AnimatedVisibility gives the chrome a short dim/undim fade instead
    // of a hard pop; the inner Box re-provides the BoxScope the two
    // anchored columns (top bar / bottom dock) align against.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(220)),
    ) {
        Box(Modifier.fillMaxSize()) {
            PlayerTopBar(
                modifier = Modifier.align(Alignment.TopCenter),
                title = title,
                accent = accent,
                showCC = showCC,
                hwDecoder = hwDecoder,
                isRebuildingDecoder = isRebuildingDecoder,
                menuOpen = menuOpen,
                zoomAbbreviation = zoomAbbreviation,
                portraitForced = portraitForced,
                abStartMs = abStartMs,
                abEndMs = abEndMs,
                volumeBoostPct = volumeBoostPct,
                sleep = sleep,
                equalizerOn = equalizerOn,
                headphonesOn = headphonesOn,
                castRouteName = castRouteName,
                showSyncPopover = showSyncPopover,
                speedLabelText = speedLabelText,
                actions = actions,
                onBack = onBack,
                onOpenSubtitlePicker = onOpenSubtitlePicker,
                onOpenSettings = onOpenSettings,
                onAbRepeatTap = onAbRepeatTap,
                onVolumeBoostCycle = onVolumeBoostCycle,
                onOpenEqPanel = onOpenEqPanel,
            )
            PlayerBottomBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                accent = accent,
                currentPositionMs = currentPositionMs,
                positionSec = positionSec,
                durationSec = durationSec,
                localSeek = localSeek,
                isPlaying = isPlaying,
                locked = locked,
                hasPreviousEpisode = hasPreviousEpisode,
                hasNextEpisode = hasNextEpisode,
                seekIncrementSec = seekIncrementSec,
                onPlayPrevious = onPlayPrevious,
                onPlayNext = onPlayNext,
                onEnterPip = onEnterPip,
                actions = actions,
            )
        }
    }
}

/** Top row (back, title, audio/CC/HW/overflow) + quick row over a gradient scrim. */
@UnstableApi
@Composable
internal fun PlayerTopBar(
    modifier: Modifier = Modifier,
    title: String,
    accent: Color,
    showCC: Boolean,
    hwDecoder: Boolean,
    isRebuildingDecoder: Boolean,
    menuOpen: Boolean,
    zoomAbbreviation: String,
    portraitForced: Boolean,
    abStartMs: Long?,
    abEndMs: Long?,
    volumeBoostPct: Int,
    sleep: SleepTimerUiState,
    equalizerOn: Boolean,
    headphonesOn: Boolean,
    castRouteName: String?,
    showSyncPopover: Boolean,
    speedLabelText: String,
    actions: PlayerScreenActions,
    onBack: () -> Unit,
    onOpenSubtitlePicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onAbRepeatTap: () -> Unit,
    onVolumeBoostCycle: () -> Unit,
    onOpenEqPanel: () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)))
            .padding(bottom = 18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back", tint = Color.White)
            }
            Text(
                title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                    .clickable { actions.openSyncPopover() }
            )
            IconButton(onClick = { actions.pickAudioTrack() }) {
                Icon(Icons.Filled.MusicNote,
                    contentDescription = "Audio track", tint = Color.White)
            }
            IconButton(onClick = { actions.toggleShowCC() }) {
                Icon(
                    Icons.Filled.ClosedCaption,
                    contentDescription = if (showCC) "Subtitles on" else "Subtitles off",
                    tint = if (showCC) accent else Color.White.copy(alpha = 0.6f)
                )
            }
            Box(
                modifier = Modifier
                    .size(width = 44.dp, height = 40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !isRebuildingDecoder) { actions.toggleHwDecoder() }
                    .border(
                        width = 1.dp,
                        color = when {
                            isRebuildingDecoder -> Color.White.copy(alpha = 0.25f)
                            hwDecoder -> accent
                            else -> Color.White.copy(alpha = 0.4f)
                        },
                        shape = RoundedCornerShape(8.dp),
                    )
                    .background(
                        when {
                            isRebuildingDecoder -> Color.White.copy(alpha = 0.05f)
                            hwDecoder -> accent.copy(alpha = 0.18f)
                            else -> Color.Transparent
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    when {
                        isRebuildingDecoder -> "…"
                        hwDecoder -> "HW"
                        else -> "SW"
                    },
                    color = when {
                        isRebuildingDecoder -> Color.White.copy(alpha = 0.6f)
                        hwDecoder -> accent
                        else -> Color.White
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Box {
                IconButton(onClick = { actions.toggleMenu() }) {
                    Icon(Icons.Filled.MoreVert,
                        contentDescription = "More options", tint = Color.White)
                }
                // ── overflow menu: subtitles / sleep / aspect / lock / settings ──
                PlayerOverflowMenu(
                    expanded = menuOpen,
                    accent = accent,
                    showCC = showCC,
                    zoomAbbreviation = zoomAbbreviation,
                    portraitForced = portraitForced,
                    abStartMs = abStartMs,
                    abEndMs = abEndMs,
                    volumeBoostPct = volumeBoostPct,
                    sleep = sleep,
                    actions = actions,
                    onOpenSubtitlePicker = onOpenSubtitlePicker,
                    onOpenSettings = onOpenSettings,
                    onAbRepeatTap = onAbRepeatTap,
                    onVolumeBoostCycle = onVolumeBoostCycle,
                )
            }
        }
        // ── quick row: equalizer, cast, headphones, rotate, output, 1X, sync popover entry ──
        PlayerQuickRow(
            accent = accent,
            equalizerOn = equalizerOn,
            headphonesOn = headphonesOn,
            castRouteName = castRouteName,
            showSyncPopover = showSyncPopover,
            speedLabelText = speedLabelText,
            actions = actions,
            onOpenEqPanel = onOpenEqPanel,
        )
    }
}

/** Quick-action row (equalizer, cast, headphones, output, speed pill, sync entry). */
@UnstableApi
@Composable
internal fun PlayerQuickRow(
    accent: Color,
    equalizerOn: Boolean,
    headphonesOn: Boolean,
    castRouteName: String?,
    showSyncPopover: Boolean,
    speedLabelText: String,
    actions: PlayerScreenActions,
    onOpenEqPanel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        QuickRowChip(
            icon = Icons.Filled.Equalizer,
            contentDescription = "Equalizer",
            active = equalizerOn,
            accent = accent,
            onClick = { actions.toggleEqualizer() },
            onLongClick = { onOpenEqPanel() },
        )
        QuickRowChip(
            icon = Icons.Filled.Cast,
            contentDescription = if (castRouteName != null)
                "Cast: $castRouteName" else "Cast",
            active = castRouteName != null,
            accent = accent,
            onClick = { actions.openCastPicker() },
        )
        QuickRowChip(
            icon = Icons.Filled.Headphones,
            contentDescription = "Headphone mode",
            active = headphonesOn,
            accent = accent,
            onClick = { actions.toggleHeadphones() },
        )
        QuickRowChip(
            icon = Icons.Filled.Tune,
            contentDescription = "Audio output picker",
            active = castRouteName != null,
            accent = accent,
            onClick = { actions.openAudioOutputPicker() },
        )
        Box(
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .height(38.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(accent)
                .clickable { actions.cycleSpeed() }
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(speedLabelText, color = Color.White,
                fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        QuickRowChip(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Sync tool",
            active = showSyncPopover,
            accent = accent,
            onClick = { actions.openSyncPopover() },
        )
    }
}

/** Overflow menu: subtitles / sleep / aspect / rotation / A-B / screenshot / boost / settings. */
@UnstableApi
@Composable
internal fun PlayerOverflowMenu(
    expanded: Boolean,
    accent: Color,
    showCC: Boolean,
    zoomAbbreviation: String,
    portraitForced: Boolean,
    abStartMs: Long?,
    abEndMs: Long?,
    volumeBoostPct: Int,
    sleep: SleepTimerUiState,
    actions: PlayerScreenActions,
    onOpenSubtitlePicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onAbRepeatTap: () -> Unit,
    onVolumeBoostCycle: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { actions.closeMenu() },
        containerColor = MxPanel,
    ) {
        DropdownMenuItem(
            text = {
                Text(if (showCC) "Subtitles: On" else "Subtitles: Off",
                    color = Color.White)
            },
            leadingIcon = {
                Icon(Icons.Filled.ClosedCaption, null,
                    tint = if (showCC) accent
                    else Color.White.copy(alpha = 0.7f))
            },
            onClick = { actions.toggleShowCC(); actions.closeMenu() }
        )
        DropdownMenuItem(
            text = { Text("Subtitle track…", color = Color.White) },
            leadingIcon = {
                Icon(Icons.Filled.Subtitles, null,
                    tint = Color.White.copy(alpha = 0.7f))
            },
            onClick = {
                actions.closeMenu()
                actions.view.haptic()
                onOpenSubtitlePicker()
            }
        )
        HorizontalDivider(color = MxMenuDivider)
        DropdownMenuItem(
            text = {
                Text("Sleep timer",
                    color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            },
            onClick = {},
            enabled = false
        )
        SleepOptions.forEach { opt ->
            DropdownMenuItem(
                text = { Text(opt.label, color = Color.White) },
                leadingIcon = {
                    if (sleep.isSelected(opt)) {
                        Icon(Icons.Filled.Check, null,
                            tint = accent, modifier = Modifier.size(18.dp))
                    }
                },
                onClick = { actions.selectSleep(opt); actions.closeMenu() }
            )
        }
        HorizontalDivider(color = MxMenuDivider)
        DropdownMenuItem(
            text = {
                Text("Aspect ratio: " + zoomAbbreviation,
                    color = Color.White)
            },
            onClick = { actions.cycleZoom() }
        )
        DropdownMenuItem(
            text = {
                Text(
                    "Rotation: " +
                        if (portraitForced) "Portrait" else "Auto",
                    color = Color.White)
            },
            leadingIcon = {
                Icon(
                    if (portraitForced) Icons.Filled.ScreenLockRotation
                    else Icons.Filled.ScreenRotation,
                    null,
                    tint = if (portraitForced) accent
                    else Color.White.copy(alpha = 0.7f),
                )
            },
            onClick = { actions.toggleRotation(); actions.closeMenu() }
        )
        HorizontalDivider(color = MxMenuDivider)
        DropdownMenuItem(
            text = {
                Text(
                    when {
                        abStartMs == null -> "A-B repeat: set start"
                        abEndMs == null ->
                            "A-B repeat: set end (A = " +
                                fmtTime(abStartMs) + ")"
                        else -> "A-B repeat: clear (" +
                            fmtTime(abStartMs) + " – " +
                            fmtTime(abEndMs) + ")"
                    },
                    color = Color.White,
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.Repeat, null,
                    tint = if (abStartMs != null) accent
                    else Color.White.copy(alpha = 0.7f))
            },
            onClick = {
                actions.closeMenu()
                actions.view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                onAbRepeatTap()
            },
        )
        DropdownMenuItem(
            text = { Text("Screenshot", color = Color.White) },
            leadingIcon = {
                Icon(Icons.Filled.PhotoCamera, null,
                    tint = Color.White.copy(alpha = 0.7f))
            },
            onClick = { actions.closeMenu(); actions.captureFrame() },
        )
        DropdownMenuItem(
            text = {
                Text(
                    "Volume boost: " + when (volumeBoostPct) {
                        0 -> "Off"
                        else -> "+${volumeBoostPct}%"
                    },
                    color = Color.White,
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.VolumeUp, null,
                    tint = if (volumeBoostPct > 0) accent
                    else Color.White.copy(alpha = 0.7f))
            },
            onClick = {
                actions.closeMenu()
                actions.view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                onVolumeBoostCycle()
            },
        )
        HorizontalDivider(color = MxMenuDivider)
        DropdownMenuItem(
            text = { Text("Settings", color = Color.White) },
            leadingIcon = {
                Icon(Icons.Filled.Settings, null,
                    tint = Color.White.copy(alpha = 0.7f))
            },
            onClick = { actions.closeMenu(); onOpenSettings() }
        )
    }
}

/** Bottom block: times + seekbar + transport dock over a gradient scrim. */
@UnstableApi
@Composable
internal fun PlayerBottomBar(
    modifier: Modifier = Modifier,
    accent: Color,
    currentPositionMs: Long,
    positionSec: Float,
    durationSec: Float,
    localSeek: MutableFloatState,
    isPlaying: Boolean,
    locked: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onEnterPip: () -> Unit,
    actions: PlayerScreenActions,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(
                listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fmtTime(currentPositionMs),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(min = 48.dp)
            )
            // Smooth seekbar: positionSec updates at the host's tick
            // (10Hz); animate the *visual* value to interpolate between
            // ticks. While the user is dragging (localSeek >= 0), the
            // raw value is used; otherwise the animated value follows.
            val visualPos by animateFloatAsState(
                targetValue = if (localSeek.floatValue >= 0f) localSeek.floatValue else positionSec,
                animationSpec = tween(durationMillis = 100, easing = LinearEasing),
                label = "seekbar",
            )
            Slider(
                value = visualPos.coerceIn(0f, durationSec.coerceAtLeast(1f)),
                onValueChange = { localSeek.floatValue = it },
                onValueChangeFinished = {
                    if (localSeek.floatValue >= 0f) actions.livePlayer.seekTo((localSeek.floatValue * 1000).toLong())
                    localSeek.floatValue = -1f
                },
                valueRange = 0f..durationSec.coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            Text(
                fmtTime((durationSec - positionSec).coerceAtLeast(0f).toLong()),
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 48.dp)
            )
        }
        // ── dock controls ──
        //
        // Visual language (intentional, do not change without UX review):
        //   * TIME-SEEK ±10s   =  SQUARED pill, 36×36, green-tint background,
        //                        label "10s" baked into the icon
        //   * EPISODE-JUMP ‹‹ ›› =  ROUND ghost 44dp, pure-white arrows,
        //                        disabled state = 35% alpha
        //   * Spacing: 14dp gap separates the time-seek group from the
        //     episode-jump group, with the BIG PLAY in the middle. The eye
        //     locks onto the play button and the two pairs read as
        //     "near: time / far: episode" with one extra row of breathing
        //     room between them — a mis-tap now needs an aimed reach.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 4.dp)
        ) {
            // Left rail: lock
            IconButton(
                onClick = { actions.lockControls() },
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Lock controls",
                    tint = if (locked) accent else Color.White,
                )
            }

            // Centre transport: time-seek 10s | ‹ep | ▶ | ep› | 10s
            Row(
                modifier = Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ── time-seek group (LEFT side) ──
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeSeekButton(
                        direction = TimeSeekDirection.BACK,
                        accent = accent,
                        onClick = {
                            actions.view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            actions.seekBy(-seekIncrementSec)
                        },
                    )
                }
                // 14dp divider gap between time-seek and episode-jump groups
                Spacer(Modifier.width(14.dp))
                // ── episode-jump group ──
                EpisodeJumpButton(
                    direction = EpisodeJumpDirection.PREVIOUS,
                    enabled = hasPreviousEpisode,
                    onClick = {
                        actions.view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                        onPlayPrevious()
                    },
                )
                Spacer(Modifier.width(6.dp))
                // ── BIG play / pause — with skin-accent ripple ────────
                PlayPauseButton(
                    isPlaying = isPlaying,
                    accent = accent,
                    onClick = {
                        actions.view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                        actions.togglePlayPause()
                    },
                )
                Spacer(Modifier.width(6.dp))
                EpisodeJumpButton(
                    direction = EpisodeJumpDirection.NEXT,
                    enabled = hasNextEpisode,
                    onClick = {
                        actions.view.haptic(HapticFeedbackConstants.VIRTUAL_KEY)
                        onPlayNext()
                    },
                )
                Spacer(Modifier.width(14.dp))
                // ── time-seek group (RIGHT side) ──
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TimeSeekButton(
                        direction = TimeSeekDirection.FORWARD,
                        accent = accent,
                        onClick = {
                            actions.view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                            actions.seekBy(seekIncrementSec)
                        },
                    )
                }
            }

            // Right rail: PiP + aspect
            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(onClick = onEnterPip) {
                    Icon(
                        Icons.Filled.PictureInPictureAlt,
                        contentDescription = "Picture-in-picture",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { actions.cycleZoom() }) {
                    Icon(
                        Icons.Filled.AspectRatio,
                        contentDescription = "Aspect ratio",
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** BIG play / pause button — AnimatedContent slides the icon vertically on flip. */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 36.dp, color = accent),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // AnimatedContent slides the icon vertically when
        // play/pause state flips — feels like a real
        // mechanical press, not a state toggle.
        AnimatedContent(
            targetState = isPlaying,
            transitionSpec = {
                (slideInVertically { it } + fadeIn()) togetherWith
                    (slideOutVertically { -it } + fadeOut())
            },
            label = "playPause",
        ) { playing ->
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/* ── dock button visual language ──────────────────────────────────────────
 * Two distinct button families live in the bottom transport:
 *
 * 1. Time-seek ±10s — SQUARED 40×40 pill, brand-green tint, label baked
 *    into the icon. The square silhouette + green-tint background is
 *    intentionally loud; the eye is trained to read "this is a time
 *    action, not an episode action".
 *
 * 2. Episode-jump ‹ep / ep› — ROUND 44dp ghost, full-white double-arrow.
 *    Same silhouette as the play button, so they read as "transport
 *    navigation" rather than "time navigation".
 *
 * Spacing (14dp) between the two families makes them read as separate
 * groups even on a quick glance.
 * ------------------------------------------------------------------------- */

private enum class TimeSeekDirection { BACK, FORWARD }
private enum class EpisodeJumpDirection { PREVIOUS, NEXT }

@Composable
private fun TimeSeekButton(
    direction: TimeSeekDirection,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.22f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = accent),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(
                text = if (direction == TimeSeekDirection.BACK) "«" else "»",
                color = accent,
                fontWeight = FontWeight.Black,
                fontSize = 13.sp,
            )
            Text(
                text = "10s",
                color = accent,
                fontWeight = FontWeight.Black,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun EpisodeJumpButton(
    direction: EpisodeJumpDirection,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Color.White),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (direction == EpisodeJumpDirection.PREVIOUS) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = "Previous episode",
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next episode",
                tint = tint,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

/* ── quick-row chip (top right under the top bar) ─────────────────────────
 * 48dp circle, frosted background, tints green when active. Used for the
 * equalizer / cast / headphones / output / sync entries.
 * ------------------------------------------------------------------------- */
@Composable
private fun QuickRowChip(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val view = LocalView.current
    val baseModifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(
            if (active) accent.copy(alpha = 0.22f)
            else Color.Black.copy(alpha = 0.45f)
        )
        .border(
            width = 1.dp,
            color = if (active) accent.copy(alpha = 0.7f)
            else Color.White.copy(alpha = 0.18f),
            shape = CircleShape,
        )
    if (onLongClick != null) {
        // combinedClickable supports long-press; IconButton does not.
        Box(
            modifier = baseModifier.combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = accent),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onLongClick()
                },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (active) accent else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        IconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            },
            modifier = baseModifier,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (active) accent else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
