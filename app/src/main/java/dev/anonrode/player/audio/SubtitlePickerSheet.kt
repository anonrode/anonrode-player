package dev.anonrode.player.audio

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anonrode.player.core.media.subtitle.OpenSubtitlesClient
import dev.anonrode.player.core.media.subtitle.OsHash
import dev.anonrode.player.core.media.subtitle.SubtitleDownloadStore
import dev.anonrode.player.core.media.subtitle.SubtitleParser
import dev.anonrode.player.core.media.subtitle.SubtitleSourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom-sheet subtitle picker: embedded container tracks, sidecar files
 * from the video's folder, previously downloaded subtitles, and online
 * search (OpenSubtitles, exact file-hash first — the MX Player flow).
 *
 * Every row maps to a persisted choice string (see
 * MediaStateEntity.subtitleChoice): "" auto / "none" / "embedded:N" /
 * "sidecar:name" / "online:name". The host applies the choice and
 * re-resolves cues; this sheet never touches playback state itself.
 */
@Composable
fun SubtitlePickerSheet(
    videoUri: String,
    videoPath: String?,
    currentChoice: String,
    accent: Color,
    preferredLangs: String = "",
    onSelect: (choice: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<PickerEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // Online search state machine.
    var searchMode by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<OpenSubtitlesClient.SearchResult>>(emptyList()) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var allLangs by remember { mutableStateOf(false) }

    // Enumerate local sources once (embedded tracks need a MediaExtractor
    // pass over the container — strictly IO work).
    LaunchedEffect(Unit) {
        val list = withContext(Dispatchers.IO) {
            val out = ArrayList<PickerEntry>()
            for (t in SubtitleSourceResolver.listEmbedded(context, videoUri, videoPath)) {
                out += PickerEntry("embedded:${t.index}", t.label, "Embedded in file", "IN THIS FILE")
            }
            if (videoPath != null) {
                for (s in SubtitleSourceResolver.listSidecars(context, videoPath)) {
                    out += PickerEntry("sidecar:${s.name}", s.name, "Next to video", "ON DEVICE")
                }
            }
            for (f in SubtitleDownloadStore(context).list(videoUri)) {
                out += PickerEntry("online:${f.name}", f.name, "Downloaded", "DOWNLOADED")
            }
            out
        }
        entries = list
        loading = false
    }

    fun runSearch() {
        scope.launch {
            searching = true
            searchNote = null
            results = emptyList()
            val res = withContext(Dispatchers.IO) {
                val path = videoPath ?: return@withContext null
                val hash = OsHash.compute(path) ?: return@withContext null
                OpenSubtitlesClient.searchByHash(
                    hash.hash,
                    hash.sizeBytes,
                    // Settings' preferred language wins; empty falls back to
                    // the client default (Chinese + English).
                    if (allLangs) "all" else preferredLangs.ifEmpty { OpenSubtitlesClient.DEFAULT_LANGS },
                )
            }
            searching = false
            when {
                res == null -> searchNote =
                    "Can't fingerprint this video (network/SAF source?)."
                res.isEmpty() -> searchNote =
                    "No subtitles matched this exact file" +
                        if (!allLangs) " in Chinese/English. Try all languages." else "."
                else -> results = res
            }
        }
    }

    fun downloadResult(r: OpenSubtitlesClient.SearchResult) {
        if (downloadingId != null) return
        scope.launch {
            downloadingId = r.idFile
            val saved = withContext(Dispatchers.IO) {
                val bytes = OpenSubtitlesClient.downloadSubtitle(r.idFile)
                    ?: return@withContext null
                val file = SubtitleDownloadStore(context)
                    .save(videoUri, "os", r.idFile, r.fileName, bytes)
                // Reject downloads that parse to nothing (binary/HTML junk
                // occasionally gets uploaded as "subtitles").
                if (SubtitleParser.parseBytes(file.name, file.readBytes()).isEmpty()) null
                else file
            }
            downloadingId = null
            if (saved != null) {
                onSelect("online:${saved.name}")
            } else {
                searchNote = "Download failed or the file wasn't a readable subtitle."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 560.dp)
            .background(Color(0xFF0E1017).copy(alpha = 0.96f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (searchMode) "SEARCH ONLINE" else "SUBTITLES",
                color = Color(0xFF8B90A0),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (searchMode) {
                TextButton(onClick = { searchMode = false }) {
                    Text("Back", color = accent)
                }
            } else {
                Text(
                    "${entries.size} sources",
                    color = Color(0xFF5B6070),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        Spacer(Modifier.size(10.dp))

        if (searchMode) {
            // ── online search pane ────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF171A22))
                        .clickable(enabled = !searching) { runSearch() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        when {
                            searching -> "Hashing file + searching…"
                            else -> "Search OpenSubtitles for this exact file"
                        },
                        color = if (searching) Color(0xFF8B90A0) else Color(0xFFF2F4F8),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (allLangs) accent.copy(alpha = 0.18f) else Color(0xFF171A22))
                        .clickable(enabled = !searching) { allLangs = !allLangs }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (allLangs) "All langs" else "中/EN",
                        color = if (allLangs) accent else Color(0xFF8B90A0),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.size(10.dp))

            searchNote?.let { note ->
                Text(
                    note,
                    color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(results, key = { it.idFile }) { r ->
                    val busy = downloadingId == r.idFile
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF171A22))
                            .clickable(enabled = downloadingId == null) { downloadResult(r) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                r.fileName,
                                color = Color(0xFFF2F4F8),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                            )
                            Text(
                                buildString {
                                    append(r.langName.ifBlank { r.langId.uppercase() })
                                    append(" · ")
                                    append(r.format.uppercase())
                                    val dl = r.downloads.toLongOrNull()
                                    if (dl != null) append(" · $dl downloads")
                                },
                                color = Color(0xFF8B90A0),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (busy) {
                            Text(
                                "SAVING…",
                                color = accent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else if (r.isExactHashMatch) {
                            Text(
                                "EXACT",
                                color = accent,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        } else {
            // ── local sources pane ────────────────────────────────────
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "__auto") {
                    PickerRow(
                        title = "Auto",
                        subtitle = "Best file next to the video",
                        section = null,
                        isCurrent = currentChoice.isEmpty(),
                        accent = accent,
                        onClick = { onSelect("") },
                    )
                }
                item(key = "__none") {
                    PickerRow(
                        title = "None",
                        subtitle = "Subtitles off",
                        section = null,
                        isCurrent = currentChoice == "none",
                        accent = accent,
                        onClick = { onSelect("none") },
                    )
                }
                if (loading) {
                    item(key = "__loading") {
                        Text(
                            "Scanning container tracks…",
                            color = Color(0xFF5B6070),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                // Precompute section headers: LazyColumn composes items
                // independently, so a mutable "last section" cursor would
                // be unreliable.
                val headed = entries.mapIndexed { i, e ->
                    e to (i == 0 || entries[i - 1].section != e.section)
                }
                items(headed, key = { it.first.choice }) { (e, isFirstOfSection) ->
                    PickerRow(
                        title = e.title,
                        subtitle = e.subtitle,
                        section = if (isFirstOfSection) e.section else null,
                        isCurrent = e.choice == currentChoice,
                        accent = accent,
                        onClick = { onSelect(e.choice) },
                    )
                }
                if (!loading && entries.isEmpty()) {
                    item(key = "__empty") {
                        Text(
                            "No embedded tracks, sidecar files, or downloads yet — " +
                                "try Search online.",
                            color = Color(0xFF8B90A0),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
                item(key = "__search") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(accent.copy(alpha = 0.12f))
                            .clickable { searchMode = true }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            "Search online…",
                            color = accent,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.size(8.dp))
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

private data class PickerEntry(
    val choice: String,
    val title: String,
    val subtitle: String,
    val section: String,
)

@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    section: String?,
    isCurrent: Boolean,
    accent: Color,
    onClick: () -> Unit,
) {
    Column {
        if (section != null) {
            Text(
                section,
                color = Color(0xFF5B6070),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isCurrent) accent.copy(alpha = 0.12f) else Color(0xFF171A22)
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
                    title,
                    color = Color(0xFFF2F4F8),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                Text(
                    subtitle,
                    color = Color(0xFF8B90A0),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (isCurrent) {
                Text(
                    "ACTIVE",
                    color = accent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
