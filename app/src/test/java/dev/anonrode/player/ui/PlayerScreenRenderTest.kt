package dev.anonrode.player.ui

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.media.AudioManager
import android.net.Uri
import android.provider.MediaStore
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.TestExoPlayerBuilder
import com.github.takahirom.roborazzi.captureRoboImage
import dev.anonrode.player.EpisodeQueue
import dev.anonrode.player.core.database.MediaDatabase
import dev.anonrode.player.core.datastore.playerSettingsDataStore
import dev.anonrode.player.core.media.library.MediaScanner
import dev.anonrode.player.core.media.state.MediaStateStore
import dev.anonrode.player.core.model.Video
import dev.anonrode.player.core.ui.theme.PlayerAccentTokens
import dev.anonrode.player.feature.library.LibraryViewModel
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.printToLog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.robolectric.shadows.ShadowContentResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the REAL player chrome (phase-1 components) into PNGs on the JVM so
 * CI can ship the images as artifacts for design diffing against
 * scratch/proposed_black_glass_player.html. This is geometry evidence, not a
 * golden test — no assertion, every state always records.
 *
 * Constraints the rendering leans on (do not "simplify"):
 *  - @GraphicsMode(NATIVE) has no software-fallback: Compose draws via
 *    Robolectric's native graphics and would render blank frames without it.
 *  - sdk 34 is pinned regardless of targetSdk: the android-all image for 34
 *    is stable across Robolectric 4.x while the app targets 37.
 *  - The fixture never instantiates PlaybackEngine — the chrome reads state,
 *    never the engine, and the sync DSP is JNI-out-of-scope here.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// application = bare Application: the real app class bootstraps Coil/native
