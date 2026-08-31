package dev.anonrode.player.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dev.anonrode.player.PlayerActivity
import dev.anonrode.player.core.model.Series
import dev.anonrode.player.core.model.Video
import dev.anonrode.player.core.ui.theme.SkinPalette
import dev.anonrode.player.core.ui.theme.rememberSkinPalette
import java.util.Locale

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
private typealias EpisodeItem = dev.anonrode.player.feature.library.LibraryViewModel.EpisodeItem
private typealias FolderSortMode = dev.anonrode.player.feature.library.FolderSort

/** Top-level destinations the app exposes in its bottom navigation.
 *  Home and Series are both views of the library (root browse + folders),
 *  Settings is its own screen — see MainActivity's NavHost. */
enum class LibraryStartDestination { Home, Series }

/** Bottom navigation tabs. Matches the FOLDERS/HOME/Series/Settings
 *  mockup: Home (library root), Series (jumps to the folders list),
 *  Settings. */
private data class BottomTab(val label: String, val icon: ImageVector)

private val bottomTabs = listOf(
    BottomTab("Home", Icons.Filled.VideoLibrary),
    BottomTab("Series", Icons.Filled.Tv),
    BottomTab("Settings", Icons.Filled.Settings),
)

/**
 * Deterministic poster-art hue from the title — stable across launches so
 * each title always renders with the same gradient.
 */
private fun posterHue(title: String): Float = Math.floorMod(title.hashCode(), 360).toFloat()

