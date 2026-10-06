#ifndef ANONSYNC_CORRELATOR_H
#define ANONSYNC_CORRELATOR_H

#include <vector>
#include <cstdint>
#include <cmath>

namespace anonsync {

struct SyncResult {
    bool locked{false};
    double offsetSec{0.0};
    double speedFactor{1.0};
    double recall{0.0};
    double precision{0.0};
    double margin{0.0};
    int hits{0};
    int localCues{0};

    // Piecewise cut detection (commercial breaks / TV cuts)
    bool hasCut{false};
    double cutTimeSec{0.0};
    double cutOffsetSec{0.0};
};

struct AlignerConfig {
    double minSearchOffsetSec{-120.0};
    double maxSearchOffsetSec{120.0};
    double coarseStepSec{0.05};       // 50ms coarse scan
    double hitToleranceSec{0.35};     // 350ms speech-to-cue hit window
    double minRecall{0.60};           // confidence gate
    double minMargin{0.08};           // runner-up separation gate
    int minHits{3};                   // minimum agreeing hits
};

class MultiScaleAligner {
public:
    explicit MultiScaleAligner(const AlignerConfig& config = AlignerConfig());

    SyncResult align(
        const std::vector<double>& audioOnsets,
        const std::vector<double>& cueStarts,
        const std::vector<double>& cueEnds,
        double activeOffsetSec = 0.0
    );

private:
    AlignerConfig mConfig;

    void evaluateOffset(
        const std::vector<double>& sortedOnsets,
        const std::vector<double>& sortedCues,
        double minAudio, double maxAudio,
        double offset, double alpha,
        int& outHits, int& outLocalCues, double& outScore
    );
};

} // namespace anonsync

#endif // ANONSYNC_CORRELATOR_H
