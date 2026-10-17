# PLAYER UI v1.0.0 — BATCH 4 SPECIFICATION
## Subsystem: Video Surface, Top-Level Orchestrator & Decommissioning
### Package: `dev.anonrode.player.ui` | TargetSdk: 37 | Android 14+

---

## 1. Batch Overview & Role in Subsystem

Batch 4 is the final convergence phase. It provides the hardware `SurfaceView` video decoder layer, binds all layers into the 4-slot top-level orchestrator (`PlayerScreenLayout` and `PlayerScreen`), updates the library cards, wires callbacks into `PlayerActivity.kt`, and permanently decommissions superseded legacy files.

### Files in this Batch:
1. **`app/src/main/java/dev/anonrode/player/ui/PlayerVideoSurface.kt`** (File 11 from Master Blueprint)
   - Layer 0 hardware `SurfaceView` binding to `ExoPlayer`.
   - First-frame poster art cache crossfade (`!hasRenderedFirstFrame && posterArtBitmap != null`).
   - Hardware-accelerated `graphicsLayer` aspect ratio & zoom transforms (`FIT`, `FILL`, `ZOOM`, `16:9`, `4:3`) without triggering view hierarchy relayout stalls.
2. **`app/src/main/java/dev/anonrode/player/ui/PlayerScreen.kt`** (File 8 from Master Blueprint)
   - Enforces the strict 4-slot architecture (`PlayerScreenLayout`): Layer 0 (Video Surface), Layer 1 (Gesture Overlay), Layer 2 (HUD Chrome), Layer 3 (Optical Glass Drawers).
   - Coordinates the 5.5s auto-hide timer via `userInteractionKey`.
   - Enforces Layer 1 hit-test priority: `PlayerSubtitleOverlay` sits ABOVE `playerGestureLayer`.
   - **Zero Full-HUD Recomposition Invariant**: `positionSec`, `durationSec`, and `bufferedSec` are passed as `State<Float>` references and NEVER read inside `PlayerScreen`.
3. **`app/src/main/java/dev/anonrode/player/ui/LibraryScreen.kt`** (File 12 from Master Blueprint)
   - §10.1 `ContinueCard`: Pure 140dp x 80dp (16:9) card with zero extraneous text below, bottom-left duration pill (`▶ 24:12`), and 3dp progress bar.
   - §10.2 `SeriesCard`: Strict modifier ordering `.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(88.dp)`, theme-aware border stroke, 112x68dp thumbnail with bottom watched progress bar, and high-contrast play button (explicit `Color.White` icon tint).
4. **Integration & Legacy Code Decommissioning**:
   - Updates `PlayerActivity.kt` to bind `rememberPlayerScreenActions` and pass `State<Float>` references.
   - Decommissions and deletes superseded legacy files:
     - `PlayerScreenOverflowSheet.kt` (superseded by `PlayerScreenSheets.kt`)
     - `PlayerScreenRibbonSheet.kt` (superseded by `PlayerScreenSheets.kt`)
     - `PlayerScreenRotateButton.kt` (superseded by `PlayerScreenControls.kt` & `PlayerScreenBottomBar.kt`)
     - `PlayerSubtitleAppearance.kt` (superseded by `PlayerScreenState.kt` & `PlayerScreenSubtitles.kt`)

---

## 2. Full Kotlin Source Code

