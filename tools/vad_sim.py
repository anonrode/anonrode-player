#!/usr/bin/env python3
"""
VAD simulation — validates the new vocal-band onset VAD against
the current energy+variance+ZCR VAD on synthetic audio.

The new VAD is what `AudioSyncProcessor.analyze()` should produce after
the rewrite. This script lets us iterate on the algorithm offline
before touching the Kotlin.

Pipeline under test (per 10ms window of mono 16kHz PCM):
  1. Biquad band-pass 300–3400 Hz
  2. Rectify (abs)
  3. Asymmetric envelope follower (50ms attack, 500ms decay)
  4. Onset strength = positive derivative of envelope
  5. Per-window soft score = clamp(peak_onset * K, 0, 1)

We compare NEW vs OLD on the same signal. The OLD is the current
inline math in `AudioSyncProcessor.analyze()`.

Run:  python3 tools/vad_sim.py
"""
import math
import random
import sys
import wave
import struct

SAMPLE_RATE = 16000
WIN_MS = 10
WIN = SAMPLE_RATE * WIN_MS // 1000   # 160 samples

# ─────────────────────────────────────────────────────────────────────
# Biquad band-pass (RBJ cookbook), Butterworth Q
# ─────────────────────────────────────────────────────────────────────
def biquad_bandpass_coeffs(sr, f_low, f_high, q=0.707):
    """Return (b0,b1,b2,a1,a2) for a 2nd-order Butterworth band-pass.
    Implementation = cascade of high-pass at f_low and low-pass at f_high."""
    def lp(fc):
        w0 = 2 * math.pi * fc / sr
        cw = math.cos(w0); sw = math.sin(w0); alpha = sw / (2 * q)
        b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
        a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
        return (b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    def hp(fc):
        w0 = 2 * math.pi * fc / sr
        cw = math.cos(w0); sw = math.sin(w0); alpha = sw / (2 * q)
        b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
        a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
        return (b0/a0, b1/a0, b2/a0, a1/a0, a2/a0)
    blp = lp(f_high)
    bhp = hp(f_low)
    # Cascade: apply HP first, then LP (per-sample)
    # Returns tuple of both stages' coeffs
    return bhp, blp

def biquad_apply(x, c, state):
    """Apply one biquad stage. state is [x1, x2, y1, y2]."""
    b0,b1,b2,a1,a2 = c
    x1,x2,y1,y2 = state
    y = b0*x + b1*x1 + b2*x2 - a1*y1 - a2*y2
    state[0] = x; state[1] = x1; state[2] = y; state[3] = y1
    return y

# ─────────────────────────────────────────────────────────────────────
# NEW VAD — biquad + envelope + onset
# ─────────────────────────────────────────────────────────────────────
def new_vad_window(chunk, coeffs, env_prev, state_hp, state_lp, decay_tc=0.5, gain=8.0):
    """Process one 10ms window. Returns (score, new_env, new_state_hp, new_state_lp).
    `chunk` is exactly WIN samples. State is [x1, x2, y1, y2] per biquad stage."""
    hp_c, lp_c = coeffs
    s_hp = list(state_hp)
    s_lp = list(state_lp)

    env = env_prev
    peak_onset = 0.0
    dt = WIN_MS / 1000.0
    decay = math.exp(-dt / decay_tc)

    for s in chunk:
        y_hp = biquad_apply(s, hp_c, s_hp)
        y_lp = biquad_apply(y_hp, lp_c, s_lp)
        rect = abs(y_lp)
        env = max(rect, env * decay)
        onset = max(0.0, env - env_prev)
        env_prev = env
        if onset > peak_onset:
            peak_onset = onset

    score = min(1.0, max(0.0, peak_onset * gain))
    return score, env_prev, s_hp, s_lp

def new_vad_full(pcm, coeffs, gain=8.0):
    """Returns list of per-window soft scores in [0,1]."""
    n_win = len(pcm) // WIN
    env_prev = 0.0
    state_hp = [0.0, 0.0, 0.0, 0.0]
    state_lp = [0.0, 0.0, 0.0, 0.0]
    scores = []
    for w in range(n_win):
        chunk = pcm[w*WIN:(w+1)*WIN]
        score, env_prev, state_hp, state_lp = new_vad_window(chunk, coeffs, env_prev, state_hp, state_lp, gain=gain)
        scores.append(score)
    return scores

# ─────────────────────────────────────────────────────────────────────
# OLD VAD — energy + variance + ZCR (port of AudioSyncProcessor.analyze)
# ─────────────────────────────────────────────────────────────────────
def old_vad_full(pcm):
    n_win = len(pcm) // WIN
    scores = []
    floor = 0.0
    peak = 0.0
    last = 0.0
    for w in range(n_win):
        chunk = pcm[w*WIN:(w+1)*WIN]
        if len(chunk) == 0: break
        rms = math.sqrt(sum(s*s for s in chunk) / len(chunk))
        # variance
        mean_abs = sum(abs(s) for s in chunk) / len(chunk)
        var = sum((s - mean_abs) ** 2 for s in chunk) / max(mean_abs * mean_abs, 1.0) / len(chunk)
        # ZCR
        zcr = sum(1 for i in range(1, len(chunk)) if (chunk[i] >= 0) != (chunk[i-1] >= 0)) / len(chunk)
        if floor == 0.0:
            floor = rms; peak = rms * 1.9 + 0.0001
        else:
            floor = floor * 0.986 + min(rms, floor * 1.45) * 0.014
            peak = max(floor + 0.00035, max(peak * 0.992, rms))
        uf = floor * 1.08
        up = max(uf + 0.0012, peak)
        e_score = max(0.0, min(1.0, (rms - uf) / max(up - uf, 0.001)))
        v_score = min(var / 2.0, 1.0)
        z_score = 1.0 if 0.02 <= zcr <= 0.15 else (0.5 if zcr < 0.02 else max(0.0, 1.0 - (zcr - 0.15) / 0.2))
        sp = max(0.0, min(1.0, e_score * 0.5 + v_score * 0.3 + z_score * 0.2))
        last = sp * 0.72 + last * 0.28
        scores.append(last)
    return scores

# ─────────────────────────────────────────────────────────────────────
# Synthetic signal builder
# ─────────────────────────────────────────────────────────────────────
def synth(seconds=60, sr=SAMPLE_RATE, music_amp=0.18, music_vocal_amp=0.10, speech_burst_amp=0.55, speech_floor_amp=0.10):
    """60s layout:
       0-10s   : silence
       10-30s  : low-frequency music floor ONLY (no vocal content) — the hard case
       30-50s  : low-frequency music + continuous vocal-band tone (sustained singing) + 80ms louder bursts every 0.5s
       50-60s  : silence

    The onsets are the START of each louder burst, not the start of the sustained vocal tone.
    """
    n = seconds * sr
    pcm = [0.0] * n
    onsets = []

    music_freqs = [110.0, 220.0, 330.0]  # low — below 300Hz, will be attenuated by the band-pass
    vocal_freqs = [400.0, 800.0, 1600.0]  # in vocal band
    lp_state = 0.0
    lp_alpha = 0.05

    for i in range(n):
        t = i / sr
        # Low-frequency music: 10-50s
        if 10.0 <= t < 50.0:
            music = sum(math.sin(2 * math.pi * f * t) for f in music_freqs) / len(music_freqs)
            lp_state = lp_state * (1 - lp_alpha) + music * lp_alpha
            pcm[i] += lp_state * music_amp
        # Sustained vocal-band tone during 30-50s (mimics singing under music)
        if 30.0 <= t < 50.0:
            vocal = sum(math.sin(2 * math.pi * f * t) for f in vocal_freqs) / len(vocal_freqs)
            pcm[i] += vocal * speech_floor_amp
        # Speech onset bursts at every 0.5s in 30-50s
        if 30.0 <= t < 50.0:
            t_beat = round(t * 2) / 2.0
            if abs(t - t_beat) < (0.5 / sr):
                onsets.append(t_beat)
                burst_n = int(0.08 * sr)
                for j in range(burst_n):
                    idx = i + j
                    if idx < n:
                        pcm[idx] += speech_burst_amp * (
                            0.4 * math.sin(2 * math.pi * 500 * (idx/sr)) +
                            0.3 * math.sin(2 * math.pi * 1000 * (idx/sr)) +
                            0.3 * math.sin(2 * math.pi * 2000 * (idx/sr)) +
                            0.1 * (random.random() - 0.5)
                        )
    return pcm, onsets

def find_onsets_from_scores(scores, threshold=0.3, min_gap_s=0.3):
    """Return list of onset times in seconds where score crosses above threshold."""
    onsets = []
    last_t = -100.0
    for w, s in enumerate(scores):
        t = w * WIN_MS / 1000.0
        if s >= threshold and (t - last_t) > min_gap_s:
            onsets.append(t)
            last_t = t
    return onsets

def match_onsets(detected, ground_truth, tolerance_s=0.1, edge_buffer_s=0.5):
    """For each ground-truth onset, is there a detected one within ±tolerance?
    Excludes detected onsets within edge_buffer_s of the section boundary
    (these are the unavoidable 'music to speech' transitions).
    """
    matched_gt = 0
    for gt in ground_truth:
        if any(abs(d - gt) <= tolerance_s for d in detected):
            matched_gt += 1
    # Detected in speech region, excluding the music→speech edge
    speech_det = [d for d in detected
                  if (30.0 + edge_buffer_s) <= d <= (50.0 - edge_buffer_s)]
    matched_det = sum(1 for d in speech_det
                      if any(abs(gt - d) <= tolerance_s for gt in ground_truth))
    false_pos_speech = len(speech_det) - matched_det
    music_only_det = [d for d in detected if 10.0 <= d < 30.0]
    recall = matched_gt / max(1, len(ground_truth))
    precision = matched_det / max(1, len(speech_det))
    return recall, precision, false_pos_speech, len(music_only_det)

# ─────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────
def main():
    print("=== VAD SIM: new (vocal-band onset) vs old (energy+variance+ZCR) ===")
    random.seed(42)
    pcm, gt_onsets = synth()
    print(f"Synth: {len(pcm)} samples, {len(gt_onsets)} ground-truth speech onsets (30-50s, every 0.5s)")
    print(f"  Music-only section 10-30s: NO speech should be detected here.")
    coeffs = biquad_bandpass_coeffs(SAMPLE_RATE, 300, 3400, q=0.707)

    new_scores = new_vad_full(pcm, coeffs, gain=8.0)
    old_scores = old_vad_full(pcm)

    new_onsets = find_onsets_from_scores(new_scores, threshold=0.3, min_gap_s=0.3)
    old_onsets = find_onsets_from_scores(old_scores, threshold=0.3, min_gap_s=0.3)

    nr, np_, nfp, nfp_music = match_onsets(new_onsets, gt_onsets)
    oR, op_, ofp, ofp_music = match_onsets(old_onsets, gt_onsets)
    print(f"NEW VAD: {len(new_onsets)} onsets, recall={nr:.2f}, precision={np_:.2f}, "
          f"FP-in-speech={nfp}, FP-in-music-only={nfp_music}")
    print(f"OLD VAD: {len(old_onsets)} onsets, recall={oR:.2f}, precision={op_:.2f}, "
          f"FP-in-speech={ofp}, FP-in-music-only={ofp_music}")

    # Pass: new is no worse on recall AND strictly fewer music-only FPs
    recall_ok = nr >= 0.8
    music_better = nfp_music < ofp_music
    speech_fp_ok = nfp <= ofp
    if recall_ok and music_better and speech_fp_ok:
        print(f"PASS: new VAD has recall={nr:.2f}>=0.8, music-only FPs {nfp_music}<{ofp_music}, "
              f"speech FPs {nfp}<={ofp}")
        return 0
    else:
        print(f"FAIL: recall_ok={recall_ok}, music_better={music_better}, speech_fp_ok={speech_fp_ok}")
        return 1

if __name__ == "__main__":
    sys.exit(main())
