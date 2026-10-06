#ifndef ANONSYNC_SILENCEDETECT_H
#define ANONSYNC_SILENCEDETECT_H

#include <vector>
#include <cstdint>
#include <cmath>

namespace anonsync {

struct SilenceDetectConfig {
    double noiseThresholdDb{-25.0}; // default -25dB
    double minSilenceDurationSec{0.30}; // default 300ms
    int frameLengthSamples{160}; // 10ms at 16kHz
    double sampleRate{16000.0};
};

class SilenceDetector {
public:
    explicit SilenceDetector(const SilenceDetectConfig& config = SilenceDetectConfig());

    void reset();

    // Process a block of mono float samples (assumed bandpassed 300Hz-3400Hz).
    // offsetSec: presentation timestamp of the first sample in this call.
    void process(const float* samples, size_t sampleCount, double offsetSec);

    // Call at end of stream to flush any trailing state.
    void flush(double finalPtsSec);

    const std::vector<double>& getOnsets() const { return mOnsets; }
    const std::vector<float>& getEnergyEnvelope() const { return mEnergyEnvelope; }

private:
    SilenceDetectConfig mConfig;
    double mLinearThreshold;

    bool mInSilence{false};
    double mSilenceStartSec{-1.0};
    double mCurrentTimeSec{0.0};

    // Adaptive noise floor tracking
    double mLocalFloor{0.015};

    std::vector<double> mOnsets;
    std::vector<float> mEnergyEnvelope; // 50ms energy bins

    // Rolling frame buffer for incomplete 10ms frames
    std::vector<float> mRemainder;

    void processFrame(const float* frame, size_t len, double framePtsSec);
};

} // namespace anonsync

#endif // ANONSYNC_SILENCEDETECT_H
