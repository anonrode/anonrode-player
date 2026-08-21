package dev.anonrode.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anonrode.player.core.model.Series
import dev.anonrode.player.core.model.Video

/** Deterministic duo-tone gradient per title — poster art without thumbnails. */
private fun titleHue(title: String): Float {
    var h = 0f
    for (c in title) h = (h * 31 + c.code) % 360
    return h
}

@Composable
fun PosterArt(title: String, modifier: Modifier = Modifier, corner: Int = 14) {
    val hue = titleHue(title)
    val c1 = Color.hsv(hue, 0.65f, 0.55f)
    val c2 = Color.hsv((hue + 40) % 360, 0.7f, 0.28f)
    Box(modifier.background(
        Brush.linearGradient(listOf(c1, c2)),
        RoundedCornerShape(corner.dp)
    ), contentAlignment = Alignment.Center) {
        Text(
            title.take(1).uppercase(),
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
    }
}

typealias InProgressItem = dev.anonrode.player.feature.library.LibraryViewModel.InProgress

@Composable
fun LibraryScreen(
    viewModelFactory: androidx.lifecycle.ViewModelProvider.Factory,
    onOpenVideo: (Video) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val vm: dev.anonrode.player.feature.library.LibraryViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory)
    val state by vm.ui.collectAsState()

    if (loading || state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Scanning library…", color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }

    Column(modifier.fillMaxSize()) {
        // ── header ────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text("Anonrode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Spacer(Modifier.weight(1f))
            Text("${state.videoCount} videos", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
        }

        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {

            if (state.inProgress.isNotEmpty()) {
                item {
                    SectionHeader("CONTINUE WATCHING")
                    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.inProgress, key = { it.video.uri }) { item ->
                            ContinueCard(item, onClick = { onOpenVideo(item.video) })
                        }
                    }
                }
            }

            if (state.series.isNotEmpty()) {
                item { SectionHeader("SERIES") }
                items(state.series.chunked(2), key = { "s" + it.first().folderPath }) { pair ->
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { s ->
                            SeriesCard(s, Modifier.weight(1f), onClick = {
                                s.videos.firstOrNull()?.let(onOpenVideo)
                            })
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            item { SectionHeader("ALL VIDEOS") }
            items(state.videos, key = { "v" + it.uri }) { v ->
                VideoRow(v, onClick = { onOpenVideo(v) })
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 8.dp))
}

@Composable
private fun ContinueCard(item: InProgressItem, onClick: () -> Unit) {
    Card(modifier = Modifier.width(150.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)) {
        Box {
            PosterArt(item.video.title, modifier = Modifier.fillMaxWidth().height(88.dp), corner = 0)
            Box(modifier = Modifier.align(Alignment.Center).size(34.dp).background(
                Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
                Text("▶", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(modifier = Modifier.padding(9.dp)) {
            Text(item.video.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(item.label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            Box(modifier = Modifier.padding(top = 7.dp).fillMaxWidth().height(3.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(2.dp))) {
                Box(modifier = Modifier.fillMaxWidth(item.fraction.coerceIn(0f, 1f)).height(3.dp)
                    .background(Brush.horizontalGradient(
                        listOf(Color(0xFF6C63FF), Color(0xFF00D4AA))), RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun SeriesCard(s: Series, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)) {
        Box {
            PosterArt(s.name, modifier = Modifier.fillMaxWidth().height(110.dp), corner = 0)
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text("${s.totalEpisodes} episodes · ${s.totalWatched} watched",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            Box(modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(3.dp)
                .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(2.dp))) {
                Box(modifier = Modifier.fillMaxWidth(s.progress.coerceIn(0f, 1f)).height(3.dp)
                    .background(Brush.horizontalGradient(
                        listOf(Color(0xFF6C63FF), Color(0xFF00D4AA))), RoundedCornerShape(2.dp)))
            }
        }
    }
}

@Composable
private fun VideoRow(v: Video, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        PosterArt(v.title, modifier = Modifier.size(width = 64.dp, height = 38.dp), corner = 8)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            Text("${v.durationMs / 60000} min · ${v.sizeBytes / 1048576} MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f))
        }
    }
}
