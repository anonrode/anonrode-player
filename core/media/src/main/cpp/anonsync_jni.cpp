#include <jni.h>
#include <vector>
#include <cstdint>
#include <algorithm>
#include <android/log.h>
#include "biquad_filter.h"
#include "silencedetect.h"
#include "correlator.h"

#define LOG_TAG "AnonSyncJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct StreamingOnsetSession {
    anonsync::VoiceBandpassFilter filter;
    anonsync::SilenceDetector detector;
    int sampleRate{0};
    int channels{0};
    double effectiveSampleRate{16000.0};
    std::vector<float> monoScratch;

    StreamingOnsetSession(int sr, int ch)
        : filter(anonsync::VoiceBandpassFilter::computeEffectiveSampleRate(sr), 300.0, 3400.0),
          detector(makeSilenceConfig(anonsync::VoiceBandpassFilter::computeEffectiveSampleRate(sr))),
          sampleRate(sr),
          channels(ch),
          effectiveSampleRate(anonsync::VoiceBandpassFilter::computeEffectiveSampleRate(sr)) {
        monoScratch.reserve(4096);
    }

    static anonsync::SilenceDetectConfig makeSilenceConfig(double effSr) {
        anonsync::SilenceDetectConfig cfg;
        cfg.sampleRate = effSr;
        cfg.noiseThresholdDb = -36.0;
        cfg.minSilenceDurationSec = 0.18;
        return cfg;
    }
};

