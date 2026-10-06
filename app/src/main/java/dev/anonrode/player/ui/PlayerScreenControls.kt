package dev.anonrode.player.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import kotlinx.coroutines.launch
/* ── Player controls chrome (v0.7.3 curated dock) ─────────────────────────
 *
 *   Top bar (auto-hide)   ‹  Title                    ⧉  🔒  ⋮
 *   Right-edge rail       GONE — its five slots moved into the dock's
 *                         utility row / the sheet. The overflow control has
 *                         one home: the top bar's ⋮, and nothing else opens
 *                         the Control Center.
 *   Ribbon vs. sheet      the ribbon is a user-curated SHORTCUT strip (its
 *                         order and hidden set are persisted); the Control
 *                         Center is the full inventory. A-B repeat, sleep
 *                         timer, equalizer and capture frame therefore
 *                         appear in both on purpose — a tool hidden from
 *                         the ribbon must not become unreachable.
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
    // Controls overlay fades completely (top bar + bottom bar + seekbar)
    // leaving a 100% clean, unblemished video frame in fullscreen immersion.
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
        AnimatedVisibility(
            visible = visible && showSeekBar,
            enter = fadeIn(animationSpec = tween(220)),
            exit = fadeOut(animationSpec = tween(220)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
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
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PlayerDimens.gapXs),
            ) {
                ControlChip(
                    icon = Icons.Filled.MusicNote,
                    contentDescription = "Audio track",
                    accent = accent,
                    onClick = { actions.openAudioSheet() },
                )
                ControlChip(
                    icon = Icons.Filled.ClosedCaption,
                    contentDescription = if (actions.ui.showCC.value) "Subtitles on" else "Subtitles off",
                    accent = accent,
                    selected = actions.ui.showCC.value,
                    onClick = { actions.toggleShowCC() },
                    onLongClick = { actions.openSubtitlePicker() },
                )
                DecoderPill(
                    isHw = actions.ui.isHwDecoder.value,
                    accent = accent,
                    onClick = { actions.toggleDecoder() },
                )
                ControlChip(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "More options",
                    accent = accent,
                    onClick = onMore,
                )
            }
        }

        // ── Row 2: Elastic Drawer Quick Ribbon ──
        //
        // Floating elastic drawer sleeve:
        // - Zero container background or dark borders.
        // - Resting width: exactly 3 tools visible in Portrait (Night, Speed, Mute),
        //   4 tools in Landscape (Night, Speed, Mute, Loop).
        // - Tool discs: petrol-slate cinema disc gradient.
        // - Chevron handle sits directly adjacent to the sleeve edge (zero overlap on tool 4 or tool 5).
        // - 3-Zone Drag Physics:
        //   * Pull < 35%: snaps back to resting width.
        //   * Pull 35%..65%: HOLDS at the exact position dragged to.
        //   * Push back by >= 15% (or drop < 35%): snaps back to resting width.
        //   * Pull >= 65%: snaps open to max screen limit.
        //   * When open to screen limit: horizontal scrolling enabled through all 13 tools.
        //   * When controls auto-hide, reset drawer back to resting width and scroll offset 0.
        val ribbonOrder = actions.quick.ribbonOrder
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val restingToolCount = if (isLandscape) 4 else 3
        val toolCellWidth = RIBBON_CELL_MIN_WIDTH_DP.dp
        val toolSpacing = PlayerDimens.gapXs
        val restingWidthDp = (restingToolCount * toolCellWidth.value + (restingToolCount - 1) * toolSpacing.value).dp

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp),
        ) {
            val density = LocalDensity.current
            val coroutineScope = rememberCoroutineScope()
            val view = LocalView.current
            val handleWidth = 28.dp
            val maxAvailableWidthDp = (maxWidth - handleWidth - 4.dp).coerceAtLeast(restingWidthDp)
            val restingWidthPx = with(density) { restingWidthDp.toPx() }
            val maxWidthPx = with(density) { maxAvailableWidthDp.toPx() }

            var isExpanded by rememberSaveable { mutableStateOf(false) }
            val drawerWidthAnim = remember { Animatable(restingWidthPx) }

            // Sync anim with isExpanded
            LaunchedEffect(isExpanded, restingWidthPx, maxWidthPx) {
                val target = if (isExpanded) maxWidthPx else restingWidthPx
                drawerWidthAnim.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = 240, easing = LinearOutSlowInEasing),
                )
            }

            // Auto-hide reset: when controls fade, automatically collapse drawer and reset scroll
            LaunchedEffect(actions.ui.controlsVisible.value) {
                if (!actions.ui.controlsVisible.value) {
                    isExpanded = false
                    drawerWidthAnim.snapTo(restingWidthPx)
                    actions.quick.ribbonScroll.scrollTo(0)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Floating elastic drawer sleeve
                Box(
                    modifier = Modifier
                        .width(with(density) { drawerWidthAnim.value.toDp() })
                        .clipToBounds()
                        .pointerInput(isExpanded) {
                            if (!isExpanded) {
                                detectHorizontalDragGestures { change, dragAmount ->
                                    if (dragAmount > 6f || dragAmount < -6f) {
                                        change.consume()
                                        isExpanded = true
                                    }
                                }
                            }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(actions.quick.ribbonScroll, enabled = isExpanded),
                        horizontalArrangement = Arrangement.spacedBy(toolSpacing),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ribbonOrder.forEach { tool ->
                            key(tool.name) {
                                RibbonToolSlot(tool = tool, accent = accent, actions = actions)
                            }
                        }
                    }
                }

                // Chevron handle: sits directly adjacent to sleeve edge
                Box(
                    modifier = Modifier
                        .size(width = handleWidth, height = 48.dp)
                        .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 20.dp, color = accent),
                            onClick = {
                                view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                                isExpanded = !isExpanded
                                if (!isExpanded) {
                                    coroutineScope.launch {
                                        actions.quick.ribbonScroll.scrollTo(0)
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
                        contentDescription = if (isExpanded) "Collapse tools ribbon" else "Expand tools ribbon",
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(20.dp),
                    )
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
            label = if (ui.repeatMode.value == RepeatLoopMode.ONE) "Loop 1" else "Loop",
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

        RibbonTool.SLEEP_TIMER -> {
            val mins = ui.sleepMinutes.intValue
            RibbonToolItem(
                icon = Icons.Filled.Timer,
                label = if (mins > 0) "${mins}m Timer" else "Sleep Timer",
                accent = accent,
                selected = mins > 0 || actions.sleep.active,
                onClick = { actions.cycleSleepTimer() },
            )
        }

        RibbonTool.AB_REPEAT -> RibbonToolItem(
            badgeText = "A⮂B",
            label = when {
                ui.abStartMs.value != null && ui.abEndMs.value != null -> "Looping A-B"
                ui.abStartMs.value != null -> "Point A Set"
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
            onClick = { actions.captureScreenshot() },
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
            label = "Rotation",
            accent = accent,
            selected = quick.rotateMode.value != RotateMode.SENSOR,
            onClick = { actions.cycleRotateMode() },
        )
    }
}

/**
 * Minimum width of one Quick Access Ribbon cell, in dp.
 *
 * This is a deliberate overflow budget, not a tidy number. The catalogue is 13
 * tools; each cell is this width plus 2dp padding per side, and the Row adds
 * `Dimens.gapXs` (4dp) between neighbours:
 *
 *     13 * (64 + 4) + 12 * 4 = 932dp of track
 *
 * A landscape phone viewport is about 873dp before the top bar's own padding,
 * so the full catalogue always overflows and the ribbon is genuinely
 * scrollable there. At the previous 56dp minimum the track was only 828dp —
 * it FIT, which meant `horizontalScroll` had nothing to scroll, `maxValue`
 * stayed 0, and the whole restore-and-persist path (the reason the scroll
 * state is hoisted at all) never ran. Persistence cannot work on a track that
 * cannot scroll.
 *
 * 64dp is also well clear of the 48dp minimum touch target, so the wider
 * pitch costs nothing ergonomically.
 */
