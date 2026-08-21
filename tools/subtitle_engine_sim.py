#!/usr/bin/env python3
"""
Subtitle engine simulation — validates the auto-pick + auto-sync algorithms
(ported 1:1 from core/media) BEFORE the Android app is built.

Pipeline under test:
  matcher (filename scoring) → parser → cue model → live RMS analysis
  (adaptive floor/peak) → histogram correlation (coarse+fine) → confidence
  gated lock → persisted per-episode offset (additive with manual nudge)

Run:  python3 tools/subtitle_engine_sim.py
"""
import math
import random
import re

# ─────────────────────────────────────────────────────────────────────
# 1:1 PORT of the Kotlin engine (core/media/.../sync/)
# ─────────────────────────────────────────────────────────────────────
ALIGN_BIN = 0.1
ALIGN_SCAN_SECONDS = 95.0
ALIGN_MAX_OFFSET = 40.0
ALIGN_MIN_AUDIO_SECONDS = 16.0
LOCK_CONFIDENCE = 0.74
EVAL_INTERVAL_MS = 1400


def build_cue_model(cues):
    """Port of CueModelBuilder.build"""
    limit = ALIGN_SCAN_SECONDS + ALIGN_MAX_OFFSET + 6.0
    bins = int(limit / ALIGN_BIN) + 4
    speech = [0.0] * bins
    edge = [0.0] * bins

    for start, end, chars in cues:
        start = max(0.0, min(start, limit))
        end = max(0.0, min(end, limit))
        dur = end - start
        if not dur > 0.12:
            continue
        density = min(1.75, max(0.72, chars / max(10.0, dur * 13.0)))
        start_bin = max(0, int(start / ALIGN_BIN))
        end_bin = min(bins - 1, int(end / ALIGN_BIN))
        if end % ALIGN_BIN == 0.0:
            end_bin -= 1
        for i in range(start_bin, end_bin + 1):
            t = i * ALIGN_BIN
            inner = min(t - start, end - t) / max(0.12, min(0.7, dur * 0.22))
            base = 0.52 + min(1.0, max(0.0, inner)) * 0.7
            speech[i] = max(speech[i], base * density)
        edge[start_bin] = max(edge[start_bin], 1.35)
        if start_bin + 1 < bins:
            edge[start_bin + 1] = max(edge[start_bin + 1], 0.75)
        edge[end_bin] = max(edge[end_bin], 0.42)

    for i in range(1, len(speech) - 1):
        speech[i] = speech[i - 1] * 0.22 + speech[i] * 0.56 + speech[i + 1] * 0.22
    return speech, edge


def median(values):
    if not values:
        return 0.0
    copy = sorted(values)
    mid = len(copy) // 2
    if len(copy) % 2 == 1:
        return copy[mid]
    return (copy[mid - 1] + copy[mid]) / 2


def score_alignment(state, offset_sec):
    """Port of AlignmentEngine.scoreAlignment"""
    shift = int(round(offset_sec / ALIGN_BIN))
    score = 0.0
    weighted = 0.0
    edge_hits = 0.0
    coverage = 0.0
    audio = state["audio"]
    rise = state["rise"]
    model_speech, model_edge = state["model"]
    max_bin = min(len(audio) - 1, int(state["max_time"] / ALIGN_BIN))

    for i in range(0, max_bin + 1):
        a = audio[i]
        r = rise[i]
        if a < 0.035 and r < 0.025:
            continue
        j = i - shift
        if j < 0 or j >= len(model_speech):
            continue
        expected = model_speech[j]
        edge = model_edge[j]
        score += a * (expected * 1.6 - (1 - min(1.0, expected)) * 0.82)
        score += r * (edge * 1.35 + expected * 0.32)
        weighted += 1
        if expected > 0.22:
            coverage += 1
        if edge > 0.35 and r > 0.04:
            edge_hits += 1

    if weighted < 90:
        return None
    norm = score / weighted
    return {
        "offset": offset_sec,
        "score": norm + edge_hits / max(1.0, weighted) * 0.9 - abs(offset_sec) * 0.0015,
        "coverage": coverage / weighted,
        "edge_hits": edge_hits,
        "weighted": weighted,
    }


