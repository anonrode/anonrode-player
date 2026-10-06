#include "correlator.h"
#include <algorithm>
#include <cmath>

namespace anonsync {

MultiScaleAligner::MultiScaleAligner(const AlignerConfig& config)
    : mConfig(config) {}

void MultiScaleAligner::evaluateOffset(
    const std::vector<double>& sortedOnsets,
    const std::vector<double>& sortedCues,
    double minAudio, double maxAudio,
    double offset, double alpha,
    int& outHits, int& outLocalCues, double& outScore
) {
    outHits = 0;
    outLocalCues = 0;
    outScore = 0.0;
    if (sortedOnsets.empty() || sortedCues.empty()) return;

    double tol = mConfig.hitToleranceSec;
    double weightedHits = 0.0;

    for (double s : sortedCues) {
        double t = s * alpha + offset;
        if (t >= (minAudio - 0.5) && t <= (maxAudio + 0.5)) {
            outLocalCues++;
            auto it = std::lower_bound(sortedOnsets.begin(), sortedOnsets.end(), t);
            double bestDiff = 999.0;
            if (it != sortedOnsets.end()) {
                bestDiff = std::min(bestDiff, std::abs(*it - t));
            }
            if (it != sortedOnsets.begin()) {
                bestDiff = std::min(bestDiff, std::abs(*(it - 1) - t));
            }
            if (bestDiff <= tol) {
                outHits++;
                weightedHits += (1.0 - 0.5 * (bestDiff / tol));
            }
        }
    }

    if (outLocalCues > 0 && outHits > 0) {
        double recall = weightedHits / outLocalCues;
        double precision = weightedHits / sortedOnsets.size();
        outScore = 0.5 * recall + 0.5 * precision;
    }
}

SyncResult MultiScaleAligner::align(
    const std::vector<double>& audioOnsets,
    const std::vector<double>& cueStarts,
    const std::vector<double>& cueEnds,
    double activeOffsetSec
) {
    SyncResult result;
    if (audioOnsets.empty() || cueStarts.empty()) return result;

    std::vector<double> sortedOnsets = audioOnsets;
    std::sort(sortedOnsets.begin(), sortedOnsets.end());

    std::vector<double> sortedCues = cueStarts;
    std::sort(sortedCues.begin(), sortedCues.end());

    double minAudio = sortedOnsets.front();
    double maxAudio = sortedOnsets.back();

    // Trial slopes for PAL drift (1.0 default, plus 23.976 <-> 25 fps conversions)
    const double trialSlopes[] = {1.0, 0.95904, 1.0427};
    constexpr size_t numSlopes = sizeof(trialSlopes) / sizeof(trialSlopes[0]);

    double bestScore = 0.0;
    double bestOffset = activeOffsetSec;
    double bestAlpha = 1.0;
    int bestHits = 0;
    int bestLocalCues = 0;

    // Coarse search grid (50ms step across search radius)
    for (size_t sIdx = 0; sIdx < numSlopes; ++sIdx) {
        double alpha = trialSlopes[sIdx];
        double b = mConfig.minSearchOffsetSec;
        while (b <= mConfig.maxSearchOffsetSec + 1e-6) {
            int hits = 0;
            int localCues = 0;
            double score = 0.0;
            evaluateOffset(sortedOnsets, sortedCues, minAudio, maxAudio, b, alpha, hits, localCues, score);

            if (score > bestScore) {
                bestScore = score;
                bestOffset = b;
                bestAlpha = alpha;
                bestHits = hits;
                bestLocalCues = localCues;
            }
            b += mConfig.coarseStepSec;
        }
    }

    if (bestHits == 0) return result;

    // Fine refinement (10ms steps within +- 60ms of best candidate)
    double fineStart = bestOffset - 0.06;
    double fineEnd = bestOffset + 0.06;
    double fineB = fineStart;
    while (fineB <= fineEnd + 1e-6) {
        int hits = 0;
        int localCues = 0;
        double score = 0.0;
        evaluateOffset(sortedOnsets, sortedCues, minAudio, maxAudio, fineB, bestAlpha, hits, localCues, score);
        if (score > bestScore) {
            bestScore = score;
            bestOffset = fineB;
            bestHits = hits;
            bestLocalCues = localCues;
        }
        fineB += 0.01;
    }

    // Runner-up search (strictly outside +- 2.0s of bestOffset to eliminate harmonic cadence aliases)
    double runnerScore = 0.0;
    double b = mConfig.minSearchOffsetSec;
    while (b <= mConfig.maxSearchOffsetSec + 1e-6) {
        if (std::abs(b - bestOffset) > 2.0) {
            int hits = 0;
            int localCues = 0;
            double score = 0.0;
            evaluateOffset(sortedOnsets, sortedCues, minAudio, maxAudio, b, bestAlpha, hits, localCues, score);
            if (score > runnerScore) {
                runnerScore = score;
            }
        }
        b += mConfig.coarseStepSec;
    }

    double margin = bestScore - runnerScore;
    double recall = (bestLocalCues > 0) ? (static_cast<double>(bestHits) / bestLocalCues) : 0.0;
    double precision = static_cast<double>(bestHits) / sortedOnsets.size();

    result.offsetSec = bestOffset;
    result.speedFactor = bestAlpha;
    result.hits = bestHits;
    result.localCues = bestLocalCues;
    result.recall = recall;
    result.precision = precision;
    result.margin = margin;

    // Gating check
    if (bestHits >= mConfig.minHits && bestLocalCues >= mConfig.minHits &&
        recall >= mConfig.minRecall && margin >= mConfig.minMargin) {
        result.locked = true;
    }

    // Piecewise cut analysis (if we have sufficient cues across a long span)
    if (result.locked && sortedCues.size() >= 30 && (maxAudio - minAudio) > 600.0) {
        // Divide cues into two halves around the midpoint to test if second half has a cut jump
        double midCue = sortedCues[sortedCues.size() / 2];
        std::vector<double> cuesHalf2;
        for (double s : sortedCues) {
            if (s >= midCue) cuesHalf2.push_back(s);
        }

        if (cuesHalf2.size() >= 15) {
            double h2BestScore = 0.0;
            double h2BestOffset = bestOffset;
            int h2Hits = 0, h2Local = 0;

            double hb = mConfig.minSearchOffsetSec;
            while (hb <= mConfig.maxSearchOffsetSec + 1e-6) {
                int hits = 0, local = 0;
                double score = 0.0;
                evaluateOffset(sortedOnsets, cuesHalf2, minAudio, maxAudio, hb, bestAlpha, hits, local, score);
                if (score > h2BestScore) {
                    h2BestScore = score;
                    h2BestOffset = hb;
                    h2Hits = hits;
                    h2Local = local;
                }
                hb += 0.1;
            }

            if (std::abs(h2BestOffset - bestOffset) > 2.0 && h2Hits >= 8 &&
                (static_cast<double>(h2Hits) / h2Local) >= 0.65) {
                result.hasCut = true;
                result.cutTimeSec = midCue;
                result.cutOffsetSec = h2BestOffset;
            }
        }
    }

    return result;
}

} // namespace anonsync
