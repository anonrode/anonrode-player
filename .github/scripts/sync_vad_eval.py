#!/usr/bin/env python3
"""Does the neural speech envelope beat the energy envelope on the live
correlator? Runs in CI on Linux, where onnxruntime-python is stable.

The Windows onnxruntime wheels segfault under sustained inference on the dev
machine, so this question could not be answered locally. CI is the gate.

Uses the app's REAL model (core/media/src/main/assets/silero_vad.onnx) and a
1:1 port of SpeechCorrelator.findOffset including every gate. Ground truth is
synthesised from the audio itself, never from the envelope under test, so the
test cannot validate its own bug.

Two content classes decide the architecture question:

  live_action  speech bursts separated by real gaps. RMS can see this, so both
               envelopes should manage it.
  continuous   a loud continuous bed with speech-like bursts on top - the
               anime/dubbed case. This is where RMS is expected to fail and
               the model is expected to win, and it is the whole reason for
               wanting a neural detector in the live path.

Exits non-zero if the neural envelope fails to beat the energy one on the
continuous class, so a regression fails the build rather than printing a
number nobody reads.
"""
import math
import os
import sys

import numpy as np
import onnxruntime as ort

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
MODEL = os.path.join(REPO, "core", "media", "src", "main", "assets", "silero_vad.onnx")

SR, WINDOW, CONTEXT, THRESHOLD = 16000, 512, 64, 0.5
FRAME_SEC = WINDOW / SR

# SpeechCorrelator gates, byte-identical
MIN_AUDIO_SECONDS, MAX_OFFSET_SEC, MIN_SPEECH_BINS = 8.0, 60.0, 30
PEAK_MIN, PROM_MIN, Z_SMALL, Z_LARGE = 0.30, 0.12, 9.0, 7.0
ELIGIBLE_BINS, EXCLUSION_BINS, ALIGN_BIN = 160, 20, 0.1
FLOOR_DOWN, FLOOR_UP, PEAK_UP, PEAK_DOWN = 0.06, 0.0007, 0.08, 0.002


class Silero:
    """Port of SileroVad.kt. `sr` is an int64 scalar in this model, not f32."""

    def __init__(self, model):
        so = ort.SessionOptions()
        so.log_severity_level = 3
        self.sess = ort.InferenceSession(model, sess_options=so,
                                        providers=["CPUExecutionProvider"])
        self.reset()

    def reset(self):
        self.state = np.zeros((2, 1, 128), dtype=np.float32)
        self.ctx = np.zeros(CONTEXT, dtype=np.float32)
        self.chunk = np.zeros(WINDOW, dtype=np.float32)
        self.chunk_n, self.has_ctx = 0, False
        self.probs, self.bins = [], []
        self.src_rate, self.frac, self.prev_last = 0, 0.0, 0.0
        self.pending, self.n = [], 0

    def process(self, x, rate):
        self.pending.extend(x)
        self.n = len(self.pending)
        if self.src_rate != rate:
            self.src_rate, self.frac = rate, 0.0
        if self.n < 2:
            self.pending, self.n = [], 0
            return
        step = rate / SR
        p = self.frac
        while p < self.n - 1:
            idx = int(math.floor(p))
            f = p - idx
            a = self.prev_last if idx < 0 else self.pending[idx]
            self._on16k(a + f * (self.pending[idx + 1] - a))
            p += step
        self.frac, self.prev_last = p - self.n, self.pending[self.n - 1]
        self.pending, self.n = [], 0

    def _on16k(self, x):
        self.chunk[self.chunk_n] = x
        self.chunk_n += 1
        if self.chunk_n < WINDOW:
            return
        self.chunk_n = 0
        xin = (np.concatenate([self.ctx, self.chunk]) if self.has_ctx
               else self.chunk).astype(np.float32).reshape(1, -1)
        out, st = self.sess.run(
            ["output", "stateN"],
            {"input": xin, "state": self.state, "sr": np.array(SR, dtype=np.int64)},
        )
        p = float(out[0][0])
        self.probs.append(p)
        self.bins.append(1 if p > THRESHOLD else 0)
        self.state = st.astype(np.float32)
        self.ctx = self.chunk[WINDOW - CONTEXT:].copy()
        self.has_ctx = True

    def envelope(self, target=0.1):
        if not self.probs:
            return np.zeros(0, dtype=np.float32)
        ob = max(1, int(len(self.probs) * FRAME_SEC / target))
        env, cnt = np.zeros(ob), np.zeros(ob, dtype=np.int64)
        for i, p in enumerate(self.probs):
            oi = min(ob - 1, int((i * FRAME_SEC) / target))
            env[oi] += p
            cnt[oi] += 1
        for i in range(ob):
            if cnt[i]:
                env[i] /= cnt[i]
        return env.astype(np.float32)


