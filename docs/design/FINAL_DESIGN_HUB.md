# FINAL DESIGN HUB — anonrode player-screen rebuild
_Single source of truth for the player-screen redesign. Numbering inherits `docs/MASTER_FEATURE_AND_INTENT_SPEC.md` (sections 0–11 plus new 12–14). Everything in one place: verdicts, behaviors, phases, benchmarks. Fix or amend here by number. Nothing is implemented until the mockup bearing these numbers is signed off (AGENTS.md 15/16, master spec U26/U34)._

**Sources fused:** master spec (v0.9.11 status), the second-agent walkthrough sheet (this session, "WS"), reference recordings R1–R4 (frame-verified this session), Persona A = everyday binge-watcher, Persona B = power quality freak (both research-backed, this session), design craft skills (.agents/skills/better-*), AGENTS.md.

---

## 0. DESIGN LAW (locked; supersedes everything below)
| Law | Value / rule | Source |
|---|---|---|
| Glass | 0.96-alpha near-black panels, **zero blur**, hairline white 8%. Never white/blue glass, never blurry | master §0 + R1–R4 |
| Press feedback | scale **0.96** spring, settle with slight overshoot; never below 0.95 | better-ui (exact value) |
| Drawer motion | slide-in 260–300 ms `cubic-bezier(0.16,1,0.3,1)`; velocity swipe-dismiss (>72 dp or >800 dp/s closes, else spring 0.85/400 back) | master §0 |
| Stagger | infrequent surfaces (drawer sections) staggered ~70 ms semantic chunks; high-frequency states instant, ≤150 ms | better-ui + WS |
| Numerals | every changing number = tnum; labels 9 sp weight 400+ under discs; headers 11 sp caps +0.4 tracking; title 14 sp ellipsized | master §0 + better-typography |
| Accent | state only — active ring, played track, filled primary; **one filled primary per view**; status hues (sync green, A-B amber, LIVE red) never collide with accent | better-colors + master |
| Icons | one family per surface, 1.5 px stroke regular / 2 px semibold context, `currentColor`, outline default + fill marks active | better-ui |
| Radii | concentric: outer = inner + padding, one rule everywhere | better-ui |
| Controls | ≥48 dp cells; 10–85 % viewport cue drag-anchors; group with space (inter-group ≥ 2× intra-group); content bleeds, controls float | better-layout |
| Honesty | no dead controls; pills state the truth (decoder, sync, speed) — including silent fallback | AGENTS.md 11 + P-B |
| Gates | mockup-approved → phase benchmarks → CI green. Subagents ≤3 concurrent (user cap) | user, this session |

---

