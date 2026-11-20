package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.media.log.AppLog
import java.nio.ByteBuffer

/**
 * JNI bridge to libanonsync.so: high-performance C++ NEON-vectorized
 * audio bandpass filtering, streaming silencedetect, and multi-scale
 * cross-correlation.
 */
object NativeSyncEngine {

    private val isNativeLoaded: Boolean by lazy {
        try {
            System.loadLibrary("anonsync")
            AppLog.d("ANONSYNC", "libanonsync.so loaded successfully")
            true
        } catch (t: Throwable) {
            AppLog.d("ANONSYNC", "libanonsync.so unavailable: ${t.message}")
            false
        }
    }

    fun isAvailable(): Boolean = isNativeLoaded

    fun isNeonSupported(): Boolean {
        if (!isAvailable()) return false
        return try {
            nativeIsNeonSupported()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Persistent native streaming session holding [VoiceBandpassFilter] and
     * [SilenceDetector] across MediaCodec PCM buffers. Call [feed] per decoded
     * buffer, [finish] once at EOF, and always [close] in a finally block.
     */
    class OnsetSession internal constructor(private var handle: Long) : AutoCloseable {
        fun feed(pcmBuffer: ByteBuffer, isFloat: Boolean, offsetSec: Double): Boolean {
            val h = handle
            if (h == 0L || !pcmBuffer.isDirect) return false
            return try {
                nativeFeedOnsetBuffer(h, pcmBuffer, isFloat, offsetSec)
            } catch (t: Throwable) {
                AppLog.e("ANONSYNC", "nativeFeedOnsetBuffer threw", t)
                false
            }
        }

        fun finish(): DoubleArray? {
            val h = handle
            if (h == 0L) return null
            return try {
                nativeFinishOnsetSession(h)
            } catch (t: Throwable) {
                AppLog.e("ANONSYNC", "nativeFinishOnsetSession threw", t)
                null
            }
        }

        override fun close() {
            val h = handle
            if (h != 0L) {
                handle = 0L
                try {
                    nativeDestroyOnsetSession(h)
                } catch (t: Throwable) {
                    AppLog.e("ANONSYNC", "nativeDestroyOnsetSession threw", t)
                }
            }
        }
    }

    fun createOnsetSession(sampleRate: Int, channels: Int): OnsetSession? {
        if (!isAvailable() || sampleRate <= 0 || channels <= 0) return null
        val h = try {
            nativeCreateOnsetSession(sampleRate, channels)
        } catch (t: Throwable) {
            AppLog.e("ANONSYNC", "nativeCreateOnsetSession threw", t)
            0L
        }
        return if (h != 0L) OnsetSession(h) else null
    }

    data class Result(
        val locked: Boolean,
        val offsetSec: Double,
        val speedFactor: Double,
        val recall: Double,
        val precision: Double,
        val margin: Double,
        val hits: Int,
        val localCues: Int,
        val hasCut: Boolean,
        val cutTimeSec: Double,
        val cutOffsetSec: Double,
    )

    fun alignPcm(
        pcmBuffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        isFloat: Boolean,
        cueStarts: DoubleArray,
        cueEnds: DoubleArray = DoubleArray(0),
        activeOffsetSec: Double = 0.0,
        searchMinOffsetSec: Double = -120.0,
        searchMaxOffsetSec: Double = 120.0,
        allowSlopeSearch: Boolean = false,
    ): Result? {
        if (!isAvailable() || !pcmBuffer.isDirect || cueStarts.isEmpty()) {
            return null
        }
        val raw = try {
            nativeAlignPcm(
                pcmBuffer,
                sampleRate,
                channels,
                isFloat,
                cueStarts,
                cueEnds,
                activeOffsetSec,
                searchMinOffsetSec,
                searchMaxOffsetSec,
                allowSlopeSearch,
            )
        } catch (t: Throwable) {
            AppLog.e("ANONSYNC", "nativeAlignPcm threw", t)
            null
        } ?: return null

        if (raw.size < 11) return null

        return Result(
            locked = raw[0] > 0.5,
            offsetSec = raw[1],
            speedFactor = raw[2],
            recall = raw[3],
            precision = raw[4],
            margin = raw[5],
            hits = raw[6].toInt(),
            localCues = raw[7].toInt(),
            hasCut = raw[8] > 0.5,
            cutTimeSec = raw[9],
            cutOffsetSec = raw[10],
        )
    }

    fun correlate(
        audioOnsets: DoubleArray,
        cueStarts: DoubleArray,
        cueEnds: DoubleArray = DoubleArray(0),
        activeOffsetSec: Double = 0.0,
        searchMinOffsetSec: Double = -120.0,
        searchMaxOffsetSec: Double = 120.0,
        allowSlopeSearch: Boolean = false,
    ): Result? {
        if (!isAvailable() || audioOnsets.isEmpty() || cueStarts.isEmpty()) {
            return null
        }
        val raw = try {
            nativeCorrelate(
                audioOnsets,
                cueStarts,
                cueEnds,
                activeOffsetSec,
                searchMinOffsetSec,
                searchMaxOffsetSec,
                allowSlopeSearch,
            )
        } catch (t: Throwable) {
            AppLog.e("ANONSYNC", "nativeCorrelate threw", t)
            null
        } ?: return null

        if (raw.size < 11) return null

        return Result(
            locked = raw[0] > 0.5,
            offsetSec = raw[1],
            speedFactor = raw[2],
            recall = raw[3],
            precision = raw[4],
            margin = raw[5],
            hits = raw[6].toInt(),
            localCues = raw[7].toInt(),
            hasCut = raw[8] > 0.5,
            cutTimeSec = raw[9],
            cutOffsetSec = raw[10],
        )
    }

    fun extractOnsets(
        pcmBuffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        isFloat: Boolean,
        offsetSec: Double = 0.0,
    ): DoubleArray? {
        if (!isAvailable() || !pcmBuffer.isDirect) return null
        return try {
            nativeExtractOnsets(pcmBuffer, sampleRate, channels, isFloat, offsetSec)
        } catch (t: Throwable) {
            AppLog.e("ANONSYNC", "nativeExtractOnsets threw", t)
            null
        }
    }

    fun extractOnsetsShorts(
        pcmShorts: ShortArray,
        sampleRate: Int,
        channels: Int,
        offsetSec: Double = 0.0,
    ): DoubleArray? {
        if (!isAvailable() || pcmShorts.isEmpty()) return null
        return try {
            nativeExtractOnsetsShorts(pcmShorts, sampleRate, channels, offsetSec)
        } catch (t: Throwable) {
            AppLog.e("ANONSYNC", "nativeExtractOnsetsShorts threw", t)
            null
        }
    }

    @JvmStatic
    private external fun nativeIsNeonSupported(): Boolean

    @JvmStatic
    private external fun nativeCreateOnsetSession(sampleRate: Int, channels: Int): Long

    @JvmStatic
    private external fun nativeFeedOnsetBuffer(
        handle: Long,
        byteBuffer: ByteBuffer,
        isFloat: Boolean,
        offsetSec: Double,
    ): Boolean

    @JvmStatic
    private external fun nativeFinishOnsetSession(handle: Long): DoubleArray?

    @JvmStatic
    private external fun nativeDestroyOnsetSession(handle: Long)

    @JvmStatic
    private external fun nativeAlignPcm(
        byteBuffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        isFloat: Boolean,
        cueStarts: DoubleArray,
        cueEnds: DoubleArray,
        activeOffsetSec: Double,
        searchMinOffsetSec: Double,
        searchMaxOffsetSec: Double,
        allowSlopeSearch: Boolean,
    ): DoubleArray?

    @JvmStatic
    private external fun nativeCorrelate(
        audioOnsets: DoubleArray,
        cueStarts: DoubleArray,
        cueEnds: DoubleArray,
        activeOffsetSec: Double,
        searchMinOffsetSec: Double,
        searchMaxOffsetSec: Double,
        allowSlopeSearch: Boolean,
    ): DoubleArray?

    @JvmStatic
    private external fun nativeExtractOnsets(
        byteBuffer: ByteBuffer,
        sampleRate: Int,
        channels: Int,
        isFloat: Boolean,
        offsetSec: Double,
    ): DoubleArray?

    @JvmStatic
    private external fun nativeExtractOnsetsShorts(
        pcmShorts: ShortArray,
        sampleRate: Int,
        channels: Int,
        offsetSec: Double,
    ): DoubleArray?
}