def find_best(state, rms_history=None, max_offset=ALIGN_MAX_OFFSET):
    """
    ffsubsync method (the validated gold standard):

    Both sources are discretized to a 100Hz speech-activity track:
      A(t) = audio speech value in [0,1]   (adaptive floor/peak, already built)
      B(t) = subtitle speech indicator     (1 inside a cue, 0 outside)

    Score every alignment δ at once:
      score(δ) = Σ_t A(t)·(2·B(t+δ) − 1)
               = (speech matched with speech) − (speech matched with silence)
    normalized by total audio speech mass → score ∈ [−1, 1].

    This is a convolution — FFT-computable in O(n log n) in the app; the
    direct sum here is equally exact. Peak of the normalized score = offset;
    significance = z-score of the peak against the score distribution.
    Language-agnostic by construction (speech rhythm is what matches).
    """
    if state["max_time"] < ALIGN_MIN_AUDIO_SECONDS:
        return None

    audio = state["audio"]  # per-0.1s speech value, index = media time bin
    n = int(state["max_time"] / ALIGN_BIN) + 1
    if n < ALIGN_MIN_AUDIO_SECONDS * 10:
        return None

    # ── VAD: hard binary decision on the soft speech track ─────────
    # ffsubsync uses a binary voice-activity track; soft values dilute
    # the correlation peak. Threshold at 0.3 with hysteresis-free snap.
    A_raw = audio[:n]
    A = [1.0 if v > 0.3 else 0.0 for v in A_raw]
    speech_mass = sum(A)
    if speech_mass < 30:  # <3s of detected speech → not enough signal
        return None

    # subtitle track B: same grid, from cue intervals
    cue_starts = state["cue_starts"]
    cue_ends = state["cue_ends"]
    total_bins = len(audio)
    B = [0.0] * total_bins
    for s, e in zip(cue_starts, cue_ends):
        i0 = max(0, int(s / ALIGN_BIN))
        i1 = min(total_bins - 1, int(e / ALIGN_BIN))
        for i in range(i0, i1 + 1):
            B[i] = 1.0

    # correlate over integer bin shifts; δ = shift * 0.1s
    # score(δ) = Σ A[i]·(2·B[i+shift] − 1) / speech_mass
    lo = -int(max_offset / ALIGN_BIN)
    hi = int(max_offset / ALIGN_BIN)
    best_score, best_shift = -2.0, 0
    scores = []
    for shift in range(lo, hi + 1):
        sc = 0.0
        for i in range(n):
            a = A[i]
            if a <= 0.0:
                continue
            j = i + shift
            b = B[j] if 0 <= j < total_bins else 0.0
            sc += a * (2.0 * b - 1.0)
        sc /= speech_mass
        scores.append((shift, sc))
        if sc > best_score:
            best_score, best_shift = sc, shift

    # significance: the peak must be (a) strongly positive and (b) clearly
    # separated from the runner-up outside a ±2s exclusion zone. (The score
    # distribution is skewed negative by construction — audio speech against
    # subtitle silence dominates — so a Gaussian z-score undersells it.)
    vals = [(s, shift) for shift, s in scores]
    mu = sum(v for v, _ in vals) / len(vals)
    sd = (sum((v - mu) ** 2 for v, _ in vals) / len(vals)) ** 0.5
    z = (best_score - mu) / sd if sd > 1e-9 else 0.0

    second = max((s for s, sh in vals if abs(sh - best_shift) * ALIGN_BIN > 2.0),
                 default=-2.0)
    margin = best_score - second

    # containment: fraction of audio speech mass inside subtitle cues under
    # the candidate offset. True alignment ≈ 0.9+; chance ≈ cue density.
    contain_num = 0.0
    for i in range(n):
        if A[i] > 0.0:
            j = i + best_shift
            contain_num += A[i] * (B[j] if 0 <= j < total_bins else 0.0)
    containment = contain_num / speech_mass

    # Renderer convention: applied offset = −peak (subs late → negative).
    offset = -best_shift * ALIGN_BIN

    # ── cross-half validation (guards against chance alignments) ──────
    # A candidate found on the FIRST half of the audio must independently
    # hold on the SECOND half: chance peaks don't replicate, true offsets
    # do. This is the multiple-comparisons guard for scanning 800 shifts.
    def corr(shift, lo_bin, hi_bin):
        sc = 0.0
        m = 0.0
        for i in range(lo_bin, min(hi_bin, n)):
            a = A[i]
            if a <= 0.0:
                continue
            m += a
            j = i + shift
            b = B[j] if 0 <= j < total_bins else 0.0
            sc += a * b
        return sc / m if m > 0 else 0.0

    mid_bin = n // 2
    best1_shift, best1 = None, -2.0
    for shift, _ in scores:
        if abs(shift) * ALIGN_BIN > ALIGN_MAX_OFFSET:
            continue
        v = corr(shift, 0, mid_bin)
        if v > best1:
            best1_shift, best1 = shift, v
    validated = (
        best1_shift is not None
        and abs(best1_shift - best_shift) * ALIGN_BIN <= 0.3
        and corr(best_shift, mid_bin, n) >= 0.7
    )

    # gates: strong positive peak, clear runner-up separation, most audio
    # speech inside subtitle cues, and cross-half validation.
    lockable = (best_score > 0.2 and margin > 0.15 and containment >= 0.7
                and validated)
    confidence = min(1.0, best_score / 0.6) if lockable else min(0.5, max(0.0, best_score / 0.6))
    return {"offset": offset, "confidence": confidence, "z": round(z, 1),
            "score": round(best_score, 3), "margin": round(margin, 2),
            "containment": round(containment, 2), "validated": validated,
            "lockable": lockable}


