# Overnight Audit Ledger

Living record of the full-app quality pass. One row per section, updated as
work lands. Nothing here is finished until it says so *and* CI agrees.

**Standing rules (from `.agents/AGENTS.md` — read before acting):**

1. Never defer a deliverable because a local build is impossible. No stubs,
   no "simplified for safety". CI is the gate; write the complete code.
2. No compromise on deliverables — full implementation, wiring and tests.
3. No AI attribution in commits. Conventional commits only.
4. Zero competitor product names anywhere. Library/algorithm attribution is
   fine; competing *product* names are not.
5. Ask before `git push`. Local commits are fine.
6. Keep the sync engine's validated behaviour intact — do not casually
   re-tune it.
7. **No subagents.** Read files directly.
8. No dead code. Zero call sites means delete it.
9. Every control must be functional — a flag nothing reads is a defect.

**Environment:** no JDK, Gradle or Android SDK on this machine. Every change
is verified by an executable model of the logic (Python/Node) plus a static
consistency check, and CI is the real gate.

**Standing rule for this session:** when an audit claim is not something I
have personally re-read in the source, it does not go in the report. Several
early claims turned out to be wrong on inspection and were dropped.

---

## Verification method that has actually worked

Reading code finds the *shape* of a bug; it does not tell you whether the
bug fires on real content. Every finding below was reproduced against the
real media library before it was called a finding:

- ffmpeg extracts real PCM from real episodes → executable port of the
  production logic → run against ground truth → measure.
- Ground truth is synthesised from an *independent* signal, never from the
  code under test, so the test cannot validate its own bug.
- Harness lives in `%LOCALAPPDATA%\cline\synctest` (not committed).

Three of my own proposed fixes were wrong and were caught this way before
shipping. That is the point of the method.

---

## Ledger

| # | Section | Defect | Status |
|---|---|---|---|
| 1 | Sync engine | Speech envelope collapsed; auto-sync could **never** lock (r=0.18–0.21 vs PEAK_MIN 0.30) | **DONE** `b3e911d` |
| 2 | Sync engine | `DriftTracker` returned the least-squares intercept at absolute media time instead of the latest measured offset; up to 3.29 s persisted error | **DONE** `b3e911d` |
| 3 | Sync engine | Anime cannot sync (continuous mix, subtitles on ~95% of the time) | **BY DESIGN** — not a bug, do not "fix" |
| 4 | Subtitles | Overlapping cues blanked the subtitle (31% of overlap instants in the worst file) | **DONE** `b43e5eb` |
| 5 | Subtitles | GB18030-first ladder swallowed every legacy CJK charset | **DONE** `b43e5eb` |
| 6 | Player chrome | Decoder chip showed a 2-state boolean over a 3-state engine; taps could no-op | **DONE** `27d3ba7` |
| 7 | Player chrome | `onVideoSizeChanged` destroyed explicit rotation locks | **DONE** `27d3ba7` |
| 8 | Player chrome | Ribbon scroll state destroyed by chrome auto-hide | **DONE** `27d3ba7` |
| 9 | Subtitles | `SubtitleParser` allocated ~6–8× file size: `stripTags` ran 8 regex/literal `replace`s per line, `splitLines` copied the whole file twice even with no CR present | **DONE** `01613a3` |
| 10 | Subtitles | `parse` returned cues in file order; the render loop binary-searches and assumed start-sorted input, so out-of-order cues silently never appeared | **DONE** `e044e05` |
| 11 | Subtitles | `Decoded.charset` / `decodeWithCharset` exist for a "loaded as Big5, tap to change" override — nothing calls them | **OPEN — feature gap, not a bug** |
| 12 | Player chrome | `isRebuildingDecoder` captured in a `remember` without being a key | **DONE** `27d3ba7` (fixed in the decoder refactor) |
| 13 | **Video pipeline** | Surface/decoder/aspect/seek path read end to end — sound. `PlayerView` uses a SurfaceView; default z-order puts it behind the window, so Compose overlays and the poster still composite correctly (the code comment's reasoning is wrong, the behaviour is right). | **REVIEWED, SOUND** |
| 14 | **Audio pipeline** | `VoiceClarityProcessor` "rumble high-pass" was a **low-pass**: flat +6 dB from DC to 1 kHz, −10.8 dB at 20 kHz. Amplified the rumble it claimed to clear, into a hard clamp | **DONE** `a4226a9` |
| 14b | **Audio pipeline** | `VolumeBoostProcessor` reviewed — buffer sizing, input consumption and clamping all correct | **REVIEWED, SOUND** |
| 14c | **Audio pipeline** | No `LoadControl` configured; Media3 defaults apply (50 s min/max buffer, 0 s back buffer) | **OPEN — needs on-device data** |
| 15 | Library / scanner | All five `scan()` call sites audited — `EpisodeQueue.build`, `fromExplicitUris`, `openVideo`, Settings rescan, `LibraryViewModel` — every one on `Dispatchers.IO`. Observer flow carries `flowOn(Dispatchers.IO)` and its `ContentObserver` only does a non-blocking `trySend`. Cache/TTL/dirty-flag/double-checked locking and the query-failure fallback are correct | **REVIEWED, SOUND** |
| 16 | Settings | No unclamped indices on restored prefs; the only IO call is the rescan, already on `Dispatchers.IO` | **REVIEWED, SOUND** |

---

## Claims checked and REJECTED

Kept so they are not re-raised:

- *"Render tick runs at 125 Hz."* `delayMs` has an 8 ms floor, but ordinary
  cue boundaries clamp to 100 ms. The floor is a floor, not the norm. Not a
  defect.
- *"The poster is drawn under the SurfaceView."* `PlayerView` does default to
  a SurfaceView here, but the default z-order places that surface BEHIND the
  window, which the window punches a hole for. Compose content in the window
  therefore still composites over the video. The comment in
  `PlayerScreenVideo.kt` reasons about it wrongly; the behaviour is correct.
  Not changed — a "fix" would have been a regression.
- *"VolumeBoostProcessor wastes a memcpy at unity gain."* True, but
  `isActive()` is consulted when Media3 builds the chain, and `gain` is a
  user-adjustable `@Volatile`. Gating the processor on it risks the chain not
  re-configuring, which would break the feature outright. Not worth one
  20 ms memcpy that cannot be tested here.
- *"Subtitle files are misdecoded on this library."* All 724 sidecar files
  here are valid UTF-8, which short-circuits ahead of the charset ladder. The
  charset bug is real but does not affect current content.
- *"Every control/section is broken."* Several areas were read end to end and
  are sound: surface lifecycle and `key(player)` handling, renderers-factory
  wiring for all three decoder profiles, build-time fallback that writes
  `decoderMode` back, seek-parameter usage, lifecycle release,
  `VolumeBoostProcessor`, `SubtitleParser` timestamp edge cases (hours > 24,
  missing hours, comma decimals, BOM, CRLF, lone CR, blank cue indices).

---

## Next

1. Video pipeline: decode configuration, surface, resize/aspect, frame
   pacing, seek behaviour. Read the real files, measure what can be measured.
2. Audio pipeline: processors, their allocation behaviour, the sync
   processor's cost now that the envelope is cheaper.
3. Row 9 — subtitle parser memory. Concrete, already scoped, no measurement
   needed to justify it.
4. Row 12 — the `remember` key staleness.
