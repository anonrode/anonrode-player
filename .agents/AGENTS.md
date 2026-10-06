# AGENTS.md — anonrode-player

_Permanent truth document. Read before touching anything. Never delete or replace this file; append to it._

## Operating rules (non-negotiable)

1. **NEVER skip, stub, or defer a deliverable because a local build is impossible.**
   This machine has no Java/Android SDK, so Gradle cannot run here. That is an
   environment fact, NOT a licence to ship placeholder code. "I'll do the rest
   later", "simplified for safety", "I can't verify so I'll skip it", and
   reduced/simplified implementations are all FORBIDDEN. A deliverable is either
   complete and correct, or the work is not done. GitHub Actions CI
   (`.github/workflows/`) is the verification gate — write the complete correct
   code and let CI prove it.
2. **No compromise on deliverables.** Everything requested gets fully built out
   in the same change: full DSP, full persistence, full wiring, full tests.
3. **No AI attribution.** No `Co-Authored-By`, no "Generated with", no model
   names in commit messages. Conventional commits only.
4. **Zero competitor brand names** anywhere — code, comments, docs, UI strings,
   commit messages. Describe behaviour, not who else does it. Tool/library
   attributions (ffmpeg, onnxruntime, ffsubsync-style algorithm references) are
   fine; competing *product* names are not.
5. **Ask before `git push` and before large architectural rewrites.** Commit
   locally is fine; pushing needs explicit confirmation.
6. **Windows git SSL:** always `git -c http.sslBackend=openssl push origin <ref>`.
7. **Keep the sync engine correct above all.** The subtitle-sync engine
   (`core/media/.../sync/`) is validated against real content and a synthetic
   suite. Its validated behaviour — the sign-inversion fix in `evalHalf` /
   `cueHits`, `PASS_BINS` starting at `ELIGIBLE_BINS` (160), the continuous
   9.0→7.0 z-floor, the 200 ms pre-roll deadband, refuse-don't-guess gating —
   is deliberate. Do not "simplify", re-tune, or re-derive it casually.
8. **User typing:** the physical keyboard has broken 'g' and 'h'. "te" = the,
   "tink" = think, "enire" = ensure, "bitton" = button, "mit" = might,
   "tat" = that, "rip it ot" = rip it out. Infer intent; never ask for
   clarification over a missing 'g' or 'h'.
9. **No subagents/teammates unless explicitly asked.** Read files directly.
10. **No dead code.** A file/symbol with zero call sites is a defect — delete it
    rather than leaving a second generation of the same UI around.
11. **Every control must be functional.** A button that only flips a flag
    nothing reads, or only shows a toast, is a defect. Wire it to real,
    persisted behaviour.
12. **Subtitle Synchronization & Native Engine Discipline:**
    - **No CLI Process Execution on Android:** Never use `ProcessBuilder` or shell `exec()` to run binaries from app storage on Android 10+ (API 29+); SELinux enforces $W \oplus X$ (error=13 Permission Denied). Native signal crunching must run in-process via C++/NDK JNI (`AMediaExtractor`, `AMediaCodec`, NEON SIMD).
    - **Asymmetric Lock Authority:** A narrow-window (15s) Live Sync pass must NEVER overwrite or clobber a verified full-file background lock with a discrepancy > 1.0s. Periodic dialogue cadences create harmonic aliases; full-file FFT correlation is the senior authority.
    - **No Deadlock on Unsynced Files:** Never let `alreadyChecked` block background sync if `autoSyncOffsetMs` is null or 0. If a file has no valid lock, background sync MUST run.
    - **User Resync Is Absolute:** When the user taps "Resync now" or "Calibration", the engine must immediately kick off an explicit forced background pass (`force = true`), not merely rearm the passive live loop.
    - **Cue-Guided Anchor Windowing:** Never blindly decode 600s of audio from t=0s. Examine subtitle cues first, seek directly to the first dense dialogue cluster, and decode a 60s–90s window to lock in < 1s.

