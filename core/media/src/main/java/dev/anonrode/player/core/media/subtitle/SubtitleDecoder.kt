package dev.anonrode.player.core.media.subtitle

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Character-set detection for sidecar subtitle files — the real-world gap
 * between "handles UTF-8" and full CJK coverage: Chinese releases routinely ship GBK /
 * Big5 / UTF-16 files, and a UTF-8-only reader shows mojibake or nothing.
 *
 * Detection ladder:
 *   1. BOM (UTF-8 / UTF-32 LE+BE / UTF-16 LE+BE) — authoritative.
 *   2. UTF-16 without BOM: null-byte parity sniff (common in Windows
 *      exports).
 *   3. Strict UTF-8 decode — clean pass means UTF-8 (ASCII included).
 *   4. Legacy CJK charsets in order GB18030 (GBK superset) → Big5 →
 *      EUC-KR → Shift_JIS: first one that yields a healthy amount of CJK
 *      ideographs with almost no replacement chars wins. GB18030-first
 *      matches the app's primary content (Simplified Chinese); Big5 vs
 *      GBK is inherently ambiguous without a language hint.
 *   5. windows-1252 catch-all for legacy Latin files (ISO-8859-1 if the
 *      platform lacks it). Single-byte → can never fail or crash.
 *
 * Every path produces text; nothing here throws on malformed input.
 * No dependencies: java.nio charsets only (Android ships all of these).
 */
object SubtitleDecoder {

    private const val SAMPLE_BYTES = 64 * 1024
    private val LEGACY_CJK = listOf("GB18030", "Big5", "EUC-KR", "Shift_JIS")

    /**
     * A decode is treated as that script when it is at least 5/3 (~60%) of the
     * non-space characters. Measured on real byte patterns, a correct decode
     * is 68-92% one script, while the nearest wrong decodes reach only 48%
     * hangul and 29% kana — so the margin is wide on both sides. See
     * legacyScore.
     */
    private const val SCRIPT_DOMINANCE_NUM = 5
    private const val SCRIPT_DOMINANCE_DEN = 3

    /** Decode result plus the charset that produced it (additive API —
     *  UI can show "loaded as GB18030" / offer a manual override). */
    data class Decoded(val text: String, val charset: String)

    fun decode(bytes: ByteArray, fileName: String = ""): String =
        decodeWithCharset(bytes, fileName).text