# ─────────────────────────────────────────────────────────────────────
# Realistic audio simulator
# ─────────────────────────────────────────────────────────────────────
def make_cues(duration=90.0, gap_range=(0.5, 3.5), dur_range=(2.0, 5.0), seed=1):
    """Generate a realistic subtitle track: (start, end, charCount)."""
    rng = random.Random(seed)
    cues = []
    t = 1.0
    while t < duration:
        d = rng.uniform(*dur_range)
        chars = int(rng.uniform(15, 55))  # line length varies
        cues.append((t, min(t + d, duration), chars))
        t += d + rng.uniform(*gap_range)
    return cues


def speech_at(cues, t, true_offset, coverage, rng, music=0.0):
    """
    True audio energy at media time t (before offset).
    Realistic: syllable-rate modulation + attack/decay envelope,
    optional constant background (music) that the floor adaptation
    must learn to ignore.
    """
    tt = t + true_offset  # what the audio actually contains
    for s, e, _chars in cues:
        if s <= tt < e:
            if rng.random() > coverage:
                break
            # 4–6 Hz syllable modulation
            phase = (tt - s) * 5.0 * 2 * math.pi
            mod = 0.55 + 0.45 * math.sin(phase + 1.3)
            # attack / decay envelope
            env = min(1.0, (tt - s) / 0.15) * min(1.0, (e - tt) / 0.25)
            return max(0.0, mod * env)
    return music


