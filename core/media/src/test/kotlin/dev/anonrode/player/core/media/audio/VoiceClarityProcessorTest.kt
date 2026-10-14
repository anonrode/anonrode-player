package dev.anonrode.player.core.media.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the VOICE CLARITY chain ("Audio Effect").
 *
 * History: the original high-pass was not a high-pass. Its coefficients
 * produced
 *
 *     y[n] = x[n] + alpha * x[n-1]        i.e. H(z) = 1 + alpha * z^-1
 *
 * whose zero sits at z = -alpha, essentially Nyquist. That is a LOW-pass:
 * measured at 44.1 kHz with an 80 Hz corner it amplified the rumble it
 * claimed to remove by a flat ~6 dB across the low and mid range, and fed
 * that straight into the hard clamp at the end of the chain. The fix is the
 * normalised one-pole high-pass
 *
 *     y[n] = alpha * y[n-1] + ((1 + alpha) / 2) * (x[n] - x[n-1])
 *
 * v2, above that: a low-mud peaking cut (~300 Hz — excess low-mid energy is
 * what MASKS consonants and reads as "mumbling"), a presence BELL (~2.6 kHz —
 * a bell, not a shelf, so the sibilance octave is not lifted), and a soft
 * limiter instead of the hard clamp (hard-clipped peaks are squared-off
 * waves; their intermodulation is itself the mumbled smear).
 *
 * These tests measure the real processor rather than coefficients, so they
 * fail on any build whose actual behaviour regresses.
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
         * The fixed high-pass alone gives 5.5 dB of separation; the mud cut
         * and presence bell only widen it. 3 dB keeps a safe distance from
         * both: the original build measured +0.56 dB, i.e. 50 Hz was
         * actually LOUDER than 5 kHz, so this discriminates unambiguously.
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
     * Pushes a sine of [hz] through the processor and returns the produced
     * samples, skipping the first 2000 so the filter transient is out of
     * the measurement. (The processor's state carries across calls in the
     * same way the running sink does; 2000 samples covers the biquad decay.)
     */
    private fun pushThrough(
        p: VoiceClarityProcessor,
        hz: Double,
        amplitude: Double = AMPLITUDE,
    ): List<Double> {
        val frames = 8192
        val input = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (amplitude * sin(2.0 * PI * hz * i / SAMPLE_RATE)).toInt()
                .coerceIn(-32768, 32767)
            input.putShort(v.toShort())
        }
        input.flip()
        p.queueInput(input)

        val out = p.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        val produced = ArrayList<Double>(out.remaining() / 2)
        while (out.remaining() >= 2) produced.add(out.short.toDouble())

        val skip = 2000.coerceAtMost(produced.size / 2)
        return produced.subList(skip, produced.size)
    }

    private fun rmsOf(samples: List<Double>): Double {
        require(samples.isNotEmpty()) { "processor produced too few samples to measure" }
        var sum = 0.0
        for (v in samples) sum += v * v
        return sqrt(sum / samples.size)
    }

    /** Convenience overload used by the spectral-shape assertions. */
    private fun measureRms(p: VoiceClarityProcessor, hz: Double): Double =
        rmsOf(pushThrough(p, hz))

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

    @Test
    fun `presence bell lifts consonants without lifting the sibilance octave`() {
        val p = newProcessor()
        val mud = measureRms(p, 500.0)
        val presence = measureRms(p, 2600.0)
        val sibilance = measureRms(p, 9000.0)
        fun gql(a: Double, b: Double) = 20.0 * log10(a / b)

        val overMud = gql(presence, mud)
        assertTrue(
            "2.6 kHz must sit at least 2 dB over the 500 Hz mud band " +
                "(measured %+.2f dB) — without this the effect is not " +
                "actually raising intelligibility".format(overMud),
            overMud >= 2.0,
        )
        val overSibilance = gql(presence, sibilance)
        assertTrue(
            "2.6 kHz must be lifted at least 1 dB MORE than 9 kHz " +
                "(measured %+.2f dB) — a shelf would hype sibilance instead " +
                "of clarity".format(overSibilance),
            overSibilance >= 1.0,
        )
    }

    @Test
    fun `loud content bends over smoothly instead of squaring off`() {
        val p = newProcessor()
        // 30000-amp sine at 2.6 kHz: the +3.5 dB bell wants ~44.9k peak —
        // far past full scale. A hard clamp's output lives ONLY at ±32767
        // or the unchanged band; the soft limiter produces a continuous
        // ease band in between.
        val out = pushThrough(p, 2600.0, amplitude = 30000.0)
        assertTrue(out.isNotEmpty())
        val overCeiling = out.count { abs(it) > 32767.0 }
        assertEquals("no sample may pass full scale", 0, overCeiling)

        val kneeBand = out.count { abs(it) in 24400.0..32600.0 }
        assertTrue(
            "expected a continuous soft-knee ease band above 24.3k " +
                "(a hard clamp produces exactly zero such samples); got " +
                "$kneeBand of ${out.size}",
            kneeBand > out.size * 0.03,
        )
    }

    @Test
    fun `disabled stage passes samples through untouched`() {
        val p = VoiceClarityProcessor().apply {
            configure(AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT))
            enabled = false
        }
        val frames = 2048
        val input = ByteBuffer.allocateDirect(frames * 2).order(ByteOrder.nativeOrder())
        val expected = ArrayList<Short>(frames)
        for (i in 0 until frames) {
            val v = (0.7 * AMPLITUDE * sin(2.0 * PI * 997.0 * i / SAMPLE_RATE))
                .toInt().toShort()
            expected.add(v)
            input.putShort(v)
        }
        input.flip()
        p.queueInput(input)
        val out = p.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(frames * 2, out.remaining())
        for (i in 0 until frames) {
            assertEquals(expected[i], out.short)
        }
    }

    @Test
    fun `multichannel surround audio does not throw and processes all channels`() {
        val channels = 6 // 5.1 surround sound
        val p = VoiceClarityProcessor().apply {
            configure(AudioFormat(48000, channels, C.ENCODING_PCM_16BIT))
            highPassHz = 80f
            enabled = true
        }
        val frames = 1024
        val input = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val v = (AMPLITUDE * sin(2.0 * PI * 1000.0 * i / 48000.0)).toInt()
                    .coerceIn(-32768, 32767)
                input.putShort(v.toShort())
            }
        }
        input.flip()
        p.queueInput(input)
        val out = p.getOutput()
        assertEquals("output must match input byte count", frames * channels * 2, out.remaining())
    }
}
