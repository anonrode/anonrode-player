package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FontDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

internal val Tnum = TextStyle(fontFeatureSettings = "tnum")
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
                    style = MaterialTheme.typography.labelSmall,
                    style = Tnum,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncPopoverButton("-0.1s", false, accent, Modifier.weight(1f)) { onNudge(-100) }
                SyncPopoverButton("+0.1s", false, accent, Modifier.weight(1f)) { onNudge(100) }
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
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (teal) accent.copy(alpha = 0.12f) else Color(0xFF171A22)
            )
            .border(
                1.dp,
                if (teal) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
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
    content: @Composable () -> Unit,
) {
    if (!visible) return

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // Scrim backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.54f))
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
                        detectHorizontalDragGestures { change, dragAmount ->
                            if (dragAmount > 20f) {
                                change.consume()
                                onDismiss()
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
                        content()
                    }
                }
            }
        }
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(episodes) { index, video ->
                    val isPlaying = index == currentIndex || video.uri == currentMediaUri
                    val cardBg = if (isPlaying) accent.container else Color.White.copy(alpha = 0.04f)
                    val cardBorder = if (isPlaying) accent.border else Color.White.copy(alpha = 0.07f)
                    val textColor = if (isPlaying) accent.primary else Color(0xFFE2E8F0)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(cardBg)
                            .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
                            .clickable {
                                onSelectEpisode(video)
                                onDismiss()
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Left active indicator
                        if (isPlaying) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(accent.primary)
                            )
                            Spacer(Modifier.width(8.dp))
                        }

                        // Thumbnail badge placeholder with duration
                        Box(
                            modifier = Modifier
                                .size(width = 68.dp, height = 42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E2530))
                                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            if (isPlaying) {
                                Icon(
                                    imageVector = Icons.Filled.GraphicEq,
                                    contentDescription = "Playing",
                                    tint = accent.primary,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .align(Alignment.Center)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.5f),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .align(Alignment.Center)
                                )
                            }

                            if (video.durationMs > 0L) {
                                Box(
                                    modifier = Modifier
                                        .padding(2.dp)
                                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = formatDuration(video.durationMs),
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = FontFamily.Monospace,
                                        style = Tnum,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(10.dp))

                        // Title and index
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = video.title,
                                color = textColor,
                                fontSize = 12.5.sp,
                                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "Episode ${index + 1}",
                                color = if (isPlaying) accent.primary.copy(alpha = 0.8f) else Color(0xFF64748B),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Normal,
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        Icon(
                            imageVector = Icons.Filled.DragHandle,
                            contentDescription = "Reorder",
                            tint = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tier 2: Subtitle Drawer (opened via CC tap).
 * Displays master toggle, current track, Sync Subtitle calibration (first!),
 * open local file, online download, and style tuning.
 */
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
    onOnlineDownload: () -> Unit,
    onSubtitleStyle: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PlayerSlideInDrawer(
        visible = visible,
        onDismiss = onDismiss,
        title = "Subtitle",
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

            // Section 2: SUBTITLE ACTIONS (Sync Subtitle FIRST)
            Text(
                text = "SUBTITLE ACTIONS",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
            )

            // 1. Sync Subtitle Card (Syncster audio calibration)
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
                            text = "${sign}%.3fs".format(offsetSec),
                            color = if (offsetMs != 0L) accent.primary else Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            style = Tnum,
                        )
                    }

                    // Steppers [-0.1s] / [+0.1s]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .clickable { onNudge(-100L) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "− 0.1s",
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                style = Tnum,
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .clickable { onNudge(100L) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "+ 0.1s",
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                style = Tnum,
                            )
                        }
                    }

                    // Continuous Slider (-5.0s to +5.0s)
                    Slider(
                        value = (offsetMs / 1000f).coerceIn(-5f, 5f),
                        onValueChange = { newVal ->
                            val diff = (newVal * 1000f).toLong() - offsetMs
                            onNudge(diff)
                        },
                        valueRange = -5f..5f,
                        colors = SliderDefaults.colors(
                            thumbColor = accent.primary,
                            activeTrackColor = accent.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                        ),
                        modifier = Modifier.fillMaxWidth().height(24.dp)
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

            // 2. Open Local File
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

            // 3. Online Download
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .clickable {
                        onOnlineDownload()
                        onDismiss()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDownload, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Online Download", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Search OpenSubtitles repository", color = Color(0xFF64748B), fontSize = 10.5.sp)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
            }

            // 4. Subtitle Style & Font
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                    .clickable {
                        onSubtitleStyle()
                        onDismiss()
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FontDownload, null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Subtitle Style & Font", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("Size, colors, background styling", color = Color(0xFF64748B), fontSize = 10.5.sp)
                    }
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color(0xFF64748B), modifier = Modifier.size(16.dp))
            }
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
                listOf("HW", "HW+", "SW").forEach { dec ->
                    val isDecSelected = decoderModeLabel.contains(dec, ignoreCase = true) ||
                        (dec == "HW" && decoderModeLabel.startsWith("HW", ignoreCase = true) && !decoderModeLabel.contains("+"))

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
                        fontFamily = FontFamily.Monospace,
                        style = Tnum,
                    )
                }
                Slider(
                    value = currentVolume.coerceIn(0f, 1f),
                    onValueChange = onVolumeChange,
                    colors = SliderDefaults.colors(
                        thumbColor = accent.primary,
                        activeTrackColor = accent.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier.fillMaxWidth().height(24.dp)
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
                        fontFamily = FontFamily.Monospace,
                        style = Tnum,
                    )
                }
                Slider(
                    value = currentBrightness.coerceIn(0f, 1f),
                    onValueChange = onBrightnessChange,
                    colors = SliderDefaults.colors(
                        thumbColor = accent.primary,
                        activeTrackColor = accent.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                    modifier = Modifier.fillMaxWidth().height(24.dp)
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
 * Tier 2: Precision Speed Control Card.
 *
 * Implements Feature 2 from the reference recording with:
 * - Stepper buttons [−] / [+] (±0.05x granular adjustment)
 * - Large tabular readout "1.00x ✎" (extra bold monospace)
 * - Continuous precision slider (0.25x to 4.00x with 0.01x granularity)
 * - One-tap Reset button [↺] back to 1.00x
 * - Preset milestone chips (0.25x, 1.0x, 1.5x, 2.0x, 3.0x, 4.0x)
 * - Smoky translucent black cinema styling with zero bright blue clutter.
 */
@Composable
internal fun PrecisionSpeedCard(
    visible: Boolean,
    currentSpeed: Float,
    onStep: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier = modifier
            .widthIn(min = 310.dp, max = 340.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xEB0A0C10))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}, // Consume click inside
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Row 1: [-] Button, Large Tabular Speed "1.00x ✎", [+] Button, Reset [↺]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Stepper [-]
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable { onStep(-0.05f) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("−", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                // Center readout: "1.00x ✎"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = String.format(java.util.Locale.US, "%.2fx", currentSpeed),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        style = Tnum,
                    )
                    Text("✎", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }

                // Stepper [+]
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape)
                        .clickable { onStep(0.05f) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                // Reset [↺]
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .clickable { onReset() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↺", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
                }
            }

            // Row 2: Precision Slider (0.25x to 4.00x with 0.01x granularity)
            Slider(
                value = currentSpeed.coerceIn(0.25f, 4.0f),
                onValueChange = { onSetSpeed(it) },
                valueRange = 0.25f..4.0f,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White.copy(alpha = 0.85f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                ),
                modifier = Modifier.fillMaxWidth().height(24.dp),
            )

            // Row 3: Preset Chips (0.25x, 1.0x, 1.5x, 2.0x, 3.0x, 4.0x)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(0.25f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f).forEach { chipSpeed ->
                    val isChipActive = abs(currentSpeed - chipSpeed) < 0.02f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isChipActive) Color.White.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (isChipActive) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                            .clickable { onSetSpeed(chipSpeed) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (chipSpeed % 1f == 0f) "${chipSpeed.toInt()}x" else "${chipSpeed}x",
                            color = if (isChipActive) Color.White else Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}
