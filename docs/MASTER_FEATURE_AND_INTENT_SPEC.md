# ANONRODE — Master Feature & Intent Specification
_Every feature ever asked for across all Antigravity sessions (1672a965, b0d46d32, c5d135bd), the v2 spec sheet, the 13-min video review, this session's transcripts, and the notebooks — with exactly how the user wanted it and where it stands today (v0.9.11)._

**Sources read (exhaustive):** player_v2_master_redesign_plan + master_v2_spec_sheet (frame-by-frame 00:00–13:22 video review), all 8 mockups (proposed_ui, player_ui_redesign_preview, v2 + option_a peek + drawer-pull + scrollable-ribbon previews, v3 ribbon/speed, v4, v5 modular/mature, settings hub), v4/v5/settings/series implementation plans, precision_speed_controls_plan, THE_DOWNLOADER notebooks (defects, UI master, pre-launch, OTA, four-track, walkthrough, asian-drama), sync_evolution + benchmark reports,_SESSION transcript (40 user requests), comprehensive handover, AGENTS.md.

## 0. THE DESIGN LAW — cross-cutting, non-negotiable
| Law | Source |
|---|---|
| Black, near-transparent translucent glass. **Never blue, never white glass, never blurry** | c5d135bd U6/U20/U21/U22 |
| Buttery yet snappy — fluid premium motion; zero-jank; never fade controls mid-interaction | U22/U36; v2 spec §E |
| Drawer motion: slide-in 260–300ms `cubic-bezier(0.16,1,0.3,1)`, velocity-aware swipe-dismiss (>72dp or >800dp/s closes, else spring-back 0.85/400), glass = 0.96 alpha opaque, **zero blur**, hairline border white 8% | v5 plan §3 |
| One anatomy: disc-in-48dp-cell; press = 0.90–0.95 scale spring; concentric radii; 48dp minimum targets | v5 plan/v2 plan |
| Numerals always tnum monospace; labels 9sp under discs; section headers 11sp caps; title 14sp SB | throughout |
| Accent: emerald on Obsidian/Light; native on Cobalt/Amber — per-skin `PlayerAccentTokens` | v5 plan §1 / U29 |
| No dead controls, no fake data, no lying pills | AGENTS.md 10/11 |
| Never implement without an approved mockup | U26/U34 |

## 1. PLAYER — Top chrome
- **Top-bar order [HD] → [CC] → [Playlist] → [⋮]**, locked on one 48dp centerline with the title; active drawer tints its icon. (v5 §2-1, U27) → **SHIPPED**
- Title = full filename 14sp ellipsized. → **SHIPPED**
- **13-tool quick ribbon**, horizontally scrollable, 1-tap direct (Night, Customise, Shuffle, Loop, Mute, Timer, A-B, FX, EQ, Speed 1X dynamic label, Screenshot w/ flash → `Pictures/Anonrode`, Background play, Rotation) — persisted order + customise sheet. (v2 §A, v5) → **SHIPPED** (v0.9.x)
- Default resting ribbon = the 4 core tools the user named: **Speed, Screenshot, Rotation, Mute**. (v2 spec §F: user 02:41–02:56) → **SHIPPED (partial)** — default order is Speed, Screenshot, Rotation, Mute ✓ but ordering follows persisted customise, no forced defaults → OK.
- Speed disc readout shows live value ("1.00X/2.45X"), **aligned under its disc**. (U9/U22) → **SHIPPED**

## 2. PLAYER — Gestures
- **Double-tap L/R = seek wave**: ±configured 10s, keyframe-snappy (no loading bar), chevrons + tnum, **no box, black ~50%, never blurry**, cumulative taps, 52dp edge dead-zones. (U18/U20/U22, v4) → **SHIPPED**
- **Vertical drags**: right=volume, left=brightness with icon+rail+% pill (900ms), full sweep 70% screen, 52dp top/bottom + 24dp side exclusion zones. (v4 §3, v2) → **SHIPPED**
- **Hold = temporary boost**: engages 2.0×, "◆ is either 0.5/1/…/4 milestones only — no in-between on hold" (U6/U7): milestone HUD [▶▶ 2.0x · slide to adjust] expandable track, haptic tick per milestone, **release restores base speed with feedback**; duration badge; NOT applied persistently. (v2 §H + v3) → **SHIPPED** (v0.9.x)
- **Pinch zoom** cycles aspect; settings-gated. → **SHIPPED**
- Subtitle cue: **direct drag without long-press delay**, 10–85% viewport anchored (decoupled from chrome height, v2 sprint 4 fixed), per-video persistence. (v2 §C/U19) → **SHIPPED**
- Hold + slide horizontally while holding = speed milestones (same gesture family). (v3) → **SHIPPED**

