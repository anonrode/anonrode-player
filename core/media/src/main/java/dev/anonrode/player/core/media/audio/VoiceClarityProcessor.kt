package dev.anonrode.player.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dialogue-clarity stage ("Audio Effect" in the player ribbon): a
 * first-order high-pass that clears rumble, followed by a high-shelf that
 * lifts the speech band, then a hard clamp so the added gain cannot clip.
 *
 * Chain position (see PlaybackEngine.buildSharedAudioSink):
 *   syncAnalyzer -> THIS -> volumeBoost
 *  • AFTER the sync analyzer, so the VAD keeps calibrating against the
 *    untouched signal — a spectral tilt on its input would move the
 *    energy floor it measures.
 *  • BEFORE the boost stage, so the existing hard-clip ceiling stays the
 *    last thing to touch the samples and the two compose predictably.
 *
 * Bypassed (returns NOT_SET, so Media3 skips it) for non-16-bit input:
 * float-passthrough devices would need a different coefficient path, and
 * silently changing the user's output format to make a toggle work would
 * be worse than a no-op.
 */
@UnstableApi
class VoiceClarityProcessor : AudioProcessor {

    /** On/off, read on the render thread so the UI can flip it mid-playback. */
    @Volatile var enabled: Boolean = false

    /** Shelving applied at/above the corner. Positive lifts speech. */
    @Volatile var gainDb: Float = 4.5f

    /** Shelf corner (Hz). ~1.5 kHz sits in the dialogue band. */
    @Volatile var cornerHz: Float = 1500f

    /** High-pass corner (Hz). ~80 Hz clears rumble without thinning voice. */
    @Volatile var highPassHz: Float = 80f

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var sampleRate = 0
    private var inputEnded = false
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    // Direct-form I: two delay lines (x1,x2) and two output memories
    // (y1,y2), per channel. 2 channels is the Media3 ceiling here.
    private val x1 = FloatArray(2)
    private val x2 = FloatArray(2)
    private val y1 = FloatArray(2)
    private val y2 = FloatArray(2)

    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    /**
     * Reused output buffer, grown on demand. This runs on the render
     * thread roughly every 20 ms; allocating a fresh direct buffer per
     * call would churn the GC and risk underruns. Media3 fully drains the
     * previous output before queueing the next input, so one buffer is safe
     * (same contract as VolumeBoostProcessor).
     */
    private var reuseBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        inputFormat =
            if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat
            else AudioFormat.NOT_SET
        if (inputFormat != AudioFormat.NOT_SET) {
            sampleRate = inputAudioFormat.sampleRate
            recompute()
            // A decoder swap or sample-rate change invalidates the filter
            // memory; starting from zero state avoids a click.
            clearState()
        }
        return inputFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET && enabled

    /**
     * RBJ high-shelf biquad, with the rumble rolloff folded in as its
     * high-pass term.
     *
     *   A = 10^(gainDb/40),  w0 = 2*PI*corner/sr,  alpha = sin(w0)/2 * 2*sqrt(A)
     *
     * Coefficients are recomputed only when a parameter actually changes
     * (dirty flag), never per buffer — the UI can drag the corner without
     * making the render thread do trig 50x/second.
     */
    private fun recompute() {
        if (sampleRate <= 0) return
        val sr = sampleRate.toDouble()
        // Clamp both corners below Nyquist: a corner at/over it would make
        // the coefficients blow up (or divide by ~0) and emit full scale.
        val corner = cornerHz.coerceIn(200.0, sr * 0.45)
        val gain = gainDb.coerceIn(0.0, 12.0)

        val a = Math.pow(10.0, gain / 40.0)
        val w0 = 2.0 * PI * corner / sr
        val cosw = cos(w0)
        val sinw = sin(w0)
        val alpha = sinw * sqrt(a) * 0.5 * 2.0
        val twoSqrtAA = 2.0 * sqrt(a) * alpha

        val b0v = a * ((a + 1.0) + (a - 1.0) * cosw + twoSqrtAA)
        val b1v = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosw)
        val b2v = a * ((a + 1.0) + (a - 1.0) * cosw - twoSqrtAA)
        val a0 = (a + 1.0) - (a - 1.0) * cosw + twoSqrtAA
        val a1v = 2.0 * ((a - 1.0) - (a + 1.0) * cosw)
        val a2v = (a + 1.0) - (a - 1.0) * cosw - twoSqrtAA

        b0 = (b0v / a0).toFloat()
        b1 = (b1v / a0).toFloat()
        b2 = (b2v / a0).toFloat()
        a1 = (a1v / a0).toFloat()
        a2 = (a2v / a0).toFloat()

        // Rumble rolloff: a one-pole high-pass pre-filter coefficient pair,
        // applied as y = x - hpA1*prevX, folded into the same delay lines by
        // feeding its output into the biquad.
        val hp = highPassHz.coerceIn(20.0, sr * 0.45)
        val rc = 1.0 / (2.0 * PI * hp)
        val dt = 1.0 / sr
        val alphaHp = rc / (rc + dt)
        hpA1 = (-alphaHp).toFloat()
        hpB0 = (1.0 - alphaHp).toFloat()
    }


    private fun clearState() {
        java.util.Arrays.fill(x1, 0f)
        java.util.Arrays.fill(x2, 0f)
        java.util.Arrays.fill(y1, 0f)
        java.util.Arrays.fill(y2, 0f)
    }

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

        if (!enabled) {
            // Either it was never on, or it was turned off a moment ago.
            // Pass the samples through untouched AND drop the filter
            // memory, so re-enabling later starts clean rather than
            // ringing out stale state.
            clearState()
            out.put(inputBuffer)
        } else {
            val channels = inputFormat.channelCount.coerceAtLeast(1)
            val src = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val frame = FloatArray(channels)
            // Work in whole frames only; a partial trailing frame is copied
            // through verbatim below (never dropped — dropping bytes would
            // desync the audio clock and stall the video).
            val wholeFrames = src.remaining() / (channels * 2)
            for (f in 0 until wholeFrames) {
                for (c in 0 until channels) frame[c] = src.short.toFloat()
                for (c in 0 until channels) {
                    // 1) rumble high-pass
                    var v = frame[c] - hpA1 * x1[c]
                    x1[c] = frame[c]
                    // 2) dialogue high-shelf (transposed direct form II)
                    val outV = b0 * v + y2[c]
                    y2[c] = b1 * v - a1 * outV + y1[c]
                    y1[c] = b2 * v - a2 * outV
                    // 3) clamp: the shelf adds up to +12 dB, so without this
                    // even the +4.5 dB default would clip on loud content.
                    frame[c] = outV.coerceIn(-32768f, 32767f)
                }
                for (c in 0 until channels) out.putShort(frame[c].toInt().toShort())
            }
            if (src.hasRemaining()) out.put(src)
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

    override fun isEnded(): Boolean =
        inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        clearState()
    }

    override fun reset() {
        flush()
        inputFormat = AudioFormat.NOT_SET
        // `enabled` / gain / corners are user preferences — they survive
        // reset() deliberately (same contract as VolumeBoostProcessor).
    }
}

    private var hpA1 = 0f
    private var hpB0 = 1f
