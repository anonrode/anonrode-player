package dev.anonrode.player.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.anonrode.player.PlayerActivity
import dev.anonrode.player.core.model.Series
import dev.anonrode.player.core.model.Video
import dev.anonrode.player.core.ui.theme.rememberSkinPalette

// ── Anonrode brand palette (legacy) ─────────────────────────────────────
// Kept for any non-themed callers; new code should use rememberSkinPalette()
// to pick up the active skin. The library now reads its surface / text
// colours from the live skin so it stays in sync with player + settings.
private val AccentPurple = Color(0xFF6C63FF)
private val AccentTeal = Color(0xFF00D4AA)
private val BrandTextPrimary = Color(0xFFF0F2F8)
private val BrandTextSecondary = Color(0xFFF0F2F8).copy(alpha = 0.45f)

private val ProgressBrush = Brush.horizontalGradient(listOf(Color(0xFF6C63FF), Color(0xFF00D4AA)))

private typealias InProgressItem = dev.anonrode.player.feature.library.LibraryViewModel.InProgress

/** Bottom navigation tabs. */
private data class BottomTab(val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab("Home", Icons.Filled.VideoLibrary),
    BottomTab("Playlists", Icons.Filled.PlaylistAdd),
    BottomTab("Settings", Icons.Filled.Settings),
)

/**
 * Deterministic poster-art hue from the title — stable across launches so
 * each title always renders with the same gradient.
 */
private fun posterHue(title: String): Float = Math.floorMod(title.hashCode(), 360).toFloat()

