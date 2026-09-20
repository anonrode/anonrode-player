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
    fun `union dedupes at the 50ms resolution the detectors have`() {
        // The hybrid-union contract the cache merge relies on: 1.0 and
        // 1.02 are one onset; 1.0 and 1.1 are two. OnsetCache.union IS
        // mergeOnsets' semantics — exercised directly, no Context needed.
        assertEquals(
            listOf(1.0, 2.5, 3.0),
            OnsetCache.mergeOnsets(listOf(1.0, 3.0), listOf(1.02, 2.5)),
        )
    }

    @Test
    fun `mergeEnvelope combines prefix and suffix at coveredSec boundary`() {
        val prefix = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f)
        val suffix = floatArrayOf(0.6f, 0.7f, 0.8f)
        // 0.35s coveredSec at 0.1s/bin -> takes first 3 bins of prefix (0.1, 0.2, 0.3) + suffix (0.6, 0.7, 0.8).
        // (0.35 rather than 0.3 so the division is not an exact FP boundary:
        // 0.3/0.1 == 2.9999999999999996 and truncates to 2, which would test
        // the truncation instead of the boundary.)
        val merged = OnsetCache.mergeEnvelope(prefix, suffix, 0.35, 0.1)
        assertEquals(6, merged.size)
        org.junit.Assert.assertArrayEquals(
            floatArrayOf(0.1f, 0.2f, 0.3f, 0.6f, 0.7f, 0.8f),
            merged,
            0.0001f,
        )
    }

    @Test
    fun `mergeEnvelope short-circuits on empty inputs`() {
        val data = floatArrayOf(0.5f, 0.9f)
        org.junit.Assert.assertArrayEquals(data, OnsetCache.mergeEnvelope(FloatArray(0), data, 0.0), 0.0001f)
        org.junit.Assert.assertArrayEquals(data, OnsetCache.mergeEnvelope(data, FloatArray(0), 1.0), 0.0001f)
    }
}
