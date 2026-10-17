# The subtitle subsystem — syncing & rendering

**Subsystem spec, extracted from the anonrode-player Android codebase (v0.9.13, tree of 2026-10-16).**

This document is written to be **self-contained**: it describes everything the app does with
subtitles — from finding a subtitle file to a pixel of text on screen — with the decisive constants,
the data contracts, the timing math, and an exact file map. It is deliberately scoped: acquisition →
parsing → cue lookup → auto-sync → rendering. Nothing about the app's other surfaces (library,
settings shell, player chrome) except where wiring directly affects subtitles.

**How to use this document (for a human or an AI assistant):** you can discuss it, review it,
critique the approach, or derive from it. Two ground rules before proposing changes: (1) the sync
engine's constants are **experimentally validated** against real media corpora — they were each
measured, and a "more natural" value is a regression until proven otherwise; (2) the engine is
**refuse-don't-guess** by design — every gate exists because a real mislock shipped without it.
The implementation is Kotlin (Jetpack Compose, Android, Media3/ExoPlayer) plus a small C++/JNI
layer. The primary real-world content is Chinese TV/movie releases with sidecar subtitles in
GBK-encoded SRT/ASS files — that context explains several choices below.

**Notation** used throughout: `tRaw` = the player's raw position in seconds; `offset` (β) = the
subtitle offset in seconds; `speed`/`alpha` (α) = the timeline scale factor (≈1.0; ≠1 when subtitle
timing was made for a different framerate); `cue` = one timed subtitle entry.

---

## 1. System map

Two pipelines meet at the cue list. Playback reads cues for rendering; auto-sync aligns cue timing
to the audio.

```
ACQUISITION                        PARSING                     RENDERING (every 8–100 ms tick)
embedded track ─┐                                             ┌─ position tRaw
sidecar file   ─┼─► bytes ─► charset detect ─► format parse ─►│─ t = (tRaw − offset) / speed
downloaded file ─┘                                 │          │─ CueLookup.find(cues, t)
                                                   ▼          │─ join lines ─► Compose overlay
                                          List<SubtitleCue>   └─ wake at cue boundary
                                                   │
SYNC (offset sources)                              └──────► cues also feed the engine:
  background: full-file fingerprint job (WorkManager, audio onsets + cue times → α, β)
  live: audio sink analyzer (Media3 AudioProcessor, VAD/energy envelope vs cue cadence)
  manual: resync / calibration / offset trim — all write the same persisted lock
```

Offsets from all sources are **persisted per video** (Room) and resolved at playback into a single
`(alpha, beta)` — or a **piecewise** list when the file has commercial/recap cuts, where beta
changes at cut points.

---

## 2. Data model

```kotlin
// core/model
data class SubtitleCue(
    val start: Double,          // seconds, subtitle timeline
    val end: Double,
    val lines: List<String>,    // display lines, markup already stripped
)
```

Persisted per video (in the media state row):

- **subtitleChoice** — which source is active. Grammar: `""` = AUTO, `"none"` = off,
  `"embedded:<int>"` = in-container text track, `"sidecar:<name>"` = sibling file,
  `"online:<name>"` = a downloaded file. A persisted choice of any kind always wins over AUTO.
- **the lock** — `autoSyncOffsetMs`, `autoSyncSpeedFactor`, `autoSyncPiecewise`
  (serialized `"startSec:betaSec;startSec:betaSec;…"`), plus a checked-at timestamp.

---

## 3. Acquisition — where cues come from

One resolver defines "the selected subtitle" for **both** playback and the background sync job, so
the two can never disagree about which file a lock was fitted to.

### 3.1 Embedded tracks (in-container)

`EmbeddedSubtitleExtractor` enumerates text tracks with `MediaExtractor` (mime-based: SRT, WebVTT,
ASS, tx3g). `MediaExtractor` exposes no per-sample duration for text tracks, so cue ends are derived
(a cue runs until the next sample; a tail rule for the last one). Sample payloads are parsed per
format (`srt`/`vtt`/`ass`/`tx3g` line decoders).