### FILE 11: `PlayerScreenVideo.kt`
```kotlin
package dev.anonrode.player.ui

import android.graphics.Bitmap
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer

/**
 * Layer 0 Decoder Surface (`§3.1`).
 * Binds `SurfaceView` to `ExoPlayer` and applies hardware-accelerated aspect/zoom transforms
 * without triggering view hierarchy relayout stalls.
 */
@Composable
fun PlayerVideoSurface(
    player: ExoPlayer?,
    videoWidth: Int,
    videoHeight: Int,
    zoomIdx: Int,
    posterArtBitmap: Bitmap?,
    hasRenderedFirstFrame: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val surfaceView = remember(context) {
        SurfaceView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    DisposableEffect(player, surfaceView) {
        player?.setVideoSurfaceView(surfaceView)
        onDispose {
            player?.clearVideoSurfaceView(surfaceView)
        }
    }

    val mode = AspectZoomMode.entries[zoomIdx.mod(AspectZoomMode.entries.size)]
    val naturalAspect = if (videoWidth > 0 && videoHeight > 0) {
        videoWidth.toFloat() / videoHeight.toFloat()
    } else {
        16f / 9f
    }

    val targetAspect = when (mode) {
        AspectZoomMode.SIXTEEN_NINE -> 16f / 9f
        AspectZoomMode.FOUR_THREE -> 4f / 3f
        else -> naturalAspect
    }

    val targetScaleX by animateFloatAsState(
        targetValue = when (mode) {
            AspectZoomMode.FILL -> 1.18f
            AspectZoomMode.ZOOM -> 1.34f
            else -> 1.0f
        },
        animationSpec = tween(260, easing = OpticalGlassTokens.EaseReveal),
        label = "videoScaleX",
    )
    val targetScaleY by animateFloatAsState(
        targetValue = when (mode) {
            AspectZoomMode.FILL -> 1.08f
            AspectZoomMode.ZOOM -> 1.34f
            else -> 1.0f
        },
        animationSpec = tween(260, easing = OpticalGlassTokens.EaseReveal),
        label = "videoScaleY",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { surfaceView },
            modifier = Modifier
                .aspectRatio(targetAspect, matchHeightConstraintsFirst = false)
                .graphicsLayer {
                    scaleX = targetScaleX
                    scaleY = targetScaleY
                },
        )

        // First-Frame Poster Art Cache Crossfade
        AnimatedVisibility(
            visible = !hasRenderedFirstFrame && posterArtBitmap != null && !posterArtBitmap.isRecycled,
            exit = fadeOut(tween(220)),
        ) {
            if (posterArtBitmap != null && !posterArtBitmap.isRecycled) {
                Image(
                    bitmap = posterArtBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
```

---

