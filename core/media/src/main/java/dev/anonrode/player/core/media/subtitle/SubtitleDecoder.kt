package dev.anonrode.player.core.media.subtitle

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Character-set detection for sidecar subtitle files — the real-world gap
 * between "supports SRT" and VLC: Chinese releases routinely ship GBK /
 * Big5 / UTF-16 files, and a UTF-8-only reader shows mojibake or nothing.
 *
 * Detection ladder:
 *   1. BOM (UTF-8 / UTF-16 LE / UTF-16 BE) — authoritative.
 *   2. UTF-16 without BOM: null-byte parity sniff (common in Windows
 *      exports).
 *   3. Strict UTF-8 decode — clean pass means UTF-8 (ASCII included).
 *   4. Legacy CJK charsets in order GB18030 (GBK superset) → Big5 →
 *      EUC-KR → Shift_JIS: first one that yields a healthy amount of CJK
 *      ideographs with almost no replacement chars wins. GB18030-first
 *      matches the app's primary content (Simplified Chinese); Big5 vs
 *      GBK is inherently ambiguous without a language hint.
 *   5. windows-1252 catch-all for legacy Latin files.
 *
 * No dependencies: java.nio charsets only (Android ships all of these).
 */
object SubtitleDecoder {

    private const val SAMPLE_BYTES = 64 * 1024
    private val LEGACY_CJK = listOf("GB18030", "Big5", "EUC-KR", "Shift_JIS")

    fun decode(bytes: ByteArray, fileName: String = ""): String {
        if (bytes.isEmpty()) return ""

        // 1. BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
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
                return String(bytes, Charsets.UTF_16LE)
            }
            if (zeroEven * 4 > pairs && zeroEven > zeroOdd * 2) {
                return String(bytes, Charsets.UTF_16BE)
            }
        }

        // 3. Strict UTF-8
        if (isValidUtf8(bytes)) return String(bytes, Charsets.UTF_8)

        // 4./5. Score legacy charsets on a bounded sample.
        val sample = if (bytes.size > SAMPLE_BYTES) bytes.copyOf(SAMPLE_BYTES) else bytes
        for (name in LEGACY_CJK) {
            val cs = charsetOrNull(name) ?: continue
            val text = try {
                String(sample, cs)
            } catch (t: Throwable) {
                continue
            }
            if (looksLikeIntended(text)) {
                return try {
                    String(bytes, cs)
                } catch (t: Throwable) {
                    String(bytes, Charsets.UTF_8)
                }
            }
        }
        return String(bytes, charsetOrNull("windows-1252") ?: Charsets.ISO_8859_1)
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
     * A charset is "the intended one" when decoding produces a solid count
     * of CJK ideographs/kana/hangul and very few replacement chars. Pure
     * Latin text decoded through GB18030 yields only a handful of accidental
     * hanzi, so the threshold rejects it.
     */
    private fun looksLikeIntended(text: String): Boolean {
        var cjk = 0
        var replacement = 0
        var total = 0
        for (ch in text) {
            if (ch.isWhitespace()) continue
            total++
            when {
                ch == '\uFFFD' -> replacement++
                ch in '\u4E00'..'\u9FFF' -> cjk++ // CJK unified ideographs
                ch in '\u3400'..'\u4DBF' -> cjk++ // extension A
                ch in '\uF900'..'\uFAFF' -> cjk++ // compatibility ideographs
                ch in '\u3040'..'\u30FF' -> cjk++ // kana
                ch in '\uAC00'..'\uD7AF' -> cjk++ // hangul syllables
            }
        }
        if (total == 0) return false
        return cjk >= 8 && cjk * 10 >= total && replacement * 50 < total
    }

    private fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (t: Throwable) {
        null
    }
}
