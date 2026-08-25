"""Subtitle auto-sync engine (design-phase Python prototype).

Algorithm overview (ffsubsync-style speech-track cross-correlation):

  1. Parse the subtitle file (SRT or VTT) into a list of ``Cue(start, end, text)``.
  2. Build a "synthetic speech signal" at ``target_rate_hz`` (default 50 Hz)
     from those cues: 1.0 inside the union of cue intervals, 0.0 between them.
  3. Compute an energy envelope of the audio track at the same ``target_rate_hz``
     by chunking, taking RMS, smoothing, and normalising to [0, 1].
  4. Cross-correlate the two signals. The lag at the peak is the time shift
     between the subtitles and the speech in the audio.
  5. Refine the peak with a parabolic (quadratic) interpolation for
     sub-sample accuracy.
  6. To detect linear drift, split the timeline into ``num_windows`` segments,
     cross-correlate each, fit a line through the per-window offsets, and
     report both the constant offset and the drift rate (ms / minute).
  7. Apply the shift (and the drift, if any) to every cue and return the
     resynced cues along with a confidence score in [0, 1].

All time units in this module are seconds. All sample rates are Hz.
"""

from __future__ import annotations

import io
import math
import re
from dataclasses import dataclass, field
from typing import Iterable, List, Optional, Sequence, Tuple

import numpy as np
from scipy.signal import correlate


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------


@dataclass
class Cue:
    """A single subtitle cue.

    Attributes:
        start: Cue start time in seconds.
        end:   Cue end time in seconds. Always ``>= start``.
        text:  Raw cue text (may contain line breaks).
    """

    start: float
    end: float
    text: str

    @property
    def duration(self) -> float:
        """Cue duration in seconds."""
        return max(0.0, self.end - self.start)


@dataclass
class SyncResult:
    """Result of a single cross-correlation between speech and audio envelope.

    Attributes:
        offset_s:         Time shift in seconds. Positive means subtitles are
                          late (need to be moved earlier) by that amount.
        confidence:       Peak prominence in [0, 1]. 1 = razor-sharp peak.
        peak_value:       Raw normalised peak value of the cross-correlation.
        second_peak_ratio: Ratio of the second-highest peak to the highest
                          peak. Lower is better (cleaner winner).
    """

    offset_s: float
    confidence: float
    peak_value: float
    second_peak_ratio: float


@dataclass
class DriftResult:
    """Linear drift result fitted across timeline windows.

    Attributes:
        offset_at_zero_s: Constant offset component, in seconds (the line at
                          t=0). Positive = subtitles are late at the start.
        drift_per_s:      Linear drift in seconds per second of timeline.
                          Multiply by 60_000 to get ms per minute.
        confidence:       Confidence in [0, 1] that the line fit is real
                          (based on R^2 of the residuals).
        window_offsets:   List of (window_center_s, offset_s) tuples used
                          for the fit. Useful for debugging.
    """

    offset_at_zero_s: float
    drift_per_s: float
    confidence: float
    window_offsets: List[Tuple[float, float]] = field(default_factory=list)

    @property
    def drift_ms_per_min(self) -> float:
        """Linear drift rate expressed in milliseconds per minute of timeline."""
        return self.drift_per_s * 60_000.0


@dataclass
class FinalSyncResult:
    """End-to-end result of :func:`sync_subtitles`.

    Attributes:
        shifted_cues:     New cue list with offsets and drift applied.
        global_offset_s:  Detected constant offset in seconds.
        drift_per_s:      Detected linear drift in seconds per second.
        drift_ms_per_min: Same drift, expressed in ms/min.
        confidence:       Combined confidence in [0, 1].
        applied:          True if the algorithm actually moved the cues.
                          False means it decided to leave them alone (either
                          because offset is ~0 or confidence is too low).
        notes:            Human-readable notes for the UI / log.
    """

    shifted_cues: List[Cue]
    global_offset_s: float
    drift_per_s: float
    drift_ms_per_min: float
    confidence: float
    applied: bool
    notes: str = ""