/**
 * Library home screen: brand row + search, continue-watching rail, FOLDERS
 * list with sorting, in-screen folder drill-down (episode list) and live
 * library search. Bottom navigation (Home / Series / Settings) lives in
 * MainActivity now — this composable only renders the content area.
 *
 * [startDestination] controls the initial scroll: Home = scroll-to-top
 * (matches the old "navIndex = 0" behaviour), Series = scroll to the
 * folders section (matches the old "navIndex = 1 → jumpToFolders()").
 * Both destinations still share the same `LibraryViewModel`, so drill-down
 * state (open folder, search query, scroll, selection) survives the
 * Home ↔ Series hop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModelFactory: androidx.lifecycle.ViewModelProvider.Factory,
    onOpenVideo: (Video) -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    startDestination: LibraryStartDestination = LibraryStartDestination.Home,
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
    // Long-press an episode row to enter selection mode; taps then toggle
    // rows in/out of `selected`, whose order defines the playback queue.
    var selecting by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<String>() }
    val context = LocalContext.current

    // ── Navigation state ──────────────────────────────────────────────
    // openFolderPath: which folder's episode list is on screen (drill-down).
    // searchActive: the faux search field is expanded to a real text field.
    // Both survive process death; a library rescan re-resolves the folder
    // against the fresh snapshot, so drill-down/search survive rescans too.
    var openFolderPath by rememberSaveable { mutableStateOf<String?>(null) }
    var searchActive by rememberSaveable { mutableStateOf(false) }

    // Bottom-nav selection state used to live here as `navIndex`. Now
    // it belongs to the NavController in MainActivity, so the only scroll
    // offset we own is the LazyColumn itself. rememberSaveable so
    // configuration changes (rotation, theme switch, dark/light) and
    // process-recreate restore the scroll offset — LazyListState.Saver is
    // the official Compose saver, no custom Saver needed.
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Deferred scroll requests: destination-change jumps must wait until
    // the browse LazyColumn is (re)composed with `listState` attached —
    // e.g. switching to Series clears the search query first, which swaps
    // the list content in the same frame, so scrolling immediately would
    // hit the outgoing list.
    var scrollRequest by remember { mutableIntStateOf(0) }
    var scrollIndex by remember { mutableIntStateOf(0) }

    fun scrollTo(index: Int) {
        scrollIndex = index
        scrollRequest++
    }

    // The folders header sits right after the optional selection bar (+1)
    // and the optional continue-watching header + row (+2).
    fun jumpToFolders() {
        val leading = (if (selecting) 1 else 0) + (if (state.inProgress.isEmpty()) 0 else 2)
        scrollTo(leading)
    }

    LaunchedEffect(scrollRequest) {
        if (scrollRequest > 0) listState.animateScrollToItem(scrollIndex)
    }

    // Apply the initial scroll for whichever destination this composable
    // was launched into. Keyed on the destination enum + the loaded
    // series so we re-evaluate when the library finishes rescanning and
    // the folder index changes.
    LaunchedEffect(startDestination, state.series.size) {
        if (scrollRequest > 0) return@LaunchedEffect
        when (startDestination) {
            LibraryStartDestination.Home -> scrollTo(0)
            // For Series, defer until the folder list is actually
            // available — otherwise jumpToFolders() scrolls to 0 which
            // is indistinguishable from Home and the user lands in the
            // wrong place. The rescan re-fires this effect.
            LibraryStartDestination.Series ->
                if (state.series.isNotEmpty()) jumpToFolders()
        }
    }

    val openFolder: Series? =
        openFolderPath?.let { p -> state.series.firstOrNull { it.folderPath == p } }
    val folderEpisodes: List<EpisodeItem> = openFolder?.let { f ->
        state.episodesByFolder[f.folderPath] ?: f.videos.map { EpisodeItem(it, 0f, false, null) }
    } ?: emptyList()

    fun exitSelectionMode() {
        selecting = false
        selected.clear()
    }

    fun toggleSelected(uri: String) {
        if (!selected.remove(uri)) selected.add(uri)
    }

    fun enterSelection(uri: String) {
        selecting = true
        if (uri !in selected) selected.add(uri)
    }

    /** Launch the player with an explicit ordered queue (play-all,
     *  play-from-here, multi-select). Keys match PlayerActivity's extras. */
    fun playQueue(videos: List<Video>) {
        val first = videos.firstOrNull() ?: return
        context.startActivity(
            Intent(context, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URI, first.uri)
                putExtra(PlayerActivity.EXTRA_TITLE, first.title)
                putStringArrayListExtra(
                    PlayerActivity.EXTRA_QUEUE_URIS,
                    ArrayList(videos.map { it.uri }),
                )
            }
        )
    }

    /** Queue this episode and every episode after it (display order). */
    fun playFromHere(episodes: List<EpisodeItem>, video: Video) {
        val idx = episodes.indexOfFirst { it.video.uri == video.uri }
        val queue = (if (idx >= 0) episodes.subList(idx, episodes.size) else episodes)
            .map { it.video }
        playQueue(queue)
    }

    fun playAllFolder(folder: Series) {
        playQueue(state.episodesByFolder[folder.folderPath]?.map { it.video } ?: folder.videos)
    }

    // The videos the current selection draws from, in screen order.
    val selectionPool: List<Video> = when {
        openFolder != null -> folderEpisodes.map { it.video }
        state.query.isNotBlank() -> state.searchHits.map { it.video }
        else -> state.videos
    }

    fun playSelectedQueue() {
        val byUri = selectionPool.associateBy { it.uri }
        val queue = selected.mapNotNull { byUri[it] }
        if (queue.isNotEmpty()) playQueue(queue)
        exitSelectionMode()
    }

    BackHandler(enabled = selecting || searchActive || openFolder != null) {
        when {
            selecting -> exitSelectionMode()
            searchActive -> {
                searchActive = false
                vm.setQuery("")
            }
            else -> openFolderPath = null
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = libBg,
        topBar = {
            // Brand + search row, per the design HTML (.lib .brand /
            // .lib .search); replaced by the folder back-bar (.backbar)
            // while a folder is open.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(libBg)
                    .statusBarsPadding()
                    .padding(horizontal = Dimens.gapLg, vertical = Dimens.gapSm),
            ) {
                if (openFolder != null) {
                    val total = fmtTotalDuration(folderEpisodes.sumOf { it.video.durationMs })
                    val countLabel =
                        if (openFolder.totalEpisodes == 1) "1 video"
                        else "${openFolder.totalEpisodes} videos"
                    FolderBackBar(
                        palette = palette,
                        name = openFolder.name,
                        subtitle = if (total.isEmpty()) countLabel else "$countLabel · $total",
                        onBack = { openFolderPath = null },
                        onPlayAll = { playAllFolder(openFolder) },
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "ANONRODE ",
                            color = libOnSurface,
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "PLAYER",
                            color = libAccent,
                            fontWeight = FontWeight.Black,
                            fontStyle = FontStyle.Italic,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        // Settings lives in the bottom nav now, so the
                        // gear icon is gone from the brand row.
                    }
                    BrandSearchField(
                        palette = palette,
                        active = searchActive,
                        query = state.query,
                        onActivate = { searchActive = true },
                        onQueryChange = { vm.setQuery(it) },
                    )
                }
            }
        },
    ) { padding ->
        if (loading || state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = libAccent)
                    Spacer(Modifier.height(Dimens.gapMd))
                    Text("Scanning library…", color = libSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@Scaffold
        }

        if (openFolder != null) {
            // ── Folder drill-down: episode list for one folder ────────
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = Dimens.gapLg),
            ) {
                if (selecting) {
                    item(key = "selection-bar") {
                        SelectionActionBar(
                            palette = palette,
                            count = selected.size,
                            canPlay = selected.isNotEmpty(),
                            onPlay = { playSelectedQueue() },
                            onCancel = { exitSelectionMode() },
                        )
                    }
                }
                if (folderEpisodes.isEmpty()) {
                    item(key = "folder-empty") {
                        EmptyState(
                            palette,
                            Icons.Filled.Folder,
                            "No videos in this folder",
                            "The library may still be scanning — come back in a moment.",
                        )
                    }
                } else {
                    items(folderEpisodes, key = { "ep-" + it.video.uri }) { ep ->
                        EpisodeRow(
                            palette = palette,
                            video = ep.video,
                            subtitle = episodeSubtitle(ep),
                            fraction = ep.fraction,
                            selecting = selecting,
                            isSelected = ep.video.uri in selected,
                            onClick = {
                                if (selecting) toggleSelected(ep.video.uri) else onOpenVideo(ep.video)
                            },
                            onLongClick = {
                                if (selecting) toggleSelected(ep.video.uri) else enterSelection(ep.video.uri)
                            },
                            onPlayFromHere = { playFromHere(folderEpisodes, ep.video) },
                        )
                    }
                }
            }
        } else if (state.query.isNotBlank()) {
            // ── Search results (live filter over the cached snapshot) ─
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = Dimens.gapLg),
            ) {
                if (selecting) {
                    item(key = "selection-bar") {
                        SelectionActionBar(
                            palette = palette,
                            count = selected.size,
                            canPlay = selected.isNotEmpty(),
                            onPlay = { playSelectedQueue() },
                            onCancel = { exitSelectionMode() },
                        )
                    }
                }
                if (state.searchHits.isEmpty()) {
                    item(key = "search-empty") {
                        EmptyState(
                            palette,
                            Icons.Filled.Search,
                            "No matches",
                            "Nothing in your library matches “${state.query.trim()}”.",
                        )
                    }
                } else {
                    item(key = "search-header") {
                        SectionLabel(palette, "RESULTS · ${state.searchHits.size}")
                    }
                    items(state.searchHits, key = { "hit-" + it.video.uri }) { hit ->
                        val folderEps = state.episodesByFolder[hit.video.parentPath]
                        EpisodeRow(
                            palette = palette,
                            video = hit.video,
                            subtitle = if (hit.finished) hit.folderName + " · Watched" else hit.folderName,
                            fraction = hit.fraction,
                            selecting = selecting,
                            isSelected = hit.video.uri in selected,
                            onClick = {
                                if (selecting) toggleSelected(hit.video.uri) else onOpenVideo(hit.video)
                            },
                            onLongClick = {
                                if (selecting) toggleSelected(hit.video.uri) else enterSelection(hit.video.uri)
                            },
                            onPlayFromHere = folderEps?.let { eps ->
                                { playFromHere(eps, hit.video) }
                            },
                        )
                    }
                }
            }
        } else {
            // ── Browse root: continue watching + folders ──────────────
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                state = listState,
                contentPadding = PaddingValues(bottom = Dimens.gapLg),
            ) {
                // Selection action bar (multi-select mode)
                if (selecting) {
                    item(key = "selection-bar") {
                        SelectionActionBar(
                            palette = palette,
                            count = selected.size,
                            canPlay = selected.isNotEmpty(),
                            onPlay = { playSelectedQueue() },
                            onCancel = { exitSelectionMode() },
                        )
                    }
                }

                // Continue watching
                if (state.inProgress.isNotEmpty()) {
                    item(key = "header-continue") { SectionLabel(palette, "CONTINUE WATCHING") }
                    item(key = "continue-row") {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Dimens.gapLg),
                            horizontalArrangement = Arrangement.spacedBy(Dimens.gapMd),
                        ) {
                            items(state.inProgress, key = { it.video.uri }) { item ->
                                ContinueCard(palette, item, onClick = { onOpenVideo(item.video) })
                            }
                        }
                    }
                }

                // Folders
                if (state.series.isNotEmpty()) {
                    item(key = "folders-header") {
                        FolderSectionHeader(
                            palette = palette,
                            count = state.series.size,
                            sort = state.sort,
                            onSortSelected = { vm.setSort(it) },
                        )
                    }
                    items(state.series, key = { "folder-" + it.folderPath }) { s ->
                        FolderRow(
                            palette = palette,
                            s = s,
                            onClick = { openFolderPath = s.folderPath },
                            onPlayAll = { playAllFolder(s) },
                        )
                    }
                }

                // Empty library — the FOLDERS list IS the library, so an
                // empty hint only shows when there is nothing at all.
                if (state.videos.isEmpty() && state.series.isEmpty()) {
                    item(key = "videos-empty") {
                        EmptyState(
                            palette,
                            Icons.Filled.VideoLibrary,
                            "No videos found",
                            "Video files on this device will show up here.",
                        )
                    }
                }
            }
        }
    }
}

