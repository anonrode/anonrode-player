#!/usr/bin/env python3
"""
Progressive drift-aware subtitle sync — validated against REAL content.

The algorithm models the relationship between audio time and subtitle time
as an affine transform:
    subtitle_time = (media_time - base_offset) * speed_factor

Where speed_factor ≈ 1 ± 2% (frame-rate mismatch) and base_offset is the
constant misalignment. Both are discovered progressively as audio plays.

Pipeline:
  1. Accumulate soft speech track from adaptive floor/peak VAD
  2. Every EVAL_INTERVAL, correlate a sliding window against subtitle cues
     to get a LOCAL offset estimate
  3. Keep a history of (media_time, local_offset) points
  4. After ≥3 points, fit least-squares line → drift rate + base offset
  5. Apply BOTH corrections in findCue:
     ta = (t - base_offset) / speed_factor
     (equivalently: ta = t/speed - offset/speed)

Run: python tools/drift_sim.py <video.mp4> <subs.srt> [seconds]
"""
import math, re, struct, sys, os

# ── constants (match Kotlin) ──────────────────────────────────────────
SAMPLE_RATE = 16000
ALIGN_BIN = 0.1          # 100ms bins
EVAL_INTERVAL_S = 10.0   # evaluate every 10s of media time
WINDOW_S = 90.0          # correlation window (last N seconds)
MIN_AUDIO_S = 16.0
MAX_OFFSET = 40.0
DRIFT_LOCK_THRESHOLD = 0.003  # 0.3% drift = significant
SPEED_CANDIDATES = [0.98, 0.99, 0.995, 1.0, 1.005, 1.01, 1.015, 1.02]

# ── SRT parser ────────────────────────────────────────────────────────
def parse_srt(raw):
    cues = []
    lines = raw.replace("\ufeff", "").replace("\r\n", "\n").split("\n")
    i = 0
    tpat = re.compile(r"([0-9:,.]+)\s*-->\s*([0-9:,.]+)")
    def pt(t):
        p = [float(x.replace(",", ".")) for x in t.strip().split(":")]
        if len(p) == 3: return p[0]*3600+p[1]*60+p[2]
        if len(p) == 2: return p[0]*60+p[1]
        return p[0] if p else -1.0
    while i < len(lines):
        if lines[i].strip().isdigit(): i += 1
        m = tpat.search(lines[i] if i < len(lines) else "")
        if m:
            start, end = pt(m.group(1)), pt(m.group(2))
            i += 1; txt = []
            while i < len(lines) and lines[i].strip():
                s = re.sub(r"<[^>]+>", "", lines[i]).strip()
                s = s.replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&nbsp;"," ")
                if s: txt.append(s)
                i += 1
            if txt and start >= 0 and end > start:
                cues.append((start, end))
            else:
                pass
        else: i += 1
    return cues

