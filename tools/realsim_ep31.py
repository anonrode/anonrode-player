#!/usr/bin/env python3
"""
REAL-WORLD validation: run the exact sync algorithm (ported 1:1 from
core/media Kotlin) against a REAL episode's audio + REAL subtitle file.

Edge case from the user: these subs are misaligned against this video.

Usage:
  python tools/realsim_ep31.py <video.mp4> <subtitle.srt> [analyze_seconds]
Requires ep31_16k.pcm (or pass --pcm) — extract with:
  ffmpeg -i video.mp4 -ac 1 -ar 16000 -f s16le -t 600 out.pcm
"""
import math
import re
import struct
import sys

ALIGN_BIN = 0.1
MIN_AUDIO_SECONDS = 16.0
MAX_OFFSET_SEC = 40.0
SAMPLE_RATE = 16000


# ── SRT parser (port of SubtitleParser.parseSrt) ─────────────────────
def parse_srt(raw):
    cues = []
    lines = raw.replace("\ufeff", "").replace("\r\n", "\n").replace("\r", "\n").split("\n")
    i = 0
    tpat = re.compile(r"([0-9:,.]+)\s*-->\s*([0-9:,.]+)")
    while i < len(lines):
        if lines[i].strip().isdigit():
            i += 1
        m = tpat.search(lines[i] if i < len(lines) else "")
        if m:
            def pt(t):
                p = [float(x.replace(",", ".")) for x in t.strip().split(":")]
                if len(p) == 3: return p[0]*3600 + p[1]*60 + p[2]
                if len(p) == 2: return p[0]*60 + p[1]
                return p[0] if p else -1.0
            start, end = pt(m.group(1)), pt(m.group(2))
            i += 1
            txt = []
            while i < len(lines) and lines[i].strip():
                s = re.sub(r"<[^>]+>", "", lines[i]).strip()
                s = (s.replace("&amp;", "&").replace("&lt;", "<")
                      .replace("&gt;", ">").replace("&nbsp;", " "))
                if s:
                    txt.append(s)
                i += 1
            if txt and start >= 0 and end > start:
                cues.append((start, end, txt))
        else:
            i += 1
    return cues


