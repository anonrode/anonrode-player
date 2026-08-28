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
                    Decoded(String(bytes, cs), name)
                } catch (t: Throwable) {
                    Decoded(String(bytes, Charsets.UTF_8), "UTF-8")
                }
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
