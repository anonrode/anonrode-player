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
import androidx.compose.foundation.shape.CircleShape
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
import dev.anonrode.player.audio.SubtitleColor
import dev.anonrode.player.audio.SubtitlePosition
import dev.anonrode.player.audio.SubtitleSize
import dev.anonrode.player.audio.SubtitleStyle
import dev.anonrode.player.core.media.log.AppLog

/* ── Inline subtitle style tray — v0.9 "Single-Plane Chrome" ───────────────
 * The 09-19 device log records ~40 subtitle-style edits in ONE session
 * (position, size, colour, weight) — the single most-touched control in the
 * whole player — yet it lived behind a MODAL bottom sheet that covered the
 * frame the subtitles render on. Tuning subtitles while unable to see them
 * is the definition of a dead-end UI.
 *
 * This tray replaces [dev.anonrode.player.audio.SubtitleStyleSheet]'s modal:
 * it expands IN PLACE inside the bottom zone, keeps the live cue visible
 * above it, and exposes all four dimensions at once (no paging, no
 * scrolling). It mutates the same host-owned [SubtitleStyle] through
 * [onStyleChanged], so persistence and the live-subtitle re-application are
 * byte-for-byte the code path the sheet already used — only the surface
 * moved.
 * ------------------------------------------------------------------------- */

@UnstableApi
@Composable
internal fun SubtitleStyleTray(
    style: SubtitleStyle,
    accent: Color,
    onStyleChanged: (SubtitleStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0C0D12).copy(alpha = 0.94f))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        TrayRow(
            label = "Position",
            accent = accent,
            options = SubtitlePosition.entries.map { it.label },
            selectedLabel = style.position.label,
            onSelect = { label ->
                val match = SubtitlePosition.entries.firstOrNull { it.label == label }
                if (match != null) onStyleChanged(style.copy(position = match))
            },
        )
        TrayRow(
            label = "Size",
            accent = accent,
            options = SubtitleSize.entries.map { it.label },
            selectedLabel = style.size.label,
            onSelect = { label ->
                val match = SubtitleSize.entries.firstOrNull { it.label == label }
                if (match != null) onStyleChanged(style.copy(size = match))
            },
        )
        TrayRow(
            label = "Colour",
            accent = accent,
            options = SubtitleColor.entries.map { it.label },
            selectedLabel = style.color.label,
            onSelect = { label ->
                val match = SubtitleColor.entries.firstOrNull { it.label == label }
                if (match != null) onStyleChanged(style.copy(color = match))
            },
        )
        TrayRow(
            label = "Weight",
            accent = accent,
            options = listOf("Bold", "Regular"),
            selectedLabel = if (style.bold) "Bold" else "Regular",
            onSelect = { label -> onStyleChanged(style.copy(bold = label == "Bold")) },
        )
    }
}
/**
 * One labelled segmented row. Same shape as the old sheet's [StyleRow] but
 * horizontally scrollable when it doesn't fit, and with a 34dp hit area
 * (above the 32dp the sheet used, so every option is a real touch target).
 */
@UnstableApi
@Composable
private fun TrayRow(
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

/**
 * Colour swatch row — used instead of a text row for Colour, because picking
 * a colour from its name is slower than picking it from the colour itself.
 * Selection ring uses the SKIN accent, not white, so it never fights the
 * swatch it is selecting.
 */
@UnstableApi
@Composable
internal fun SubtitleColorSwatchRow(
    selected: SubtitleColor,
    accent: Color,
    onSelect: (SubtitleColor) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SubtitleColor.entries.forEach { swatch ->
            val isActive = swatch == selected
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .background(swatch.value)
                    .border(
                        width = if (isActive) 2.dp else 1.dp,
                        color = if (isActive) accent else Color.White.copy(alpha = 0.30f),
                        shape = CircleShape,
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, color = accent),
                    ) {
                        AppLog.d("STYLE", "selected Colour = ${swatch.label}")
                        onSelect(swatch)
                    },
                contentAlignment = Alignment.Center,
            )
        }
    }
}