# ── VAD + correlation (exact port of AudioSyncProcessor/SpeechCorrelator) ──
def build_speech_track(pcm_path, max_seconds):
    """Adaptive floor/peak VAD at 100ms bins — same math as AudioSyncProcessor."""
    window_target = SAMPLE_RATE // 100          # 10ms windows
    bins = []
    floor = 0.0
    peak = 0.0
    window_sum = 0.0
    window_n = 0
    total_frames = 0
    with open(pcm_path, "rb") as f:
        while total_frames < max_seconds * SAMPLE_RATE:
            chunk = f.read(2 * window_target)
            if len(chunk) < 2 * window_target:
                break
            samples = struct.unpack("<%dh" % (len(chunk) // 2), chunk)
            rms = math.sqrt(sum(float(v) * v for v in samples) / len(samples))
            total_frames += len(samples)
            # adaptive floor/peak (identical constants)
            if floor == 0.0:
                floor = rms
                peak = rms * 1.9 + 0.0001
            else:
                floor = floor * 0.986 + min(rms, floor * 1.45) * 0.014
                peak = max(floor + 0.00035, max(peak * 0.992, rms))
            uf = floor * 1.08
            up = max(uf + 0.0012, peak)
            speech = min(1.0, max(0.0, (rms - uf) / (up - uf)))
            window_sum += speech
            window_n += 1
            if window_n >= 10:  # 100ms bin, mean-pool (matches soft track)
                bins.append(window_sum / window_n)
                window_sum = 0.0
                window_n = 0
    return bins


def find_offset(audio_bins, cue_starts, cue_ends, max_offset=MAX_OFFSET_SEC):
    n = len(audio_bins)
    if n < (MIN_AUDIO_SECONDS / ALIGN_BIN):
        return None
    A = [1.0 if v > 0.3 else 0.0 for v in audio_bins[:n]]
    mass = sum(A)
    if mass < 30:
        return None
    total = len(audio_bins)
    B = [0.0] * total
    for s, e in zip(cue_starts, cue_ends):
        for i in range(max(0, int(s / ALIGN_BIN)), min(total - 1, int(e / ALIGN_BIN)) + 1):
            B[i] = 1.0
    lo, hi = -int(max_offset / ALIGN_BIN), int(max_offset / ALIGN_BIN)
    best_score, best_shift = -2.0, 0
    scores = []
    for shift in range(lo, hi + 1):
        sc = 0.0
        for i in range(n):
            if A[i] <= 0.0:
                continue
            j = i + shift
            b = B[j] if 0 <= j < total else 0.0
            sc += A[i] * (2.0 * b - 1.0)
        sc /= mass
        scores.append(sc)
        if sc > best_score:
            best_score, best_shift = sc, shift
    second = max((s for k, s in enumerate(scores)
                  if abs((k + lo) - best_shift) * ALIGN_BIN > 2.0), default=-2.0)
    margin = best_score - second
    inside = sum(A[i] * (B[i + best_shift] if 0 <= i + best_shift < total else 0.0)
                 for i in range(n))
    containment = inside / mass
    mid = n // 2
    h1s, h1v = 0, -2.0
    for shift in range(lo, hi + 1):
        sc = m = 0
        for i in range(mid):
            if A[i] <= 0.0:
                continue
            m += 1
            j = i + shift
            sc += B[j] if 0 <= j < total else 0
        v = sc / m if m else 0.0
        if v > h1v:
            h1s, h1v = shift, v
    m2 = c2 = 0
    for i in range(mid, n):
        if A[i] <= 0.0:
            continue
        m2 += 1
        j = i + best_shift
        c2 += B[j] if 0 <= j < total else 0
    cont2 = c2 / m2 if m2 else 0.0
    validated = abs(h1s - best_shift) * ALIGN_BIN <= 0.3 and cont2 >= 0.7
    lockable = best_score > 0.2 and margin > 0.15 and containment >= 0.7 and validated
    return {
        "offset": -best_shift * ALIGN_BIN,
        "score": round(best_score, 3),
        "margin": round(margin, 3),
        "containment": round(containment, 3),
        "half1": round(h1v, 3),
        "lockable": lockable,
    }


def main():
    pcm = None
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    analyze_sec = float(args[2]) if len(args) > 2 else 600.0
    video, srt_path = args[0], args[1]
    raw = open(srt_path, encoding="utf-8", errors="replace").read()
    cues = parse_srt(raw)
    print(f"cues parsed: {len(cues)}")
    if cues:
        print(f"first cue: {cues[0][0]:.1f}s-{cues[0][1]:.1f}s   last ends: {cues[-1][1]:.1f}s")

    bins = build_speech_track(pcm or "tools/realsim/ep31_16k.pcm", analyze_sec)
    speech_bins = sum(1 for b in bins if b > 0.3)
    density = speech_bins / max(1, len(bins))
    print(f"audio analyzed: {len(bins)*0.1:.0f}s, speech density={density:.2f}")

    starts = [c[0] for c in cues]
    ends = [c[1] for c in cues]
    r = find_offset(bins, starts, ends)
    if r is None:
        print("RESULT: no lockable alignment found")
        return
    print(f"\nOFFSET FOUND: {r['offset']:+.1f}s")
    print(f"  score={r['score']}  margin={r['margin']}  containment={r['containment']}")
    print(f"  half1-agreement={r['half1']}  lockable={r['lockable']}")
    verdict = ("SUBS ARE MISALIGNED — apply offset to fix"
               if abs(r["offset"]) > 0.5 else "subs already aligned")
    print(f"  → {verdict}")


if __name__ == "__main__":
    main()