# ---------------------------------------------------------------------------
# Parsers
# ---------------------------------------------------------------------------

_SRT_TIME = re.compile(
    r"(?P<h>\d+):(?P<m>\d{2}):(?P<s>\d{2})[,.](?P<ms>\d{3})"
)
_VTT_TIME = re.compile(
    r"(?:(?P<h>\d+):)?(?P<m>\d{2}):(?P<s>\d{2})\.(?P<ms>\d{3})"
)


def _to_seconds(h: str, m: str, s: str, ms: str) -> float:
    return int(h) * 3600 + int(m) * 60 + int(s) + int(ms) / 1000.0


def parse_srt(text: str) -> List[Cue]:
    """Parse a SubRip (``.srt``) string into a list of :class:`Cue`.

    Robust against empty input, malformed blocks, and missing line numbers.
    Out-of-order or overlapping cues are preserved as-is.
    """
    if not text or not text.strip():
        return []

    cues: List[Cue] = []
    # Normalise line endings, then split into blocks on blank lines.
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    blocks = re.split(r"\n\s*\n", text.strip())

    for block in blocks:
        lines = [ln for ln in block.split("\n") if ln.strip() != ""]
        if len(lines) < 2:
            continue
        # Optional numeric index as the first line.
        if lines[0].strip().isdigit() and len(lines) >= 3:
            timing_line = lines[1]
            body_lines = lines[2:]
        else:
            timing_line = lines[0]
            body_lines = lines[1:]

        m = _SRT_TIME.search(timing_line)
        if not m:
            continue
        try:
            start = _to_seconds(m.group("h"), m.group("m"), m.group("s"), m.group("ms"))
        except ValueError:
            continue

        # The end timestamp is everything after the " --> ".
        end_str = timing_line.split("-->", 1)
        if len(end_str) != 2:
            continue
        m2 = _SRT_TIME.search(end_str[1])
        if not m2:
            continue
        try:
            end = _to_seconds(
                m2.group("h"), m2.group("m"), m2.group("s"), m2.group("ms")
            )
        except ValueError:
            continue

        if end < start:
            # Defensive swap; SRT allows this on some encoders.
            start, end = end, start

        body = "\n".join(body_lines).strip()
        if not body:
            continue
        cues.append(Cue(start=start, end=end, text=body))

    return _clean_cues(cues)


def parse_vtt(text: str) -> List[Cue]:
    """Parse a WebVTT (``.vtt``) string into a list of :class:`Cue`.

    The header line ``WEBVTT`` is tolerated and ignored. Cue blocks without
    a timing line are skipped silently.
    """
    if not text or not text.strip():
        return []

    text = text.replace("\r\n", "\n").replace("\r", "\n")
    # Drop the WEBVTT header line.
    if text.lstrip().startswith("WEBVTT"):
        # Remove just the first non-empty line.
        lines = text.split("\n")
        for i, ln in enumerate(lines):
            if ln.strip() != "":
                lines.pop(i)
                break
        text = "\n".join(lines)

    cues: List[Cue] = []
    blocks = re.split(r"\n\s*\n", text.strip())
    for block in blocks:
        lines = [ln for ln in block.split("\n") if ln.strip() != ""]
        if not lines:
            continue
        # Skip pure NOTE / STYLE / REGION blocks.
        head = lines[0].strip()
        if head.startswith(("NOTE", "STYLE", "REGION")):
            continue
        # Find the timing line — first line that contains "-->".
        timing_idx = -1
        for i, ln in enumerate(lines):
            if "-->" in ln:
                timing_idx = i
                break
        if timing_idx < 0:
            continue
        timing_line = lines[timing_idx]
        m = _VTT_TIME.search(timing_line.split("-->", 1)[0])
        m2 = _VTT_TIME.search(timing_line.split("-->", 1)[1])
        if not m or not m2:
            continue
        try:
            start = _to_seconds(
                m.group("h") or "0", m.group("m"), m.group("s"), m.group("ms")
            )
            end = _to_seconds(
                m2.group("h") or "0", m2.group("m"), m2.group("s"), m2.group("ms")
            )
        except ValueError:
            continue
        if end < start:
            start, end = end, start
        body = "\n".join(lines[timing_idx + 1 :]).strip()
        if not body:
            continue
        cues.append(Cue(start=start, end=end, text=body))
    return _clean_cues(cues)