## 3. PLAYER — Bottom dock
- Scrubber: tnum monospace times, buffered (white/40, honest - never hidden behind played), played = accent (was sky-blue in v2, skin accent since v5), 14dp round thumb, amber A-B pins, tap-time-to-flip (−remaining), math-aligned 1:1 thumb/track. (v2 §B/v3) → **SHIPPED**
- Frame-preview **scrub bubble while dragging** (throttled keyframes). → **SHIPPED** (v0.7.1+)
- Transport: **Lock (left wing) · |‹ · ▶ hero (56dp, dark circle white icon) · ›| · right wing [aspect cycle w/ transient badge + fullscreen]** — Next/Prev jump **files in folder**, NOT 10s; Prev seeks to 0:00 if >3s in. (v2 spec §C, v5 dock) → **SHIPPED**
- **±10s dedicated seek chips** with replay_10/forward_10 badges, flanking play. (v2 §C "dedicated 10-second jump buttons"; present in v5 modular dock) → **SHIPPED in v6 proposal; app: **NOT BUILT** (chips died in an old purge; TimeSeekButton was re-founded in v6 interactive) — pending implementation round.
- Controls auto-hide: **5–6s (user asked for longer), currently 3.5s**; never fade while scrolling/interacting/drawer open; zero residual lines/bars in immersion; cutout edges full-bleed. (v2 spec §E/§B) → **PARTIAL** (3.5s + interaction-aware hiding shipped; timeout 3.5s vs asked 5–6s; full immersion fixed in v0.7.3)
- A-B repeat with amber pins on the bar + tap-cycle chip. (v2/v5) → **SHIPPED** (loop wake bug fixed this cycle)
- Up-Next: final-30s pill → end-of-video **hero card w/ countdown ring + Play now/Queue**; auto-advance 5s, per-series bypass. → **SHIPPED** (v0.9.x)
- Lock: all touches dead except unlock (tap+long-press today; **hold-to-unlock ring** in v6 proposal — NOT BUILT in app)

## 4. PLAYER — Speed (the laws)
- Disc tap → **Precision card**: −/+ 0.05 steps, continuous 0.25–4.00 slider @0.01 snap, ↺ reset, milestone preset chips, big tnum readout. (U9, v3) → **SHIPPED**
- Hold gesture: milestones ONLY (0.25…4.0), temporary, restore-on-release. (U6/U7, v2 §H) → **SHIPPED**
- Speed re-asserted on decoder swaps + episode switches; persisted per-video. → **SHIPPED**

## 5. PLAYER — Subtitles & Sync (the flagship)
- Crisp modern engine: bold outlined high-contrast, custom size/colour/bold, **identical rendering for embedded MKV/AAC and sidecars (no black boxes)** — overlay routing, internal SubtitleView suppressed. (v2 §G, v4 §1) → **SHIPPED**
- PiP keeps subtitles. (v4 §2) → **SHIPPED**
- Auto-Sync ("the heartbeat of the player"): live per-cue sync via audio fingerprinting, **compact status pill near the top bar: [⚡ Sub-Syncing… / ✓ Synced +1.2s]**, auto-minimise when locked, **auto re-correlate on seek <1s**, native C++. (v2 §G, sync sessions) → **SHIPPED** (pill moved to dock hero chip — same role)
- Manual: −0.1/+0.1 steppers, ±5s slider, reset, re-sync card **in the subtitle drawer, before Open File** ordering. (v5 §2-3) → **SHIPPED**
- Online subtitle download search (OpenSubtitles). (v2 §G) → **SHIPPED**
- Pastel-free Sync HUD; status colours follow the theme. (U29/v0.9 debug) → **SHIPPED**
- v6 last-row "old boxy CC" compare toggle — prototype only, NOT in the app (dev aid; app keeps the fixed rendering).