### FILE 8: `PlayerScreen.kt` (Top-Level 4-Slot Orchestrator)
```kotlin
package dev.anonrode.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * §3. Four-Slot Screen Architecture (`AGENT.md` §2.2).
 */
@Composable
fun PlayerScreenLayout(
    videoSurfaceSlot: @Composable BoxScope.() -> Unit,
    gestureOverlaySlot: @Composable BoxScope.() -> Unit,
    hudControlsSlot: @Composable BoxScope.() -> Unit,
    sideDrawerSlot: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        videoSurfaceSlot()      // Layer 0: SurfaceView decoder, poster art cache, night mode scrim
        gestureOverlaySlot()    // Layer 1: Gesture layer, ripples, vertical pills, lock scrim, subtitle overlay
        hudControlsSlot()       // Layer 2: Top bar, quick ribbon, scrubber dock, transport
        sideDrawerSlot()        // Layer 3: 350dp optical glass right-aligned drawers
    }
}

/**
 * Top-Level Orchestrator.
 * ZERO FULL-HUD RECOMPOSITION INVARIANT:
 * [positionSec], [durationSec], and [bufferedSec] are passed as [State<Float>] references and are
 * NEVER read inside [PlayerScreen] or [hudControlsSlot].
 */
@Composable
fun PlayerScreen(
    title: String,
    positionSec: State<Float>,
    durationSec: State<Float>,
    bufferedSec: State<Float>,
    cueText: State<String>,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    chapters: List<ChapterMarker>,
    subtitleTracks: List<SubtitleTrackItem>,
    selectedSubtitleTrackId: String?,
    episodes: List<PlaylistEpisodeItem>,
    scrubPreviewBitmap: Bitmap?,
    isPipMode: Boolean,
    ui: PlayerUiState,
    gestures: GestureUiState,
    quick: QuickRowUiState,
    actions: PlayerScreenActions,
    videoSurfaceContent: @Composable BoxScope.() -> Unit,
    onNavigateBack: () -> Unit,
    onSelectSubtitleTrack: (String?) -> Unit,
    onOpenLocalSubtitlePicker: () -> Unit,
    onSelectEpisode: (PlaylistEpisodeItem) -> Unit,
    onPreviousEpisode: () -> Unit,
    onNextEpisode: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 5.5-second Auto-Hide Timer reset by userInteractionKey
    LaunchedEffect(
        ui.controlsVisible.value,
        ui.isPlaying.value,
        ui.userInteractionKey.longValue,
        ui.localSeek.floatValue >= 0f,
    ) {
        if (ui.controlsVisible.value && ui.isPlaying.value && ui.localSeek.floatValue < 0f) {
            delay(5500L)
            ui.controlsVisible.value = false
            quick.showSyncPopover.value = false
        }
    }

    // Reset double-tap ripple indicator after 450ms
    LaunchedEffect(gestures.doubleTapAccumSec.intValue) {
        if (gestures.doubleTapSide.intValue != 0) {
            delay(450L)
            gestures.doubleTapSide.intValue = 0
            gestures.doubleTapAccumSec.intValue = 0
        }
    }

    // 5.0s Resume Prompt & 2.5s Sync Toast Auto-Dismiss Timers
    TransientStatusPillTimers(ui = ui)

    PlayerScreenLayout(
        modifier = modifier,
        // =====================================================================
        // LAYER 0: VIDEO SURFACE + EYE-COMFORT NIGHT SCRIM
        // =====================================================================
        videoSurfaceSlot = {
            videoSurfaceContent()
            if (ui.nightMode.value) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFFF7314).copy(alpha = 0.22f))
                )
            }
        },
        // =====================================================================
        // LAYER 1: GESTURE DETECTOR, RIPPLES, EDGE PILLS, SUBTITLE OVERLAY
        // =====================================================================
        gestureOverlaySlot = {
            // 1A. Base Gesture Interception Layer
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .playerGestureLayer(
                        ui = ui,
                        gestures = gestures,
                        durationSecProvider = { durationSec.value },
                        currentPositionMsProvider = { positionSec.value * 1000f },
                        onToggleControls = { ui.controlsVisible.value = !ui.controlsVisible.value },
                        onDoubleTapSeek = { deltaSec, _ -> actions.onStepSeekSec(deltaSec) },
                        onPlayPauseToggle = actions::onPlayPauseToggle,
                        onPreviewKeyFrameSeekMs = actions::onPreviewKeyFrameSeekMs,
                        onCommitExactSeekMs = actions::onCommitExactSeekMs,
                        onCancelKeyframeSeek = actions::onCancelKeyframeSeek,
                        onApplyVolumeBoostPercent = actions::onApplyVolumeBoostPercent,
                        onApplyBrightnessFraction = actions::onApplyBrightnessFraction,
                        onSetPlaybackSpeed = actions::onSetPlaybackSpeed,
                        onCycleZoomMode = {
                            ui.zoomIdx.intValue = (ui.zoomIdx.intValue + 1) % AspectZoomMode.entries.size
                        },
                    )
            )

            // 1B. Double-Tap Seek Ripple Arcs (Left <35% / Right >65%)
            SeekRippleOverlay(gestures = gestures)

            // 1C. Edge-Hugged Vertical Brightness Pill (Left) & 200% Volume+Boost Pill (Right)
            VerticalGestureHudPill(
                visible = gestures.mode.value == GestureMode.BRIGHTNESS,
                isVolume = false,
                percentage = (ui.screenBrightnessFraction.floatValue * 100f).toInt(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 20.dp),
            )
            VerticalGestureHudPill(
                visible = gestures.mode.value == GestureMode.VOLUME,
                isVolume = true,
                percentage = ui.volumeBoostPercent.intValue,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp),
            )

            // 1D. Center Hold-to-Boost Milestones & Progressive Scrub Pill
            CenterGestureHudPill(
                ui = ui,
                gestures = gestures,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 84.dp),
            )

            // 1E. CRITICAL HIT-TEST INVARIANT: Subtitle Overlay sits ABOVE playerGestureLayer
            PlayerSubtitleOverlay(
                cueText = cueText,
                isPipMode = isPipMode,
                ui = ui,
                gestures = gestures,
                onUpdateSubOffsetSec = actions::onApplySubtitleOffsetSec,
            )

            // 1F. Screen Lock Scrim Unlock Chip
            AnimatedVisibility(
                visible = ui.locked.value && ui.lockScrimVisible.value,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp),
            ) {
                OpticalGlassChip(
                    label = "🔒 Locked • Tap to Unlock",
                    isActive = true,
                    onClick = {
                        ui.locked.value = false
                        ui.lockScrimVisible.value = false
                        ui.controlsVisible.value = true
                    },
                )
            }
        },
        // =====================================================================
        // LAYER 2: HUD CHROME (TOP BAR + BOTTOM DOCK)
        // =====================================================================
        hudControlsSlot = {
            PlayerScreenTopBar(
                title = title,
                videoResolutionFpsLabel = videoResolutionFpsLabel,
                decoderModeLabel = decoderModeLabel,
                liveOffsetMs = liveOffsetMs,
                episodeCount = episodes.size,
                ui = ui,
                quick = quick,
                onNavigateBack = onNavigateBack,
                onCycleDecoderMode = actions::onCycleDecoderMode,
                onNudgeSubtitleOffsetSec = { delta ->
                    actions.onApplySubtitleOffsetSec(ui.subtitleOffsetSec.floatValue + delta)
                },
                onResetSubtitleOffset = {
                    ui.subtitleBaseOffsetSec.floatValue = 0f
                    actions.onApplySubtitleOffsetSec(0f)
                },
                onOpenLocalSubtitlePicker = onOpenLocalSubtitlePicker,
                onCaptureScreenshot = actions::onCaptureScreenshot,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            PlayerScreenBottomBar(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedSec = bufferedSec,
                chapters = chapters,
                scrubPreviewBitmap = scrubPreviewBitmap,
                ui = ui,
                onPreviewScrubSec = { sec -> actions.onPreviewKeyFrameSeekMs((sec * 1000f).toLong()) },
                onCommitScrubSec = { sec -> actions.onCommitExactSeekMs((sec * 1000f).toLong()) },
                onStepSeekSec = actions::onStepSeekSec,
                onPlayPauseToggle = actions::onPlayPauseToggle,
                onPreviousEpisode = onPreviousEpisode,
                onNextEpisode = onNextEpisode,
                onCycleAspectRatio = {
                    ui.zoomIdx.intValue = (ui.zoomIdx.intValue + 1) % AspectZoomMode.entries.size
                },
                onToggleRotateMode = {
                    val modes = RotateMode.entries
                    quick.rotateMode.value = modes[(quick.rotateMode.value.ordinal + 1) % modes.size]
                },
                onStartOverFromZero = { actions.onCommitExactSeekMs(0L) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        },
        // =====================================================================
        // LAYER 3: 350dp OPTICAL GLASS RIGHT-ALIGNED DRAWERS
        // =====================================================================
        sideDrawerSlot = {
            PlayerSubtitleDrawer(
                visible = ui.subtitleDrawerOpen.value,
                tracks = subtitleTracks,
                selectedTrackId = selectedSubtitleTrackId,
                ui = ui,
                gestures = gestures,
                onSelectTrack = onSelectSubtitleTrack,
                onOpenLocalFilePicker = onOpenLocalSubtitlePicker,
                onTriggerAutoSync = actions::onTriggerAutoSync,
                onApplySubtitleOffsetSec = actions::onApplySubtitleOffsetSec,
                onDismiss = { ui.subtitleDrawerOpen.value = false },
            )

            PlayerPlaylistDrawer(
                visible = ui.playlistDrawerOpen.value,
                episodes = episodes,
                onSelectEpisode = onSelectEpisode,
                onDismiss = { ui.playlistDrawerOpen.value = false },
            )

            PlayerSettingsDrawer(
                visible = ui.threeDotsMenuOpen.value,
                ui = ui,
                quick = quick,
                onApplySpeed = actions::onSetPlaybackSpeed,
                onOpenAudioTracks = onOpenAudioTracks,
                onEnterPip = onEnterPip,
                onCaptureScreenshot = actions::onCaptureScreenshot,
                onDismiss = { ui.threeDotsMenuOpen.value = false },
            )
        },
    )
}
```

