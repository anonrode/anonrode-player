package dev.anonrode.player.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import kotlin.math.abs

/* ── Bottom chrome of the player overlay (v0.7.3 curated dock) ────────────
 *
 *   ┌──────────────────────────────────────────────────────────────┐
 *   │ 04:12 ━━━━━━●━━━━━━━━━  18:30            seek — ALWAYS shown │
 *   ├──────────────────────────────────────────────────────────────┤
 *   │      ‹10  ⏮   ▶(64)   ⏭  10›              transport (auto-hide)
 *   │   [✨Sync] [1.0×] [CC] [♫] [FIT] [↻]      utility  (auto-hide)
 *   └──────────────────────────────────────────────────────────────┘
 *
 * ONE layout at every width (was: a 540dp two-branch layout whose narrow
 * branch REORDERED the controls — muscle memory differing by device width
 * is a UX bug; the wide branch is what overflowed a portrait phone and hid
 * the sync toggle in v0.7.1). The rows no longer compete for a single
 * width: transport is 5 fixed items (≈300dp), utility is a pill row whose
 * LABELS collapse (sync → short form, aspect → icon-only, audio track →
 * hidden into the sheet) via BoxWithConstraints when the measured width
 * runs out. Worst case utility ≈320dp, transport ≈304dp — both fit a
 * 320dp-wide screen; no control is ever laid out past an edge again.
 *
 * Lock + PiP moved to the TOP bar (a "chrome utility", not transport), so
 * the transport row is exactly what the thumb wants: jump-back, prev,
 * PLAY, next, jump-forward. The right-edge rail is gone entirely.
 *
 * Insets: the block paints its scrim to the screen edge (background is
 * applied BEFORE navigationBarsPadding/displayCutoutPadding in the chain)
 * and lays content clear of the nav bar, gesture area and landscape cutout
 * — the edge-to-edge targetSdk-37 build had zero inset handling before.
 *
 * The measured height of this block (INCLUDING its inset padding) is
 * published to PlayerUiState.bottomBarHeightPx; the Up-Next pill and the
 * sync popover anchor off it instead of the old hand-tuned `bottom = 140 /
 * 210dp` magic numbers that broke whenever the chrome changed shape.
 * ------------------------------------------------------------------------- */

@UnstableApi
@Composable
internal fun PlayerScreenBottomBar(
    visible: Boolean,
    modifier: Modifier = Modifier,
    accent: Color,
    /** State-wrapped (v0.7.1 perf pass) — see PlayerScreen's param docs. */
    positionSec: State<Float>,
    durationSec: State<Float>,
    /** Buffered position (seconds), 10Hz-contained State like the others. */
    bufferedSec: State<Float>,
    localSeek: MutableFloatState,
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    /** Signed live subtitle offset — drives the sync hero chip's label. */
    liveOffsetMs: Long,
    /** Media has subtitle tracks (embedded OR a loaded sidecar) — the CC
     *  chip's visibility. (Was keyed on "a cue is on screen right now", so
     *  it vanished between cues.) */
    hasSubtitleTrack: Boolean,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    actions: PlayerScreenActions,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Scrim first, insets after — the gradient must reach the
            // physical bottom edge even when content clears the nav bar.
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                )
            )
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(
                start = PlayerDimens.gapLg,
                top = PlayerDimens.gapSm,
                end = PlayerDimens.gapLg,
                bottom = PlayerDimens.gapXs,
            )
            .onSizeChanged {
                // Never shrink back to 0: while the chrome auto-hides the
                // transport/utility rows collapse, and overlays anchored
                // off this height would jump. The stale full-chrome height
                // keeps Up Next / popover riding steady above the seek row.
                if (it.height > 0) actions.ui.bottomBarHeightPx.intValue = it.height
            },
    ) {
        // ── 1) Seek row — ALWAYS visible (except locked/PiP: whole block
        //    skipped by the host). Recomposes on the 10Hz tick ONLY here —
        //    same State discipline as the v0.7.1 perf pass.
        SeekBarRow(
            accent = accent,
            positionSec = positionSec,
            durationSec = durationSec,
            bufferedSec = bufferedSec,
            localSeek = localSeek,
            pendingSeekMs = actions.gestures.pendingSeekMs,
            scrubPreview = actions.ui.scrubPreview,
            onSeekCommitted = { sec ->
                actions.livePlayer.seekTo((sec * 1000).toLong())
            },
        )

        // ── 2) Transport + utility — auto-hide together (one AnimatedVisibility
        //    so they never disagree about when the chrome is up).
        var showSpeedStrip by remember { mutableStateOf(false) }

        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(220)) +
                slideInVertically(animationSpec = tween(220)) { it / 2 },
            exit = fadeOut(animationSpec = tween(220)) +
                slideOutVertically(animationSpec = tween(220)) { it / 2 },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(PlayerDimens.gapSm))
                TransportRow(
                    accent = accent,
                    isPlaying = isPlaying,
                    hasPreviousEpisode = hasPreviousEpisode,
                    hasNextEpisode = hasNextEpisode,
                    seekIncrementSec = seekIncrementSec,
                    onPlayPrevious = onPlayPrevious,
                    onPlayNext = onPlayNext,
                    onPlayPause = { actions.togglePlayPause() },
                    onSeekBack = { actions.seekBy(-seekIncrementSec) },
                    onSeekForward = { actions.seekBy(seekIncrementSec) },
                )
                if (showSpeedStrip) {
                    Spacer(Modifier.height(PlayerDimens.gapXs))
                    SpeedSelectorRow(
                        speeds = actions.speeds,
                        selectedSpeed = actions.speeds[actions.speedIdx.intValue],
                        accent = accent,
                        onSelectSpeed = { sp ->
                            actions.setSpeed(sp)
                            showSpeedStrip = false
                        },
                    )
                }
                Spacer(Modifier.height(PlayerDimens.gapXs))
                UtilityRow(
                    accent = accent,
                    liveOffsetMs = liveOffsetMs,
                    hasSubtitleTrack = hasSubtitleTrack,
                    actions = actions,
                    showSpeedStrip = showSpeedStrip,
                    onToggleSpeedStrip = { showSpeedStrip = !showSpeedStrip },
                )
            }
        }
    }
}

