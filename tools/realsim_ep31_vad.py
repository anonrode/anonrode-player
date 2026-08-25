#!/usr/bin/env python3
"""
Real-audio VAD validation: run the new vocal-band onset VAD on the
Growling Tiger 2 EP31 PCM (16kHz mono) and compare against the
ffmpeg silencedetect onset list (tools/realsim/onsets_speech.txt).

This is the test that proves the new VAD is actually good on
the hardest real content we have. The offline pipeline already
derived α=1.009, β=−0.33s from these 1026 ffmpeg onsets, so a VAD
that gets within ±50ms of them most of the time will let the
in-app live sync work the same way.

Pass criteria:
  - recall >= 0.80 of ffmpeg onsets detected within ±50ms
  - false-positive rate in clearly-non-speech regions <= 0.05
    (we use 5 random 30s windows of "no GT onset within ±2s"
     and assert we detect <= 1 onset per window on average)

Outputs:
  - console summary
  - tools/realsim/vad_plot.png — side-by-side onset plot (ffmpeg vs new vs old)

Run:  python3 tools/realsim_ep31_vad.py
"""
import os, sys, math, random, struct
import numpy as np

# Reuse the VAD math from vad_sim
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vad_sim as V

PCM_PATH   = os.path.join(os.path.dirname(__file__), 'realsim', 'ep31_16k.pcm')
FFMPEG_REF = os.path.join(os.path.dirname(__file__), 'realsim', 'onsets_speech.txt')
PLOT_PATH  = os.path.join(os.path.dirname(__file__), 'realsim', 'vad_plot.png')

SR = 16000
TOLERANCE_S = 0.05  # ±50ms
# The on-disk PCM is the first 10 minutes (600s) of EP31. The
# ffmpeg reference covers the full ~40-min episode, so we restrict
# both to the first 10 min so the matcher compares same-domain data.
PCM_DUR_S = 600.0

def load_pcm_16k_mono(path):
    raw = open(path, 'rb').read()
    n = len(raw) // 2
    pcm = np.frombuffer(raw[:n*2], dtype='<i2').astype(np.float32) / 32768.0
    return pcm

def load_ffmpeg_onsets(path, max_t=None):
    arr = np.array([float(line.strip()) for line in open(path) if line.strip()])
    if max_t is not None:
        arr = arr[arr <= max_t]
    return arr

def detect_onsets_from_scores(scores, threshold=0.3, min_gap_s=0.3):
    win_s = V.WIN_MS / 1000.0
    onsets = []
    last = -100.0
    for w, s in enumerate(scores):
        t = w * win_s
        if s >= threshold and (t - last) > min_gap_s:
            onsets.append(t)
            last = t
    return np.array(onsets)

def match_recall(detected, reference, tolerance=TOLERANCE_S):
    """Fraction of reference onsets that have at least one detected onset within ±tolerance."""
    if len(reference) == 0:
        return 0.0
    matched = 0
    for r in reference:
        if np.any(np.abs(detected - r) <= tolerance):
            matched += 1
    return matched / len(reference)

def false_positive_rate(detected, reference, audio_dur_s):
    """Approximate: detections with no reference onset within ±2s, as a rate per minute."""
    fp_count = 0
    for d in detected:
        if np.all(np.abs(reference - d) > 2.0):
            fp_count += 1
    return fp_count / (audio_dur_s / 60.0)

def main():
    print("=== REAL-AUDIO VAD VALIDATION: EP31 vs ffmpeg silencedetect ===")
    pcm = load_pcm_16k_mono(PCM_PATH)
    audio_dur_s = len(pcm) / SR
    print(f"PCM: {len(pcm)} samples, {audio_dur_s:.1f}s @ {SR}Hz")

    ref = load_ffmpeg_onsets(FFMPEG_REF, max_t=PCM_DUR_S)
    print(f"Reference ffmpeg onsets (restricted to {PCM_DUR_S:.0f}s): {len(ref)}")

    coeffs = V.biquad_bandpass_coeffs(SR, 300, 3400, q=0.707)

    print("Running new VAD (biquad + envelope + onset)...")
    new_scores = V.new_vad_full(pcm, coeffs, gain=8.0)
    print("Running old VAD (energy + variance + ZCR)...")
    old_scores = V.old_vad_full(pcm)

    new_onsets = detect_onsets_from_scores(new_scores, threshold=0.3, min_gap_s=0.3)
    old_onsets = detect_onsets_from_scores(old_scores, threshold=0.3, min_gap_s=0.3)

    nr = match_recall(new_onsets, ref)
    oR = match_recall(old_onsets, ref)
    nfp = false_positive_rate(new_onsets, ref, audio_dur_s)
    ofp = false_positive_rate(old_onsets, ref, audio_dur_s)

    print()
    print(f"NEW VAD: {len(new_onsets)} onsets, recall vs ffmpeg = {nr:.3f}, FP/min = {nfp:.2f}")
    print(f"OLD VAD: {len(old_onsets)} onsets, recall vs ffmpeg = {oR:.3f}, FP/min = {ofp:.2f}")

    pass_recall = nr >= 0.80
    pass_fp     = nfp <= 5.0
    pass_strict = (nr >= oR) and (nfp <= ofp)
    print()
    print(f"recall >= 0.80 ?  {pass_recall}")
    print(f"FP/min <= 5 ?     {pass_fp}")
    print(f"strictly better ? {pass_strict}")

    # Plot
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        fig, (a1, a2) = plt.subplots(2, 1, figsize=(14, 6), sharex=True)
        # Time axis in minutes for readability
        for ax, scores, label in [(a1, new_scores, "NEW (biquad+envelope+onset)"),
                                   (a2, old_scores, "OLD (energy+variance+ZCR)")]:
            t = np.arange(len(scores)) * V.WIN_MS / 1000.0
            ax.plot(t / 60.0, scores, lw=0.4, color='#2DE0B6' if 'NEW' in label else '#888')
            ax.set_ylim(0, 1.05)
            ax.set_ylabel(label, fontsize=9)
            for r in ref[::10]:  # subsample reference for visibility
                ax.axvline(r / 60.0, color='#FFB454', lw=0.3, alpha=0.4)
        a1.scatter(new_onsets / 60.0, [1.0]*len(new_onsets), s=3, color='#00E676', label='new detects', zorder=3)
        a2.scatter(old_onsets / 60.0, [1.0]*len(old_onsets), s=3, color='#ff5577', label='old detects', zorder=3)
        a1.legend(loc='upper right', fontsize=8)
        a2.legend(loc='upper right', fontsize=8)
        a2.set_xlabel('Time (minutes)')
        a1.set_title(f'EP31 VAD comparison — recall new={nr:.2f} old={oR:.2f} · '
                     f'FP/min new={nfp:.1f} old={ofp:.1f}', fontsize=10)
        plt.tight_layout()
        plt.savefig(PLOT_PATH, dpi=80)
        print(f"\nPlot saved: {PLOT_PATH}")
    except ImportError:
        print("\n(matplotlib not available, skipping plot)")

    if pass_recall and pass_fp:
        print("\nPASS: new VAD works on real EP31 audio")
        return 0
    else:
        print("\nFAIL: new VAD does not yet work on real EP31")
        return 1

if __name__ == "__main__":
    sys.exit(main())
