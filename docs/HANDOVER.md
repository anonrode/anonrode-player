# HANDOVER NOTE — anonrode-player

_Date: 2026-08-31 · Written after the v0.7.0 release · Read this before touching anything._

---

## 1. Where things stand

| Thing | State |
|---|---|
| Latest release | **v0.7.0** on GitHub Releases (tag `v0.7.0`, commit `c690878`, marked **Latest**) |
| APKs | 10 assets attached to the release (arm64-v8a / armeabi-v7a / x86 / x86_64 / universal × release-with-debug-signing + debug) |
| CI | **GREEN** — `android_build` run 33379150147, `publish_release` run 33381774556 |
| Users to install | `anonrode-player-v0.7.0-app-arm64-v8a-releaseWithDebugSigning.apk` (same keystore as v0.6.1 → installs over it, no uninstall) |
| Full changelog | `docs/V0.7_REPORT.md` (Section 30 report, committed `e1d88b1`) |

## 2. What v0.7.0 contains (the master-prompt pass)

- **Library snappiness**: disk-snapshot persistence in `MediaScanner.kt` (`observeLibrary(): Flow<LibraryEvent>`, `@Serializable PersistedLibrarySnapshot`, `ScanSource` enum) — library paints from last scan instead of cold rescanning every launch.
- **Startup/playback**: lazy ExoPlayer build (double-checked locking), hand-authored baseline profile + profileinstaller, natural episode ordering with cached season/episode parse (O(N log N)).
- **Player UI polish**: 64dp play button, "10" labels inside skip buttons, tap-to-toggle timestamp side, right-edge action rail (`PlayerScreenActionRail.kt`: CC · audio · sync · rotate · more), PiP chip.
- **Subtitle sync (the headline)**:
  - Toggle in player chrome, default OFF, persisted globally in `PlayerSettings.subtitleAutoSyncEnabled` (DataStore) — survives across videos and restarts.
  - Smart sidecar auto-pick in `SubtitleMatcher.scoreSidecar()`: exact stem = 100, stem+tag = 80, episode conflict = −100 (hard disqualify), episode agreement = +50, token-overlap (Jaccard, junk-word filtered) ≤ +20, language hints ≤ +10, format ≤ +2. Normalized by `normalizedScore()` (÷100, clamped 0..1).
  - Gates: score ≥ 0.6 → trust sidecar, skip fingerprint job; ≥ 0.7 → persisted lock reusable.
  - `SyncFingerprint.scheduleSuspending()` reads the toggle and returns WITHOUT enqueueing when OFF; job re-checks the toggle at runtime; 90s initial delay + battery-not-low + exponential backoff.
  - "Resync now" = long-press on toggle (`PlayerActivity.onResyncNow()`).
- **Embed rule kept**: MKV → embedded track auto-selected; separate files → sidecar scored auto-pick.

## 3. Build & release system — READ CAREFULLY

- **Local machine has NO Java.** You cannot run Gradle locally. All builds happen in CI.
  - `android_build.yaml` — on push to main, builds debug APKs, uploads `debug-apks` artifact.
  - `publish_release.yaml` — **on tag push `v*`**, builds signed APKs and attaches them to the release automatically. So: **just push the tag; the APKs appear on the release by themselves** (~10 min). Do NOT manually `gh release create` before the workflow finishes — you'll race it (this bit us once; the release briefly showed no APKs).
- **Keystore**: only in CI secrets (`ANONRODE_KEYSTORE_BASE64` + aliases/passwords). NEVER commit it. Local backup: `C:\Users\Anon\Desktop\Anon\anonrode-keystore-backup\`.
- **`tools/` directory is the user's read-only Python workspace** (sync research scripts, Silero VAD `.onnx` models). NEVER commit, NEVER delete. Same for anything else untracked that you didn't create.
- Committing gotchas learned this session: kotlinx-serialization **plugin** needs the **runtime dep** in the same module; extension functions (`GlobalScope.launch`, `Context.playerSettingsDataStore`) can't be called fully-qualified / without receiver; Compose function-type params must be invoked positionally (no named args); Kotlin local functions must be declared before use; `EpisodePattern.find()` returns `Pair<Int?, Int>?` (not an object).

## 4. Open items / next steps

1. **On-device verification (NOT done yet — no device was available).** The whole v0.7 chain compiles and is wired, but nobody has pressed play on a real phone. Verify first:
   - Library opens instantly on second launch (disk snapshot).
   - Player opens fast; first frame lands quickly.
   - Sub sync: toggle ON → MKV with embedded subs → auto-pick; separate `.srt` (mismatched filename, e.g. series name differs) → scoring picks the right one; episode folder → wrong-episode sub never selected.
   - Toggle state survives: back out, play another video → still ON. Force-stop → still ON.
2. **Wi-Fi ADB test session** (planned, interrupted): pair via Developer options → Wireless debugging → "Pair device with pairing code" (`adb pair IP:PORT CODE`, then `adb connect IP:PORT`), then drive the app with `adb shell input`, verify via `adb exec-out screencap -p > frame.png` (Read the PNG), record with `adb shell screenrecord /sdcard/test.mp4` and pull to Desktop.
3. If sync still misbehaves on device: logcat filter `adb logcat -s SUB PLAYER` (tags used: `SUB`, `PLAYER`, `APP`, `POSTER`).
4. Known limitation (documented in V0.7_REPORT.md): fingerprint job decodes the whole file — up to ~10 min on long videos; runs in background, gated on battery-not-low.

## 5. File map (v0.7 core surfaces)

| Area | File |
|---|---|
| Scoring engine | `core/media/.../subtitle/SubtitleMatcher.kt` |
| Sidecar resolution | `core/media/.../subtitle/SubtitleSourceResolver.kt` |
| Fingerprint schedule | `core/media/.../sync/SyncFingerprint.kt`, `SyncFingerprintJob.kt` |
| Scanner | `core/media/.../library/MediaScanner.kt` |
| Settings field | `core/datastore/.../PlayerSettings.kt` (`subtitleAutoSyncEnabled`) |
| Toggle UI | `app/.../ui/PlayerSubSyncToggle.kt`, `PlayerScreenBottomBar.kt` |
| Action rail | `app/.../ui/PlayerScreenActionRail.kt` |
| Host wiring | `app/.../PlayerActivity.kt` (`onSetSubSyncEnabled`, `onResyncNow`, `subtitleAutoSyncEnabled` param) |
| Episode ordering cache | `feature/library/.../LibraryViewModel.kt` |

## 6. Uncommitted files (intentionally)

- `docs/draw_player_v1.py`, `docs/player_screen_v0_7.png` — local design sketches; user could not view them; do not commit unless asked.
- Everything in `tools/` — user's own files, hands off.