/* ── Seek-bar row — honest geometry: own three-part track, M3 Slider on top
 * with a transparent track (the Slider keeps the proven drag semantics +
 * touch slop; we stopped pretending its internal track can show a buffer).
 *
 * Tap-to-toggle labels unchanged (left ↔ −remaining, right ↔ current).
 * ────────────────────────────────────────────────────────────────────── */

@UnstableApi
@Composable
private fun SeekBarRow(
    accent: Color,
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    localSeek: MutableFloatState,
    /** Swipe-gesture scrub target (ms, −1 = none) — see GestureUiState. */
    pendingSeekMs: MutableFloatState,
    /** Throttled frame preview for the scrub bubble — see ScrubPreviewEffect. */
    scrubPreview: State<ImageBitmap?>,
    onSeekCommitted: (Float) -> Unit,
) {
    var showRemainingOnLeft by remember { mutableStateOf(false) }
    var showCurrentOnRight by remember { mutableStateOf(false) }
    val posWhole = positionSec.value.toLong()
    val leftLabel = if (showRemainingOnLeft) {
        "−${fmtTime(((durationSec.value - positionSec.value).coerceAtLeast(0f)).toLong())}"
    } else {
        fmtTime(posWhole * 1000L)
    }
    val rightLabel = if (showCurrentOnRight) fmtTime(posWhole * 1000L)
    else fmtTime(durationSec.value.toLong() * 1000L)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            leftLabel,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .widthIn(min = PlayerDimens.timeLabelMinW)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 32.dp, color = accent),
                ) { showRemainingOnLeft = !showRemainingOnLeft }
        )
        Box(Modifier.weight(1f).padding(horizontal = PlayerDimens.gapSm)) {
            val visualPos by animateFloatAsState(
                targetValue = when {
                    localSeek.floatValue >= 0f -> localSeek.floatValue
                    pendingSeekMs.floatValue >= 0f -> pendingSeekMs.floatValue / 1000f
                    else -> positionSec.value
                },
                animationSpec = tween(durationMillis = 100, easing = LinearEasing),
                label = "seekbar",
            )
            val dur = durationSec.value.coerceAtLeast(1f)
            val posFrac = (visualPos / dur).coerceIn(0f, 1f)
            // The buffered fraction is clamped 0..1 only, never UP to the
            // played fraction. The old coerceIn(posFrac, 1f) meant the
            // 0.45-alpha buffer box was ALWAYS at least as wide as the
            // accent box painted on top of it — the "buffer" was invisible
            // at all times, so the bar lied about progress by omission.
            // Stale/behind bufferedSec (right after a seek) now honestly
            // shows no buffer ahead.
            val bufFrac = (bufferedSec.value / dur).coerceIn(0f, 1f)
            // ── the three-part painted track, vertically centered in the
            //    same 32dp band the slider occupies ──
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .align(Alignment.Center)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f))
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(bufFrac)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.45f))
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(posFrac)
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
            }
            Slider(
                value = visualPos.coerceIn(0f, dur),
                onValueChange = { localSeek.floatValue = it },
                onValueChangeFinished = {
                    if (localSeek.floatValue >= 0f) {
                        onSeekCommitted(localSeek.floatValue)
                    }
                    localSeek.floatValue = -1f
                },
                valueRange = 0f..dur,
                // The painted Boxes above are the visible track; the M3
                // track goes transparent so only the thumb + drag surface
                // of the real Slider remain in play.
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
            )
            val scrubSec = when {
                localSeek.floatValue >= 0f -> localSeek.floatValue
                pendingSeekMs.floatValue >= 0f -> pendingSeekMs.floatValue / 1000f
                else -> -1f
            }
            if (scrubSec >= 0f) {
                ScrubBubble(
                    timeLabel = fmtTime((scrubSec * 1000).toLong()),
                    fraction = (scrubSec / dur).coerceIn(0f, 1f),
                    preview = scrubPreview.value,
                    accent = accent,
                    modifier = Modifier.align(Alignment.TopStart),
                )
            }
        }
        Text(
            rightLabel,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            modifier = Modifier
                .widthIn(min = PlayerDimens.timeLabelMinW)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 32.dp, color = accent),
                ) { showCurrentOnRight = !showCurrentOnRight }
        )
    }
}

