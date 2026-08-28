package dev.anonrode.player.core.media.subtitle

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.anonrode.player.core.model.SubtitleCue
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * Extracts embedded (in-container) text subtitle tracks — the subtitle
 * streams muxed into MKV/MP4 files — into the same [SubtitleCue] list the
 * sidecar pipeline uses, so the Compose overlay renderer and the auto-sync
 * engine work on them unchanged.
 *
 * Strategy: [MediaExtractor] enumerates tracks; text tracks are identified
 * by MIME. Samples are read with their container timestamps; since
 * MediaExtractor exposes no duration for text samples, cue ends are derived
 * from the following sample's start — this is container-agnostic and matches
 * how Media3's own SubripDecoder/SubRip handling treats Matroska blocks.
 * Per-MIME payload handling:
 *   - application/x-subrip : payload is the cue text (timing lives in the
 *     container block), strip HTML-style tags.
 *   - text/vtt             : cue payload, strip tags + cue settings.
 *   - text/x-ssa           : ASS/SSA "ReadOrder,Layer,Style,Name,MarginL,
 *     MarginR,MarginV,Effect,Text" dialogue fields (with or without the
 *     "Dialogue:" prefix); text = field 9 onward, ASS override blocks and
 *     \N stripped (same rules as [SubtitleParser.parseAss]).
 *   - application/ttml+xml : basic <p begin end> extraction, sample time
 *     fallback.
 *   - application/x-quicktime-tx3g (MP4/MOV) : binary sample — uint16
 *     big-endian text length + UTF-8 text (3GPP TS 26.245), style records
 *     after the text are dropped.
 * Bitmap tracks (PGS / DVB) are NOT enumerated — they need OCR/rendering,
 * out of scope for a text overlay.
 */
object EmbeddedSubtitleExtractor {

    /** One embedded text track the container exposes. */
    data class Track(
        /** MediaExtractor track index (pass back to [extractCues]). */
        val index: Int,
        val mime: String,
        /** ISO 639-2/T code from the container ("chi", "eng", "und"…). */
        val language: String?,
        /** Track title/name tag if the container stores one. */
        val title: String?,
        /** Ready-to-show label, e.g. "Chinese · SRT". */
        val label: String,
    )

    private const val MAX_SAMPLES = 60_000
    private const val DEFAULT_CUE_LEN_SEC = 2.0

    /** Cap for cues whose successor is far away (silence gap), in seconds. */
    private const val MAX_CUE_LEN_SEC = 7.0

    /** Text samples are tiny; a sample this big is corrupt — skip it. */
    private const val MAX_SAMPLE_BYTES = 1024 * 1024

    private val MIME_SRT = "application/x-subrip"
    private val MIME_VTT = "text/vtt"
    private val MIME_SSA = "text/x-ssa"
    private val MIME_TTML = setOf("application/ttml+xml", "text/ttml", "video/ttml")
    private val MIME_TX3G = setOf("application/x-quicktime-tx3g", "text/3gpp")

    /** Caption formats that carry no usable text samples via MediaExtractor. */
    private val MIME_SKIP = setOf(
        "text/cea-608", "text/cea-708", "application/cea-608", "application/cea-708",
    )

    fun listTracks(path: String): List<Track> = try {
        withExtractor({ it.setDataSource(path) }) { listTextTracks(it) }
    } catch (t: Throwable) {
        emptyList()
    }

    fun listTracks(fd: FileDescriptor): List<Track> = try {
        withExtractor({ it.setDataSource(fd) }) { listTextTracks(it) }
    } catch (t: Throwable) {
        emptyList()
    }

    fun extractCues(path: String, trackIndex: Int): List<SubtitleCue> = try {
        withExtractor({ it.setDataSource(path) }) { readCues(it, trackIndex) }
    } catch (t: Throwable) {
        emptyList()
    }

    fun extractCues(fd: FileDescriptor, trackIndex: Int): List<SubtitleCue> = try {
        withExtractor({ it.setDataSource(fd) }) { readCues(it, trackIndex) }
    } catch (t: Throwable) {
        emptyList()
    }

