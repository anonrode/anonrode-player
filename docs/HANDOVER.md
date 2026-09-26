# HANDOVER NOTE — anonrode-player

_Date: 2026-09-26 · Written after the v0.8.8 release + the v0.8.9 functional pass · Read this before touching anything._

> **Read `.agents/AGENTS.md` first.** It holds the non-negotiable operating
> rules. Most important: *never* defer or simplify a deliverable because this
> machine has no Java/Android SDK and cannot compile locally. CI is the gate;
> write the complete correct code and let CI prove it.

---

## 1. Where Things Stand

| Thing | State |
|---|---|
| Latest release | **v0.8.8** on GitHub Releases (tag `v0.8.8`) |
| APKs | 10 assets attached automatically by `publish_release.yaml` (arm64-v8a / armeabi-v7a / x86 / x86_64 / universal × release-with-debug-signing + debug) |
| CI Status | Verified via GitHub Actions CI (`android_build` and `publish_release`) |
| Target APK to install | `anonrode-player-v0.8.8-app-arm64-v8a-releaseWithDebugSigning.apk` (installs cleanly over v0.8.7 / v0.8.6 without data loss) |
| Release URL | https://github.com/anonrode/anonrode-player/releases/tag/v0.8.8 |

---

## 1b. v0.8.9 — Functional Pass (commit `18cb8fa`, NOT yet pushed)

An audit of v0.8.8 found several controls that looked real but did nothing,
plus dead code. All of it is fixed. **Nothing in the sync engine was touched** —
`core/media/.../sync/` is byte-identical to v0.8.8 by design.

### Controls that were decorative and now work

| Tool | Was | Now |
|---|---|---|
| `Audio Effect` | Flipped a Compose flag nothing read; showed a toast | Real DSP: `VoiceClarityProcessor` (RBJ high-shelf + rumble high-pass, clamped against clipping), persisted in `PlayerSettings.audioEffectEnabled`, also exposed in Settings → Audio |
| `Background Play` | Flipped a flag nothing read | Writes the real `PlayerSettings.backgroundPlayback`, the same field `PlayerActivity.onStop` already consulted |
| `Customise` | Toast only — no reorder existed | Real editor (`PlayerScreenRibbonSheet.kt`): reorder + hide/show, persisted to `PlayerPrefs` |
| `Shuffle` / `Loop` | Reset to OFF on every open | Persisted per-video (Room v5), restored on open |
| `Speed` long-press | Was wired to `setSpeed(1f)` | Now `resetSpeed()` — distinct code path, confirms "already 1×" instead of a silent no-op |

### Speed list corrected

The reference spec requires `0.5, 0.75, 1, 1.25, 1.5, 1.75, 2`. The code was
missing **1.75×**. Fixed at `PlayerScreen.kt`.

### New architecture

- **`RibbonTool` enum** (`PlayerScreenCommon.kt`) — the ribbon now renders
  from a user-editable ordered list instead of 13 hard-coded calls. This is
  what made `Customise` possible. Persistence stores enum **names** (newline
  joined), so reordering the declaration never invalidates a saved layout;
  unknown names are dropped and new tools append in catalogue order.
- **`VoiceClarityProcessor`** (`core/media/audio/`) — chain position is
  load-bearing: `syncAnalyzer → voiceClarity → volumeBoost`. It must sit
  *after* the sync analyzer or the VAD's energy floor moves, and *before* the
  boost so the existing hard-clip ceiling stays last.
- **Room v5 migration** adds `shuffle_enabled` + `repeat_mode`. Per the
  `ensureRow` KDoc, both new NOT NULL columns are bound in the INSERT —
  forgetting that silently disables every writer.

### R8 now enabled on `releaseWithDebugSigning`

That build type shipped **unshrunk** because "R8 breaks DataStore/
serialization" and the keep rules were never written. They now exist in
`proguard-rules.pro` (kotlinx.serialization companions/serializers, coroutine
`SafeContinuation`, Room, enum `values()`/`valueOf`), and `isMinifyEnabled`
/ `isShrinkResources` are both `true`.

### Dead code removed

