# AGENT.md — Anonrode Player Architecture, Jetpack Compose & UI/UX Standards

Permanent source of truth for Anonrode Player's Android architecture, Jetpack Compose performance standards, and UI/UX design laws. All agents, contributors, and code changes MUST strictly adhere to this document.

---

## 1. Project Overview & Multi-Module Architecture

Anonrode Player is a high-performance native Android media player built on Kotlin, Jetpack Compose, AndroidX Media3 (ExoPlayer), FFmpeg (`nextlib`), and an in-process native C++/JNI subtitle synchronization engine.

### 1.1 Module Structure & Responsibilities

```
anonrode-player/
├── app/                  # Application root: MainActivity, PlayerActivity, top navigation & Compose screens
│   └── src/main/java/dev/anonrode/player/
│       ├── AnonrodeApp.kt          # Application class, DI setup, lifecycle initialization
│       ├── MainActivity.kt         # Navigation graph (Home, Series, Settings), permission gates
│       ├── PlayerActivity.kt       # Dedicated full-screen player activity, insets & sensory orientation
│       ├── PlayerPrefs.kt          # SharedPreferences persistence for UI preferences
│       ├── QueueOrderStore.kt      # Folder playlist custom drag-order persistence
│       ├── audio/                  # Host dialogs, track pickers, and equalizer sheets
│       └── ui/                     # Composable player, library, sheets, HUD, and gesture layers
├── feature/
│   ├── library/          # MediaStore library state management & search view model
│   │   └── .../feature/library/LibraryViewModel.kt
│   └── player/           # Playback engine, MediaSessionService, FFmpeg decoder injection
│       └── .../feature/player/PlaybackEngine.kt, PlayerService.kt
├── core/
│   ├── model/            # Immutable domain models (Video, Series, SubtitleCue, MediaState)
│   ├── database/         # Room persistence for per-video state (MediaStateDao, MediaDatabase)
│   ├── datastore/        # Global player preferences via Jetpack DataStore (PlayerSettings)
│   ├── media/            # Subtitle sync DSP, VAD, OnsetExtractor, parsers (SRT/VTT/ASS), MediaScanner
│   └── ui/               # Design system tokens, skin palettes (Theme.kt, ThemePrefs.kt)
└── gradle/
    └── libs.versions.toml# Authoritative dependency and plugin version catalog
```

### 1.2 Build, Test, and CI Verification Commands

This repository is developed on environments without local Android SDK/JDK installations. All build and rendering verification is enforced via GitHub Actions CI (`.github/workflows/`):

* **Local Syntax & Unit Test Validation**:
  ```bash
  ./gradlew testDebugUnitTest
  ```
* **Roborazzi Screenshot Recording & Verification**:
  ```bash
  ./gradlew recordRoborazziDebug
  ./gradlew verifyRoborazziDebug
  ```
* **Assembly Commands (CI)**:
  ```bash
  ./gradlew assembleDebug
  ./gradlew assembleReleaseWithDebugSigning
  ```
* **Windows Git SSL Backend Requirement**:
  ```bash
  git -c http.sslBackend=openssl push origin <branch>
  ```

---

## 2. Unidirectional Data Flow (UDF) & State Management

### 2.1 Strict ViewModel UDF Law
1. **Single Immutable State Flow**: Every ViewModel exposes a single, immutable `StateFlow<UiState>` via `.asStateFlow()`.
2. **Lifecycle-Aware State Collection**: In composables, collect state strictly using `collectAsStateWithLifecycle()`:
   ```kotlin
   val state by viewModel.uiState.collectAsStateWithLifecycle()
   ```
3. **Stateless UI Boundary**: Composables never reference raw database DAOs, repositories, or heavy engine components. State is hoisted upward; events are dispatched downward via lambda callbacks.

### 2.2 Video Player Screen: Composable Slot Pattern
To maintain complete separation of concerns and eliminate layout coupling, `PlayerScreen` is structured into four distinct, stateless layout slots:

```kotlin
@Composable
fun PlayerScreen(
    videoSurfaceSlot: @Composable BoxScope.() -> Unit,
    gestureOverlaySlot: @Composable BoxScope.() -> Unit,
    hudControlsSlot: @Composable BoxScope.() -> Unit,
    sideDrawerSlot: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // Layer 0: Decoder surface
        videoSurfaceSlot()

        // Layer 1: Gesture interception (drags, double-taps, hold-to-boost)
        gestureOverlaySlot()

        // Layer 2: Main HUD controls, top bar, ribbons, scrubber dock
        hudControlsSlot()

        // Layer 3: Topmost modal side drawers (Subtitles, Playlist, Settings)
        sideDrawerSlot()
    }
}
```

* **`VideoSurfaceSlot`**: Hosts `PlayerSurface` or `AndroidView` binding `SurfaceView` to `PlaybackEngine.player`. Completely decoupled from UI controls.
* **`GestureOverlaySlot`**: Detects 2-axis horizontal scrubber dragging, left-half vertical brightness, right-half vertical stream volume, double-tap seek, and hold-to-boost gesture mechanics without triggering layout passes on child controls.
* **`HudControlsSlot`**: Renders the top header bar, collapsible quick-action ribbon, bottom scrubber timeline, and playback transport buttons.
* **`SideDrawerSlot`**: Renders right-aligned, full-height translucent sliding drawers for Subtitle management, Playlist navigation, and Player Settings.

---

## 3. Jetpack Compose Performance & Recomposition Laws

*(Ref: Jetpack Compose Internals by Jorge Castillo & Effective Kotlin by Marcin Moskała)*

### 3.1 Zero Full-HUD Recomposition on Playback Ticks
Playback position (`currentPosition`, `bufferedPosition`) updates 10–20 times per second during playback.
* **FORBIDDEN**: Passing raw changing `positionMs: Long` or `positionSec: Float` directly into `PlayerScreen`, `HudControlsSlot`, or top-level containers. Doing so invalidates the composition scope of the entire screen on every playback frame.
* **MANDATORY: State Read Deferral via Lambda Providers**:
  Pass lambda state providers down to leaf rendering composables:
  ```kotlin
  @Composable
  fun ScrubberBar(
      currentPositionProvider: () -> Long,
      durationMs: Long,
      onSeek: (Long) -> Unit,
      modifier: Modifier = Modifier,
  )
  ```
  Only leaf elements (the scrubber thumb and elapsed timestamp text) read `.invoke()`, confining recomposition exclusively to those leaves.

### 3.2 Stability Annotations & Immutable Collections
* All UI data classes (`Video`, `Series`, `InProgress`, `EpisodeItem`, `TrackItem`, `QuickAction`) MUST be explicitly annotated with `@Immutable` or `@Stable`.
* Never expose raw mutable `List<T>` to composable parameters. Use `kotlinx.collections.immutable.ImmutableList` or read-only `List<T>` containing strictly `@Immutable` items.
* Compose skips equality checks and recomposition on unchanged items only when the container and item types are provably stable.

### 3.3 Stable Keys & ContentTypes in Lazy Collections
Every `LazyRow` and `LazyColumn` across the Home Screen, Series Hub, and Playlist Drawer MUST supply an explicit, persistent `key` and `contentType`:
```kotlin
LazyColumn(modifier = Modifier.fillMaxSize()) {
    items(
        items = seriesList,
        key = { series -> "series_${series.folderPath}" },
        contentType = { "series_card" },
    ) { series ->
        SeriesCard(series = series, onClick = { ... })
    }
}
```
* Omitting `key` causes complete list-item recycling and tears down active thumbnails and scroll states during item additions or order mutations.

### 3.4 Hardware-Accelerated Overlay & Scrim Animations
* Never animate layout bounds (`Modifier.height`, `Modifier.width`, or `Modifier.padding`) on every frame over an active video decoder.
* Use `Modifier.graphicsLayer { alpha = animatedAlpha }` or `AnimatedVisibility` with:
  ```kotlin
  fadeIn(animationSpec = tween(210, easing = FastOutSlowInEasing)) +
  scaleIn(initialScale = 0.985f, animationSpec = tween(210, easing = FastOutSlowInEasing))
  ```
