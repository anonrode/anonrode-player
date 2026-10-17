package dev.anonrode.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun PlayerScreenTopBar(
    title: String,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    episodeCount: Int,
    ui: PlayerUiState,
    quick: QuickRowUiState,
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
        enter = fadeIn(tween(240)) + slideInVertically(tween(280, easing = OpticalGlassTokens.EaseReveal)) { -8 },
        exit = fadeOut(tween(160)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { ui.topBarHeightPx.intValue = it.height }
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Black.copy(alpha = 0.44f),
                        0.68f to Color.Black.copy(alpha = 0.14f),
                        1.0f to Color.Transparent,
                    )
                )
                .windowInsetsPadding(safeTopInsets)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ROW 1: 48dp Centerline (Back Button + Isolated Title/Tech Leaf + Right Chips)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OpticalGlassDiscButton(
                    onClick = onNavigateBack,
                    discSize = 36.dp,
                    touchTargetSize = 48.dp,
                ) {
                    Text(
                        text = "←",
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            shadow = OpticalGlassTokens.CrispTextShadow,
                        ),
                    )
                }

                // LEAF COMPOSABLE: Isolates decoderModeLabel & liveOffsetMs recomposition
                PlayerTopBarTitleAndTechLine(
                    title = title,
                    videoResolutionFpsLabel = videoResolutionFpsLabel,
                    decoderModeLabel = decoderModeLabel,
                    liveOffsetMs = liveOffsetMs,
                    onToggleNerdStats = {
                        ui.nerdStatsVisible.value = !ui.nerdStatsVisible.value
                    },
                    onCycleDecoderMode = onCycleDecoderMode,
                    onToggleSyncPopover = {
                        quick.showSyncPopover.value = !quick.showSyncPopover.value
                    },
                    modifier = Modifier.weight(1f),
                )

                // Right Chips: [CC], [Playlist], [⋮]
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OpticalGlassChip(
                        label = "CC",
                        isActive = ui.showCC.value,
                        onClick = { ui.subtitleDrawerOpen.value = true },
                        onLongClick = onOpenLocalSubtitlePicker,
                    )
                    if (episodeCount > 1) {
                        OpticalGlassChip(
                            label = "$episodeCount Ep",
                            isActive = ui.playlistDrawerOpen.value,
                            onClick = { ui.playlistDrawerOpen.value = true },
                        )
                    }
                    OpticalGlassDiscButton(
                        onClick = { ui.threeDotsMenuOpen.value = true },
                        isActive = ui.threeDotsMenuOpen.value,
                    ) {
                        Text(
                            text = "⋮",
                            style = TextStyle(
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                        )
                    }
                }
            }

            // ROW 2: Collapsible Quick-Action Ribbon (First 5 tools + Chevron)
            QuickToolsRibbon(
                ui = ui,
                quick = quick,
                onCaptureScreenshot = onCaptureScreenshot,
            )

            // INLINE SYNC MICRO-POPOVER (1-tap nudge without opening drawer)
            AnimatedVisibility(visible = quick.showSyncPopover.value) {
                InlineSyncMicroPopover(
                    liveOffsetMs = liveOffsetMs,
                    onNudgeSec = onNudgeSubtitleOffsetSec,
                    onReset = onResetSubtitleOffset,
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
 * Isolated Leaf Composable per §6.2 Recomposition Isolation Law.
 * Reads [decoderModeLabel] and [liveOffsetMs] inside its own scope so changes never recompose
 * the Back button, right chips, or QuickToolsRibbon.
 */
@Composable
fun PlayerTopBarTitleAndTechLine(
    title: String,
    videoResolutionFpsLabel: State<String>,
    decoderModeLabel: State<String>,
    liveOffsetMs: State<Long>,
    onToggleNerdStats: () -> Unit,
    onCycleDecoderMode: () -> Unit,
    onToggleSyncPopover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offsetSec = liveOffsetMs.value / 1000f
    val hasOffset = abs(offsetSec) >= 0.01f
    val signedOffsetText = if (offsetSec >= 0f) {
        "Sync +%.2fs".format(offsetSec)
    } else {
        "Sync %.2fs".format(offsetSec)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                shadow = OpticalGlassTokens.CrispTextShadow,
            ),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 1. Resolution & FPS Micro-Pill (Toggles Nerd Telemetry)
            TechMicroPill(
                text = videoResolutionFpsLabel.value,
                onClick = onToggleNerdStats,
            )
            Text(
                text = "•",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
            )
            // 2. Interactive Decoder Hot-Swap Micro-Pill (HW+ / HW / SW)
            TechMicroPill(
                text = decoderModeLabel.value,
                isHighlighted = true,
                accentColor = OpticalGlassTokens.CyanAccent,
                onClick = onCycleDecoderMode,
            )
            Text(
                text = "•",
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 11.sp,
            )
            // 3. Interactive Subtitle Sync Micro-Pill
            TechMicroPill(
                text = signedOffsetText,
                isHighlighted = hasOffset,
                accentColor = OpticalGlassTokens.EmeraldSync,
                onClick = onToggleSyncPopover,
            )
        }
    }
}

@Composable
private fun TechMicroPill(
    text: String,
    onClick: () -> Unit,
    isHighlighted: Boolean = false,
    accentColor: Color = OpticalGlassTokens.CyanAccent,
) {
    Box(
        modifier = Modifier
            .height(20.dp)
            .opticalGlass(
                shape = RoundedCornerShape(5.dp),
                isActive = isHighlighted,
                accentColor = accentColor,
                borderWidth = 0.8.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
                shadow = OpticalGlassTokens.CrispTextShadow,
            ),
        )
    }
}

