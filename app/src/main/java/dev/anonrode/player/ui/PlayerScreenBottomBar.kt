package dev.anonrode.player.ui

import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi

/* ── Bottom chrome of the player overlay ──────────────────────────────────
 *
 *   ┌──────────────────────────────────────────────────────────────────┐
 *   │ [⟳ Synced +0.06s]                sync pill (state-gated)         │
 *   │ 04:12 ━━━━━━●━━━━━━━━━  18:30   seek row (lives with the chrome) │
 *   ├──────────────────────────────────────────────────────────────────┤
 *   │ [🔒]  ⏮  |◀◀  ▶(64)  ▶▶|  ⏭   [FIT] [⛶]  transport (auto-hide)   │
 *   └──────────────────────────────────────────────────────────────────┘
 *
 * ONE layout at every width and ZERO reordering (was: a 540dp two-branch
 * layout whose narrow branch reordered the controls — muscle memory
 * differing by device width is a UX bug — and whose wide branch overflowed
 * portrait phones in v0.7.1). Left wing: Lock; center: prev / ±step seek
 * chips (hub 4.6 — the SAME configurable step the double-tap wave uses,
 * read from ui.seekStepSec) / PLAY / next; right wing: aspect (tap cycles,
 * long-press = direct pick) and fullscreen/rotate.
 *
 * The seek row lives for the whole chrome, so the scrub bubble + its live
 * frame preview never detach mid-gesture. The sync pill above is
 * state-gated (running / locked / track present), opens the sync popover
 * on tap, resyncs on long-press.
 *
 * Insets: the block paints its scrim to the screen edge (background is
 * applied BEFORE navigationBarsPadding/displayCutoutPadding in the chain)
 * and lays content clear of the nav bar, gesture area and landscape cutout
 * — the edge-to-edge targetSdk-37 build had zero inset handling before.
 *
 * The measured height of this block (INCLUDING its inset padding) is
 * published to PlayerUiState.bottomBarHeightPx; Up Next, the sync popover
 * and the style tray anchor off it instead of the old hand-tuned
 * `bottom = 140 / 210dp` magic numbers.
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
    /** Signed live subtitle offset — kept for signature compatibility. */
    liveOffsetMs: Long = 0L,
    /** Media has subtitle tracks — kept for signature compatibility. */
    hasSubtitleTrack: Boolean = false,
    /**
     * Non-null while the non-blocking "Resumed at …" pill shows (position ms).
     * Rendered INSIDE the dock so it can never cover the seekbar again — the
     * old activity-level overlay sat dead-center on the scrubber.
     */
    resumePillMs: Long? = null,
    onStartOver: () -> Unit = {},
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    actions: PlayerScreenActions,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val horizontalPadding = fluid(min = 12.dp, max = 24.dp)
    val verticalTopPadding = fluidVertical(base = 8.dp, isLandscape = isLandscape)
    val verticalBottomPadding = fluidVertical(base = 6.dp, isLandscape = isLandscape)
    val view = LocalView.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            // Scrim first, insets after — the gradient must reach the
            // physical bottom edge even when content clears the nav bar.
            .background(
                // Mockup `.dock`: transparent → black 62% at 30% → 72% at
                // the bottom edge (reaches the physical bottom via the
                // nav-bar padding below).
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.3f to Color.Black.copy(alpha = 0.62f),
                    1f to Color.Black.copy(alpha = 0.72f),
                )
            )
            .navigationBarsPadding()
            .displayCutoutPadding()
            .padding(
                start = horizontalPadding,
                top = verticalTopPadding,
                end = horizontalPadding,
                bottom = verticalBottomPadding,
            )
            .onSizeChanged {
                // Never shrink back to 0: while the chrome auto-hides the
                // transport/utility rows collapse, and overlays anchored
                // off this height would jump. The stale full-chrome height
                // keeps Up Next / popover riding steady above the seek row.
                if (it.height > 0) actions.ui.bottomBarHeightPx.intValue = it.height
            },
    ) {
        // ── Non-blocking Resume Pill (hub 8.1) — rendered INSIDE the dock:
        // it sits above the sync row and can never cover the scrubber again
        // (the old activity-level overlay sat dead-center on the track). ──
        androidx.compose.animation.AnimatedVisibility(
            visible = visible && resumePillMs != null,
            enter = fadeIn(animationSpec = tween(180)) + slideInVertically(
                animationSpec = tween(220),
                initialOffsetY = { it / 2 },
            ),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            if (resumePillMs != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xF50A0C10))
                            // hair2 (14%) per the mockup `.resume` border.
                            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val totalSec = resumePillMs / 1000L
                        Text(
                            // Mockup `.resume`: wording in --fg, the tnum time
                            // in --dim. 11.5sp.
                            text = buildAnnotatedString {
                                append("Resumed at ")
                                withStyle(SpanStyle(color = Color(0xFFABABB2))) {
                                    append("%d:%02d".format(totalSec / 60L, totalSec % 60L))
                                }
                            },
                            color = Color.White,
                            fontSize = 11.5.sp,
                            style = Tnum,
                        )
                        Text(
                            text = "Start over",
                            // Mockup `.resume .act`: --fg text on white 5% with
                            // a hair2 border, 10.5sp.
                            color = Color.White,
                            fontSize = 10.5.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                                .clickable {
                                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                    onStartOver()
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        // ── Subtitle Sync Status Pill (Directly Above Scrubber Timeline) ──
        androidx.compose.animation.AnimatedVisibility(
            visible = visible && (actions.quick.subSyncRunning.value || actions.quick.isSyncLocked.value || hasSubtitleTrack),
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                SubSyncStatusHud(
                    visible = true,
                    isSyncRunning = actions.quick.subSyncRunning.value,
                    isSyncLocked = actions.quick.isSyncLocked.value,
                    syncEnabled = actions.quick.subSyncEnabled.value,
                    liveOffsetMs = liveOffsetMs,
                    accent = accent,
                    onClick = { actions.openSyncPopover() },
                    onLongClick = { actions.resyncNow() },
                )
            }
        }

        // ── Row 1: Scrubber Timeline — ALWAYS visible (except locked/PiP) ──
        SeekBarRow(
            accent = accent,
            positionSec = positionSec,
            durationSec = durationSec,
            bufferedSec = bufferedSec,
            localSeek = localSeek,
            pendingSeekMs = actions.gestures.pendingSeekMs,
            scrubPreview = actions.ui.scrubPreview,
            abStartMs = actions.ui.abStartMs.value,
            abEndMs = actions.ui.abEndMs.value,
            onSeekCommitted = { sec ->
                actions.livePlayer.seekTo((sec * 1000).toLong())
            },
        )

        // ── Row 2: Bottom Controls (auto-hide together with chrome) ──
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(220)) +
                slideInVertically(animationSpec = tween(220)) { it / 2 },
            exit = fadeOut(animationSpec = tween(220)) +
                slideOutVertically(animationSpec = tween(220)) { it / 2 },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(fluidVertical(base = 6.dp, isLandscape = isLandscape)))
                TransportRow(
                    accent = accent,
                    isPlaying = isPlaying,
                    hasPreviousEpisode = hasPreviousEpisode,
                    hasNextEpisode = hasNextEpisode,
                    onPlayPrevious = onPlayPrevious,
                    onPlayNext = onPlayNext,
                    actions = actions,
                    onPlayPause = { actions.togglePlayPause() },
                    // Hub 4.6 single-source seek step: the chips and the
                    // double-tap wave (PlayerScreenGestures) BOTH call
                    // actions.seekDelta(±ui.seekStepSec.intValue) — one
                    // shared, persisted step, never two sources of truth.
                    onSeekBack = { actions.seekDelta(-actions.ui.seekStepSec.intValue) },
                    onSeekForward = { actions.seekDelta(actions.ui.seekStepSec.intValue) },
                )
            }
        }
    }
}