# ── VAD: adaptive floor/peak → soft speech per 100ms bin ────────────
def vad_track(pcm_path, max_s):
    sr = SAMPLE_RATE
    wt = sr // 100  # samples per 10ms window
    floor = peak = 0.0
    wsum = wn = 0
    frames = 0
    bins = []
    with open(pcm_path, "rb") as f:
        while frames < max_s * sr:
            chunk = f.read(2 * wt)
            if len(chunk) < 2 * wt: break
            samples = struct.unpack("<%dh" % (len(chunk)//2), chunk)
            rms = math.sqrt(sum(float(v)*v for v in samples) / len(samples))
            frames += len(samples)
            if floor == 0.0:
                floor = rms; peak = rms*1.9+0.0001
            else:
                floor = floor*0.986 + min(rms, floor*1.45)*0.014
                peak = max(floor+0.00035, max(peak*0.992, rms))
            uf = floor*1.08; up = max(uf+0.0012, peak)
            sp = min(1.0, max(0.0, (rms-uf)/(up-uf)))
            wsum += sp; wn += 1
            if wn >= 10:  # 100ms bin
                bins.append(wsum/wn); wsum=0.0; wn=0
    return bins

# ── correlation of a segment against cues ────────────────────────────
def correlate_segment(A, cue_starts, cue_ends, seg_start_bin, seg_end_bin):
    """
    Correlate A[seg_start_bin:seg_end_bin] against subtitle cues.
    Returns (best_offset_sec, score) or None.
    Offset convention: positive = subs late (need to shift earlier).
    """
    n_seg = seg_end_bin - seg_start_bin
    if n_seg < 160: return None  # <16s
    mass = sum(A[seg_start_bin:seg_end_bin])
    if mass < 30: return None
    lo = -int(MAX_OFFSET / ALIGN_BIN)
    hi = int(MAX_OFFSET / ALIGN_BIN)
    best_sc, best_sh = -2.0, 0
    for shift in range(lo, hi + 1):
        sc = 0.0
        for i in range(seg_start_bin, seg_end_bin):
            a = A[i]
            if a <= 0.05: continue
            j = i - shift  # subtitle time = media time - offset
            # check if inside any cue
            st = j * ALIGN_BIN
            hit = 0.0
            for cs, ce in zip(cue_starts, cue_ends):
                if cs <= st < ce:
                    hit = 1.0; break
                if cs > st: break
            sc += a * (2*hit - 1)
        sc /= mass
        if sc > best_sc:
            best_sc, best_sh = sc, shift
    if best_sc <= 0.05: return None
    return (best_sh * ALIGN_BIN, best_sc)

# ── progressive drift tracker ────────────────────────────────────────
class DriftTracker:
    def __init__(self):
        self.points = []  # (media_time_s, local_offset_s, score)

    def add(self, t, offset, score):
        self.points.append((t, offset, score))

    def fit(self):
        """Least-squares fit: offset(t) = a + b*t. Returns (a, b) or None."""
        if len(self.points) < 2: return None
        n = len(self.points)
        mt = sum(p[0] for p in self.points) / n
        mo = sum(p[1] for p in self.points) / n
        num = sum((p[0]-mt)*(p[1]-mo) for p in self.points)
        den = sum((p[0]-mt)**2 for p in self.points)
        if den < 1e-6: return None
        b = num / den
        a = mo - b * mt
        return (a, b)

    def get_correction(self, media_time):
        """Returns (base_offset, speed_factor) to use in findCue."""
        fit = self.fit()
        if fit is None:
            # Not enough data yet — use latest point as constant offset
            if self.points:
                return (self.points[-1][1], 1.0)
            return (0.0, 1.0)
        a, b = fit
        # offset(t) = a + b*t means subs drift by b seconds per second
        # speed_factor = 1 + b (if b > 0, subs play too slow → speed up)
        speed = 1.0 + b
        base = a
        return (base, speed)


def simulate(cues, pcm_path, duration_s=600.0):
    """
    Full progressive simulation: accumulate VAD bins, periodically correlate,
    track drift, report corrections. Mirrors the Kotlin AudioSyncProcessor.
    """
    bins = vad_track(pcm_path, duration_s)
    print(f"VAD: {len(bins)} bins ({len(bins)*ALIGN_BIN:.0f}s), "
          f"density={sum(1 for b in bins if b>0.25)/max(1,len(bins)):.2f}")

    tracker = DriftTracker()
    cue_starts = [c[0] for c in cues]
    cue_ends = [c[1] for c in cues]

    eval_interval_bins = int(EVAL_INTERVAL_S / ALIGN_BIN)
    min_eval_bins = int(MIN_AUDIO_S / ALIGN_BIN)
    window_bins = int(WINDOW_S / ALIGN_BIN)

    log = []
    locked_offset = None
    locked_drift = None

    for eval_bin in range(min_eval_bins, len(bins), eval_interval_bins):
        # correlate the last WINDOW_S seconds
        seg_start = max(0, eval_bin - window_bins)
        result = correlate_segment(bins, cue_starts, cue_ends, seg_start, eval_bin)
        if result is None:
            continue
        local_offset, local_score = result
        media_t = eval_bin * ALIGN_BIN
        tracker.add(media_t, local_offset, local_score)

        correction = tracker.get_correction(media_t)
        base, speed = correction
        log.append({
            "t": media_t, "local_off": local_offset, "score": round(local_score, 3),
            "base": round(base, 2), "speed": round(speed, 5),
            "points": len(tracker.points),
        })

    # Final fit
    fit = tracker.fit()
    if fit:
        a, b = fit
        print(f"\nFINAL DRIFT FIT (from {len(tracker.points)} points):")
        print(f"  base_offset = {a:+.2f}s")
        print(f"  drift_rate  = {b*1000:.1f} ms/s ({b*100:.3f}%)")
        print(f"  speed_factor = {1+b:.5f}")
        print(f"\nEvaluation timeline:")
        for entry in log[-8:]:
            print(f"  t={entry['t']:5.0f}s  local={entry['local_off']:+6.1f}s  "
                  f"score={entry['score']:.3f}  → base={entry['base']:+.2f} speed={entry['speed']:.5f}")
    else:
        print("Not enough points for drift fit")

    return tracker


if __name__ == "__main__":
    video = sys.argv[1] if len(sys.argv) > 1 else None
    srt = sys.argv[2] if len(sys.argv) > 2 else None
    dur = float(sys.argv[3]) if len(sys.argv) > 3 else 600.0

    # extract PCM if needed
    pcm_name = "tools/realsim/ep31_16k.pcm"
    if not os.path.exists(pcm_name) or (video and "EP32" in video):
        pcm_name = "tools/realsim/temp_16k.pcm"
        ffmpeg = r"C:\Users\Anon\Documents\Codex\tools\ffmpeg\bin\ffmpeg.exe"
        os.system(f'"{ffmpeg}" -y -v error -i "{video}" -ac 1 -ar 16000 -f s16le -t {dur:.0f} "{pcm_name}"')

    raw = open(srt, encoding="utf-8", errors="replace").read()
    cues = parse_srt(raw)
    print(f"cues: {len(cues)}, span: {cues[0][0]:.0f}s-{cues[-1][1]:.0f}s" if cues else "no cues")

    tracker = simulate(cues, pcm_name, dur)