---

### FILE 12: `LibraryScreen.kt` (Section 10 Library Hub Cards)
```kotlin
package dev.anonrode.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * §10.1 Continue Watching (`ContinueCard`):
 * - Pure 140dp x 80dp (16:9) card (`AGENT.md` §4.1).
 * - All extraneous text labels below the card are removed.
 * - Overlays: Bottom-left duration pill (`▶ 24:12`) and bottom edge 3dp progress bar.
 */
@Composable
fun ContinueCard(
    thumbnail: Bitmap?,
    remainingOrDurationLabel: String,
    progressFraction: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 140.dp, height = 80.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111827))
            .clickable(onClick = onClick),
    ) {
        if (thumbnail != null && !thumbnail.isRecycled) {
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Bottom-left duration pill (`▶ 24:12`)
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 6.dp, bottom = 7.dp)
                .opticalGlass(
                    shape = RoundedCornerShape(5.dp),
                    baseColor = Color.Black.copy(alpha = 0.58f),
                    borderWidth = 0.8.dp,
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "▶ $remainingOrDurationLabel",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFeatureSettings = "tnum",
                ),
            )
        }

        // Bottom edge 3dp progress bar
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(3.dp)
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progressFraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(OpticalGlassTokens.CyanAccent),
            )
        }
    }
}

/**
 * §10.2 Series Hub (`SeriesCard`):
 * - Strict Modifier Ordering: `.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(88.dp)`
 *   so vertical padding never reduces the 88dp row height or clips thumbnails.
 * - Theme-Aware Border: `BorderStroke(1.dp, paletteTextColor.copy(alpha = 0.08f))`.
 * - Thumbnail Progress Bar along the bottom edge of the 112dp x 68dp thumbnail.
 * - Play Button Contrast: Icon tint explicitly set to `Color.White`.
 */
@Composable
fun SeriesCard(
    title: String,
    episodeCountLabel: String,
    lastWatchedSubtitle: String,
    thumbnail: Bitmap?,
    watchedFraction: Float,
    paletteSurfaceColor: Color,
    paletteTextColor: Color,
    onCardClick: () -> Unit,
    onQuickPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(88.dp),
        shape = RoundedCornerShape(14.dp),
        color = paletteSurfaceColor,
        border = BorderStroke(1.dp, paletteTextColor.copy(alpha = 0.08f)),
        onClick = onCardClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 112dp x 68dp Thumbnail with bottom watched progress bar
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 68.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B)),
            ) {
                if (thumbnail != null && !thumbnail.isRecycled) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (watchedFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(watchedFraction.coerceIn(0f, 1f))
                                .height(3.dp)
                                .background(OpticalGlassTokens.CyanAccent),
                        )
                    }
                }
            }

            // Series Title & Episode Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = paletteTextColor,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = episodeCountLabel,
                    maxLines = 1,
                    style = TextStyle(
                        color = paletteTextColor.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
                Text(
                    text = lastWatchedSubtitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(
                        color = OpticalGlassTokens.CyanAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }

            // High-Contrast Play Button (Explicit Color.White glyph tint)
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(OpticalGlassTokens.CyanAccent)
                    .clickable(onClick = onQuickPlayClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "▶",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
```

