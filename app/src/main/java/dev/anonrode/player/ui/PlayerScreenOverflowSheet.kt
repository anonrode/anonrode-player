package dev.anonrode.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ── Overflow sheet (the "more" destination) ──────────────────────────────
 * Single destination for both the top-bar "more" and the right-rail "more"
 * buttons. Two-column scrollable grid; each tile = icon + label + state
 * chip for the selects. Tap fires the callback and dismisses the sheet.
 *
 * Rows (per design):
 *   1.  Aspect ratio        (cycles FIT/CROP/STR/16:9/4:3)
 *   2.  Zoom                (currently same as aspect — alias entry)
 *   3.  AB-repeat           (set A → set B → clear cycle)
 *   4.  Sleep timer         (opens sub-menu; for now, cycles Off → end)
 *   5.  Audio track         (opens the host track picker)
 *   6.  Speed               (cycles 0.5x → 2x)
 *   7.  Equalizer           (toggle)
 *   8.  Cast                (opens the MediaRouter picker)
 *   9.  Headphones          (BT/wired detect — see [PlayerScreenActions])
 *   10. Speaker             (output picker — alias of Cast route picker)
 *   11. Capture frame       (PixelCopy → PNG to Pictures/AnonPlayer)
 *
 * PiP is intentionally NOT in this sheet — it lives in the transport row
 * (between ⏩10 and the lock), where it's adjacent to the time-seek controls
 * the user is more likely to combine it with.
 * ------------------------------------------------------------------------- */

/** Inputs the overflow tiles need to render their current state. */
internal data class OverflowState(
    val zoomAbbreviation: String,
    val showCC: Boolean,
    val abStartMs: Long?,
    val abEndMs: Long?,
    val sleep: SleepTimerUiState,
    val speedLabelText: String,
    val equalizerOn: Boolean,
    val headphonesOn: Boolean,
    val castRouteName: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerOverflowSheet(
    visible: Boolean,
    state: OverflowState,
    onDismiss: () -> Unit,
    onAspect: () -> Unit,
    onZoom: () -> Unit,
    onAbRepeat: () -> Unit,
    onSleep: () -> Unit,
    onAudioTrack: () -> Unit,
    onSpeed: () -> Unit,
    onEqualizer: () -> Unit,
    onCast: () -> Unit,
    onHeadphones: () -> Unit,
    onSpeaker: () -> Unit,
    onCaptureFrame: () -> Unit,
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MxPanel,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.55f),
        dragHandle = {
            // Default drag handle is fine — Material renders a 32×4dp bar
            // automatically; the explicit null would suppress it.
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
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // Header — title + close affordance (also implicit from drag).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Options",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (state.castRouteName != null) "Casting: ${state.castRouteName}"
                    else "Tap to toggle",
                    color = Color.White.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))

            // Two-column scrollable grid. The LazyVerticalGrid handles the
            // scroll for us so we don't need a manual Column + verticalScroll.
            // 11 rows in 2 columns → 6 rows, last row has 1 item centered.
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                userScrollEnabled = true,
            ) {
                // ── aspect / zoom ──
                item(key = "aspect") {
                    OverflowTile(
                        icon = Icons.Filled.AspectRatio,
                        label = "Aspect",
                        subLabel = state.zoomAbbreviation,
                        accent = Color.White,
                        onClick = { onAspect(); onDismiss() },
                    )
                }
                item(key = "zoom") {
                    OverflowTile(
                        icon = Icons.Filled.ZoomOutMap,
                        label = "Zoom",
                        subLabel = state.zoomAbbreviation,
                        accent = Color.White,
                        onClick = { onZoom(); onDismiss() },
                    )
                }
                // ── a-b repeat + sleep timer ──
                item(key = "ab") {
                    val abActive = state.abStartMs != null
                    OverflowTile(
                        icon = Icons.Filled.Repeat,
                        label = "A-B repeat",
                        subLabel = when {
                            state.abStartMs == null -> "Off"
                            state.abEndMs == null -> "A set"
                            else -> "${fmtTime(state.abStartMs)} – " +
                                fmtTime(state.abEndMs)
                        },
                        accent = if (abActive) MxGreen else Color.White,
                        onClick = { onAbRepeat(); onDismiss() },
                    )
                }
                item(key = "sleep") {
                    OverflowTile(
                        icon = Icons.Filled.Bedtime,
                        label = "Sleep timer",
                        subLabel = state.sleep.selection.label,
                        accent = if (state.sleep.active) MxGreen else Color.White,
                        onClick = { onSleep(); onDismiss() },
                    )
                }
                // ── audio track + speed ──
                item(key = "audio") {
                    OverflowTile(
                        icon = Icons.Filled.MusicNote,
                        label = "Audio track",
                        subLabel = "Pick…",
                        accent = Color.White,
                        onClick = { onAudioTrack(); onDismiss() },
                    )
                }
                item(key = "speed") {
                    OverflowTile(
                        icon = Icons.Filled.Speed,
                        label = "Speed",
                        subLabel = state.speedLabelText,
                        accent = Color.White,
                        onClick = { onSpeed(); onDismiss() },
                    )
                }
                // ── equalizer + cast ──
                item(key = "eq") {
                    OverflowTile(
                        icon = Icons.Filled.Equalizer,
                        label = "Equalizer",
                        subLabel = if (state.equalizerOn) "On" else "Off",
                        accent = if (state.equalizerOn) MxGreen else Color.White,
                        onClick = { onEqualizer(); onDismiss() },
                    )
                }
                item(key = "cast") {
                    OverflowTile(
                        icon = Icons.Filled.Cast,
                        label = "Cast",
                        subLabel = state.castRouteName ?: "Off",
                        accent = if (state.castRouteName != null) MxGreen
                        else Color.White,
                        onClick = { onCast(); onDismiss() },
                    )
                }
                // ── headphones + speaker (output pickers) ──
                item(key = "hp") {
                    OverflowTile(
                        icon = Icons.Filled.Headphones,
                        label = "Headphones",
                        subLabel = if (state.headphonesOn) "Connected" else "Detect",
                        accent = if (state.headphonesOn) MxGreen else Color.White,
                        onClick = { onHeadphones(); onDismiss() },
                    )
                }
                item(key = "spk") {
                    OverflowTile(
                        icon = Icons.Filled.Speaker,
                        label = "Speaker",
                        subLabel = "Output…",
                        accent = Color.White,
                        onClick = { onSpeaker(); onDismiss() },
                    )
                }
                // ── capture frame (single tile in last row) ──
                item(key = "shot") {
                    OverflowTile(
                        icon = Icons.Filled.PhotoCamera,
                        label = "Capture frame",
                        subLabel = "Save PNG",
                        accent = Color.White,
                        onClick = { onCaptureFrame(); onDismiss() },
                    )
                }
            }
        }
    }
}

/** One tile in the overflow grid: 88dp tall, frosted background, icon
 *  above label + state sub-label. */
@Composable
private fun OverflowTile(
    icon: ImageVector,
    label: String,
    subLabel: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (accent == MxGreen) accent.copy(alpha = 0.18f)
                        else Color.White.copy(alpha = 0.10f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon, null,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
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
                subLabel,
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}