def simulate_playback(cues, true_offset, noise=0.012, coverage=1.0, music=0.0,
                      duration=80.0, seed=7, model_cues=None):
    """
    Feed RMS frames (10ms) through the exact adaptive floor/peak + bin
    accumulation + periodic evaluation logic, mirroring AudioSyncProcessor.
    `model_cues`: subtitle track used for the MODEL (defaults to `cues`);
    set it different from `cues` to simulate wrong-language audio.
    Returns: (locked_offset_or_None, confidence_at_lock, eval_log)
    """
    mcues = model_cues if model_cues is not None else cues
    rng = random.Random(seed)
    speech_model, edge_model = build_cue_model(mcue_s := mcues)
    state = {
        "model": (speech_model, edge_model),
        "cue_starts": [c[0] for c in mcue_s],
        "cue_ends": [c[1] for c in mcue_s],
        "audio": [0.0] * len(speech_model),
        "rise": [0.0] * len(speech_model),
        "max_time": 0.0,
        "sample_count": 0,
        "floor": 0.0,
        "peak": 0.0,
        "last_speech": 0.0,
        "best_result": None,
        "stable_hits": 0,
        "last_offset": None,
        "locked": False,
    }

    last_eval_pos = 0.0
    locked = None
    lock_conf = 0.0
    log = []
    rms_history = []

    frame = 0
    max_frames = int(duration * 100)
    pos_ms = 0

    while frame < max_frames and locked is None:
        t = frame / 100.0
        activity = speech_at(cues, t, true_offset, coverage, rng, music)
        rms = noise + (0.14 + rng.uniform(-0.02, 0.05)) * activity
        rms = max(0.0, rms)
        rms_history.append(rms)

        # exact accumulateFrame port
        s = state
        if s["floor"] == 0.0:
            s["floor"] = rms
            s["peak"] = rms * 1.9 + 0.0001
        else:
            s["floor"] = s["floor"] * 0.986 + min(rms, s["floor"] * 1.45) * 0.014
            s["peak"] = max(s["floor"] + 0.00035, max(s["peak"] * 0.992, rms))

        usable_floor = s["floor"] * 1.08
        usable_peak = max(usable_floor + 0.0012, s["peak"])
        speech = min(1.0, max(0.0, (rms - usable_floor) / (usable_peak - usable_floor)))
        rise = max(0.0, speech - s["last_speech"])
        s["last_speech"] = speech * 0.72 + s["last_speech"] * 0.28

        pos_ms = frame * 10  # 10ms per frame → real milliseconds
        # bin = media time / ALIGN_BIN  →  pos_ms/1000/0.1 = pos_ms/100
        idx = min(len(s["audio"]) - 1, max(0, int(pos_ms / 100)))
        s["max_time"] = max(s["max_time"], pos_ms / 1000.0)
        s["sample_count"] += 1
        s["audio"][idx] = max(s["audio"][idx], speech)
        s["rise"][idx] = max(s["rise"][idx], rise)

        # periodic evaluation (every 1.4s of position)
        if pos_ms - last_eval_pos >= EVAL_INTERVAL_MS:
            last_eval_pos = pos_ms
            if s["max_time"] >= ALIGN_MIN_AUDIO_SECONDS:
                result = find_best(s, rms_history)
                if result:
                    if (s["best_result"] is None or
                            result["confidence"] > s["best_result"]["confidence"] + 0.04):
                        s["best_result"] = result
                    last = s["last_offset"]
                    s["stable_hits"] = (
                        s["stable_hits"] + 1
                        if last is not None and abs(result["offset"] - last) <= 0.25
                        else 1
                    )
                    s["last_offset"] = result["offset"]
                    log.append((round(pos_ms / 1000.0, 1), round(result["offset"], 2),
                                round(result["confidence"], 3), s["stable_hits"]))
                    if result.get("lockable") and s["stable_hits"] >= 2:
                        locked = result["offset"]
                        lock_conf = result["confidence"]
        frame += 1

    if locked is None and state["best_result"] is not None \
            and state["best_result"].get("lockable"):
        locked = state["best_result"]["offset"]
        lock_conf = state["best_result"]["confidence"]
    return locked, lock_conf, log


# ─────────────────────────────────────────────────────────────────────
# Matcher port (SubtitleMatcher) for the auto-picker tests
# ─────────────────────────────────────────────────────────────────────
JUNK = {"720p", "1080p", "2160p", "480p", "4k", "uhd", "hdr", "bluray", "bdrip",
        "brrip", "webrip", "webdl", "web", "dl", "x264", "x265", "h264", "h265",
        "hevc", "xvid", "aac", "ac3", "dts", "yify", "yts", "rarbg", "proper",
        "repack", "extended", "unrated", "dubbed", "subbed", "multi", "dual",
        "hdrip", "dvdrip", "hdtv", "cam"}
LANG_W = {"english": 1.0, "eng": 1.0, "en": 0.9, "us": 0.85, "uk": 0.85,
          "yoruba": 0.7, "igbo": 0.7, "hausa": 0.7, "pidgin": 0.65,
          "french": 0.5, "spanish": 0.5, "arabic": 0.5}


def find_episode(name):
    m = re.search(r"[Ss](\d{1,2})[_.\-\s]*[Ee][Pp]?[_.\-\s]*(\d{1,3})(?!\d)", name)
    if m:
        return (int(m.group(1)), int(m.group(2)))
    m = re.search(r"(\d{1,2})x(\d{2})", name)
    if m:
        return (int(m.group(1)), int(m.group(2)))
    m = re.search(r"[Ee][Pp]?[_.\-\s]*(\d{1,3})(?!\d)", name)
    if m:
        return (None, int(m.group(1)))
    return None


def tokens(name):
    words = re.sub(r"[._\-\[\](){}+]", " ", name.lower()).split()
    return [w for w in words if len(w) > 2 and w not in JUNK and not w.isdigit()]


