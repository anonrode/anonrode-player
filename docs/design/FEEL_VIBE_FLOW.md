# ANONRODE — FEEL · VIBE · FLOW

_Design-language v2, expansion across the whole app — **proposal for sign-off** (2026-10-15)._
_Authority chain: this document **extends** `FINAL_DESIGN_HUB.md` §0 (pinned, untouched). The converged player (rounds 0–3.1) is evidence, not a target — it stays exactly as signed off. Nothing here is implemented until the user approves (AGENTS rules 15/16). Impeccable run context: PRODUCT.md (android), new-work flow, established-world mode._

---

## 0. The soul, and the thesis

**Subtitle sync is the life of the app** (user's words, standing). Everything below grows from that one sentence.

Thesis: **Time, kept.** Anonrode is the player that makes the right words arrive at the right moment, provably. So the design language is a *timekeeping* language: hairlines that measure, numerals that are jewelry, motion that behaves like a well-built mechanism, and one recurring signature — **the Thread**, a 1-line pulse that appears wherever the engine is listening, locking, or holding time.

What this refuses: the generic dark-player look. No neon glow, no gradient hero, no animation for its own sake. The film is the only bright thing; the app is the instrument around it.

---

## 1. VIBE — the identity system

### 1.1 Color (extends §0; accent stays state-only)

| Token | Value | Role |
|---|---|---|
| `canvas` | `#000000` | The stage (player). Video owns it. |
| `ground` | `#0A0A0A` | App canvas (library/settings) — the room dims, not glows |
| `glass` | `rgba(10,10,12,.96)` | Panels, drawers, pills |
| `glassRaised` | `rgba(17,17,19,.94)` | Floating transients only (popovers, cards) |
| `hair` / `hairStrong` | white 8% / 14% | Structure and chip borders |
| `fg` / `dim` / `dim2` | `#F8FAFC` / `#ABABB2` / `#78787F` | Text hierarchy |
| `accent` | `#3DDBA8` | **State only**: selected, active, played, lock |
| `accentDim` / `accentGlow` | 16% / 18% | Rings' halos, knob glows |
| `thread` | accent @ 45%, 1dp line | The Thread motif (below) |
| `amber` | `#F5B84B` | A-B points, pins — never anything else |
| `red` | `#EF4444` | Destructive only |

Rules: one filled accent per view; status hues never collide with accent; never a second accent hue; scrims stay pinned (drawer 42%, top 55%, dock 62→72%).

### 1.2 Type — one scale, numerals as jewelry

System face (Roboto) everywhere; the legacy monospace numerals retire app-wide (player already dropped them). Type maps to roles, never per-screen sizes (impeccable android ref). `tnum` on **every** changing number.

| Role | Size/line | Weight | Use |
|---|---|---|---|
| Display | 28/34 | 600 | Series hero title |
| Headline | 22/28 | 600 | "Continue watching" moment, section heroes |
| Title-lg | 17/22 | 600 | Screen titles, drawer headers |
| Title | 15/20 | 600 | Row titles, series names |
| Body | 13/18 | 400 | Default text, subs inside rows |
| Label | 11/14 | 500 | Chips, buttons, values |
| Micro | 9.5/12 +0.8 tracking | 500, caps | Section heads |
| Status | 9/12 +0.5 | 500 | `elapsed` / `left` / under-times |

Numerals earn weight: offsets, positions, durations, percentages = 500–600 tnum. The sync offset is the most honored number in the app.

### 1.3 Shape, elevation, structure

- Radii concentric: pill 999 · sheet 20 top (drawer start radius pinned) · card 14 · tile 14 · thumb 8.
- Elevation = **glass alpha steps + scrims only**. No drop shadows on flat surfaces; floating transients (speed card, popovers, Up-Next hero) may carry one soft shadow (y8 blur28 50%). Zero blur, ever.
- Grid: 12dp base, 8dp intra-group, ≥2× inter-group. Content bleeds edge-to-edge; controls float over hairlines.
- Icons: current outliner set stays (1.5dp stroke default, 2dp active, currentColor). **Two new in-house glyphs only** — `Thread` (the pulse) and `LockTick` — drawn in the same grammar; no new icon libraries.

### 1.4 Imagery — the poster language

Posters stay fully on-device: deterministic two-layer hue art from the title hash (existing `posterHue`), promoted into a system:

- Frame: 16:9 thumb, radius 8, 1px white 10% outline, hairline inset vignette.
- Duration pill bottom-left (black 72%, 8.5sp tnum) — pinned.
- **Progress hairline** under every in-progress card: 3dp rail white 16% + accent fill; the same line language as the scrubber and rail ticks.
- Watched = check + 60% dim; unwatched = frame-accurate 15% sample (hub 9.1); playing = accent bar + tint + equalizer (hub 7.3).
- Series hero: full-bleed hue art, Display title, one accent action (Play/Resume).

### 1.5 The Thread — the signature motif

One 1dp pulse line, `thread` color, that means *the engine is listening / has locked / holds time*:

1. **Sync pill**: idle beat while correlating; flattens and turns `accent` at lock (the lock moment, §3 J3).
2. **Empty states**: the line draws once, settles — "nothing here yet" told calmly.
3. **Resume moment**: under the resume pill, a single tick at the resumed position.
4. **Skeletons**: the thread sweeps as shimmer (no gray boxes pulsing).
5. **Up-Next countdown**: the ring's hairline continues into a thread pulse at 5s.

Never decorative elsewhere. If it appears, the engine has something true to say.

---

## 2. FEEL — the motion & touch system

### 2.1 Motion tokens (named, centralized; player constants stay pinned)

| Token | Value | Use |
|---|---|---|
| `micro` | 90 ms | High-frequency state (toggle, check, chip select) |
| `quick` | 150 ms | Small state change (timer value, label swap) |
| `base` | 240 ms | Arrive/depart of panels, drawers' inner content |
| `glide` | 300 ms | Drawer/sheet travel (pinned) |
| `drama` | 420 ms | Player open, sync lock moment, Up-Next hero |

Easing: `emphasize` = pinned `cubic-bezier(.16,1,.3,1)` for enters; `accelerate` = `cubic-bezier(.4,0,1,1)` for exits (exits run one step faster than their enter); springs only for press (0.55 stiffness MLow, pinned) and settle/dismiss (0.85/400, pinned). Reveal stagger 40 ms (pinned); list cascades 30 ms/row, max 6 animated rows.

### 2.2 Choreography laws

1. **Nothing teleports.** Every surface states where it came from; exits reverse their enter.
2. **Interruptible, always.** New input mid-flight retargets (Compose `animateTo` semantics); nothing blocks the hand.
3. **State is instant unless spatial.** Toggles/labels ≤ `quick`; only position/scale/panel travel earns `base`+.
4. **One drama beat per screen.** The player's is the sync lock. Nothing else may compete.
5. **Gravity, not gliding.** Elements arrive with the pinned −8dp/+10dp reveal; lists land, they don't float.
6. **Honest motion.** Indeterminate states stay indeterminate; no fake progress bars, no eased placeholders that imply knowledge the engine lacks.
7. **Reduced motion**: system "remove animations" → crossfade 120 ms or instant cut; no exceptions.

### 2.3 Haptic vocabulary

| Event | Haptic |
|---|---|
| Select / toggle / chip | `KEYBOARD_TAP` |
| Primary transport, drawer open | `VIRTUAL_KEY` |
| Long-press confirm (reset speed, forced resync) | `LONG_PRESS` |
| Scrub milestones, slider ticks | `CLOCK_TICK` per tick |
| **Sync lock** | one `CLOCK_TICK` — the app's heartbeat click |
| Passive state changes | none, ever |

### 2.4 Gesture physics (pinned, unchanged)

Vertical drags with 900 ms value pill; double-tap ±10s wave; hold-to-speed milestones; drawer velocity dismiss (72 dp / 800 dp/s); dead zones 52/24 dp. The reimagination adds no new stages inside the player — it extends the same physics to app surfaces (list fling, sheet dismiss, nav).

---

## 3. FLOW — the journeys

### 3.1 Information architecture

```
MainActivity  ── Home ── Series ── Settings        (bottom nav, 3 — current)
     │            └─ "Watch History" sheet → full History page (see §5)
     │  card / continue / row
     ▼
PlayerActivity (own task; predictive Back returns to the exact card)
```

Transitions: between app surfaces = **fade-through** (out 90 ms fade+scale .985, in 210 ms fade) on `emphasize`; player open = **stage handoff** — the card's thumb expands into the player's black stage (420 ms `drama`; pragmatic cross-activity form: position-carried fade-through + in-player reveal gravity; true container transform becomes possible if a single-activity refactor ever lands — flagged, not promised). Back is always predictive; nothing traps.

### 3.2 J1 — Cold open → Home

Skeleton (existing `SkeletonRow`) → content fade-through `base`. First rail: **Continue watching** (not a generic carousel): the last-watched gets a wide `ContinueCard` with the progress hairline and a `Resume` affordance — the "welcome back" moment. Empty library = Thread empty state + one honest line.

### 3.3 J2 — Open a video

Card press → stage handoff → title/tech arrive with pinned reveal gravity → resume pill if applicable (inside dock, pinned) → sync story begins (J3). No spinner wall: the frame shows as soon as the decoder has it.

### 3.4 J3 — THE SYNC STORY (the flagship; the app's hero moment)

Four honest beats, every one already backed by real engine state:

1. **Beat** — pill `Syncing…` with the true spinner; Thread idles beneath it at 45%.
2. **Lock** — engine locks: offset settles to its final value, the Thread flattens to `accent`, the tech-line segment ignites, **one `CLOCK_TICK`** haptic. This is the drama beat (420 ms), and it is *never* faked — it fires only on a verified lock.
3. **Verified** — pill `✓ Synced +0.12s` (tnum). Tap = popover: ±0.1 hold-repeat steppers, ±5 s hairline slider, `Resync now` (forced full pass). Long-press = forced pass straight from the pill.
4. **Refused** — when the engine declines (no speech, no track, sync off), the pill says exactly that. "Refuse-don't-guess" is a design law, not just engine behavior.

Post-seek: re-correlation under 1 s restarts beat 1 visibly — the user watches the engine keep its promise.

### 3.5 J4 — Episode switch & Up-Next

Playlist drawer rows (P2 restyle: flat rows, rich subs `48:23 in · 10% left`, watched checks, real drag-reorder) → tap switches in place: pill re-beats, offset re-locks, position resets — same choreography every time. Final 30 s → Up-Next pill → hero card with countdown ring (ring hairline → thread pulse at 5 s) → Play now / Queue / Cancel.

### 3.6 J5 — Library & series

Sections: Continue, Recently added, Folders/Series — flat rows on `ground`, no boxed cells (rip list below). Folder row → series hub (`14 / 24 episodes watched`, one-tap Up-Next chip) → episode list with watched marks. Multi-select via `SelectionActionBar`. Search sits at the top of Home (existing `BrandSearchField`, restyled to the Label/`glass` language).

### 3.7 J6 — History

Day groups + per-item progress hairline + **% shown as trust** (hub 8.2), remove per-row, Clear History with confirm. Percentages are tnum jewelry, not decoration.

### 3.8 J7 — Settings hub

Keep the 6-folder IA (existing, hub 9.4); restyle folders as flat `glass` rows with Label values; drill-ins use fade-through; every toggle honest (decoder segment labels become the true `HW+SW · APP · SW` in P2; skins preview in a live strip: Obsidian · Light · Cobalt · Amber). Settings search = P3 (hub 9.5), inherits this language.

### 3.9 J8 — Exit, PiP, background

Player → PiP animates from the stage (system PiP, subtitles live); background audio = notification with honest transport; return restores the exact frame. No announcements, no modals.

### 3.10 Rip list (rule 15 — stale UI has no tenure)

- Boxed settings/quick-action cards → flat rows (§3.8).
- Oversized library card icons/badges (user complaint, P2-A) → token scale.
- Legacy monospace numerals → the type scale.
- Duplicated popup sheets / second-generation controls (hub 7.5) → delete on sight.
- Any row-within-row chrome that draws boxes where a hairline would do.

---

## 4. Build mapping & verification

**Token homes (Compose):** `ui/theme/Motion.kt` (durations/easings/springs as named `MotionSpec`s) · extend `Dimens.kt`/`PlayerDimens.kt` with the type/space/poster tokens · `WaveThread` composable (Canvas, 1dp, no allocations per frame) · poster upgrade inside `LibraryScreen.kt`'s `PosterArt` · transitions: `fade-through` helper + activity-position carry.

**Phase map (extends hub §13):** the player-side beats (Thread idle/flatten in the sync pill, lock haptic) land with **P2** (they ride the pinned chrome without changing its 1:1 look where frames are modeled; the Thread is additive state, verified by a new render state `portrait_sync_locked`); library/history/settings restyles absorb into **P2-A/S6**; series drill-down, settings search, sidecar posters stay **P3** as queued.

**Verification loop (unchanged, extended):** CI Roborazzi renders gain states for the new surfaces (library home, series hub, history, settings hub, sync-locked) alongside the existing seven; the phone feel-check remains the motion gate (no emulator exists locally — declared honestly; Robolectric renders are geometry evidence, the phone is the physics evidence).

**Risks, stated plainly:** cross-activity container transform is the one promise activity boundaries may refuse (pragmatic fade-through fallback defined above); the Thread must never animate in a way that fakes engine state (its visibility is bound to real sync state); the type-scale migration touches many files and must be done per-surface, CI-verified, never in one blind sweep.

---

## 5. Open questions for the user

1. **Ambient contrast:** app canvas `#0A0A0A` graphite vs the player's pure black — keep the two-step "the app dims, the film lights" story, or make the whole app pure black? (Design leans graphite for structure; either works.)
2. **History placement:** today it lives in a Home sheet ("Watch History"). Promote it to a fourth bottom-nav destination (Home · Series · History · Settings), or keep the sheet + "View all" deep-link? (Design leans 4 tabs — History becomes a real page in P2, and discoverability matters.)
3. **The lock soundless moment:** haptic-only, or also a 60 ms "tick" audio cue when headphones are on? (Design leans haptic-only — audio in a film player risks polluting the mix.)
