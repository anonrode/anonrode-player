package dev.anonrode.player.core.media.subtitle

import dev.anonrode.player.core.model.SubtitleCue

/**
 * Subtitle parsers: SRT, WebVTT, ASS/SSA.
 *
 * Ported from the original mxweb-player JS implementation, including its
 * hardening: negative timestamps are dropped, ASS tag stripping removes
 * unclosed braces and nested-brace residue, CRLF normalized.
 */
object SubtitleParser {

    fun parse(fileName: String, raw: String): List<SubtitleCue> = when {
        fileName.endsWith(".vtt", ignoreCase = true) -> parseVtt(raw)
        fileName.endsWith(".ass", ignoreCase = true) || fileName.endsWith(".ssa", ignoreCase = true) -> parseAss(raw)
        else -> parseSrt(raw)
    }

    private fun parseTime(t: String): Double {
        val parts = t.trim().replace(',', '.').split(':').mapNotNull { it.toDoubleOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> -1.0
        }
    }

    private fun stripTags(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&nbsp;", " ")
        .trim()

    private fun parseSrt(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim().all { it.isDigit() }) i++
            val tm = Regex("""([0-9:,.]+)\s*-->\s*([0-9:,.]+)""").find(lines.getOrElse(i) { "" })
            if (tm != null) {
                val start = parseTime(tm.groupValues[1])
                val end = parseTime(tm.groupValues[2])
                i++
                val txt = ArrayList<String>()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    val s = stripTags(lines[i])
                    if (s.isNotEmpty()) txt.add(s)
                    i++
                }
                if (txt.isNotEmpty() && start >= 0 && end > start) {
                    cues.add(SubtitleCue(start, end, txt))
                }
            } else i++
        }
        return cues
    }

    private fun parseVtt(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val tm = Regex("""([0-9:,.]+)\s*-->\s*([0-9:,.]+)""").find(lines.getOrElse(i) { "" })
            if (tm != null) {
                val start = parseTime(tm.groupValues[1])
                val end = parseTime(tm.groupValues[2])
                i++
                val txt = ArrayList<String>()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    val s = stripTags(lines[i])
                    if (s.isNotEmpty()) txt.add(s)
                    i++
                }
                if (txt.isNotEmpty() && start >= 0 && end > start) {
                    cues.add(SubtitleCue(start, end, txt))
                }
            } else i++
        }
        return cues
    }

    private fun parseAss(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (line in raw.split(Regex("\r?\n"))) {
            if (!line.startsWith("Dialogue:")) continue
            val p = line.split(',')
            if (p.size < 10) continue
            val start = parseTime(p[1].trim())
            val end = parseTime(p[2].trim())
            val text = p.drop(9).joinToString(",")
                .replace(Regex("\\{[^}]*\\}"), "") // closed {blocks}
                .replace(Regex("\\{[^}]*"), "")    // unclosed { to EOL
                .replace(Regex("\\}+"), "")        // stray }
                .replace(Regex("\\\\[Nn]"), "\n")
                .trim()
            val txt = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (txt.isNotEmpty() && start >= 0 && end > start) {
                cues.add(SubtitleCue(start, end, txt))
            }
        }
        return cues
    }

    /** Last end time, used as a duration proxy for matcher scoring. */
    fun lastEndMs(cues: List<SubtitleCue>): Double {
        var last = 0.0
        for (c in cues) if (c.end > last) last = c.end
        return last
    }
}
