#ifndef ANONSYNC_BIQUAD_FILTER_H
#define ANONSYNC_BIQUAD_FILTER_H

#include <vector>
#include <cstdint>
#include <cmath>

namespace anonsync {

struct BiquadCoeffs {
    double b0{1.0}, b1{0.0}, b2{0.0};
    double a1{0.0}, a2{0.0};
};

struct BiquadState {
    double s1{0.0};
    double s2{0.0};

    inline void reset() {
        s1 = 0.0;
        s2 = 0.0;
    }

    inline double process(double x, const BiquadCoeffs& c) {
        double y = c.b0 * x + s1;
        s1 = c.b1 * x - c.a1 * y + s2;
        s2 = c.b2 * x - c.a2 * y;
        return y;
    }
};

class VoiceBandpassFilter {
public:
    VoiceBandpassFilter(double sampleRate = 16000.0, double hpCutoff = 300.0, double lpCutoff = 3400.0);

    void reset();
    void configure(double sampleRate, double hpCutoff = 300.0, double lpCutoff = 3400.0);

    // Process mono buffer in-place or into output buffer
    void process(const float* input, float* output, size_t count);
    void process(const int16_t* input, float* output, size_t count);

    // Downsamples multi-channel audio to 16kHz mono and bandpasses in a single pass
    size_t downsampleAndFilter(
        const int16_t* input, size_t frameCount,
        int inSampleRate, int inChannels,
        std::vector<float>& outMono
    );

    size_t downsampleAndFilterFloat(
        const float* input, size_t frameCount,
        int inSampleRate, int inChannels,
        std::vector<float>& outMono
    );

private:
    double mSampleRate{16000.0};
    BiquadCoeffs mHpCoeffs;
    BiquadCoeffs mLpCoeffs;
    BiquadState mHpState;
    BiquadState mLpState;

    static BiquadCoeffs computeHighpass(double sampleRate, double cutoffHz, double q = 0.7071067811865475);
    static BiquadCoeffs computeLowpass(double sampleRate, double cutoffHz, double q = 0.7071067811865475);
};

} // namespace anonsync

#endif // ANONSYNC_BIQUAD_FILTER_H
