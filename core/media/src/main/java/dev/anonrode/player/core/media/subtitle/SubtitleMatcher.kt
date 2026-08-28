package dev.anonrode.player.core.media.subtitle

import dev.anonrode.player.core.model.EpisodePattern

/**
 * Subtitle auto-match scoring engine.
 *
 * Ported from the original mxweb-player JS: episode extraction + duration
 * similarity + token overlap (with junk-word filtering) + language hints.
 * Higher score = better match. 100 = exact filename match.
 *
 * [scoreSidecar] / [episodeConflict] are the canonical NAME-ONLY scoring
 * pair used by [SubtitleSourceResolver.pickAutoSidecar] — the single
 * auto-pick both playback and the sync fingerprint job must share so a
 * persisted lock is never fitted to a different file than the rendered
 * one (audit #23).
 */
object SubtitleMatcher {

    private val JUNK = setOf(
        "720p", "1080p", "2160p", "480p", "4k", "uhd", "hdr", "bluray", "bdrip", "brrip",
        "webrip", "webdl", "web", "dl", "x264", "x265", "h264", "h265", "hevc", "xvid", "aac",
        "ac3", "dts", "yify", "yts", "rarbg", "proper", "repack", "extended", "unrated",
        "dubbed", "subbed", "multi", "dual", "hdrip", "dvdrip", "hdtv", "cam",
    )

    private val LANG_W = mapOf(
        "english" to 1.0, "eng" to 1.0, "en" to 0.9, "us" to 0.85, "uk" to 0.85,
        "yoruba" to 0.7, "igbo" to 0.7, "hausa" to 0.7, "pidgin" to 0.65,
        "french" to 0.5, "spanish" to 0.5, "arabic" to 0.5,
    )

    /** Precompiled word-boundary matchers for [LANG_W] (built once). */
    private val LANG_RE: Map<String, Regex> = LANG_W.keys.associateWith { kw ->
        Regex("(?:^|[._\\-\\s\\[(])$kw(?:[._\\-\\s\\])]|$)")
    }

    /**
     * @param videoName filename of the video (with extension)
     * @param subName filename of the subtitle candidate
     * @param subText subtitle file text (used for duration similarity)
     * @param videoDurationMs video duration from metadata (0 if unknown)
     */
    fun score(videoName: String, subName: String, subText: String?, videoDurationMs: Long): Double {
        val vb = videoName.substringBeforeLast('.', videoName)
        val sb = subName.substringBeforeLast('.', subName)

        if (sb.equals(vb, ignoreCase = true)) return 100.0

        var sc = 0.0

        val ve = EpisodePattern.find(vb)
        val se = EpisodePattern.find(sb)
        if (ve != null && se != null) {
            val sameSeason = (ve.first == null || se.first == null) || ve.first == se.first
            val sameEp = ve.second == se.second
            sc += when {
                sameSeason && sameEp -> 50.0
                !sameSeason -> -60.0
                else -> -(10.0 + Math.abs(ve.second - se.second) * 5.0)
            }
        } else if (ve != null && se == null) {
            sc -= 10.0
        }

        if (subName.endsWith(".srt", ignoreCase = true) && subText != null) {
            val subMs = SubtitleParser.lastEndMs(SubtitleParser.parse(subName, subText)) * 1000.0
            if (videoDurationMs > 0 && subMs > 0) {
                val d = Math.abs(videoDurationMs - subMs) / videoDurationMs
                sc += when {
                    d <= .01 -> 30.0
                    d <= .02 -> 26.0
                    d <= .05 -> 20.0
                    d <= .10 -> 10.0
                    d <= .20 -> 4.0
                    else -> 0.0
                }
            }
        }

        val vw = tokens(vb)
        val sw = tokens(sb)
        if (vw.isNotEmpty() && sw.isNotEmpty()) {
            val vs = vw.toSet()
            val ss = sw.toSet()
            val inter = vs.count { it in ss }
            val union = (vs + ss).size
            sc += inter.toDouble() / union * 20.0
        }

        val sl = sb.lowercase()
        for ((kw, w) in LANG_W) {
            if (LANG_RE.getValue(kw).containsMatchIn(sl)) {
                sc += w * 10
                break
            }
        }

        val ext = subName.substringAfterLast('.', "").lowercase()
        sc += when (ext) {
            "srt" -> 2.0
            "vtt" -> 1.0
            else -> 0.0
        }
        return sc
    }

    /**
     * True when BOTH names carry season/episode markers and they disagree
     * (different season or different episode). Such a candidate must never
     * be auto-picked: in a folder full of episodes, episode 2's subtitle
     * would otherwise out-score on shared series tokens and play over
     * episode 3. When either side has no episode marker there is no
     * conflict to detect (fuzzy scoring handles it).
     */
    fun episodeConflict(videoName: String, subName: String): Boolean {
        val ve = EpisodePattern.find(stem(videoName)) ?: return false
        val se = EpisodePattern.find(stem(subName)) ?: return false
        val sameSeason = (ve.first == null || se.first == null) || ve.first == se.first
        return !sameSeason || ve.second != se.second
    }

    /**
     * Name-only sidecar score — no file contents needed, so an auto-pick
     * never reads every candidate from disk. Tiers:
     *   100  exact stem match ("Show.S01E03.mkv" ↔ "Show.S01E03.srt")
     *    80  stem + tag in either direction at a dot boundary
     *        ("Show.S01E03.eng.srt", or the sub being the shorter stem)
     *   -100 episode conflict (hard disqualification)
     *   else episode agreement (+50) + fuzzy token overlap (≤20) +
     *        language hint (≤10) + format preference (≤2); a video with
     *        episode markers and an unnumbered candidate is penalized.
     */
    fun scoreSidecar(videoName: String, subName: String): Double {
        val vb = stem(videoName)
        val sb = stem(subName)

        if (sb.equals(vb, ignoreCase = true)) return 100.0
        if (sb.startsWith("$vb.", ignoreCase = true) ||
            vb.startsWith("$sb.", ignoreCase = true)
        ) return 80.0
        if (episodeConflict(videoName, subName)) return -100.0

        var sc = 0.0
        val ve = EpisodePattern.find(vb)
        val se = EpisodePattern.find(sb)
        if (ve != null && se != null) {
            // Conflict was ruled out above → season + episode agree.
            sc += 50.0
        } else if (ve != null) {
            // Episode folder, unnumbered file: ambiguous, keep it as a
            // last resort rather than a confident pick.
            sc -= 15.0
        }

        val vw = tokens(vb)
        val sw = tokens(sb)
        if (vw.isNotEmpty() && sw.isNotEmpty()) {
            val vs = vw.toSet()
            val ss = sw.toSet()
            val inter = vs.count { it in ss }
            val union = (vs + ss).size
            sc += inter.toDouble() / union * 20.0
        }

        val sl = sb.lowercase()
        for ((kw, w) in LANG_W) {
            if (LANG_RE.getValue(kw).containsMatchIn(sl)) {
                sc += w * 10
                break
            }
        }

        sc += when (subName.substringAfterLast('.', "").lowercase()) {
            "srt" -> 2.0
            "vtt", "ass" -> 1.0
            else -> 0.0
        }
        return sc
    }

    /** Filename without its final extension. */
    private fun stem(name: String): String = name.substringBeforeLast('.', name)

    private fun tokens(name: String): List<String> =
        name.lowercase()
            .replace(Regex("[._\\-\\[\\](){}+]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in JUNK && !it.all { c -> c.isDigit() } }
}