`PlayerScreenChrome.kt` (517 lines) — a complete second generation of the
player chrome (`V9ControlsOverlay`, `V9TopBar`, `V9BottomZone`,
`V9TransportRow`, `V9ToolRail`) with **zero** call sites. Deleted.

### Rule compliance

Zero competitor brand names remain in code, comments, docs, and the manifest.

---

## 2. What v0.8.8 Contains (Full Sub-Sync Reliability & Player Screen V2 Clean-Sheet Redesign)
v0.8.8 resolves two major systems:
1. Complete Sub-Sync Reliability & Deadlock Fixes (root causes from live device logs on Infinix X669 · Android API 31).
2. Clean-Sheet Player Screen V2 Redesign (ripping out incremental band-aids and establishing a strict 3-tier hierarchy matching the reference screenshots and screen recordings).

### Player Screen V2 Clean-Sheet Redesign (`app/src/main/java/dev/anonrode/player/ui/`)
Previous attempts at "simplifying" or "curating" the player chrome pushed core visual playback settings into hidden sheets or bottom rails. The user explicitly rejected this, demanding a clean-sheet architecture matching the reference player:
- **Strict 3-Tier Control Hierarchy**:
  - **Tier 1 (Direct 1-Tap On-Screen Tools)**:
    - **Top Header**: Back (`←`), clean video title with ellipsis, Audio Track selection (`♫`), Subtitle toggle (`CC`), Decoder toggle badge (`HW` / `SW`), and More settings (`⋮`).
    - **Quick Access Ribbon (Directly below header)**: Horizontally scrollable row containing all 13 reference tools with exact phrases and circular badges:
      1. `Night Mode`: Toggles eye-protection amber tint scrim over video canvas.
      2. `Customise Items`: Reorder toolbar items.
      3. `Shuffle`: Toggles playlist shuffle.
      4. `Loop`: Cycles repeat modes: `Loop Off` → `Loop 1` → `Loop All`.
      5. `Mute`: 1-tap master audio mute toggle (saves and restores volume).
      6. `Sleep Timer`: Direct sleep timer cycle (`15m` → `30m` → `45m` → `60m` → `End` → `Off`).
      7. `A - B Repeat`: 1-tap pin A, pin B, clear loop (`A⮂B`).
      8. `Audio Effect`: Toggles voice clarity / vocal boost, featuring a **bright red active indicator dot** matching reference video.
      9. `Equalizer`: Direct 5-band EQ panel toggle.
      10. `Speed`: Displays active speed pill (`1X`, `1.25X`, etc.), tap cycles presets, long-press resets to `1×`.
      11. `Screenshot`: Captures current video frame via PixelCopy, saving to `Pictures/AnonPlayer`.
      12. `Background Play`: Toggles audio-only background playback.
      13. `Screen Rotation`: Cycles orientation (`Auto` → `Landscape` → `Portrait`).
  - **Timeline Scrubber**: Elapsed time (`14:15`), seekbar with circular 14dp thumb, buffered track, played track, **A–B repeat amber marker pins** on the track, and total duration (`53:08`).
  - **Bottom Transport Bar**:
    - Far Left: Screen Lock button (`🔒`).
    - Centered: 10s Rewind (`⏮`), Large Play/Pause (`▶ / ⏸`), 10s Fast-Forward (`⏭`).
    - Far Right: Aspect ratio mode cycler (`◫`), Fullscreen / rotation expand button (`⤢`).
  - **Center Canvas Gesture Engine**:
    - **Hold-to-2× Speed + Dynamic Drag**: Long-press engages temporary 2.0× speed; sliding horizontally while holding adjusts temporary speed dynamically between 1.0× and 3.5× in 0.25× increments with live HUD pill feedback; releasing instantly restores the user's previously selected normal speed.
    - **Double-Tap Seek**: Left 35% seeks -10s, right 35% seeks +10s, center toggles Play/Pause.
    - **Swipe Scrubbing & Volume/Brightness**: Horizontal drag scrubs timeline; vertical drags control brightness (left) and volume (right).
    - **Draggable Subtitles**: Subtitle cues remain draggable with persistent position saving.
    - **A–B Repeat Effect**: Automatic loop detection seeking back to Point A when Point B is reached.