## 6. PLAYER — Drawers (the thing everything converges on)
- **Three Tier-2 right slide-in drawers** replace popups/legacy cards: Playlist, Subtitle, Settings. (v5 master plan) → **SHIPPED** as structure; restyle to v6 glass per the approved interactive prototype — **PENDING** (the current round).
- Playlist drawer: folder/series header + count; thumb + duration pill per row (tnum); active row: left accent bar, container tint, playing equalizer icon; reorder affordance; 1-tap switch. (v5 §2-2) → **SHIPPED structure** (reorder handle is decorative — real reorder NOT built)
- Subtitle drawer ordering: toggle → current track → **Sync first** → Open File → Online → Style. (v5 §2-3, explicitly user-demanded ordering) → **SHIPPED**
- Settings drawer (⋮): context header; 4×2 quick grid (A-B, Night, Background, Boost, Lock, Sleep, Screenshot, EQ — active tiles glow accent); repeat [Off|One|All] segment (mockup shows Off/One/All; Order semantics); DECODER 3-segment [HW|HW+|SW]; brightness + volume precision sliders w/ tnum %; "Full App Settings →". (v5 §2-4) → **SHIPPED anatomy** (v0.9.x) — v6 glass restyle pending this round.
- Drawer motion: 260–300ms bezier glide + velocity swipe-dismiss + glass scroll + autohide pause while open. (v5 §3) → **SHIPPED partial** (bezier + scrim ✓; velocity-aware swipe-dismiss on drawers itself NOT built yet — dismiss is scrim-tap/close button)

## 7. PLAYER — Misc playback
- Decoder profiles HW+SW→APP→SW with honest labels. → **SHIPPED** (this cycle fixed chip lying)
- Screenshot with flash; to `Pictures/Anonrode` (v2) — shipped to `Pictures/AnonPlayer` (near-match, folder name differs — cosmetic).
- Background play real foreground-service toggle. → **SHIPPED**
- Night scrim. → **SHIPPED**
- Sleep timer incl. 30/60 cycles + **end-of-episode** (fixed this cycle). → **SHIPPED**
- Volume boost up to +100%. (settings hub subtitle) → **SHIPPED**
- Aspect: FIT/CROP/STR/16:9/4:3 + badge; pinch zoom; fullscreen w/ short-edges cutout. → **SHIPPED**
- Resume: **silent auto-resume + non-blocking "Resumed at 14:22 · [Start over]" pill, never the annoying modal**. (v2 §D) → **SHIPPED** (v0.9.x)
- Prev→0:00-if->3s behaviour. → **SHIPPED**

## 8. LIBRARY / SERIES / SETTINGS (home app)
- Bottom nav contrast in dark+light (semantic tokens). (v2 §A) → **SHIPPED**
- Home = universal hub (all folders, search, continue-watching, **zero benefits removed**). (U33, settings plan) → **SHIPPED**
- Series hub = episodic groupings, "14 / 24 Episodes Watched", **1-tap Up Next chip**. → **SHIPPED** (v0.9.x) — season/episode drill-down w/ watched checkmarks: built into Series level? (present in hub mockup; status PARTIAL in app — drill-down exists in mockup only)
- Continue Watching: rail → **"View all" → dedicated history list w/ progress + Clear History**. (v2 §A frames 0418–0434) → **PARTIAL** (rail + real-time updates shipped 0.9.11; "View all/history drawer + clear history" NOT BUILT)
- Frame-accurate thumbnails: paused-frame for in-progress (`videoFrameMillis(pos)`), 15% sample for unwatched, 2:1 series backdrops w/ 180° vignette. → **SHIPPED**
- Continue Watching instant updates (save on pause/stop/back, 5000→1000ms threshold). (U33) → **SHIPPED** (fixed this cycle)
- White-mode text overshadowing fixed (textDim tokens). → **SHIPPED**
- Settings = **6 categorized folders** (Playback & Engine, Audio & Sound, Subtitles & Sync, Controls & Gestures, Appearance & Themes, Library & Data), drill-down + back-stack. (v2 §I + settings plan) → **SHIPPED**
- Settings folders contents per the enum spec (resume rule, speed, autoplay, sleep, decoder / dialogue enhancer, boost up to +100% / auto-sync, SAF folder, size, colour, bold / double-tap, swipe scrub, sliders, pinch / skins / rescan, clear progress, share sync logs). → **SHIPPED**
- SAF folder grant persistence — validate on OEM 13/14 providers. → **SHIPPED, OEM validation open**
- **Recently-watched history drawer + Clear History** (v2 §A) → **NOT BUILT**
- **Settings search bar across the 6 folders** (handover roadmap) → **NOT BUILT**
- **Series poster sidecars** (.nfo/folder.jpg) (handover roadmap) → **NOT BUILT**