def _clean_cues(cues: Iterable[Cue]) -> List[Cue]:
    """Drop cues with non-finite or negative times and clamp zero-length ones."""
    out: List[Cue] = []
    for c in cues:
        if not (math.isfinite(c.start) and math.isfinite(c.end)):
            continue
        if c.start < 0:
            c.start = 0.0
        if c.end < c.start:
            c.end = c.start
        if not c.text.strip():
            continue
        out.append(c)
    out.sort(key=lambda c: c.start)
    return out


# ---------------------------------------------------------------------------
# Signal builders
# ---------------------------------------------------------------------------


def build_speech_signal(
    cues: Sequence[Cue], duration_s: float, sample_rate_hz: float = 50.0
) -> np.ndarray:
    """Build the synthetic speech-on/off signal.

    The output is a 1-D float array of length
    ``round(duration_s * sample_rate_hz)``. Each sample is 1.0 inside the
    union of cue intervals and 0.0 between them. Overlapping cues are
    handled by the union semantics (so the signal is always a clean 0/1,
    no NaN, no double-counting).

    Args:
        cues:          Subtitle cues (any order, possibly overlapping).
        duration_s:    Total timeline length in seconds.
        sample_rate_hz: Samples per second for the output signal.

    Returns:
        1-D float64 array of 0/1 values.
    """
    if duration_s <= 0 or sample_rate_hz <= 0:
        return np.zeros(0, dtype=np.float64)
    n = int(round(duration_s * sample_rate_hz))
    if n <= 0:
        return np.zeros(0, dtype=np.float64)
    sig = np.zeros(n, dtype=np.float64)
    if not cues:
        return sig
    for c in cues:
        s = max(0.0, c.start)
        e = min(duration_s, c.end)
        if e <= s:
            continue
        i0 = int(math.floor(s * sample_rate_hz))
        i1 = int(math.ceil(e * sample_rate_hz))
        i0 = max(0, min(n, i0))
        i1 = max(i0, min(n, i1))
        sig[i0:i1] = 1.0
    return sig


def build_energy_envelope(
    audio: np.ndarray,
    sample_rate_hz: float,
    target_rate_hz: float = 50.0,
    smooth_ms: float = 40.0,
) -> np.ndarray:
    """Compute the audio energy envelope at ``target_rate_hz``.

    Args:
        audio:          Mono or multi-channel audio as a 1-D or 2-D
                        ``float`` array. Multi-channel audio is mixed down
                        to mono first.
        sample_rate_hz: Sample rate of the input audio in Hz.
        target_rate_hz: Desired output rate in Hz (default 50).
        smooth_ms:      Width of a moving-average smoothing window in ms.

    Returns:
        1-D float64 array of energy values, length
        ``round(audio_len * target_rate_hz / sample_rate_hz)``, normalised
        to [0, 1] (constant zero if audio is silent).
    """
    if audio is None or audio.size == 0 or sample_rate_hz <= 0 or target_rate_hz <= 0:
        return np.zeros(0, dtype=np.float64)

    a = np.asarray(audio, dtype=np.float64)
    if a.ndim == 2:
        # Average channels into mono. This is sufficient for an envelope.
        a = a.mean(axis=1)
    a = a.reshape(-1)

    n = a.shape[0]
    samples_per_frame = max(1, int(round(sample_rate_hz / target_rate_hz)))
    if n < samples_per_frame:
        return np.zeros(0, dtype=np.float64)

    # Trim to a whole number of frames.
    n_frames = n // samples_per_frame
    a = a[: n_frames * samples_per_frame]
    frames = a.reshape(n_frames, samples_per_frame)
    env = np.sqrt(np.mean(frames * frames, axis=1))  # RMS

    # Smooth with a moving average. Window length in target-rate samples.
    win = max(1, int(round(smooth_ms * target_rate_hz / 1000.0)))
    if win > 1 and env.size >= win:
        kernel = np.ones(win, dtype=np.float64) / win
        env = np.convolve(env, kernel, mode="same")

    peak = float(env.max()) if env.size else 0.0
    if peak > 0:
        env = env / peak
    return env


