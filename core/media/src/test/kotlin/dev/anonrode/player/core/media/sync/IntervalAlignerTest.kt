package dev.anonrode.player.core.media.sync

import dev.anonrode.player.core.model.SubtitleCue
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntervalAlignerTest {

    private companion object {
        fun generateSyntheticData(
            bursts: List<Double>,
            alpha: Double = 1.0,
            beta: Double = 0.0,
        ): Pair<List<Double>, List<SubtitleCue>> {
            val cues = bursts.mapIndexed { idx, b ->
                val subStart = (b - beta) / alpha
                SubtitleCue(start = subStart, end = subStart + 2.0, lines = listOf("Line $idx"))
            }
            return bursts to cues
        }
    }

    @Test
    fun `align locks nominal framerate on positive shift`() {
        val bursts = listOf(
            10.0, 25.0, 45.0, 68.0, 92.0, 118.0, 145.0, 175.0, 210.0, 248.0,
            290.0, 335.0, 385.0, 440.0, 500.0, 565.0, 635.0, 710.0, 790.0, 875.0,
            965.0, 1060.0, 1160.0, 1265.0, 1375.0,
        )
        val expectedBeta = 3.65
        val (onsets, cues) = generateSyntheticData(bursts, alpha = 1.0, beta = expectedBeta)

        val result = IntervalAligner.align(onsets, cues.map { it.start })
        assertNotNull("Expected lock, got null", result)
        assertEquals(1.0, result!!.alpha, 0.001)
        assertTrue("Beta error too large: ${result.beta} vs $expectedBeta", abs(result.beta - expectedBeta) <= 0.10)
        assertTrue("Margin should be high: ${result.margin}", result.margin >= 0.15)
        assertTrue("Recall should be near 1.0: ${result.recall}", result.recall >= 0.90)
    }

    @Test
    fun `align locks nominal framerate on negative shift`() {
        val bursts = listOf(
            20.0, 38.0, 59.0, 82.0, 108.0, 137.0, 169.0, 204.0, 242.0, 283.0,
            327.0, 374.0, 424.0, 477.0, 533.0, 592.0, 654.0, 719.0, 787.0, 858.0,
            932.0, 1009.0, 1089.0, 1172.0, 1258.0,
        )
        val expectedBeta = -12.40
        val (onsets, cues) = generateSyntheticData(bursts, alpha = 1.0, beta = expectedBeta)

        val result = IntervalAligner.align(onsets, cues.map { it.start })
        assertNotNull("Expected lock, got null", result)
        assertEquals(1.0, result!!.alpha, 0.001)
        assertTrue("Beta error too large: ${result.beta} vs $expectedBeta", abs(result.beta - expectedBeta) <= 0.10)
        assertTrue("Recall should be near 1.0: ${result.recall}", result.recall >= 0.90)
    }

    @Test
    fun `align recovers standard PAL-to-FILM framerate conversion ratio`() {
        val bursts = listOf(
            30.0, 55.0, 82.0, 112.0, 145.0, 181.0, 220.0, 262.0, 307.0, 355.0,
            406.0, 460.0, 517.0, 577.0, 640.0, 706.0, 775.0, 847.0, 922.0, 1000.0,
            1081.0, 1165.0, 1252.0, 1342.0, 1435.0,
        )
        val expectedAlpha = 25.0 / 23.976 // ~1.042709
        val expectedBeta = 1.50
        val (onsets, cues) = generateSyntheticData(bursts, alpha = expectedAlpha, beta = expectedBeta)

        val result = IntervalAligner.align(onsets, cues.map { it.start })
        assertNotNull("Expected lock, got null", result)
        assertEquals(expectedAlpha, result!!.alpha, 0.001)
        assertTrue("Beta error too large: ${result.beta} vs $expectedBeta", abs(result.beta - expectedBeta) <= 0.15)
    }

    @Test
    fun `align locks large positive offset up to 120s window`() {
        val bursts = listOf(
            75.0, 95.0, 120.0, 148.0, 180.0, 215.0, 255.0, 300.0, 350.0, 405.0,
            465.0, 530.0, 600.0, 675.0, 755.0, 840.0, 930.0, 1025.0, 1125.0, 1230.0,
            1340.0, 1455.0, 1575.0, 1700.0, 1830.0,
        )
        val expectedBeta = 66.50
        val (onsets, cues) = generateSyntheticData(bursts, alpha = 1.0, beta = expectedBeta)

        val result = IntervalAligner.align(onsets, cues.map { it.start })
        assertNotNull("Expected lock, got null", result)
        assertEquals(1.0, result!!.alpha, 0.001)
        assertTrue("Beta error too large: ${result.beta} vs $expectedBeta", abs(result.beta - expectedBeta) <= 0.10)
        assertTrue("Recall should be near 1.0: ${result.recall}", result.recall >= 0.90)
    }

    @Test
    fun `alignPiecewise detects commercial cut jump and generates piecewise storage string`() {
        // Segment 1: from 10s to 500s with betaBefore = -12.0s
        val seg1Audio = mutableListOf<Double>()
        var t = 10.0
        var i = 1
        while (t < 500.0) {
            t += 7.0 + (i * 3.7 % 9.0)
            seg1Audio.add(Math.round(t * 100.0) / 100.0)
            i++
        }
        val seg1Cues = seg1Audio.map { Math.round((it - (-12.0)) * 100.0) / 100.0 }

        // Segment 2: from 550s to 1100s with betaAfter = 66.5s
        val seg2Audio = mutableListOf<Double>()
        t = 550.0
        while (t < 1100.0) {
            t += 7.0 + (i * 4.3 % 9.0)
            seg2Audio.add(Math.round(t * 100.0) / 100.0)
            i++
        }
        val seg2Cues = seg2Audio.map { Math.round((it - 66.5) * 100.0) / 100.0 }

        val onsets = seg1Audio + seg2Audio
        val cueStarts = seg1Cues + seg2Cues

        val result = IntervalAligner.alignPiecewise(onsets, cueStarts)
        assertNotNull("Expected piecewise result, got null", result)
        assertTrue("Expected hasSplit=true", result!!.hasSplit)
        assertTrue("BetaBefore error: ${result.betaBefore}", abs(result.betaBefore - (-12.0)) <= 0.30)
        assertTrue("BetaAfter error: ${result.betaAfter}", abs(result.betaAfter - 66.5) <= 0.30)
        assertTrue("Piecewise string should contain betaBefore", result.piecewise.startsWith("0.0:"))
        assertTrue("Recall should be high for both segments: ${result.recall}", result.recall >= 0.80)
    }

    @Test
    fun `align refuses random noise cues without false locking`() {
        val onsets = (1..50).map { it * 15.0 }
        // Completely unrelated random cue positions
        val noiseCues = (1..30).map { 1000.0 + it * 37.3 % 500.0 }

        val result = IntervalAligner.align(onsets, noiseCues)
        assertNull("Should refuse random noise", result)
    }
}
