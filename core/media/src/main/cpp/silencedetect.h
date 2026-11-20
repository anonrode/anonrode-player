#ifndef ANONSYNC_SILENCEDETECT_H
#define ANONSYNC_SILENCEDETECT_H

#include <vector>
#include <cstdint>
#include <cmath>

namespace anonsync {

struct SilenceDetectConfig {
    double noiseThresholdDb{-36.0};      // static floor (~0.01585) matching Kotlin SilenceState
    double minSilenceDurationSec{0.18};  // 180ms minimum silence before onset
    int frameLengthSamples{160};         // recomputed from sampleRate (10ms)
    double sampleRate{16000.0};
};

class SilenceDetector {
public:
    explicit SilenceDetector(const SilenceDetectConfig& config = SilenceDetectConfig());

    void reset();

    // Process a block of mono float samples (assumed bandpassed 300Hz-3400Hz).
    // offsetSec: presentation timestamp of the first sample in this call.
    void process(const float* samples, size_t sampleCount, double offsetSec);

    // Call once at end of stream to flush any trailing remainder samples.
    void flush(double finalPtsSec = -1.0);

    const std::vector<double>& getOnsets() const { return mOnsets; }
    double getCoveredSec() const;

private:
    SilenceDetectConfig mConfig;
    double mLinearThreshold{0.01585};

    bool mAnchored{false};
    double mAnchorPtsSec{0.0};
    uint64_t mSamplesProcessed{0};

    bool mInSilence{false};
    double mSilenceStartSec{-1.0};

    // Adaptive noise floor tracking
    double mLocalFloor{0.02};

    std::vector<double> mOnsets;

    // Rolling frame buffer for incomplete 10ms frames
    std::vector<float> mRemainder;

    void processFrame(const float* frame, size_t len, double frameStartSec, double frameEndSec);
};

} // namespace anonsync

#endif // ANONSYNC_SILENCEDETECT_H