/**
 * App-wide bottom navigation rendered by MainActivity's Scaffold. The
 * tabs reflect the NavController's current destination so the highlight
 * is always in sync with the active composable.
 *
 *   index 0  →  Home    (library root)
 *   index 1  →  Series  (library with folders list at top)
 *   index 2  →  Settings
 *
 * The route name is the tab label's lowercased form ("home", "series",
 * "settings"); the constant [TAB_ROUTES] is the source of truth.
 */
val AppTabRoutes = listOf("home", "series", "settings")

@Composable
fun AppBottomNav(
    palette: SkinPalette,
    currentRoute: String?,
    onSelect: (Int) -> Unit,
) {
    NavigationBar(containerColor = palette.surface) {
        bottomTabs.forEachIndexed { index, tab ->
            val route = AppTabRoutes[index]
            NavigationBarItem(
                selected = currentRoute == route,
                onClick = { onSelect(index) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = palette.accent,
                    selectedTextColor = BrandTextPrimary,
                    indicatorColor = palette.accent.copy(alpha = 0.18f),
                    unselectedIconColor = BrandTextSecondary,
                    unselectedTextColor = BrandTextSecondary,
                ),
            )
        }
    }
}

/** Small caps section label, matching `.continue-t` in the design. */
@Composable
private fun SectionLabel(palette: SkinPalette, text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = palette.textDim,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = Dimens.gapLg, end = Dimens.gapLg, top = Dimens.gapLg, bottom = Dimens.gapSm),
    )
}

