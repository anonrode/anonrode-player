#include <jni.h>
#include <vector>
#include <cstdint>
#include <android/log.h>
#include "biquad_filter.h"
#include "silencedetect.h"
#include "correlator.h"

#define LOG_TAG "AnonSyncJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jdoubleArray JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeAlignPcm(
    JNIEnv* env,
    jclass /* clazz */,
    jobject byteBuffer,
    jint sampleRate,
    jint channels,
    jboolean isFloat,
    jdoubleArray jCueStarts,
    jdoubleArray jCueEnds,
    jdouble activeOffsetSec
) {
    if (!byteBuffer || !jCueStarts || channels <= 0 || sampleRate <= 0) {
        return nullptr;
    }

    void* bufPtr = env->GetDirectBufferAddress(byteBuffer);
    jlong bufCap = env->GetDirectBufferCapacity(byteBuffer);
    if (!bufPtr || bufCap <= 0) {
        return nullptr;
    }

    // 1. Convert cues from JNI array
    jsize cueCount = env->GetArrayLength(jCueStarts);
    if (cueCount == 0) return nullptr;

    std::vector<double> cueStarts(cueCount);
    env->GetDoubleArrayRegion(jCueStarts, 0, cueCount, cueStarts.data());

    std::vector<double> cueEnds;
    if (jCueEnds) {
        jsize endCount = env->GetArrayLength(jCueEnds);
        if (endCount > 0) {
            cueEnds.resize(endCount);
            env->GetDoubleArrayRegion(jCueEnds, 0, endCount, cueEnds.data());
        }
    }

    // 2. Downsample and bandpass filter audio
    anonsync::VoiceBandpassFilter filter(16000.0, 300.0, 3400.0);
    std::vector<float> monoAudio;

    size_t bytesPerSample = isFloat ? 4 : 2;
    size_t frameCount = bufCap / (bytesPerSample * channels);

    if (isFloat) {
        const float* floatPtr = reinterpret_cast<const float*>(bufPtr);
        filter.downsampleAndFilterFloat(floatPtr, frameCount, sampleRate, channels, monoAudio);
    } else {
        const int16_t* shortPtr = reinterpret_cast<const int16_t*>(bufPtr);
        filter.downsampleAndFilter(shortPtr, frameCount, sampleRate, channels, monoAudio);
    }

    if (monoAudio.empty()) {
        return nullptr;
    }

    // 3. Detect speech onsets using FFmpeg adaptive silence detector
    anonsync::SilenceDetector detector;
    detector.process(monoAudio.data(), monoAudio.size(), 0.0);
    detector.flush(static_cast<double>(monoAudio.size()) / 16000.0);

    const auto& onsets = detector.getOnsets();
    if (onsets.empty()) {
        return nullptr;
    }

    // 4. Align onsets against cues
    anonsync::MultiScaleAligner aligner;
    anonsync::SyncResult sync = aligner.align(onsets, cueStarts, cueEnds, activeOffsetSec);

    // Pack results into jdoubleArray
    // [0]: locked (1.0 or 0.0)
    // [1]: offsetSec
    // [2]: speedFactor
    // [3]: recall
    // [4]: precision
    // [5]: margin
    // [6]: hits
    // [7]: localCues
    // [8]: hasCut (1.0 or 0.0)
    // [9]: cutTimeSec
    // [10]: cutOffsetSec
    jdouble outData[11] = {
        sync.locked ? 1.0 : 0.0,
        sync.offsetSec,
        sync.speedFactor,
        sync.recall,
        sync.precision,
        sync.margin,
        static_cast<double>(sync.hits),
        static_cast<double>(sync.localCues),
        sync.hasCut ? 1.0 : 0.0,
        sync.cutTimeSec,
        sync.cutOffsetSec
    };

    jdoubleArray jResult = env->NewDoubleArray(11);
    if (jResult) {
        env->SetDoubleArrayRegion(jResult, 0, 11, outData);
    }
    return jResult;
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeExtractOnsets(
    JNIEnv* env,
    jclass /* clazz */,
    jobject byteBuffer,
    jint sampleRate,
    jint channels,
    jboolean isFloat,
    jdouble offsetSec
) {
    if (!byteBuffer || channels <= 0 || sampleRate <= 0) return nullptr;

    void* bufPtr = env->GetDirectBufferAddress(byteBuffer);
    jlong bufCap = env->GetDirectBufferCapacity(byteBuffer);
    if (!bufPtr || bufCap <= 0) return nullptr;

    anonsync::VoiceBandpassFilter filter(16000.0, 300.0, 3400.0);
    std::vector<float> monoAudio;

    size_t bytesPerSample = isFloat ? 4 : 2;
    size_t frameCount = bufCap / (bytesPerSample * channels);

    if (isFloat) {
        const float* floatPtr = reinterpret_cast<const float*>(bufPtr);
        filter.downsampleAndFilterFloat(floatPtr, frameCount, sampleRate, channels, monoAudio);
    } else {
        const int16_t* shortPtr = reinterpret_cast<const int16_t*>(bufPtr);
        filter.downsampleAndFilter(shortPtr, frameCount, sampleRate, channels, monoAudio);
    }

    anonsync::SilenceDetector detector;
    detector.process(monoAudio.data(), monoAudio.size(), offsetSec);
    detector.flush(offsetSec + static_cast<double>(monoAudio.size()) / 16000.0);

    const auto& onsets = detector.getOnsets();
    jsize n = static_cast<jsize>(onsets.size());
    jdoubleArray result = env->NewDoubleArray(n);
    if (result && n > 0) {
        env->SetDoubleArrayRegion(result, 0, n, onsets.data());
    }
    return result;
}

} // extern "C"
