#!/usr/bin/env python3
"""Measure the exact onset bias: median(s - o) for matched pairs."""
import sys, os, random
sys.path.insert(0, os.path.dirname(__file__))
import subtitle_engine_sim as sim

cues = sim.make_cues(seed=3)
cue_starts = [c[0] for c in cues]
speech_model, edge_model = sim.build_cue_model(cues)
state = {"model": (speech_model, edge_model), "cue_starts": cue_starts,
         "audio": [0.0]*len(speech_model), "rise": [0.0]*len(speech_model),
         "max_time": 0.0, "sample_count": 0, "floor": 0.0, "peak": 0.0,
         "last_speech": 0.0, "best_result": None, "stable_hits": 0,
         "last_offset": None, "locked": False}
rng = random.Random(9)
rms_history = []
for frame in range(int(60.0*100)):
    t = frame/100.0
    activity = sim.speech_at(cues, t, 0.0, 1.0, rng, 0.0)
    rms = max(0.0, 0.012 + (0.14 + rng.uniform(-0.02, 0.05))*activity)
    rms_history.append(rms)
    s = state
    if s["floor"] == 0.0:
        s["floor"] = rms; s["peak"] = rms*1.9 + 0.0001
    else:
        s["floor"] = s["floor"]*0.986 + min(rms, s["floor"]*1.45)*0.014
        s["peak"] = max(s["floor"]+0.00035, max(s["peak"]*0.992, rms))
    uf = s["floor"]*1.08; up = max(uf+0.0012, s["peak"])
    sp = min(1.0, max(0.0, (rms-uf)/(up-uf)))
    rise = max(0.0, sp - s["last_speech"]); s["last_speech"] = sp*0.72 + s["last_speech"]*0.28
    pos_ms = frame*10
    idx = min(len(s["audio"])-1, max(0, int((pos_ms/100.0)/sim.ALIGN_BIN)))
    s["max_time"] = max(s["max_time"], pos_ms/1000.0)
    s["audio"][idx] = max(s["audio"][idx], sp)
    s["rise"][idx] = max(s["rise"][idx], rise)

# detect onsets exactly as find_best does (with backtrack)
raw = []; last=-99.0; rolling=[]
for fi, rms in enumerate(rms_history):
    rolling.append(rms)
    if len(rolling) > 120: rolling.pop(0)
    avg = sum(rolling)/len(rolling)
    t = fi*0.01
    if rms > 0.006 and rms > avg*2.2 and t-last > 0.35:
        raw.append(fi); last = t
onsets = []
for fi in raw:
    j = fi
    while j > 0 and rms_history[j-1] < rms_history[j] and fi-j < 30:
        j -= 1
    onsets.append(j*0.01)

print(f"onsets: {len(onsets)}")
res = []
for o in onsets:
    near = min(cue_starts, key=lambda s: abs(o-s))
    res.append((round(o-near, 2), round(o,2), round(near,2)))
print("onset - nearest_cue_start (o-s):", sorted(res)[:20])
offs = [r[0] for r in res]
offs.sort()
mid = len(offs)//2
med = offs[mid] if len(offs)%2 else (offs[mid-1]+offs[mid])/2
print(f"MEDIAN(o-s) = {med:.3f}  ← this is the detector bias")
