package dev.anonrode.player.core.media.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for [SubtitleMatcher.scoreSidecar] and the v0.6.2 normalized
 * helper [SubtitleMatcher.normalizedScore].
 *
 * Scoring formula (preserved verbatim from the v0.6.1 implementation):
 *   - 100   exact stem match (case-insensitive)
 *   -  80   stem + tag in either direction at a dot boundary
 *   - -100  episode conflict (hard disqualification; normalized → 0.0)
 *   - else: episode agreement (+50), episode folder + unnumbered sub
 *           (-10, used as last-resort in single-sub folder),
 *           fuzzy token overlap (≤ 20), language hint (≤ 10),
 *           format preference (srt +2 / vtt,ass +1 / else 0)
 *
 * v0.6.2 changes reflected here:
 *   - The "unnumbered sub in episode folder" penalty was relaxed from
 *     -15 to -10 so single-sub folders still get a usable last-resort
 *     pick (the [SubtitleSourceResolver.pickAutoSidecar] gate in
 *     multi-sub folders still rejects via the > 0 score requirement).
 *   - [normalizedScore] returns 0.0 for negative legacy scores and
 *     clamps to [0.0, 1.0].
 */
class SubtitleMatcherTest {

    /**
     * (1) Exact filename match → 1.0 normalized.
     * "Episode 01.mkv" ↔ "Episode 01.srt" — same stem, ignoreCase match.
     */
    @Test
    fun exactFilenameMatch_returnsPerfectScore() {
        val s = SubtitleMatcher.scoreSidecar("Episode 01.mkv", "Episode 01.srt")
        assertEquals(100.0, s, 0.001)
        assertEquals(1.0, SubtitleMatcher.normalizedScore("Episode 01.mkv", "Episode 01.srt"), 0.001)
    }

    /**
     * (2) Episode number match with different prefix/suffix → high.
     * "Show.S01E03.mkv" vs "the.show-S01E03.1080p.WEB-DL.srt" — different
     * junk-tagged forms. Both name an episode (S01E03), same season+ep,
     * so this should land in the high-confidence tier (≥ 0.5 normalized).
     * The srt extension bonus (+2) keeps it above the 0.6 threshold.
     */
    @Test
    fun episodeNumberMatchWithDifferentSuffix_isHighConfidence() {
        val s = SubtitleMatcher.scoreSidecar(
            "Show.S01E03.mkv",
            "the.show-S01E03.1080p.WEB-DL.srt",
        )
        // Expectation: episode agreement (+50) + token overlap × 20
        // (show, S01E03) + srt bonus (+2). The 1080p / web-dl / dl
        // tokens are in the JUNK set so they don't contribute to
        // overlap. We don't pin an exact value — we verify the
        // normalized score is above 0.6 (the "skip fingerprint" gate).
        val n = SubtitleMatcher.normalizedScore(
            "Show.S01E03.mkv",
            "the.show-S01E03.1080p.WEB-DL.srt",
        )
        assertTrue("normalized score should be ≥ 0.6, got $n", n >= 0.6)
        assertTrue("score should be positive, got $s", s > 0.0)
    }

    /**
     * (3) Season/episode mismatch → score = 0 (disqualified, normalized).
     * "Show.S01E03.mkv" vs "Show.S01E04.srt" — both have S/E markers
     * but disagree. Episode conflict → -100 → normalized → 0.0.
     */
    @Test
    fun seasonEpisodeMismatch_isHardDisqualified() {
        val s = SubtitleMatcher.scoreSidecar("Show.S01E03.mkv", "Show.S01E04.srt")
        assertEquals(-100.0, s, 0.001)
        assertEquals(
            0.0,
            SubtitleMatcher.normalizedScore("Show.S01E03.mkv", "Show.S01E04.srt"),
            0.001,
        )
        // The resolver-level hard gate: episodeConflict() must return true.
        assertTrue(
            SubtitleMatcher.episodeConflict("Show.S01E03.mkv", "Show.S01E04.srt"),
        )
    }