/* ── Scrub bubble (v0.7.1, unchanged): 128×72dp frame preview + exact
 * target time, anchored to the thumb, clamped to the track. ──────────────── */
@Composable
private fun ScrubBubble(
    timeLabel: String,
    fraction: Float,
    preview: ImageBitmap?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val trackW = maxWidth
        val cardW = 128.dp
        val x = (trackW * fraction - cardW / 2).coerceIn(0.dp, (trackW - cardW).coerceAtLeast(0.dp))
        Box(
            modifier = Modifier
                .offset(x = x, y = (-88).dp)
                .size(width = cardW, height = 72.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black.copy(alpha = 0.75f))
                .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(10.dp)),
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    timeLabel,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/* ── Transport row — five fixed items, one layout everywhere ──────────────
 * ‹N · ⏮ · ▶(64) · ⏭ · N›  ≈ 304dp at gapMd, 288dp at gapSm on a 320dp
 * phone (328dp content). Haptics moved into the chip primitives.
 * ------------------------------------------------------------------------- */
@Composable
private fun TransportRow(
    accent: Color,
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    seekIncrementSec: Int,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = if (maxWidth < 330.dp) PlayerDimens.gapSm else PlayerDimens.gapMd
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimeSeekButton(
                direction = TimeSeekDirection.BACK,
                seconds = seekIncrementSec,
                accent = accent,
                onClick = onSeekBack,
            )
            EpisodeJumpButton(
                direction = EpisodeJumpDirection.PREVIOUS,
                enabled = hasPreviousEpisode,
                onClick = onPlayPrevious,
            )
            BigPlayPauseButton(isPlaying = isPlaying, accent = accent, onClick = onPlayPause)
            EpisodeJumpButton(
                direction = EpisodeJumpDirection.NEXT,
                enabled = hasNextEpisode,
                onClick = onPlayNext,
            )
            TimeSeekButton(
                direction = TimeSeekDirection.FORWARD,
                seconds = seekIncrementSec,
                accent = accent,
                onClick = onSeekForward,
            )
        }
    }
}

/* ── Utility row — the labeled daily-tool strip ───────────────────────────
 * [✨ sync] [1.0× speed] [CC] [♫ audio] [FIT aspect] [↻ rotate]
 * Every item is labeled or self-describing; the collapse tiers below are
 * the only responsive behaviour left in the dock.
 * ------------------------------------------------------------------------- */
