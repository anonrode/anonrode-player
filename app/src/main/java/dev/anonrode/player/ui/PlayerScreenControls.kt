package dev.anonrode.player.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anonrode.player.PlayerPrefs
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerScreenTopBar(
    title: String,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    episodeCount: Int,
    ui: PlayerUiState,
    quick: QuickRowUiState,
    actions: PlayerScreenActions? = null,
    onNavigateBack: () -> Unit,
    onCycleDecoderMode: () -> Unit,
    onNudgeSubtitleOffsetSec: (deltaSec: Float) -> Unit,
    onResetSubtitleOffset: () -> Unit,
    onOpenLocalSubtitlePicker: () -> Unit,
    onCaptureScreenshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeTopInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout)

    AnimatedVisibility(
        visible = ui.controlsVisible.value && !ui.locked.value,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(240, easing = OpticalGlassTokens.EaseReveal)) { -8 },
        exit = fadeOut(tween(150)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { ui.topBarHeightPx.intValue = it.height }
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.72f),
                        0.68f to Color.Black.copy(alpha = 0.24f),
                        1.0f to Color.Transparent,
                    )
                )
                .windowInsetsPadding(safeTopInsets)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ROW 1: Borderless Top Row (Back, Title + Whisper Sync Pill, Bare Right Actions)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onNavigateBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                PlayerTopBarTitleAndTechLine(
                    title = title,
                    videoResolutionFpsLabel = videoResolutionFpsLabel,
                    decoderModeLabel = decoderModeLabel,
                    liveOffsetMs = liveOffsetMs,
                    isSyncLocked = quick.isSyncLocked.value,
                    isSyncRunning = quick.subSyncRunning.value,
                    onToggleNerdStats = {
                        ui.nerdStatsVisible.value = !ui.nerdStatsVisible.value
                    },
                    onCycleDecoderMode = onCycleDecoderMode,
                    onToggleSyncPopover = {
                        quick.showSyncPopover.value = !quick.showSyncPopover.value
                    },
                    onForceResync = { actions?.onTriggerAutoSync() },
                    modifier = Modifier.weight(1f),
                )

                // Bare Right Actions: [HW+], [CC], [Playlist], [More]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onCycleDecoderMode)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = decoderModeLabel.value,
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                shadow = OpticalGlassTokens.CrispTextShadow,
                            ),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { ui.subtitleDrawerOpen.value = true },
                                onLongClick = onOpenLocalSubtitlePicker,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ClosedCaption,
                            contentDescription = "Subtitles",
                            tint = if (ui.showCC.value) Color.White else Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable { ui.playlistDrawerOpen.value = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = "Episodes",
                            tint = if (ui.playlistDrawerOpen.value) OpticalGlassTokens.EmeraldAccent else Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable { ui.threeDotsMenuOpen.value = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "Controls",
                            tint = if (ui.threeDotsMenuOpen.value) OpticalGlassTokens.EmeraldAccent else Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            // ROW 2: Pull-Out / Pull-Back ("Paper-Pull") Quick-Tools Ribbon
            QuickToolsRibbon(
                ui = ui,
                quick = quick,
                actions = actions,
                onCaptureScreenshot = onCaptureScreenshot,
            )

            // INLINE SYNC MICRO-POPOVER (1-tap nudge without opening drawer)
            AnimatedVisibility(visible = quick.showSyncPopover.value) {
                InlineSyncMicroPopover(
                    liveOffsetMs = liveOffsetMs,
                    onNudgeSec = onNudgeSubtitleOffsetSec,
                    onReset = onResetSubtitleOffset,
                    onResync = { actions?.onTriggerAutoSync() },
                )
            }

            // LIVE DECODER TELEMETRY OVERLAY
            AnimatedVisibility(visible = ui.nerdStatsVisible.value) {
                NerdTelemetryCard(
                    resolutionFps = videoResolutionFpsLabel,
                    decoderMode = decoderModeLabel,
                )
            }
        }
    }
}