# ---------------------------------------------------------------------------
# Cross-correlation helpers
# ---------------------------------------------------------------------------


def _xcorr_bounded(
    a: np.ndarray, b: np.ndarray, max_lag: int
) -> np.ndarray:
    """Cross-correlation of ``a`` and ``b`` restricted to lags in
    ``[-max_lag, +max_lag]``.

    Uses ``scipy.signal.correlate`` for correctness (linear, not circular).
    The output has length ``2*max_lag+1`` and index ``i`` corresponds to
    lag ``i - max_lag``. A positive lag means ``a`` is shifted right
    relative to ``b``.
    """
    n_a = a.size
    n_b = b.size
    if n_a == 0 or n_b == 0 or max_lag <= 0:
        return np.zeros(0, dtype=np.float64)
    a0 = a.astype(np.float64) - float(a.mean())
    b0 = b.astype(np.float64) - float(b.mean())
    full = correlate(a0, b0, mode="full")
    # Lag at index k is k - (n_b - 1).
    centre = n_b - 1
    lo = centre - max_lag
    hi = centre + max_lag + 1
    if lo < 0:
        full = np.concatenate([np.zeros(-lo, dtype=np.float64), full])
        hi += -lo
        lo = 0
    if hi > full.size:
        full = np.concatenate([full, np.zeros(hi - full.size, dtype=np.float64)])
    return full[lo:hi]


def _second_peak_ratio(values: np.ndarray, peak_idx: int, exclude: int) -> float:
    """Ratio of the second-highest value to the highest, excluding a
    ``+/- exclude`` window around ``peak_idx``.

    Returns a value in ``[0, 1]``. ``1.0`` means the second peak is
    exactly as tall as the main peak (very ambiguous alignment).
    """
    peak_value = float(abs(values[peak_idx]))
    if peak_value <= 1e-12:
        return 1.0
    n = values.size
    lo = max(0, peak_idx - exclude)
    hi = min(n, peak_idx + exclude + 1)
    # Use the absolute values so that we treat negative dips the same.
    abs_vals = np.abs(values)
    abs_vals[lo:hi] = 0.0
    second = float(abs_vals.max()) if abs_vals.size else 0.0
    return min(1.0, second / peak_value)


def _parabolic_offset(y: np.ndarray, idx: int) -> float:
    """Sub-sample peak offset around ``idx`` using a 3-point quadratic fit.

    Returns the fractional offset in samples (positive = peak is to the
    right of ``idx``). Caller must ensure ``0 < idx < len(y) - 1``.
    """
    y0 = float(y[idx - 1])
    y1 = float(y[idx])
    y2 = float(y[idx + 1])
    denom = (y0 - 2.0 * y1 + y2)
    if abs(denom) < 1e-12:
        return 0.0
    # Standard 3-point quadratic vertex formula.
    return 0.5 * (y0 - y2) / denom