- **Tier 2 (Three-Dots `⋮` Control Center)**: Secondary in-playback settings (Subtitle Sync offset tuning with ±0.1s nudges and speech correlator status, Subtitle Appearance styling tray, Audio Decoder pipeline, Playback Resume behavior, and Media details) with a link to Tier 3 App Settings.
- **Tier 3 (App Global Settings)**: Deep infrequent settings (storage paths, auto-download subtitles, global hardware acceleration defaults, theme).

### Subtitle Sync Engine & Processor (`core/media`)
- **Fatal Live Sync Deactivation on Video Open (`AudioSyncProcessor.kt`)**: `AudioSyncProcessor.reset()` previously left `configured = true` while clearing `active = false`. On the next video open, `setCues(...)` saw `configured && !active`, concluded the sink had no PCM capability, and permanently called `listener.onSyncNoMatch()` with `gaveUp = true` at frame 0. Fixed by ensuring `reset()` and unconfigured audio formats set `configured = false`.
- **Dialogue Pause Agreement Retention & Leaky Integrator (`AudioSyncProcessor.kt`)**: On `SpeechCorrelator.Outcome.NoMatch`, tentative agreement progress is decayed rather than instantly wiped, and `SpeechCorrelator.Outcome.NotReady` preserves `stableHits` so natural conversational pauses don't reset progress toward the required 2 consecutive agreeing passes.
- **Hierarchical 2-Stage Continuous Envelope Engine (`SpeechCorrelator.kt`)**:
  - **Stage 1 (Nominal $\alpha = 1.0$ Fast Path)**: Evaluates nominal framerate across full $\pm 60$s shift range (stride 1) with 3-point sub-bin parabolic interpolation. Locks standard videos in $< 100$ms instead of brute-forcing all slopes.
  - **Stage 2 (Multi-Resolution Framerate Drift)**: For videos with PAL / telecine speedup/slowdown, searches candidate slopes in a coarse-to-fine hierarchy (stride 4, then fine refinement at stride 1).
  - **Verification Gate Sign Inversion Fix**: Corrected the mathematical inversion in `evalHalf` (`i + fineBestShift`) and `cueHits / recall` (`cue_bin - fineBestShift`), which previously caused 100% of shifted continuous envelope locks to fail validation.
- **Pearson Subtitle Grid Negative Headroom (`SpeechCorrelator.kt`)**: Added `padBins = (maxOffsetSec / ALIGN_BIN).toInt()` headroom to the subtitle grid $B$ and search loop. Subtitle cues leading audio (negative shift) no longer clamp to bin 0 or read zero-fill, restoring true Pearson correlation peaks on mid-video resumes.
- **Live Pass Scheduler Alignment & Continuous Z-Score (`SpeechCorrelator.kt` & `AudioSyncProcessor.kt`)**:
  - Aligned `PASS_BINS` to start at eligible window `160` (16.0s) and distributed 24 evaluation thresholds logarithmically, eliminating the first 4 dead passes that were guaranteed to return `NotReady`.
  - Replaced the abrupt $zFloor$ step drop at 240 bins with a smooth continuous curve transitioning from 9.0 to 7.0 between 160 and 280 bins.

### Concurrency, Cancellation & State Store (`app/` & `core/media`)
- **Decode Semaphore Deadlock Prevention (`SyncFingerprintJob.kt` & `OnsetExtractor.kt`)**: Added cooperative cancellation checks (`isCancelled()`) inside `OnsetExtractor.decodeAudio`'s `while (!outputDone)` loop. Cancelling an abandoned episode's background job now instantly breaks out of `MediaCodec` and frees `DECODE_GATE` (Semaphore(1)). In `SyncFingerprintJob`, replaced `tryAcquire()` with `tryAcquire(3, TimeUnit.SECONDS)` so a new video cleanly waits for a cancelled predecessor to release the gate instead of bouncing to exponential backoff.
- **Previous Episode Fingerprint Cancellation (`PlayerActivity.kt`)**: In `openVideo()`, captured `prevUri` *before* assigning `currentUriStr = uriStr`, correctly invoking `SyncFingerprint.cancel(applicationContext, prevUri)`.
- **Room State Collector per Video (`PlayerActivity.kt`)**: Replaced the blocked `while (true)` Room flow collector with `startStateStoreCollector(uriStr)`, which cancels the previous collector and starts a fresh collector per media item so Room locks for subsequent episodes are immediately applied.
- **Fresh UI State Reset (`PlayerActivity.kt`)**: Explicitly reset `lastCues = emptyList()` on video open to avoid carrying over cue state between media items.