// machinery that must not run under JVM instrumentation (startup crash).
@Config(sdk = [34], qualifiers = "w392dp-h850dp", application = android.app.Application::class)
class PlayerScreenRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val accent = PlayerAccentTokens(
        primary = Color(0xFF3DDBA8),
        container = Color(0x2A3DDBA8),
        border = Color(0x473DDBA8),
        glow = Color(0x1F3DDBA8),
        onAccent = Color(0xFF04150D),
    )

    private fun fixturePlayer(): ExoPlayer =
        TestExoPlayerBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>(),
        ).build()

    private fun fixtureActions(): PlayerScreenActions {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<Context>()
        @Suppress("UNUSED_VARIABLE")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return PlayerScreenActions(
            context = context,
            view = View(context),
            audioManager = audioManager,
            activity = null,
            engine = null,
            livePlayer = fixturePlayer(),
            captureScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            ui = PlayerUiState(initialIsPlaying = true),
            hud = HudUiState(),
            sleep = SleepTimerUiState(),
            gestures = GestureUiState(),
            quick = QuickRowUiState(),
            speedIdx = mutableIntStateOf(2),
            speeds = listOf(0.25f, 0.5f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 3.5f, 4f),
            seekIncrementSec = 10,
            fastSeekThresholdSec = 120L,
            isRebuildingDecoder = false,
            title = "The Great Protector S01E04 540p x265 AAC",
            onSpeedChanged = {},
            onZoomChanged = {},
            onToggleEqualizer = { true },
            onOpenCastPicker = {},
            onOpenAudioTrackPicker = {},
            onRebuildDecoder = { 0 },
            onNudgeSubtitle = {},
            onEnterPip = {},
            onSetSubSyncEnabled = {},
            onResyncNow = {},
        )
    }

    @Composable
    private fun Stage(landscape: Boolean, content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF050507)),
            ) {
                content()
            }
        }
    }

    @Composable
    private fun Chrome(actions: PlayerScreenActions, visible: Boolean) {
        PlayerControlsOverlay(
            visible = visible,
            title = "The Great Protector S01E04 540p x265 AAC",
            accent = accent.primary,
            positionSec = mutableFloatStateOf(46f * 60f + 23f),
            durationSec = mutableFloatStateOf(53f * 60f + 52f),
            bufferedSec = mutableFloatStateOf(34f * 60f),
            localSeek = mutableFloatStateOf(-1f),
            isPlaying = true,
            hasPreviousEpisode = true,
            hasNextEpisode = true,
            videoWidth = 1920,
            videoHeight = 1080,
            videoFps = 24,
            hasSubtitleTrack = true,
            liveOffsetMs = 120L,
            actions = actions,
            onBack = {},
            onPlayPrevious = {},
            onPlayNext = {},
            onMore = {},
        )
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onRoot()
            .captureRoboImage("src/test/snapshots/$name.png")
    }

    @Test
    fun portrait_chrome_on() {
        val actions = fixtureActions()
        compose.setContent { Stage(false) { Chrome(actions, visible = true) } }
        capture("portrait_chrome_on")
    }

    @Test
    fun portrait_sync_locked() {
        val actions = fixtureActions()
        // Verified lock (design J3 beat 3): the pill reads "✓ Synced +0.12s",
        // the Thread flattens to accent, and the tech line's synced segment
        // ignites — all from the same state the host writes.
        actions.quick.isSyncLocked.value = true
        compose.setContent { Stage(false) { Chrome(actions, visible = true) } }
        capture("portrait_sync_locked")
    }

    @Test
    fun settings_hub() {
        // The full Settings directory hub — flat rows per board F. Renders
        // from DataStore defaults under Robolectric; the whole screen is
        // self-contained (no player chrome behind it).
        compose.setContent {
            Stage(false) {
                SettingsScreen(onBack = {})
            }
        }
        capture("settings_hub")
    }

    @Test
    fun portrait_immersive() {
        val actions = fixtureActions()
        compose.setContent { Stage(false) { Chrome(actions, visible = false) } }
        capture("portrait_immersive")
    }

    @Test
    fun speed_card_open() {
        val actions = fixtureActions()
        compose.setContent {
            Stage(false) {
                Chrome(actions, visible = true)
                Box(Modifier.fillMaxSize().padding(top = 150.dp), contentAlignment = Alignment.TopCenter) {
                    PrecisionSpeedCard(
                        visible = true,
                        currentSpeed = 1.37f,
                        onStep = {},
                        onSetSpeed = {},
                        onReset = {},
                        accent = accent.primary,
                    )
                }
            }
        }
        capture("speed_card_open")
    }

    @Test
    fun subtitle_drawer_open() {
        val actions = fixtureActions()
        compose.setContent {
            Stage(false) {
                Chrome(actions, visible = true)
                Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                    PlayerSubtitleDrawer(
                        visible = true,
                        showCC = true,
                        currentChoiceLabel = "episode-2 · English (embedded)",
                        offsetMs = 120L,
                        accent = accent,
                        onToggleShowCC = {},
                        onNudge = {},
                        onResetOffset = {},
                        onResync = {},
                        onOpenLocalFile = {},
                        onSubtitleStyle = {},
                        onDismiss = {},
                    )
                }
            }
        }
        capture("subtitle_drawer_open")
    }

    @Test
    fun settings_drawer_open() {
        val actions = fixtureActions()
        compose.setContent {
            Stage(false) {
                Chrome(actions, visible = true)
                Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                    PlayerSettingsDrawer(
                        visible = true,
                        accent = accent,
                        abActive = true,
                        nightMode = false,
                        backgroundPlay = true,
                        volumeBoost = 50,
                        sleepMinutes = 30,
                        equalizerOn = false,
                        repeatModeLabel = "OFF",
                        decoderModeLabel = "HW+SW",
                        onDismiss = {},
                    )
                }
            }
        }
        capture("settings_drawer_open")
    }

    @Test
    fun playlist_drawer_open() {
        val actions = fixtureActions()
        val videos = (1..5).map { i ->
            Video(
                uri = "/sdcard/show/episode-0$i.mkv",
                path = "/sdcard/show/episode-0$i.mkv",
                title = "…season-1-episode-0$i",
                durationMs = 3_200_000L + i * 60_000L,
                width = 1280,
                height = 720,
                sizeBytes = 730_000_000L,
                lastModifiedMs = 1_760_000_000_000L,
                mediaStoreId = i.toLong(),
                parentPath = "/sdcard/show",
            )
        }
        val queue = EpisodeQueue(episodes = videos, currentIndex = 1)
        compose.setContent {
            Stage(false) {
                Chrome(actions, visible = true)
                Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
                    PlayerPlaylistDrawer(
                        visible = true,
                        queue = queue,
                        currentMediaUri = videos[1].uri,
                        accent = accent,
                        onSelectEpisode = {},
                        onDismiss = {},
                    )
                }
            }
        }
        capture("playlist_drawer_open")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w850dp-h392dp", application = android.app.Application::class)
    fun landscape_chrome_on() {
        val actions = fixtureActions()
        compose.setContent { Stage(true) { Chrome(actions, visible = true) } }
        capture("landscape_chrome_on")
    }

    // ══════════════ LIBRARY RENDER STATES (B3) ══════════════
    // These states run the library's REAL pipeline: a fake "media" provider
    // feeds MediaScanner, MediaStateStore seeds watch positions, and
    // LibraryViewModel's join produces the same UiState production builds.
    // Only the media rows and positions are synthetic; every composable, the
    // scan, the join and the grouping are the shipped code.

    /** id, display name, durationMs, width, height, bytes, mtimeSeconds, path. */
    private fun fakeVideoRows(): List<Array<Any>> = listOf(
        arrayOf(1L, "download-house-of-the-dragon-2022-season-1-episode-1.mkv", 3_588_000L, 1920, 1080, 1_900_000_000L, 1_760_470_000L,
            "/storage/emulated/0/Shows/House Of The Dragon/download-house-of-the-dragon-2022-season-1-episode-1.mkv"),
        arrayOf(2L, "download-house-of-the-dragon-2022-season-1-episode-2.mkv", 3_232_000L, 1920, 1080, 1_850_000_000L, 1_760_500_000L,
            "/storage/emulated/0/Shows/House Of The Dragon/download-house-of-the-dragon-2022-season-1-episode-2.mkv"),
        arrayOf(3L, "download-house-of-the-dragon-2022-season-1-episode-3.mkv", 3_570_000L, 1920, 1080, 1_880_000_000L, 1_760_460_000L,
            "/storage/emulated/0/Shows/House Of The Dragon/download-house-of-the-dragon-2022-season-1-episode-3.mkv"),
        arrayOf(4L, "download-house-of-the-dragon-2022-season-1-episode-4.mkv", 3_500_000L, 1920, 1080, 1_840_000_000L, 1_760_450_000L,
            "/storage/emulated/0/Shows/House Of The Dragon/download-house-of-the-dragon-2022-season-1-episode-4.mkv"),
        arrayOf(5L, "The.Great.Protector.S01E04.540p.x265.AAC.mkv", 2_100_000L, 960, 540, 730_000_000L, 1_760_410_000L,
            "/storage/emulated/0/Shows/The Great Protector/The.Great.Protector.S01E04.540p.x265.AAC.mkv"),
        arrayOf(6L, "The.Great.Protector.S01E05.540p.x265.AAC.mkv", 2_080_000L, 960, 540, 720_000_000L, 1_760_400_000L,
            "/storage/emulated/0/Shows/The Great Protector/The.Great.Protector.S01E05.540p.x265.AAC.mkv"),
    )

    /** A MatrixCursor-backed provider for authority "media", matching the
     *  exact projection MediaScanner reads (columns copied from it verbatim).
     *  Registered through Robolectric's provider controller; an insert-based
     *  fallback covers Robolectric builds that ship their own media store. */
    private class FakeMediaProvider : ContentProvider() {
        companion object {
            var rows: List<Array<Any>> = emptyList()
        }

        override fun onCreate(): Boolean = true
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val cols = (projection ?: arrayOf("_id")).toList()
            val cursor = MatrixCursor(cols.toTypedArray())
            for (r in rows) {
                val path = r[7] as String
                // Folder-scoped queries (videosInFolder) filter by DATA prefix.
                if (selection != null && selectionArgs != null && selection.contains("LIKE")) {
                    val prefix = selectionArgs[0].removeSuffix("%")
                    if (!path.startsWith(prefix)) continue
                }
                cursor.addRow(
                    cols.map { col ->
                        when (col) {
                            "_id" -> r[0]
                            "display_name" -> r[1]
                            "duration" -> r[2]
                            "width" -> r[3]
                            "height" -> r[4]
                            "_size" -> r[5]
                            "date_modified" -> r[6]
                            "_data" -> r[7]
                            "is_pending" -> 0
                            else -> null
                        }
                    }
                )
            }
            println("RENDER-DIAG provider.query(uri=$uri, cols=${cols.size}, sort=$sortOrder) -> ${cursor.count} rows")
            return cursor
        }
    }

    /** Feed the fake provider, probe the SHIPPED scanner, and fall back to
     *  MediaStore-API inserts when the provider registration is bypassed. */
    private fun installAndProbeMedia(context: Context) {
        val resolver = context.contentResolver
        FakeMediaProvider.rows = fakeVideoRows()
        runCatching {
            org.robolectric.Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")
        }.onFailure { println("RENDER-DIAG setupContentProvider failed: $it") }
        runCatching {
            ShadowContentResolver.registerProviderInternal("media", FakeMediaProvider())
        }.onFailure { println("RENDER-DIAG registerProviderInternal failed: $it") }

        // Probe: reproduce the scanner's EXACT query shape and print the raw
        // cursor, so a zero-row read is attributable (store vs sorting vs
        // projection).
        val cols = arrayOf(
            "_id", "display_name", "duration", "width", "height",
            "_size", "date_modified", "_data", "is_pending",
        )
        for (sort in listOf(null, "date_modified DESC")) {
            runCatching {
                resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols, null, null, sort)
            }.onSuccess { c ->
                println("RENDER-DIAG rawQuery sort=$sort rows=${c?.count} cols=${c?.columnNames?.toList()}")
                c?.use { if (it.moveToFirst()) print("RENDER-DIAG firstRow id=${it.getLong(0)} data=${it.getString(7)} pending=${it.getInt(8)}") }
                println()
            }.onFailure { println("RENDER-DIAG rawQuery sort=$sort threw: $it") }
        }

        val probeA = MediaScanner(context).scan(force = true)
        println("RENDER-DIAG probeA(scanner): videos=${probeA.videos.size}")
        if (probeA.videos.isEmpty()) {
            // Fallback: write through the MediaStore API exactly like an indexer.
            for (r in fakeVideoRows()) {
                val values = ContentValues().apply {
                    put("_id", r[0] as Long)
                    put("display_name", r[1] as String)
                    put("duration", r[2] as Long)
                    put("width", r[3] as Int)
                    put("height", r[4] as Int)
                    put("_size", r[5] as Long)
                    put("date_modified", r[6] as Long)
                    put("_data", r[7] as String)
                    put("is_pending", 0)
                }
                runCatching { resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) }
            }
            val probeB = MediaScanner(context).scan(force = true)
            println("RENDER-DIAG probeB(insert): videos=${probeB.videos.size}")
        }
    }

    /** Watch positions through the SHIPPED store — the same writes the player
     *  makes on pause. Ids are re-queried from whatever store answered, so
     *  seeding matches the scanned rows. Two continue items + one finished. */
    private fun seedWatchProgress(context: Context) {
        val resolver = context.contentResolver
        val idsByPath = HashMap<String, Long>()
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf("_id", "_data"), null, null, null,
        )?.use { c ->
            while (c.moveToNext()) idsByPath[c.getString(1)] = c.getLong(0)
        }
        println("RENDER-DIAG idsByPath=${idsByPath.size}")
        fun uriOf(pathSuffix: String): String? {
            val entry = idsByPath.entries.firstOrNull { it.key.endsWith(pathSuffix) } ?: return null
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, entry.value).toString()
        }
        runBlocking(Dispatchers.IO) {
            val store = MediaStateStore(MediaDatabase.get(context).mediaStateDao())
            uriOf("season-1-episode-1.mkv")?.let { store.updatePosition(it, 3_588_000L, 3_588_000L, finished = true) }
            uriOf("season-1-episode-2.mkv")?.let { store.updatePosition(it, 2_783_000L, 3_232_000L) }
            uriOf("S01E04.540p.x265.AAC.mkv")?.let { store.updatePosition(it, 826_000L, 2_100_000L) }
        }
    }

    private fun libraryFactory(context: Context, stash: (LibraryViewModel) -> Unit = {}): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val vm = LibraryViewModel(
                    scanner = MediaScanner(context),
                    stateStore = MediaStateStore(MediaDatabase.get(context).mediaStateDao()),
                    settings = context.playerSettingsDataStore,
                )
                stash(vm)
                return vm as T
            }
        }

    /** Wait for library content; on timeout dump the VM state + the semantics
     *  tree so CI logs say exactly what rendered (or why nothing did). */
    private fun awaitOrDump(expected: String, vm: () -> LibraryViewModel?) {
        try {
            compose.waitUntil(timeoutMillis = 25_000) {
                compose.onAllNodesWithText(expected, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (t: Throwable) {
            val state = vm()?.ui?.value
            println("RENDER-DIAG expected='$expected' loading=${state?.loading} videos=${state?.videoCount} inProgress=${state?.inProgress?.size} series=${state?.series?.size}")
            compose.onRoot().printToLog("RENDER-DIAG")
            throw t
        }
    }

    @Test
    fun library_home() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        installAndProbeMedia(context)
        seedWatchProgress(context)
        var vmRef: LibraryViewModel? = null
        val factory = libraryFactory(context) { vmRef = it }
        compose.setContent {
            Stage(false) {
                LibraryScreen(
                    viewModelFactory = factory,
                    onOpenVideo = {},
                )
            }
        }
        awaitOrDump("Continue watching") { vmRef }
        capture("library_home")
    }

    @Test
    fun library_series() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        installAndProbeMedia(context)
        seedWatchProgress(context)
        var vmRef: LibraryViewModel? = null
        val factory = libraryFactory(context) { vmRef = it }
        compose.setContent {
            Stage(false) {
                LibraryScreen(
                    viewModelFactory = factory,
                    onOpenVideo = {},
                    startDestination = LibraryStartDestination.Series,
                )
            }
        }
        awaitOrDump("House Of The Dragon") { vmRef }
        capture("library_series")
    }
}