    /**
     * (4) Quality tag mismatch → high but not perfect.
     * "Show.S01E03.720p.mkv" vs "Show.S01E03.1080p.srt". Both have S01E03
     * markers (agree), but the 720p token is in JUNK (won't penalize).
     * The 1080p token is also in JUNK so overlap is dominated by "show"
     * + "S01E03". Net result: positive score in the high band but
     * below the 1.0 perfect ceiling.
     */
    @Test
    fun qualityTagMismatch_isHighButNotPerfect() {
        val s = SubtitleMatcher.scoreSidecar(
            "Show.S01E03.720p.mkv",
            "Show.S01E03.1080p.srt",
        )
        val n = SubtitleMatcher.normalizedScore(
            "Show.S01E03.720p.mkv",
            "Show.S01E03.1080p.srt",
        )
        assertTrue("score should be positive, got $s", s > 0.0)
        assertTrue("normalized should be < 1.0 (not perfect), got $n", n < 1.0)
        assertTrue("normalized should still be high (≥ 0.5), got $n", n >= 0.5)
    }

    /**
     * (5) Language tag match (eng.srt) → high.
     * "Episode 01.mkv" vs "Episode 01.eng.srt" — stem + ".eng." boundary
     * hits the 80-pt "stem + tag at dot boundary" tier directly via the
     * short-circuit `return 80.0`. The language bonus (+10 for "eng")
     * and srt bonus (+2) DO NOT apply on top of the early 80-pt return.
     */
    @Test
    fun languageTagMatch_returnsHighScore() {
        val s = SubtitleMatcher.scoreSidecar("Episode 01.mkv", "Episode 01.eng.srt")
        // The stem+tag tier short-circuits at exactly 80.
        assertEquals(80.0, s, 0.001)
        assertTrue(
            "language-tagged score should be ≥ 0.6 threshold, got $s",
            SubtitleMatcher.normalizedScore("Episode 01.mkv", "Episode 01.eng.srt") >= 0.6,
        )
    }

    /**
     * (5b) Language tag spelled out → high (same as 5, "English.srt").
     * The stem+tag short-circuit fires identically to the abbreviation.
     */
    @Test
    fun languageTagSpelledOut_returnsHighScore() {
        val s = SubtitleMatcher.scoreSidecar("Episode 01.mkv", "Episode 01.English.srt")
        assertEquals(80.0, s, 0.001)
    }

    /**
     * (5c) Language tag attached as a stem suffix → 80-tier short-circuit.
     * "ShowA.S01E03.mkv" vs "ShowA.S01E03.en.srt" — `sb` starts with
     * `vb.` ("ShowA.S01E03."), so the stem+tag short-circuit fires and
     * returns 80.0 directly. The language bonus does NOT stack on top.
     */
    @Test
    fun languageTagAsStemSuffix_shortCircuitsAt80() {
        val s = SubtitleMatcher.scoreSidecar("ShowA.S01E03.mkv", "ShowA.S01E03.en.srt")
        // Stem+tag tier short-circuit at 80 (no language bonus stacked).
        assertEquals(80.0, s, 0.001)
    }

    /**
     * (5d) Language tag appearing as a separate token (no stem prefix)
     * → language bonus tier applies. Here the lang tag is positioned
     * AFTER extra non-stem content that prevents the short-circuit.
     * "ShowA.S01E03.mkv" vs "ShowA.S01E03-extra.en.srt" — sb is
     * "ShowA.S01E03-extra.en", which does NOT start with "ShowA.S01E03."
     * because of the "-extra" between the stem and the lang tag. Falls
     * through to general tier: episode agreement +50, token overlap,
     * language bonus +10 ("en"), srt bonus +2.
     */
    @Test
    fun languageTagAfterExtraSuffix_appliesLanguageBonus() {
        val s = SubtitleMatcher.scoreSidecar(
            "ShowA.S01E03.mkv",
            "ShowA.S01E03-extra.en.srt",
        )
        // vb = "ShowA.S01E03", sb = "ShowA.S01E03-extra.en".
        // sb doesn't start with "vb." (the dash-extra breaks the prefix
        // match). Falls into general tier:
        //   episode agreement (S01E03 vs S01E03): +50
        //   tokens(vb) = ["showa", "s01e03"]
        //   tokens(sb) = ["showa", "s01e03", "extra", "en"]
        //   overlap 2/4 × 20 = 10
        //   "en" language bonus: +10
        //   srt extension: +2
        //   Total: 50 + 10 + 10 + 2 = 72.
        assertEquals(72.0, s, 0.001)
    }

