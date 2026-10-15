package dev.anonrode.player.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import kotlinx.coroutines.launch
/* ── Player controls chrome ────────────────────────────────────────────────
 *
 *   Top bar (auto-hide)   ‹  Title   [HW] [CC] [Playlist] [⋮]
 *   Ribbon (auto-hide)    elastic shortcut sleeve + chevron expand handle
 *   Bottom block          sync pill / seek row / transport (see BottomBar)
 *
 *   Ribbon vs. sheet      the ribbon is a user-curated SHORTCUT strip (its
 *                         order, hidden set and scroll offset are
 *                         persisted); the Control Center is the full
 *                         inventory. A-B repeat, sleep timer, equalizer and
 *                         capture frame therefore appear in both on
 *                         purpose — a tool hidden from the ribbon must not
 *                         become unreachable.
 *   Single overflow       the top bar's ⋮ is the ONLY home of the Control
 *                         Center.
 *
 *   • The top bar carries chrome-level actions only: back, the HW/SW
 *     decoder pill, the CC subtitle chip, the playlist drawer chip and
 *     the single ⋮ "more". Lock lives in the dock's transport row.
 *   • Insets are real (statusBarsPadding / navigationBarsPadding /
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
    /** Coded video geometry for the title's tech sub-line (hub 1.3).
     *  0 = no live signal — the segment is omitted, never faked. */
    videoWidth: Int = 0,
    videoHeight: Int = 0,
    videoFps: Int = 0,
    hasSubtitleTrack: Boolean,
    liveOffsetMs: Long,
    /** Non-blocking resume pill state (hub 8.1) — rendered inside the dock. */
    resumePillMs: Long? = null,
    onStartOver: () -> Unit = {},
    actions: PlayerScreenActions,
    onBack: () -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onMore: () -> Unit,
) {
    // Controls overlay fades completely (top bar + bottom bar + seekbar)
    // leaving a 100% clean, unblemished video frame in fullscreen immersion.
    // Reveal motion (better than the static HTML): top slides down 8dp, dock
    // slides up 10dp, both on the law's emphasized bezier, dock 40ms behind.
    val easeReveal = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(240)) +
                slideInVertically(animationSpec = tween(280, easing = easeReveal)) { -8 },
            exit = fadeOut(animationSpec = tween(160)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            PlayerScreenTopBar(
                title = title,
                accent = accent,
                actions = actions,
                videoWidth = videoWidth,
                videoHeight = videoHeight,
                videoFps = videoFps,
                hasSubtitleTrack = hasSubtitleTrack,
                liveOffsetMs = liveOffsetMs,
                onBack = onBack,
                onMore = onMore,
            )
        }
        AnimatedVisibility(
            visible = visible && showSeekBar,
            enter = fadeIn(
                animationSpec = tween(240, delayMillis = 40),
            ) + slideInVertically(
                animationSpec = tween(300, delayMillis = 40, easing = easeReveal),
            ) { 10 },
            exit = fadeOut(animationSpec = tween(160)),
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
                resumePillMs = resumePillMs,
                onStartOver = onStartOver,
                hasPreviousEpisode = hasPreviousEpisode,
                hasNextEpisode = hasNextEpisode,
                hasSubtitleTrack = hasSubtitleTrack,
                liveOffsetMs = liveOffsetMs,
                onPlayPrevious = onPlayPrevious,
                onPlayNext = onPlayNext,
                actions = actions,
            )
        }
    }
}

