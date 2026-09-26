package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.model.SubtitleCue
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCorrelatorTest {

    private companion object {
        const val ALIGN_BIN = SpeechCorrelator.ALIGN_BIN // 0.1s

        /**
         * Creates a synthetic audio envelope (FloatArray at 0.1s bins) with
         * distinct speech bursts.
         */
        fun createSyntheticAudio(
            totalSec: Double,
            burstTimesSec: List<Double>,
            burstDurationSec: Double = 2.0,
        ): FloatArray {
            val totalBins = (totalSec / ALIGN_BIN).toInt()
            val audio = FloatArray(totalBins)
            for (t in burstTimesSec) {
                val startBin = (t / ALIGN_BIN).toInt()
                val endBin = ((t + burstDurationSec) / ALIGN_BIN).toInt()
                for (b in startBin until minOf(totalBins, endBin)) {
                    audio[b] = 0.85f
                }
            }
            return audio
        }

        /**
         * Creates subtitle cues from burst start times, modified by framerate slope
         * alpha and temporal offset beta (audio_time = alpha * sub_time + beta => sub_time = (audio_time - beta) / alpha).
         */
        fun createCues(
            audioBurstTimesSec: List<Double>,
            alpha: Double = 1.0,
            betaSec: Double = 0.0,
            cueDurationSec: Double = 2.0,
        ): List<SubtitleCue> {
            return audioBurstTimesSec.mapIndexed { idx, aStart ->
                val subStart = (aStart - betaSec) / alpha
                val subEnd = subStart + cueDurationSec
                SubtitleCue(start = subStart, end = subEnd, lines = listOf("Dialogue $idx"))
            }
        }
    }

    @Test
    fun `findJointSync accurately recovers positive shift on nominal framerate`() {
        val bursts = listOf(
            15.0, 30.0, 48.0, 65.0, 85.0, 110.0, 135.0, 160.0, 190.0, 220.0,
            250.0, 280.0, 315.0, 350.0, 390.0, 430.0, 470.0, 510.0, 560.0, 610.0,
        )
        val audio = createSyntheticAudio(totalSec = 700.0, burstTimesSec = bursts)
        // Subtitles late by 3.5s -> audio_time = 1.0 * sub_time - 3.5s => beta = -3.5s
        val cues = createCues(bursts, alpha = 1.0, betaSec = -3.5)

        val result = SpeechCorrelator.findJointSync(audio, cues)
        assertNotNull("findJointSync should lock on clean synthetic alignment", result)
        assertEquals(1.0, result!!.alpha, 0.0001)
        assertTrue(
            "recovered beta ${result.beta}s should be within 0.08s of expected -3.5s",
            abs(result.beta - (-3.5)) <= 0.08,
        )
        assertTrue("recall should be high on true alignment (got ${result.recall})", result.recall >= 0.70)
        assertTrue("margin should be confident (got ${result.margin})", result.margin >= 0.10)
    }

    @Test
    fun `findJointSync accurately recovers negative shift on nominal framerate`() {
        val bursts = listOf(
            20.0, 38.0, 55.0, 75.0, 100.0, 125.0, 155.0, 185.0, 215.0, 245.0,
            280.0, 315.0, 355.0, 395.0, 435.0, 480.0, 525.0, 570.0, 620.0, 670.0,
        )
        val audio = createSyntheticAudio(totalSec = 750.0, burstTimesSec = bursts)
        // Subtitles early by 2.8s -> audio_time = 1.0 * sub_time + 2.8s => beta = +2.8s
        val cues = createCues(bursts, alpha = 1.0, betaSec = 2.8)

        val result = SpeechCorrelator.findJointSync(audio, cues)
        assertNotNull("findJointSync should lock on negative shift", result)
        assertEquals(1.0, result!!.alpha, 0.0001)
        assertTrue(
            "recovered beta ${result.beta}s should be within 0.08s of expected +2.8s",
            abs(result.beta - 2.8) <= 0.08,
        )
        assertTrue("recall should be high (got ${result.recall})", result.recall >= 0.70)
    }

    @Test
    fun `findJointSync recovers PAL framerate drift`() {
        val bursts = listOf(
            25.0, 50.0, 80.0, 115.0, 150.0, 190.0, 230.0, 275.0, 320.0, 370.0,
            420.0, 475.0, 530.0, 590.0, 650.0, 715.0, 780.0, 850.0, 920.0, 1000.0,
        )
        val audio = createSyntheticAudio(totalSec = 1100.0, burstTimesSec = bursts)
        // PAL speedup alpha = 1.0425, beta = +1.0s
        val cues = createCues(bursts, alpha = 1.0425, betaSec = 1.0)

        val result = SpeechCorrelator.findJointSync(audio, cues)
        assertNotNull("findJointSync should detect PAL slope", result)
        assertTrue(
            "recovered alpha ${result!!.alpha} should be within 0.0015 of 1.0425",
            abs(result.alpha - 1.0425) <= 0.0015,
        )
        assertTrue(
            "recovered beta ${result.beta} should be within 0.25s of 1.0s",
            abs(result.beta - 1.0) <= 0.25,
        )
    }

    @Test
    fun `findOffset returns NotReady below eligible bins`() {
        val bursts = listOf(5.0, 10.0)
        val audio = createSyntheticAudio(totalSec = 15.0, burstTimesSec = bursts)
        val cues = createCues(bursts)
        val outcome = SpeechCorrelator.findOffset(audio, binCount = 140, cues = cues)
        assertTrue("should be NotReady below ELIGIBLE_BINS (160)", outcome is SpeechCorrelator.Outcome.NotReady)
    }

    @Test
    fun `findOffset locks on matching live audio window`() {
        val bursts = listOf(5.0, 8.0, 12.0, 15.0, 18.0, 21.0, 24.0, 27.0)
        val audio = createSyntheticAudio(totalSec = 35.0, burstTimesSec = bursts)
        // Cues aligned with audio (+1.5s offset)
        val cues = createCues(bursts, betaSec = -1.5)
        val outcome = SpeechCorrelator.findOffset(audio, binCount = 300, cues = cues)
        assertTrue("should match on clear window", outcome is SpeechCorrelator.Outcome.Match)
        val match = (outcome as SpeechCorrelator.Outcome.Match).result
        assertTrue("offset should be close to -1.5s (got ${match.offsetSeconds})", abs(match.offsetSeconds - (-1.5)) <= 0.15)
    }
}