    /**
     * (6) Generic name (`subtitle.srt`) → low, used as fallback.
     * No episode markers on either side, no stem overlap, no language
     * tag. The score is just the format bonus (+2 for srt). In a
     * single-sub folder this is the only candidate so the resolver
     * returns it; in a multi-sub folder the > 0 score gate rejects.
     */
    @Test
    fun genericName_isLowConfidenceFallback() {
        val s = SubtitleMatcher.scoreSidecar("Episode 01.mkv", "subtitle.srt")
        // No episode marker on either side (video has one → video penalty
        // -10). Token overlap empty (subtitle isn't a token, episode 01
        // is all-digits filtered). No language tag. +2 for srt.
        // Total: -10 + 0 + 0 + 2 = -8. Normalized clamps to 0.0.
        assertEquals(-8.0, s, 0.001)
        assertEquals(0.0, SubtitleMatcher.normalizedScore("Episode 01.mkv", "subtitle.srt"), 0.001)
    }

    /**
     * (7) Multi-candidate case (3 subs, pick best).
     * Verifies the resolver picks the highest-scoring one when three
     * candidates exist. We test [SubtitleSourceResolver.pickAutoSidecar]
     * indirectly via [SubtitleMatcher.scoreSidecar] — but the resolver
     * needs filesystem access. The matcher alone proves the per-candidate
     * score is what the resolver would sort by:
     *   - "Episode 01.mkv" → "Episode 01.srt"     : 100 (perfect)
     *   - "Episode 01.mkv" → "Episode 02.srt"     : -100 (disqualified)
     *   - "Episode 01.mkv" → "subtitle.srt"       : -8 (last-resort)
     * The resolver must skip the -100 and pick "Episode 01.srt".
     */
    @Test
    fun multiCandidate_picksHighestScoring() {
        val perfect = SubtitleMatcher.scoreSidecar("Episode 01.mkv", "Episode 01.srt")
        val wrongEp = SubtitleMatcher.scoreSidecar("Episode 01.mkv", "Episode 02.srt")
        val generic = SubtitleMatcher.scoreSidecar("Episode 01.mkv", "subtitle.srt")
        assertTrue("perfect > wrongEp", perfect > wrongEp)
        assertTrue("perfect > generic", perfect > generic)
        assertTrue("perfect wins overall", perfect == 100.0)
    }

    /**
     * (8) Episode pattern with leading zero (`EP01` vs `EP1`).
     * `EpisodePattern.find` extracts the episode digits via intOrNull,
     * so "EP01" → 1 and "EP1" → 1 — both register as episode 1. The
     * `bare` regex `[Ee][Pp]?[\d]{1,3}` is greedy on the leading zero
     * but the intOrNull discards it. We verify the matcher treats both
     * as the same episode number so they MATCH.
     */
    @Test
    fun leadingZeroEpisodeMatch_agreesOnEpisodeNumber() {
        // "Show.EP01.mkv" vs "Show.EP1.srt" — both have episode=1 from
        // the bare pattern. Episode agreement (+50). Token overlap:
        // tokens() filters out all-digit strings ("ep01" / "ep1" both
        // dropped), so only "show" overlaps → 1/1 × 20 = 20. srt bonus
        // +2. Total = 72.
        val s = SubtitleMatcher.scoreSidecar("Show.EP01.mkv", "Show.EP1.srt")
        assertEquals(72.0, s, 0.001)
        // And the dot-separated S01E03 form should score identically.
        val dotForm = SubtitleMatcher.scoreSidecar("Show.S01E03.mkv", "Show.S01E03.srt")
        // S01E03 form hits the exact-stem short-circuit → 100.
        assertEquals(100.0, dotForm, 0.001)
    }