**AUTO preference:** when no choice is persisted and the container has any text-sub track, the
first embedded track is preferred over sidecars — embedded timing is already container-aligned.
But embedded tracks are **not** auto-fingerprinted (that would burn CPU proving a container truth);
the sync job only runs against embedded cues when explicitly enabled for them.

### 3.2 Sidecar files (sibling files next to the video)

- Scanned extensions: `srt, vtt, ass, ssa, sub, tmp, ttml`. Files over **32 MB** are refused.
- Discovery ladder (Android storage reality): (1) list the parent directory directly on the
  filesystem (the app holds all-files access; fastest and complete) → (2) `MediaStore.Files` query
  (legacy / no all-files access) → (3) a SAF tree the user designates once in Settings
  ("Subtitles folder access"). None of the paths may ever throw upward: a resolver failure simply
  means "no sidecars".
- **Auto-pick** uses `SubtitleMatcher`, a name-only score (never reads candidate files during the
  pick), higher = better:

| score | rule |
|---|---|
| 100 | exact stem match (`Show.S01E03.mkv` ↔ `Show.S01E03.srt`) |
| 80 | one stem is the other plus a tag at a dot boundary (`Show.S01E03.eng.srt`) |
| −100 | **episode conflict** — both names carry season/episode markers and they disagree; hard disqualification (prevents episode N+1's subtitle out-scoring on shared series tokens) |
| +50 | episode agreement (both sides numbered, same S+E) |
| +≤20 | token overlap (IoU over tokens, junk tokens removed: codec/release tags — `1080p`, `x265`, `webrip`, `yify`, … — and pure-digit tokens) |
| +≤10 | language hint (weighted keyword table; `english/eng/en` highest) |
| +2 / +1 | format preference (`srt` / `vtt`, `ass`) |
| −10 | episode-marked video with an unnumbered candidate: last-resort fallback, kept so a lone `subtitle.srt` in a single-sub folder is still usable |

- Normalized to 0..1 (divide by 100). Two thresholds in the UX: **≥ 0.6** = sidecar good enough to
  skip scheduling a fingerprint; **≥ 0.7** = a persisted lock for this sidecar may be reused on
  next open. A weak match (0.0..0.1) is usable only as a single-sub-folder fallback.

### 3.3 Downloaded subtitles

An online search client exists (`OpenSubtitlesClient`, legacy XML-RPC, **experimental**): blocking
calls on IO threads, 8 s connect / 15 s read timeouts, nothing ever throws to the UI, and once the
service definitively refuses the session the client **latches** off (later calls short-circuit
locally). Downloads land in `SubtitleDownloadStore` (per-video file list). Online results are just
another sidecar-shaped source to the rest of the system.

---

## 4. Decoding & parsing

### 4.1 Charset detection (`SubtitleDecoder`)

Real-world CJK releases ship GBK/Big5/UTF-16 files; a UTF-8-only reader shows mojibake. Ladder,
first hit wins, nothing throws:

1. **BOM** — UTF-8, UTF-32LE/BE, UTF-16LE/BE (authoritative; UTF-32LE checked before UTF-16LE —
   its BOM is a superset).
2. **BOM-less UTF-16** — null-byte parity sniff over the first 512 bytes (Windows exports).
3. **Strict UTF-8** decode (malformed bytes rejected, not replaced) — a clean pass is UTF-8.
4. **Scored legacy CJK**: GB18030, Big5, EUC-KR, Shift_JIS — each decoded on a 64 KB sample and
   ranked by `legacyScore`. The discriminator: a **correct** decode is overwhelmingly one script
   (measured 68–92 %), a **wrong** one scatters (nearest wrong cases: 48 % hangul, 29 % kana).
   Score: ≥ 60 % kana → Japanese; ≥ 60 % hangul → Korean; else rank on hanzi count (rescues Big5);
   a decode with ≥ 2 % replacement characters scores 0. Highest score wins (strict `>` keeps the
   ladder order as tiebreak). NB: an early version returned the **first** candidate that "looked
   like CJK" — GB18030 (a superset) won every time and 3 of 4 legacy charsets were misdetected;
   the scorer replaced that.
5. **windows-1252** catch-all (ISO-8859-1 if absent) — single-byte, can never fail.

`decodeWithCharset` also returns the chosen charset name (the UI can surface it / allow override).

### 4.2 Format parsing (`SubtitleParser`)

Supported: **SRT, WebVTT, ASS/SSA, MicroDVD (.sub), MPL2 (.mpl), TMP (.tmp), SubViewer (.sub),
TTML/DFXP (.ttml/.dfxp/.xml)**. `.sub` is sniffed (MicroDVD `{start}{end}` pattern → frame-based;
else SubViewer timing lines; else SRT). MicroDVD assumes 25 fps when frame-based; MPL2 dispatched
at 10 units/s (tenths).

Hardening rules (each exists because real files violated the naive version):

- BOM stripped, CRLF/lone-CR normalized to LF before parsing; the CR pass is skipped when no `\r`
  exists (avoids 2 extra full-file copies on multi-MB files).
- Timestamps tolerate missing milliseconds, comma or dot decimals, missing hours (VTT `MM:SS.mmm`),
  and hours > 24. A malformed timestamp drops **that cue only** — never aborts the file.
- ASS override blocks `{…}`, unclosed `{` runs, stray `}` and `\N`/`\n` hard breaks are handled in
  one pass per line; HTML/XML tags and entities (`&amp; &lt; &gt; &nbsp; &quot; &#39;`) stripped in
  a second pass. A `<` with no `>` stays literal.
- A timing line always terminates the previous cue's text (files with missing blank-line separators
  still parse); cue-index lines and VTT `NOTE/STYLE` headers are skipped.