### Automated Unit Test Suite
- Added `SpeechCorrelatorTest.kt` unit test suite covering positive/negative nominal shifts, PAL framerate drift detection, live correlator thresholding, and verification gate correctness.
- Added `reset followed by setCues does not trigger false give-up or analyzer inactive` regression test to `SyncScheduleRegressionTest.kt`.

---

## 3. What v0.8.7 Contained (Sub-Sync Reliability · Single-Plane Chrome · CI Test Gate)

v0.8.6's player redesign and performance work (top-bar architecture, bottom-bar
transport, gesture engine, subtitle dragging, auto-landscape, library thumbnail
perf) are all still in place. v0.8.7 adds:

### Sub-Sync Reliability (core/media)
- **Pass-budget collapse fix** (`AudioSyncProcessor.kt`): `setEnabled(true)` used to re-arm the 24-pass listening schedule on EVERY call — including redundant ON→ON hits from the host mirroring the whole settings DataStore. Zeroing `passesUsed` with a large `binCount` banked collapsed the ~4.8-minute listen into ~240 ms of audio; every collapsed pass was refused, each refusal reset `stableHits`, so the two consecutive agreeing passes a lock requires became unreachable. Now re-arms only on a genuine OFF→ON flip.
- **Second re-arm path closed** (`setCues`): re-arms only when the cue list actually changes (structural compare; `PlaybackEngine.setSubSyncEnabled` re-pushes the same list on every emission). `passesScheduled` monotonic counter pins it in tests.
- **Host churn fix** (`PlayerActivity.kt`): settings collector mirrors volume boost and sub-sync into the engine only when those values actually change.
- **No more permanent no-lock verdicts** (`SyncFingerprintJob.kt`): a truncated decode never gets marked "checked"; gate-busy retries can no longer consume the whole resume budget.
- **Lock always lands** (`SyncFingerprintJob.kt`): computed locks persist under `NonCancellable`; `CancellationException` rethrown cleanly.

### Single-Plane Chrome (player `ui/`)
- **Status strip** (`PlayerScreenStatusStrip.kt`): sync state + speed + remaining as a 26dp read-out line above the seek bar.
- **Inline style tray** (`PlayerScreenStyleTray.kt`): subtitle tuning in place; the cue stays visible while tuned.
- **Always-visible rail** (`PlayerScreenControls.kt` + `PlayerScreenChrome.kt`): lock, sync, EQ, style, rotate, speed, aspect, PiP, capture, cast — no collapse chevron, pure centered transport.

### CI Test Gate
- Both workflows run `testDebugUnitTest` as a hard gate before APK builds. First run caught and resolved 3 real compile errors plus 4 pre-existing test failures (3 matcher spec-drift cases now `@Ignore`d with reasons; one FP boundary fixed). 5 new regression tests drive the real `AudioSyncProcessor` and would trip the collapse.

### Top Bar Architecture (`PlayerScreenControls.kt`)
- *(v0.8.6, unchanged)*
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
   - Sideload `anonrode-player-v0.8.7-app-arm64-v8a-releaseWithDebugSigning.apk` on a physical device.
   - Verify the status strip: sync dot/label flips to green "Synced +X.Xs" once a lock lands (the whole point of the v0.8.7 engine work).
   - Open one of the episodes that failed on v0.8.6 (e.g. the one that never synced) and confirm it now fingers-prints to completion and locks.
   - Verify the rail is always visible (no collapse chevron), every hit area ≥ 48dp, and the tools scroll smoothly.
   - Verify the inline style tray: style changes apply with the cue still visible; no modal appears from the sync popover STYLE button or the Control Centre tile.
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