---

## 3. Mandatory Verification & Benchmark Matrix

| # | Subsystem Invariant | Implementation Mechanism | Required Verification |
| :--- | :--- | :--- | :--- |
| **1** | **0 Hz Scrubber Track Recomposition** | `OpticalSeekBarCanvas` reads `positionSec.value` & `bufferedSec.value` strictly inside `Modifier.drawWithCache { onDrawBehind { ... } }` | Layout Inspector shows 0 recompositions on `OpticalSeekBarCanvas` during active 60fps playback. |
| **2** | **1 Hz Timestamp Recomposition** | `displayPosIntSec` wrapped in `remember { derivedStateOf { positionSec.value.toInt() } }` | `SeekBarRow` elapsed/remaining `Text` recomposes at most once per second (down from 20 Hz). |
| **3** | **Top Bar & Ribbon 0 Hz Skip** | `PlayerScreenTopBar` & `QuickToolsRibbon` do not read `positionSec`, `decoderModeLabel`, or `liveOffsetMs` | Layout Inspector shows 100% skipped ticks for Back button, `[CC]`, `[Playlist]`, `[⋮]`, and Ribbon discs. |
| **4** | **Tech Truth Leaf Isolation** | `PlayerTopBarTitleAndTechLine` encapsulates `videoResolutionFpsLabel`, `decoderModeLabel`, and `liveOffsetMs` ($\ge 50\,\text{ms}$ quantized) | Tapping `[HW+SW]` or slewing audio-sub sync only recomposes `PlayerTopBarTitleAndTechLine`. |
| **5** | **Optical Glass over SurfaceView** | `Modifier.opticalGlass()` caches Outline, 3-stop directional specular border, and top luminance highlight | Zero `Brush` or `Path` allocations per frame; 100% readable over pure `#FFFFFF` video frames. |
| **6** | **Unscrimmed Left Video on Drawers** | `PlayerSlideInDrawer` full-screen backdrop uses `Color.Black.copy(alpha = 0.06f)` | Left $\sim 65\%$ of the video surface stays 100% bright and unobstructed while $350\,\text{dp}$ drawers are open. |
| **7** | **Subtitle Hit-Test & Bounds Yield** | `PlayerSubtitleOverlay` sits above `playerGestureLayer` in Layer 1; `isInsideSubtitle(offset)` guards all 4 video gesture detectors | Vertical anchor drag, horizontal sync drag, and 2-finger pinch on subtitles never trigger video seeks or Hold-to-Boost. |
| **8** | **Hold-to-Boost Scrub Lockout** | `absDx > 1.5f * absDy` checks `!ui.boostActive.value && !ui.speedScrubHudVisible.value` before entering `GestureMode.SEEK` | Horizontal finger sliding during 2.0x Hold-to-Boost steps `[0.5x..3.0x]` milestones ($34\,\text{dp/step}$) and never seeks the timeline. |
| **9** | **Keyframe Seek Cancellation Recovery** | `onDragCancel` invokes `actions.onCancelKeyframeSeek() -> player.setSeekParameters(SeekParameters.EXACT)` | System gesture interruption during horizontal scrub never leaves ExoPlayer in `CLOSEST_SYNC` mode. |
| **10** | **Dynamic Sync Adjustment Window** | `abs(currentOffset - baseOffset) > 4.8f` auto-recenters `ui.subtitleBaseOffsetSec` | Large auto-sync offsets (e.g. $+35.0\,\text{s}$) are preserved in $[+30.0\text{s} .. +40.0\text{s}]$ and never clamped to $[-5\text{s}, +5\text{s}]$. |
