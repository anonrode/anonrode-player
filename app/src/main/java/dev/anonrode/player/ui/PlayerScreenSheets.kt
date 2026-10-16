package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anonrode.player.EpisodeQueue
import dev.anonrode.player.audio.SubtitleColor
import dev.anonrode.player.audio.SubtitlePosition
import dev.anonrode.player.audio.SubtitleSize
import dev.anonrode.player.audio.SubtitleStyle
import dev.anonrode.player.core.model.Video
import dev.anonrode.player.core.ui.theme.PlayerAccentTokens
import dev.anonrode.player.core.ui.theme.rememberPlayerAccent
import kotlin.math.abs
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/* ── Popup surfaces anchored over the player: the sync popover
 * (-0.1 / +0.1 / RE-SYNC / STYLE grid, plus the auto-sync OFF escape)
 * and the subtitle style dropdown opened by long-pressing the cue.
 *
 * The top-left SYNCED chip is retired — as is the calibration banner that
 * used to collide with it at `top = 70` / `start = 14`. Sync state is now
 * read off the status strip above the seek row, which routes here on tap;
 * this popover is the working surface it opens.
 * ------------------------------------------------------------------------- */

/* ── Sync popover (the -0.1 / +0.1 / RE-SYNC / STYLE / ON/OFF grid) ───────── */
@Composable
internal fun SyncPopover(
    offsetMs: Long,
    accent: Color,
    syncEnabled: Boolean = true,
    onNudge: (Long) -> Unit,
    onResync: () -> Unit,
    onStyle: () -> Unit,
    onToggleSync: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0E1017).copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(18.dp))
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "SUBTITLE SYNC",
                    color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (!syncEnabled) "Off"
                    else "Offset " + (if (offsetMs >= 0) "+" else "") + "%.2fs".format(java.util.Locale.US, offsetMs / 1000f),
                    color = if (!syncEnabled) Color(0xFF94A3B8) else Color(0xFF5B6070),
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                )
            }
            // ±0.1 steppers — same hold-repeat law as the drawer's sync row
            // (hub 4.1: popover −0.1/+0.1 hold-repeat).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPopoverButton("-0.1s", false, accent, Modifier.weight(1f), onStep = { onNudge(-100) })
                SyncPopoverButton("+0.1s", false, accent, Modifier.weight(1f), onStep = { onNudge(100) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPopoverButton("⌖ RE-SYNC", syncEnabled, accent, Modifier.weight(1f)) { onResync() }
                SyncPopoverButton("STYLE", false, accent, Modifier.weight(1f)) { onStyle() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPopoverButton(
                    if (syncEnabled) "TURN OFF" else "TURN ON",
                    !syncEnabled,
                    accent,
                    Modifier.weight(1f),
                ) {
                    onToggleSync(!syncEnabled)
                }
                SyncPopoverButton("Close", false, accent, Modifier.weight(1f)) { onDismiss() }
            }
        }
    }
}

@Composable
private fun SyncPopoverButton(
    label: String,
    teal: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    /** Non-null = the button steps a value; it then follows the shared
     *  hold-repeat law instead of plain tap (see syncStepHoldRepeat). */
    onStep: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    var held by remember { mutableStateOf(false) }
    val scope = if (onStep != null) rememberCoroutineScope() else null

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    held -> accent.copy(alpha = 0.14f)
                    teal -> accent.copy(alpha = 0.12f)
                    else -> Color(0xFF171A22)
                }
            )
            .border(
                1.dp,
                when {
                    held -> accent.copy(alpha = 0.55f)
                    teal -> accent.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.10f)
                },
                RoundedCornerShape(12.dp),
            )
            .then(
                if (onStep != null && scope != null) {
                    Modifier.syncStepHoldRepeat(scope, onStep) { held = it }
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (teal) accent else Color(0xFFF2F4F8),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold)
    }
}

/* ── Subtitle style dropdown (long-press the subtitle to open) ───────────
 * Operates on the host-owned [SubtitleStyle] — every mutation is routed
 * through [onStyle] so the host persists it and flows it back into the
 * screen (same value the SubtitleStyleSheet live-previews).
 * ------------------------------------------------------------------------- */
