#!/usr/bin/env python3
"""Debug onset detection + voting for the +3.7s case."""
import sys, os, random, math
sys.path.insert(0, os.path.dirname(__file__))
import subtitle_engine_sim as sim

cues = sim.make_cues(seed=3)
true_offset = 3.7
speech_model, edge_model = sim.build_cue_model(cues)
state = {
    "model": (speech_model, edge_model),
    "cue_starts": [c[0] for c in cues],
    "audio": [0.0] * len(speech_model),
    "rise": [0.0] * len(speech_model),
    "max_time": 0.0, "sample_count": 0, "floor": 0.0, "peak": 0.0,
    "last_speech": 0.0, "best_result": None, "stable_hits": 0,
    "last_offset": None, "locked": False,
}
rng = random.Random(9)
for frame in range(int(40.0 * 100)):
    t = frame / 100.0
    activity = sim.speech_at(cues, t, true_offset, 1.0, rng, 0.0)
    rms = max(0.0, 0.012 + (0.14 + rng.uniform(-0.02, 0.05)) * activity)
    s = state
    if s["floor"] == 0.0:
        s["floor"] = rms; s["peak"] = rms * 1.9 + 0.0001
    else:
        s["floor"] = s["floor"] * 0.986 + min(rms, s["floor"] * 1.45) * 0.014
        s["peak"] = max(s["floor"] + 0.00035, max(s["peak"] * 0.992, rms))
    uf = s["floor"] * 1.08; up = max(uf + 0.0012, s["peak"])
    sp = min(1.0, max(0.0, (rms - uf) / (up - uf)))
    rise = max(0.0, sp - s["last_speech"]); s["last_speech"] = sp * 0.72 + s["last_speech"] * 0.28
    pos_ms = frame * 10
    idx = min(len(s["audio"]) - 1, max(0, int((pos_ms / 100.0) / sim.ALIGN_BIN)))
    s["max_time"] = max(s["max_time"], pos_ms / 1000.0)
    s["audio"][idx] = max(s["audio"][idx], sp)
    s["rise"][idx] = max(s["rise"][idx], rise)

# onset stats
rise_vals = sorted([r for r in s["rise"] if r > 0.01], reverse=True)
print(f"cue_starts={[round(c,1) for c in state['cue_starts'][:8]]}... ({len(state['cue_starts'])} total)")
print(f"top rise values: {[round(v,3) for v in rise_vals[:12]]}")
print(f"bins with rise>0.05: {sum(1 for r in s['rise'] if r > 0.05)}")
print(f"bins with rise>0.02: {sum(1 for r in s['rise'] if r > 0.02)}")

onsets = []
last = -99.0
max_bin = int(state["max_time"] / sim.ALIGN_BIN)
for i in range(max_bin):
    if state["rise"][i] > 0.05:
        t = i * sim.ALIGN_BIN
        if t - last > 0.35:
            onsets.append(t); last = t
print(f"onsets (thr 0.05): {len(onsets)} -> {[round(o,1) for o in onsets[:10]]}")

# votes
votes = {}
for o in onsets:
    best_d = None
    for cst in state["cue_starts"]:
        d = o - cst
        if abs(d) > sim.ALIGN_MAX_OFFSET: continue
        if best_d is None or abs(d) < abs(best_d): best_d = d
    if best_d is None or abs(best_d) > 1.5: continue
    b = round(best_d / 0.25) * 0.25
    votes[b] = votes.get(b, 0) + 1
print(f"votes: {dict(sorted(votes.items()))}")
print(f"true offset = {-true_offset}")
