package dev.anonrode.player.audio

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anonrode.player.core.media.log.AppLog

/**
 * Bottom-sheet 5-band equalizer panel. Reads the band layout from the
 * live [EqualizerManager] (so it works for devices that expose 5 bands,
 * or whatever the OEM gave us), shows one slider per band, and writes
 * back via [setBandLevel]. Each move is debounced by the manager's own
 * `setBandLevel` call which clamps and applies atomically.
 *
 * The panel is purely UI; the host (PlayerActivity) owns the
 * [EqualizerManager] and the on/off toggle.
 */
@Composable
fun EqualizerPanelSheet(
    equalizer: EqualizerManager,
    accent: Color,
    onDismiss: () -> Unit,
) {
    val bands = equalizer.bandCount.coerceAtLeast(1)
    val rangeMb = remember(equalizer) {
        val r = equalizer.bandLevelRange
        (r[0].toInt())..(r[1].toInt())
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 280.dp, max = 520.dp)
            .background(Color(0xFF0E1017).copy(alpha = 0.96f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "EQUALIZER",
                color = Color(0xFF8B90A0),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (equalizer.isEnabled) "ON" else "OFF",
                color = if (equalizer.isEnabled) accent else Color(0xFF5B6070),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "$bands bands · ${(rangeMb.first / 100).toInt()} dB to ${(rangeMb.last / 100).toInt()} dB",
            color = Color(0xFF5B6070),
            style = MaterialTheme.typography.labelSmall,
        )
        Spacer(Modifier.height(14.dp))

        if (bands == 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF171A22)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Equalizer not available on this device.\nRebind the effect to retry.",
                    color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed((0 until bands).toList()) { idx, band ->
                    BandSlider(
                        index = band,
                        equalizer = equalizer,
                        accent = accent,
                        rangeMb = rangeMb,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = {
                // Flatten all bands to 0 dB (mid position)
                for (b in 0 until bands) equalizer.setBandLevel(b, 0)
                AppLog.d("EQ", "reset all bands to 0 dB")
            }) {
                Text("Reset", color = Color(0xFF8B90A0))
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onDismiss) {
                Text("Close", color = accent)
            }
        }
    }
}

@Composable
private fun BandSlider(
    index: Int,
    equalizer: EqualizerManager,
    accent: Color,
    rangeMb: IntRange,
) {
    var level by remember(index) { mutableStateOf(equalizer.getBandLevel(index).toFloat()) }
    val freqHz = (equalizer.getCentreFreqMhz(index) / 1000).coerceAtLeast(0)
    val label = when {
        freqHz >= 1000 -> "${freqHz / 1000} kHz"
        freqHz > 0 -> "$freqHz Hz"
        else -> "Band ${index + 1}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF171A22))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(width = 56.dp, height = 22.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                label,
                color = Color(0xFFCBD0DC),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = level,
            onValueChange = { v ->
                level = v
                equalizer.setBandLevel(index, v.toInt())
            },
            valueRange = rangeMb.first.toFloat()..rangeMb.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = accent,
                activeTrackColor = accent,
                inactiveTrackColor = Color(0xFF2A2F3A),
            ),
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.size(width = 44.dp, height = 22.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                "%+d dB".format((level / 100).toInt()),
                color = Color(0xFF8B90A0),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
