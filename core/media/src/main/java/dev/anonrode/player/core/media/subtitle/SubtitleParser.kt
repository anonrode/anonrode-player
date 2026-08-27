package dev.anonrode.player.core.media.subtitle

import dev.anonrode.player.core.model.SubtitleCue

/**
 * Subtitle parsers: SRT, WebVTT, ASS/SSA, MicroDVD/MPL2 (.sub/.mpl),
 * TMP (.tmp), SubViewer (.sub), TTML/DFXP (.ttml/.dfxp/.xml).
 *
 * The core three are ported from the original mxweb-player JS
 * implementation, including its hardening: negative timestamps are
 * dropped, ASS tag stripping removes unclosed braces and nested-brace
 * residue, CRLF normalized. The text-based legacy formats cover the rest
 * of what VLC-style players accept; bitmap formats (PGS/VobSub/DVB) are
 * out of scope for a text overlay.
 */
object SubtitleParser {

    fun parse(fileName: String, raw: String): List<SubtitleCue> {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".vtt") -> parseVtt(raw)
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> parseAss(raw)
            lower.endsWith(".tmp") -> parseTmp(raw)
            lower.endsWith(".ttml") || lower.endsWith(".dfxp") || lower.endsWith(".xml") ->
                parseTtml(raw)
            lower.endsWith(".mpl") -> parseMicroDvd(raw, fps = 10.0) // MPL2 tenths-of-second
            lower.endsWith(".sub") -> parseSub(raw)
            else -> parseSrt(raw)
        }
    }

    /**
     * Raw-bytes entry point: charset detection (BOM / UTF-8 / GB18030 /
     * Big5 / EUC-KR / Shift_JIS / windows-1252) then format dispatch.
     * Prefer this over [parse] whenever the source is a file, not a
     * already-decoded string.
     */
    fun parseBytes(fileName: String, bytes: ByteArray): List<SubtitleCue> =
        parse(fileName, SubtitleDecoder.decode(bytes, fileName))

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

    // ── legacy text formats ───────────────────────────────────────────

    /**
     * `.sub` is ambiguous: MicroDVD frame-based cues or SubViewer timing
     * lines. Sniff the first non-blank line; fall back to SRT parsing.
     */
    private fun parseSub(raw: String): List<SubtitleCue> {
        val first = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (Regex("""^\{\d+\}\{\d+\}""").containsMatchIn(first)) return parseMicroDvd(raw)
        val sv = parseSubViewer(raw)
        if (sv.isNotEmpty()) return sv
        return parseSrt(raw)
    }

    /**
     * MicroDVD: `{startFrame}{endFrame}text|text`. Frame-based — without a
     * fps hint we assume 25fps (the de-facto default; MPL2 `.mpl` files use
     * tenths of a second instead, dispatched with fps=10).
     */
    private fun parseMicroDvd(raw: String, fps: Double = 25.0): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val re = Regex("""^\{(\d+)\}\{(\d+)\}(.*)$""")
        for (line in raw.split(Regex("\r?\n"))) {
            val m = re.find(line.trim()) ?: continue
            val start = (m.groupValues[1].toLongOrNull() ?: continue).toDouble() / fps
            val end = (m.groupValues[2].toLongOrNull() ?: continue).toDouble() / fps
            val txt = m.groupValues[3]
                .replace(Regex("\\{[^}]*\\}"), "") // {style} markers
                .split('|')
                .map { stripTags(it).trim() }
                .filter { it.isNotEmpty() }
            if (txt.isNotEmpty() && start >= 0 && end > start) {
                cues.add(SubtitleCue(start, end, txt))
            }
        }
        return cues
    }

    /** SubViewer: `00:01:23.45,00:01:26.78` timing line, text until blank. */
    private fun parseSubViewer(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = raw.removePrefix("\uFEFF").replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val timing = Regex(
            """^([0-9]{1,2}:[0-9]{2}:[0-9]{2}[.,][0-9]{1,3})\s*,\s*""" +
                """([0-9]{1,2}:[0-9]{2}:[0-9]{2}[.,][0-9]{1,3})"""
        )
        var i = 0
        while (i < lines.size) {
            val m = timing.find(lines[i].trim())
            if (m != null) {
                val start = parseTime(m.groupValues[1])
                val end = parseTime(m.groupValues[2])
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

    /**
     * TMP: `H:MM:SS:text` — start times only; each cue runs until the next
     * one (last cue gets a 2s tail).
     */
    private fun parseTmp(raw: String): List<SubtitleCue> {
        val entries = ArrayList<Pair<Double, List<String>>>()
        val re = Regex("""^(\d{1,2}):(\d{1,2}):(\d{1,2}):(.*)$""")
        for (line in raw.split(Regex("\r?\n"))) {
            val m = re.find(line.trim()) ?: continue
            val h = m.groupValues[1].toDoubleOrNull() ?: continue
            val mi = m.groupValues[2].toDoubleOrNull() ?: continue
            val s = m.groupValues[3].toDoubleOrNull() ?: continue
            val txt = stripTags(m.groupValues[4])
            if (txt.isNotEmpty()) entries.add((h * 3600 + mi * 60 + s) to listOf(txt))
        }
        val cues = ArrayList<SubtitleCue>()
        for (i in entries.indices) {
            val start = entries[i].first
            val end = if (i + 1 < entries.size) entries[i + 1].first else start + 2.0
            if (start >= 0 && end > start) cues.add(SubtitleCue(start, end, entries[i].second))
        }
        return cues
    }

    /**
     * TTML/DFXP: extract `<p begin=".." end="..">` bodies. Handles
     * clock-time (`HH:MM:SS.mmm`, fractional optional) and offset-time
     * (`12.5s`, `500ms`) expressions; tick-rate `t` expressions and
     * `<br/>` → newline are covered, everything fancier is stripped.
     */
    private fun parseTtml(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val pRe = Regex("""<p\b([^>]*)>(.*?)</p>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val attrRe = Regex("(begin|end|dur)\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        for (m in pRe.findAll(raw)) {
            var start = -1.0
            var end = -1.0
            var dur = -1.0
            for (am in attrRe.findAll(m.groupValues[1])) {
                val v = parseTtmlTime(am.groupValues[2])
                when (am.groupValues[1].lowercase()) {
                    "begin" -> start = v
                    "end" -> end = v
                    "dur" -> dur = v
                }
            }
            if (end < 0 && dur > 0 && start >= 0) end = start + dur
            val txt = m.groupValues[2]
                .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                .split('\n')
                .map { stripTags(it).trim() }
                .filter { it.isNotEmpty() }
            if (txt.isNotEmpty() && start >= 0 && end > start) {
                cues.add(SubtitleCue(start, end, txt))
            }
        }
        return cues
    }

    private fun parseTtmlTime(t: String): Double {
        val s = t.trim()
        if (s.endsWith("ms", ignoreCase = true)) {
            return (s.dropLast(2).toDoubleOrNull() ?: return -1.0) / 1000.0
        }
        if (s.endsWith("s", ignoreCase = true) && !s.contains(':')) {
            return s.dropLast(1).toDoubleOrNull() ?: -1.0
        }
        if (s.endsWith("h", ignoreCase = true)) {
            return (s.dropLast(1).toDoubleOrNull() ?: return -1.0) * 3600.0
        }
        if (s.endsWith("m", ignoreCase = true) && !s.contains(':')) {
            return (s.dropLast(1).toDoubleOrNull() ?: return -1.0) * 60.0
        }
        // Clock time: HH:MM:SS[.mmm] (also tolerate trailing :FF frames).
        val parts = s.split(':').mapNotNull { it.toDoubleOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            4 -> parts[0] * 3600 + parts[1] * 60 + parts[2] // drop frames field
            2 -> parts[0] * 60 + parts[1]
            else -> -1.0
        }
    }

    /** Last end time, used as a duration proxy for matcher scoring. */
    fun lastEndMs(cues: List<SubtitleCue>): Double {
        var last = 0.0
        for (c in cues) if (c.end > last) last = c.end
        return last
    }
}