- **The output contract: cues are returned sorted by `start`** (stable). ASS `[Events]` need not be
  chronological, and the render loop binary-searches the list — sorting at the producer makes the
  guarantee a property of the type instead of something each caller must remember.
- `lastEndMs(cues)` = last end time, used as a duration proxy for matcher scoring.

---

## 5. Cue lookup — the render tick's search

`CueLookup.find(cues, t)` finds the cue showing at time `t` on a start-sorted list:

1. **Upper-bound binary search on `start`** — first index whose start is after `t`.
2. **Backward walk** from there (max `MAX_OVERLAP = 32`) returning the first cue with
   `start ≤ t ≤ end`.

Why the walk: the obvious search discards half the range whenever `t` falls past `cues[mid].end`,
silently assuming **non-overlapping cues** — but ASS sign/song cues, two-speaker lines, and
bilingual tracks overlap routinely. Measured over 60 real subtitle files from the local library,
**17 contained overlapping cues**; under the naive assumption the lookup returned null while a cue
was demonstrably on screen for **31 % of overlap instants** in one file and 13 % in another — and
because the render tick retries on a timer, the subtitle is missing for the *whole* overlap, not one
frame. Ends are not monotonic either, so "last cue that started before `t`" is insufficient — hence
the walk. Watch out: the walk may only ever return a cue that genuinely covers `t`; an over-deep
overlap degrades to a **missing** subtitle, never a **wrong** one. (Deepest walk any real file
needed: 1; 32 is a wide, flat-cost margin.)

---

## 6. The render tick & offset application

The player runs a handler-driven tick (`renderTick` in `PlayerActivity`) that recomputes the
visible cue and schedules its next wake. Core loop, once per tick:

```kotlin
val tRaw = positionMs / 1000.0
val spd  = engine.subtitleSpeedFactor            // alpha, ≥ 0.5 clamped
// scalar lock → one beta; piecewise lock → beta of the active segment
val targetOffsetSec = piecewise.lastOrNull { it.start <= tRaw }?.beta
                      ?: engine.subtitleOffsetMs / 1000.0
// …offset smoothing (below)…
val t = (tRaw - appliedOffsetSec) / spd          // subtitle timeline
val cue = CueLookup.find(cues, t)
cueText = cue?.lines?.joinToString("\n")
```

