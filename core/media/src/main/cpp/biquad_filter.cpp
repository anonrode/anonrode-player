#include "biquad_filter.h"
#include <cmath>
#include <algorithm>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace anonsync {

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

VoiceBandpassFilter::VoiceBandpassFilter(double sampleRate, double hpCutoff, double lpCutoff) {
    configure(sampleRate, hpCutoff, lpCutoff);
}

void VoiceBandpassFilter::reset() {
    mHpState.reset();
    mLpState.reset();
}

BiquadCoeffs VoiceBandpassFilter::computeHighpass(double sampleRate, double cutoffHz, double q) {
    double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
    double cosw0 = std::cos(w0);
    double sinw0 = std::sin(w0);
    double alpha = sinw0 / (2.0 * q);

    double a0 = 1.0 + alpha;
    BiquadCoeffs c;
    c.b0 = ((1.0 + cosw0) / 2.0) / a0;
    c.b1 = (-(1.0 + cosw0)) / a0;
    c.b2 = ((1.0 + cosw0) / 2.0) / a0;
    c.a1 = (-2.0 * cosw0) / a0;
    c.a2 = (1.0 - alpha) / a0;
    return c;
}

BiquadCoeffs VoiceBandpassFilter::computeLowpass(double sampleRate, double cutoffHz, double q) {
    double w0 = 2.0 * M_PI * cutoffHz / sampleRate;
    double cosw0 = std::cos(w0);
    double sinw0 = std::sin(w0);
    double alpha = sinw0 / (2.0 * q);

    double a0 = 1.0 + alpha;
    BiquadCoeffs c;
    c.b0 = ((1.0 - cosw0) / 2.0) / a0;
    c.b1 = (1.0 - cosw0) / a0;
    c.b2 = ((1.0 - cosw0) / 2.0) / a0;
    c.a1 = (-2.0 * cosw0) / a0;
    c.a2 = (1.0 - alpha) / a0;
    return c;
}

void VoiceBandpassFilter::configure(double sampleRate, double hpCutoff, double lpCutoff) {
    mSampleRate = sampleRate;
    mHpCoeffs = computeHighpass(sampleRate, hpCutoff);
    mLpCoeffs = computeLowpass(sampleRate, lpCutoff);
    reset();
}

void VoiceBandpassFilter::process(const float* input, float* output, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        double hp = mHpState.process(static_cast<double>(input[i]), mHpCoeffs);
        double bp = mLpState.process(hp, mLpCoeffs);
        output[i] = static_cast<float>(bp);
    }
}

void VoiceBandpassFilter::process(const int16_t* input, float* output, size_t count) {
    constexpr double scale = 1.0 / 32768.0;
    for (size_t i = 0; i < count; ++i) {
        double in = static_cast<double>(input[i]) * scale;
        double hp = mHpState.process(in, mHpCoeffs);
        double bp = mLpState.process(hp, mLpCoeffs);
        output[i] = static_cast<float>(bp);
    }
}

size_t VoiceBandpassFilter::downsampleAndFilter(
    const int16_t* input, size_t frameCount,
    int inSampleRate, int inChannels,
    std::vector<float>& outMono
) {
    if (frameCount == 0 || inChannels <= 0) return 0;
    constexpr double scale = 1.0 / 32768.0;

    // Determine decimation factor to target ~16000 Hz
    int step = 1;
    if (inSampleRate >= 44100) {
        step = 3; // 48000 -> 16000, 44100 -> 14700
    } else if (inSampleRate >= 32000) {
        step = 2; // 32000 -> 16000
    }

    size_t outFrames = frameCount / step;
    size_t startOffset = outMono.size();
    outMono.resize(startOffset + outFrames);
    float* outPtr = outMono.data() + startOffset;

    size_t outIdx = 0;
    for (size_t f = 0; f < frameCount; f += step) {
        const int16_t* framePtr = input + (f * inChannels);
        double monoSum = 0.0;
        for (int c = 0; c < inChannels; ++c) {
            monoSum += framePtr[c];
        }
        double sample = (monoSum / inChannels) * scale;
        double hp = mHpState.process(sample, mHpCoeffs);
        double bp = mLpState.process(hp, mLpCoeffs);
        outPtr[outIdx++] = static_cast<float>(bp);
    }
    return outIdx;
}

size_t VoiceBandpassFilter::downsampleAndFilterFloat(
    const float* input, size_t frameCount,
    int inSampleRate, int inChannels,
    std::vector<float>& outMono
) {
    if (frameCount == 0 || inChannels <= 0) return 0;

    int step = 1;
    if (inSampleRate >= 44100) {
        step = 3;
    } else if (inSampleRate >= 32000) {
        step = 2;
    }

    size_t outFrames = frameCount / step;
    size_t startOffset = outMono.size();
    outMono.resize(startOffset + outFrames);
    float* outPtr = outMono.data() + startOffset;

    size_t outIdx = 0;
    for (size_t f = 0; f < frameCount; f += step) {
        const float* framePtr = input + (f * inChannels);
        double monoSum = 0.0;
        for (int c = 0; c < inChannels; ++c) {
            monoSum += framePtr[c];
        }
        double sample = monoSum / inChannels;
        double hp = mHpState.process(sample, mHpCoeffs);
        double bp = mLpState.process(hp, mLpCoeffs);
        outPtr[outIdx++] = static_cast<float>(bp);
    }
    return outIdx;
}

} // namespace anonsync
