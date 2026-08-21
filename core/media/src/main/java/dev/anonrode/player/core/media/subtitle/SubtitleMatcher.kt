package dev.anonrode.player.core.media.subtitle

import dev.anonrode.player.core.model.EpisodePattern

/**
 * Subtitle auto-match scoring engine.
 *
 * Ported from the original mxweb-player JS: episode extraction + duration
 * similarity + token overlap (with junk-word filtering) + language hints.
 * Higher score = better match. 100 = exact filename match.
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
            if (Regex("(?:^|[._\\-\\s\\[(])$kw(?:[._\\-\\s\\])]|$)").containsMatchIn(sl)) {
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

    private fun tokens(name: String): List<String> =
        name.lowercase()
            .replace(Regex("[._\\-\\[\\](){}+]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 && it !in JUNK && !it.all { c -> c.isDigit() } }
}