def _bounded_xcorr_peak(
    speech: np.ndarray,
    audio_env: np.ndarray,
    sample_rate_hz: float,
    max_shift_s: float,
) -> SyncResult:
    """Internal: bounded cross-correlation between ``speech`` and
    ``audio_env``. Returns the best offset and a confidence score.

    Positive ``offset_s`` means the speech signal is later than the audio
    (subtitles are delayed) — caller shifts the cues back by that amount.
    """
    n = min(speech.size, audio_env.size)
    if n <= 4 or sample_rate_hz <= 0 or max_shift_s <= 0:
        return SyncResult(
            offset_s=0.0,
            confidence=0.0,
            peak_value=0.0,
            second_peak_ratio=1.0,
        )
    a = speech[:n].astype(np.float64)
    b = audio_env[:n].astype(np.float64)
    max_lag = int(round(max_shift_s * sample_rate_hz))
    max_lag = min(max_lag, n - 1)
    if max_lag < 1:
        return SyncResult(
            offset_s=0.0,
            confidence=0.0,
            peak_value=0.0,
            second_peak_ratio=1.0,
        )

    corr = _xcorr_bounded(a, b, max_lag)
    if corr.size == 0:
        return SyncResult(
            offset_s=0.0,
            confidence=0.0,
            peak_value=0.0,
            second_peak_ratio=1.0,
        )

    peak_idx = int(np.argmax(corr))
    # Convert array index to lag in samples.
    lag_samples = peak_idx - max_lag
    peak_value = float(corr[peak_idx])

    # Parabolic sub-sample refinement, only if we have neighbours.
    if 0 < peak_idx < corr.size - 1:
        frac = _parabolic_offset(corr, peak_idx)
        # Clamp the fraction to a sensible range so a noisy edge case
        # cannot push the offset by a full lag.
        frac = max(-0.5, min(0.5, frac))
        lag_frac = lag_samples + frac
    else:
        lag_frac = float(lag_samples)

    offset_s = lag_frac / sample_rate_hz

    # Exclude a +/- 50-sample (1 s) window when looking for the second peak.
    second_ratio = _second_peak_ratio(corr, peak_idx, exclude=50)

    # Confidence: combine peak prominence with a "speech density" factor
    # and an "agreement between main peak and any nearby peaks" factor.
    prominence = max(0.0, min(1.0, 1.0 - second_ratio))
    speech_density = float(a.mean())
    if speech_density < 0.02:
        prominence *= 0.25
    confidence = float(max(0.0, min(1.0, prominence)))

    return SyncResult(
        offset_s=float(offset_s),
        confidence=confidence,
        peak_value=peak_value,
        second_peak_ratio=float(second_ratio),
    )


def find_global_offset(
    speech: np.ndarray,
    audio_env: np.ndarray,
    sample_rate_hz: float = 50.0,
    max_shift_s: float = 600.0,
) -> SyncResult:
    """Find the global time offset between subtitle speech and audio.

    A positive ``offset_s`` means the subtitle signal is later than the
    audio (subtitles are delayed). To bring them in sync, the subtitle
    cues need to be shifted earlier by ``offset_s`` seconds.
    """
    return _bounded_xcorr_peak(
        speech, audio_env, sample_rate_hz, max_shift_s
    )


# ---------------------------------------------------------------------------
# Drift detection
# ---------------------------------------------------------------------------