static inline void getBufferBounds(JNIEnv* env, jobject byteBuffer, jlong bufCap, jint& outPos, jint& outRem) {
    outPos = 0;
    outRem = static_cast<jint>(bufCap);
    if (!byteBuffer) return;

    jclass bufClass = env->GetObjectClass(byteBuffer);
    if (bufClass) {
        jmethodID posMethod = env->GetMethodID(bufClass, "position", "()I");
        jmethodID remMethod = env->GetMethodID(bufClass, "remaining", "()I");
        if (posMethod && remMethod) {
            jint p = env->CallIntMethod(byteBuffer, posMethod);
            jint r = env->CallIntMethod(byteBuffer, remMethod);
            if (!env->ExceptionCheck() && p >= 0 && r > 0 && (p + r) <= bufCap) {
                outPos = p;
                outRem = r;
            } else if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
        env->DeleteLocalRef(bufClass);
    }
}

static inline anonsync::AlignerConfig buildAlignerConfig(
    double searchMinOffsetSec,
    double searchMaxOffsetSec,
    bool allowSlopeSearch
) {
    anonsync::AlignerConfig cfg;
    if (searchMaxOffsetSec >= searchMinOffsetSec) {
        cfg.minSearchOffsetSec = searchMinOffsetSec;
        cfg.maxSearchOffsetSec = searchMaxOffsetSec;
    }
    cfg.allowSlopeSearch = allowSlopeSearch;
    return cfg;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeIsNeonSupported(
    JNIEnv* /* env */,
    jclass /* clazz */
) {
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jlong JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeCreateOnsetSession(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jint sampleRate,
    jint channels
) {
    if (sampleRate <= 0 || channels <= 0) return 0;
    auto* session = new StreamingOnsetSession(sampleRate, channels);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jboolean JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeFeedOnsetBuffer(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle,
    jobject byteBuffer,
    jboolean isFloat,
    jdouble offsetSec
) {
    if (handle == 0 || !byteBuffer) return JNI_FALSE;
    auto* session = reinterpret_cast<StreamingOnsetSession*>(handle);

    void* rawBufPtr = env->GetDirectBufferAddress(byteBuffer);
    jlong bufCap = env->GetDirectBufferCapacity(byteBuffer);
    if (!rawBufPtr || bufCap <= 0) return JNI_FALSE;

    jint pos = 0;
    jint rem = 0;
    getBufferBounds(env, byteBuffer, bufCap, pos, rem);
    if (rem <= 0) return JNI_TRUE;

    uint8_t* bufPtr = static_cast<uint8_t*>(rawBufPtr) + pos;
    uintptr_t addr = reinterpret_cast<uintptr_t>(bufPtr);
    if ((isFloat && (addr % sizeof(float) != 0)) || (!isFloat && (addr % sizeof(int16_t) != 0))) {
        return JNI_FALSE;
    }

    size_t bytesPerSample = isFloat ? 4 : 2;
    size_t frameCount = static_cast<size_t>(rem) / (bytesPerSample * static_cast<size_t>(session->channels));
    if (frameCount == 0) return JNI_TRUE;

    session->monoScratch.clear();
    if (isFloat) {
        const float* floatPtr = reinterpret_cast<const float*>(bufPtr);
        session->filter.downsampleAndFilterFloat(
            floatPtr, frameCount, session->sampleRate, session->channels, session->monoScratch
        );
    } else {
        const int16_t* shortPtr = reinterpret_cast<const int16_t*>(bufPtr);
        session->filter.downsampleAndFilter(
            shortPtr, frameCount, session->sampleRate, session->channels, session->monoScratch
        );
    }

    if (!session->monoScratch.empty()) {
        session->detector.process(session->monoScratch.data(), session->monoScratch.size(), offsetSec);
    }
    return JNI_TRUE;
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeFinishOnsetSession(
    JNIEnv* env,
    jclass /* clazz */,
    jlong handle
) {
    if (handle == 0) return nullptr;
    auto* session = reinterpret_cast<StreamingOnsetSession*>(handle);
    session->detector.flush();
    const auto& onsets = session->detector.getOnsets();
    jsize n = static_cast<jsize>(onsets.size());
    jdoubleArray result = env->NewDoubleArray(n);
    if (result && n > 0) {
        env->SetDoubleArrayRegion(result, 0, n, onsets.data());
    }
    return result;
}

JNIEXPORT void JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeDestroyOnsetSession(
    JNIEnv* /* env */,
    jclass /* clazz */,
    jlong handle
) {
    if (handle == 0) return;
    auto* session = reinterpret_cast<StreamingOnsetSession*>(handle);
    delete session;
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeCorrelate(
    JNIEnv* env,
    jclass /* clazz */,
    jdoubleArray jAudioOnsets,
    jdoubleArray jCueStarts,
    jdoubleArray jCueEnds,
    jdouble activeOffsetSec,
    jdouble searchMinOffsetSec,
    jdouble searchMaxOffsetSec,
    jboolean allowSlopeSearch
) {
    if (!jAudioOnsets || !jCueStarts) {
        return nullptr;
    }

    jsize onsetCount = env->GetArrayLength(jAudioOnsets);
    jsize cueCount = env->GetArrayLength(jCueStarts);
    if (onsetCount == 0 || cueCount == 0) {
        return nullptr;
    }

    jdouble* onsetPtr = env->GetDoubleArrayElements(jAudioOnsets, nullptr);
    if (!onsetPtr) return nullptr;
    std::vector<double> audioOnsets(onsetPtr, onsetPtr + onsetCount);
    env->ReleaseDoubleArrayElements(jAudioOnsets, onsetPtr, JNI_ABORT);

    jdouble* cuePtr = env->GetDoubleArrayElements(jCueStarts, nullptr);
    if (!cuePtr) return nullptr;
    std::vector<double> cueStarts(cuePtr, cuePtr + cueCount);
    env->ReleaseDoubleArrayElements(jCueStarts, cuePtr, JNI_ABORT);

    std::vector<double> cueEnds;
    if (jCueEnds) {
        jsize endCount = env->GetArrayLength(jCueEnds);
        if (endCount > 0) {
            jdouble* endPtr = env->GetDoubleArrayElements(jCueEnds, nullptr);
            if (endPtr) {
                cueEnds.assign(endPtr, endPtr + endCount);
                env->ReleaseDoubleArrayElements(jCueEnds, endPtr, JNI_ABORT);
            }
        }
    }

    anonsync::AlignerConfig cfg = buildAlignerConfig(searchMinOffsetSec, searchMaxOffsetSec, allowSlopeSearch == JNI_TRUE);
    anonsync::MultiScaleAligner aligner(cfg);
    anonsync::SyncResult sync = aligner.align(audioOnsets, cueStarts, cueEnds, activeOffsetSec);

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
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeAlignPcm(
    JNIEnv* env,
    jclass /* clazz */,
    jobject byteBuffer,
    jint sampleRate,
    jint channels,
    jboolean isFloat,
    jdoubleArray jCueStarts,
    jdoubleArray jCueEnds,
    jdouble activeOffsetSec,
    jdouble searchMinOffsetSec,
    jdouble searchMaxOffsetSec,
    jboolean allowSlopeSearch
) {
    if (!byteBuffer || !jCueStarts || channels <= 0 || sampleRate <= 0) {
        return nullptr;
    }

    void* rawBufPtr = env->GetDirectBufferAddress(byteBuffer);
    jlong bufCap = env->GetDirectBufferCapacity(byteBuffer);
    if (!rawBufPtr || bufCap <= 0) {
        return nullptr;
    }

    jint pos = 0;
    jint rem = 0;
    getBufferBounds(env, byteBuffer, bufCap, pos, rem);
    if (rem <= 0) return nullptr;

    uint8_t* bufPtr = static_cast<uint8_t*>(rawBufPtr) + pos;
    uintptr_t addr = reinterpret_cast<uintptr_t>(bufPtr);
    if ((isFloat && (addr % sizeof(float) != 0)) || (!isFloat && (addr % sizeof(int16_t) != 0))) {
        return nullptr;
    }

    jsize cueCount = env->GetArrayLength(jCueStarts);
    if (cueCount == 0) return nullptr;

    jdouble* cuePtr = env->GetDoubleArrayElements(jCueStarts, nullptr);
    if (!cuePtr) return nullptr;
    std::vector<double> cueStarts(cuePtr, cuePtr + cueCount);
    env->ReleaseDoubleArrayElements(jCueStarts, cuePtr, JNI_ABORT);

    std::vector<double> cueEnds;
    if (jCueEnds) {
        jsize endCount = env->GetArrayLength(jCueEnds);
        if (endCount > 0) {
            jdouble* endPtr = env->GetDoubleArrayElements(jCueEnds, nullptr);
            if (endPtr) {
                cueEnds.assign(endPtr, endPtr + endCount);
                env->ReleaseDoubleArrayElements(jCueEnds, endPtr, JNI_ABORT);
            }
        }
    }

    const double effRate = anonsync::VoiceBandpassFilter::computeEffectiveSampleRate(sampleRate);
    anonsync::VoiceBandpassFilter filter(effRate, 300.0, 3400.0);
    std::vector<float> monoAudio;

    size_t bytesPerSample = isFloat ? 4 : 2;
    size_t frameCount = static_cast<size_t>(rem) / (bytesPerSample * static_cast<size_t>(channels));
    if (frameCount == 0) return nullptr;

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

    anonsync::SilenceDetector detector(StreamingOnsetSession::makeSilenceConfig(effRate));
    detector.process(monoAudio.data(), monoAudio.size(), 0.0);
    detector.flush();

    const auto& onsets = detector.getOnsets();
    if (onsets.empty()) {
        return nullptr;
    }

    anonsync::AlignerConfig cfg = buildAlignerConfig(searchMinOffsetSec, searchMaxOffsetSec, allowSlopeSearch == JNI_TRUE);
    anonsync::MultiScaleAligner aligner(cfg);
    anonsync::SyncResult sync = aligner.align(onsets, cueStarts, cueEnds, activeOffsetSec);

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

    void* rawBufPtr = env->GetDirectBufferAddress(byteBuffer);
    jlong bufCap = env->GetDirectBufferCapacity(byteBuffer);
    if (!rawBufPtr || bufCap <= 0) return nullptr;

    jint pos = 0;
    jint rem = 0;
    getBufferBounds(env, byteBuffer, bufCap, pos, rem);
    if (rem <= 0) return nullptr;

    uint8_t* bufPtr = static_cast<uint8_t*>(rawBufPtr) + pos;
    uintptr_t addr = reinterpret_cast<uintptr_t>(bufPtr);
    if ((isFloat && (addr % sizeof(float) != 0)) || (!isFloat && (addr % sizeof(int16_t) != 0))) {
        return nullptr;
    }

    const double effRate = anonsync::VoiceBandpassFilter::computeEffectiveSampleRate(sampleRate);
    anonsync::VoiceBandpassFilter filter(effRate, 300.0, 3400.0);
    std::vector<float> monoAudio;

    size_t bytesPerSample = isFloat ? 4 : 2;
    size_t frameCount = static_cast<size_t>(rem) / (bytesPerSample * static_cast<size_t>(channels));
    if (frameCount == 0) return nullptr;

    if (isFloat) {
        const float* floatPtr = reinterpret_cast<const float*>(bufPtr);
        filter.downsampleAndFilterFloat(floatPtr, frameCount, sampleRate, channels, monoAudio);
    } else {
        const int16_t* shortPtr = reinterpret_cast<const int16_t*>(bufPtr);
        filter.downsampleAndFilter(shortPtr, frameCount, sampleRate, channels, monoAudio);
    }

    if (monoAudio.empty()) return nullptr;

    anonsync::SilenceDetector detector(StreamingOnsetSession::makeSilenceConfig(effRate));
    detector.process(monoAudio.data(), monoAudio.size(), offsetSec);
    detector.flush();

    const auto& onsets = detector.getOnsets();
    jsize n = static_cast<jsize>(onsets.size());
    jdoubleArray result = env->NewDoubleArray(n);
    if (result && n > 0) {
        env->SetDoubleArrayRegion(result, 0, n, onsets.data());
    }
    return result;
}

JNIEXPORT jdoubleArray JNICALL
Java_dev_anonrode_player_core_media_sync_NativeSyncEngine_nativeExtractOnsetsShorts(
    JNIEnv* env,
    jclass /* clazz */,
    jshortArray jPcmShorts,
    jint sampleRate,
    jint channels,
    jdouble offsetSec
) {
    if (!jPcmShorts || channels <= 0 || sampleRate <= 0) return nullptr;

    jsize shortCount = env->GetArrayLength(jPcmShorts);
    if (shortCount <= 0) return nullptr;

    jshort* shortsPtr = env->GetShortArrayElements(jPcmShorts, nullptr);
    if (!shortsPtr) return nullptr;

    const double effRate = anonsync::VoiceBandpassFilter::computeEffectiveSampleRate(sampleRate);
    anonsync::VoiceBandpassFilter filter(effRate, 300.0, 3400.0);
    std::vector<float> monoAudio;

    size_t frameCount = static_cast<size_t>(shortCount) / static_cast<size_t>(channels);
    filter.downsampleAndFilter(reinterpret_cast<const int16_t*>(shortsPtr), frameCount, sampleRate, channels, monoAudio);

    env->ReleaseShortArrayElements(jPcmShorts, shortsPtr, JNI_ABORT);

    if (monoAudio.empty()) return nullptr;

    anonsync::SilenceDetector detector(StreamingOnsetSession::makeSilenceConfig(effRate));
    detector.process(monoAudio.data(), monoAudio.size(), offsetSec);
    detector.flush();

    const auto& onsets = detector.getOnsets();
    jsize n = static_cast<jsize>(onsets.size());
    jdoubleArray result = env->NewDoubleArray(n);
    if (result && n > 0) {
        env->SetDoubleArrayRegion(result, 0, n, onsets.data());
    }
    return result;
}

} // extern "C"
