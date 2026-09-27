package dev.anonrode.player.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression test for the VOICE CLARITY HIGH-PASS.
 *
 * The "Audio Effect" stage is documented as a first-order high-pass that
 * clears rumble, followed by a high-shelf that lifts dialogue. The high-pass
 * was not one. Its coefficients produced
 *
 *     y[n] = x[n] + alpha * x[n-1]        i.e. H(z) = 1 + alpha * z^-1
 *
 * whose zero sits at z = -alpha, essentially Nyquist. That is a LOW-pass.
 * Measured at 44.1 kHz with an 80 Hz corner:
 *
 *     20 Hz  +5.97 dB      1 kHz  +5.94 dB
 *     80 Hz  +5.97 dB     10 kHz  +3.55 dB
 *                        20 kHz -10.76 dB
 *
 * So it amplified the rumble it claimed to remove, by a flat ~6 dB across
 * the whole low and mid range, and pushed that straight into the hard clamp
 * at the end of the chain. The normalisation constant (`hpB0`) that would
 * have fixed the gain was computed and then never read.
 *
 * The fix is the normalised one-pole high-pass
 *
 *     y[n] = alpha * y[n-1] + ((1 + alpha) / 2) * (x[n] - x[n-1])
 *
 * which needs the output memory the old form had no term for at all.
 *
 * This test measures the real processor rather than the coefficients, so it
 * fails on the old build regardless of how the coefficients are written.
 */
@UnstableApi
class VoiceClarityProcessorTest {

    private companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNELS = 1
        const val AMPLITUDE = 10000.0

        /**
         * 50 Hz must sit at least this far below 5 kHz.
         *
         * The fixed high-pass alone gives 5.5 dB of separation, and the
         * high-shelf that follows only lifts the 5 kHz end further, so the
         * real margin is larger. 3 dB keeps a safe distance from both: the
         * old build measures +0.56 dB, i.e. 50 Hz was actually LOUDER than
         * 5 kHz, so this discriminates unambiguously.
         */
        const val REQUIRED_SEPARATION_DB = 3.0
    }

    private fun newProcessor(): VoiceClarityProcessor =
        VoiceClarityProcessor().apply {
            configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
            highPassHz = 80f
            enabled = true
        }

    /**
     * Pushes a sine of [hz] through the processor and returns the RMS of the
     * produced samples, skipping the first 2000 so the filter transient is
     * out of the measurement.
     */
    private fun measureRms(p: VoiceClarityProcessor, hz: Double): Double {
        val frames = 8192
        val input = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (AMPLITUDE * sin(2.0 * PI * hz * i / SAMPLE_RATE)).toInt()
                .coerceIn(-32768, 32767)
            input.putShort(v.toShort())
        }
        input.flip()
        p.queueInput(input)

        val out = p.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        val produced = ArrayList<Double>(out.remaining() / 2)
        while (out.remaining() >= 2) produced.add(out.short.toDouble())

        val skip = 2000.coerceAtMost(produced.size / 2)
        val tail = produced.subList(skip, produced.size)
        require(tail.isNotEmpty()) { "processor produced too few samples to measure" }
        var sum = 0.0
        for (v in tail) sum += v * v
        return sqrt(sum / tail.size)
    }

    @Test
    fun `rumble is attenuated relative to the dialogue band`() {
        val p = newProcessor()
        val rumble = measureRms(p, 50.0)
        val dialogue = measureRms(p, 5000.0)
        val sepDb = 20.0 * log10(dialogue / rumble)
        assertTrue(
            "50 Hz must be at least $REQUIRED_SEPARATION_DB dB below 5 kHz, " +
                "but measured $sepDb dB (rumble=$rumble dialogue=$dialogue). " +
                "A near-zero separation means the high-pass is not a high-pass.",
            sepDb >= REQUIRED_SEPARATION_DB,
        )
    }
}