@Composable
internal fun SubtitleStyleDropdown(
    style: SubtitleStyle,
    onStyle: (SubtitleStyle) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    accent: Color,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(Color(0xFF0E1017).copy(alpha = 0.96f), RoundedCornerShape(12.dp)),
    ) {
        DropdownMenuItem(
            text = { Text("Size: ${style.size.label}", color = Color.White) },
            onClick = {
                val next = SubtitleSize.entries[(style.size.ordinal + 1) % SubtitleSize.entries.size]
                onStyle(style.copy(size = next))
            },
        )
        DropdownMenuItem(
            text = { Text("Position: ${style.position.label}", color = Color.White) },
            onClick = {
                val next = SubtitlePosition.entries[(style.position.ordinal + 1) % SubtitlePosition.entries.size]
                onStyle(style.copy(position = next))
            },
        )
        DropdownMenuItem(
            text = { Text("Color: ${style.color.label}", color = Color.White) },
            onClick = {
                val next = SubtitleColor.entries[(style.color.ordinal + 1) % SubtitleColor.entries.size]
                onStyle(style.copy(color = next))
            },
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.1f))
        DropdownMenuItem(
            text = { Text("Reset", color = accent) },
            onClick = onReset,
        )
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "00:00"
    val totalSec = durationMs / 1000
    val hours = totalSec / 3600
    val min = (totalSec % 3600) / 60
    val sec = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, min, sec)
    } else {
        "%02d:%02d".format(min, sec)
    }
}

/**
 * Drawer swipe-dismiss law (hub 7.1, master gap 1 / drawer-motion spec):
 * the panel follows a rightward drag with the finger, and the release
 * dismisses when displacement exceeds [DRAWER_DISMISS_DISPLACEMENT_DP]
 * (72dp) OR release velocity exceeds [DRAWER_DISMISS_VELOCITY_DPS] (800dp/s);
 * anything gentler springs back (0.85 damping ≈ the specified slight
 * overshoot, StiffnessMediumLow ≈ the 400ms feel). The scrim stays under
 * the panel for the whole gesture; scrim tap-out and the header close
 * button keep dismissing exactly as before.
 */
private const val DRAWER_DISMISS_DISPLACEMENT_DP = 72f

/** Release velocity (dp/s) above which the swipe counts as a dismiss. */
private const val DRAWER_DISMISS_VELOCITY_DPS = 800f

/** Max panel width — dragging past it guarantees a full off-screen exit. */
private const val DRAWER_PANEL_MAX_WIDTH_DP = 380f

/**
 * Common Right Slide-In Drawer Shell with buttery cubic-bezier physics,
 * velocity-aware drag dismissal, scrim backdrop, and zero-blur cinema glass.
 */
@Composable
internal fun PlayerSlideInDrawer(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String,
    subtitle: String? = null,
    headerTrailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Wrap plain content in a scroll column. Never for LazyColumn-backed
     *  drawers — nesting them under verticalScroll is an infinite-height
     *  crash. */
    contentScrollable: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (!visible) return

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrim backdrop — never moves during drag; tap-out dismisses.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )

        // Slide-in right sheet
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(
                initialOffsetX = { it },
                animationSpec = tween(
                    durationMillis = 260,
                    easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f),
                )
            ) + fadeIn(animationSpec = tween(180)),
            exit = slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(
                    durationMillis = 200,
                    easing = FastOutLinearInEasing,
                )
            ) + fadeOut(animationSpec = tween(140)),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            // ── Drawer chassis (hub 7.1): velocity-aware swipe dismissal.
            //    The drawer slides from the right edge, so dismissal runs in
            //    the +x direction — the same direction its slide-IN uses
            //    (slideInHorizontally { it }), i.e. the motion is consistent
            //    with the existing container (which is not RTL-mirrored).
            val density = LocalDensity.current
            val scope = rememberCoroutineScope()
            val dragX = remember { Animatable(0f) }
            val velocityTracker = remember { VelocityTracker() }
            val springBackSpec = spring<Float>(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow,
            )
            val dismissThresholdPx = with(density) { DRAWER_DISMISS_DISPLACEMENT_DP.dp.toPx() }
            val dismissVelocityPx = with(density) { DRAWER_DISMISS_VELOCITY_DPS.dp.toPx() }
            val panelFullOutPx = with(density) { DRAWER_PANEL_MAX_WIDTH_DP.dp.toPx() }

            Box(
                modifier = Modifier.graphicsLayer { translationX = dragX.value }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(min = 320.dp, max = 380.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .background(Color(0xFF0C1017).copy(alpha = 0.98f))
                        .border(
                            1.dp,
                            Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
                        )
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { velocityTracker.resetTracking() },
                                onDragEnd = {
                                    val velocity = velocityTracker.calculateVelocity().x
                                    velocityTracker.resetTracking()
                                    val displacement = dragX.value
                                    if (displacement > dismissThresholdPx ||
                                        velocity > dismissVelocityPx
                                    ) {
                                        // Dismiss: follow the gesture fully
                                        // off-screen, THEN hand over to the
                                        // (instant, off-stage) exit so the
                                        // chrome never double-animates.
                                        scope.launch {
                                            dragX.animateTo(
                                                targetValue = maxOf(panelFullOutPx, displacement + 1f),
                                                animationSpec = tween(
                                                    durationMillis = 160,
                                                    easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f),
                                                ),
                                            )
                                            onDismiss()
                                        }
                                    } else {
                                        // Spring back — 0.85 damping (slight
                                        // overshoot), 400ms stiffness feel.
                                        scope.launch {
                                            dragX.animateTo(0f, animationSpec = springBackSpec)
                                        }
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        dragX.animateTo(0f, animationSpec = springBackSpec)
                                    }
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                // Track dx to the RIGHT only (+x closes this
                                // drawer); leftward drags clamp at rest.
                                val next = (dragX.value + dragAmount).coerceAtLeast(0f)
                                if (next != dragX.value) {
                                    scope.launch { dragX.snapTo(next) }
                                }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // Consume tap inside
                        )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                    // Header Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            headerTrailing?.invoke()
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Close",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        color = Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.padding(vertical = 12.dp)
                    )

                    // Content Area
                    Box(modifier = Modifier.weight(1f)) {
                        if (contentScrollable) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                content()
                            }
                        } else {
                            content()
                        }
                    }
                    } // Column
                } // panel Box (drag gestures + glass)
            } // drag-translation Box
        } // AnimatedVisibility content
    }
}

