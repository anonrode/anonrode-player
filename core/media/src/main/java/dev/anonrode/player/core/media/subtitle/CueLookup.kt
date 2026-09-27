package dev.anonrode.player.core.media.subtitle

import dev.anonrode.player.core.model.SubtitleCue

/**
 * Cue lookup for the player render tick.
 *
 * The obvious implementation — a plain binary search that discards half the
 * range whenever `t` falls past `cues[mid].end` — silently assumes the cues
 * do not overlap. They routinely do: ASS sign/song cues, two speaker lines,
 * bilingual tracks, and SRT files converted from them.
 *
 * Measured over 60 real subtitle files from the local library, 17 of them
 * contain overlapping cues. Under the non-overlap assumption the lookup
 * returned null while a cue was demonstrably on screen for 31% of the overlap
 * instants in one file and 13% in another. The render tick retries on a timer
 * and keeps getting null, so the subtitle is missing for the whole overlap,
 * not for one frame.
 *
 * Ends are not monotonic, so "the last cue that started before t" is not
 * enough either: a long cue can start far earlier and still be running while
 * several later cues have already ended. So upper-bound on `start`, then walk
 * back for a cue that genuinely covers `t`.
 *
 * [maxOverlap] caps that walk. The deepest walk any real file in the library
 * needed was 1 cue, so 32 is a wide margin and keeps the cost flat. Past it
 * this returns null, which is the previous behaviour, but it can never return
 * a cue that is not actually showing — the predicate is `start <= t <= end` —
 * so an over-deep overlap degrades to a missing subtitle, not a wrong one.
 */
object CueLookup {

    const val MAX_OVERLAP = 32

    fun find(cues: List<SubtitleCue>, t: Double, maxOverlap: Int = MAX_OVERLAP): SubtitleCue? {
        if (cues.isEmpty()) return null
        // upper bound: first index whose start is after t
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= t) lo = mid + 1 else hi = mid
        }
        var i = lo - 1
        var walked = 0
        while (i >= 0 && walked < maxOverlap) {
            val c = cues[i]
            if (t >= c.start && t <= c.end) return c
            i--
            walked++
        }
        return null
    }
}