/** FOLDERS section header with the "· N" count and a working sort menu
 *  on the right (`.sect` in the design). */
@Composable
private fun FolderSectionHeader(
    palette: SkinPalette,
    count: Int,
    sort: FolderSortMode,
    onSortSelected: (FolderSortMode) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Dimens.gapLg, end = Dimens.gapLg, top = Dimens.gapXl, bottom = Dimens.gapXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "FOLDERS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = palette.text,
        )
        Spacer(Modifier.width(Dimens.gapSm))
        Text(
            "· $count",
            style = MaterialTheme.typography.bodySmall,
            color = palette.textDim,
        )
        Spacer(Modifier.weight(1f))
        Box {
            Text(
                "sort ▾",
                style = MaterialTheme.typography.labelSmall,
                color = palette.accent,
                modifier = Modifier
                    .clickable { menuOpen = true }
                    .padding(vertical = Dimens.gapXs),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                FolderSortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        leadingIcon = {
                            if (mode == sort) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = palette.accent,
                                )
                            }
                        },
                        onClick = {
                            menuOpen = false
                            onSortSelected(mode)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One row in the FOLDERS list (design: `.coll`). 44×44 folder tile on
 * the left, name + "N videos" stacked, kebab dots on the right. Tap opens
 * the folder's episode list; the kebab offers "Play all".
 */
@Composable
private fun FolderRow(palette: SkinPalette, s: Series, onClick: () -> Unit, onPlayAll: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick)
            .padding(horizontal = Dimens.gapLg, vertical = Dimens.gapMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Folder icon tile — matches .coll .ic in the design.
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    Brush.linearGradient(
                        listOf(
                            palette.accent.copy(alpha = 0.18f),
                            palette.accent.copy(alpha = 0.06f),
                        )
                    ),
                    RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = palette.iconDim,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(Dimens.gapMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                s.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Slight inline gap so the count is visually associated with
            // the series name without a heavy separator.
            val totalLabel = if (s.totalEpisodes == 1) "video" else "videos"
            val watchedLabel =
                if (s.totalWatched > 0) " · ${s.totalWatched} watched" else ""
            Text(
                "${s.totalEpisodes} $totalLabel$watchedLabel",
                style = MaterialTheme.typography.labelSmall,
                color = palette.textDim,
            )
        }
        // Kebab dots — design's `.coll .dots` — with folder actions.
        // titleMedium size is intentional for the single-glyph "⋮" so it
        // reads as a button affordance, not as body text — no standard
        // role fits a 1-glyph button glyph.
        Box {
            Text(
                "⋮",
                color = palette.iconDim,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .clickable { menuOpen = true }
                    .padding(horizontal = Dimens.gapSm, vertical = Dimens.gapXs),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play all") },
                    onClick = {
                        menuOpen = false
                        onPlayAll()
                    },
                )
            }
        }
    }
}