/**
 * Tier 2: Folder Sibling / Playlist Drawer.
 * Slides in from the right listing episodes in the current folder.
 */
@Composable
internal fun PlayerPlaylistDrawer(
    visible: Boolean,
    queue: EpisodeQueue?,
    currentMediaUri: String,
    accent: PlayerAccentTokens,
    onSelectEpisode: (Video) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Drag-reorder commit: (fromIndex, toIndex) inside the queue's episode list. */
    onReorder: (Int, Int) -> Unit = { _, _ -> },
) {
    val episodes = queue?.episodes ?: emptyList()
    val currentIndex = queue?.currentIndex ?: -1

    PlayerSlideInDrawer(
        visible = visible,
        onDismiss = onDismiss,
        title = "Playlist",
        subtitle = if (episodes.isNotEmpty()) "${episodes.size} Episodes" else "Current Folder",
        modifier = modifier,
    ) {
        if (episodes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No other episodes in this folder",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                )
            }
        } else {
            // Drag-reorder state: the handle tracks the pull, the row floats
            // under the pointer, and the drop commits through onReorder (the
            // host persists the order per folder — QueueOrderStore).
            val listState = rememberLazyListState()
            val view = LocalView.current
            var dragFrom by remember { mutableStateOf(-1) }
            var dragAccum by remember { mutableStateOf(0f) }
            var rowHeightPx by remember { mutableStateOf(0f) }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(episodes, key = { _, v -> v.uri }) { index, video ->
                    val isPlaying = index == currentIndex || video.uri == currentMediaUri
                    val isDragged = dragFrom == index

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .graphicsLayer { translationY = if (isDragged) dragAccum else 0f }
                            .zIndex(if (isDragged) 1f else 0f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isPlaying) accent.container else Color.Transparent)
                            .clickable {
                                onSelectEpisode(video)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left active indicator (flat rows, board C/F law).
                        if (isPlaying) {
                            Box(
                                modifier = Modifier
                                    .width(2.5.dp)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(accent.primary)
                            )
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.width(10.5.dp))
                        }

                        // Thumb: the shared hue-art frame with the duration
                        // pill bottom-right (J1 law); equalizer when playing.
                        Box(modifier = Modifier.size(width = 64.dp, height = 40.dp)) {
                            PosterArt(
                                title = video.title,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 8.dp,
                                videoUri = video.uri,
                                durationMs = video.durationMs,
                            )
                            if (isPlaying) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = "Playing",
                                    tint = accent.primary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .align(Alignment.Center),
                                )
                            }
                            if (video.durationMs > 0L) {
                                Text(
                                    text = formatDuration(video.durationMs),
                                    color = Color.White,
                                    fontSize = 8.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    style = Tnum,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(3.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color.Black.copy(alpha = 0.72f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }

                        Spacer(Modifier.width(12.dp))

                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = video.title,
                                color = if (isPlaying) accent.primary else Color(0xFFF8FAFC),
                                fontSize = 13.sp,
                                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Episode ${index + 1}",
                                color = if (isPlaying) accent.primary.copy(alpha = 0.8f) else Color(0xFF78787F),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Normal,
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // The drag handle — pull it to reorder the queue.
                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = "Reorder episode",
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier
                                .size(22.dp)
                                .pointerInput(video.uri) {
                                    detectDragGestures(
                                        onDragStart = {
                                            dragFrom = index
                                            dragAccum = 0f
                                            val measured = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.index == index }?.size?.toFloat() ?: 0f
                                            rowHeightPx = if (measured > 0f) {
                                                measured + 8.dp.toPx()
                                            } else {
                                                58.dp.toPx() + 8.dp.toPx()
                                            }
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragAccum += amount.y
                                        },
                                        onDragEnd = {
                                            val steps = dragAccum / rowHeightPx
                                            val target = (index + steps + (if (steps >= 0f) 0.5f else -0.5f))
                                                .toInt()
                                                .coerceIn(0, episodes.lastIndex)
                                            if (target != index) {
                                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                onReorder(index, target)
                                            }
                                            dragFrom = -1
                                            dragAccum = 0f
                                        },
                                        onDragCancel = {
                                            dragFrom = -1
                                            dragAccum = 0f
                                        },
                                    )
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tier 2: Subtitle Drawer (opened via CC tap).
 * Displays master toggle, current track, SYNC calibration first
 * (±0.1s hold-repeat steppers, ±5s slider, reset, forced re-sync),
 * then Open File and subtitle style tuning.
 */

/* Hold-repeat law for the ±0.1s sync steppers (design hub 6.3 + 4.1, master
 * spec §5 "Manual: −0.1/+0.1 steppers"): one nudge fires on touch-down;
 * holding past 400 ms starts repeating the nudge every 150 ms until release
 * or cancel. */
private const val SYNC_STEP_HOLD_DELAY_MS = 400L
private const val SYNC_STEP_REPEAT_MS = 150L

/**
 * Shared hold-repeat press law for every ±0.1s sync stepper (drawer discs and
 * popover step buttons): fires [onStep] once per tap; while pressed past
 * [SYNC_STEP_HOLD_DELAY_MS] it repeats [onStep] every [SYNC_STEP_REPEAT_MS].
 * The repeat job always dies on pointer up, gesture cancel, or the composable
 * leaving composition (awaitRelease unwinds → finally cancels).
 */
private fun Modifier.syncStepHoldRepeat(
    scope: CoroutineScope,
    onStep: () -> Unit,
    onHeldChange: (Boolean) -> Unit = {},
): Modifier = pointerInput(Unit) {
    detectTapGestures(
        onPress = {
            onHeldChange(true)
            onStep()
            val repeatJob = scope.launch {
                delay(SYNC_STEP_HOLD_DELAY_MS)
                while (isActive) {
                    onStep()
                    delay(SYNC_STEP_REPEAT_MS)
                }
            }
            try {
                awaitRelease()
            } finally {
                // Stop on pointer up AND on gesture cancel.
                repeatJob.cancel()
                onHeldChange(false)
            }
        },
    )
}

/**
 * One round ±0.1s sync stepper: a 48dp disc (ribbon disc anatomy) with a
 * 9sp tnum label underneath, following the shared hold-repeat law.
 */
@Composable
private fun SyncStepDisc(
    glyph: String,
    label: String,
    accentColor: Color,
    onStep: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pressScale by animateFloatAsState(
        targetValue = if (held) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
        label = "syncStepPressScale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                // Press law: 0.96 scale spring with slight overshoot.
                .scale(pressScale)
                .clip(CircleShape)
                .background(if (held) accentColor.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f))
                .border(
                    1.dp,
                    if (held) accentColor.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.12f),
                    CircleShape,
                )
                .syncStepHoldRepeat(scope, onStep) { held = it },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                color = Color(0xFFE2E8F0),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                style = Tnum,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            style = Tnum,
        )
    }
}

@Composable
internal fun PlayerSubtitleDrawer(
    visible: Boolean,
    showCC: Boolean,
    currentChoiceLabel: String?,
    offsetMs: Long,
    accent: PlayerAccentTokens,
    onToggleShowCC: () -> Unit,
    onNudge: (Long) -> Unit,
    onResetOffset: () -> Unit,
    onResync: () -> Unit,
    onOpenLocalFile: () -> Unit,
    /** Subtitle appearance (hub 6.6 merge): tuned inline, persisted by the
     *  host through [onStyleChanged] — same path the retired tray used. */
    style: SubtitleStyle = SubtitleStyle(),
    onStyleChanged: (SubtitleStyle) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSlideInDrawer(
        visible = visible,
        onDismiss = onDismiss,
        title = "Subtitle",
        contentScrollable = true,
        headerTrailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    text = if (showCC) "ON" else "OFF",
                    color = if (showCC) accent.primary else Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Switch(
                    checked = showCC,
                    onCheckedChange = { onToggleShowCC() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = accent.primary,
                        uncheckedThumbColor = Color(0xFF94A3B8),
                        uncheckedTrackColor = Color(0xFF1E293B),
                    ),
                    modifier = Modifier.size(width = 44.dp, height = 24.dp)
                )
            }
        },
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section 1: CURRENT SUBTITLE TRACKS
            Text(
                text = "CURRENT SUBTITLE",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Active Track Radio Row — the label is the host's real
                // subtitle-source choice; a neutral placeholder when no
                // label is known (never a fabricated track name).
                val activeTrackText = currentChoiceLabel?.takeIf { it.isNotBlank() }
                    ?: "Selected subtitle track"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (showCC) accent.container else Color.White.copy(alpha = 0.04f))
                        .border(
                            1.dp,
                            if (showCC) accent.border else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { if (!showCC) onToggleShowCC() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, if (showCC) accent.primary else Color(0xFF64748B), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (showCC) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(accent.primary)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = activeTrackText,
                            color = if (showCC) accent.primary else Color(0xFFE2E8F0),
                            fontSize = 12.5.sp,
                            fontWeight = if (showCC) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (showCC) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = accent.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // None (Off) Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (!showCC) accent.container else Color.White.copy(alpha = 0.04f))
                        .border(
                            1.dp,
                            if (!showCC) accent.border else Color.White.copy(alpha = 0.08f),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { if (showCC) onToggleShowCC() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .border(1.5.dp, if (!showCC) accent.primary else Color(0xFF64748B), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!showCC) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(accent.primary)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "None (Off)",
                            color = if (!showCC) accent.primary else Color(0xFF94A3B8),
                            fontSize = 12.5.sp,
                            fontWeight = if (!showCC) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }

                    if (!showCC) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Selected",
                            tint = accent.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Section 2: SYNC (the law: sync section comes FIRST, before any
            // file/open or style actions — design hub 6.2/7.4)
            Text(
                text = "SYNC",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )

            // Sync Subtitle Card (audio-fingerprint calibration). The live
            // offset readout is the same host wiring the dock HUD reads
            // (PlayerScreen passes [offsetMs] from liveOffsetMs) — one
            // source of truth, never a second one.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, accent.border.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = null,
                                tint = accent.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Sync Subtitle",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        val sign = if (offsetMs > 0L) "+" else if (offsetMs < 0L) "-" else ""
                        val offsetSec = abs(offsetMs) / 1000f
                        Text(
                            text = "${sign}%.2fs".format(offsetSec),
                            color = if (offsetMs != 0L) accent.primary else Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            style = Tnum,
                        )
                    }

                    // Round steppers [-0.1s] / [+0.1s] — one nudge per tap,
                    // hold 400ms then repeat every 150ms until release
                    // (see SyncStepDisc). Manual offset stays the user's
                    // absolute authority; the engine itself is untouched.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                    ) {
                        SyncStepDisc(
                            glyph = "−",
                            label = "−0.1s",
                            accentColor = accent.primary,
                            onStep = { onNudge(-100L) },
                        )
                        SyncStepDisc(
                            glyph = "+",
                            label = "+0.1s",
                            accentColor = accent.primary,
                            onStep = { onNudge(100L) },
                        )
                    }

                    // Continuous Slider (-5.0s to +5.0s)
                    HairlineSlider(
                        value = (offsetMs / 1000f).coerceIn(-5f, 5f),
                        onValueChange = { newVal ->
                            val diff = (newVal * 1000f).toLong() - offsetMs
                            onNudge(diff)
                        },
                        valueRange = -5f..5f,
                        trackActive = accent.primary,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Reset & Resync
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable { onResetOffset() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Refresh, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Reset (0s)", color = Color(0xFF94A3B8), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(accent.container)
                                .border(1.dp, accent.border, RoundedCornerShape(8.dp))
                                .clickable { onResync() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Sync, null, tint = accent.primary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resync", color = accent.primary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Section 3: OPEN & STYLE (after the SYNC section, per hub 6.2)
            Text(
                text = "OPEN & STYLE",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )

            // Open Local File
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .clickable {
                        onOpenLocalFile()
                        onDismiss()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FolderOpen, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Open File", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(".srt, .ass, .vtt sidecars", color = Color(0xFF64748B), fontSize = 10.5.sp)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
            }

            // ── APPEARANCE (hub 6.6 merge + design D): the style controls
            //    live IN the drawer now — the separate inline tray is
            //    retired, so the cue stays visible while every dimension is
            //    tuned and there is no second surface to open.
            Text(
                text = "APPEARANCE",
                color = Color(0xFF78787F),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(top = 18.dp, bottom = 6.dp),
            )
            TrayRow(
                label = "Position",
                accent = accent.primary,
                options = SubtitlePosition.entries.map { it.label },
                selectedLabel = style.position.label,
                onSelect = { label ->
                    SubtitlePosition.entries.firstOrNull { it.label == label }
                        ?.let { onStyleChanged(style.copy(position = it)) }
                },
            )
            Spacer(Modifier.height(10.dp))
            TrayRow(
                label = "Size",
                accent = accent.primary,
                options = SubtitleSize.entries.map { it.label },
                selectedLabel = style.size.label,
                onSelect = { label ->
                    SubtitleSize.entries.firstOrNull { it.label == label }
                        ?.let { onStyleChanged(style.copy(size = it)) }
                },
            )
            Spacer(Modifier.height(10.dp))
            TrayRow(
                label = "Colour",
                accent = accent.primary,
                options = SubtitleColor.entries.map { it.label },
                selectedLabel = style.color.label,
                onSelect = { label ->
                    SubtitleColor.entries.firstOrNull { it.label == label }
                        ?.let { onStyleChanged(style.copy(color = it)) }
                },
            )
            Spacer(Modifier.height(10.dp))
            TrayRow(
                label = "Weight",
                accent = accent.primary,
                options = listOf("Bold", "Regular"),
                selectedLabel = if (style.bold) "Bold" else "Regular",
                onSelect = { label -> onStyleChanged(style.copy(bold = label == "Bold")) },
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

private data class GridActionItem(
    val title: String,
    val icon: ImageVector,
    val active: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsGridTile(
    item: GridActionItem,
    accent: PlayerAccentTokens,
    modifier: Modifier = Modifier,
) {
    val bg = if (item.active) accent.container else Color.White.copy(alpha = 0.05f)
    val border = if (item.active) accent.border else Color.White.copy(alpha = 0.08f)
    val tint = if (item.active) accent.primary else Color(0xFFE2E8F0)

    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable { item.onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.title,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = item.title,
                color = tint,
                fontSize = 11.sp,
                fontWeight = if (item.active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Tier 2: Player v5 Settings Drawer (replacing the old floating popup).
 *
 * Full-height right slide-in sheet with:
 * - 4x2 Quick Action Grid (A-B Repeat, Night Mode, Background Play, Audio Boost, Screen Lock, Sleep Timer, Screenshot, Equalizer)
 * - Playback Mode / Loop Switcher (Off | All | One)
 * - Audio Decoder Pipeline (HW | HW+ | SW)
 * - Volume & Brightness precision sliders
 * - Full App Settings link (→)
 */
@Composable
internal fun PlayerSettingsDrawer(
    visible: Boolean,
    accent: PlayerAccentTokens,
    abActive: Boolean = false,
    nightMode: Boolean = false,
    backgroundPlay: Boolean = false,
    volumeBoost: Int = 0,
    sleepMinutes: Int = 0,
    equalizerOn: Boolean = false,
    repeatModeLabel: String = "OFF",
    decoderModeLabel: String = "HW+SW",
    currentVolume: Float = 0.7f,
    currentBrightness: Float = 0.5f,
    onAbRepeat: () -> Unit = {},
    onNightMode: () -> Unit = {},
    onBackgroundPlay: () -> Unit = {},
    onVolumeBoost: () -> Unit = {},
    onLockScreen: () -> Unit = {},
    onSleepTimer: () -> Unit = {},
    onScreenshot: () -> Unit = {},
    onEqualizer: () -> Unit = {},
    onCycleRepeat: () -> Unit = {},
    onCycleDecoder: () -> Unit = {},
    onVolumeChange: (Float) -> Unit = {},
    onBrightnessChange: (Float) -> Unit = {},
    onOpenFullSettings: () -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSlideInDrawer(
        visible = visible,
        onDismiss = onDismiss,
        title = "Player Settings",
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Section 1: 4x2 Quick Action Grid
            Text(
                text = "QUICK ACTIONS",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )

            val gridItems = listOf(
                GridActionItem("A-B Repeat", Icons.Filled.RepeatOne, abActive) { onAbRepeat() },
                GridActionItem("Night Mode", Icons.Filled.Bedtime, nightMode) { onNightMode() },
                GridActionItem("Background", Icons.Filled.Headphones, backgroundPlay) { onBackgroundPlay() },
                GridActionItem(if (volumeBoost > 0) "+$volumeBoost% Boost" else "Audio Boost", Icons.AutoMirrored.Filled.VolumeUp, volumeBoost > 0) { onVolumeBoost() },
                GridActionItem("Screen Lock", Icons.Filled.Lock, false) { onLockScreen(); onDismiss() },
                GridActionItem(if (sleepMinutes > 0) "${sleepMinutes}m Sleep" else "Sleep Timer", Icons.Filled.Timer, sleepMinutes > 0) { onSleepTimer() },
                GridActionItem("Screenshot", Icons.Filled.PhotoCamera, false) { onScreenshot(); onDismiss() },
                GridActionItem("Equalizer", Icons.Filled.Equalizer, equalizerOn) { onEqualizer() },
            )

            for (i in 0 until gridItems.size step 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val item1 = gridItems[i]
                    val item2 = gridItems.getOrNull(i + 1)

                    SettingsGridTile(
                        item = item1,
                        accent = accent,
                        modifier = Modifier.weight(1f)
                    )

                    if (item2 != null) {
                        SettingsGridTile(
                            item = item2,
                            accent = accent,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            // Section 2: Repeat Mode Segmented Control
            Text(
                text = "REPEAT MODE",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("OFF", "ALL", "ONE").forEach { mode ->
                    val isModeSelected = repeatModeLabel.equals(mode, ignoreCase = true) ||
                        (mode == "OFF" && (repeatModeLabel.isBlank() || repeatModeLabel.equals("OFF", ignoreCase = true)))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isModeSelected) accent.primary else Color.Transparent)
                            .clickable { onCycleRepeat() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = mode,
                            color = if (isModeSelected) accent.onAccent else Color(0xFF94A3B8),
                            fontSize = 11.5.sp,
                            fontWeight = if (isModeSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            // Section 3: Audio Decoder Pipeline
            Text(
                text = "DECODER PROFILE",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // The running pipeline labels, truth first (hub 1.4): the
                // selected segment is an EXACT match — the old substring
                // check lit several segments at once for "HW+SW".
                listOf("HW+SW", "APP", "SW").forEach { dec ->
                    val isDecSelected = decoderModeLabel.equals(dec, ignoreCase = true)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDecSelected) accent.primary else Color.Transparent)
                            .clickable { onCycleDecoder() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = dec,
                            color = if (isDecSelected) accent.onAccent else Color(0xFF94A3B8),
                            fontSize = 11.5.sp,
                            fontWeight = if (isDecSelected) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }

            // Section 4: Volume & Brightness Sliders
            Text(
                text = "AUDIO & DISPLAY",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )

            // Volume Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Volume", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        text = "${(currentVolume * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        style = Tnum,
                    )
                }
                HairlineSlider(
                    value = currentVolume.coerceIn(0f, 1f),
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    trackActive = accent.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Brightness Slider
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Brightness6, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Brightness", color = Color(0xFFCBD5E1), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    Text(
                        text = "${(currentBrightness * 100).toInt()}%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        style = Tnum,
                    )
                }
                HairlineSlider(
                    value = currentBrightness.coerceIn(0f, 1f),
                    onValueChange = onBrightnessChange,
                    valueRange = 0f..1f,
                    trackActive = accent.primary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(4.dp))

            // Section 5: Full App Settings Link
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.container)
                    .border(1.dp, accent.border, RoundedCornerShape(12.dp))
                    .clickable {
                        onOpenFullSettings()
                        onDismiss()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Full App Settings",
                        color = accent.primary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = accent.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * Tier 2: Precision Speed Control Card — mockup §C (`.speedcard`).
 *
 * - Row 1: 22sp tnum readout + dim edit glyph (`.sval`), −/+ 40dp rounds
 *   (`.sminus/.splus`, white 6% over a hairline) at the right.
 * - Milestones: 0.25 / 0.5 / 1 / 1.25 / 2 / 3 / 4 as pill chips, plus the
 *   live speed as a filled accent chip whenever it sits off the grid
 *   (`.schip.on`) — the ONE filled primary of this view.
 * - Rail: uniform white-20% hairline, 7 marker ticks (`.srail .ticks`),
 *   15dp knob behind its accent halo, 0.25×/4.0× end labels underneath.
 * - Footer: "↺ Reset 1×" chip + the step law caption ("±0.05 steps ·
 *   slider 0.01").
 *
 * Dismissal is tap-outside (the PlayerScreen scrim); the mockup draws no
 * close affordance inside the card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrecisionSpeedCard(
    visible: Boolean,
    currentSpeed: Float,
    onStep: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onReset: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val current = currentSpeed.coerceIn(0.25f, 4.0f)
    // Mockup §C geometry: 330dp glass card, 22dp radius, hair2 border.
    Box(
        modifier = modifier
            .width(330.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xF50A0C10))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(22.dp))
            .padding(14.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}, // Consume click inside
            ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Row 1 (`.srow1`): readout left, steppers right ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.2f×", current),
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = Tnum,
                    )
                    // Decorative affordance from the mockup (`.sval .pen`);
                    // the value changes via steppers, chips and the slider.
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        tint = Color(0xFFABABB2),
                        modifier = Modifier.size(16.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                            .clickable { onStep(-0.05f) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("−", color = Color.White, fontSize = 18.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape)
                            .clickable { onStep(0.05f) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+", color = Color.White, fontSize = 18.sp)
                    }
                }
            }

            // ── Row 2 (`.sgrid`): milestone chips + the off-grid live speed
            //    as the accent-filled chip ──
            val milestones = listOf(0.25f, 0.5f, 1f, 1.25f, 2f, 3f, 4f)
            val entries = if (milestones.none { abs(current - it) < 0.005f }) {
                (milestones + current).sorted()
            } else {
                milestones
            }
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                entries.forEach { chipSpeed ->
                    val isChipActive = abs(current - chipSpeed) < 0.005f
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(
                                if (isChipActive) accent else Color.White.copy(alpha = 0.05f)
                            )
                            .border(
                                width = 1.dp,
                                color = if (isChipActive) Color.Transparent
                                else Color.White.copy(alpha = 0.08f),
                                shape = RoundedCornerShape(999.dp),
                            )
                            .clickable { onSetSpeed(chipSpeed) }
                            .padding(horizontal = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = speedChipLabel(chipSpeed),
                            color = if (isChipActive) Color(0xFF04150D) else Color(0xFFABABB2),
                            fontSize = 11.sp,
                            fontWeight = if (isChipActive) FontWeight.SemiBold else FontWeight.Normal,
                            style = Tnum,
                        )
                    }
                }
            }

            // ── Row 3 (`.srail`): uniform rail + ticks + haloed knob, with
            //    the 0.25×/4.0× ends underneath ──
            HairlineSlider(
                value = current,
                onValueChange = { onSetSpeed(it) },
                valueRange = 0.25f..4.0f,
                trackActive = Color.White.copy(alpha = 0.20f),
                trackRest = Color.White.copy(alpha = 0.20f),
                haloColor = accent,
                ticks = 7,
                knobDiameter = 15.dp,
                knobHaloDiameter = 25.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("0.25×", color = Color(0xFF78787F), fontSize = 9.sp, style = Tnum)
                Text("4.0×", color = Color(0xFF78787F), fontSize = 9.sp, style = Tnum)
            }

            // ── Row 4 (`.resetrow`): reset chip + the step law caption ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(999.dp))
                        .clickable { onReset() }
                        .padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = Color(0xFFABABB2),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = "Reset 1×",
                        color = Color(0xFFABABB2),
                        fontSize = 11.sp,
                        style = Tnum,
                    )
                }
                Text(
                    text = "±0.05 steps · slider 0.01",
                    color = Color(0xFF78787F),
                    fontSize = 10.sp,
                    style = Tnum,
                )
            }
        }
    }
}

/** Speed chip copy per the mockup: "0.25×", "0.5×", "1×", "1.25×" — never "1.0×". */
private fun speedChipLabel(speed: Float): String = when {
    speed % 1f == 0f -> "${speed.toInt()}×"
    (speed * 10f) % 1f == 0f -> String.format(java.util.Locale.US, "%.1f×", speed)
    else -> String.format(java.util.Locale.US, "%.2f×", speed)
}

@OptIn(ExperimentalMaterial3Api::class)
/**
 * Hairline slider law (mockup C/D/E): a 3dp painted rail — accent fill over
 * white 15% — with a 13dp white knob behind an accent halo. The Material
 * slider remains only as a transparent drag surface, because its default
 * 16dp capsule track is exactly the clunky shape the design removes.
 */
@Composable
private fun HairlineSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    trackActive: Color,
    modifier: Modifier = Modifier,
    trackRest: Color = Color.White.copy(alpha = 0.15f),
    /** Halo hue behind the knob (mockup: accent, 20%). */
    haloColor: Color = trackActive,
    /** Evenly spaced 3dp marker ticks riding above the rail (`.srail .ticks`). */
    ticks: Int? = null,
    knobDiameter: Dp = 13.dp,
    knobHaloDiameter: Dp = 18.dp,
) {
    val frac = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start))
        .coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 10.dp)
                .fillMaxWidth()
                .height(3.dp),
        ) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(trackRest))
            Box(Modifier.fillMaxWidth(frac).clip(CircleShape).background(trackActive))
        }
        if (ticks != null && ticks > 1) {
            // Marker ticks ride just above the rail like the mockup
            // (`.ticks` top 11.5 vs `.line` top 16).
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-4).dp)
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                repeat(ticks) {
                    Box(
                        Modifier
                            .size(3.dp)
                            .background(Color.White.copy(alpha = 0.5f), CircleShape)
                    )
                }
            }
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            thumb = {
                Box(Modifier.size(knobHaloDiameter), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier
                            .size(knobHaloDiameter)
                            .background(haloColor.copy(alpha = 0.20f), CircleShape),
                    )
                    Box(
                        Modifier
                            .size(knobDiameter)
                            .background(Color.White, CircleShape),
                    )
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.Transparent,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