/**
 * Library home screen: continue-watching rail, series grid and full video
 * list, framed by a Material 3 top bar and bottom navigation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModelFactory: androidx.lifecycle.ViewModelProvider.Factory,
    onOpenVideo: (Video) -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val vm: dev.anonrode.player.feature.library.LibraryViewModel =
        androidx.lifecycle.viewmodel.compose.viewModel(factory = viewModelFactory)
    val state by vm.ui.collectAsState()
    val palette = rememberSkinPalette()
    val libBg = palette.background
    val libSurface = palette.surface
    val libOnSurface = palette.text
    val libAccent = palette.accent
    val libSecondary = palette.textDim

    // ── Multi-select play queue ───────────────────────────────────────
    // Long-press a video row to enter selection mode; taps then toggle
    // rows in/out of `selected`, whose order defines the playback queue.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val context = LocalContext.current

    fun exitSelectionMode() {
        selecting = false
        selected.clear()
    }

    fun toggleSelected(uri: String) {
        if (!selected.remove(uri)) selected.add(uri)
    }

    fun playSelectedQueue() {
        val firstUri = selected.firstOrNull() ?: return
        val firstTitle = state.videos.firstOrNull { it.uri == firstUri }?.title
            ?: state.series.flatMap { it.videos }.firstOrNull { it.uri == firstUri }?.title
            ?: firstUri
        // Same launch as the single-video tap (MainActivity.play → plain
        // Intent, no flags), plus the ordered queue extra. Keys match
        // PlayerActivity.EXTRA_URI / EXTRA_TITLE / EXTRA_QUEUE_URIS.
        context.startActivity(
            Intent(context, PlayerActivity::class.java).apply {
                putExtra("uri", firstUri)
                putExtra("title", firstTitle)
                putStringArrayListExtra("queue_uris", ArrayList(selected))
            }
        )
        exitSelectionMode()
    }

    BackHandler(enabled = selecting) { exitSelectionMode() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = libBg,
        topBar = {
            TopAppBar(
                title = {
                    Text("Anonrode", color = libOnSurface, fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = { /* search: future iteration */ }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search", tint = libOnSurface)
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = libOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = libBg),
            )
        },
        bottomBar = { LibraryBottomNav(libSurface = libSurface, accent = libAccent, onOpenSettings = onOpenSettings) },
    ) { padding ->
        if (loading || state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = libAccent)
                    Spacer(Modifier.height(12.dp))
                    Text("Scanning library…", color = libSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            // ── Selection action bar (multi-select mode) ─────────────
            if (selecting) {
                item(key = "selection-bar") {
                    SelectionActionBar(
                        count = selected.size,
                        canPlay = selected.isNotEmpty(),
                        onPlay = { playSelectedQueue() },
                        onCancel = { exitSelectionMode() },
                    )
                }
            }

            // ── Continue watching ────────────────────────────────────
            if (state.inProgress.isNotEmpty()) {
                item(key = "header-continue") { SectionHeader("CONTINUE WATCHING") }
                item(key = "continue-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.inProgress, key = { it.video.uri }) { item ->
                            ContinueCard(item, onClick = { onOpenVideo(item.video) })
                        }
                    }
                }
            }

            // ── Series ───────────────────────────────────────────────
            if (state.series.isNotEmpty()) {
                item(key = "header-series") { SectionHeader("SERIES") }
                items(state.series.chunked(2), key = { "series-" + it.first().folderPath }) { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        pair.forEach { s ->
                            SeriesCard(s, Modifier.weight(1f), onClick = {
                                s.videos.firstOrNull()?.let(onOpenVideo)
                            })
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }

            // ── All videos ───────────────────────────────────────────
            item(key = "header-videos") { SectionHeader("ALL VIDEOS") }
            if (state.videos.isEmpty()) {
                item(key = "videos-empty") {
                    Text(
                        "No videos found on this device.",
                        color = libSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                    )
                }
            } else {
                items(state.videos, key = { "video-" + it.uri }) { v ->
                    // Explicit lambda type: `if (selecting) null else { … }`
                    // can't infer the else-branch's return on its own.
                    val enterSelection: () -> Unit = {
                        selecting = true
                        if (v.uri !in selected) selected.add(v.uri)
                    }
                    VideoRow(
                        v,
                        selected = selecting && v.uri in selected,
                        onClick = {
                            if (selecting) toggleSelected(v.uri) else onOpenVideo(v)
                        },
                        onLongClick = if (selecting) null else enterSelection,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryBottomNav(
    libSurface: Color,
    accent: Color,
    onOpenSettings: () -> Unit = {},
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    NavigationBar(containerColor = libSurface) {
        bottomTabs.forEachIndexed { index, tab ->
            val isSettings = tab.label == "Settings"
            NavigationBarItem(
                selected = selected == index,
                onClick = {
                    selected = index
                    if (isSettings) onOpenSettings()
                },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = accent,
                    selectedTextColor = BrandTextPrimary,
                    indicatorColor = accent.copy(alpha = 0.18f),
                    unselectedIconColor = BrandTextSecondary,
                    unselectedTextColor = BrandTextSecondary,
                ),
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = BrandTextSecondary,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 10.dp),
    )
}

/**
 * Duo-tone gradient "poster art" generated from the title hash. When
 * [videoUri] is provided, the real video frame (Coil video decoder) is
 * drawn over the gradient — which stays as placeholder while loading and
 * as fallback if the frame can't be decoded.
 */
@Composable
fun PosterArt(
    title: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    letterStyle: TextStyle? = null,
    videoUri: String? = null,
) {
    val hue = posterHue(title)
    val c1 = Color.hsv(hue, 0.62f, 0.60f)
    val c2 = Color.hsv((hue + 42f) % 360f, 0.72f, 0.30f)
    Box(
        modifier = modifier.background(Brush.linearGradient(listOf(c1, c2)), RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.take(1).uppercase(),
            color = Color.White.copy(alpha = 0.85f),
            style = letterStyle ?: MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )
        if (videoUri != null) {
            AsyncImage(
                model = videoUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(cornerRadius)),
            )
        }
    }
}

/** Thin purple→teal progress bar over a faint track. */
@Composable
private fun GradientProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(2.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(4.dp)
                .background(ProgressBrush, RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun ContinueCard(item: InProgressItem, onClick: () -> Unit) {
    val palette = rememberSkinPalette()
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        modifier = Modifier.width(150.dp),
    ) {
        Box {
            PosterArt(item.video.title, modifier = Modifier.fillMaxWidth().height(88.dp), cornerRadius = 0.dp, videoUri = item.video.uri)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                item.video.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = palette.text,
            )
            Text(
                item.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textDim,
            )
            GradientProgressBar(item.fraction, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun SeriesCard(s: Series, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = rememberSkinPalette()
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = palette.surface),
        modifier = modifier,
    ) {
        PosterArt(s.name, modifier = Modifier.fillMaxWidth().height(110.dp), cornerRadius = 0.dp, videoUri = s.videos.firstOrNull()?.uri)
        Column(modifier = Modifier.padding(11.dp)) {
            Text(
                s.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = palette.text,
            )
            Text(
                "${s.totalEpisodes} episodes · ${s.totalWatched} watched",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textDim,
            )
            GradientProgressBar(s.progress, modifier = Modifier.padding(top = 9.dp))
        }
    }
}

/** Multi-select action bar: selection count plus play / cancel actions. */
@Composable
private fun SelectionActionBar(
    count: Int,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
) {
    val palette = rememberSkinPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .background(palette.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count selected",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = palette.text,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onPlay,
            enabled = canPlay,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = BrandTextPrimary,
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("Play")
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel selection", tint = palette.textDim)
        }
    }
}

@Composable
private fun VideoRow(
    v: Video,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val palette = rememberSkinPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            PosterArt(
                v.title,
                modifier = Modifier.size(width = 64.dp, height = 38.dp),
                cornerRadius = 8.dp,
                letterStyle = MaterialTheme.typography.labelMedium,
                videoUri = v.uri,
            )
            if (selected) {
                // Accent scrim + check badge mark the row as queued.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(palette.accent.copy(alpha = 0.30f), RoundedCornerShape(8.dp)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(18.dp)
                        .background(palette.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = BrandTextPrimary,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                v.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.text,
            )
            Text(
                "${formatDuration(v.durationMs)} · ${formatSize(v.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textDim,
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1073741824f)
    bytes >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1048576f)
    else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024f)
}