/** Folder drill-down top bar (design's `.backbar`): back arrow, folder
 *  name + stats, and a Play-all button. */
@Composable
private fun FolderBackBar(
    palette: SkinPalette,
    name: String,
    subtitle: String,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = palette.text,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textDim,
                )
            }
        }
        Spacer(Modifier.width(Dimens.gapSm))
        Button(
            onClick = onPlayAll,
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.accent,
                contentColor = palette.tabOn,
            ),
            contentPadding = PaddingValues(horizontal = Dimens.gapMd, vertical = Dimens.gapSm),
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Dimens.gapXs))
            Text("Play all")
        }
    }
}

/**
 * One episode row (design's `.eps-row`): 96×54 thumbnail with a duration
 * badge and watched-progress bar, title + subtitle, kebab menu with
 * Play / Play-from-here. Long-press enters multi-select.
 */
@Composable
private fun EpisodeRow(
    palette: SkinPalette,
    video: Video,
    subtitle: String,
    fraction: Float,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlayFromHere: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) palette.accent.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = Dimens.gapLg, vertical = Dimens.gapSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 54.dp)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            PosterArt(
                video.title,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                videoUri = video.uri,
            )
            if (video.durationMs > 0) {
                // 9.5sp is an intentional micro-label: tighter than
                // labelSmall (11sp) so the duration badge sits visually
                // *inside* the 54dp poster thumbnail without colliding
                // with the title row. Not a standard M3 role.
                Text(
                    fmtDuration(video.durationMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Dimens.gapSm, bottom = Dimens.gapSm)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(3.dp))
                        .padding(horizontal = Dimens.gapXs, vertical = 2.dp),
                )
            }
            if (fraction > 0f) {
                GradientProgressBar(
                    fraction,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                )
            }
            if (selecting) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Dimens.gapXs)
                        .size(18.dp)
                        .background(
                            if (isSelected) palette.accent else Color.Black.copy(alpha = 0.55f),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = palette.tabOn,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(Dimens.gapMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                video.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = palette.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Episode options",
                    tint = palette.iconDim,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Play") },
                    onClick = {
                        menuOpen = false
                        onClick()
                    },
                )
                if (onPlayFromHere != null) {
                    DropdownMenuItem(
                        text = { Text("Play from here") },
                        onClick = {
                            menuOpen = false
                            onPlayFromHere()
                        },
                    )
                }
            }
        }
    }
}

