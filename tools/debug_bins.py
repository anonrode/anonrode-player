#!/usr/bin/env python3
"""Per-bin debug: audio speech values vs cue coverage."""
import sys, os, random
sys.path.insert(0, os.path.dirname(__file__))
import subtitle_engine_sim as sim

cues = sim.make_cues(seed=3)
print('cues:', [(round(s, 1), round(e, 1)) for s, e, _ in cues[:6]])
speech_model, edge_model = sim.build_cue_model(cues)
state = {"model": (speech_model, edge_model), "cue_starts": [c[0] for c in cues],
         "cue_ends": [c[1] for c in cues],
         "audio": [0.0] * len(speech_model), "rise": [0.0] * len(speech_model),
         "max_time": 0.0, "sample_count": 0, "floor": 0.0, "peak": 0.0,
         "last_speech": 0.0, "best_result": None, "stable_hits": 0,
         "last_offset": None, "locked": False}
rng = random.Random(9)
rms_hist = []
act_hist = []
for frame in range(int(12.0 * 100)):
    t = frame / 100.0
    activity = sim.speech_at(cues, t, 0.0, 1.0, rng, 0.0)
    act_hist.append(activity)
    rms = max(0.0, 0.012 + (0.14 + rng.uniform(-0.02, 0.05)) * activity)
    rms_hist.append(rms)
    s = state
    if s["floor"] == 0.0:
        s["floor"] = rms
        s["peak"] = rms * 1.9 + 0.0001
    else:
        s["floor"] = s["floor"] * 0.986 + min(rms, s["floor"] * 1.45) * 0.014
        s["peak"] = max(s["floor"] + 0.00035, max(s["peak"] * 0.992, rms))
    uf = s["floor"] * 1.08
    up = max(uf + 0.0012, s["peak"])
    sp = min(1.0, max(0.0, (rms - uf) / (up - uf)))
    pos_ms = frame * 10
    idx = min(len(s["audio"]) - 1, max(0, int((pos_ms / 100.0) / sim.ALIGN_BIN)))
    s["max_time"] = max(s["max_time"], pos_ms / 1000.0)
    s["audio"][idx] = max(s["audio"][idx], sp)

print('bin  t     act    A      inCue  rms')
for i in range(5, 56):
    t = i * 0.1
    incue = any(s_ <= t < e_ for s_, e_, _ in cues)
    print(f'{i:3d} {t:5.1f} {act_hist[i]:5.2f} {state["audio"][i]:5.2f}  '
          f'{"X" if incue else "."}  {rms_hist[i]:.3f}')
