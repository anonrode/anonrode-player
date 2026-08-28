package dev.anonrode.player.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import dev.anonrode.player.core.media.log.AppLog

/**
 * Bottom-sheet picker for the audio tracks available in the currently
 * loaded media. Reads [Player.getCurrentTracks], filters to
 * [C.TRACK_TYPE_AUDIO], renders one row per track, and the row that
 * matches the player's [Player.getCurrentTrackSelections] for audio
 * is marked "NOW PLAYING".
 *
 * Tapping a row calls [onSelectTrack] with the new track id. The
 * [Player] is owned by the host; this composable is read-only.
 */
@Composable
fun AudioTrackPickerSheet(
    player: Player,
    accent: Color,
    onSelectTrack: (trackId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tracks = remember(player) { buildAudioTrackList(player) }
    val currentTrackId = remember(player) { currentAudioTrackId(player) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 520.dp)
            .background(Color(0xFF0E1017).copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "AUDIO TRACK",
                color = Color(0xFF8B90A0),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${tracks.size} available",
                color = Color(0xFF5B6070),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.size(10.dp))

        if (tracks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF171A22)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No audio tracks in the current media.",
                    color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                items(tracks, key = { it.id }) { row ->
                    AudioTrackRow(
                        row = row,
                        isCurrent = row.id == currentTrackId,
                        accent = accent,
                        onClick = { onSelectTrack(row.id) },
                    )
                }
            }
        }

        Spacer(Modifier.size(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text("Close", color = accent)
            }
        }
    }
}

private data class AudioTrackRowData(
    val id: String,
    val title: String,
    val subtitle: String,
)

private fun buildAudioTrackList(player: Player): List<AudioTrackRowData> {
    val out = mutableListOf<AudioTrackRowData>()
    // Media3 exposes tracks as Tracks.Group wrappers around a TrackGroup;
    // the row id is "groupIndex:indexInGroup", which is exactly what the
    // host needs to build a TrackSelectionOverride.
    for ((groupIdx, trackGroup) in player.currentTracks.groups.withIndex()) {
        if (trackGroup.type != C.TRACK_TYPE_AUDIO) continue
        val group: TrackGroup = trackGroup.mediaTrackGroup
        for (i in 0 until group.length) {
            val id = "$groupIdx:$i"
            val format: Format = group.getFormat(i)
            val title = format.label?.takeIf { it.isNotBlank() }
                ?: languageLabel(format.language)
                ?: "Track ${out.size + 1}"
            val parts = mutableListOf<String>()
            val channels = format.channelCount
            if (channels == 6) parts += "5.1"
            else if (channels == 2) parts += "Stereo"
            else if (channels == 1) parts += "Mono"
            else if (channels > 0) parts += "${channels}ch"
            val sampleRate = format.sampleRate
            if (sampleRate > 0) parts += "${sampleRate / 1000} kHz"
            val bitrate = format.bitrate
            if (bitrate > 0) parts += "${bitrate / 1000} kbps"
            val codec = format.codecs
            if (!codec.isNullOrBlank()) parts += codec.uppercase()
            out += AudioTrackRowData(
                id = id,
                title = title,
                subtitle = parts.joinToString(" · "),
            )
        }
    }
    AppLog.d("TRACKS", "audio tracks listed: " + out.size)
    return out
}

private fun currentAudioTrackId(player: Player): String? {
    for ((groupIdx, trackGroup) in player.currentTracks.groups.withIndex()) {
        if (trackGroup.type != C.TRACK_TYPE_AUDIO || !trackGroup.isSelected) continue
        for (i in 0 until trackGroup.mediaTrackGroup.length) {
            if (trackGroup.isTrackSelected(i)) return "$groupIdx:$i"
        }
    }
    return null
}

private fun languageLabel(lang: String?): String? {
    if (lang.isNullOrBlank()) return null
    return when (lang.lowercase()) {
        "en", "eng" -> "English"
        "ja", "jpn" -> "Japanese"
        "zh", "chi", "zho" -> "Chinese"
        "ko", "kor" -> "Korean"
        "es", "spa" -> "Spanish"
        "fr", "fra" -> "French"
        "de", "deu" -> "German"
        else -> lang.uppercase()
    }
}

@Composable
private fun AudioTrackRow(
    row: AudioTrackRowData,
    isCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isCurrent) accent.copy(alpha = 0.12f)
                else Color(0xFF171A22)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isCurrent) accent else Color.Transparent)
        )
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.title,
                color = Color(0xFFF2F4F8),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (row.subtitle.isNotEmpty()) {
                Text(
                    row.subtitle,
                    color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (isCurrent) {
            Text(
                "NOW PLAYING",
                color = accent,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
