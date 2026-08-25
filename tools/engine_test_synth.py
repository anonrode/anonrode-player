#!/usr/bin/env python3
"""
Self-contained sync engine test. Synthesizes audio in-memory, no files >2MB.

Each test case:
  1. Build synthetic audio in a temp WAV (typically <500KB)
  2. Build synthetic SRT with known offset + speed_factor distortion
  3. Run ffmpeg silencedetect on the WAV
  4. Run joint (alpha, beta) search
  5. Assert: alpha within 0.003, beta within 0.3s

Uses ffmpeg's lavfi source for clean sine-wave audio (not Python synthesis
which had bad silence detection). All temp files cleaned up after each case.
"""
import math, re, struct, subprocess, tempfile, sys, os
from pathlib import Path


def ffmpeg(args, capture=True):
    cmd = ["C:/Users/Anon/Documents/Codex/tools/ffmpeg/bin/ffmpeg.exe"] + args
    return subprocess.run(cmd, capture_output=capture, text=True)


def gen_audio_and_subs(seed, duration_s, true_offset, true_speed, music_db=-50):
    """
    Generate audio via ffmpeg lavfi: gated sine tones (real speech-like
    bursts) with optional background noise, with a known offset+drift
    distortion applied to the cue schedule.

    Returns (wav_path, srt_path, audio_cue_starts).
    """
    import random
    rng = random.Random(seed)

    # Generate true audio cue schedule (in AUDIO time)
    cues = []
    t = 2.0
    while t < duration_s - 5:
        dur = rng.uniform(1.5, 3.0)
        cues.append((t, min(t + dur, duration_s - 2)))
        t += dur + rng.uniform(0.8, 2.5)

    # Subtitle time = (audio_time - offset) / speed
    sub_cues = [(max(0, (s - true_offset) / true_speed),
                 max(0, (e - true_offset) / true_speed)) for s, e in cues]

    # Build SRT
    def fmt(t):
        t = max(0, t)
        h = int(t // 3600); m = int((t % 3600) // 60); s = int(t % 60); ms = int((t % 1) * 1000)
        return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"
    srt_text = ""
    for i, (s, e) in enumerate(sub_cues, 1):
        srt_text += f"{i}\n{fmt(s)} --> {fmt(e)}\nLine {i}\n\n"

    srt_path = Path(tempfile.gettempdir()) / f"esynth_{seed}.srt"
    srt_path.write_text(srt_text, encoding="utf-8")

    # Build audio: for each cue, generate a sine tone burst of that duration,
    # delayed to start at the cue time. Use ffmpeg lavfi concat for clean output.
    inputs = []
    filter_parts = []
    for i, (s, e) in enumerate(cues):
        dur = e - s
        if dur < 0.3: continue
        inputs.extend(["-f", "lavfi", "-t", f"{dur:.3f}",
                        "-i", f"sine=frequency={150 + (i % 6) * 50}:sample_rate=16000"])
        delay_ms = int(s * 1000)
        filter_parts.append(f"[{i+1}:a]adelay={delay_ms}|{delay_ms}[a{i+1}]")

    if not filter_parts:
        return None, srt_path, []

    # Concat all delayed streams
    if music_db > -60:
        # add background music
        inputs.extend(["-f", "lavfi", "-t", f"{duration_s}",
                        "-i", f"anoisesrc=color=brown:amplitude=0.05:sample_rate=16000"])
        filter_parts.append(f"[{len(cues)+1}:a]volume={music_db}dB[mus]")
        filter_parts.append(f"[mus]atrim=0:{duration_s}[mus2]")

    concat_in = "".join(f"[a{i+1}]" for i in range(len(cues) if filter_parts else 0))
    # actually simpler: just emit each cue as a separate input then amix
    if len(cues) > 1:
        # mix all with weights
        amix_inputs = "".join(f"[a{i+1}]" for i in range(len(cues)))
        # we already used adelay per cue; now amix them
        filter = "".join(filter_parts)
        # combine all with amix
        filter += f"amix=inputs={len(cues)}:duration=longest:dropout_transition=0[aout]"
    else:
        filter = f"{filter_parts[0]}[aout]"

    if music_db > -60 and len(cues) > 0:
        filter = f"{filter};[aout][mus2]amix=inputs=2:duration=longest[aout2]"

    wav_path = Path(tempfile.gettempdir()) / f"esynth_{seed}.wav"
    cmd = [
        "-y", "-v", "error"
    ] + inputs + [
        "-filter_complex", filter,
        "-map", "[aout]" if music_db <= -60 else "[aout2]",
        "-ac", "1", "-ar", "16000",
        str(wav_path)
    ]
    r = ffmpeg(cmd)
    if r.returncode != 0:
        return None, srt_path, [s for s, e in cues]

    return wav_path, srt_path, [s for s, e in cues]


def extract_onsets(wav_path, min_dur=0.3, noise_db=-30):
    """Run ffmpeg silencedetect, return list of onset times (in seconds)."""
    cmd = [
        "-v", "error", "-i", str(wav_path),
        "-af", f"silencedetect=noise={noise_db}:d={min_dur}",
        "-f", "null", "-"
    ]
    r = ffmpeg(cmd)
    onsets = []
    for line in r.stderr.splitlines() + r.stdout.splitlines():
        if "silence_end:" in line:
            try:
                t = float(line.split("silence_end:")[1].strip().split()[0])
                onsets.append(t)
            except (ValueError, IndexError):
                pass
    return sorted(onsets)


def joint_search(onsets, sub_starts, max_offset=60.0):
    """Search (alpha, beta) jointly. Returns (best_alpha, best_beta, match_rate)."""
    if not onsets or not sub_starts:
        return 1.0, 0.0, 0.0

    import bisect
    alphas = [0.99, 0.995, 1.0, 1.005, 1.01, 1.015, 1.02]
    best = (0.0, 1.0, 0.0)

    for alpha in alphas:
        scaled = [alpha * s for s in sub_starts]
        for beta_10 in range(-int(max_offset * 10), int(max_offset * 10) + 1, 5):
            beta = beta_10 / 10.0
            shifted = [s + beta for s in scaled]
            matched = 0
            for s in shifted:
                pos = bisect.bisect_left(scaled, s)
                found = False
                for k in [pos-1, pos]:
                    if 0 <= k < len(scaled) and abs(scaled[k] - s) <= 0.5:
                        found = True; break
                if found: matched += 1
            frac = matched / max(1, len(sub_starts))
            if frac > best[0]:
                best = (frac, alpha, beta)

    return best[1], best[2], best[0]


def test_case(name, true_offset, true_speed, duration=30,
              tol_alpha=0.003, tol_beta=0.3):
    wav, srt, cues = gen_audio_and_subs(
        seed=abs(hash(name)) % 100000,
        duration_s=duration, true_offset=true_offset, true_speed=true_speed
    )
    if wav is None or not wav.exists():
        print(f"  [SKIP] {name} (audio gen failed)")
        return False

    onsets = extract_onsets(wav, min_dur=0.3, noise_db=-30)
    if len(onsets) < 3:
        # try less strict silence
        onsets = extract_onsets(wav, min_dur=0.2, noise_db=-35)
    if len(onsets) < 3:
        print(f"  [SKIP] {name} (only {len(onsets)} onsets)")
        return False

    # parse sub starts
    srt_text = srt.read_text(encoding='utf-8')
    sub_starts = []
    for m in re.finditer(r'(\d{2}):(\d{2}):(\d{2}),(\d{3})', srt_text):
        h, mn, s, ms = (int(x) for x in m.groups())
        sub_starts.append(h*3600 + mn*60 + s + ms/1000.0)

    alpha, beta, match = joint_search(onsets, sub_starts)

    alpha_err = abs(alpha - true_speed)
    beta_err = abs(beta - true_offset)
    ok = alpha_err <= tol_alpha and beta_err <= tol_beta

    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {name}")
    print(f"    true:      offset={true_offset:+.2f}s  speed={true_speed:.4f}")
    print(f"    found:     offset={beta:+.2f}s  speed={alpha:.4f}  match={match*100:.0f}%  onsets={len(onsets)}")
    if not ok:
        print(f"    ERROR: alpha_err={alpha_err*1000:.0f}ms (tol {tol_alpha*1000:.0f}ms) "
              f"beta_err={beta_err:.2f}s (tol {tol_beta:.1f}s)")
    wav.unlink(missing_ok=True)
    srt.unlink(missing_ok=True)
    return ok


def main():
    print("="*60)
    print("  SELF-CONTAINED ENGINE TEST (no real files)")
    print("="*60)

    PASS, FAIL = 0, 0
    cases = [
        ("Zero offset, 1.0x",                  0.0,   1.0),
        ("Late subs +2.5s, 1.0x",              2.5,   1.0),
        ("Early subs -3.0s, 1.0x",            -3.0,   1.0),
        ("Large offset +10s, 1.0x",            10.0,   1.0),
        ("Drift 1.005x, +1s",                  1.0,   1.005),
        ("Drift 0.995x, -1s",                 -1.0,   0.995),
        ("Growling Tiger: -0.33s, 1.009x",    -0.33,  1.009),
        ("Large drift 1.02x, +0.5s",          0.5,   1.02),
    ]
    for name, off, spd in cases:
        if test_case(name, off, spd):
            PASS += 1
        else:
            FAIL += 1

    print(f"\n{'='*60}")
    print(f"  RESULT: {PASS}/{PASS+FAIL} passed")
    print(f"{'='*60}")
    sys.exit(0 if FAIL == 0 else 1)


if __name__ == "__main__":
    main()