private const val RIBBON_CELL_MIN_WIDTH_DP = 60

/** Individual circular tool in the Quick Access Ribbon with text label underneath. */
@OptIn(ExperimentalFoundationApi::class)
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
    val view = LocalView.current
    Column(
        modifier = modifier
            .widthIn(min = RIBBON_CELL_MIN_WIDTH_DP.dp)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 24.dp, color = accent),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClick()
                        },
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 24.dp, color = accent),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClick()
                        },
                        onLongClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                            onLongClick()
                        },
                    )
                }
            )
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val cinemaGradient = Brush.verticalGradient(
            listOf(
                Color(0xFF2A3647),
                Color(0xFF1A2330),
                Color(0xFF111722),
            )
        )
        val boxModifier = if (selected) {
            Modifier.background(color = accent.copy(alpha = 0.35f), shape = CircleShape)
        } else {
            Modifier.background(brush = cinemaGradient, shape = CircleShape)
        }
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .then(boxModifier)
                .border(
                    width = 1.dp,
                    color = if (selected) accent else Color.White.copy(alpha = 0.18f),
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
                        .offset(x = (-2).dp, y = 2.dp)
                        .background(Color(0xFFFF3B30), CircleShape)
                        .border(1.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
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

/**
 * Dedicated HW/SW Decoder Pill in the top header.
 * Shows "HW" (white/neutral) or "SW" (amber background badge),
 * with minimum 48dp touch ergonomics and instant decoder toggle.
 */
@Composable
internal fun DecoderPill(
    isHw: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    Box(
        modifier = modifier
            .sizeIn(minWidth = 44.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = if (isHw) accent else Color(0xFFF59E0B)),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onClick()
                },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val pillBg = if (isHw) Color.White.copy(alpha = 0.12f) else Color(0xFFF59E0B).copy(alpha = 0.25f)
        val pillBorder = if (isHw) Color.White.copy(alpha = 0.20f) else Color(0xFFF59E0B).copy(alpha = 0.50f)
        val textColor = if (isHw) Color(0xFFCBD5E1) else Color(0xFFFCD34D)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(pillBg)
                .border(1.dp, pillBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isHw) "HW" else "SW",
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
            )
        }
    }
}