/** Top bar — Row 1: back · title (+tech sub-line; landscape inlines both on one line per mockup H) · CC · Playlist · ⋮ on ONE 48dp centerline; Row 2: collapsible scrollable tools ribbon. */
@UnstableApi
@Composable
internal fun PlayerScreenTopBar(
    modifier: Modifier = Modifier,
    title: String,
    accent: Color,
    actions: PlayerScreenActions,
    /** Live coded video geometry — 0 means "no signal", segment omitted. */
    videoWidth: Int = 0,
    videoHeight: Int = 0,
    videoFps: Int = 0,
    hasSubtitleTrack: Boolean,
    liveOffsetMs: Long,
    onBack: () -> Unit,
    onMore: () -> Unit,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Scrim before insets → the gradient reaches the physical top
            // edge; content sits below the status bar / cutout.
            .background(
                // Mockup `.top` scrim: black 55% → transparent.
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            ) {
                // ── Tech sub-line (hub 1.3): dim 10sp tnum, composed ONLY
                //    from live engine values — resolution + fps come from the
                //    player's video track (VideoTechLineEffect), the decoder
                //    label from the running engine, the sync offset from the
                //    same host state the dock's sync chip reads. A segment
                //    with no live source is omitted, never faked (AGENTS
                //    rule 11).
                val offsetLocked = actions.quick.isSyncLocked.value
                val syncedLabel = "synced " + (if (liveOffsetMs >= 0) "+" else "") +
                    String.format(java.util.Locale.US, "%.2f", liveOffsetMs / 1000.0) + "s"
                val techLine = buildAnnotatedString {
                    if (videoWidth > 0 && videoHeight > 0) {
                        // "1080p" convention: the shorter coded side names the
                        // line count, correct for rotated videos too.
                        append("${minOf(videoWidth, videoHeight)}p · ")
                    }
                    if (videoFps > 0) {
                        append("${videoFps}fps · ")
                    }
                    append(actions.decoderModeLabel)
                    if (offsetLocked) {
                        withStyle(SpanStyle(color = accent)) { append(" · $syncedLabel") }
                    }
                }
                if (isLandscape) {
                    // Landscape (mockup H): "title · tech" on ONE ellipsized
                    // line — the tech segments inline in dim, the synced
                    // tail still accent.
                    Text(
                        text = buildAnnotatedString {
                            append(title)
                            withStyle(
                                SpanStyle(
                                    color = Color(0xFFABABB2),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Normal,
                                )
                            ) {
                                append(" · ")
                                append(techLine)
                            }
                        },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    // Portrait: filename over the tech sub-line (mockup A).
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = techLine,
                        color = Color(0xFFABABB2),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Normal,
                        style = Tnum,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PlayerDimens.gapXs),
            ) {
                // [ CC ] — decoder truth lives in the tech sub-line under the
                // title (hub 1.3); no chip in the top row echoes it.
                ControlChip(
                    icon = Icons.Filled.ClosedCaption,
                    contentDescription = if (actions.ui.showCC.value) "Subtitles on" else "Subtitles off",
                    accent = accent,
                    selected = actions.ui.subtitleDrawerOpen.value || actions.ui.showCC.value,
                    onClick = { actions.toggleSubtitleDrawer() },
                    onLongClick = { actions.openSubtitlePicker() },
                )
                // 3. [ Playlist ]
                ControlChip(
                    icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = "Playlist / Folder episodes",
                    accent = accent,
                    selected = actions.ui.playlistDrawerOpen.value,
                    onClick = { actions.togglePlaylistDrawer() },
                )
                // 4. [ ⋮ ]
                ControlChip(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = "Player Settings",
                    accent = accent,
                    selected = actions.ui.threeDotsMenuOpen.value,
                    onClick = onMore,
                )
            }
        }

        // ── Row 2: Elastic Drawer Quick Ribbon ──
        //
        // The mockup's closed sleeve (`.ribbon.closed` hides
        // `nth-child(n+6)`): the first FIVE tools rest visible — in the
        // default order Speed, Screenshot, Rotation, Mute, Night, exactly
        // the mockup's resting set — with the chevron pinned to the row's
        // right edge. Tap the chevron to animate the sleeve clip open,
        // revealing the same horizontally scrolling track (all enabled
        // tools); collapsing scrolls back to offset 0. When the controls
        // auto-hide, the drawer resets to its resting width and offset.
        val ribbonOrder = actions.quick.ribbonOrder
        val restingToolCount = 5
        val toolCellWidth = RIBBON_CELL_WIDTH_DP.dp
        val toolSpacing = 4.dp
        val restingWidthDp = (restingToolCount * toolCellWidth.value + (restingToolCount - 1) * toolSpacing.value).dp

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp),
        ) {
            val density = LocalDensity.current
            val coroutineScope = rememberCoroutineScope()
            val view = LocalView.current
            val handleReserve = 36.dp // 32dp chevron + 4dp gap
            val maxAvailableWidthDp = (maxWidth - handleReserve).coerceAtLeast(restingWidthDp)
            val restingWidthPx = with(density) { restingWidthDp.toPx() }
            val maxWidthPx = with(density) { maxAvailableWidthDp.toPx() }

            val isExpanded = actions.quick.ribbonExpanded.value
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
                    actions.quick.ribbonExpanded.value = false
                    drawerWidthAnim.snapTo(restingWidthPx)
                    actions.quick.ribbonScroll.scrollTo(0)
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Sleeve: the Row always reserves everything but the chevron
                // so the handle sits at the row's right edge like the mockup;
                // the animated width below is the CLIP — collapsed it shows
                // exactly the five resting cells, expanded it uncovers the
                // rest of the scrolling track.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clipToBounds(),
                ) {
                    Row(
                        modifier = Modifier
                            .width(with(density) { drawerWidthAnim.value.toDp() })
                            .horizontalScroll(actions.quick.ribbonScroll, enabled = true),
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

                // Chevron handle: bare icon per the mockup (`›` unboxed,
                // white 60%) — a boxed chip here is exactly the clutter the
                // design law strips out.
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(width = 32.dp, height = 44.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 20.dp, color = accent),
                            onClick = {
                                view.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                                val willExpand = !actions.quick.ribbonExpanded.value
                                actions.quick.ribbonExpanded.value = willExpand
                                if (!willExpand) {
                                    coroutineScope.launch {
                                        actions.quick.ribbonScroll.animateScrollTo(0)
                                    }
                                }
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
                        contentDescription = if (isExpanded) "Collapse tools ribbon" else "Expand tools ribbon",
                        tint = Color.White.copy(alpha = 0.65f),
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
            label = "Night",
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
                label = if (mins > 0) "${mins}m" else "Timer",
                accent = accent,
                selected = mins > 0 || actions.sleep.active,
                onClick = { actions.cycleSleepTimer() },
            )
        }

        RibbonTool.AB_REPEAT -> RibbonToolItem(
            badgeText = "A⮂B",
            label = when {
                ui.abStartMs.value != null && ui.abEndMs.value != null -> "Loop A-B"
                ui.abStartMs.value != null -> "Point A"
                else -> "A - B"
            },
            accent = accent,
            selected = ui.abStartMs.value != null,
            // Amber active dot is the A-B status hue (hub 2.3: dot ONLY for
            // A-B) — every other tool states its state via ring + label.
            hasActiveDot = ui.abStartMs.value != null,
            onClick = { actions.cycleAbRepeat() },
        )

        RibbonTool.AUDIO_EFFECT -> RibbonToolItem(
            icon = Icons.Filled.GraphicEq,
            label = "Effect",
            accent = accent,
            selected = ui.audioEffectOn.value,
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
            val curSpeed = actions.ui.currentPlaybackSpeed.floatValue
            val labelText = if (curSpeed % 1f == 0f) "${curSpeed.toInt()}.0X" else String.format(java.util.Locale.US, "%.2fX", curSpeed)
            RibbonToolItem(
                badgeText = labelText,
                label = "Speed",
                accent = accent,
                selected = abs(curSpeed - 1f) > 0.05f,
                onClick = { actions.toggleSpeedCard() },
                onLongClick = { actions.resetSpeedToDefault() },
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
 * Width of one Quick Access Ribbon cell, in dp — the mockup's `.disc` cell
 * (48px) exactly, with a 4dp gap between neighbours (mockup `gap:4px`):
 *
 *     13 * 48 + 12 * 4 = 672dp of track
 *
 * The closed sleeve shows the first five cells (`nth-child(n+6)` hidden);
 * the same horizontally scrolling track holds the rest, and the chevron
 * animates the clip open to reveal it. On wider windows the track can FIT:
 * `ScrollState.maxValue` stays 0 and the persisted scroll offset simply
 * never gets restored, which [QuickRowUiState.tryRestoreRibbonScroll]
 * already treats as the normal no-op case. Persistence cannot work on a
 * track that cannot scroll; nothing here pretends otherwise.
 *
 * 48dp is the minimum touch-target width, so the pitch costs nothing
 * ergonomically.
 */
private const val RIBBON_CELL_WIDTH_DP = 48

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
    // Press feel: 0.96 spring per the mockup law (`.disc:active`).
    // The scale lives on the VISUAL only — the cell keeps its full ≥48dp
    // touch footprint for the whole gesture.
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "ribbonPress",
    )
    Column(
        modifier = modifier
            .width(RIBBON_CELL_WIDTH_DP.dp)
            .heightIn(min = 48.dp)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = ripple(bounded = false, radius = 24.dp, color = accent),
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onClick()
                        },
                    )
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
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
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // ══ Disc (hub 2.3 active-state law) ══
        // Flat dark glass — rgba(0,0,0,.42) — never a gradient. The ACTIVE
        // state is the accent RING (1.5dp) + a 12%-alpha accent halo
        // (`.disc.on .dn { border-color; box-shadow: 0 0 0 3px accent-dim }`
        // in the approved mockup), with the accent-tinted icon.
        // Never color-only: ring + label state together.
        val discModifier =
            Modifier.background(color = Color.Black.copy(alpha = 0.42f), shape = CircleShape)

        // Unselected discs carry NO border (mockup: `border: 1px transparent`
        // — the 42% glass alone separates them from the video); selected
        // earns the 1.5dp accent ring.
        val borderColor = if (selected) accent else Color.Transparent
        val borderWidth = if (selected) 1.5.dp else 1.dp

        // 44dp disc + 3dp halo band = 50dp stack, so the glow reads OUTSIDE
        // the ring like the mockup's halo; the wrapper keeps every cell's
        // geometry identical whether or not it is lit.
        Box(
            modifier = Modifier.size(50.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(50.dp)
                        .background(accent.copy(alpha = 0.12f), CircleShape)
                )
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                    }
                    .clip(CircleShape)
                    .then(discModifier)
                    .border(
                        width = borderWidth,
                        color = borderColor,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (selected) accent else Color(0xFFE2E8F0),
                        modifier = Modifier.size(22.dp),
                    )
                } else if (badgeText != null) {
                    // Live tnum badge as the disc's whole content (mockup
                    // `.disc .badge` 9sp/600 — the Speed disc never shows
                    // an icon).
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = Tnum,
                    )
                }
                if (hasActiveDot) {
                    // Amber status hue reserved for A-B (AbMarkAmber is the
                    // same amber the scrubber's A/B pins wear).
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = (-2).dp, y = 2.dp)
                            .background(AbMarkAmber, CircleShape)
                            .border(1.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFFABABB2),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
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
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
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
        val pillBg = if (isHw) accent.copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.20f)
        val pillBorder = if (isHw) accent.copy(alpha = 0.35f) else Color(0xFFF59E0B).copy(alpha = 0.45f)
        val textColor = if (isHw) accent else Color(0xFFFCD34D)
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