/**
 * Isolated Leaf Composable for Title and Whisper-Quiet Inline Sync Status.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlayerTopBarTitleAndTechLine(
    title: String,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    isSyncLocked: Boolean = false,
    isSyncRunning: Boolean = false,
    onToggleNerdStats: () -> Unit,
    onCycleDecoderMode: () -> Unit,
    onToggleSyncPopover: () -> Unit,
    onForceResync: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val offsetSec = liveOffsetMs.value / 1000f
    val hasOffset = abs(offsetSec) >= 0.01f
    val showSyncBadge = isSyncLocked || isSyncRunning || hasOffset
    val syncText = when {
        isSyncRunning && !isSyncLocked -> "Syncing…"
        isSyncLocked -> if (offsetSec >= 0f) "✓ Synced +%.2fs".format(offsetSec) else "✓ Synced %.2fs".format(offsetSec)
        else -> if (offsetSec >= 0f) "+%.2fs".format(offsetSec) else "%.2fs".format(offsetSec)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .clickable(onClick = onToggleNerdStats),
            style = TextStyle(
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                shadow = OpticalGlassTokens.CrispTextShadow,
            ),
        )

        if (showSyncBadge) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSyncLocked || hasOffset) {
                            OpticalGlassTokens.EmeraldAccent.copy(alpha = 0.18f)
                        } else {
                            Color.Black.copy(alpha = 0.50f)
                        }
                    )
                    .combinedClickable(
                        onClick = onToggleSyncPopover,
                        onLongClick = onForceResync,
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSyncLocked || hasOffset) OpticalGlassTokens.EmeraldSync else Color.White.copy(alpha = 0.6f)
                        ),
                )
                Text(
                    text = syncText,
                    style = TextStyle(
                        color = if (isSyncLocked || hasOffset) OpticalGlassTokens.EmeraldSync else Color.White.copy(alpha = 0.85f),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFeatureSettings = "tnum",
                    ),
                )
            }
        }
    }
}

/**
 * Pull-Out / Pull-Back ("Drawing Out Paper") Quick-Tools Ribbon.
 * - Collapsed (`progress = 0f`): 4 core circular dark discs + `›` pull handle, zero labels.
 * - Dragging rightward on the handle or row smoothly draws out all remaining tools with
 *   spring physics + velocity snap (`>0.32f` or `>420dp/s`), revealing labels underneath.
 * - Dragging leftward (or tapping `‹`) smoothly tucks the tools back in.
 */