/* ── Seek-bar row — honest geometry: own three-part track, M3 Slider on top
 * with a transparent track (the Slider keeps the proven drag semantics +
 * touch slop; we stopped pretending its internal track can show a buffer).
 *
 * Tap-to-toggle: left swaps elapsed ⇄ −remaining, right swaps −remaining
 * ⇄ total duration — mockup A's default is elapsed + −remaining, each with
 * its own 9sp status label underneath (`.time small`: "elapsed"/"left").
 * ────────────────────────────────────────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
internal fun SeekBarRow(
    accent: Color,
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    localSeek: MutableFloatState,
    /** Swipe-gesture scrub target (ms, −1 = none) — see GestureUiState. */
    pendingSeekMs: MutableFloatState,
    /** Throttled frame preview for the scrub bubble — see ScrubPreviewEffect. */
    scrubPreview: State<ImageBitmap?>,
    abStartMs: Long? = null,
    abEndMs: Long? = null,
    onSeekCommitted: (Float) -> Unit,
) {
    var leftShowsRemaining by remember { mutableStateOf(false) }
    var rightShowsDuration by remember { mutableStateOf(false) }
    val posWhole = positionSec.value.toLong()
    // fmtTime() takes MILLISECONDS — passing seconds here silently rendered
    // "-0:00" on every frame (latent since the flip shipped; visible the
    // moment the remaining side became the default).
    val remMs = ((durationSec.value - positionSec.value).coerceAtLeast(0f) * 1000f).toLong()
    val leftLabel = if (leftShowsRemaining) "-${fmtTime(remMs)}" else fmtTime(posWhole * 1000L)
    val rightLabel = if (rightShowsDuration) fmtTime(durationSec.value.toLong() * 1000L)
    else "-${fmtTime(remMs)}"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier
                .widthIn(min = PlayerDimens.timeLabelMinW)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 24.dp, color = accent),
                ) { leftShowsRemaining = !leftShowsRemaining },
        ) {
            Text(
                leftLabel,
                color = Color(0xFFF8FAFC),
                fontSize = fluidText(min = 11.sp, max = 13.sp),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Start,
                style = Tnum,
            )
            // 9sp status label under the numeral (mockup `.time small`) —
            // tnum, dim2.
            Text(
                text = if (leftShowsRemaining) "left" else "elapsed",
                color = Color(0xFF78787F),
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
                style = Tnum,
            )
        }
        Box(Modifier.weight(1f).padding(horizontal = fluid(min = 6.dp, max = 12.dp))) {
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
            //    same 32dp band the slider occupies. We add 7dp horizontal padding
            //    to match the 14dp slider thumb radius (7dp) so the track start and
            //    end mathematically align 1:1 with the center of the thumb disc.
            //    Hub 4.2 layer law, in DRAW order: track white 22% → buffered
            //    white 45% (visible wherever it runs ahead of the played tip —
            //    it is never coerced up to the played fraction) → played accent.
            //    3dp heights per the approved mockup. ──
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .align(Alignment.Center)
                    .padding(horizontal = 7.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.22f))
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(bufFrac)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.45f))
                )
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(posFrac)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                if (abStartMs != null) {
                    val aFrac = (abStartMs.toFloat() / (dur * 1000f)).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth(aFrac)
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .size(width = 3.dp, height = 14.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(AbMarkAmber)
                        )
                    }
                }
                if (abEndMs != null) {
                    val bFrac = (abEndMs.toFloat() / (dur * 1000f)).coerceIn(0f, 1f)
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth(bFrac)
                    ) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .size(width = 3.dp, height = 14.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(AbMarkAmber)
                        )
                    }
                }
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
                thumb = {
                    // Hub 4.2 thumb law: 14dp WHITE knob with a soft accent
                    // glow — the mockup's
                    // `box-shadow: 0 0 0 4px accent-dim, 0 1px 4px black`.
                    Box(
                        modifier = Modifier.size(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .background(accent.copy(alpha = 0.22f), CircleShape)
                        )
                        Box(
                            Modifier
                                .size(14.dp)
                                .shadow(2.dp, CircleShape)
                                .background(Color.White, CircleShape)
                        )
                    }
                },
                // The painted Boxes above are the visible track; the M3
                // track goes transparent so only the thumb + drag surface
                // of the real Slider remain in play.
                colors = SliderDefaults.colors(
                    thumbColor = accent,
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
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .widthIn(min = PlayerDimens.timeLabelMinW)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = 24.dp, color = accent),
                ) { rightShowsDuration = !rightShowsDuration },
        ) {
            Text(
                rightLabel,
                color = Color(0xFFF8FAFC),
                fontSize = fluidText(min = 11.sp, max = 13.sp),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                style = Tnum,
            )
            Text(
                text = if (rightShowsDuration) "total" else "left",
                color = Color(0xFF78787F),
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
                style = Tnum,
                textAlign = TextAlign.End,
            )
        }
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
                    style = Tnum,
                )
            }
        }
    }
}