    /**
     * Detect + decode, reporting the charset used. Never throws: the
     * worst case is windows-1252/ISO-8859-1, which accepts every byte.
     */
    fun decodeWithCharset(bytes: ByteArray, fileName: String = ""): Decoded {
        if (bytes.isEmpty()) return Decoded("", "UTF-8")

        // 1. BOM — authoritative.
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8")
        if (bytes.size >= 4 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
        ) {
            // UTF-32LE BOM (FF FE 00 00) must be checked before UTF-16LE.
            charsetOrNull("UTF-32LE")?.let {
                return Decoded(String(bytes, 4, bytes.size - 4, it), "UTF-32LE")
            }
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
        ) {
            charsetOrNull("UTF-32BE")?.let {
                return Decoded(String(bytes, 4, bytes.size - 4, it), "UTF-32BE")
            }
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE")
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE")
        }

        // 2. BOM-less UTF-16: lots of zero bytes on one parity.
        val probe = minOf(bytes.size, 512)
        if (probe >= 16) {
            var zeroEven = 0
            var zeroOdd = 0
            var i = 0
            while (i + 1 < probe) {
                if (bytes[i] == 0.toByte()) zeroEven++
                if (bytes[i + 1] == 0.toByte()) zeroOdd++
                i += 2
            }
            val pairs = probe / 2
            if (zeroOdd * 4 > pairs && zeroOdd > zeroEven * 2) {
                return Decoded(String(bytes, Charsets.UTF_16LE), "UTF-16LE")
            }
            if (zeroEven * 4 > pairs && zeroEven > zeroOdd * 2) {
                return Decoded(String(bytes, Charsets.UTF_16BE), "UTF-16BE")
            }
        }

        // 3. Strict UTF-8 (malformed sequences rejected, not replaced).
        if (isValidUtf8(bytes)) return Decoded(String(bytes, Charsets.UTF_8), "UTF-8")

        // 4./5. Score every legacy charset and keep the best. This used to
        // return the FIRST candidate that passed a "looks like CJK" test, and
        // because GB18030 is a superset whose two-byte space contains the
        // Shift_JIS and Big5 spaces, it decoded those without error and won
        // every time — measured: 3 of 4 legacy charsets were misdetected as
        // GB18030, so Japanese, Traditional Chinese and Korean subtitle files
        // all rendered as mojibake. See legacyScore.
        val sample = if (bytes.size > SAMPLE_BYTES) bytes.copyOf(SAMPLE_BYTES) else bytes
        var bestScore = 0
        var best: Pair<String, Charset>? = null
        for (name in LEGACY_CJK) {
            val cs = charsetOrNull(name) ?: continue
            val text = try {
                String(sample, cs)
            } catch (t: Throwable) {
                continue
            }
            val score = legacyScore(text)
            // strict > keeps the ladder order as the tiebreak
            if (score > bestScore) {
                bestScore = score
                best = name to cs
            }
        }
        val chosen = best
        if (chosen != null) {
            return try {
                Decoded(String(bytes, chosen.second), chosen.first)
            } catch (t: Throwable) {
                Decoded(String(bytes, Charsets.UTF_8), "UTF-8")
            }
        }
        val latin = charsetOrNull("windows-1252")
        return if (latin != null) {
            Decoded(String(bytes, latin), "windows-1252")
        } else {
            Decoded(String(bytes, Charsets.ISO_8859_1), "ISO-8859-1")
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes))
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Ranks a candidate legacy decode. Higher is better; 0 means "not this".
     *
     * The old test was a pass/fail "does this look like CJK" gate, and the
     * ladder returned the FIRST candidate to pass. That cannot work: GB18030
     * accepts almost any byte sequence, so it passed first for Japanese,
     * Traditional Chinese and Korean files alike and the correct charset was
     * never tried. Measured on synthetic files, all four legacy charsets were
     * being decoded as GB18030.
     *
     * The discriminator is that a CORRECT decode is overwhelmingly one script
     * (or overwhelmingly hanzi) while a wrong decode scatters across scripts:
     *
     *     file       correct decode          wrong decode
     *     Shift_JIS  45/66 kana   (68%)      0 kana
     *     EUC-KR     69/75 hangul (92%)      0 hangul
     *     GB18030    0 script, 57 hanzi      30 hangul + 27 hanzi (mixed)
     *     Big5       0 script, 57 hanzi      18 kana + 24 hanzi (mixed)
     *
     * So a >=60% kana decode is Japanese and a >=60% hangul decode is Korean,
     * both decisively. Failing that, rank on hanzi count, which also rescues
     * Big5: a correct CJK decode yields more hanzi than a garbled one.
     *
     * 60% is the measured gap — the nearest wrong cases sit at 48% hangul and
     * 29% kana, so the threshold has margin either side, and real text is far
     * more lopsided than these worst cases.
     */
    private fun legacyScore(text: String): Int {
        var kana = 0
        var hangul = 0
        var hanzi = 0
        var replacement = 0
        var total = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            total++
            val c = ch.toInt()
            when {
                ch == '\uFFFD' -> replacement++
                c in 0x3040..0x30FF -> kana++
                c in 0xAC00..0xD7AF -> hangul++
                c in 0x4E00..0x9FFF -> hanzi++ // CJK unified ideographs
                c in 0x3400..0x4DBF -> hanzi++ // extension A
                c in 0xF900..0xFAFF -> hanzi++ // compatibility ideographs
            }
        }
        if (total == 0) return 0
        // a decode that produced this many replacement chars is simply wrong
        if (replacement * 50 >= total) return 0
        if (kana * SCRIPT_DOMINANCE_NUM >= total * SCRIPT_DOMINANCE_DEN) return 1000 + kana
        if (hangul * SCRIPT_DOMINANCE_NUM >= total * SCRIPT_DOMINANCE_DEN) return 1000 + hangul
        if (hanzi >= 8 && hanzi * 10 >= total) return 100 + hanzi
        return 0
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (t: Throwable) {
        null
    }
}