@Composable
private fun QuickToolsRibbon(
    ui: PlayerUiState,
    quick: QuickRowUiState,
    actions: PlayerScreenActions?,
    onCaptureScreenshot: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    // Restore persisted ribbon order & hidden set once
    LaunchedEffect(Unit) {
        val defaultNames = RibbonTool.entries.map { it.name }
        val savedOrder = PlayerPrefs.ribbonOrder(context, defaultNames)
            .mapNotNull { name -> RibbonTool.entries.firstOrNull { it.name == name } }
        if (savedOrder.isNotEmpty()) {
            quick.ribbonOrder = savedOrder
        }
        val savedHidden = PlayerPrefs.ribbonHidden(context)
            .mapNotNull { name -> RibbonTool.entries.firstOrNull { it.name == name } }
            .toSet()
        quick.ribbonHidden = savedHidden
    }

    val pullAnim = remember { Animatable(if (quick.ribbonExpanded.value) 1f else 0f) }

    LaunchedEffect(quick.ribbonExpanded.value) {
        val target = if (quick.ribbonExpanded.value) 1f else 0f
        if (abs(pullAnim.value - target) > 0.01f) {
            pullAnim.animateTo(
                targetValue = target,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow),
            )
        }
        quick.ribbonProgress.floatValue = pullAnim.value
    }

    val progress = pullAnim.value.coerceIn(0f, 1f)
    val isExpanded = progress > 0.15f
    val showLabels = progress > 0.55f

    val visibleTools = quick.ribbonOrder.filterNot { it in quick.ribbonHidden }
    val coreCount = 4
    val dragSpanPx = (220f * density).coerceAtLeast(1f)

    fun settleRibbon(open: Boolean) {
        quick.ribbonExpanded.value = open
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        scope.launch {
            pullAnim.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 400f),
            )
            quick.ribbonProgress.floatValue = pullAnim.value
        }
    }

    val pullGestureModifier = Modifier.pointerInput(Unit) {
        val velocityTracker = VelocityTracker()
        detectHorizontalDragGestures(
            onDragStart = {
                ui.bumpInteraction()
                velocityTracker.resetTracking()
            },
            onHorizontalDrag = { change, dragAmount ->
                // When fully expanded and scrolled right, let HorizontalScroll handle leftward scroll first
                if (pullAnim.value >= 0.99f && quick.ribbonScroll.value > 0 && dragAmount > 0f) {
                    return@detectHorizontalDragGestures
                }
                change.consume()
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val next = (pullAnim.value + (dragAmount / dragSpanPx)).coerceIn(0f, 1f)
                scope.launch {
                    pullAnim.snapTo(next)
                    quick.ribbonProgress.floatValue = next
                }
            },
            onDragEnd = {
                val vx = velocityTracker.calculateVelocity().x
                val velocityThreshold = 420f * density
                val shouldOpen = when {
                    vx > velocityThreshold -> true
                    vx < -velocityThreshold -> false
                    else -> pullAnim.value >= 0.35f
                }
                settleRibbon(shouldOpen)
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(pullGestureModifier)
            .then(if (progress > 0.85f) Modifier.horizontalScroll(quick.ribbonScroll) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        visibleTools.forEachIndexed { index, tool ->
            val isExtra = index >= coreCount
            if (isExtra && !isExpanded) return@forEachIndexed

            val itemAlpha = if (!isExtra) 1f else ((progress - 0.10f) / 0.90f).coerceIn(0f, 1f)
            val itemSlidePx = if (!isExtra) 0f else -(1f - progress) * 28f * density

            val isActive = when (tool) {
                RibbonTool.NIGHT_MODE -> ui.nightMode.value
                RibbonTool.SPEED -> abs(ui.currentPlaybackSpeed.floatValue - 1.0f) > 0.02f
                RibbonTool.AB_REPEAT -> ui.abStartSec.floatValue >= 0f
                RibbonTool.AUDIO_EFFECT -> ui.audioEffectOn.value || ui.volumeBoostPercent.intValue > 100
                RibbonTool.EQUALIZER -> quick.equalizerOn.value
                RibbonTool.SHUFFLE -> ui.shuffleOn.value
                RibbonTool.LOOP -> ui.repeatMode.value != RepeatLoopMode.OFF
                RibbonTool.MUTE -> ui.isMuted.value
                RibbonTool.SLEEP_TIMER -> ui.sleepTimerMinutes.intValue != 0
                RibbonTool.BG_PLAY -> ui.backgroundPlayOn.value
                RibbonTool.ROTATE_LOCK -> quick.rotateMode.value != RotateMode.SENSOR
                else -> false
            }

            Column(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = itemAlpha
                        translationX = itemSlidePx
                    }
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            ui.bumpInteraction()
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            when (tool) {
                                RibbonTool.NIGHT_MODE -> ui.nightMode.value = !ui.nightMode.value
                                RibbonTool.SPEED -> ui.speedCardOpen.value = !ui.speedCardOpen.value
                                RibbonTool.AB_REPEAT -> {
                                    if (actions != null) {
                                        actions.onCycleAbRepeat()
                                    } else {
                                        ui.abStartSec.floatValue = if (ui.abStartSec.floatValue < 0f) 0f else -1f
                                    }
                                }
                                RibbonTool.AUDIO_EFFECT -> {
                                    val next = !ui.audioEffectOn.value
                                    if (actions != null) actions.onToggleAudioEffect(next) else ui.audioEffectOn.value = next
                                }
                                RibbonTool.EQUALIZER -> {
                                    if (actions != null) {
                                        actions.onOpenEqualizerSheet()
                                    } else {
                                        quick.equalizerOn.value = !quick.equalizerOn.value
                                    }
                                }
                                RibbonTool.SCREENSHOT -> onCaptureScreenshot()
                                RibbonTool.SHUFFLE -> {
                                    val next = !ui.shuffleOn.value
                                    if (actions != null) {
                                        actions.onUpdatePlaylistMode(next, ui.repeatMode.value)
                                    } else {
                                        ui.shuffleOn.value = next
                                    }
                                }
                                RibbonTool.LOOP -> {
                                    val modes = RepeatLoopMode.entries
                                    val next = modes[(ui.repeatMode.value.ordinal + 1) % modes.size]
                                    if (actions != null) {
                                        actions.onUpdatePlaylistMode(ui.shuffleOn.value, next)
                                    } else {
                                        ui.repeatMode.value = next
                                    }
                                }
                                RibbonTool.MUTE -> {
                                    val nextMuted = !ui.isMuted.value
                                    ui.isMuted.value = nextMuted
                                    actions?.onApplyVolumeBoostPercent(if (nextMuted) 0 else 100)
                                }
                                RibbonTool.SLEEP_TIMER -> {
                                    val cur = ui.sleepTimerMinutes.intValue
                                    val next = when (cur) {
                                        0 -> 15
                                        15 -> 30
                                        30 -> 60
                                        60 -> -1
                                        else -> 0
                                    }
                                    if (actions != null) actions.onSelectSleepTimer(next) else ui.sleepTimerMinutes.intValue = next
                                }
                                RibbonTool.BG_PLAY -> {
                                    val next = !ui.backgroundPlayOn.value
                                    if (actions != null) actions.onToggleBackgroundPlay(next) else ui.backgroundPlayOn.value = next
                                }
                                RibbonTool.ROTATE_LOCK -> {
                                    val modes = RotateMode.entries
                                    val next = modes[(quick.rotateMode.value.ordinal + 1) % modes.size]
                                    if (actions != null) actions.onSetRotateMode(next) else quick.rotateMode.value = next
                                }
                                RibbonTool.CUSTOMISE -> quick.showRibbonCustomise.value = true
                            }
                        },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) OpticalGlassTokens.EmeraldAccent else Color.Black.copy(alpha = 0.50f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    RibbonToolGlyph(tool = tool, ui = ui)
                }

                if (showLabels) {
                    val subLabel = when (tool) {
                        RibbonTool.SLEEP_TIMER -> when (ui.sleepTimerMinutes.intValue) {
                            0 -> "Sleep"
                            -1 -> "End Ep"
                            else -> "${ui.sleepTimerMinutes.intValue}m"
                        }
                        RibbonTool.LOOP -> when (ui.repeatMode.value) {
                            RepeatLoopMode.OFF -> "Loop"
                            RepeatLoopMode.ONE -> "Loop 1"
                            RepeatLoopMode.ALL -> "Loop All"
                        }
                        RibbonTool.ROTATE_LOCK -> when (quick.rotateMode.value) {
                            RotateMode.SENSOR -> "Rotate"
                            RotateMode.LANDSCAPE -> "Landscape"
                            RotateMode.PORTRAIT -> "Portrait"
                            RotateMode.LOCKED -> "Locked"
                        }
                        else -> tool.label
                    }
                    Text(
                        text = subLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(
                            color = Color.White.copy(alpha = 0.88f),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            shadow = OpticalGlassTokens.CrispTextShadow,
                        ),
                    )
                }
            }
        }

        // Pull-Out / Pull-Back Handle Disc (`›` / `‹`)
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.50f))
                .then(pullGestureModifier)
                .clickable {
                    ui.bumpInteraction()
                    settleRibbon(!quick.ribbonExpanded.value)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (quick.ribbonExpanded.value) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                contentDescription = if (quick.ribbonExpanded.value) "Collapse Tools" else "Expand Tools",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RibbonToolGlyph(
    tool: RibbonTool,
    ui: PlayerUiState,
) {
    when (tool) {
        RibbonTool.SPEED -> {
            val spd = ui.currentPlaybackSpeed.floatValue
            val label = if (spd % 1f == 0f) "${spd.toInt()}X" else "%.2f".format(spd).trimEnd('0').trimEnd('.') + "X"
            Text(
                text = label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }
        RibbonTool.AB_REPEAT -> {
            val label = when {
                ui.abStartSec.floatValue >= 0f && ui.abEndSec.floatValue >= 0f -> "A•B"
                ui.abStartSec.floatValue >= 0f -> "A…"
                else -> "A-B"
            }
            Text(
                text = label,
                style = TextStyle(
                    color = Color.White,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
        RibbonTool.NIGHT_MODE -> Icon(Icons.Rounded.Bedtime, null, tint = Color.White, modifier = Modifier.size(18.dp))
        RibbonTool.AUDIO_EFFECT -> Icon(Icons.Rounded.GraphicEq, null, tint = Color.White, modifier = Modifier.size(18.dp))
        RibbonTool.EQUALIZER -> Icon(Icons.Rounded.Equalizer, null, tint = Color.White, modifier = Modifier.size(18.dp))
        RibbonTool.SCREENSHOT -> Icon(Icons.Rounded.CameraAlt, null, tint = Color.White, modifier = Modifier.size(18.dp))
        RibbonTool.SHUFFLE -> Icon(Icons.Rounded.Shuffle, null, tint = Color.White, modifier = Modifier.size(18.dp))
        RibbonTool.LOOP -> Icon(
            if (ui.repeatMode.value == RepeatLoopMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
            null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        RibbonTool.MUTE -> Icon(
            if (ui.isMuted.value) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            null,
            tint = Color.White,
            modifier = Modifier.size(18.dp),
        )
        RibbonTool.SLEEP_TIMER -> Icon(Icons.Rounded.Timer, null, tint = Color.White, modifier = Modifier.size(18.dp))
        RibbonTool.BG_PLAY -> Icon(Icons.Rounded.Headphones, null, tint = Color.White, modifier = Modifier.size(18.dp))
        RibbonTool.ROTATE_LOCK -> Icon(Icons.Rounded.ScreenRotation, null, tint = Color.White, modifier = Modifier.size(18.dp))
        RibbonTool.CUSTOMISE -> Icon(Icons.Rounded.Edit, null, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun InlineSyncMicroPopover(
    liveOffsetMs: State<Long>,
    onNudgeSec: (Float) -> Unit,
    onReset: () -> Unit,
    onResync: () -> Unit,
) {
    val sec = liveOffsetMs.value / 1000f
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xD80A0A0C))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Sync %+.2fs".format(sec),
            style = TextStyle(
                color = OpticalGlassTokens.EmeraldSync,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
        )
        MicroPopoverBtn(text = "−0.05s", onClick = { onNudgeSec(-0.05f) })
        MicroPopoverBtn(text = "+0.05s", onClick = { onNudgeSec(+0.05f) })
        MicroPopoverBtn(text = "Reset", onClick = onReset)
        MicroPopoverBtn(text = "Resync", isEmerald = true, onClick = onResync)
    }
}

@Composable
private fun MicroPopoverBtn(
    text: String,
    isEmerald: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (isEmerald) OpticalGlassTokens.EmeraldAccent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = if (isEmerald) OpticalGlassTokens.EmeraldSync else Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                fontFeatureSettings = "tnum",
            ),
        )
    }
}

@Composable
private fun NerdTelemetryCard(
    resolutionFps: State<String>,
    decoderMode: State<String>,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xD80A0A0C))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val monoStyle = TextStyle(
            color = Color.White.copy(alpha = 0.90f),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text("SURFACE : SurfaceView (Hardware Underlay Layer 0)", style = monoStyle)
        Text("STREAM  : ${resolutionFps.value} • Decoder: ${decoderMode.value}", style = monoStyle)
        Text("HUD LAW : 0 Hz Track Recomp (Draw-Phase) | 1 Hz Tnum", style = monoStyle)
    }
}