@Composable
private fun UtilityRow(
    accent: Color,
    liveOffsetMs: Long,
    hasSubtitleTrack: Boolean,
    actions: PlayerScreenActions,
    showSpeedStrip: Boolean = false,
    onToggleSpeedStrip: () -> Unit = {},
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth
        // Label-collapse tiers (see file header): sync keeps a readable
        // state at every width; aspect falls back to its icon below 420dp;
        // the audio-track chip leaves the row below 340dp (it stays in the
        // Control Center sheet).
        val compactSync = w < 380.dp
        val showAudio = w >= 340.dp
        val showAspectLabel = w >= 420.dp

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement
                .spacedBy(PlayerDimens.gapXs, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1) the sync HERO chip (flagship feature, always in the row).
            //    weight(fill=false) makes it the row's ONLY shrinkable item:
            //    fixed-size chips (CC/audio/aspect/rotate = 48dp each, speed
            //    ~50) with gaps already need ~262dp, and on 360dp-class
            //    phones (the majority) the ~290–360dp of content width left
            //    after padding can't also fit an 80–90dp locked sync label
            //    — Compose does not shrink fixed children, so the tail of
            //    the row clipped off-screen (the 0.7.1 portrait bug class).
            //    Below the needed width the chip's label ellipsizes first.
            PlayerSubSyncToggle(
                modifier = Modifier.weight(1f, fill = false),
                enabled = actions.quick.subSyncEnabled.value,
                running = actions.quick.subSyncRunning.value,
                offsetMs = liveOffsetMs,
                accent = accent,
                onEnable = { actions.setSubSyncEnabled(true) },
                onOpenPopover = { actions.openSyncPopover() },
                onResync = { actions.resyncNow() },
                compact = compactSync,
            )

            // 2) speed — a pill that SHOWS the rate; tap = toggle inline speed strip,
            //    long-press = quick reset to 1.0×.
            TextPill(
                text = speedLabel(actions.speeds[actions.speedIdx.intValue]),
                accent = accent,
                selected = showSpeedStrip || abs(actions.speeds[actions.speedIdx.intValue] - 1f) >= 0.05f,
                onClick = onToggleSpeedStrip,
                onLongClick = { actions.setSpeed(1f) },
            )

            // 3) CC — subtitle visibility toggle; present iff the media has
            //    any subtitle source (track or sidecar), never blinks with
            //    the cue text.
            if (hasSubtitleTrack) {
                ControlChip(
                    icon = Icons.Filled.ClosedCaption,
                    contentDescription = if (actions.ui.showCC.value) {
                        "Subtitles on"
                    } else {
                        "Subtitles off"
                    },
                    accent = accent,
                    selected = actions.ui.showCC.value,
                    onClick = { actions.toggleShowCC() },
                )
            }

            // 4) audio track (width-tiered)
            if (showAudio) {
                ControlChip(
                    icon = Icons.Filled.MusicNote,
                    contentDescription = "Audio track",
                    accent = accent,
                    onClick = { actions.pickAudioTrack() },
                )
            }

            // 5) aspect — tap = instant cycle; long-press = direct pick menu
            var aspectMenu by remember { mutableStateOf(false) }
            val cycleAspect = {
                val nextIdx = (actions.ui.zoomIdx.intValue + 1) % ZoomModes.size
                actions.setZoom(nextIdx)
            }
            Box {
                if (showAspectLabel) {
                    TextPill(
                        text = ZoomModes[actions.ui.zoomIdx.intValue].abbreviation,
                        accent = accent,
                        onClick = cycleAspect,
                        onLongClick = { aspectMenu = true },
                    )
                } else {
                    ControlChip(
                        icon = Icons.Filled.AspectRatio,
                        contentDescription = "Aspect: " +
                            ZoomModes[actions.ui.zoomIdx.intValue].abbreviation,
                        accent = accent,
                        onClick = cycleAspect,
                        onLongClick = { aspectMenu = true },
                    )
                }
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
                                    Icon(Icons.Filled.Check, null, tint = accent,
                                        modifier = Modifier.size(18.dp))
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

            // 6) rotation — the one 3-state cycle + long-press-menu control
            //    (its own file; keeps its behavior untouched).
            PlayerScreenRotateButton(
                mode = actions.quick.rotateMode.value,
                accent = accent,
                onCycle = { actions.cycleRotateMode() },
                onSetMode = { actions.setRotateMode(it) },
            )
        }
    }
}

/** Inline speed selector row (0.5× to 2.0×) toggled smoothly without covering the screen. */
@Composable
private fun SpeedSelectorRow(
    speeds: List<Float>,
    selectedSpeed: Float,
    accent: Color,
    onSelectSpeed: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(PlayerDimens.gapSm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        speeds.forEach { sp ->
            val isSelected = abs(sp - selectedSpeed) < 0.05f
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (isSelected) accent.copy(alpha = 0.25f)
                        else Color.Black.copy(alpha = 0.6f)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) accent else Color.White.copy(alpha = 0.20f),
                        shape = RoundedCornerShape(999.dp),
                    )
                    .clickable { onSelectSpeed(sp) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = speedLabel(sp),
                    color = if (isSelected) accent else Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}
