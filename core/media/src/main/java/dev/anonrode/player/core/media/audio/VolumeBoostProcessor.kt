package dev.anonrode.player.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VLC-style volume boost: multiplies 16-bit PCM samples by a runtime gain
 * (1.0 = off, 2.0 = +6 dB ≈ "200% volume") with hard clipping. Sits in the
 * DefaultAudioSink processor chain AFTER [dev.anonrode.player.core.media.sync.AudioSyncProcessor]
 * so the sync engine analyzes the untouched signal. Gain is a volatile
 * field — adjustable from the main thread while the audio thread renders.
 * Non-16-bit input (float passthrough devices) bypasses the processor.
 */
@UnstableApi
class VolumeBoostProcessor : AudioProcessor {

    /** Linear gain. 1.0 = unity; clamped by the engine to [1, 3]. */
    @Volatile var gain: Float = 1f

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        inputFormat =
            if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
            else AudioFormat.NOT_SET
        return inputFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val g = gain
        if (g == 1f) {
            // Unity gain: hand the buffer straight through, no copy.
            inputBuffer.position(inputBuffer.limit())
            outputBuffer = inputBuffer
            return
        }
        val out = ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
        val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (src.hasRemaining()) {
            val v = src.short * g
            out.putShort(
                when {
                    v > Short.MAX_VALUE -> Short.MAX_VALUE
                    v < Short.MIN_VALUE -> Short.MIN_VALUE
                    else -> v.toInt().toShort()
                }
            )
        }
        inputBuffer.position(inputBuffer.limit())
        out.flip()
        outputBuffer = out
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val b = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return b
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        // gain is a user setting — deliberately survives reset().
    }
}