def find_drift(
    speech: np.ndarray,
    audio_env: np.ndarray,
    sample_rate_hz: float = 50.0,
    num_windows: int = 8,
    max_shift_s: float = 600.0,
) -> DriftResult:
    """Estimate linear drift by correlating in N timeline windows.

    Each window is correlated independently; the resulting per-window
    offsets are then fit with a straight line using least squares.
    """
    n = min(speech.size, audio_env.size)
    if n <= 0 or num_windows < 2:
        return DriftResult(
            offset_at_zero_s=0.0,
            drift_per_s=0.0,
            confidence=0.0,
            window_offsets=[],
        )

    window_len = n // num_windows
    if window_len < 8:
        return DriftResult(
            offset_at_zero_s=0.0,
            drift_per_s=0.0,
            confidence=0.0,
            window_offsets=[],
        )

    max_lag = int(round(max_shift_s * sample_rate_hz))
    points: List[Tuple[float, float]] = []

    for w in range(num_windows):
        i0 = w * window_len
        i1 = n if w == num_windows - 1 else (w + 1) * window_len
        seg_speech = speech[i0:i1].astype(np.float64)
        seg_audio = audio_env[i0:i1].astype(np.float64)
        if seg_speech.size < 8 or seg_audio.size < 8:
            continue
        # Require some speech in the window to even try.
        if float(seg_speech.mean()) < 0.01:
            continue
        # The per-window max shift can be smaller than the global one to
        # keep the FFT small and to reject wildly drifting windows.
        window_max_shift_s = min(max_shift_s, max(30.0, (i1 - i0) / sample_rate_hz / 4.0))
        result = _bounded_xcorr_peak(
            seg_speech, seg_audio, sample_rate_hz, window_max_shift_s
        )
        offset_s = result.offset_s
        # Reject windows where the second peak is too close to the first.
        if result.second_peak_ratio > 0.85:
            continue
        # Reject windows whose offset is at the edge of the search range
        # (suggests the true peak is outside the window).
        if abs(offset_s) >= window_max_shift_s * 0.95:
            continue
        centre_time = (i0 + (i1 - i0) / 2.0) / sample_rate_hz
        points.append((centre_time, float(offset_s)))

    if len(points) < 2:
        return DriftResult(
            offset_at_zero_s=points[0][1] if points else 0.0,
            drift_per_s=0.0,
            confidence=0.0,
            window_offsets=points,
        )

    # Ordinary least squares line fit: offset = a + b * t
    ts = np.array([p[0] for p in points], dtype=np.float64)
    ys = np.array([p[1] for p in points], dtype=np.float64)
    t_mean = ts.mean()
    y_mean = ys.mean()
    denom = float(np.sum((ts - t_mean) ** 2))
    if denom < 1e-9:
        return DriftResult(
            offset_at_zero_s=float(y_mean),
            drift_per_s=0.0,
            confidence=0.0,
            window_offsets=points,
        )
    b = float(np.sum((ts - t_mean) * (ys - y_mean)) / denom)
    a = float(y_mean - b * t_mean)

    # R^2 as a confidence measure.
    y_hat = a + b * ts
    ss_res = float(np.sum((ys - y_hat) ** 2))
    ss_tot = float(np.sum((ys - y_mean) ** 2))
    r2 = 1.0 - ss_res / ss_tot if ss_tot > 1e-9 else 0.0
    r2 = max(0.0, min(1.0, r2))

    return DriftResult(
        offset_at_zero_s=a,
        drift_per_s=b,
        confidence=r2,
        window_offsets=points,
    )


# ---------------------------------------------------------------------------
# Top-level entry point
# ---------------------------------------------------------------------------


def _apply_drift_to_cues(
    cues: Sequence[Cue],
    offset_s: float,
    drift_per_s: float,
    total_duration_s: float,
) -> List[Cue]:
    """Apply a constant offset plus a linear drift to every cue.

    The shift applied to a cue starting at time ``t`` is
    ``offset_s + drift_per_s * t``. We then re-sort and clip negative
    starts to 0.
    """
    out: List[Cue] = []
    for c in cues:
        new_start = c.start - (offset_s + drift_per_s * c.start)
        new_end = c.end - (offset_s + drift_per_s * c.end)
        if new_end < 0:
            continue  # shifted entirely off the start of the timeline
        if new_start < 0:
            new_start = 0.0
        if new_end - new_start < 0.01:
            # Less than 10 ms — discard the cue.
            continue
        if total_duration_s > 0 and new_start >= total_duration_s:
            continue
        out.append(Cue(start=float(new_start), end=float(new_end), text=c.text))
    out.sort(key=lambda c: c.start)
    return out