## 1. TOP CHROME
| # | Feature | Behavior law | Status v0.9.11 | Verdict (source) | Phase |
|---|---|---|---|---|---|
| 1.1 | Back (←) | leaves player, position saved first (already saved on pause/back) | SHIPPED | KEEP [master] | — |
| 1.2 | Title | full filename 14 sp SB, one line, ellipsized | SHIPPED | KEEP [master] | — |
| 1.3 | Tech sub-line | under title: `1080p · 24fps · HW+` — resolution, fps, real decoder | gap 5 (partial) | RESHAPE (wire real values) [WS; P-B: "label must match engine"] | P1 |
| 1.4 | Decoder pill | shows the **running** pipeline (HW+SW / APP / SW); tap cycles, rebuilds, keeps position/speed/boost/sync; truth incl. silent fallback | SHIPPED (chip fixed) + keep cycling | KEEP (P-B #2 priority) [master/WS] | P2 re-verify |
| 1.5 | CC chip | opens subtitle drawer; ring tints while a subtitle source is active | SHIPPED | KEEP | — |
| 1.6 | Playlist chip | opens episodes drawer | SHIPPED | KEEP | — |
| 1.7 | ⋮ | opens settings drawer | SHIPPED | KEEP | — |
| 1.8 | Centerline law | all four icons + title locked to one 48 dp centerline; active drawer tints its icon | SHIPPED | KEEP [master v5 §2-1] | — |

**Hotfix note (P0, shipped commit `4b9fe9d`):** the hold-to-speed detector engaged on every tap (CC-tap repro). Guard restored. This was the "2× without touching" defect.

## 2. THE RIBBON
| # | Feature | Behavior law | Status | Verdict (source) | Phase |
|---|---|---|---|---|---|
| 2.1 | Disc ribbon anatomy | circular disc in 48 dp cell, 9 sp label underneath, horizontal scroll, sleeve toggled by ›; survives process death | SHIPPED | KEEP anatomy [master/WS/R2] | P1 polish |
| 2.2 | Resting set | default visible = **Speed, Screenshot, Rotation, Mute** (user-named) then persisted customise | PARTIAL (no forced defaults) | RESHAPE: force the named resting order on first run [master v2 §F] | P1 |
| 2.3 | Active state | len-des ring in accent + soft glow; never color-only — dot/ring + label state | SHIPPED partial | RESHAPE to craft-skill ring values | P1 |
| 2.4 | Speed disc | tnum live badge ("1.0X"/"2.45X") centered under disc; tap → precision card; long-press → reset 1× | SHIPPED | KEEP [U9] | P2 speed pass |
| 2.5 | Screenshot | PixelCopy frame grab (never black), white flash, saves `Pictures/Anonrode` (fix folder name), toast w/ timestamped filename | SHIPPED (folder cosmetics) | KEEP, rename folder [gap 8; P-B condition: frame-exact + timestamp] | P2 |
| 2.6 | Rotation | free-sensor → locked landscape → locked portrait; activity orientation follows; host stops overriding | SHIPPED | KEEP | — |
| 2.7 | Mute | true stream mute; volume memory restores exact level on unmute | SHIPPED | KEEP, add memory-restore check | P2 |
| 2.8 | Night | amber eye-comfort scrim, in-ribbon toggle | SHIPPED | KEEP (P-A tolerated; keep out of default resting? default OFF) | P2 |
| 2.9 | Loop | off / repeat-one / repeat-all on the real engine, persisted per-video | SHIPPED | KEEP [master] | — |
| 2.10 | Sleep timer | off →15/30/45/60 → end-of-episode; disc shows live countdown; end-of-episode blocks auto-advance (fixed bug) | SHIPPED | KEEP | — |
| 2.11 | A-B repeat | tap A, tap B = loop pins amber on scrubber, third tap clears; enforced at engine tick | SHIPPED | KEEP [R2 active-dot pattern] | — |
| 2.12 | Customise | reorder/hide tools, persisted order + scroll | SHIPPED | KEEP [P-A fixed-five satisfied by sleeve] | P2 |
| 2.13 | Background play | audio continues when leaving app iff setting on; leaving pauses iff off | SHIPPED | KEEP | — |
| 2.14 | Enhancer | Dialogue DSP (rumble HP + mud cut + presence + soft limiter) — audible or nothing | SHIPPED (v2) | KEEP as "Audio effect" preset row | P2 |
| 2.15 | EQ | 5-band session EQ | in settings grid | RESHAPE → **presets first** (Flat / Dialogue / Bass / Late-night) wired in DSP chain; raw bands only in P3 if it earns it [P-A: "bands are furniture"; P-B: persist + honest labels] | P2 presets, P3 bands |

## 3. GESTURES (stage)
| # | Feature | Behavior law | Status | Verdict | Phase |
|---|---|---|---|---|---|
| 3.1 | Single tap | toggles HUD, quick fade; auto-hide 3.5 s (ask: 5–6 s, gap 6); NEVER hides during interaction/drawer/scrub | PARTIAL | KEEP + raise timeout to 5.5 s [gap 6 = an explicit user ask] | P1 |
| 3.2 | Double-tap L/R | ±10 s seek wave: chevrons + tnum, ~50 % black, **no box/blur** (never blurry law), cumulative, 52 dp dead zones | SHIPPED | KEEP [U18/U20/U22] | — |
| 3.3 | Hold + slide | temporary milestones 0.25–4.0 only, haptic per tick, release restores exactly base speed + toast; expanded HUD | SHIPPED | KEEP (hotfixed guard) [U6/U7] | — |
| 3.4 | Vertical drags | right = real stream volume, left = brightness 2–100 % persisted; icon + rail + tnum % pill (900 ms); full sweep 70 % screen; zoned exclusions | SHIPPED | KEEP [v4] | — |
| 3.5 | Dead zones | 52 dp top/bottom, 24 dp sides never trigger gesture layer | SHIPPED | KEEP | — |
| 3.6 | Pinch | cycles aspect steps w/ transient badge | SHIPPED | KEEP | — |
| 3.7 | Subtitle cue | direct drag, no long-press delay, 10–85 % viewport, per-video + global default | SHIPPED | KEEP [U19] | — |

## 4. BOTTOM DOCK
| # | Feature | Behavior law | Status | Verdict | Phase |
|---|---|---|---|---|---|
| 4.1 | Sync pill (heartbeat) | "Syncing…" live → "✓ Synced +0.00s" tnum → "Sync Off"; tap = popover (−0.1/+0.1 hold-repeat, ±5 s slider, re-sync, style, on/off); **long-press = forced full pass**; re-correlate <1 s post-seek | SHIPPED (popover exists) | RESHAPE: ±0.1 s steps + hold-repeat + long-press force [WS; master rule 46] — engine untouched (rules 7/12/14) | P1 |
| 4.2 | Scrubber layers | buffered white 45 % **visible, not hidden behind played**, played = accent, track white 22 %; honest | SHIPPED partial | RESHAPE exact layer values | P1 |
| 4.3 | Times | tnum both ends; tap to flip elapsed ⇄ −remaining | SHIPPED | KEEP | — |
| 4.4 | Preview bubble | while dragging: real decoded frame near target + exact time; **timestamp-only fallback allowed if frame not renderable in <100 ms** (P-B honesty condition) | SHIPPED (v0.7.1 throttle) | KEEP + honesty fallback | P2 verify |
| 4.5 | A-B pins | amber pins at A and B | SHIPPED | KEEP | — |
| 4.6 | Transport | Lock (left wing) · ‹10s · ▶ hero 56 dp dark circle · 10s› · right wing aspect + fullscreen | ±10s chips **NOT BUILT** | BUILD chips (replay10/forward10 badges, same configurable step 5/10/15/30 shared with double-tap) [gap 2; P-B expand → chips must take configurable step] [master v2 §C] | P1 |
| 4.7 | Prev/Next | previous/next file in folder; Prev → 0:00 if >3 s in | SHIPPED | KEEP | — |
| 4.8 | Lock overlay | everything dead except unlock (tap+long-press; v6 hold-to-unlock ring proposal) | SHIPPED | KEEP; hold-ring optional polish P3 | P3 opt |
| 4.9 | Aspect chip | FIT/CROP/STR/16:9/4:3 + transient badge + long-press picker; **remember per show** [P-A] | SHIPPED partial | RESHAPE persistence | P2 |
| 4.10 | Auto-hide | 3.5 s → **5.5 s**; never mid-interaction | PARTIAL | FIX timeout [gap 6] | P1 (with 3.1) |

## 5. SPEED SYSTEM (laws)
| # | Feature | Behavior law | Status | Verdict | Phase |
|---|---|---|---|---|---|
| 5.1 | Precision card | −/+ 0.05 steps, continuous slider 0.25–4.00 @0.01, ↺ reset, milestone chips, big tnum readout | SHIPPED | KEEP (this is the R3 fine-slider family) [U9] | P2 polish |
| 5.2 | Hold milestones | temporary, snap-only, restore exact base | SHIPPED | KEEP | — |
| 5.3 | Persistence | re-asserted on decoder swaps + episode switch; persisted per-video; **speed survives files when user sets it globally** [P-B: 1.4× every night] | per-video only | RESHAPE: add global default speed setting | P2 |
| 5.4 | Truth badges | "2.0× Speed" keep-alive pill only while boosted; speed disc badge while ≠1× | SHIPPED | KEEP, no phantom triggers (hotfix) | — |

## 6. SUBTITLES & SYNC (flagship — engine untouched)
| # | Feature | Behavior law | Status | Verdict | Phase |
|---|---|---|---|---|---|
| 6.1 | Cue rendering | bold outlined high-contrast, custom size/colour/bold, identical embedded vs sidecar (no black boxes), overlay routing | SHIPPED | KEEP | — |
| 6.2 | Sync-first drawer order | toggle → current track → **Sync first** → Open File → Online → Style | SHIPPED | KEEP [explicit user ordering] | — |
| 6.3 | Sync row | status line ("Offset +0.12s · fingerprint active"), −0.1/+0.1 steppers (hold-repeat), ±5 s slider, reset, re-sync = force | SHIPPED (250/1000 ms steps) | RESHAPE to ±0.1 hold-repeat [WS] | P1 |
| 6.4 | Open File | SAF picker .srt/.vtt/.ass | SHIPPED | KEEP | — |
| 6.5 | **Online download** | search → list → download → auto-attach → run sync pass; **user amendment: in scope, full function later**; footer "local-only" wording updated when it ships | PARTIALLY SHIPPED: the host sheet `app/.../audio/SubtitlePickerSheet.kt` (~:206) already carries an online-search pane; the drawer row that led to it was mislabeled (it opened the local track picker) and was ripped in `819422e` per rule 11 | P3: verify pane end-to-end against real files → then drawer re-entry only as a wired row [user amendment this session] | P3 |
| 6.6 | Style | size/position/colour/bold + live preview cue | SHIPPED (tray) | KEEP | P2 merge into drawer |
| 6.7 | PiP subtitles | cues persist in PiP | SHIPPED | KEEP | — |
| 6.8 | Engine discipline | no modals, glanceable status, force=full-pass, refuse-don't-guess, <1 s re-lock, no re-tuning of validated envelope | SHIPPED/VALIDATED | UNTOUCHABLE (rules 7,12,14) | — |

## 7. DRAWERS (three, glass law)
| # | Feature | Behavior law | Status | Verdict | Phase |
|---|---|---|---|---|---|
| 7.1 | Drawer chassis | right slide-in 260–300 ms glide, **velocity-aware swipe-dismiss** (gap 1), glass scroll w/ momentum, scrim tap-out, auto-hide paused while open | PARTIAL (no velocity dismiss) | BUILD dismiss [gap 1] | P1 |
| 7.2 | Settings (⋮) | context header (file/ep/duration/decoder); ACTIONS 4×2 real tiles (Audio track, Subtitle, Pop-up play, Cast, Background, Boost, Night, Lock — active glow); PLAY OPTION circles (A-B, EQ, Timer, Enhancer); REPEAT [Off|One|All] segmented + Order label; brightness/volume precision sliders w/ tnum %; DECODER 3-seg; footer Full App Settings | SHIPPED anatomy; glass restyle pending | RESTYLE + velocity dismiss [gap 9] | P2 |
| 7.3 | Playlist | folder/series header + count; rows thumb + duration pill tnum; playing row = accent bar + container tint + equalizer icon; watched checks; 1-tap switch; **real drag-reorder** (gap 4 — handle decorative today) | PARTIAL | BUILD reorder [gap 4; R4 queue panel] | P2 |
| 7.4 | Subtitle | see §6 | SHIPPED | KEEP order | P1 sync row |
| 7.5 | Rip-out list | anything duplicated by the three drawers: legacy popup sheets, second-generation controls; no dead code left behind | — | RIP (rule 15) | P2/P3 |

## 8. PLAYBACK OPERATIONS
| # | Feature | Behavior law | Status | Verdict | Phase |
|---|---|---|---|---|---|
| 8.1 | Resume | silent auto-resume + non-blocking pill "Resumed at 14:22 · Start over" a few seconds; saves on pause (listener) + back/stop synchronous; A-B & sync survive decoder swap | SHIPPED | KEEP [P-A: 80 % positive, non-blocking form] | P2 polish |
| 8.2 | History | **Continue-watching "View all" → history list w/ per-item progress + % + remove + Clear History** (day groups) [gap 3; R1 History screen]; plus progress hairline under library thumbs | NOT BUILT | BUILD [gap 3; P-B: % builds trust; P-A: prefers hairlines in library first] | P2 |
| 8.3 | Up-Next | final-30 s pill → end hero card w/ real thumb + 5 s countdown ring + Play now/Queue/Cancel; per-series bypass | SHIPPED | KEEP, add Cancel row on hero | P2 |
| 8.4 | Decoder swaps | never lose position/speed/sync (P-B grievance 2) | SHIPPED | KEEP + CI regression guard | P2 |
| 8.5 | PiP | system PiP primary (subtitles live); floating overlay = deferred opt-in with one honest sentence + deep link (P-B verdict; my rec) | PARTIAL | KEEP system; defer overlay | P3 opt |
| 8.6 | Background audio | see 2.13 | SHIPPED | KEEP | — |

## 9. LIBRARY / SERIES / SETTINGS (home app)
| # | Feature | Behavior law | Status | Verdict | Phase |
|---|---|---|---|---|---|
| 9.1 | Home | universal hub, zero benefits removed; continue-watching instant updates; frame-accurate thumbs (paused frame / 15 % sample unwatched) | SHIPPED | KEEP | — |
| 9.2 | History page | see 8.2 — surface in Library nav | NOT BUILT | BUILD | P2 |
| 9.3 | Series hub | auto-group, "14 / 24 Episodes Watched", 1-tap Up Next chip; drill-down w/ watched marks | PARTIAL (drill-down mockup-only) | COMPLETE drill-down [gap 7] | P3 |
| 9.4 | Settings hub | 6 categorized folders + drill-down + backstack correct; contents per enum spec | SHIPPED | KEEP + items below | — |
| 9.5 | Settings search | search bar across the 6 folders | NOT BUILT | BUILD [gap 7] | P3 |
| 9.6 | Poster sidecars | .nfo/folder.jpg series art | NOT BUILT | BUILD [gap 7] | P3 |
| 9.7 | Themes | Obsidian/Light (emerald), Cobalt (blue), Amber (gold); player chrome stays dark glass on every skin; unify the dual accent tokens (rememberSkinPalette vs rememberPlayerAccent) for bars/HUDs | SHIPPED + dual tokens | UNIFY (S6) | P3 |

## 10. SYNC ENGINE (§9 of master spec) — untouched; validated envelope rules 7/12/14 stand. Corpus CI must stay green. No UI change may alter correlator tuning. Benchmarks only GUARANTEE the UI reflects real state (honesty law).

## 11. DOWNLOADER APP — separate repo; out of scope here. Mentioned only because master spec tracks it.

---

## 12. HOTFIX LOG
| Commit | What |
|---|---|
| `4b9fe9d` | fix(player): every tap armed the hold-to-speed scrub at 2.0× (guard dropped in `4cef2c8` during v0.9.10→11 swap). Restored `if (!activated)` guard + fall-through documented. **Push/tag v0.9.12 pending user approval.** |
| `eedb602` | docs: AGENTS rules 15 (stale UI no tenure) + 16 (design-skill leverage, no unapproved downloads) |

**Design gate status: PASSED (this session).** Mockup `scratch/proposed_black_glass_player.html` — 10 frames, judged in rounds by the acceptance pass. Round-2 verdict: law holds everywhere; 5 numeric-truth defects (remaining-time/fill disagreements, orphaned Order label, cross-frame duration mismatch, inconsistent card anatomy) — all fixed and re-verified round-3: overall PASS, zero must-fix. Numeric data on the dock must derive from ONE source of truth in code (elapsed + duration → fill, remaining, %) — that is now a Phase-1 S1/S2 benchmark, not just a mockup property.

## 13. PHASES, SECTIONS, SUBAGENT FLEET (≤3 concurrent, report back here)
**Sequence:** P1 → P2 → P3. Each section = one subagent workstream with entry/exit benchmarks; reports land in this doc as updates; integration pass closes each phase.

- **Phase 1 — "The confident chrome"** (sections S1, S2, S3 in parallel)
  - S1 Chrome skeleton: centerline law, tech sub-line (1.3), ribbon restyle + resting set (2.2/2.3), dock layers (4.2), **±10s chips (4.6)**, auto-hide 5.5 s (3.1/4.10), drawer chassis velocity dismiss (7.1).
  - S2 Gesture + speed hardening: hold-to-speed regression suite (12), double-tap step sharing, vertical drag polish, haptics law.
  - S3 Sync + subtitle surface: sync pill states + popover ±0.1 hold-repeat + long-press force (4.1/6.3), drawer merge order (6.2-6.4/7.4).
  - Exit benchmarks: CI compile+unit green; gesture regression suite (tap→2.0× impossible, fast-tap ×20 no scrub, drawer chip tap never scrubs); visual diff vs approved mockup §A/B/C; no-dead-code grep; every control wired.
- **Phase 2 — "The trusted player"**
  - S4 Continuity: History page (8.2/9.2), playlist drag-reorder (7.3), Up-Next hero rows (8.3), resume pill polish (8.1), preview-bubble honesty fallback (4.4), aspect per-show memory (4.9), global default speed (5.3).
  - S5 Audio & decoder: audio-effect presets + EQ presets (2.15), mute memory (2.7), screenshot folder+filename (2.5), Night default off (2.8), decoder cross-swap regression (8.4), truth pills (1.4).
  - S6 Consolidation part 1: settings drawer restyle (7.2), rip-outs (7.5), style-tray merge (6.6).
  - User feedback (pre-0.9.12 session): Home/Series card icons render too large — audit card anatomy in LibraryScreen.kt (Home + Series tabs + continue-watching cards) and size icons/badges back to the token scale during Phase 2 (S4/S6).
  - Exit benchmarks: CI green; % accuracy in History real; reorder survives restart; decoder cycle keeps position/speed/offset (instrumented); no orphaned sheets; visual diff.
- **Phase 3 — "The complete product"**
  - S6 rest: settings search (9.5), series drill-down (9.3), poster sidecars (9.6), accent unification (9.7), hold-to-unlock ring (4.8, optional), PiP fit-finish (8.5).
  - S7 Online subtitle download, full function (6.5): provider search UI, fetch+persist (cache dir, license-safe), auto-attach, sync pass trigger, offline-first error states; footer wording update.
  - Exit benchmarks: CI green; end-to-end sub search→download→attach on 3 real films; sync engine corpus CI untouched-green; final mockup diff = clean.

## 14. OPEN QUESTIONS FOR THE USER (answered before P2 starts)
1. Auto-hide target: 5.5 s agreed? (gap 6 says "5–6 s" asked; hub picks 5.5)
2. Default skin + default resting ribbon order (2.2 candidates: Speed, Screenshot, Rotation, Mute as v2 named)
3. Screenshot keep (P-B conditional) vs persona A's CUT — hub leans KEEP w/ timestamp filename
4. Series hub priority vs online-sub download for P3 slot order

_Maintainer: update verdicts/benchmarks by number; never renumber. Phase completion stamped here with commit hash._

## 15. CONVERGENCE ROUND LOG — player screen vs the agreed HTML (overnight loop)
_Mandate: match `scratch/proposed_black_glass_player.html` 1:1 in every section, then EXCEED it; the drawer must feel butter, never janky. Every round is rendered by the real app (ui_render workflow → Robolectric PNG artifacts, dispatch-only) and judged side-by-side against the mockup frames. User's phone recordings remain the final feel-check per converged round._

**Why phase 1 drifted (root cause, honestly recorded):** the mockup was verified against the law, but the app was never verified against the MOCKUP — prose specs drifted. Countermeasure now live: `recordRoborazziDebug` on CI = hard visual evidence per round; every chrome constant defers to the mockup's exact values.

**Round 0 — evidence (user recording screen-20261014-232847.mp4 + v0.9.12):**
- FAIL resume pill floated dead-center ON the seekbar (activity-level overlay, bottom=88dp while dock ≈200px).
- FAIL "Syncing…" pill cramped at the edge (no gutter; border carried accent instead of hairline).
- FAIL top row: HW/HD chip duplicated decoder truth (mockup carries it in the tech sub-line).
- FAIL speed disc = filled accent; law = ring + halo on dark glass.
- FAIL transport chips = outline boxes; mockup = bare white icons.
- FAIL press scales 0.88–0.90 (law 0.96).
- FAIL motion missing: no reveal stagger/gravity — flat snaps ("clunky").

**Round 1 — conversion (commit `6e990db`, push pending network recovery):**
- ControlChip: no box unless STATEFUL (selected = black-glass disc + accent ring, tint accent; else bare white icon).
- Ribbon discs: flat 42% black glass, selected = 1.5dp accent ring + 12% halo; labels white when lit; chevron handle = bare icon (was a boxed chip).
- Hero: flat dark-glass circle 0xE616181B, white 12% hairline, white glyph; press 0.96.
- Press 0.96 everywhere (chips, ribbon; was 0.88/0.90).
- Sync pill: law glass 0.96 near-black, hairline white 8% border, fully-rounded; accent only on glyph/text.
- Resume pill MOVED INSIDE the dock (PlayerScreenBottomBar renders it above the sync row from `resumePillMs`/`onStartOver` params; activity overlay deleted) — overlap structurally impossible.
- Top bar: decoder chip removed (truth = tech sub-line + settings decoder segments).
- Reveal motion (better-than-HTML): top slides −8dp, dock slides +10dp on the emphasized bezier, 40ms stagger.
- Rule-10 cleanups: DecoderPill call removed (composable pending removal check), isSpeedPill param chain removed, fmtClock def removed (dead after pill move).

**Round 1 render verdict:** _pending — push blocked by flaky network (remote hangups); retry queued._ Log every next verdict here.

**Round 2 planned (apply + render after round 1 evidence):**
- Drawer feel audit (explicit user demand): confirm 260–300ms emphasized-bezier + velocity dismiss (72dp / 800dp/s, spring 0.85) survive; panel = 0.96 flat glass, zero shadows/blur layers on the panel; drag uses graphicsLayer-only translation; scrim fades with the panel; add drawer CONTENT stagger (sections cascade 40–70ms) on first open only.
- Sync pill: give it a min-width so "Syncing…" never computes an ellipsis; status text stays tnum.
- Seek chips: chevrons white 90%, "Ns" label white 60% (verify vs render).
- Sliders inside drawers: hairline 3dp fills (mockup E/D).
- Speed card: add tick rail + 0.25x/4.0x end labels (matches C).
- Library card icons (user complaint, P2-A scope) stay queued for the Phase-2 library pass.

**Standing loop protocol:** convert → `recordRoborazziDebug` retry push → dispatch ui_render → download artifact → read every frame vs mockup → log verdicts here → repeat. A section is DONE only when a screenshot round shows 1:1 (or documented better-than-HTML delta).
