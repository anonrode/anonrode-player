package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
                text = actions.decoderModeLabel,
                accent = accent,
                // "Highlighted" means an override: the default hybrid HW+SW
                // profile is the baseline, APP and HW-only are deliberate
                // choices the user made to change decoding behaviour.
                selected = actions.decoderModeLabel != "HW+SW",
                onClick = { actions.cycleDecoderMode() },
            )
            ControlChip(
                icon = Icons.Filled.MoreVert,
                contentDescription = "More options",
                accent = accent,
                onClick = onMore,
            )
        }

        // ── Row 2: Quick Access Ribbon ──
        //
        // Renders from [QuickRowUiState.ribbonOrder] rather than 13
        // hard-coded calls, which is what makes the Customise tool real:
        // the user's order (and hidden set) is a single persisted list the
        // ribbon and the customise sheet both read and write.
        val ribbonOrder = actions.quick.ribbonOrder
        // Hoisted, not created here — see QuickRowUiState.ribbonScroll for
        // why a scroll state born inside this (auto-hiding) subtree could
        // never hold an offset.
        //
        // The observe/restore collectors deliberately do NOT live here
        // either; they are hoisted into PlayerScreen, above the chrome's
        // AnimatedVisibility. A LaunchedEffect started inside a subtree that
        // Compose disposes on auto-hide is cancelled with it, which would drop
        // the debounce delay mid-flight and lose the final offset exactly when
        // the user scrolls and then lets the controls fade — the common case.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp)
                .horizontalScroll(actions.quick.ribbonScroll),
            horizontalArrangement = Arrangement.spacedBy(PlayerDimens.gapXs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ribbonOrder.forEach { tool ->
                // `key` keeps a moved tool's slot stable across the reorder
                // so the ripple/scale animation doesn't restart on every tap.
                key(tool.name) {
                    RibbonToolSlot(tool = tool, accent = accent, actions = actions)
                }
            }
        }
    }
}

/**
 * One ribbon entry: the icon, label, active state and tap target for a
 * single [RibbonTool]. Split out of the ribbon's Row so the ribbon can
 * iterate an ordered list while each tool keeps its own state reads.
 */
