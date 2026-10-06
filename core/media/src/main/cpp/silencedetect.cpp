#include "silencedetect.h"
#include <cmath>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace anonsync {

static inline float computeRmsNeon(const float* data, size_t count) {
    if (count == 0) return 0.0f;
    float sumSq = 0.0f;
    size_t i = 0;

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    float32x4_t sumVec = vdupq_n_f32(0.0f);
    for (; i + 4 <= count; i += 4) {
        float32x4_t v = vld1q_f32(data + i);
        sumVec = vfmaq_f32(sumVec, v, v);
    }
    float temp[4];
    vst1q_f32(temp, sumVec);
    sumSq = temp[0] + temp[1] + temp[2] + temp[3];
#endif

    for (; i < count; ++i) {
        sumSq += data[i] * data[i];
    }
    return std::sqrt(sumSq / static_cast<float>(count));
}

SilenceDetector::SilenceDetector(const SilenceDetectConfig& config)
    : mConfig(config) {
    mLinearThreshold = std::pow(10.0, mConfig.noiseThresholdDb / 20.0);
    reset();
}

void SilenceDetector::reset() {
    mInSilence = false;
    mSilenceStartSec = -1.0;
    mCurrentTimeSec = 0.0;
    mLocalFloor = 0.015;
    mOnsets.clear();
    mEnergyEnvelope.clear();
    mRemainder.clear();
}

void SilenceDetector::processFrame(const float* frame, size_t len, double framePtsSec) {
    float rms = computeRmsNeon(frame, len);

    // Adaptive noise floor tracking (leaky update)
    if (rms < mLocalFloor) {
        mLocalFloor = mLocalFloor * 0.95 + rms * 0.05;
    } else {
        mLocalFloor = mLocalFloor * 0.9995 + rms * 0.0005;
    }

    // Dynamic threshold: base -25dB threshold or 2x local noise floor
    double activeThreshold = std::max(mLinearThreshold, mLocalFloor * 1.8);

    mEnergyEnvelope.push_back(rms);

    if (rms < activeThreshold) {
        // Current frame is silence
        if (!mInSilence) {
            mInSilence = true;
            mSilenceStartSec = framePtsSec;
        }
    } else {
        // Current frame is sound / speech
        if (mInSilence) {
            double silenceDuration = framePtsSec - mSilenceStartSec;
            if (silenceDuration >= mConfig.minSilenceDurationSec) {
                // Speech onset: silence just ended!
                mOnsets.push_back(framePtsSec);
            }
            mInSilence = false;
            mSilenceStartSec = -1.0;
        }
    }
}

void SilenceDetector::process(const float* samples, size_t sampleCount, double offsetSec) {
    if (sampleCount == 0) return;

    size_t frameLen = mConfig.frameLengthSamples;
    double frameDuration = static_cast<double>(frameLen) / mConfig.sampleRate;

    size_t processed = 0;

    // Handle any leftover samples from the previous batch
    if (!mRemainder.empty()) {
        size_t needed = frameLen - mRemainder.size();
        if (sampleCount >= needed) {
            mRemainder.insert(mRemainder.end(), samples, samples + needed);
            processFrame(mRemainder.data(), frameLen, offsetSec);
            mRemainder.clear();
            processed += needed;
        } else {
            mRemainder.insert(mRemainder.end(), samples, samples + sampleCount);
            return;
        }
    }

    // Process full 10ms frames directly from input buffer
    while (processed + frameLen <= sampleCount) {
        double currentPts = offsetSec + (static_cast<double>(processed) / mConfig.sampleRate);
        processFrame(samples + processed, frameLen, currentPts);
        processed += frameLen;
    }

    // Store remaining samples for next call
    if (processed < sampleCount) {
        mRemainder.assign(samples + processed, samples + sampleCount);
    }
}

void SilenceDetector::flush(double finalPtsSec) {
    if (!mRemainder.empty()) {
        processFrame(mRemainder.data(), mRemainder.size(), finalPtsSec);
        mRemainder.clear();
    }
}

} // namespace anonsync