def energy_envelope(pcm, rate):
    """Port of the fixed AudioSyncProcessor.finishWindow()."""
    target = max(1, rate // 100)
    bins, floor, peak, last = [], 0.0, 0.0, 0.0
    wn = sq = 0.0
    for v in pcm:
        sq += v * v
        wn += 1
        if wn < target:
            continue
        rms = math.sqrt(sq / wn)
        if floor <= 0.0:
            floor, peak = rms, rms * 2.0 + 1e-4
        else:
            floor += (rms - floor) * (FLOOR_DOWN if rms < floor else FLOOR_UP)
            peak += (rms - peak) * (PEAK_UP if rms > peak else PEAK_DOWN)
        head = peak - floor
        sp = min(1.0, max(0.0, (rms - floor) / max(head, peak * 0.05 + 1e-4)))
        last = sp * 0.72 + last * 0.28
        bins.append(sp)
        wn, sq = 0.0, 0.0
    return np.array(bins, dtype=np.float32)


def find_offset(audio, n, cues):
    """Port of SpeechCorrelator.findOffset, every gate included."""
    if n < int(MIN_AUDIO_SECONDS / ALIGN_BIN) or len(cues) < 3:
        return {"kind": "NotReady"}
    if int((audio[:n] > 0.3).sum()) < MIN_SPEECH_BINS or n < ELIGIBLE_BINS:
        return {"kind": "NotReady"}
    q = np.floor(np.clip(audio[:n], 0, None) * 255 + 0.5).astype(np.int64)
    a = audio[:n].astype(np.float64)
    sum_a, sum_a2 = a.sum(), (a * a).sum()
    var_a = n * sum_a2 - sum_a * sum_a
    if var_a <= 1e-9:
        return {"kind": "NoMatch", "peak": 0.0}
    pad = int(MAX_OFFSET_SEC / ALIGN_BIN)
    b_bins = len(audio) + 2 * pad
    B = np.zeros(b_bins)
    for c in cues:
        i0 = max(0, int(c["start"] / ALIGN_BIN) + pad)
        i1 = min(b_bins - 1, int(c["end"] / ALIGN_BIN) + pad)
        if i0 <= i1:
            B[i0:i1 + 1] = 1.0

    def corr(shift):
        off = shift + pad
        j0, j1 = max(0, -off), min(n, b_bins - off)
        if j1 <= j0:
            return None
        d = B[j0 + off:j1 + off]
        s_b = d.sum()
        if s_b == 0 or s_b == n:
            return None
        s_ab = q[j0:j1][d > 0].sum()
        den = math.sqrt(var_a * (n * s_b - s_b * s_b))
        if den < 1e-9:
            return None
        return (n * (s_ab / 255.0) - sum_a * s_b) / den

    peak_r, best = -2.0, 0
    for s in range(-pad, pad + 1):
        r = corr(s)
        if r is not None and r > peak_r:
            peak_r, best = r, s
    if peak_r <= -2:
        return {"kind": "NoMatch", "peak": peak_r, "z": 0.0, "zFloor": 0.0}
    second = -2.0
    for s in range(-pad, pad + 1):
        if abs(s - best) <= EXCLUSION_BINS:
            continue
        r = corr(s)
        if r is not None and r > second:
            second = r
    margin = peak_r if second <= -2 else peak_r - second
    z = peak_r * math.sqrt(n)
    zf = (Z_SMALL if n <= 160 else
          Z_SMALL - (Z_SMALL - Z_LARGE) * min(1.0, (n - 160) / 120))
    if not (peak_r >= PEAK_MIN and margin >= PROM_MIN and z >= zf):
        return {"kind": "NoMatch", "peak": peak_r, "margin": margin, "z": z, "zFloor": zf}
    return {"kind": "Match", "offset": -best * ALIGN_BIN, "r": peak_r, "z": z}



def synth(seed, seconds, rate, bed_level):
    """Irregular speech-like bursts. bed_level=0 -> real gaps (live action);
    bed_level>0 -> a continuous loud bed under the bursts (anime/dub), the case
    an RMS envelope cannot see and a model can."""
    rng = np.random.RandomState(seed)
    t, speech = 0.0, []
    while t < seconds:
        dur = 0.8 + rng.rand() * 1.7
        speech.append((t, t + dur))
        t += dur + 0.3 + rng.rand() * 1.2
    n = int(seconds * rate)
    out = np.full(n, bed_level, dtype=np.float64)
    for s, e in speech:
        i0, i1 = int(s * rate), min(n, int(e * rate))
        if i1 <= i0:
            continue
        tt = np.arange(i1 - i0) / rate
        f0 = 110 + rng.rand() * 70
        tone = (np.sin(2 * np.pi * f0 * tt)
                + 0.5 * np.sin(2 * np.pi * 2 * f0 * tt)
                + 0.25 * np.sin(2 * np.pi * 3 * f0 * tt))
        tone = tone * (0.55 + 0.45 * np.sin(2 * np.pi * 3.5 * tt))
        out[i0:i1] += 0.55 * tone + 0.05 * rng.randn(i1 - i0)
    out = np.clip(out, -1.0, 1.0).astype(np.float32)
    truth = np.zeros(int(seconds * 100), dtype=np.int8)
    for s, e in speech:
        a, b = int(s * 100), min(len(truth), int(e * 100))
        truth[a:b] = 1
    return out, truth


def shape_cues(truth, dilate=2, min_gap=3, min_on=6, max_on=30):
    """Turn 100 ms truth marks into realistic 0.6-3.0 s subtitle cues."""
    n = len(truth)
    on = np.zeros(n, dtype=np.uint8)
    for i, v in enumerate(truth):
        if v:
            for k in range(-dilate, dilate + 1):
                j = i + k
                if 0 <= j < n:
                    on[j] = 1
    regions, i = [], 0
    while i < n:
        if not on[i]:
            i += 1
            continue
        j = i
        while j < n:
            if on[j]:
                j += 1
                continue
            k = j
            while k < n and not on[k]:
                k += 1
            if k - j <= min_gap:
                j = k
                continue
            break
        if j - i >= min_on:
            regions.append((i, j))
        i = j
    out = []
    for a, z in regions:
        p, ln = a, z - a
        while ln > max_on:
            out.append({"start": p * 0.1, "end": (p + max_on) * 0.1})
            p += max_on
            ln -= max_on
        if ln >= min_on:
            out.append({"start": p * 0.1, "end": z * 0.1})
    return out


def main():
    if not os.path.exists(MODEL):
        print("model missing at", MODEL)
        return 1
    print("model:", os.path.relpath(MODEL, REPO))
    rate, seconds, deltas = 44100, 150, (-4.0, -2.0, 2.0, 4.0, 8.0)
    res = {}
    for label, bed in (("live_action", 0.0), ("continuous", 0.22)):
        pcm, truth = synth(abs(hash(label)) % 9999, seconds, rate, bed)
        v = Silero(MODEL)
        v.process(pcm.tolist(), rate)
        env_s = v.envelope()
        env_e = energy_envelope(pcm, rate)
        cues = shape_cues(truth)
        print(f"  {label}: bed={bed} {len(cues)} truth cues, "
              f"silero mean prob {float(env_s.mean()) if len(env_s) else 0:.3f}")
        res[label] = {}
        for name, env in (("energy", env_e), ("silero", env_s)):
            n = min(len(env), int(seconds * 10))
            hits, worst, peaks = 0, 0.0, []
            for d in deltas:
                sh = [{"start": c["start"] + d, "end": c["end"] + d} for c in cues]
                r = find_offset(env, n, sh)
                peaks.append(round(r.get("peak", 0.0), 3))
                if r["kind"] == "Match":
                    hits += 1
                    worst = max(worst, abs(r["offset"] + d))
            res[label][name] = hits
            print(f"    {name:7} locks {hits}/{len(deltas)} "
                  f"worst|resid| {worst:.1f}s peaks={peaks}")
    e = res["continuous"]["energy"]
    s = res["continuous"]["silero"]
    print()
    print(f"continuous class: energy {e}/{len(deltas)} locks, "
          f"silero {s}/{len(deltas)} locks")
    if s <= e:
        print("REGRESSION: the neural envelope is not beating energy on "
              "continuous content, so the live-path VAD swap is not worth "
              "making. See docs/AUDIT_LEDGER.md.")
        return 1
    print("OK: the neural envelope beats energy on continuous content.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