* Side drawers MUST animate using `slideInHorizontally(initialOffsetX = { it })` and `slideOutHorizontally(targetOffsetX = { it })` to preserve GPU-backed hardware layer translations.

---

## 4. UI Density & Visual Hierarchy Laws

*(Utility-First High-Density Media Player Standard)*

### 4.1 Home Screen: High-Density Universal Dashboard
Media players are utility tools. Users navigate large media libraries and must not be trapped in oversized, sparse cards.

1. **`Continue watching` Section**:
   * Positioned immediately above the vertical `FOLDERS` list.
   * MUST be structured as a compact single-row horizontal `LazyRow` (`contentPadding = PaddingValues(horizontal = 16.dp)`).
   * **Card Dimensions**: Exactly `140.dp × 80.dp` (16:9 aspect ratio thumbnail).
   * **Overlays**: Bottom-left duration pill (`▶ 24:12`), bottom edge `3.dp` progress bar in accent color.
   * **Text Hierarchy**: Zero multi-line text blocks underneath the card. Title is rendered single-line with ellipsis.

2. **`FOLDERS` Section**:
   * Vertical `LazyColumn` presenting device video directories.
   * Displays folder icon, directory name, video count, total duration, and natural-order sort indicators.

### 4.2 Series Screen (`SERIES HUB`): Compact Horizontal Series Cards
The Series Hub organizes multi-episode series and seasons. It must allow 5–6 series cards to fit comfortably within a standard phone viewport.

* **Card Height**: Compact horizontal row card (`~88.dp–96.dp` total height).
* **Card Anatomy**:
  * **Left Thumbnail**: `112.dp × 68.dp` (16:9 ratio) thumbnail with rounded corners (`8.dp`), showing poster frame and episode count badge.
  * **Center Metadata (3-Line Layout)**:
    * Line 1: Series Title (`14.sp`, `FontWeight.SemiBold`, ellipsized).
    * Line 2: Watched Progress (`12.sp`, `Watched: X/Y • Z% progress`, tabular numerals).
    * Line 3: Next Up (`11.sp`, `Up Next: S01E03 • Episode Title`, muted).
  * **Right Action**: A circular `38.dp` 1-Tap Quick Resume play button (`▶`) that immediately launches the next unwatched episode in `PlayerActivity`.

### 4.3 Icon & Text Contrast on Video Surfaces
Video playback frames vary unpredictably between pitch black and blinding white. Relying on default system or theme contrast over video leads to unreadable controls.

1. **Translucent Glass Backdrops**:
   * All top bar and bottom transport icon buttons MUST be enclosed in dark translucent circular backdrops (`Color.Black.copy(alpha = 0.55f)`).
   * Active toggle states (Loop, Shuffle, Night Mode, Enhancer) replace the dark circle with the primary accent background (`#3DDBA8` or active skin token).
2. **Explicit Icon Tinting**:
   * Every icon rendered within a player overlay MUST specify `tint = Color.White` (`#FFFFFF`). Never allow icons to inherit `LocalContentColor` from ambient theme palettes over video surfaces.
3. **Scrim Gradients**:
   * Top bar controls MUST sit on a downward vertical gradient (`Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))`).
   * Bottom transport and timeline controls MUST sit on an upward vertical gradient (`Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))`).
   * All timeline text and HUD labels MUST use tabular numerals (`tnum`), white coloring, and subtle drop shadows.

### 4.4 Landscape Translucent Side Drawers
Secondary menus (Subtitles, Playlist, and Settings) must never block playback with center-screen popups or opaque modals.

* **Right-Aligned Drawer Geometry**:
  * Width: Fixed `340.dp–360.dp`.
  * Height: Full viewport height (`fillMaxHeight()`).
  * Surface: Translucent near-black glass (`Color(0xD60E1117)` with 0.96 alpha, zero blur, hairline white border `8%`).
  * The left video surface remains visible and active while the drawer is open.
