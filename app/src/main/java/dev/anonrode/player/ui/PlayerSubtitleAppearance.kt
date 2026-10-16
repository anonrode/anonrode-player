package dev.anonrode.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import dev.anonrode.player.core.media.log.AppLog

/* ── Subtitle appearance chips ─────────────────────────────────────────────
 * v0.9.1: the style controls live INSIDE the subtitle drawer now (hub 6.6
 * merge + design frame D) — this file is all that remains of the old inline
 * tray: the labelled chip row. The drawer renders four of these (Position,
 * Size, Colour, Weight) directly on its panel; there is no separate surface
 * to open and no second surface to keep in sync.
 * ------------------------------------------------------------------------- */

/**
 * One labelled chip row. Every option is a real touch target (34dp) and the
 * selected chip carries the accent — state, never decoration.
 */
@UnstableApi
@Composable
internal fun TrayRow(
    label: String,
    accent: Color,
    options: List<String>,
    selectedLabel: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { opt ->
                val isActive = opt == selectedLabel
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isActive) accent.copy(alpha = 0.20f)
                            else Color.White.copy(alpha = 0.05f)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isActive) accent.copy(alpha = 0.65f)
                            else Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = accent),
                        ) {
                            AppLog.d("STYLE", "selected $label = $opt")
                            onSelect(opt)
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt,
                        color = if (isActive) accent else Color.White.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}
