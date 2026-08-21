#!/usr/bin/env python3
"""Debug the score landscape for a shifted-offset case."""
import sys, os
sys.path.insert(0, os.path.dirname(__file__))
import subtitle_engine_sim as sim

cues = sim.make_cues(seed=3)
true_offset = 3.7
locked, conf, log = sim.simulate_playback(cues, true_offset, duration=40.0)
print(f"LOCKED={locked} conf={conf}")

# Rebuild state the same way, then dump landscape
speech_model, edge_model = sim.build_cue_model(cues)
state = {
    "model": (speech_model, edge_model),
    "audio": [0.0] * len(speech_model),
    "rise": [0.0] * len(speech_model),
    "max_time": 0.0, "sample_count": 0, "floor": 0.0, "peak": 0.0,
    "last_speech": 0.0, "best_result": None, "stable_hits": 0,
    "last_offset": None, "locked": False,
}
rng = random = __import__("random").Random(9)
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

print(f"max_time={s['max_time']:.1f} active_bins={sum(1 for a in s['audio'] if a>0.035)}")
print(f"speech_density_model={sum(1 for x in speech_model if x>0.22)/len(speech_model):.2f}")

print("\noffset  score     cov   edgeHits  weighted")
for off in [x/10.0 for x in range(-80, 81, 5)]:
    r = sim.score_alignment(state, off)
    if r:
        print(f"{off:6.1f} {r['score']:8.3f} {r['coverage']:5.2f} {r['edge_hits']:8.0f} {r['weighted']:8.0f}")