* **Hierarchical Drill-Down Navigation**:
  * Drawers with multi-faceted controls (such as the Subtitle Drawer) MUST use internal drill-down navigation states (`MAIN_MENU`, `TRACK_SELECT`, `SYNC_ADJUSTMENT`, `STYLE_CUSTOMIZATION`).
  * Never dump dozens of disconnected chips, sliders, and steppers into a single chaotic scrolling column. Provide clear header back navigation (`←`) within the drawer.

---

## 5. Subtitle Synchronization Engine Laws (Untouchable Invariants)

The subtitle sync engine (`core/media/.../sync/`) is the flagship core of Anonrode Player ("the life of the app"). Its mathematical and architectural invariants are non-negotiable:

1. **No ProcessBuilder or Subprocesses**: Android 10+ (API 29+) SELinux rules enforce $W \oplus X$ (`Permission Denied`). All native signal crunching runs in-process via C++/NDK JNI (`AMediaExtractor`, `AMediaCodec`, NEON SIMD).
2. **Asymmetric Lock Authority**: A 15-second live-sync pass MUST NEVER overwrite or clobber a verified full-file fingerprint lock if the discrepancy is $> 1.0\text{s}$. Full-file FFT correlation holds senior authority over live audio cadences.
3. **Resumable Extraction Resilience**: Automatic re-arms schedule fingerprint passes with WorkManager `KEEP` rather than `REPLACE`, ensuring in-flight background passes are not cancelled during playback seek events.
4. **Stage-0 Cue-Anchor Hybrid Probe**: The cue anchor uses the hybrid onset union (silencedetect + Silero VAD) to ensure instant sub-2s locks on continuous audio and music mixes.
5. **No Casually Re-Tuned Constants**: Correlator thresholds, $z$-score ramps (5.5→4.5), and noise-floor constants are pinned and validated against real content benchmarks.

---

## 6. Lifecycle & Media Engine Safety

### 6.1 Media Session & Lifecycle Management
* All `Player` and `PlaybackEngine` listeners MUST be registered and unregistered strictly inside `DisposableEffect(lifecycleOwner)` using `rememberUpdatedState`.
* Audio focus changes, background transitions, and PiP mode entries MUST cleanly coordinate between `PlayerActivity` and `PlayerService`.
* When background playback is disabled in `PlayerSettings`, leaving `PlayerActivity` MUST pause playback immediately and free decoder hardware surfaces.

### 6.2 System Bars & Immersive Mode
* In landscape video mode, system bars MUST be controlled via `WindowInsetsControllerCompat`:
  ```kotlin
  val controller = WindowCompat.getInsetsController(window, window.decorView)
  controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
  controller.hide(WindowInsetsCompat.Type.systemBars())
  ```
* When HUD controls auto-hide (after the 5.5s inactivity timeout), system navigation bars must hide synchronously.
* Upon user tap, HUD controls and transient system bars restore smoothly without causing video aspect ratio re-layouts.

---

## 7. Development Discipline & Non-Negotiable Rules

1. **No AI Attribution**: Never commit or author `Co-Authored-By`, "Generated with", or AI model strings anywhere in commits, code, or documentation.
2. **Zero Competitor Brand Names**: Never reference competitor apps or products in code, comments, docs, or UI strings. Describe technical behavior and standards only.
3. **No Dead Code**: Any unused composable, modifier, function, or resource with zero call sites is a defect and must be removed immediately.
4. **Every Control Must Be Functional**: Every toggle, slider, button, or menu item must be wired to real, persisted engine or database state. Decorative or placeholder controls are forbidden.
5. **Standing Approvals for Loop Work**: Standing approval is granted for pushing loop commits and dispatching UI render verification runs (`git -c http.sslBackend=openssl push origin <ref>`). Official tags and releases remain gated on explicit user authorization.