@Composable
private fun QuickToolsRibbon(
    ui: PlayerUiState,
    quick: QuickRowUiState,
    onCaptureScreenshot: () -> Unit,
) {
    val visibleTools = quick.ribbonOrder.filterNot { it in quick.ribbonHidden }
    val expanded = quick.ribbonExpanded.value
    val displayedTools = if (expanded) visibleTools else visibleTools.take(5)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier.horizontalScroll(quick.ribbonScroll) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        displayedTools.forEach { tool ->
            val isActive = when (tool) {
                RibbonTool.NIGHT_MODE -> ui.nightMode.value
                RibbonTool.SPEED -> ui.currentPlaybackSpeed.floatValue != 1.0f
                RibbonTool.AB_REPEAT -> ui.abStartSec.floatValue >= 0f
                RibbonTool.AUDIO_EFFECT -> ui.audioEffectOn.value
                RibbonTool.EQUALIZER -> quick.equalizerOn.value
                RibbonTool.SHUFFLE -> ui.shuffleOn.value
                RibbonTool.LOOP -> ui.repeatMode.value != RepeatLoopMode.OFF
                RibbonTool.MUTE -> ui.isMuted.value
                RibbonTool.BG_PLAY -> ui.backgroundPlayOn.value
                else -> false
            }

            OpticalGlassDiscButton(
                isActive = isActive,
                discSize = 36.dp,
                touchTargetSize = 38.dp,
                onClick = {
                    ui.bumpInteraction()
                    when (tool) {
                        RibbonTool.NIGHT_MODE -> ui.nightMode.value = !ui.nightMode.value
                        RibbonTool.SPEED -> ui.speedCardOpen.value = !ui.speedCardOpen.value
                        RibbonTool.EQUALIZER -> quick.equalizerOn.value = !quick.equalizerOn.value
                        RibbonTool.AUDIO_EFFECT -> ui.audioEffectOn.value = !ui.audioEffectOn.value
                        RibbonTool.SHUFFLE -> ui.shuffleOn.value = !ui.shuffleOn.value
                        RibbonTool.MUTE -> ui.isMuted.value = !ui.isMuted.value
                        RibbonTool.BG_PLAY -> ui.backgroundPlayOn.value = !ui.backgroundPlayOn.value
                        RibbonTool.SCREENSHOT -> onCaptureScreenshot()
                        RibbonTool.CUSTOMISE -> quick.showRibbonCustomise.value = true
                        else -> Unit
                    }
                },
            ) {
                val glyph = when (tool) {
                    RibbonTool.NIGHT_MODE -> "🌙"
                    RibbonTool.SPEED -> "${ui.currentPlaybackSpeed.floatValue}x"
                    RibbonTool.AB_REPEAT -> "A-B"
                    RibbonTool.AUDIO_EFFECT -> "FX"
                    RibbonTool.EQUALIZER -> "🎛"
                    RibbonTool.SCREENSHOT -> "📸"
                    RibbonTool.SHUFFLE -> "🔀"
                    RibbonTool.LOOP -> "🔁"
                    RibbonTool.MUTE -> "🔇"
                    RibbonTool.SLEEP_TIMER -> "⏲"
                    RibbonTool.BG_PLAY -> "🎧"
                    RibbonTool.ROTATE_LOCK -> "🔄"
                    RibbonTool.CUSTOMISE -> "✏"
                }
                Text(
                    text = glyph,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFeatureSettings = "tnum",
                        shadow = OpticalGlassTokens.CrispTextShadow,
                    ),
                )
            }
        }

        // Expand / Collapse Chevron Disc
        OpticalGlassDiscButton(
            discSize = 36.dp,
            touchTargetSize = 38.dp,
            onClick = { quick.ribbonExpanded.value = !expanded },
        ) {
            Text(
                text = if (expanded) "‹" else "›",
                style = TextStyle(
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

@Composable
private fun InlineSyncMicroPopover(
    liveOffsetMs: State<Long>,
    onNudgeSec: (Float) -> Unit,
    onReset: () -> Unit,
) {
    val sec = liveOffsetMs.value / 1000f
    Row(
        modifier = Modifier
            .opticalGlass(
                shape = RoundedCornerShape(12.dp),
                baseColor = OpticalGlassTokens.SmokedHudPillBase,
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Sub Sync: %+.2fs".format(sec),
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFeatureSettings = "tnum",
            ),
        )
        TechMicroPill(text = "−0.05s", onClick = { onNudgeSec(-0.05f) })
        TechMicroPill(text = "+0.05s", onClick = { onNudgeSec(+0.05f) })
        TechMicroPill(text = "Reset", onClick = onReset)
    }
}

@Composable
private fun NerdTelemetryCard(
    resolutionFps: State<String>,
    decoderMode: State<String>,
) {
    Column(
        modifier = Modifier
            .opticalGlass(
                shape = RoundedCornerShape(12.dp),
                baseColor = OpticalGlassTokens.SmokedHudPillBase,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val monoStyle = TextStyle(
            color = Color.White.copy(alpha = 0.90f),
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            shadow = OpticalGlassTokens.CrispTextShadow,
        )
        Text("SURFACE : SurfaceView (Hardware Underlay Layer 0)", style = monoStyle)
        Text("STREAM  : ${resolutionFps.value} • Mode: ${decoderMode.value}", style = monoStyle)
        Text("HUD LAW : 0 Hz Track Recomp (Draw-Phase) | 1 Hz Tnum", style = monoStyle)
    }
}