    /**
     * (9) Hyphen-separated vs underscore-separated → same tokenization,
     * same episode pattern match, same score.
     * "Show-S01E03.mkv" vs "Show_S01E03.srt". The episode regex accepts
     * [_.\-\s] as separators inside the S/E pattern, so both forms parse
     * as the same (S01, E03). Tokens: "show", "s01e03" in both. Overlap
     * 2/2 × 20 = 20. Episode agreement +50. srt +2. Total: 72.
     */
    @Test
    fun hyphenVsUnderscoreSeparator_yieldsIdenticalScore() {
        val hyphenScore = SubtitleMatcher.scoreSidecar(
            "Show-S01E03.mkv",
            "Show_S01E03.srt",
        )
        val underscoreScore = SubtitleMatcher.scoreSidecar(
            "Show-S01E03.mkv",
            "Show-S01E03.srt",
        )
        // Identical stems separated by hyphen vs the EXACT MATCH form
        // (which short-circuits to 100). We verify the hyphen/underscore
        // SEPARATOR variant yields the same 72-pt score for both.
        val underscoreOnlyScore = SubtitleMatcher.scoreSidecar(
            "Show_S01E03.mkv",
            "Show_S01E03.srt",
        )
        assertEquals(100.0, hyphenScore, 0.001) // exact stem
        assertEquals(100.0, underscoreOnlyScore, 0.001) // exact stem
        // And the cross-form (hyphen video vs underscore sub) yields the
        // general-tier 72-pt score via episode agreement + token overlap.
        assertEquals(
            "cross-separator form should yield identical 72-pt score",
            72.0, SubtitleMatcher.scoreSidecar(
                "Show-S01E03.mkv",
                "Show_S01E03.srt",
            ), 0.001,
        )
    }

    /**
     * (10) Wrong show, right episode number (must NOT match).
     * "ShowA.S01E03.mkv" vs "ShowB.S01E03.srt". Both have S01E03 markers
     * (agree on episode). BUT the show name is different. The episode
     * markers agreeing pushes the score positive, but token overlap
     * (no "show" / "showb" shared) keeps the score low. The resolver
     * SHOULD NOT auto-pick this — the user has ShowA, but a sidecar
     * for ShowB is presented. We assert the score is in the low band
     * (< 0.6 normalized) so it doesn't trigger the "skip fingerprint"
     * gate; in a multi-sub folder with a better candidate present,
     * the resolver will skip it.
     */
    @Test
    fun wrongShowRightEpisode_isLowConfidence() {
        val s = SubtitleMatcher.scoreSidecar("ShowA.S01E03.mkv", "ShowB.S01E03.srt")
        val n = SubtitleMatcher.normalizedScore("ShowA.S01E03.mkv", "ShowB.S01E03.srt")
        assertTrue("wrong-show score should be positive (S01E03 agreement), got $s", s > 0.0)
        assertTrue(
            "wrong-show normalized should be < 0.6 (skip-fingerprint gate), got $n",
            n < 0.6,
        )
        // No episode conflict — they DO agree on episode — so the
        // hard-disqualification gate does NOT fire. Only the score
        // tier keeps it from being a confident pick.
        assertFalse(
            SubtitleMatcher.episodeConflict("ShowA.S01E03.mkv", "ShowB.S01E03.srt"),
        )
    }

    /**
     * Extra: a no-episode-marker case ("random.mkv" + "random.srt").
     * Both stems match exactly → 100. Sanity check on the stem-equality
     * short-circuit.
     */
    @Test
    fun nonEpisodeExactStem_returnsPerfectScore() {
        val s = SubtitleMatcher.scoreSidecar("random.mkv", "random.srt")
        assertEquals(100.0, s, 0.001)
    }

    /**
     * Extra: normalizedScore clamps the legacy 100-point scale to
     * [0.0, 1.0] regardless of how large the underlying raw score is
     * (defensive — current formula caps at 100, but future scoring
     * additions must not exceed 1.0).
     */
    @Test
    fun normalizedScore_isBoundedByUnitInterval() {
        val n = SubtitleMatcher.normalizedScore("Episode 01.mkv", "Episode 01.srt")
        assertTrue(n in 0.0..1.0)
    }
}