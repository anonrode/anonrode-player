# HANDOVER NOTE — anonrode-player

_Date: 2026-09-20 · Written after the v0.8.6 release · Read this before touching anything._

---

## 1. Where Things Stand

| Thing | State |
|---|---|
| Latest release | **v0.8.6** on GitHub Releases (tag `v0.8.6`, commit `f3df461`, marked **Latest**) |
| APKs | 10 assets attached automatically by `publish_release.yaml` (arm64-v8a / armeabi-v7a / x86 / x86_64 / universal × release-with-debug-signing + debug) |
| CI Status | **GREEN** — `android_build` run 35449355358, `publish_release` run 35449369815 |
| Target APK to install | `anonrode-player-v0.8.6-app-arm64-v8a-releaseWithDebugSigning.apk` (installs cleanly over v0.8.5 / v0.7.0 without data loss) |
| Release URL | https://github.com/anonrode/anonrode-player/releases/tag/v0.8.6 |

---

## 2. What v0.8.6 Contains (Player Redesign & Performance Overhaul)

### Top Bar Architecture (`PlayerScreenControls.kt`)
- **Row 1 (Primary Header Controls)**:
  - Back button (`←`) exits cleanly back to library browsing.
  - Video title rendered with single-line ellipsis (no internal path truncation).
  - Audio Track picker chip (`♫`) opens the host audio track sheet.
  - Subtitle toggle chip (`CC`) directly gates cue rendering; shown only when tracks/sidecars exist.
  - Decoder toggle pill (`HW` / `SW`) switches hardware/software codec pipeline via host rebuild.
  - Control Center button (`⋮`) opens `PlayerControlCenterSheet` with sleep timer, stats, and audio options.
- **Row 2 (Collapsible & Scrollable Quick Tools Ribbon)**:
  - Horizontally scrollable row containing: Sub-Sync toggle/status pill, Equalizer, 3-state Rotation Lock, Picture-in-Picture (`PiP`), Frame Screenshot / Capture, and Cast device picker.
  - Collapsible `<` / `>` chevron toggle allows one-tap minimization/expansion so tools never obstruct playback.

### Bottom Bar & Transport Restructure (`PlayerScreenBottomBar.kt`)
- **Sleek Scrubber Thumb**: Custom 14dp circular thumb (`Box(Modifier.size(14.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))`) replacing default M3 slider thumb, completely eliminating the vertical line (`|`) artifact. Annotated with `@OptIn(ExperimentalMaterial3Api::class)`.
- **Balanced 3-Cluster Layout**:
  - **Left**: Screen Lock button (`🔒`).
  - **Center**: 5 transport controls (`⟲ 10s`, `⏮`, **Big Play/Pause**, `⏭`, `10s ⟳`).
  - **Right**: Playback Speed pill (`1.0×`, tap toggles inline speed strip `0.5×`–`2.0×`; long-press resets to `1.0×`) and Aspect Ratio button (`FIT`, tap cycles mode; long-press opens mode selector).
  - Cleaned up redundant bottom utility dock (tools relocated to top ribbon).

### Gesture Engine (`PlayerScreenGestures.kt` & `PlayerScreen.kt`)
- **Double-Tap**:
  - Center ($35\%–65\%$ width): Toggles Play / Pause.
  - Left ($< 35\%$ width): Seeks backwards by 10 seconds.
  - Right ($> 65\%$ width): Seeks forward by 10 seconds.
- **Vertical Swipe Controls**:
  - Left half: Slide up/down adjusts screen brightness (`0%`–`100%`).
  - Right half: Slide up/down adjusts stream volume (`0%`–`100%`).
- **Repositioned Gesture HUD**: Moved `GestureHudPill` to `Alignment.TopCenter` with status bar and cutout padding, keeping volume, brightness, and 2× boost indicators completely clear of subtitles.
- **Hold-to-Boost**: Long-press and hold anywhere triggers instant `2× speed` until released.

