package dev.anonrode.player.core.media.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.8.3 — pure-logic tests for the onset cache's seam merge: the cached
 * prefix ends where the resumed decode begins, and the detectors' clocks
 * can disagree by a few ms around that seam. The merge must sort, dedupe
 * the overlap (50 ms — the resolution the detectors themselves have), and
 * never lose a distinct onset.
 */
class OnsetCacheTest {

    @Test
    fun `disjoint prefix and suffix pass through sorted`() {
        val merged = OnsetCache.mergeOnsets(
            listOf(1.0, 3.0, 5.0),
            listOf(6.0, 7.5, 9.0),
        )
        assertEquals(listOf(1.0, 3.0, 5.0, 6.0, 7.5, 9.0), merged)
    }

    @Test
    fun `seam duplicates inside the dedup window collapse`() {
        // The resumed pass re-detected 5.02 (overlap) — one onset, not two.
        val merged = OnsetCache.mergeOnsets(
            listOf(1.0, 5.0),
            listOf(5.02, 6.0),
        )
        assertEquals(listOf(1.0, 5.0, 6.0), merged)
    }

    @Test
    fun `distinct onsets outside the window survive`() {
        val merged = OnsetCache.mergeOnsets(
            listOf(5.0),
            listOf(5.1, 6.0),
        )
        assertEquals(listOf(5.0, 5.1, 6.0), merged)
    }

    @Test
    fun `empty sides short-circuit to the other list`() {
        assertEquals(listOf(1.0, 2.0), OnsetCache.mergeOnsets(emptyList(), listOf(1.0, 2.0)))
        assertEquals(listOf(1.0, 2.0), OnsetCache.mergeOnsets(listOf(1.0, 2.0), emptyList()))
    }

    @Test
    fun `hybrid union dedupes both sources together`() {
        val sources = OnsetExtractor.OnsetSources(
            silencedetect = listOf(1.0, 3.0),
            vad = listOf(1.02, 2.5),
        )
        assertEquals(listOf(1.0, 2.5, 3.0), sources.hybrid)
    }
}