    private inline fun <T> withExtractor(
        open: (MediaExtractor) -> Unit,
        block: (MediaExtractor) -> T,
    ): T {
        val ex = MediaExtractor()
        try {
            open(ex)
            return block(ex)
        } finally {
            ex.release()
        }
    }

    private fun listTextTracks(ex: MediaExtractor): List<Track> {
        val out = ArrayList<Track>()
        for (i in 0 until ex.trackCount) {
            // One exotic/corrupt track must not hide the other tracks.
            try {
                val fmt = ex.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!isTextMime(mime)) continue
                val lang = fmt.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotEmpty() }
                // android.media.MediaFormat has no KEY_TITLE constant; MKV
                // track name tags surface (if at all) under the raw "title"
                // key.
                val title = fmt.getString("title")?.takeIf { it.isNotEmpty() }
                out.add(Track(i, mime, lang, title, buildLabel(lang, title, mime)))
            } catch (t: Throwable) {
                continue
            }
        }
        return out
    }

    private fun isTextMime(mime: String): Boolean {
        val m = mime.lowercase()
        if (m in MIME_SKIP) return false
        return m.startsWith("text/") ||
            m == MIME_SRT ||
            m in MIME_TX3G ||
            (m.startsWith("application/") &&
                (m.contains("subrip") || m.contains("ttml") || m.contains("ssa") ||
                    m.contains("tx3g")))
    }

    private fun buildLabel(lang: String?, title: String?, mime: String): String {
        val langName = lang?.let { LANG_NAMES[it.lowercase()] ?: it.uppercase() } ?: "Unknown"
        val fmtName = when (mime.lowercase()) {
            MIME_SRT -> "SRT"
            MIME_VTT -> "VTT"
            MIME_SSA -> "ASS"
            in MIME_TTML -> "TTML"
            in MIME_TX3G -> "TX3G"
            else -> mime.substringAfterLast('/').uppercase()
        }
        return if (title.isNullOrBlank()) "$langName · $fmtName" else "$title ($langName · $fmtName)"
    }

    private val LANG_NAMES = mapOf(
        "chi" to "Chinese", "zho" to "Chinese", "zhi" to "Chinese",
        "eng" to "English", "jpn" to "Japanese", "kor" to "Korean",
        "fre" to "French", "fra" to "French", "ger" to "German", "deu" to "German",
        "spa" to "Spanish", "por" to "Portuguese", "rus" to "Russian",
        "ara" to "Arabic", "tha" to "Thai", "vie" to "Vietnamese",
        "ita" to "Italian", "dut" to "Dutch", "nld" to "Dutch",
        "pol" to "Polish", "tur" to "Turkish", "hin" to "Hindi",
        "ind" to "Indonesian", "may" to "Malay", "msa" to "Malay",
        "und" to "Unknown",
    )

    // ── sample reading ────────────────────────────────────────────────

    private fun readCues(ex: MediaExtractor, trackIndex: Int): List<SubtitleCue> {
        if (trackIndex < 0 || trackIndex >= ex.trackCount) return emptyList()
        val fmt = ex.getTrackFormat(trackIndex)
        val mime = (fmt.getString(MediaFormat.KEY_MIME) ?: "").lowercase()
        if (!isTextMime(mime)) return emptyList()
        ex.selectTrack(trackIndex)

        val buf = ByteBuffer.allocateDirect(MAX_SAMPLE_BYTES)
        val cues = ArrayList<SubtitleCue>()
        // MediaExtractor exposes no per-sample duration for text tracks, so
        // each cue ends when the next sample starts (capped at
        // MAX_CUE_LEN_SEC so a silence gap can't freeze a cue on screen);
        // the final sample gets the default length.
        var prevStartSec = -1.0
        var prevPayload: ByteArray? = null
        var guard = 0
        while (guard++ < MAX_SAMPLES) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            val startSec = ex.sampleTime / 1_000_000.0
            if (n > buf.capacity()) {
                // Corrupt/huge sample: drop it, keep the stream moving.
                if (!ex.advance()) break
                continue
            }
            val bytes = ByteArray(n)
            buf.position(0)
            buf.get(bytes, 0, n)
            if (prevPayload != null) {
                val endSec = minOf(startSec, prevStartSec + MAX_CUE_LEN_SEC)
                addCue(cues, mime, prevPayload, prevStartSec, endSec)
            }
            prevStartSec = startSec
            prevPayload = bytes
            if (!ex.advance()) break
        }
        if (prevPayload != null) {
            addCue(cues, mime, prevPayload, prevStartSec, prevStartSec + DEFAULT_CUE_LEN_SEC)
        }
        cues.sortBy { it.start }
        return cues
    }

    private fun addCue(
        cues: ArrayList<SubtitleCue>,
        mime: String,
        payload: ByteArray,
        startSec: Double,
        endSec: Double,
    ) {
        if (startSec < 0 || endSec <= startSec) return
        val lines: List<String> = when {
            mime == MIME_SRT || mime.contains("subrip") ->
                plainLines(String(payload, Charsets.UTF_8))
            mime == MIME_VTT -> vttLines(String(payload, Charsets.UTF_8))
            mime == MIME_SSA || mime.contains("ssa") -> assLines(String(payload, Charsets.UTF_8))
            mime in MIME_TTML -> ttmlLines(String(payload, Charsets.UTF_8))
            mime in MIME_TX3G || mime.contains("tx3g") -> tx3gLines(payload)
            else -> plainLines(String(payload, Charsets.UTF_8))
        }
        if (lines.isNotEmpty()) cues.add(SubtitleCue(startSec, endSec, lines))
    }

    private fun plainLines(text: String): List<String> = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .split('\n')
        .map { stripInlineTags(it).trim() }
        .filter { it.isNotEmpty() }

    /** VTT payloads may carry a cue-identifier first line and settings. */
    private fun vttLines(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n')
        // A leading line without '-->' that isn't text: drop identifier-only
        // lines conservatively (identifier has no spaces and no tags).
        val parts = raw.split('\n').toMutableList()
        if (parts.size > 1) {
            val first = parts[0].trim()
            if (first.isNotEmpty() && !first.contains(' ') && !first.contains('<')) {
                parts.removeAt(0)
            }
        }
        return parts
            .map { stripInlineTags(it).trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * ASS/SSA sample inside Matroska: "ReadOrder,Layer,Style,Name,MarginL,
     * MarginR,MarginV,Effect,Text" — text is everything after the 8th comma
     * (commas inside the text are preserved). Some muxers keep the
     * "Dialogue:" prefix; tolerate both.
     */
    private fun assLines(payload: String): List<String> {
        var body = payload.trim()
        if (body.startsWith("Dialogue:")) body = body.removePrefix("Dialogue:")
        if (body.startsWith("Comment:")) return emptyList() // creator comments
        val parts = body.split(',')
        if (parts.size < 9) return emptyList()
        val text = parts.drop(8).joinToString(",")
            .replace(Regex("\\{[^}]*\\}"), "") // closed {override blocks}
            .replace(Regex("\\{[^}]*"), "")    // unclosed { to EOL
            .replace(Regex("\\}+"), "")        // stray }
            .replace(Regex("\\\\[Nn]"), "\n")
            .replace("\\h", " ")
        return text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * MP4/MOV tx3g sample (3GPP TS 26.245): uint16 BIG-endian text length,
     * then UTF-8 text, then optional style/box records — drop everything
     * after the text. Some muxers omit the length prefix; fall back to
     * treating the whole payload as text.
     */
    private fun tx3gLines(payload: ByteArray): List<String> {
        if (payload.size >= 2) {
            val len = ((payload[0].toInt() and 0xFF) shl 8) or
                (payload[1].toInt() and 0xFF)
            if (len in 1..(payload.size - 2)) {
                return plainLines(String(payload, 2, len, Charsets.UTF_8))
            }
        }
        return plainLines(String(payload, Charsets.UTF_8))
    }

    /** Minimal TTML: pull <p begin=".." end="..">text</p> bodies. */
    private fun ttmlLines(payload: String): List<String> {
        val p = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(payload)
            ?: return plainLines(payload)
        return plainLines(p.groupValues[1])
    }

    private fun stripInlineTags(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&nbsp;", " ")
}
