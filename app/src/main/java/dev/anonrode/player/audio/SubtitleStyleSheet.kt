package dev.anonrode.player.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anonrode.player.core.media.log.AppLog

/**
 * Bottom-sheet subtitle style picker. Mutates the supplied [SubtitleStyle]
 * via [onStyleChanged] — the host is responsible for persisting the value
 * (e.g. via PlayerSettings DataStore) and re-applying it to the live
 * subtitle composable.
 *
 * Three rows of choices:
 *  - Size:      Small / Medium / Large / Extra-Large
 *  - Position:  Low / Mid / High / Top
 *  - Color:     White / Yellow / Green / Cyan
 * Each row is rendered as a segmented control so the active option is
 * obvious at a glance.
 */
@Composable
fun SubtitleStyleSheet(
    style: SubtitleStyle,
    accent: Color,
    onStyleChanged: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp, max = 600.dp)
            .background(Color(0xFF0E1017).copy(alpha = 0.96f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "SUBTITLE STYLE",
                color = Color(0xFF8B90A0),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                style.size.label + " · " + style.position.label + " · " + style.color.label,
                color = Color(0xFF5B6070),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.size(12.dp))

        // Live preview — matches the player overlay's look so the user sees
        // exactly what their change will look like.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "黑夜里的天线，我试过了",
                color = style.color.value,
                fontSize = style.size.fontSp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.size(14.dp))
        StyleRow(
            label = "Size",
            accent = accent,
            options = SubtitleSize.entries,
            selectedLabel = style.size.label,
            onSelect = { onStyleChanged(style.copy(size = it)) },
        )
        Spacer(Modifier.size(8.dp))
        StyleRow(
            label = "Position",
            accent = accent,
            options = SubtitlePosition.entries,
            selectedLabel = style.position.label,
            onSelect = { onStyleChanged(style.copy(position = it)) },
        )
        Spacer(Modifier.size(8.dp))
        StyleRow(
            label = "Color",
            accent = accent,
            options = SubtitleColor.entries,
            selectedLabel = style.color.label,
            onSelect = { onStyleChanged(style.copy(color = it)) },
        )

        Spacer(Modifier.size(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Close", color = accent)
            }
        }
    }
}

@Composable
private fun <E : StyleOption> StyleRow(
    label: String,
    accent: Color,
    options: List<E>,
    selectedLabel: String,
    onSelect: (E) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            color = Color(0xFF8B90A0),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.size(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { opt ->
                val isActive = opt.label == selectedLabel
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isActive) accent.copy(alpha = 0.18f)
                            else Color(0xFF171A22)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isActive) accent.copy(alpha = 0.8f)
                            else Color.White.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable {
                            AppLog.d("STYLE", "selected $label = ${opt.label}")
                            onSelect(opt)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt.label,
                        color = if (isActive) accent else Color(0xFFCBD0DC),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/** Subtitle style envelope. Persist this to PlayerSettings. */
data class SubtitleStyle(
    val size: SubtitleSize = SubtitleSize.MEDIUM,
    val position: SubtitlePosition = SubtitlePosition.LOW,
    val color: SubtitleColor = SubtitleColor.WHITE,
    val bold: Boolean = true,
)

interface StyleOption {
    val label: String
}

enum class SubtitleSize(override val label: String, val fontSp: androidx.compose.ui.unit.TextUnit) : StyleOption {
    SMALL("S", 14.sp),
    MEDIUM("M", 18.sp),
    LARGE("L", 22.sp),
    XL("XL", 26.sp);
}

enum class SubtitlePosition(override val label: String) : StyleOption {
    LOW("Low"), MID("Mid"), HIGH("High"), TOP("Top");
}

enum class SubtitleColor(override val label: String, val value: Color) : StyleOption {
    WHITE("White", Color.White),
    YELLOW("Yellow", Color(0xFFFFD75E)),
    GREEN("Green", Color(0xFF7CE487)),
    CYAN("Cyan", Color(0xFF7AD8E8));
}