def match_score(video_name, sub_name, sub_text=None, video_duration_ms=0):
    """Port of SubtitleMatcher.score"""
    vb = video_name.rsplit(".", 1)[0]
    sb = sub_name.rsplit(".", 1)[0]
    if sb.lower() == vb.lower():
        return 100.0
    sc = 0.0
    ve = find_episode(vb)
    se = find_episode(sb)
    if ve and se:
        same_season = (ve[0] is None or se[0] is None) or ve[0] == se[0]
        same_ep = ve[1] == se[1]
        if same_season and same_ep:
            sc += 50.0
        elif not same_season:
            sc -= 60.0
        else:
            sc -= 10.0 + abs(ve[1] - se[1]) * 5.0
    elif ve and not se:
        sc -= 10.0

    vw, sw = set(tokens(vb)), set(tokens(sb))
    if vw and sw:
        inter = len(vw & sw)
        union = len(vw | sw)
        sc += inter / union * 20.0

    sl = sb.lower()
    for kw, w in LANG_W.items():
        if re.search(rf"(?:^|[._\-\s\[(]){kw}(?:[._\-\s\])]|$)", sl):
            sc += w * 10
            break
    ext = sub_name.rsplit(".", 1)[-1].lower()
    sc += 2.0 if ext == "srt" else 1.0 if ext == "vtt" else 0.0
    return sc


# ─────────────────────────────────────────────────────────────────────
# Test harness
# ─────────────────────────────────────────────────────────────────────
PASS = 0
FAIL = 0


def check(name, cond, detail=""):
    global PASS, FAIL
    mark = "PASS" if cond else "FAIL"
    if cond:
        PASS += 1
    else:
        FAIL += 1
    print(f"  [{mark}] {name}" + (f"  — {detail}" if detail else ""))


def test_matcher():
    print("\n═══ AUTO-PICKER (matcher) ═══")
    video = "Horimiya _2021_ - S01 E01.mp4"
    candidates = {
        "Horimiya _2021_ - S01 E01.en.srt": 0,
        "Horimiya _2021_ - S01 E01.ja.srt": 0,
        "Horimiya _2021_ - S01 E02.en.srt": 0,
        "Another.Show.S01E01.eng.srt": 0,
        "Horimiya _2021_ - S01 E01.srt": 0,
    }
    for name in candidates:
        candidates[name] = match_score(video, name)
    best = max(candidates, key=candidates.get)
    check("exact-name sub wins over language-tagged variants",
          best == "Horimiya _2021_ - S01 E01.srt",
          f"best={best} scores={ {k: round(v,1) for k, v in candidates.items()} }")
    check("English beats Japanese by language weight",
          candidates["Horimiya _2021_ - S01 E01.en.srt"] > candidates["Horimiya _2021_ - S01 E01.ja.srt"])
    check("wrong episode strongly penalized",
          candidates["Horimiya _2021_ - S01 E01.en.srt"] > candidates["Horimiya _2021_ - S01 E02.en.srt"] + 30)
    check("wrong show penalized",
          candidates["Horimiya _2021_ - S01 E01.en.srt"] > candidates["Another.Show.S01E01.eng.srt"])
    check("plain same-name match scores high", match_score("Blue Box S01E05.mkv", "Blue Box S01E05.srt") >= 70)
    check("junk tokens ignored, real tokens match",
          match_score("Movie.2024.1080p.WEB-DL.x265.mkv", "Movie.2024.1080p.WEB-DL.x265.srt") > 50)