### Subtitle Dragging & Sub-Sync (`PlayerScreenSubtitles.kt` & `PlayerSettings.kt`)
- **Draggable Subtitles**: Long-pressing active cue text triggers haptic feedback and scales the text ($1.08\times$), allowing free vertical dragging from $10\%$ to $94\%$ of screen height. Saved and remembered per video in `PlayerPrefs.saveSubtitlePosition`.
- **Always-On Sub-Sync**: `subtitleAutoSyncEnabled` defaults to `true` permanently across all videos in `PlayerSettings.kt`.
- **Instant Force Re-Sync**: Long-pressing the Sub-Sync pill triggers `actions.resyncNow()`, running a full-file cross-correlation fingerprint immediately.

### Auto-Orientation & Library Performance
- **Auto-Landscape (`PlayerActivity.kt`)**: Added `onVideoSizeChanged` listener to auto-orient widescreen ($w > h$) videos to `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`.
- **Scroll Optimization (`LibraryScreen.kt`)**: Bounded `PosterArt` thumbnails to `size(240, 135)` with `crossfade(true)`, eliminating 1080p uncompressed frame decoding during fast library scrolling.

---

## 3. Build & Release System

- **Local environment has NO Java / Android SDK.** Local Gradle builds are not possible. All builds happen in GitHub Actions CI.
- **Triggering Releases**:
  - Pushing to `main` triggers `android_build.yaml` (debug APK verification).
  - Pushing tags matching `v*` triggers `publish_release.yaml` (builds signed release APKs and automatically publishes the GitHub Release with attached APKs).
  - Version bump in `app/build.gradle.kts` (`versionCode` and `versionName`) must precede the tag.
- **Windows Git Push Gotcha**:
  - Git's Windows `schannel` SSL backend can encounter `Empty reply from server` when talking to GitHub. Always use `git -c http.sslBackend=openssl push origin <branch/tag>`.
- **Keystore**: Injected via CI secrets (`ANONRODE_KEYSTORE_BASE64`). Never committed to repository.
- **Rules**:
  - Never add AI attribution to commits (no `Co-Authored-By`, no "Generated with").
  - Zero competitor brand names in code, UI strings, logs, or documentation.
  - Ask before pushing and before large refactors.

---

## 4. File Map of Core Surfaces

| Area | File |
|---|---|
| Top Bar Chrome | `app/src/main/java/dev/anonrode/player/ui/PlayerScreenControls.kt` |
| Bottom Bar & Dock | `app/src/main/java/dev/anonrode/player/ui/PlayerScreenBottomBar.kt` |
| Gestures & Touch Zones | `app/src/main/java/dev/anonrode/player/ui/PlayerScreenGestures.kt` |
| Subtitle Rendering & Drag | `app/src/main/java/dev/anonrode/player/ui/PlayerScreenSubtitles.kt` |
| Main Player Container | `app/src/main/java/dev/anonrode/player/ui/PlayerScreen.kt` |
| Activity & Orientation | `app/src/main/java/dev/anonrode/player/PlayerActivity.kt` |
| Library Thumbnail Perf | `app/src/main/java/dev/anonrode/player/ui/LibraryScreen.kt` |
| Settings Defaults | `core/datastore/src/main/java/dev/anonrode/player/core/datastore/PlayerSettings.kt` |
| Version Configuration | `app/build.gradle.kts` |
| Engineering Skill | `.agents/skills/mobile-app-engineering/SKILL.md` |

---

## 5. Next Steps & Recommended Verifications

1. **On-Device Real World Run**:
   - Sideload `anonrode-player-v0.8.6-app-arm64-v8a-releaseWithDebugSigning.apk` on a physical device.
   - Verify top ribbon collapse/expand animation and smooth horizontal scrolling.
   - Verify 14dp circular seekbar thumb drag feel and precision.
   - Verify double-tap center for play/pause and sides for ±10s seek.
   - Verify vertical swipe brightness (left) and volume (right) with HUD at top-center.
   - Verify long-press subtitle drag up/down and position persistence across reopening.
   - Verify auto-orientation behavior when opening 16:9 widescreen videos in portrait.

---

## 6. Uncommitted Files (Intentionally)

- `docs/draw_player_v1.py`, `docs/player_screen_v0_7.png` — local design sketches; keep uncommitted unless explicitly requested.
- Everything in `tools/` — user's personal tools, hands off.

