# GitHub verification guide — the render-picture loop and the four pipelines

_This machine has no JDK / Android SDK (AGENTS rule 1), so nothing is built, tested, or rendered
locally. GitHub Actions is the build farm, and the dev loop is: **write → push → CI renders/builds →
download artifacts → read the pictures → log a verdict**. This document is the operating manual for
that loop: what each workflow does, how the PNGs are produced, how a round is judged, and how to read
results and failures. Keep it current: if a workflow changes, update this file in the same commit._

---

## 0. The loop in one block

```bash
export GH_TOKEN=$(cat ~/.anonrode_gh_token)
cd C:/Users/user/Anon/anonrode-player

git add -A && git commit -m "feat(scope): what changed"    # conventional commits, no AI attribution (rule 3)
git -c http.sslBackend=openssl push origin main            # rule 6: always this SSL backend on Windows

gh workflow run ui_render.yml --ref main                   # the picture farm (dispatch-only, on purpose)
sleep 5
gh run list --workflow ui_render.yml --limit 3             # grab the run id
gh run watch <run-id> --exit-status                        # blocks; exits non-zero on failure

SHA=$(git rev-parse HEAD)                                  # the artifact name carries the full 40-char sha
gh run download <run-id> -n "player-screens-$SHA" -D scratch/renders/${SHA:0:7}
ls scratch/renders/${SHA:0:7}/                             # one PNG per state
```

Then: read every PNG (side-by-side against its mockup frame where one exists), pixel-measure
anything geometric, and log PASS/FAIL + residuals in `docs/design/FINAL_DESIGN_HUB.md` §15.

---

## 1. The four workflows

| workflow | file | trigger | what it proves | artifacts |
|---|---|---|---|---|
| `android_build` | `.github/workflows/android_build.yaml` | every push to `main`, every PR | unit tests (`testDebugUnitTest`, incl. the sync-engine suite), ONNX VAD eval, debug APKs build | `unit-test-results`, `debug-apks` |
| `ui_render` | `.github/workflows/ui_render.yml` | **dispatch only** | the real Compose chrome renders — Robolectric + Roborazzi PNGs, one per state | `player-screens-<full-sha>`, `render-test-results-<full-sha>` |
| `sync_vad_eval` | `.github/workflows/sync_vad_eval.yaml` | push / PR / dispatch | the neural speech envelope still beats the energy one on continuous-mix content (the reason the live path can lock there) | — (log only) |
| `publish_release` | `.github/workflows/publish_release.yaml` | tag push matching `v*` | unit tests + VAD eval + `assembleReleaseWithDebugSigning assembleDebug` with the shared signing key, then a GitHub Release with the APKs | the Release itself |

Notes:

- `ui_render` is deliberately **not** wired into per-push CI: it is the on-demand screenshot farm,
  dispatched when there is a visual change to verify. Dispatch also pins each round's artifact to a
  known SHA. `cache-disabled: true` on that job is intentional — a fresh render every time, no stale
  cache can lie about a pixel.
- `android_build` also runs the VAD eval as a step (`python .github/scripts/sync_vad_eval.py`);
  `sync_vad_eval.yaml` is the standalone copy so engine changes show up as their own check.
- `android_build` and `publish_release` decode the shared signing keystore from repo secrets
  (`ANONRODE_KEYSTORE_BASE64`, `ANONRODE_KEY_ALIAS`, `ANONRODE_STORE_PASSWORD`,
  `ANONRODE_KEY_PASSWORD`) into `ANONRODE_KEYSTORE_PATH`, so APKs update in place over previous
  installs. Without the secret (e.g. a fork) the build falls back to a transient debug key.
- Artifacts expire (repo default retention, 90 days). The hub §15 verdict log is the durable record;
  artifacts are the evidence of the moment. Re-dispatch `ui_render` any time — it renders whatever
  the dispatched ref's head commit is.

---

## 2. How the pictures are made (Robolectric + Roborazzi)

- Harness: Robolectric **4.17** + Roborazzi **1.76.0** in the `:app` module.
- `@GraphicsMode(NATIVE)` — real Skia rendering, so Compose draws through the real pipeline (no
  software fallback).
- `@Config(sdk = [34], qualifiers = "w392dp-h850dp")` for phone portrait; the landscape state
  overrides with `w850dp-h392dp`.
- The states live in `app/src/test/java/dev/anonrode/player/ui/PlayerScreenRenderTest.kt` —
  **13 test functions as of v0.9.13, one `capture()` each**:

| state | what it proves |
|---|---|
| `portrait_chrome_on` | the canonical portrait dock (six controls: lock · ‹10s · hero · 10s› · FIT · ⛶; centered cluster; elapsed/left labels; ribbon resting at five discs; top row `title · tech`) |
| `portrait_sync_locked` | the locked sync state: pill + Thread after a real locked transition |
| `portrait_immersive` | immersive = fully black, chrome gone |
| `speed_card_open` | the speed card frame: readout, −/+ steppers, milestone set with filled current, rail + ticks, 0.25×/4.0× ends, Reset + steps caption |
| `subtitle_drawer_open` | the subtitle drawer: flat rows plus the inline APPEARANCE tray (position/size/colour/weight) |
| `settings_drawer_open` | the player-settings drawer over video |
| `playlist_drawer_open` | the playlist: flat rows, hue thumbs, tnum duration pills, drag handle |
| `settings_hub` | the settings hub: flat rows, inset hairline, 38dp discs, titleMedium headers |
| `settings_playback` | drill-in reached by a **real click-through** — proves the navigation, not just the pixels |
| `landscape_chrome_on` | landscape chrome (all eight transport controls incl. prev/next, inline title) |
| `library_home` | library home over the real `LibraryViewModel` + seeded store |
| `library_series` | series hub rows |
| `library_history` | the history surface (flat rows, percent column, per-row ✕, confirming CLEAR ALL) |

- Fixture discipline — renders run the **real composables and real view models**, never preview stubs:
  - `fixturePlayer()` — a real `ExoPlayer` via Media3 test utils under Robolectric (no media loaded).
  - `installAndProbeMedia()` — a fake `ContentProvider` registered for the `media` authority
    returning `fakeVideoRows()`, built from **real MediaStore constants**
    (`MediaStore.Video.Media.EXTERNAL_CONTENT_URI`, `_display_name`); the real scanner path then
    discovers them.
  - `seedWatchProgress()` writes through the real `MediaStateStore.updatePosition`, so library
    states prove the actual persistence wiring.
  - `libraryFactory()` builds the real `LibraryViewModel` over DataStore.
  - `@Before pinObsidianSkin()` pins the owner's skin so renders are comparable across rounds.
- `capture(name)` is `captureRoboImage("src/test/snapshots/$name.png")` — PNGs land under
  `app/src/test/snapshots/` on the runner. They are **never committed**: the CI artifact is the
  record.
- Resolution: the PNGs come out at 1 px = 1 dp (392×850 portrait, 850×392 landscape), so a measured
  pixel offset reads directly as a dp offset — this is how geometry verdicts are made instead of
  eyeballing.
- `awaitOrDump(expected, vm)` polls until a node with the given text exists and, on timeout, prints
  the whole semantics tree into the test log. Read that dump before guessing why a state timed out.

---

## 3. How to judge a round (what "checking the pictures" means)

One round = one dispatch → one artifact → one verdict entry. The rubric, in order:

1. **Structure** — every element of the target frame is present; nothing extra. A stale control is a
   FAIL and a deletion (rule 10), never "kept for continuity".
2. **Geometry** — positions and sizes measured in px (1:1 with dp). Eyeballing is not a verdict for
   anything measurable.
3. **Skin** — the pinned law (`docs/design/FINAL_DESIGN_HUB.md` §0): 0.96 near-black glass, zero
   blur, hairline white 8 % / 14 %, emerald `#3DDBA8` only as a state colour, tnum numerals.
4. **Type** — exact label text, no ellipsis, correct size / weight / tracking.
5. **State invariants** — the thing that state exists to prove (see the table above).
6. **Verdict** — PASS per state, or FAIL + each residual. Residuals become the next round's fix
   list. A section is DONE only when a render shows 1:1 — or a documented better-than-mockup delta
   (standing mandate: match `scratch/proposed_black_glass_player.html` 1:1, then EXCEED it).

Side-by-side composite — paste the render above its mockup frame, then one look judges both:

```python
from PIL import Image
render = Image.open("scratch/renders/<sha>/portrait_chrome_on.png")
frame  = Image.open("scratch/mockup/frame_a.png")          # a crop/screenshot of the target frame
w = max(render.width, frame.width)
canvas = Image.new("RGB", (w, render.height + frame.height + 8), (18, 18, 22))
canvas.paste(render, (0, 0))
canvas.paste(frame, (0, render.height + 8))
canvas.save("scratch/renders/<sha>/round_<n>_vs_A.png")
```

Pixel measure — e.g. the dock cluster's center on a bright-glyph row (portrait):

```python
from PIL import Image
im = Image.open("scratch/renders/<sha>/portrait_chrome_on.png").convert("L")
row = 425                                    # the transport row, y in px
cols = [x for x in range(im.width) if im.getpixel((x, row)) > 90]
print("left", cols[0], "right", cols[-1],
      "center", (cols[0] + cols[-1]) / 2, "canvas", im.width / 2)
```

Where the mockup frames live:

- Player chrome target: `scratch/proposed_black_glass_player.html` (frames A–H; its CSS is the
  pinned law).
- Whole-app board: `scratch/reimagined_feel_vibe_flow.html` (settings / library / history frames).

Verdict-log format in hub §15 (follow the existing rounds): round number + commit, artifact name +
run id, PASS list, FAIL list with the residual named for each, micro-deltas kept (marked as
deliberate), and what the next round converts.

---

## 4. Reading a run