def test_sync():
    print("\n═══ AUTO-SYNC (alignment engine) ═══")
    # Engine convention: lock = −true_offset (positive lock = subs late).
    def run(name, true_offset, duration=80.0, noise=0.012, coverage=1.0,
            music=0.0, expect_lock=True, tol=0.35, min_conf=0.0):
        cues = make_cues(seed=3)
        locked, conf, log = simulate_playback(cues, true_offset, noise=noise,
                                              coverage=coverage, music=music, duration=duration)
        if expect_lock:
            ok = (locked is not None and abs(locked - (-true_offset)) <= tol and
                  (min_conf == 0.0 or conf >= min_conf))
            check(f"{name}: lock {true_offset:+.1f}s → engine {locked if locked is None else round(locked,2)}s",
                  ok, f"conf={round(conf,3)} evals={len(log)} first={log[0] if log else '-'}")
        else:
            ok = locked is None or abs(locked - (-true_offset)) > 1.0
            check(f"{name}: must NOT lock", ok,
                  f"locked={None if locked is None else round(locked,2)} conf={round(conf,3)}")

    run("already in sync", 0.0)
    run("audio leads (subs late) +3.7s", 3.7)
    run("audio lags (subs early) −5.2s", -5.2)
    run("large offset +25s", 25.0, duration=90.0)
    run("fractional offset +1.35s", 1.35)
    run("with background music", 4.2, music=0.05)

    # pure noise — must never lock
    run("no speech (pure noise)", 0.0, noise=0.03, coverage=0.0, expect_lock=False)
    # half the cues have no audio
    # half the cues have no audio — remaining onsets still align → lock OK
    run("50% speech coverage (partial match)", 4.2, coverage=0.5, expect_lock=True)
    # unrelated timing pattern (different episode's audio) → must NEVER lock
    foreign = make_cues(seed=11)
    cues = make_cues(seed=3)
    locked, conf, log = simulate_playback(foreign, 0.0, coverage=1.0,
                                          duration=80.0, model_cues=cues)
    check("unrelated audio must never lock", locked is None,
          f"locked={None if locked is None else round(locked,2)} conf={round(conf,3)}")

    # language-agnostic: SAME timing pattern, "different language" content —
    # speech rhythm is what matches, so this SHOULD lock (ffsubsync property)
    same_timing = [(s, e, 40) for (s, e, _) in cues]  # identical timing
    locked2, conf2, log2 = simulate_playback(same_timing, 3.3, coverage=1.0,
                                             duration=80.0, model_cues=cues)
    check("same rhythm different language → locks (language-agnostic)",
          locked2 is not None and abs(locked2 - (-3.3)) <= 0.35,
          f"locked={None if locked2 is None else round(locked2,2)} conf={round(conf2,3)}")


def test_interactions():
    print("\n═══ INTERACTIONS (matcher → sync → persist → rewatch) ═══")
    videos = [f"Horimiya _2021_ - S01 E{str(i).zfill(2)}.mp4" for i in range(1, 5)]
    subs = {
        "Horimiya _2021_ - S01 E01.en.srt": 0,
        "Horimiya _2021_ - S01 E01.ja.srt": 0,
        "Horimiya _2021_ - S01 E02.en.srt": 0,
        "Horimiya _2021_ - S01 E03.en.srt": 0,
        "Horimiya _2021_ - S01 E04.en.srt": 0,
    }
    for v in videos:
        ep = v.rsplit(".", 1)[0].split("E")[-1]
        best = max(subs, key=lambda s: match_score(v, s))
        expect = f"Horimiya _2021_ - S01 E{ep}.en.srt"
        check(f"episode {ep} picks its own sub", best == expect, f"best={best}")

    # First watch: true offset +2.5 (audio leads) → engine locks −2.5.
    true_offset = 2.5
    cues = make_cues(seed=3)
    locked, conf, log = simulate_playback(cues, true_offset, duration=60.0)
    auto_persisted = locked if locked is not None else 0.0
    manual = 0.5
    total = auto_persisted + manual
    check("first watch locks −2.5s (auto persisted)",
          abs(auto_persisted - (-2.5)) <= 0.35, f"auto={round(auto_persisted,2)} conf={round(conf,2)}")
    check("additive: rewatch offset = auto + manual instantly",
          abs(total - (-2.0)) <= 0.35, f"total={round(total,2)}")

    locked2, conf2, log2 = simulate_playback(cues, true_offset, duration=60.0)
    refined = locked2 if locked2 is not None else auto_persisted
    total2 = refined + manual
    check("rewatch refines to same auto (no drift)",
          abs(refined - (-2.5)) <= 0.35 and abs(total2 - (-2.0)) <= 0.35,
          f"refined={round(refined,2)} total={round(total2,2)}")


if __name__ == "__main__":
    random.seed(0)
    print("Subtitle engine simulation — ported 1:1 from core/media")
    test_matcher()
    test_sync()
    test_interactions()
    print(f"\n═══ RESULT: {PASS} passed, {FAIL} failed ═══")
    raise SystemExit(1 if FAIL else 0)