/** Search field — the design's `.lib .search`. Inactive it is the faux
 *  placeholder row; a tap expands it into a real text field that filters
 *  the library live via [onQueryChange]. */
@Composable
private fun BrandSearchField(
    palette: SkinPalette,
    active: Boolean,
    query: String,
    onActivate: () -> Unit,
    onQueryChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(active) {
        if (active) focusRequester.requestFocus() else keyboard?.hide()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.gapMd)
            .background(palette.surface, RoundedCornerShape(12.dp))
            .then(if (!active) Modifier.clickable(onClick = onActivate) else Modifier)
            .padding(horizontal = Dimens.gapMd, vertical = Dimens.gapMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = if (active) palette.accent else palette.textDim,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Dimens.gapSm))
        if (active) {
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Search episodes / series…",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textDim,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = palette.text),
                    cursorBrush = SolidColor(palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Clear search",
                        tint = palette.textDim,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        } else {
            Text(
                "Search episodes / series…",
                style = MaterialTheme.typography.bodySmall,
                color = palette.textDim,
            )
        }
    }
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
private fun ContinueCard(palette: SkinPalette, item: InProgressItem, onClick: () -> Unit) {
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
        Column(modifier = Modifier.padding(Dimens.gapMd)) {
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
            GradientProgressBar(item.fraction, modifier = Modifier.padding(top = Dimens.gapSm))
        }
    }
}

/** Multi-select action bar: selection count plus play / cancel actions. */
@Composable
private fun SelectionActionBar(
    palette: SkinPalette,
    count: Int,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gapLg, vertical = Dimens.gapSm)
            .background(palette.surface, RoundedCornerShape(16.dp))
            .padding(horizontal = Dimens.gapMd, vertical = Dimens.gapSm),
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
            contentPadding = PaddingValues(horizontal = Dimens.gapMd, vertical = Dimens.gapSm),
        ) {
            Icon(
                Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Dimens.gapXs))
            Text("Play")
        }
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel selection", tint = palette.textDim)
        }
    }
}

/** Intentional-looking empty state: dim icon disc + headline + body hint.
 *  Used by the library screen for: empty library, no search matches, and
 *  the folder drill-down "no videos in this folder" case. Optional [action]
 *  slot renders a CTA button below the hint. */
@Composable
private fun EmptyState(
    palette: SkinPalette,
    icon: ImageVector,
    title: String,
    hint: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimens.gapXxl, vertical = Dimens.gapXxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(palette.surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = palette.iconDim, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(Dimens.gapLg))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = palette.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Dimens.gapXs))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textDim,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(Dimens.gapLg))
            action()
        }
    }
}

// ── Small local formatters ───────────────────────────────────────────────

/** m:ss below one hour, h:mm:ss above. "" for unknown durations. */
private fun fmtDuration(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    else "$m:${s.toString().padStart(2, '0')}"
}

/** "1h 24m" / "43 min" style total for folder stats. */
private fun fmtTotalDuration(ms: Long): String {
    val totalMin = ms / 60000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        totalMin > 0 -> "${totalMin} min"
        else -> ""
    }
}

/** Human file size; "" when unknown. */
private fun fmtSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 ->
        String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** Episode row subtitle: resume position / watched flag + file size. */
private fun episodeSubtitle(ep: EpisodeItem): String {
    val status = when {
        ep.finished -> "Watched"
        ep.resumeLabel != null -> ep.resumeLabel
        else -> null
    }
    val size = fmtSize(ep.video.sizeBytes).ifEmpty { null }
    return listOfNotNull(status, size).joinToString(" · ")
}
