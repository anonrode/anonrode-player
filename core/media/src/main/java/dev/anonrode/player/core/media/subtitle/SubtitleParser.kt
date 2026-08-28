package dev.anonrode.player.core.media.subtitle

import dev.anonrode.player.core.model.SubtitleCue

/**
 * Subtitle parsers: SRT, WebVTT, ASS/SSA, MicroDVD/MPL2 (.sub/.mpl),
 * TMP (.tmp), SubViewer (.sub), TTML/DFXP (.ttml/.dfxp/.xml).
 *
 * The core three are ported from the original mxweb-player JS
 * implementation, then hardened to mainstream-player tolerance:
 *   - BOM stripped, CRLF / lone-CR normalized to LF before any parsing
 *   - timestamps tolerate missing milliseconds, comma OR dot decimals,
 *     missing hours (VTT MM:SS.mmm) and >24h values; a malformed
 *     timestamp drops ONE cue, never aborts the file
 *   - HTML tags AND ASS-style {\an8} override blocks are stripped from
 *     display text (very common in SRT converted from ASS)
 *   - a timing line always terminates the previous cue's text, so files
 *     with missing blank-line separators still parse cleanly
 *   - all regexes are compiled once; parsing is a single pass over the
 *     line list, friendly to multi-MB files
 * Bitmap formats (PGS/VobSub/DVB) are out of scope for a text overlay.
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

    // ── shared machinery ──────────────────────────────────────────────

    /** `HH:MM:SS.mmm --> HH:MM:SS.mmm` (lenient character class; the real
     *  validation happens in [parseTime]). */
    private val TIMING = Regex("""([0-9:,.]+)\s*-->\s*([0-9:,.]+)""")

    private val HTML_TAG = Regex("<[^>]+>")
    private val ASS_BLOCK = Regex("\\{[^}]*\\}")   // closed {override blocks}
    private val ASS_OPEN = Regex("\\{[^}]*")      // unclosed { to end of line
    private val STRAY_BRACE = Regex("\\}+")       // stray }
    private val ASS_NEWLINE = Regex("\\\\[Nn]")   // \N / \n hard break

    /** BOM strip + CRLF/CR → LF normalization, split once. */
    private fun splitLines(raw: String): List<String> =
        raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')

    /**
     * Timestamp → seconds. Accepts H:MM:SS.mmm, MM:SS.mmm (VTT), missing
     * milliseconds, comma decimals (SRT) and hours > 24. Returns -1 for
     * anything unparseable so the caller can drop just that cue.
     */
    private fun parseTime(t: String): Double {
        val parts = t.trim().replace(',', '.').split(':')
        if (parts.isEmpty() || parts.size > 3) return -1.0
        var secs = 0.0
        for (p in parts) {
            val v = p.toDoubleOrNull() ?: return -1.0
            if (v < 0 || !v.isFinite()) return -1.0
            secs = secs * 60 + v
        }
        return secs
    }

    /**
     * Clean one display-text line: ASS override blocks first (they appear
     * in SRT converted from ASS, e.g. {\an8}), then HTML/XML tags, then
     * the common entities. Empty result = line contributes nothing.
     */
    private fun stripTags(s: String): String = s
        .replace(ASS_BLOCK, "")
        .replace(ASS_OPEN, "")
        .replace(STRAY_BRACE, "")
        .replace(HTML_TAG, "")
        .replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&nbsp;", " ")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .trim()

    /**
     * Collect cue text lines starting at [from]; stops at a blank line OR
     * a line containing a timing arrow (files with missing separators).
     * Returns (lines, nextIndex).
     */
    private fun collectText(
        lines: List<String>,
        from: Int,
    ): Pair<List<String>, Int> {
        val txt = ArrayList<String>()
        var i = from
        while (i < lines.size) {
            val l = lines[i]
            if (l.trim().isEmpty() || TIMING.containsMatchIn(l)) break
            val s = stripTags(l)
            if (s.isNotEmpty()) txt.add(s)
            i++
        }
        return txt to i
    }

    /** Shared SRT/WebVTT body: scan for timing lines, read text blocks. */
    private fun parseCueBlocks(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = splitLines(raw)
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            // Cue-index lines (SRT) and headers/NOTE/STYLE (VTT): skip.
            if (line.isEmpty() || line.all { it.isDigit() }) {
                i++
                continue
            }
            val tm = TIMING.find(line)
            if (tm == null) {
                i++
                continue
            }
            val start = parseTime(tm.groupValues[1])
            val end = parseTime(tm.groupValues[2])
            val (txt, next) = collectText(lines, i + 1)
            i = next
            if (txt.isNotEmpty() && start >= 0 && end > start) {
                cues.add(SubtitleCue(start, end, txt))
            }
        }
        return cues
    }

    private fun parseSrt(raw: String): List<SubtitleCue> = parseCueBlocks(raw)

    private fun parseVtt(raw: String): List<SubtitleCue> = parseCueBlocks(raw)

    private fun parseAss(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (line in splitLines(raw)) {
            val t = line.trim()
            if (!t.startsWith("Dialogue:", ignoreCase = true)) continue
            // Fields: "Dialogue: Marked,Start,End,Style,Name,MarginL,
            // MarginR,MarginV,Effect,Text…" — text may contain commas, so
            // it is everything from field 9 onward.
            val p = t.split(',')
            if (p.size < 10) continue
            val start = parseTime(p[1].trim())
            val end = parseTime(p[2].trim())
            val text = p.drop(9).joinToString(",")
                .replace(ASS_BLOCK, "")
                .replace(ASS_OPEN, "")
                .replace(STRAY_BRACE, "")
                .replace(ASS_NEWLINE, "\n")
                .replace("\\h", " ")
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

    private val MICRO_DVD_LINE = Regex("""^\{(\d+)\}\{(\d+)\}(.*)$""")

    /**
     * MicroDVD: `{startFrame}{endFrame}text|text`. Frame-based — without a
     * fps hint we assume 25fps (the de-facto default; MPL2 `.mpl` files use
     * tenths of a second instead, dispatched with fps=10).
     */
    private fun parseMicroDvd(raw: String, fps: Double = 25.0): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (line in splitLines(raw)) {
            val m = MICRO_DVD_LINE.find(line.trim()) ?: continue
            val start = (m.groupValues[1].toLongOrNull() ?: continue).toDouble() / fps
            val end = (m.groupValues[2].toLongOrNull() ?: continue).toDouble() / fps
            val txt = m.groupValues[3]
                .replace(ASS_BLOCK, "") // {style} markers
                .split('|')
                .map { stripTags(it).trim() }
                .filter { it.isNotEmpty() }
            if (txt.isNotEmpty() && start >= 0 && end > start) {
                cues.add(SubtitleCue(start, end, txt))
            }
        }
        return cues
    }

    private val SUBVIEWER_TIMING = Regex(
        """^([0-9]{1,2}:[0-9]{2}:[0-9]{2}[.,][0-9]{1,3})\s*,\s*""" +
            """([0-9]{1,2}:[0-9]{2}:[0-9]{2}[.,][0-9]{1,3})"""
    )

    /** SubViewer: `00:01:23.45,00:01:26.78` timing line, text until blank. */
    private fun parseSubViewer(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = splitLines(raw)
        var i = 0
        while (i < lines.size) {
            val m = SUBVIEWER_TIMING.find(lines[i].trim())
            if (m != null) {
                val start = parseTime(m.groupValues[1])
                val end = parseTime(m.groupValues[2])
                val (txt, next) = collectText(lines, i + 1)
                i = next
                if (txt.isNotEmpty() && start >= 0 && end > start) {
                    cues.add(SubtitleCue(start, end, txt))
                }
            } else i++
        }
        return cues
    }

    private val TMP_LINE = Regex("""^(\d{1,2}):(\d{1,2}):(\d{1,2}):(.*)$""")

    /**
     * TMP: `H:MM:SS:text` — start times only; each cue runs until the next
     * one (last cue gets a 2s tail).
     */
    private fun parseTmp(raw: String): List<SubtitleCue> {
        val entries = ArrayList<Pair<Double, List<String>>>()
        for (line in splitLines(raw)) {
            val m = TMP_LINE.find(line.trim()) ?: continue
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

    private val TTML_P = Regex(
        """<(?:[\w-]+:)?p\b([^>]*)>(.*?)</(?:[\w-]+:)?p>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )
    private val TTML_ATTR = Regex(
        "(begin|end|dur)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')",
        RegexOption.IGNORE_CASE,
    )
    private val TTML_BR = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /**
     * TTML/DFXP: extract `<p begin=".." end="..">` bodies (namespace
     * prefixes tolerated). Handles clock-time (`HH:MM:SS.mmm`, fractional
     * optional) and offset-time (`12.5s`, `500ms`) expressions; tick-rate
     * `t` expressions and `<br/>` → newline are covered, everything
     * fancier is stripped.
     */
    private fun parseTtml(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        for (m in TTML_P.findAll(raw)) {
            var start = -1.0
            var end = -1.0
            var dur = -1.0
            for (am in TTML_ATTR.findAll(m.groupValues[1])) {
                val v = parseTtmlTime(am.groupValues[2].ifEmpty { am.groupValues[3] })
                when (am.groupValues[1].lowercase()) {
                    "begin" -> start = v
                    "end" -> end = v
                    "dur" -> dur = v
                }
            }
            if (end < 0 && dur > 0 && start >= 0) end = start + dur
            val txt = m.groupValues[2]
                .replace(TTML_BR, "\n")
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
            return s.dropLast(1).toDoubleOrNull() ?: return -1.0
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
