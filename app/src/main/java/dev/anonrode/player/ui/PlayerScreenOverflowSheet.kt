package dev.anonrode.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ── Control Center (was "the overflow sheet") — v0.7.3 ──────────────────
 * The old sheet was a FLAT 14-tile grid with three tiles that all opened
 * the same thing (Cast / Speaker / Headphones), two that ran the SAME
 * action (Aspect ≡ Zoom), a blind cycler for speed (now a labeled pill in
 * the dock), a sleep timer that could only pick Off↔end-episode, and —
 * worst — it did NOT contain the app's three dead-end surfaces: the
 * subtitle source picker, the 5-band EQ panel and subtitle style editor
 * were wired from PlayerActivity but had no entry point anywhere.
 *
 * This rewrite is sectioned (Playback / Picture / Audio / Subtitles) with
 * every tile showing its live current value, active state in the SKIN
 * accent, and the dead ends wired:
 * subtitle source, subtitle style, the EQ panel, the full sleep option
 * list, and Settings as a footer.
 *
 * Speed and aspect deliberately do NOT appear here — they live in the dock
 * as labeled pills with direct-pick dropdowns (one home per control is
 * the rule this round enforces). PiP and lock live in the top bar.
 * ------------------------------------------------------------------------- */

/** Inputs the tiles need to render their current state. */
internal data class ControlCenterState(
    val abStartMs: Long?,
    val abEndMs: Long?,
    val sleep: SleepTimerUiState,
    val skipIncrementSec: Int,
    val equalizerOn: Boolean,
    val castRouteName: String?,
    /** The active subtitle source choice ("" = none) — labels the tile. */
    val subtitleChoiceLabel: String,
    val decoderModeLabel: String = "HW+SW",
    val rebuildingDecoder: Boolean = false,
    val volumeBoostPct: Int = 0,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerControlCenterSheet(
    visible: Boolean,
    state: ControlCenterState,
    accent: Color,
    onDismiss: () -> Unit,
    onAbRepeat: () -> Unit,
    onSkipLengthCycle: () -> Unit,
    onSleepOption: (SleepOption) -> Unit,
    onEqualizerToggle: () -> Unit,
    onOpenEqPanel: () -> Unit,
    onAudioTrack: () -> Unit,
    onAudioOutput: () -> Unit,
    onVolumeBoost: () -> Unit,
    onCaptureFrame: () -> Unit,
    onDecoder: () -> Unit,
    onSubtitleSource: () -> Unit,
    onSubtitleStyle: () -> Unit,
    onShareSyncLog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Sleep expands IN PLACE — the old "cycles Off ↔ end" tile is gone;
    // the full SleepOptions list is reachable.
    var sleepExpanded by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OverlayPanelBg,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.25f))
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Control Center",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    state.subtitleChoiceLabel,
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ── Playback ──
            SectionHeader("Playback")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val abActive = state.abStartMs != null
                CenterTile(
                    icon = Icons.Filled.Repeat,
                    label = "A-B repeat",
                    value = when {
                        state.abStartMs == null -> "Off"
                        state.abEndMs == null -> "A set"
                        else -> fmtTime(state.abStartMs!!) + "–" +
                            fmtTime(state.abEndMs!!)
                    },
                    active = abActive,
                    accent = accent,
                    onClick = { onAbRepeat(); onDismiss() },
                )
                CenterTile(
                    icon = Icons.Filled.Timelapse,
                    label = "Skip length",
                    value = state.skipIncrementSec.toString() + "s",
                    active = false,
                    accent = accent,
                    onClick = onSkipLengthCycle, // stays open — cycle is visible
                )
                CenterTile(
                    icon = Icons.Filled.Bedtime,
                    label = "Sleep timer",
                    value = state.sleep.selection.value.label,
                    active = state.sleep.active,
                    accent = accent,
                    onClick = { sleepExpanded = !sleepExpanded },
                )
            }
            if (sleepExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(vertical = 4.dp),
                ) {
                    SleepOptions.forEach { opt ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSleepOption(opt)
                                    sleepExpanded = false
                                }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                opt.label,
                                color = if (state.sleep.isSelected(opt)) accent else Color.White,
                                fontSize = 14.sp,
                                fontWeight = if (state.sleep.isSelected(opt)) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (state.sleep.isSelected(opt)) {
                                Text("on", color = accent, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // ── Picture ──
            SectionHeader("Picture")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CenterTile(
                    icon = Icons.Filled.PhotoCamera,
                    label = "Capture frame",
                    value = "Save PNG",
                    active = false,
                    accent = accent,
                    onClick = { onCaptureFrame(); onDismiss() },
                )
                CenterTile(
                    icon = Icons.Filled.Memory,
                    label = "Decoder",
                    value = if (state.rebuildingDecoder) "Rebuilding…"
                    else state.decoderModeLabel,
                    active = false,
                    accent = accent,
                    onClick = { onDecoder(); onDismiss() },
                )
            }

            // ── Audio ──
            SectionHeader("Audio")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CenterTile(
                    icon = Icons.Filled.VolumeUp,
                    label = "Volume boost",
                    value = if (state.volumeBoostPct > 0) "+${state.volumeBoostPct}%" else "Off",
                    active = state.volumeBoostPct > 0,
                    accent = accent,
                    onClick = onVolumeBoost, // stays open — value flips live
                )
                CenterTile(
                    icon = Icons.Filled.Equalizer,
                    label = "Equalizer",
                    value = if (state.equalizerOn) "On" else "Off",
                    active = state.equalizerOn,
                    accent = accent,
                    onClick = onEqualizerToggle,
                    onLongClick = { onOpenEqPanel(); onDismiss() },
                )
                CenterTile(
                    icon = Icons.Filled.MusicNote,
                    label = "Audio track",
                    value = "Pick…",
                    active = false,
                    accent = accent,
                    onClick = { onAudioTrack(); onDismiss() },
                )
                // Cast + the old duplicate "Speaker" route alias merged
                // into one honest entry (MediaRouter covers speaker/BT/
                // cast/HDMI/wired).
                CenterTile(
                    icon = Icons.Filled.Headphones,
                    label = "Audio output",
                    value = state.castRouteName ?: "This device",
                    active = state.castRouteName != null,
                    accent = accent,
                    onClick = { onAudioOutput(); onDismiss() },
                )
            }

            // ── Subtitles ──
            SectionHeader("Subtitles")
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CenterTile(
                    icon = Icons.Filled.ClosedCaptionDisabled,
                    label = "Subtitle source",
                    value = state.subtitleChoiceLabel,
                    active = state.subtitleChoiceLabel != "None",
                    accent = accent,
                    onClick = { onSubtitleSource(); onDismiss() },
                )
                CenterTile(
                    icon = Icons.Filled.Movie,
                    label = "Subtitle style",
                    value = "Size · color · spot",
                    active = false,
                    accent = accent,
                    onClick = { onSubtitleStyle(); onDismiss() },
                )
                // v0.7.2 evidence channel: shares the device's own sync
                // decisions so "it didn't lock" becomes an evidence
                // question. Strictly manual; the log contains paths.
                CenterTile(
                    icon = Icons.Filled.BugReport,
                    label = "Sync log",
                    value = "Share what the engines did",
                    active = false,
                    accent = accent,
                    onClick = { onShareSyncLog(); onDismiss() },
                )
            }

            // ── Settings footer — the dead-end in-player settings screen,
            //    wired again after two releases of being a no-op param. ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickable { onOpenSettings(); onDismiss() }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Settings, null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    "Player settings",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "gestures · seek step · sync · style",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * One Control Center tile: icon + label + LIVE current value. Tap fires
 * (and typically dismisses, via the caller); optional long-press adds a
 * secondary surface (EQ tile: tap toggles, long-press opens the panel).
 * Active state colors with the user's SKIN accent — not the old
 * hardcoded MX green.
 */
@Composable
private fun CenterTile(
    icon: ImageVector,
    label: String,
    value: String,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val tint = if (active) accent else Color.White
    Box(
        modifier = Modifier
            .height(88.dp)
            // Two tiles per sheet row: FlowRow + spacedBy(10dp) distributes
            // the remainder, so 48% per tile fills exactly two columns.
            .fillMaxWidth(0.48f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (active) accent.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.06f)
            )
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                },
            )
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) accent.copy(alpha = 0.18f)
                        else Color.White.copy(alpha = 0.10f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(
                label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
