package dev.anonrode.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ── Ribbon customise sheet ───────────────────────────────────────────────
 * Backs the ribbon's "Customise" tool.
 *
 * That tool used to be a dead toast. It is now a real editor: the ribbon
 * renders from [QuickRowUiState.ribbonOrder], and this sheet is the only
 * writer of that list. Each row moves within the visible sequence or hides
 * entirely; hidden tools are kept in a separate set so unhiding restores
 * them at their catalogue position instead of dumping them at the end.
 *
 * Every mutation persists to SharedPreferences (see PlayerPrefs), so the
 * sheet is a live view of already-saved state, not a draft — no save
 * button, and dismissing cannot lose an edit.
 * ------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RibbonCustomiseSheet(
    visible: Boolean,
    actions: PlayerScreenActions,
    accent: Color,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val quick = actions.quick
    val order = quick.ribbonOrder
    val hidden = quick.ribbonHiddenTools()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = {
            actions.closeRibbonCustomise()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = OverlayPanelBg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                "Customise ribbon",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Reorder the tools, or hide the ones you never use.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))

            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (order.isEmpty()) {
                    Text(
                        "Every tool is hidden — turn one back on below.",
                        color = accent,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                order.forEachIndexed { index, tool ->
                    RibbonCustomiseRow(
                        label = tool.label,
                        accent = accent,
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        onMoveUp = { actions.moveRibbonTool(tool, -1) },
                        onMoveDown = { actions.moveRibbonTool(tool, +1) },
                        onHide = { actions.toggleRibbonToolVisible(tool) },
                    )
                }

                if (hidden.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "HIDDEN",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(6.dp))
                    // Catalogue order, so the hidden list never inherits the
                    // arbitrary order things happened to be hidden in.
                    RibbonTool.entries
                        .filter { it in hidden }
                        .forEach { tool ->
                            RibbonCustomiseRow(
                                label = tool.label,
                                accent = accent,
                                canMoveUp = false,
                                canMoveDown = false,
                                onMoveUp = {},
                                onMoveDown = {},
                                onShow = { actions.toggleRibbonToolVisible(tool) },
                            )
                        }
                }
            }
        }
    }
}


@Composable
private fun RibbonCustomiseRow(
    label: String,
    accent: Color,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onHide: (() -> Unit)? = null,
    onShow: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onHide != null) {
            IconButton(onClick = onHide, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Visibility,
                    contentDescription = "Hide $label",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (onShow != null) {
            IconButton(onClick = onShow, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.VisibilityOff,
                    contentDescription = "Show $label",
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = "Move $label earlier",
                tint = if (canMoveUp) Color.White.copy(alpha = 0.8f)
                else Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = "Move $label later",
                tint = if (canMoveDown) Color.White.copy(alpha = 0.8f)
                else Color.White.copy(alpha = 0.2f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