```bash
gh run list --limit 5                      # recent runs, all workflows
gh run list --workflow ui_render.yml -L 3  # just the picture farm
gh run view <run-id>                       # step list + status + result
gh run view <run-id> --log-failed          # only the failing step's log
gh run view <run-id> --json headSha,conclusion,status   # confirm which SHA a run used
gh run rerun <run-id>                      # rerun everything; --failed for failed jobs only
```

- **Render timeout / failure** → download `render-test-results-<sha>` (JUnit XML + HTML report) and
  read the `awaitOrDump` semantics dump in the step log. Usual causes: an exact-wait string changed
  (note: `SectionHeader` UPPERCASES its text — wait on the normal-case row title, e.g. "Resume
  position"), a state needs a real click-through first, or the fixture produced zero rows.
- **Unit-test failure** → `android_build` deliberately dumps every `<failure` XML file into the log,
  so the failure is readable from the run page without downloading anything.
- **Green render run but no artifact** → the upload uses `if-no-files-found: ignore`. Check the test
  count in the log: a healthy run is 13/13 green (as of v0.9.13) with one PNG per `capture()`.
- **Empty API replies / `http:000`** → transient network flake on this machine; rerun the exact same
  command. Artifacts of old runs can be re-downloaded any time until they expire.

---

## 5. Releasing

```bash
# 1. bump app/build.gradle.kts lines 16-17 (versionCode + versionName)
# 2. commit + push main; watch android_build go green on that exact SHA
git add app/build.gradle.kts && git commit -m "chore(release): v0.9.14 (41)"
git -c http.sslBackend=openssl push origin main
gh run list --workflow android_build -L 2

# 3. annotated tag + push (tags/releases are GATED on the user's explicit go — rule 5)
git tag -a v0.9.14 -m "v0.9.14"
git -c http.sslBackend=openssl push origin v0.9.14

# 4. watch the release pipeline and verify the result
gh run list --workflow publish_release.yaml -L 1
gh run watch <run-id> --exit-status
gh release view v0.9.14 --json tagName,isDraft,assets \
  --jq '{tag: .tagName, draft: .isDraft, assets: (.assets | length)}'
```

Asset naming: `anonrode-player-<tag>-app-<abi>-<variant>.apk`. The matrix is 5 ABIs (arm64-v8a,
armeabi-v7a, universal, x86, x86_64) × 2 variants (debug, releaseWithDebugSigning) = **10 APKs per
release**. The `releaseWithDebugSigning` build is the sideload build: shared signing key, minified,
updates in place. Release notes are auto-generated (`generate_release_notes: true`).

---

## 6. Adding a new render state

```kotlin
@Test
fun my_new_state() {
    compose.setContent { Stage(false) { /* the real composable, real params */ } }
    capture("my_new_state")
}
```

- One test = one `capture()` = one PNG.
- **Modal surfaces live in their own window** (`ModalBottomSheet`, `Dialog`) and never land in a root
  screenshot — render the *content* composable directly, the way `library_history` renders
  `HistorySheetContent`.
- Real view models need real stores: build with `libraryFactory`, seed through the real store APIs
  (`MediaStateStore.updatePosition`), and never fake the thing the state exists to prove.
- A fixture `ContentProvider` must match on the **real MediaStore constants** (`_display_name`, not
  `"display_name"`): a mistyped constant means `name == null` for every row, the scanner skips them
  all, and the state renders empty while the build stays green.
- Skins are pinned globally in `@Before`; don't unpin per test unless the state is about skinning.
- When a state first renders, add it to the table in §2 and to the round's verdict entry in hub §15.

---

## 7. Troubleshooting quick table

| symptom | cause | fix |
|---|---|---|
| `git push` TLS / schannel error | Windows git SSL backend | `git -c http.sslBackend=openssl push origin <ref>` (rule 6) |
| `gh` returns empty / `http:000` | flaky network / TLS on this machine | rerun the exact same command; old artifacts stay downloadable |
| Render state times out | wait string changed / click-through needed / empty fixture | read the semantics dump in the log; fix the wait or the fixture |
| Green render run, no PNGs | capture path changed, or upload ignored an empty set | check test count and the `app/src/test/snapshots/` path in the log |
| `git push` rejected (non-fast-forward) | remote moved (another session pushed) | `git pull --rebase`, resolve, push again |
| Artifact name not found on download | the name carries the **full 40-char** dispatch SHA | use `-n "player-screens-$(git rev-parse HEAD)"` |
| Release has no APKs | tag pushed before tests passed, or keystore secret missing | read the `publish_release` log; never tag before `android_build` is green |

---

## 8. Standing rules for this loop

- **Push and `ui_render` dispatches are standing-approved** (AGENTS rule 5, amended 2026-10-15:
  "always push and render"). **Tags and releases remain gated** on the user's explicit approval.
- Conventional commits, no AI attribution, no competitor product names (rules 3 and 4).
- The VAD eval must stay green on every push. If it ever fails, that is a stop-the-line signal for
  the sync engine — read it, don't shrug at it.
- A visual section is DONE only when a render round shows it 1:1 (or a documented better-than-mockup
  delta). The user's phone recording remains the final feel-check on top of the pixels.