def sync_subtitles(
    cues: Sequence[Cue],
    audio: np.ndarray,
    sample_rate_hz: float,
    target_rate_hz: float = 50.0,
    total_duration_s: Optional[float] = None,
    confidence_threshold: float = 0.25,
    drift_threshold_ms_per_min: float = 5.0,
    num_windows: int = 8,
) -> FinalSyncResult:
    """End-to-end subtitle sync.

    Args:
        cues:                  Subtitle cues.
        audio:                 Audio samples (mono or multi-channel).
        sample_rate_hz:        Audio sample rate in Hz.
        target_rate_hz:        Working sample rate for the speech / audio
                               envelope signals.
        total_duration_s:      Timeline length in seconds. Defaults to the
                               last cue's end or the audio length.
        confidence_threshold:  Below this, no shift is applied.
        drift_threshold_ms_per_min: Below this absolute drift, no drift is
                               applied (only the global offset).
        num_windows:           Number of windows for the drift fit.

    Returns:
        :class:`FinalSyncResult` with the resynced cues and diagnostics.
    """
    # Resolve the timeline length.
    audio_len_s = 0.0
    if audio is not None and audio.size:
        a = np.asarray(audio)
        n_samples = a.shape[0] if a.ndim == 1 else a.shape[0]
        audio_len_s = n_samples / float(sample_rate_hz) if sample_rate_hz > 0 else 0.0
    last_cue_end = max((c.end for c in cues), default=0.0)
    if total_duration_s is None:
        total_duration_s = max(audio_len_s, last_cue_end)
    total_duration_s = max(total_duration_s, 0.0)

    if not cues:
        return FinalSyncResult(
            shifted_cues=[],
            global_offset_s=0.0,
            drift_per_s=0.0,
            drift_ms_per_min=0.0,
            confidence=0.0,
            applied=False,
            notes="empty subtitle file",
        )

    # Build signals at the same rate.
    speech = build_speech_signal(cues, total_duration_s, target_rate_hz)
    env = build_energy_envelope(audio, sample_rate_hz, target_rate_hz)
    # The audio envelope may be shorter than the speech signal if the audio
    # ends before the last cue. Pad with zeros so that cross-correlation
    # still has a defined peak.
    n = max(speech.size, env.size)
    if speech.size < n:
        speech = np.concatenate(
            [speech, np.zeros(n - speech.size, dtype=np.float64)]
        )
    if env.size < n:
        env = np.concatenate([env, np.zeros(n - env.size, dtype=np.float64)])

    # Global cross-correlation.
    global_res = find_global_offset(speech, env, target_rate_hz)

    # If global confidence is too low, give up.
    if global_res.confidence < confidence_threshold:
        return FinalSyncResult(
            shifted_cues=list(cues),
            global_offset_s=0.0,
            drift_per_s=0.0,
            drift_ms_per_min=0.0,
            confidence=global_res.confidence,
            applied=False,
            notes=(
                f"low confidence ({global_res.confidence:.3f}); "
                "leaving subtitles unchanged"
            ),
        )

    # Drift detection.
    drift = find_drift(
        speech, env, target_rate_hz, num_windows=num_windows
    )
    # Decide the constant offset. If we have at least two valid per-window
    # offsets, use the line-fit intercept (which is more robust than the
    # global argmax). Otherwise, fall back to the global result.
    if len(drift.window_offsets) >= 2:
        offset_s = drift.offset_at_zero_s
    else:
        offset_s = global_res.offset_s

    # Only apply drift if it is both non-trivial and well-supported.
    drift_per_s = drift.drift_per_s if len(drift.window_offsets) >= 2 else 0.0
    drift_ms_per_min = drift_per_s * 60_000.0
    if (
        abs(drift_ms_per_min) < drift_threshold_ms_per_min
        or drift.confidence < 0.5
    ):
        drift_per_s = 0.0
        drift_ms_per_min = 0.0

    # If both the global and the windowed result agree we're at zero shift,
    # don't bother re-emitting the cues.
    if abs(offset_s) < 0.02 and abs(drift_per_s) < 1e-6:
        return FinalSyncResult(
            shifted_cues=list(cues),
            global_offset_s=0.0,
            drift_per_s=0.0,
            drift_ms_per_min=0.0,
            confidence=global_res.confidence,
            applied=False,
            notes="already in sync (offset < 20 ms, no drift)",
        )

    shifted = _apply_drift_to_cues(cues, offset_s, drift_per_s, total_duration_s)
    # Combined confidence: blend the global peak confidence with the drift R^2.
    combined = 0.7 * global_res.confidence + 0.3 * drift.confidence
    return FinalSyncResult(
        shifted_cues=shifted,
        global_offset_s=float(offset_s),
        drift_per_s=float(drift_per_s),
        drift_ms_per_min=float(drift_ms_per_min),
        confidence=float(combined),
        applied=True,
        notes=(
            f"offset={offset_s:.3f}s drift={drift_ms_per_min:.2f}ms/min "
            f"confidence={combined:.3f}"
        ),
    )