**Offset smoothing** — text must never jitter or jump while being read:

- Offset adaptation happens **only during silence gaps** (`cueText == null`).
- Small differences are **micro-slewed: at most 50 ms per tick** toward the target.
- A jump > 1.2 s during a gap = commercial cut / seek → **snap** to the target.
- A jump > 2.5 s while text is displayed (scrub) → snap.
- The tick mirrors the **actually applied** offset (incl. active piecewise beta) into state for the
  UI's "synced" indicator — never the raw engine scalar.

**Boundary-aware scheduling** — instead of a fixed poll, the tick computes its next wake as the
distance to the *next event* (current cue's end, or next cue's start; A-B repeat's B is also a wake
point), mapped back onto the raw position timeline: `delay = (boundary·spd + offset − tRaw) /
playbackSpeed`, wall-clock, clamped to **8–100 ms**. Touching cues can't induce a spin below 8 ms;
an eventless stretch sleeps at most 100 ms. (Mapping back through `spd`/`offset` means drift
correction doesn't skew the delay.)

**Source changes clear the lock.** A lock was fitted to the *previous* source's timing; on a new
choice it is cleared, then the video reloads (restoring position), re-resolves cues, and
re-schedules the fingerprint job so the new source earns its own lock.

---

## 7. Overlay rendering (Compose)

`PlayerSubtitleOverlay` — the cue is drawn at a **percentage position** (not chrome-relative):

- Vertical fraction `subY ∈ [0.10, 0.85]` of screen height, default **0.78**; horizontally locked
  to exact screen center (0.5).
- **Draggable**: drag moves Y only (haptic tick at drag start, cue scales to 1.04 and 0.95 alpha
  while dragging), clamped to the range; on release the position persists **per video** and
  globally. PiP mode: static, no drag, fixed 10.5 sp size, tighter padding.
- **Long-press** opens an in-place style dropdown (size / position / color / reset) right on the
  cue, so tuning never covers the frame; it mutates the same host-owned style object as the drawer
  tray. Reset restores defaults and the saved position.

`OutlinedSubtitleText` — the look:

- **8-way black outline** — eight copies of the text at ±2 dp offsets in 95 %-black beneath the fill
  text; **no background box**.
- Max **3 lines**, soft-wrapped, centered, line height 1.35× font size; overflow visible.
- Style envelope (persisted): size ∈ {S=14, M=18, L=22, XL=26 sp}, color ∈ {White, Yellow
  `#FFD75E`, Green `#7CE487`, Cyan `#7AD8E8`}, weight (bold default), position preset
  (Low/Mid/High/Top — applied through the same vertical fraction).

Style surfaces (all mutate/persist the same envelope): the player drawer's inline **APPEARANCE**
tray (Position / Size / Colour / Weight rows), the bottom style sheet, and the on-cue long-press
dropdown.

---

## 8. The sync engine (auto-sync)

### 8.0 Model and laws

The engine fits **audio_time = α · subtitle_time + β** (α near 1.0, β in seconds), or a
**two-segment cut model** (β changes at a detected cut — commercials/recaps removed from the video;
`cut_len = β_before − β_after` seconds of audio gone), or a full **piecewise** map for heavily cut
files. Playback inverts it as `t = (tRaw − β) / α`.

Non-negotiable laws (each earned by a real failure):

1. **Full-file FFT correlation is the senior authority.** A live 15 s-window pass must NEVER
   overwrite a verified full-file lock with a discrepancy > **1.0 s** (periodic dialogue cadences
   create harmonic aliases that make the short window confidently wrong).
2. **No deadlock on unsynced files.** A guard may never block the background pass for a file
   without a valid lock (auto-sync MUST run when no valid lock exists).
3. **User resync is absolute.** "Resync now" / calibration = an explicit forced background pass
   (`force = true`), not merely re-arming the passive loop.
4. **Cue-guided anchor windows.** Never blindly decode 600 s of audio from t=0. Use the subtitle
   cues to seek the first dense dialogue cluster and decode a **60–90 s** window to lock in < 1 s.
5. **Refuse don't guess.** Every emitted lock passes confidence gates; a weak or ambiguous match
   is discarded and original timing stays untouched.
6. **Validated constants are not re-tuned casually** (see preamble).

### 8.1 Background path — the full-file fingerprint (`SyncFingerprintJob`, WorkManager)

Pipeline:

1. Resolve the video's real file path from its content URI.
2. Resolve the subtitle source **exactly as playback does** (same resolver, same parser — audit
   finding #23: a lock must never be fitted to a different file than the rendered one).
3. Parse cues; pick the analysis window via **cue guidance** (`findDensestDialogueCluster`): scan
   cue starts for a **20 s span with ~12–15 dialogue cues** within the first 5–8 minutes;
   intro/cold-open handling skips an initial music/silence gap ≥ 35 s (title themes); junk cues are
   filtered (credits lines, song marks `♪`, bracketed sign text, subtitle-credit lines).
4. Extract speech onsets for the window (below) — with a **hybrid anchor**: silencedetect onsets
   ∪ VAD onsets, both gated, so stage-0 works on continuous-mix content, not only on clean
   dialogue.
5. Fit: the tier-1 **continuous soft-envelope 2D Pearson correlator** (`SpeechCorrelator`) first;
   then the discrete-onset fitters and cut machinery (`SyncOrchestrator.resolveModel` → cut model
   via `IntervalAligner`/`CutEnsemble`, else `SyncBest`/`SyncFinder` singles). Gates (any tier):

   - **Job/worker gates:** ≥ 20 onsets, recall ≥ **0.35**, margin ≥ **0.04**.
   - **Correlator gates (continuous envelope):** peak height ≥ **0.18**, prominence ≥ **0.08**,
     z-score of the peak ≥ ramp **5.5 → 4.5** (as bin count rises past the eligibility line of
     **160** bins; ~280 bins for the low end), pair exclusion ≤ **35** bins, containment
     ≥ **0.40** at its page, 100 ms alignment bins.
   - **Discrete fitter gates (`SyncFinder`):** ≥ 20 onsets / ≥ 10 cue starts, recall ≥ **0.25**,
     containment ≥ **0.18** at 0.5 s window, margin ≥ **0.04**, cross-half consistency, LSQ refit
     on matched pairs.
   - **`SyncBest` acquisition:** short-circuit on a confident plain lock (margin ≥ 0.06 + trimmed
     peak check); else wide acquisition β ∈ [−**600**, +**600**] with a length-ratio α seed, coarse
     histogram bin 0.5 s, guard band 1.0 s, trimmed score drops worst **40 %** of cues, then a
     6-phase outlier-robust refinement; final fallback = gated wide, then gated plain, **else
     null**.
6. Persist the surviving lock (α, β, piecewise) to Room.

Cut detection (`CutEnsemble`) runs **two independent detectors** — a robust line-fit (RANSAC/Hough
style) and a spectral-modes + dynamic-programming detector. Both no-cut → high-confidence no-cut.
Both cut and agreeing (cut position within **40 s**, post-cut β within **10 s**) → evidence-weighted
blend + joint covariance refit, confidence "high". Disagreement or single-method → arbitrate with a
common density-normalized objective and accept the winner only if its gain over the no-cut baseline
exceeds the split penalty — **else refuse**. `IntervalAligner` additionally detects a two-segment
split directly for the orchestrator.

**Resumability & churn fix:** the job is enqueued as unique work per video. A forced re-arm
("resync now") **replaces**; an automatic re-arm uses **KEEP** so an in-flight, resumable
extraction is never cancelled ~instantly by the next schedule pass (a real defect: automatic
re-arms were REPLACE-ing and killing in-flight extractions, causing repeated 20-minute churn). A
process-wide **decoder gate** (semaphore = 1) serializes whole-file decodes so concurrent workers
can't starve each other with parallel MediaCodec/ONNX passes; a busy gate returns
`Result.retry()`, never blocks a thread.

### 8.2 Live path — the audio sink analyzer (`AudioSyncProcessor`)

Injected into Media3's audio pipeline as an `AudioProcessor`, so it sees exactly the PCM being
played. It is **passthrough** (never alters audio) and allocation-free on the render thread.

- **Speech envelope**: neural VAD (`LiveVad` → `SileroVad`, in-process ONNX, 1.26 MB model) runs on
  a dedicated low-priority worker — the render thread only copies PCM into a preallocated buffer
  (no math, no allocation, no inference; a render-thread stutter is strictly worse than no sync).
  The worker publishes a **100 ms probability envelope**. When the model is unavailable the caller
  falls back to the **min-statistics noise-floor + long-horizon energy envelope** (constants
  `NOISE_DECAY=0.9995`, `NOISE_RISE=0.005`, `HI_DECAY=0.9998`, `NOISE_GUARD=2.0`, `SPAN_HI=0.7`) —
  evidence log in `tools/_sync_fallback_study.md`. The previous AGC floor/peak envelope pinned music
  into mid-range scores and could not lock real dramas; this replaced it. The neural path is an
  upgrade, never a dependency.
- **Window**: 100 ms bins in a sliding array (`BIN_WINDOW` ≈ 9,800 bins ≈ 16 min) that slides
  forward once playback passes its end — live sync keeps working deep into a resume, instead of
  dying at a fixed cap.
- **Schedule**: correlator evaluations on the `SpeechCorrelator.PASS_BINS` schedule (starts at the
  160-bin eligibility line); regression-tested (`SyncScheduleRegressionTest` asserts the exact
  schedule because a collapsed attempt budget was invisible to black-box observation).
- **Tracking mode** (already locked, watching for drift): evaluation cadence every **25 s**,
  confirmation every **6 s**, local leashing radius **1.5 s** — a tracking update may only move the
  offset inside the leash, so routine checks cannot teleport the picture.
- **Tracking philosophy / authority**: a live lock is granted only through the correlator gates
  (§8.1); once granted, live updates are confined by law #1 — never stepping a full-file lock by
  > 1.0 s.
- **Off switch**: `enabled=false` (mirroring the user's auto-sync preference) makes `setCues`
  refuse to re-arm, `evaluate` refuse to publish, and bin accumulation refuse to schedule — a
  single volatile read at decision points, never per sample.
- The analyzer reports through a listener: `onSyncLocked(offset, speed)`,
  `onSyncTrackingUpdate(position, offset, speed, isPiecewiseJump)`, `onSyncNoMatch()`.

### 8.3 Native layer (`libanonsync.so`, C++/NDK, JNI)

`NativeSyncEngine` bridges to in-process native code: NEON-vectorized band-pass filtering,
silencedetect-style onset extraction, and multi-scale cross-correlation (`alignPcm(pcm, rate,
channels, isFloat, cueStarts, cueEnds, …)`). Why native: **no CLI processes on Android** —
`ProcessBuilder`/exec of bundled binaries is denied by SELinux ($W⊕X) on API 29+, so all signal
crunching is in-process; and the NEON paths keep whole-file passes inside a phone's budget.

### 8.4 Manual adjustment

Beside auto-sync the UI offers manual trim (finer nudges) and calibration; those write the same
persisted lock fields, so every consumer (render tick, fingerprint schedule, reuse rules) sees one
source of truth. Calibration triggers a **forced** pass per law #3.

### 8.5 Validation

- **1:1 Python replica** of the engine (`tools/_sync_replica_eval.py`, 543 lines) runs a synthetic
  suite + a real film corpus; the Kotlin ports carry the same constants and are validated against
  the replica (`tools/engine_test_v2.py` lineage — the fitter headers record real validation
  numbers, e.g. three consecutive real episodes locking at α≈1.0089–1.0094, |β| < 0.31 s, recall
  56–70 %).
- **CI:** `sync_vad_eval` proves the neural envelope beats the energy envelope on continuous-mix
  content (the reason the live path can lock there at all); `android_build` runs it plus the JVM
  unit suites on every push, and the release pipeline will not cut a release with a failing engine
  test.
- **Unit suites:** `SpeechCorrelatorTest`, `SyncEnvelopeTest`, `SyncScheduleRegressionTest`,
  `IntervalAlignerTest`, `DriftTrackerTest`, `OnsetCacheTest` (six files, ~1,015 lines) plus
  subtitle-side tests (`CueLookupTest`, `SubtitleDecoderTest`, `SubtitleMatcherTest`).

---

## 9. Integration & UX touchpoints (subtitle-related only)

| surface | behavior |
|---|---|
| sync pill / status | running / locked / no-match states; a claimed lock fires a single haptic on a real false→true transition |
| "synced" indicator | mirrors the **actually applied** offset (piecewise-aware), not the engine scalar |
| subtitle drawer | source picker (embedded / sidecar / online / none) + inline APPEARANCE tray |
| style tray / dropdown | size, position, colour, weight — one persisted `SubtitleStyle` envelope |
| auto-sync toggle | mirrored into `AudioSyncProcessor.enabled` at runtime |
| resync / calibration | forced background pass (`force = true`) per law #3 |
| position drag | per-video + global persistence of the cue's vertical fraction |
| source switch | clears the lock (fitted to the previous source), reload, re-schedule |

---

## 10. File map (v0.9.13)

### Subtitle package — `core/media/src/main/java/dev/anonrode/player/core/media/subtitle/` (2,423 lines)

| file | lines | role |
|---|---|---|
| `SubtitleSourceResolver.kt` | 473 | choice grammar, AUTO/embedded/sidecar/online resolution, discovery ladder |
| `SubtitleParser.kt` | 437 | all text formats, hardening, sorted-output contract |
| `OpenSubtitlesClient.kt` | 445 | experimental online search (latched fail-fast) |
| `EmbeddedSubtitleExtractor.kt` | 303 | MediaExtractor text-track enumeration + cue extraction |
| `SubtitleMatcher.kt` | 235 | name-only sidecar scoring, episode-conflict veto |
| `SubtitleDecoder.kt` | 220 | charset detection ladder |
| `SubtitleDownloadStore.kt` | 139 | downloaded-file store per video |
| `CueLookup.kt` | 54 | overlap-aware cue search |
| `SubtitleTreeStore.kt` | 43 | SAF tree persistence for sidecar access |
| `OsHash.kt` | 74 | file-hash helper for provider search |

### Sync engine — `core/media/src/main/java/dev/anonrode/player/core/media/sync/` (8,701 lines)

| file | lines | role |
|---|---|---|
| `CutEnsemble.kt` | 1,517 | two-detector cut ensemble + arbitration |
| `AudioSyncProcessor.kt` | 940 | live in-sink analyzer (envelope, tracking, gates) |
| `SyncFingerprintJob.kt` | 934 | background fingerprint worker (cue-guided windows, resumable) |
| `OnsetExtractor.kt` | 918 | onset extraction (silencedetect ∪ VAD passes) |
| `SyncBest.kt` | 895 | consolidated single-segment fitter (phases 0–4) |
| `SyncFinder.kt` | 638 | joint (α, β) coarse→fine search + LSQ refit |
| `IntervalAligner.kt` | 627 | piecewise/cut interval alignment |
| `SpeechCorrelator.kt` | 565 | continuous-envelope correlator + gates/schedule |
| `SileroVad.kt` | 385 | ONNX VAD inference |
| `LiveVad.kt` | 268 | worker-thread live VAD envelope for the sink |
| `NativeSyncEngine.kt` | 200 | JNI bridge to libanonsync |
| `SyncOrchestrator.kt` | 184 | tier selection + refuse-don't-guess |
| `OnsetCache.kt` | 171 | per-file cached onset passes, merge helpers |
| `SyncFingerprint.kt` | 138 | schedule/enqueue (unique work, KEEP vs REPLACE) |
| `DriftTracker.kt` | 119 | long-horizon drift tracking for tracking mode |
| `SyncAnalysisWorker.kt` | 104 | background evaluation worker for the live path |
| `PlaybackClock.kt` | 64 | position source feeding the analyzer |
| `SpeechDetector.kt` | 34 | speech-presence interface |

### Native — `core/media/src/main/cpp/` (998 lines)

`anonsync_jni.cpp` 322 · `correlator.cpp` 194 · `biquad_filter.cpp` 151 · `silencedetect.cpp` 126 ·
headers 185 · `CMakeLists.txt` 20. The VAD model asset: `silero_vad.onnx` (1.26 MB).

### Playback/render wiring (in the app module)

`PlayerActivity.kt` — render tick, offset smoothing + piecewise resolution, cue text, source-choice
handling. `ui/PlayerScreenSubtitles.kt` (190) — overlay + outlined text. `ui/PlayerScreen.kt` —
overlay call site. `audio/SubtitleStyleSheet.kt` (218, incl. the style model), `SubtitlePickerSheet.kt`
(472), `ui/PlayerSubtitleAppearance.kt` (97) — style/appearance surfaces.

---

## 11. Invariants & known traps

- **Sorted cues are a contract.** `CueLookup` binary-searches; producers sort, consumers assume it.
- **Seconds vs milliseconds.** Cue times are `Double` seconds; persisted offsets are ms; the UI
  formatting layer takes ms. Mismatches here have shipped real bugs (a displayed `-0:00`).
- **The walk-back predicate is the safety property** in `CueLookup`: `start ≤ t ≤ end` — degrade to
  missing, never wrong.
- **Offset may only move in silence** (or on ≥ 1.2/2.5 s discontinuities) — visible text must not
  jitter, and micro-slew steps are ≤ 50 ms.
- **A source switch invalidates the lock.** Locks are per source-timing; reuse thresholds (0.6 /
  0.7) exist precisely to avoid re-fingerprinting a confident sidecar.
- **Anchor windows are cue-guided**, never t=0 (law #4), and the anchor uses the silencedetect ∪
  VAD hybrid so continuous-mix content still anchors.
- **The live path never outranks the full-file lock** (> 1.0 s rule), the tracking leash confines
  routine updates (1.5 s), and the disable flag is honored at every decision point.
- **No subprocess execution on Android** — all signal work is in-process JNI/NEON.
- **Cut vs. no-cut arbitration may refuse.** A null result means "no trustworthy model", and the
  system's response is to leave the original timing alone.
- **ONNX threading:** `numpy<2` pin exists in CI because onnxruntime wheels are built against the
  NumPy 1.x ABI (a failed import, not a runtime quirk).

---

## 12. Glossary

- **Onset** — a moment speech begins (energy/neural detector output), used as an alignment anchor.
- **Envelope** — a per-100 ms signal (probability for VAD, energy for fallback) over time; the
  correlator's subject.
- **α (alpha)** — timeline scale between subtitle and audio (framerate mismatch ≈ 1.043 for
  23.976→25 fps).
- **β (beta)** — the offset to apply, seconds; piecewise = per-segment β across cuts.
- **Recall** — fraction of cues landing near an onset; **containment** — fraction of onsets landing
  near a cue; **margin** — recall minus the best runner-up > 2 s away.
- **Leash** — the ± bound within which tracking mode may move an existing offset (1.5 s).
- **Refuse-don't-guess** — the engine's standing bias: no lock beats a wrong lock.

---

*End of document. Everything above was assembled from the v0.9.13 tree (commit `3739d27`); line
counts refer to that snapshot. For deeper detail the code itself is the ground truth — this
document is the map.*