/* ── Aspect chip (§4.9) — the CURRENT mode abbreviation in a hairline
 * pill (mockup `.chip.deco`): tap cycles, long-press opens the picker.
 * 40dp wide × 30dp tall like the mockup chip; 48dp tall touch cell. ── */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AspectTextChip(
    abbreviation: String,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "aspectPress",
    )
    Box(
        modifier = Modifier.width(40.dp).height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .height(30.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = false, radius = 24.dp, color = accent),
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = abbreviation,
                color = Color(0xFFABABB2),
                fontSize = 10.5.sp,
                letterSpacing = 0.4.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/* ── Transport row — [🔒]  ‹10s [▶/⏸ hero] 10s›  [FIT] [⤢] (portrait, mockup A);
 *    landscape adds ⏮ before / ⏭ after the seek chips (there is room). ── */
@Composable
private fun TransportRow(
    accent: Color,
    isPlaying: Boolean,
    hasPreviousEpisode: Boolean,
    hasNextEpisode: Boolean,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    actions: PlayerScreenActions,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Box(modifier = Modifier.fillMaxWidth()) {
        // Left wing: lock, pinned to the start edge.
        Row(modifier = Modifier.align(Alignment.CenterStart)) {
            ControlChip(
                icon = if (actions.ui.locked.value) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (actions.ui.locked.value) "Controls locked" else "Lock controls",
                accent = accent,
                selected = actions.ui.locked.value,
                onClick = { actions.toggleLock() },
            )
        }

        // Center cluster: centered on the SCREEN — the mockup's hero sits on
        // the exact centerline, and leftover-space centering read 26–30dp
        // left of it in the renders. Portrait stays mockup-exact (lock ·
        // ‹10s · hero · 10s› · FIT · ⛶); prev/next remain landscape-only.
        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(
                if (isLandscape) 16.dp else 8.dp,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isLandscape) {
                ControlChip(
                    icon = Icons.Filled.SkipPrevious,
                    contentDescription = "Previous video",
                    accent = accent,
                    enabled = hasPreviousEpisode,
                    onClick = onPlayPrevious,
                )
            }
            SeekChip(
                icon = Icons.Filled.FastRewind,
                seconds = actions.ui.seekStepSec.intValue,
                accent = accent,
                contentDescription = "Seek back",
                onClick = onSeekBack,
            )
            BigPlayPauseButton(isPlaying = isPlaying, accent = accent, onClick = onPlayPause)
            SeekChip(
                icon = Icons.Filled.FastForward,
                seconds = actions.ui.seekStepSec.intValue,
                accent = accent,
                contentDescription = "Seek forward",
                onClick = onSeekForward,
            )
            if (isLandscape) {
                ControlChip(
                    icon = Icons.Filled.SkipNext,
                    contentDescription = "Next video",
                    accent = accent,
                    enabled = hasNextEpisode,
                    onClick = onPlayNext,
                )
            }
        }

        // Right wing: Aspect ratio & Fullscreen, pinned to the end edge.
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var aspectMenu by remember { mutableStateOf(false) }
            Box {
                AspectTextChip(
                    abbreviation = ZoomModes[actions.ui.zoomIdx.intValue].abbreviation,
                    accent = accent,
                    onClick = { actions.cycleAspect() },
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
                icon = Icons.Filled.CropFree,
                contentDescription = "Fullscreen",
                accent = accent,
                onClick = { actions.cycleRotateMode() },
            )
        }
    }
}

/* ── ±step seek chip (hub 4.6 / master gap 2) — flanks the hero play
 *    button. Mockup `.seekchip`: transparent chrome, a replay-style
 *    fast-rewind/forward glyph over a dim tnum step label ("10s"), 44×48dp
 *    cell, press scale 0.96 (design-law press, never a toast, no blur box).
 *    The tap is wired to the SAME actions.seekDelta the double-tap wave
 *    uses, with the SAME shared ui.seekStepSec value — one action, one
 *    step, two entry points.
 * ------------------------------------------------------------------------- */
@Composable
private fun SeekChip(
    icon: ImageVector,
    seconds: Int,
    accent: Color,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "seekChipPress",
    )
    Column(
        modifier = Modifier
            .size(width = 44.dp, height = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, color = accent),
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onClick()
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = "${seconds}s",
            color = Color.White.copy(alpha = 0.60f),
            fontSize = 9.sp,
            style = Tnum,
        )
    }
}
