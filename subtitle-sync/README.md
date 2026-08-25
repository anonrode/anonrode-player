# Subtitle Auto-Sync Engine (design phase)

This is the design-phase Python prototype of the ANONRODE PLAYER subtitle
auto-sync engine. It is a standalone, importable module that implements the
ffsubsync-style speech-track cross-correlation algorithm. The same
algorithm ports directly to Kotlin later.

The Android player (Kotlin + Jetpack Compose + Media3 + nextlib FFmpeg
decoding) supplies the audio track. In the production build, the audio
energy envelope will be computed from PCM via a `Media3.AudioProcessor`.
Here, the prototype accepts a numpy array of audio samples and does the
same job.

## Algorithm

1. Parse the SRT or VTT into a list of `Cue(start, end, text)`.
2. Build a synthetic "speech on/off" signal at 50 Hz (1.0 inside the union
   of cues, 0.0 between them). Overlapping cues collapse into a single
   on-region.
3. Compute the audio energy envelope at the same 50 Hz by chunking into
   20 ms frames, taking RMS, smoothing with a 40 ms moving average, and
   normalising to [0, 1].
4. Cross-correlate the two signals. The integer lag of the peak is the
   time shift between subtitles and speech. A positive lag means the
   subtitles are late.
5. Refine the peak with a 3-point quadratic interpolation for sub-frame
   accuracy.
6. To detect linear drift, split the timeline into 8 windows,
   cross-correlate each, fit a straight line through the per-window
   offsets with ordinary least squares, and report both the constant
   offset (line at t=0) and the drift rate (ms/minute).
7. Apply the offset and the drift to every cue, re-sort, and drop any
   that ended up with non-positive duration.
8. Confidence is computed from the peak prominence
   `1 - second_peak_ratio`, blended with the R^2 of the drift line fit.
   If confidence is below 0.25 the algorithm leaves the cues alone
   rather than risk a wrong sync.

## Edge cases handled

- Empty / malformed SRT or VTT blocks are skipped without crashing.
- The audio length is independent of the last cue's end; signals are
  zero-padded to the same length.
- Overlapping or back-to-back cues become a clean 0/1 union — no NaN,
  no double counting.
- Silence or music (low or no audio energy) is handled by falling back
  to "no shift applied" when confidence is too low.
- Zero shift (already in sync) is detected and the cues are returned
  unchanged.
- Negative and positive shifts are both supported.
- Linear drift is detected via per-window cross-correlation and a
  line fit.
- All-zero (silent) audio does not crash and does not apply a shift.
- The algorithm is fully deterministic — same inputs always produce
  the same outputs.

## Usage

```python
import numpy as np
from engine import (
    parse_srt, sync_subtitles,
)

with open("movie.srt", "r", encoding="utf-8") as f:
    cues = parse_srt(f.read())

# `audio` is a mono numpy array of float64 samples.
# `sample_rate_hz` is the audio sample rate (e.g. 48000).
result = sync_subtitles(cues, audio, sample_rate_hz=48000)

if result.applied:
    print(f"shifted by {result.global_offset_s:+.3f}s, "
          f"drift {result.drift_ms_per_min:+.2f} ms/min, "
          f"confidence {result.confidence:.2f}")
    for cue in result.shifted_cues:
        print(f"{cue.start:7.2f} -> {cue.end:7.2f}  {cue.text!r}")
else:
    print(f"no sync applied: {result.notes}")
```

## Running the tests

The test harness is self-contained and has no `pytest` dependency.

```bash
cd subtitle-sync
pip install -r requirements.txt
python test_engine.py
```

The whole suite should run in a few seconds and exit 0. Each test prints
`PASS` or `FAIL` with the expected vs actual values.

## What gets tested

- Zero shift (already in sync): high confidence, no shift applied.
- Positive shift (subtitles late by 2.5 s): recovered to within 50 ms.
- Negative shift (subtitles early by 1.7 s): recovered to within 50 ms.
- Linear drift (0 s to +4 s over 20 minutes): drift direction and
  magnitude detected.
- 30 s of silence at the start of the audio: algorithm still finds the
  right peak.
- Overlapping cues: union semantics, correct recovery.
- Empty SRT: parser returns `[]`, sync is a no-op.
- Single cue: no crash, low-confidence no-op.
- 60 minutes / ~1200 cues: runs in under 5 s.
- Determinism: same input twice yields identical output.
- Noisy audio: peak still well above the second peak.
- 1-frame offset (33 ms): detected within 60 ms.
- SRT / VTT parsers produce equivalent results on the same content.
- Malformed input blocks are skipped, not crashing the parser.
- All-silent audio does not apply a shift.
- Drift detection: per-window offsets lie on a line near the true
  drift.

## Porting notes for Kotlin

- Replace `numpy.ndarray` with a primitive `FloatArray` and a
  hand-rolled energy-envelope loop. The chunk size is
  `round(sample_rate_hz / 50)` samples per 20 ms frame.
- Replace `scipy.signal.correlate` with a direct O(N*M) cross-correlation
  in the bounded lag window (max shift is 600 s by default → 30 000
  samples at 50 Hz, fine to do in Kotlin on a background thread).
- The parabolic peak refinement and the OLS drift fit are straightforward
  to translate: the formulas are all in this file.
- Keep the working sample rate at 50 Hz — it's the right trade-off
  between resolution (20 ms) and CPU.

## License

GPL-3.0 (matches the rest of the project).