## 9. SYNC ENGINE specifics the user cared about
- Never show modal sync dialogs; status must be a glanceable chip, auto-escalate from live to full-file fingerprint; **never let subtitle speed get persisted from short measurement spans** (v0.7.2 fix); resync button = force a full pass (rule 46); no CLI binaries on-device (rule 12); refuse-don't-guess; ~1s re-lock after seek. → **SHIPPED** (all in engine + validated; fallback envelope upgraded v0.9.11)
- Perf: locks on clean dialogue in ~10–18s of listening; validated 93% lock / 0% false locks on 100-episode corpus. → **VALIDATED (corpus CI `sync_vad_eval` green)**

## 10. DOWNLOADER APP ("Anon Road") — features as demanded (separate repo)
- Canonical monochrome design (pure black/porcelain, hairline borders, white-accent selected states, zero pastels, native geometry everywhere, pill-text inversion rule, per-release UI checklist). → **SHIPPED** (notebook §5 + all 7 complaints FIXED)
- Home: no fragmented sub-genres (Modern/Historical + All/Completed/Ongoing only); no site badges on cards (keep only AIRING/END status); hero cards native; Anime & View More side-by-side row 3; fix AsianC blank posters. → **SHIPPED**
- Downloads screen: 32dp sleek sort bar, minimalist action chips (Pause all/Resume all/**Retry failed**/Cancel all — no pastels), per-queued pause, retry-failed bulk. → **SHIPPED**
- Search: no stale results below 2 chars; 1-char search empty-state; partial-failure banner; quality picker in episode drawer; rapid-tap enqueue guard. (ui_audit) → **SHIPPED**
- Download integrity: hotlink headers to aria2c; HTML-as-video byte sniffing; native aria2c first (no turbo-fallback failures); cross-provider failover; stall/crawl watchdogs; tracker injection (wss); magnet "connecting to swarm" telemetry; OTA dynamic rules; notification retry cleanup; correctness on 0-byte/HTML remnants. (defects notebook + OTA plan) → **SHIPPED**
- Fullscreen cutouts, Log viewer (request), social quality chips parity — status varies, listed in ui_audit.

## 11. THE HONEST OPEN GAPS (what is NOT yet in the app)
1. Drawer **velocity-aware swipe-dismiss** (drag-to-close with spring return).
2. **±10s chips in the dock** (v2 §C + v5 dock mockup — killed in the old purge; spec is clear, implementation pending).
3. **Recently-watched history drawer + Clear History**.
4. **Playlist real drag-reorder** (handle is decorative today).
5. Settings drawer **context header meta line** (ep/duration/decoder line inside the ⋮ drawer as v5 plans) — partially there.
6. Auto-hide timeout **5–6s vs current 3.5s**.
7. Series drill-down polish per hub mockup; Series poster sidecars; Settings search.
8. Screenshot folder naming to `Pictures/Anonrode` (currently `AnonPlayer`).
9. The v6 glass restyle of drawers (this approved interactive prototype) — implementation next round.

_Every item above is typed from the source documents; "how I wanted it" quotes come from the transcripts (U-numbers = user request indices in c5d135bd transcript_full)._
