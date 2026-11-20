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
        sumVec = vmlaq_f32(sumVec, v, v);
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
    if (mConfig.sampleRate <= 0.0) mConfig.sampleRate = 16000.0;
    mConfig.frameLengthSamples = std::max(1, static_cast<int>(std::llround(mConfig.sampleRate * 0.01)));
    mLinearThreshold = std::pow(10.0, mConfig.noiseThresholdDb / 20.0);
    reset();
}

void SilenceDetector::reset() {
    mAnchored = false;
    mAnchorPtsSec = 0.0;
    mSamplesProcessed = 0;
    mInSilence = false;
    mSilenceStartSec = -1.0;
    mLocalFloor = 0.02;
    mOnsets.clear();
    mRemainder.clear();
}

double SilenceDetector::getCoveredSec() const {
    if (!mAnchored) return 0.0;
    return mAnchorPtsSec + static_cast<double>(mSamplesProcessed + mRemainder.size()) / mConfig.sampleRate;
}

void SilenceDetector::processFrame(const float* frame, size_t len, double frameStartSec, double frameEndSec) {
    if (len == 0) return;
    double rms = static_cast<double>(computeRmsNeon(frame, len));

    // Adaptive background noise floor matching Kotlin SilenceState:
    // drops rapidly on quiet passages, leaks up slowly during sustained loud sections.
    if (rms < mLocalFloor) {
        mLocalFloor = std::max(mLinearThreshold, rms * 0.9 + mLocalFloor * 0.1);
    } else {
        mLocalFloor = std::min(0.15, mLocalFloor * 1.0005);
    }

    // Dynamic speech threshold: +4 dB above local noise floor (x1.6) or static floor.
    double activeThreshold = std::max(mLinearThreshold, mLocalFloor * 1.6);

    if (rms <= activeThreshold) {
        if (!mInSilence) {
            mInSilence = true;
            mSilenceStartSec = frameStartSec;
        }
    } else if (mInSilence) {
        double silenceDuration = frameEndSec - mSilenceStartSec;
        if (silenceDuration >= mConfig.minSilenceDurationSec) {
            mOnsets.push_back(frameEndSec);
        }
        mInSilence = false;
        mSilenceStartSec = -1.0;
    }
}

void SilenceDetector::process(const float* samples, size_t sampleCount, double offsetSec) {
    if (sampleCount == 0) return;

    const size_t frameLen = static_cast<size_t>(mConfig.frameLengthSamples);
    const double sr = mConfig.sampleRate;

    if (!mAnchored) {
        mAnchored = true;
        mAnchorPtsSec = (offsetSec >= 0.0) ? offsetSec : 0.0;
        mSamplesProcessed = 0;
    } else if (offsetSec >= 0.0) {
        double expectedPts = mAnchorPtsSec + static_cast<double>(mSamplesProcessed + mRemainder.size()) / sr;
        if (std::abs(offsetSec - expectedPts) > 0.50) {
            // Genuine container timestamp discontinuity: re-anchor cleanly.
            mAnchorPtsSec = offsetSec - static_cast<double>(mRemainder.size()) / sr;
            mSamplesProcessed = 0;
            mInSilence = false;
            mSilenceStartSec = -1.0;
        }
    }

    size_t processed = 0;

    // Complete any leftover samples from the previous streaming buffer.
    if (!mRemainder.empty()) {
        size_t needed = frameLen - mRemainder.size();
        if (sampleCount >= needed) {
            mRemainder.insert(mRemainder.end(), samples, samples + needed);
            double fStart = mAnchorPtsSec + static_cast<double>(mSamplesProcessed) / sr;
            double fEnd = mAnchorPtsSec + static_cast<double>(mSamplesProcessed + frameLen) / sr;
            mSamplesProcessed += frameLen;
            processFrame(mRemainder.data(), frameLen, fStart, fEnd);
            mRemainder.clear();
            processed += needed;
        } else {
            mRemainder.insert(mRemainder.end(), samples, samples + sampleCount);
            return;
        }
    }

    // Process full 10ms frames directly from input buffer.
    while (processed + frameLen <= sampleCount) {
        double fStart = mAnchorPtsSec + static_cast<double>(mSamplesProcessed) / sr;
        double fEnd = mAnchorPtsSec + static_cast<double>(mSamplesProcessed + frameLen) / sr;
        mSamplesProcessed += frameLen;
        processFrame(samples + processed, frameLen, fStart, fEnd);
        processed += frameLen;
    }

    // Carry trailing partial frame to the next streaming buffer.
    if (processed < sampleCount) {
        mRemainder.assign(samples + processed, samples + sampleCount);
    }
}

void SilenceDetector::flush(double finalPtsSec) {
    if (!mRemainder.empty()) {
        const double sr = mConfig.sampleRate;
        double fStart = mAnchored
            ? (mAnchorPtsSec + static_cast<double>(mSamplesProcessed) / sr)
            : (finalPtsSec >= 0.0 ? finalPtsSec : 0.0);
        double fEnd = fStart + static_cast<double>(mRemainder.size()) / sr;
        mSamplesProcessed += mRemainder.size();
        processFrame(mRemainder.data(), mRemainder.size(), fStart, fEnd);
        mRemainder.clear();
    }
}

} // namespace anonsync
