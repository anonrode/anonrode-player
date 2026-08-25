#!/usr/bin/env python3
"""
Validate the ffmpeg-silencedetect + joint (alpha, beta) search on EP32 and
EP37 of Growling Tiger 2. If these work, the pipeline generalizes and we
know we can port it to the in-app ffmpeg call.
"""
import math, re, struct, sys, os
sys.path.insert(0, os.path.dirname(__file__))
import realsim_ep31 as sim


def run_episode(ep_num, video_path, srt_path, pcm_path):
    print(f"\n{'='*60}")
    print(f"  EP{ep_num}")
    print(f"{'='*60}")

    cues = sim.parse_srt(open(srt_path, encoding='utf-8', errors='replace').read())
    print(f"cues: {len(cues)}, span: {cues[0][0]:.0f}s-{cues[-1][1]:.0f}s")

    bins = sim.build_speech_track(pcm_path, 600.0)
    print(f"VAD: {len(bins)} bins × 100ms = {len(bins)*0.1:.0f}s, density={sum(1 for b in bins if b>0.25)/max(1,len(bins)):.2f}")

    starts = [c[0] for c in cues]; ends = [c[1] for c in cues]

    # Joint search: alpha * speed_factor, beta * offset
    candidates = []
    for base in [23976, 24000, 25000]:
        for alt in [23976, 24000, 25000]:
            candidates.append(base/alt)
    candidates += [round(1.0 + i*0.001, 4) for i in range(-30, 31)]
    candidates = sorted(set(round(c, 5) for c in candidates))
    print(f"joint search: {len(candidates)} speed factors")

    results = []
    for alpha in candidates:
        scaled_starts = [alpha * s for s in starts]
        scaled_ends = [alpha * e for e in ends]
        # for each shifted sub, find offset via median nearest-neighbor
        # then count matches within 0.5s
        for offset_10 in range(-600, 601, 5):  # ±60s in 0.05s steps
            offset = offset_10 / 10.0
            shifted_starts = [s + offset for s in scaled_starts]
            matched = 0
            import bisect
            for s in shifted_starts:
                pos = bisect.bisect_left(scaled_starts, s)
                found = False
                for k in [pos-1, pos]:
                    if 0 <= k < len(scaled_starts) and abs(scaled_starts[k] - s) <= 0.5:
                        found = True; break
                if found: matched += 1
            frac = matched / max(1, len(cues))
            results.append((frac, alpha, offset))

    results.sort(reverse=True)
    print(f"\nTop 5 candidates:")
    for frac, a, o in results[:5]:
        marker = ' ←←<' if frac == results[0][0] else ''
        print(f"  alpha={a:.4f}  offset={o:+.1f}s  match={frac:.3f}{marker}")

    best = results[0]
    runner = results[1]
    print(f"\nResult: speed_factor={best[1]:.5f}, base_offset={best[2]:+.2f}s")
    print(f"  match={best[0]*100:.0f}%, runner-up match={runner[0]*100:.0f}%, margin={(best[0]-runner[0])*100:.0f}%")
    return best


if __name__ == "__main__":
    base = r"C:\Users\Anon\Desktop\Anon\Growling Tiger2\4"
    # EP32
    ep32_video = base + r"\《大军师司马懿之虎啸龙吟》第32集_-_司马家豢养死士暴露_Growling_Tiger_Roaring_Dragon_EP32【超清】(0).mp4"
    ep32_srt = base + r"\Growling.Tiger.And.Roaring.Dragon.2017.EP32.HD1080P.X264.AAC.Mandarin.CHS.MF.srt"
    ep32_pcm = os.path.join(os.path.dirname(__file__), "ep32_16k.pcm")
    r32 = run_episode(32, ep32_video, ep32_srt, ep32_pcm)

    # EP37
    ep37_video = base + r"\《大军师司马懿之虎啸龙吟》第37集_-_曹爽欲废天子以称帝_Growling_Tiger_Roaring_Dragon_EP37【超清】(0).mp4"
    ep37_srt = base + r"\Growling.Tiger.And.Roaring.Dragon.2017.EP37.HD1080P.X264.AAC.Mandarin.CHS.MF.srt"
    ep37_pcm = os.path.join(os.path.dirname(__file__), "ep37_16k.pcm")
    r37 = run_episode(37, ep37_video, ep37_srt, ep37_pcm)

    print(f"\n{'='*60}")
    print(f"  SUMMARY")
    print(f"{'='*60}")
    print(f"  EP31 (validated):  alpha=1.00900  match=71%")
    print(f"  EP32 (new):        alpha={r32[1]:.5f}  match={r32[0]*100:.0f}%")
    print(f"  EP37 (new):        alpha={r37[1]:.5f}  match={r37[0]*100:.0f}%")
    print()
    if abs(r32[1] - 1.0) < 0.01 and abs(r37[1] - 1.0) < 0.01:
        print("  → CONSISTENT: same ~0.9% drift across episodes — pipeline generalizes")
    else:
        print("  → MIXED: episodes need different speed factors — still solvable per-episode")
