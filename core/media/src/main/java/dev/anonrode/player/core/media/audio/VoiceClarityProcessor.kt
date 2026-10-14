package dev.anonrode.player.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Dialogue-clarity stage ("Audio Effect" in the player ribbon). v2 chain:
 *
 *   1. one-pole high-pass @ 80 Hz  — clears rumble without thinning voice
 *   2. peaking cut @ ~300 Hz       — the real "mumble" culprit: excess
 *      low-mid energy masks consonants, so a gentle dip there frees the voice
 *   3. peaking lift @ ~2.6 kHz     — presence/consonant lift; a BELL, not a
 *      shelf, so the 6-10 kHz sibilance region is left untouched (a shelf
 *      up there clarifies nothing and hisses)
 *   4. soft limiter                — the added gain bends over smoothly
 *      instead of hard-clipping; a squared-off waveform is itself mumbling
 *      (intermodulation grinds consonants into mush)
 *
 * Chain position (see PlaybackEngine.buildSharedAudioSink):
 *   syncAnalyzer -> THIS -> volumeBoost
 *  • AFTER the sync analyzer, so the VAD keeps calibrating against the
 *    untouched signal — a spectral tilt on its input would move the
 *    energy floor it measures.
 *  • BEFORE the boost stage, so the limiter stays the last thing to touch
 *    the samples and the two compose predictably.
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

    /** Presence-bell gain (dB) at [presenceHz]. Positive lifts consonants. */
    @Volatile var gainDb: Float = 3.5f

    /** Presence-bell center (Hz) — the consonant/definition band. */
    @Volatile var presenceHz: Float = 2600f

    /** High-pass corner (Hz). ~80 Hz clears rumble without thinning voice. */
    @Volatile var highPassHz: Float = 80f

    /** Center (Hz) of the low-mid mud cut. */
    @Volatile var mudHz: Float = 300f

    /** Mud-cut (dB) of the low-mid peaking stage. Negative = dip. */
    @Volatile var mudGainDb: Float = -2.5f

    private var inputFormat: AudioFormat = AudioFormat.NOT_SET
    private var sampleRate = 0
    private var inputEnded = false
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    // Per-channel delay/state lines (mono..7.1), resized in configure().
    // Two transposed direct-form II biquads (mud cut, presence bell) plus
    // the high-pass memories.
    private var x1 = FloatArray(2)
    private var x2 = FloatArray(2)
    private var hpY1 = FloatArray(2)

    private var mudY1 = FloatArray(2)
    private var mudY2 = FloatArray(2)
    private var bellY1 = FloatArray(2)
    private var bellY2 = FloatArray(2)
    private var frameBuffer = FloatArray(2)

    private var hpAlpha = 0f
    private var hpK = 0f

    // Mud-cut peaking biquad coefficients
    private var mudB0 = 1f; private var mudB1 = 0f; private var mudB2 = 0f
    private var mudA1 = 0f; private var mudA2 = 0f

    // Presence bell biquad coefficients
    private var bellB0 = 1f; private var bellB1 = 0f; private var bellB2 = 0f
    private var bellA1 = 0f; private var bellA2 = 0f

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
            grow(inputAudioFormat.channelCount.coerceAtLeast(1))
            recompute()
            // A decoder swap or sample-rate change invalidates the filter
            // memory; starting from zero state avoids a click.
            clearState()
        }
        return inputFormat
    }

    private fun grow(ch: Int) {
        if (x1.size < ch) {
            x1 = FloatArray(ch); x2 = FloatArray(ch); hpY1 = FloatArray(ch)
            mudY1 = FloatArray(ch); mudY2 = FloatArray(ch)
            bellY1 = FloatArray(ch); bellY2 = FloatArray(ch)
        }
        if (frameBuffer.size < ch) frameBuffer = FloatArray(ch)
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET && enabled

    /** RBJ peaking-EQ coefficients, normalised and handed back as
     *  (b0, b1, b2, a1, a2) for a transposed direct form II biquad. */
    private fun computePeaking(hz: Double, gainDbV: Double, q: Double): FloatArray {
        val sr = sampleRate.toDouble()
        val f = hz.coerceIn(20.0, sr * 0.45)
        val g = gainDbV.coerceIn(-12.0, 12.0)
        val a = Math.pow(10.0, g / 40.0)
        val w0 = 2.0 * PI * f / sr
        val cosw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / a
        val b0 = (1.0 + alpha * a) / a0
        val b1 = (-2.0 * cosw) / a0
        val b2 = (1.0 - alpha * a) / a0
        val a1 = (-2.0 * cosw) / a0
        val a2 = (1.0 - alpha / a) / a0
        return floatArrayOf(
            b0.toFloat(), b1.toFloat(), b2.toFloat(), a1.toFloat(), a2.toFloat(),
        )
    }

    private fun recompute() {
        if (sampleRate <= 0) return
        val sr = sampleRate.toDouble()

        // ── stage 2: low-mud peaking cut ──
        val mud = computePeaking(mudHz.toDouble(), mudGainDb.toDouble(), 1.0)
        mudB0 = mud[0]; mudB1 = mud[1]; mudB2 = mud[2]
        mudA1 = mud[3]; mudA2 = mud[4]

        // ── stage 3: presence bell ──
        val bell = computePeaking(
            presenceHz.toDouble().coerceIn(200.0, sr * 0.45),
            gainDb.toDouble().coerceIn(0.0, 12.0),
            0.9,
        )
        bellB0 = bell[0]; bellB1 = bell[1]; bellB2 = bell[2]
        bellA1 = bell[3]; bellA2 = bell[4]

        // ── stage 1: rumble rolloff, normalised one-pole high-pass ──
        //   y[n] = alpha * y[n-1] + ((1+alpha)/2) * (x[n] - x[n-1])
        // H(z) = ((1+alpha)/2)(1 - z^-1)/(1 - alpha z^-1): unity in the
        // passband, -6 dB/oct below the corner. (The build this replaced
        // produced H(z) = 1 + alpha z^-1 — a LOW-pass that amplified the
        // rumble it claimed to clear; see the regression test's header.)
        val hp = highPassHz.toDouble().coerceIn(20.0, sr * 0.45)
        val rc = 1.0 / (2.0 * PI * hp)
        val dt = 1.0 / sr
        val alphaHp = rc / (rc + dt)
        hpAlpha = alphaHp.toFloat()
        hpK = ((1.0 + alphaHp) / 2.0).toFloat()
    }

    private fun clearState() {
        java.util.Arrays.fill(x1, 0f)
        java.util.Arrays.fill(x2, 0f)
        java.util.Arrays.fill(hpY1, 0f)
        java.util.Arrays.fill(mudY1, 0f)
        java.util.Arrays.fill(mudY2, 0f)
        java.util.Arrays.fill(bellY1, 0f)
        java.util.Arrays.fill(bellY2, 0f)
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
            val frame = if (frameBuffer.size >= channels) frameBuffer else FloatArray(channels)
            // Work in whole frames only; a partial trailing frame is copied
            // through verbatim below (never dropped — dropping bytes would
            // desync the audio clock and stall the video).
            val wholeFrames = src.remaining() / (channels * 2)
            for (f in 0 until wholeFrames) {
                for (c in 0 until channels) frame[c] = src.short.toFloat()
                for (c in 0 until channels) {
                    // 1) rumble high-pass (normalised one-pole)
                    val v = hpK * (frame[c] - x1[c]) + hpAlpha * hpY1[c]
                    x1[c] = frame[c]
                    hpY1[c] = v
                    // 2) low-mud peaking cut (transposed direct form II)
                    val t = mudB0 * v + mudY2[c]
                    mudY2[c] = mudB1 * v - mudA1 * t + mudY1[c]
                    mudY1[c] = mudB2 * v - mudA2 * t
                    // 3) presence bell (transposed direct form II)
                    val outV = bellB0 * t + bellY2[c]
                    bellY2[c] = bellB1 * t - bellA1 * outV + bellY1[c]
                    bellY1[c] = bellB2 * t - bellA2 * outV
                    // 4) soft limiter — see [Companion.softLimit].
                    frame[c] = softLimit(outV)
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

    companion object {
        /** Amplitude where the soft limiter's quadratic ease begins. */
        private const val SOFT_KNEE = 24_320f

        /** Absolute ceiling (never exceeded, slope-zero at the top). */
        private const val HARD_MAX = 32_767f

        /**
         * C1 soft limiter: identity below [SOFT_KNEE]; above it a quadratic
         * ease with slope 1 at the knee and slope 0 at [HARD_MAX], so the
         * curve is continuous, its derivative is continuous, and no sample
         * ever crosses full scale.
         */
        fun softLimit(x: Float): Float {
            val ax = abs(x)
            if (ax <= SOFT_KNEE) return x
            if (ax >= HARD_MAX) return if (x > 0f) HARD_MAX else -HARD_MAX
            val span = HARD_MAX - SOFT_KNEE
            val u = (ax - SOFT_KNEE) / span
            val eased = span * u * (2f - u)
            val yv = SOFT_KNEE + eased
            return if (x > 0f) yv else -yv
        }
    }
}
