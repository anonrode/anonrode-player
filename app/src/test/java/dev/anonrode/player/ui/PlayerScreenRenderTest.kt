package dev.anonrode.player.ui

import android.content.Context
import android.media.AudioManager
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
import dev.anonrode.player.core.model.Video
import dev.anonrode.player.core.ui.theme.PlayerAccentTokens
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
                        onDismiss = {},
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
}
