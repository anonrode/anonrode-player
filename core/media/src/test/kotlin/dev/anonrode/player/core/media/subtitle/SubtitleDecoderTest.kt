package dev.anonrode.player.core.media.subtitle

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for sidecar charset detection.
 *
 * The ladder used to return the FIRST candidate passing a "looks like CJK"
 * gate, with GB18030 first. GB18030's two-byte space contains the Shift_JIS
 * and Big5 spaces, so it decoded those without producing a single replacement
 * character, cleared the CJK threshold, and won — every time. Measured on
 * synthetic files, all four legacy charsets were detected as GB18030, so
 * Japanese, Traditional Chinese and Korean subtitles rendered as mojibake.
 *
 * Note the decoder only matters for legacy-encoded files: a strict UTF-8 pass
 * still short-circuits ahead of this ladder, and the local library's
 * subtitle files are all UTF-8. The bug defeats the feature's stated purpose
 * rather than the current content.
 */
class SubtitleDecoderTest {

    private fun encode(text: String, charset: String): ByteArray =
        text.toByteArray(Charset.forName(charset))

    private fun detect(text: String, charset: String): String =
        SubtitleDecoder.decodeWithCharset(encode(text, charset)).charset

    @Test
    fun `utf-8 still wins outright`() {
        val text = "これは日本語の字幕です。Hello world."
        assertEquals("UTF-8", detect(text, "UTF-8"))
        assertEquals("UTF-8", detect("plain ascii subtitle", "UTF-8"))
    }

    @Test
    fun `utf-8 bom is honoured`() {
        val bytes = "字幕テスト".toByteArray(Charsets.UTF_8)
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes
        assertEquals("UTF-8", SubtitleDecoder.decodeWithCharset(withBom).charset)
    }

    /** Japanese anime subtitles are the case GB18030 was swallowing. */
    @Test
    fun `shift-jis is detected as shift-jis not gb18030`() {
        val text = "これは日本語の字幕です。セリフのテストです。よろしくお願いします。" * 3
        assertEquals("Shift_JIS", detect(text, "Shift_JIS"))
        // and the text must come back intact, not mojibake
        assertEquals(text, SubtitleDecoder.decode(encode(text, "Shift_JIS")))
    }

    @Test
    fun `euc-kr is detected as euc-kr not gb18030`() {
        val text = "이것은 한국어 자막 테스트입니다. 오늘 날씨가 좋습니다." * 3
        assertEquals("EUC-KR", detect(text, "EUC-KR"))
        assertEquals(text, SubtitleDecoder.decode(encode(text, "EUC-KR")))
    }

    @Test
    fun `big5 is detected as big5 not gb18030`() {
        val text = "這是繁體中文字幕的測試內容。今天天氣很好。" * 3
        assertEquals("Big5", detect(text, "Big5"))
        assertEquals(text, SubtitleDecoder.decode(encode(text, "Big5")))
    }

    /**
     * GBK is a strict subset of GB18030, so this one may be *labelled*
     * GB18030 while still decoding to the correct characters. What matters is
     * that the text round-trips, which is what the user sees.
     */
    @Test
    fun `simplified chinese decodes to the right characters`() {
        val text = "这个是简体中文字幕的测试内容。我们一起去看电影吧。" * 3
        assertEquals(text, SubtitleDecoder.decode(encode(text, "GBK")))
        assertEquals(text, SubtitleDecoder.decode(encode(text, "GB18030")))
    }

    /**
     * Latin legacy files must not be dragged into a CJK charset. The text
     * needs real high bytes: pure ASCII is a subset of UTF-8, so the strict
     * UTF-8 pass legitimately claims it first and returns identical text.
     */
    @Test
    fun `latin text with accents is not claimed by a cjk charset`() {
        val text = "Le sous-titre doit rester lisible ici. Café à côté. " * 6
        val out = SubtitleDecoder.decodeWithCharset(encode(text, "ISO-8859-1"))
        assertTrue("got ${out.charset}", out.charset == "windows-1252" || out.charset == "ISO-8859-1")
        assertEquals(text, out.text)
    }

    @Test
    fun `empty input is handled`() {
        assertEquals("UTF-8", SubtitleDecoder.decodeWithCharset(ByteArray(0)).charset)
    }
}