@Composable
private fun RibbonToolSlot(
    tool: RibbonTool,
    accent: Color,
    actions: PlayerScreenActions,
) {
    val ui = actions.ui
    val quick = actions.quick
    when (tool) {
        RibbonTool.NIGHT_MODE -> RibbonToolItem(
            icon = Icons.Filled.Bedtime,
            label = "Night Mode",
            accent = accent,
            selected = ui.nightMode.value,
            onClick = { actions.toggleNightMode() },
        )

        RibbonTool.CUSTOMISE -> RibbonToolItem(
            icon = Icons.Filled.Edit,
            label = "Customise",
            accent = accent,
            selected = quick.showRibbonCustomise.value,
            onClick = { actions.openRibbonCustomise() },
        )

        RibbonTool.SHUFFLE -> RibbonToolItem(
            icon = Icons.Filled.Shuffle,
            label = "Shuffle",
            accent = accent,
            selected = ui.shuffleOn.value,
            onClick = { actions.toggleShuffle() },
        )

        RibbonTool.LOOP -> RibbonToolItem(
            icon = if (ui.repeatMode.value == RepeatLoopMode.ONE) Icons.Filled.RepeatOne
            else Icons.Filled.Repeat,
            label = when (ui.repeatMode.value) {
                RepeatLoopMode.ONE -> "Loop 1"
                RepeatLoopMode.ALL -> "Loop All"
                RepeatLoopMode.OFF -> "Loop"
            },
            accent = accent,
            selected = ui.repeatMode.value != RepeatLoopMode.OFF,
            onClick = { actions.cycleRepeatLoopMode() },
        )

        RibbonTool.MUTE -> RibbonToolItem(
            icon = if (ui.isMuted.value) Icons.AutoMirrored.Filled.VolumeOff
            else Icons.AutoMirrored.Filled.VolumeUp,
            label = if (ui.isMuted.value) "Muted" else "Mute",
            accent = accent,
            selected = ui.isMuted.value,
            onClick = { actions.toggleMute() },
        )

        RibbonTool.SLEEP_TIMER -> RibbonToolItem(
            icon = Icons.Filled.Timer,
            label = "Sleep Timer",
            accent = accent,
            selected = actions.sleep.active,
            onClick = { actions.cycleSleepTimer() },
        )

        RibbonTool.AB_REPEAT -> RibbonToolItem(
            badgeText = "A⮂B",
            label = when {
                ui.abStartMs.value != null && ui.abEndMs.value != null -> "Loop A-B"
                ui.abStartMs.value != null -> "Set B"
                else -> "A - B Repeat"
            },
            accent = accent,
            selected = ui.abStartMs.value != null,
            onClick = { actions.cycleAbRepeat() },
        )

        RibbonTool.AUDIO_EFFECT -> RibbonToolItem(
            icon = Icons.Filled.GraphicEq,
            label = "Audio Effect",
            accent = accent,
            selected = ui.audioEffectOn.value,
            hasActiveDot = ui.audioEffectOn.value,
            onClick = { actions.toggleAudioEffect() },
        )

        RibbonTool.EQUALIZER -> RibbonToolItem(
            icon = Icons.Filled.Equalizer,
            label = "Equalizer",
            accent = accent,
            selected = quick.equalizerOn.value,
            onClick = { actions.toggleEqualizer() },
        )

        RibbonTool.SPEED -> {
            val curSpeed = actions.speeds[actions.speedIdx.intValue]
            RibbonToolItem(
                badgeText = speedLabel(curSpeed),
                label = "Speed",
                accent = accent,
                selected = abs(curSpeed - 1f) > 0.05f,
                onClick = { actions.cycleSpeed() },
                onLongClick = { actions.resetSpeed() },
            )
        }

        RibbonTool.SCREENSHOT -> RibbonToolItem(
            icon = Icons.Filled.PhotoCamera,
            label = "Screenshot",
            accent = accent,
            selected = false,
            onClick = { actions.captureFrame() },
        )

        RibbonTool.BACKGROUND_PLAY -> RibbonToolItem(
            icon = Icons.Filled.Headphones,
            label = "Background",
            accent = accent,
            selected = ui.backgroundPlayOn.value,
            onClick = { actions.toggleBackgroundPlay() },
        )

        RibbonTool.ROTATION -> RibbonToolItem(
            icon = Icons.Filled.ScreenRotation,
            label = when (quick.rotateMode.value) {
                RotateMode.SENSOR -> "Auto"
                RotateMode.LANDSCAPE -> "Landscape"
                RotateMode.PORTRAIT -> "Portrait"
            },
            accent = accent,
            selected = quick.rotateMode.value != RotateMode.SENSOR,
            onClick = { actions.cycleRotateMode() },
        )
    }
}

/** Individual circular tool in the Quick Access Ribbon with text label underneath. */
@Composable
internal fun RibbonToolItem(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    badgeText: String? = null,
    label: String,
    accent: Color,
    selected: Boolean = false,
    hasActiveDot: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .widthIn(min = 56.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 24.dp, color = accent),
                onClick = onClick,
            )
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    if (selected) accent.copy(alpha = 0.28f)
                    else Color.White.copy(alpha = 0.12f)
                )
                .border(
                    width = if (selected) 1.dp else 0.dp,
                    color = if (selected) accent else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) accent else Color.White,
                    modifier = Modifier.size(18.dp),
                )
            } else if (badgeText != null) {
                Text(
                    text = badgeText,
                    color = if (selected) accent else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (hasActiveDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = (-3).dp, y = 3.dp)
                        .background(Color(0xFFFF3B30), CircleShape)
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = if (selected) accent else Color.White.copy(alpha = 0.85f),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