# ---------------------------------------------------------------------------
# Helpers used by tests
# ---------------------------------------------------------------------------


def shift_cues(cues: Sequence[Cue], offset_s: float) -> List[Cue]:
    """Apply a constant time shift to every cue. Used by tests to set up."""
    return [
        Cue(start=max(0.0, c.start + offset_s), end=c.end + offset_s, text=c.text)
        for c in cues
    ]


def synthetic_audio_with_speech(
    duration_s: float,
    sample_rate_hz: int,
    cues: Sequence[Cue],
    noise_floor: float = 0.0,
    lead_silence_s: float = 0.0,
    speech_amplitude: float = 1.0,
) -> np.ndarray:
    """Generate a synthetic audio signal that "speaks" during the given cues.

    - Inside each cue, the audio is white noise scaled to ``speech_amplitude``.
    - Outside cues, the audio is white noise at ``noise_floor`` (or silence
      if ``noise_floor == 0``).
    - A lead-in silence of ``lead_silence_s`` is added at the start to test
      the "music/silence at the start" edge case.
    """
    n = int(round(duration_s * sample_rate_hz))
    if n <= 0:
        return np.zeros(0, dtype=np.float64)
    rng = np.random.default_rng(42)  # deterministic across runs
    audio = rng.normal(0.0, max(noise_floor, 1e-6), size=n).astype(np.float64)
    # A small low-frequency carrier inside speech to make the energy envelope
    # look more "voice-like" than pure white noise.
    t = np.arange(n, dtype=np.float64) / float(sample_rate_hz)
    for c in cues:
        s = max(0.0, c.start)
        e = min(duration_s, c.end)
        if e <= s:
            continue
        i0 = int(round(s * sample_rate_hz))
        i1 = int(round(e * sample_rate_hz))
        if i0 >= n or i1 <= 0:
            continue
        i0 = max(0, i0)
        i1 = min(n, i1)
        seg_t = t[i0:i1] - s
        # Sum of two low-frequency tones modulated by an envelope, which
        # gives a non-flat RMS even when noise is added on top.
        carrier = (
            np.sin(2 * np.pi * 180.0 * seg_t)
            + 0.6 * np.sin(2 * np.pi * 320.0 * seg_t)
        ) * (speech_amplitude * 0.5)
        # Replace audio inside the cue with the carrier + a bit of noise.
        audio[i0:i1] = carrier + rng.normal(
            0.0, max(noise_floor, 1e-3), size=i1 - i0
        )
    if lead_silence_s > 0:
        i0 = 0
        i1 = min(n, int(round(lead_silence_s * sample_rate_hz)))
        audio[i0:i1] = 0.0
    return audio


def cues_from_intervals(
    intervals: Sequence[Tuple[float, float]], text: str = "x"
) -> List[Cue]:
    """Make a list of :class:`Cue` from a sequence of (start, end) tuples."""
    return [Cue(start=s, end=e, text=text) for s, e in intervals]
