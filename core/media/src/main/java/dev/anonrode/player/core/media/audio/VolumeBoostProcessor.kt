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

    /**
     * Reusable output buffer, grown on demand. [queueInput] runs on the audio
     * render thread roughly every 20 ms, so allocating a fresh direct buffer
     * each call would churn the GC and risk underruns; Media3 fully drains the
     * previous output (via [getOutput]) before queueing the next input, so a
     * single reused buffer is safe.
     */
    private var reuseBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

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
        val out = if (reuseBuffer.capacity() < remaining) {
            ByteBuffer.allocateDirect(remaining).order(ByteOrder.nativeOrder())
                .also { reuseBuffer = it }
        } else {
            reuseBuffer
        }
        out.clear()
        val g = gain
        if (g == 1f) {
            // Unity gain: bulk copy. NOTE: the output must be a fresh
            // buffer with position=0 — handing back the input buffer after
            // consuming it (position==limit) delivers ZERO bytes downstream:
            // total silence, and the audio-driven clock stalls the video.
            out.put(inputBuffer) // advances inputBuffer.position to limit
        } else {
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
        }
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
