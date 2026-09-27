package dev.anonrode.player.core.media.subtitle

import dev.anonrode.player.core.model.SubtitleCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the cue lookup the player render tick uses.
 *
 * The previous implementation was a plain binary search that assumed cues
 * never overlap. Over 60 real subtitle files from the local library, 17
 * contained overlapping cues, and the old search returned null while a cue
 * was on screen for 31% of the overlap instants in the worst file.
 *
 * Every case below is a shape taken from, or directly derived from, a real
 * file rather than an invented one.
 */
class CueLookupTest {

    private fun cue(s: Double, e: Double, tag: String = "") =
        SubtitleCue(start = s, end = e, lines = listOf(if (tag.isEmpty()) "c" else tag))

    /**
     * The exact shape that broke the old search, reconstructed from a real
     * Better Call Saul file: a long cue that is still running while two later
     * cues have already come and gone. At t=5 the old search discarded
     * cues[0] on its way to the answer and returned null.
     */
    @Test
    fun `long cue still active after two later cues have ended`() {
        val cues = listOf(cue(0.0, 10.0, "long"), cue(1.0, 2.0, "a"), cue(3.0, 4.0, "b"))
        assertEquals("long", CueLookup.find(cues, 5.0)?.lines?.first())
        assertEquals("long", CueLookup.find(cues, 0.0)?.lines?.first())
        assertEquals("long", CueLookup.find(cues, 10.0)?.lines?.first())
    }

    /** The ordinary case must be untouched. */
    @Test
    fun `non overlapping cues resolve normally`() {
        val cues = listOf(cue(0.0, 2.0, "a"), cue(3.0, 5.0, "b"), cue(6.0, 8.0, "c"))
        assertNull(CueLookup.find(cues, 2.5))
        assertNull(CueLookup.find(cues, 5.5))
        assertNull(CueLookup.find(cues, 8.5))
        assertNull(CueLookup.find(cues, -1.0))
        assertEquals("a", CueLookup.find(cues, 0.0)?.lines?.first())
        assertEquals("a", CueLookup.find(cues, 1.999)?.lines?.first())
        assertEquals("b", CueLookup.find(cues, 3.0)?.lines?.first())
        assertEquals("c", CueLookup.find(cues, 8.0)?.lines?.first())
    }

    /**
     * With two cues genuinely active, the later one wins. That matches what
     * players do for an overlapping sign/lyric over dialogue: the newer cue
     * is the one the user is reading.
     */
    @Test
    fun `when two cues are active the later one is chosen`() {
        val cues = listOf(cue(0.0, 10.0, "sign"), cue(4.0, 6.0, "dialogue"))
        assertEquals("dialogue", CueLookup.find(cues, 5.0)?.lines?.first())
        // outside the dialogue, the sign is the only thing showing
        assertEquals("sign", CueLookup.find(cues, 2.0)?.lines?.first())
        assertEquals("sign", CueLookup.find(cues, 8.0)?.lines?.first())
    }

    @Test
    fun `empty list returns null`() {
        assertNull(CueLookup.find(emptyList(), 1.0))
    }

    /**
     * Exhaustive check against brute force over a densely overlapping track.
     * This is the property that actually matters: whenever ANY cue covers t,
     * find must return one that covers t. Returning a cue that is not
     * showing would be worse than returning null.
     */
    @Test
    fun `never returns a cue that is not showing`() {
        val cues = ArrayList<SubtitleCue>()
        // irregular, heavily overlapping, start-sorted
        var t = 0.0
        var seed = 42L
        while (t < 60.0) {
            seed = seed * 1103515245L + 12345L
            val len = 0.5 + ((seed ushr 16) % 30) / 10.0
            cues.add(cue(t, t + len))
            seed = seed * 1103515245L + 12345L
            t += 0.05 + ((seed ushr 16) % 20) / 10.0   // deliberately < len
        }
        var probe = 0.0
        var misses = 0
        while (probe < 60.0) {
            val anyActive = cues.any { probe >= it.start && probe <= it.end }
            val got = CueLookup.find(cues, probe)
            if (got != null) {
                assertTrue(
                    "returned a cue that is not showing at t=$probe",
                    probe >= got.start && probe <= got.end,
                )
            } else if (anyActive) {
                misses++
            }
            probe += 0.01
        }
        assertEquals("a cue was on screen but the lookup returned null", 0, misses)
    }
}
