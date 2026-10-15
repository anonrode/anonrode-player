# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

The owner (primary user, sole developer and reviewer) plus privacy-minded local-media watchers who keep their own film and series libraries on-device. Two standing personas from the master spec: Persona A — the everyday binge-watcher (resume, next-episode, simple controls); Persona B — the power quality freak (verifies decoder truth, sync offset, per-show aspect, screenshot fidelity; treats the app as an instrument).

## Product Purpose

A local video player for phone and tablet whose flagship is subtitle synchronization: sidecar or embedded subtitles are locked to the video's real timing (full-file fingerprint correlation, sub-second) so every line arrives when it should. Success = the user stops thinking about subtitles at all.

## Positioning

The sync engine's validated mechanism — full-file FFT correlation with refuse-don't-guess gating, a verified-lock authority a narrow live-sync window may never clobber, and an honesty law: every surface states only what the engine actually reports. No competitor product is referenced anywhere (AGENTS rule 4). The user's standing directive: **subtitle sync is "the life of the app"** — identity, motion, and every journey organize around it.

## Operating Context

Solo-developer workflow: no local JDK/SDK/emulator — GitHub Actions CI (Roborazzi renders of the real Compose player screens, `android_build` APKs) is the build gate; the owner's phone recordings are the final motion/feel check. `AGENTS.md` rules 1–16 and `docs/design/FINAL_DESIGN_HUB.md` §0 laws are binding. Windows dev machine; UI language English; single-user personal build shipped from a public repo.

## Capabilities and Constraints

Compose + Media3; local media via MediaStore/SAF; PiP; background audio; gesture layer (double-tap seek, hold-to-speed, vertical volume/brightness); three glass drawers (subtitle/playlist/settings); resume; episode queue; Up-Next; sleep timer; A-B repeat; PixelCopy screenshots; skins (Obsidian/Light/Cobalt/Amber). Constraints: the sync engine (`core/media/.../sync/`) is untouchable (rules 7/12/14); zero-blur glass law; no accounts, no cloud, no network dependency; every control functional (rule 11); no dead code (rule 10).

## Brand Commitments

Name: **Anonrode**. No competitor product names (rule 4); no AI attribution or generation strings (rule 3). Pinned visual world (user sign-off, hub §0): 0.96-alpha near-black glass, zero blur, hairline white 8%, emerald `#3DDBA8` as state-only, tnum numerals, press 0.96, 260–300 ms glides on `cubic-bezier(.16,1,.3,1)`, velocity swipe-dismiss. The reimagination expands this world app-wide; the converged player chrome itself stays untouched (user answer this session). What else must survive was delegated to designer judgment.

## Evidence on Hand

`docs/design/FINAL_DESIGN_HUB.md` (§0 laws; §15 convergence loop with render verdicts); `scratch/proposed_black_glass_player.html` (the approved 10-frame mockup); Roborazzi artifacts `player-screens-*`; owner's phone recordings under `tools/_screen_ref/` and `~/Downloads`. No testimonials, benchmarks, press, or marketing claims exist — future work must not fabricate any.

## Product Principles

1. **Sync is the life.** Every surface tells the engine's true story; the lock moment is the product's hero.
2. **Honest state, always.** Nothing decorative pretends — refuse-don't-guess extends from the engine to the UI.
3. **Cinema-first calm.** Black glass, zero blur, accent only as state; the video is the only bright thing on screen.
4. **Numerals are jewelry.** tnum, precise, glanceable — offset, times, durations, percentages.
5. **One thumb, no dead ends.** ≥48 dp targets, system Back always works, no modal mazes.

## Accessibility & Inclusion

Android platform rules are binding: predictive/gesture Back, edge-to-edge window insets, 48 dp targets with ≥8 dp separation, sp type that follows the system font scale, dark-first design, system remove-animations honored with crossfade/instant cuts. Controls carry content descriptions; wording stays plain.
