package dev.anonrode.player.core.media.subtitle

import android.util.Base64
import android.util.Xml
import dev.anonrode.player.core.media.log.AppLog
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * OpenSubtitles legacy XML-RPC client — the same zero-setup path MX-style
 * players use for in-player online subtitle search: anonymous guest login
 * (empty username/password → throwaway session token), hash-first
 * [SearchSubtitles], gzip+base64 [DownloadSubtitles]. No API key, no user
 * account; the trade-off is a small per-IP download quota, which is fine
 * for personal use.
 *
 * Deliberately dependency-free: requests are hand-built XML over
 * [HttpURLConnection], responses parsed with the platform XmlPullParser.
 * All calls are blocking — run on Dispatchers.IO.
 */
object OpenSubtitlesClient {

    private const val ENDPOINT = "https://api.opensubtitles.org/xml-rpc"
    private const val TAG = "OS_XMLRPC"
    private const val USER_AGENT = "anonrode-player 1.0"

    /** ISO 639-2 ids used when the caller doesn't specify languages. */
    const val DEFAULT_LANGS = "chi,eng"

    data class SearchResult(
        val idFile: String,
        val fileName: String,
        val downloadLink: String,
        val format: String,       // srt / ass / sub / vtt …
        val langName: String,     // "Chinese (Simplified)", "English" …
        val langId: String,       // ISO 639-2 ("chi", "eng")
        val downloads: String,
        val rating: String,
        /** "moviehash" = exact file-hash match; anything else is fuzzy. */
        val matchedBy: String,
    ) {
        val isExactHashMatch: Boolean get() = matchedBy.equals("moviehash", ignoreCase = true)
    }

    /**
     * Search subtitles for an exact file. Exact-hash matches are pinned to
     * the top, the rest ordered by download count (popularity proxy).
     * Returns an empty list on any network/protocol failure.
     */
    fun searchByHash(
        hash: String,
        sizeBytes: Long,
        langIds: String = DEFAULT_LANGS,
    ): List<SearchResult> {
        val token = login() ?: return emptyList()
        try {
            val query =
                "<param><value><array><data><value><struct>" +
                    member("sublanguageid", strValue(langIds)) +
                    member("moviehash", strValue(hash)) +
                    member("moviebytesize", strValue(sizeBytes.toString())) +
                    "</struct></value></data></array></param>"
            val resp = call("SearchSubtitles", strParam(token) + query) as? Map<*, *>
                ?: return emptyList()
            if (!statusOk(resp)) return emptyList()
            val rows = resp["data"] as? List<*> ?: return emptyList()
            return rows.mapNotNull { row ->
                val m = row as? Map<*, *> ?: return@mapNotNull null
                SearchResult(
                    idFile = s(m["IDSubtitleFile"]),
                    fileName = s(m["SubFileName"]),
                    downloadLink = s(m["SubDownloadLink"]),
                    format = s(m["SubFormat"]),
                    langName = s(m["LanguageName"]),
                    langId = s(m["ISO639"]),
                    downloads = s(m["SubDownloadsCnt"]),
                    rating = s(m["SubRating"]),
                    matchedBy = s(m["MatchedBy"]),
                )
            }.sortedWith(
                compareByDescending<SearchResult> { it.isExactHashMatch }
                    .thenByDescending { it.downloads.toLongOrNull() ?: 0L }
            )
        } catch (t: Throwable) {
            AppLog.e(TAG, "search failed", t)
            return emptyList()
        } finally {
            logout(token)
        }
    }

    /**
     * Download one subtitle file by IDSubtitleFile. Returns the DECODED,
     * DECOMPRESSED subtitle bytes (the API wraps gzip in base64), or null.
     */
    fun downloadSubtitle(idFile: String): ByteArray? {
        val token = login() ?: return null
        try {
            val ids =
                "<param><value><array><data>" +
                    "<value><string>${xmlEscape(idFile)}</string></value>" +
                    "</data></array></param>"
            val resp = call("DownloadSubtitles", strParam(token) + ids) as? Map<*, *>
                ?: return null
            if (!statusOk(resp)) return null
            val first = (resp["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
            val raw = when (val d = first["data"]) {
                is ByteArray -> d
                is String -> Base64.decode(d.trim(), Base64.DEFAULT)
                else -> return null
            }
            return gunzip(raw) ?: raw // a few uploads arrive uncompressed
        } catch (t: Throwable) {
            AppLog.e(TAG, "download failed", t)
            return null
        } finally {
            logout(token)
        }
    }

    // ── XML-RPC plumbing ──────────────────────────────────────────────

    private fun login(): String? = try {
        val resp = call(
            "LogIn",
            strParam("") + strParam("") + strParam("en") + strParam(USER_AGENT),
        ) as? Map<*, *> ?: return null
        if (!statusOk(resp)) {
            AppLog.e(TAG, "login refused: ${resp["status"]}")
            return null
        }
        resp["token"] as? String
    } catch (t: Throwable) {
        AppLog.e(TAG, "login failed", t)
        null
    }

    private fun logout(token: String) {
        try {
            call("LogOut", strParam(token))
        } catch (t: Throwable) {
            AppLog.e(TAG, "logout failed", t)
        }
    }

    private fun statusOk(resp: Map<*, *>): Boolean =
        (resp["status"] as? String).orEmpty().startsWith("200")

    private fun s(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        else -> v.toString()
    }

    /** POST one methodCall, parse the methodResponse into Kotlin values. */
    private fun call(method: String, paramsXml: String): Any? {
        val body =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<methodCall><methodName>$method</methodName>" +
                "<params>$paramsXml</params></methodCall>"
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream: InputStream = if (code in 200..299) conn.inputStream
                else conn.errorStream ?: throw java.io.IOException("HTTP $code, no body")
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return stream.use { parseMethodResponse(it) }
        } finally {
            conn.disconnect()
        }
    }

    // ── response parsing (XmlPullParser, recursive) ───────────────────

    private fun parseMethodResponse(input: InputStream): Any? {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input, null)
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (p.name) {
                    "value" -> return parseValue(p)
                    "fault" -> {
                        // <fault><value>…struct with faultString…</value></fault>
                        var e = p.next()
                        while (e != XmlPullParser.END_DOCUMENT &&
                            !(e == XmlPullParser.END_TAG && p.name == "fault")) {
                            if (e == XmlPullParser.START_TAG && p.name == "value") {
                                val f = parseValue(p)
                                AppLog.e(TAG, "XML-RPC fault: $f")
                                return null
                            }
                            e = p.next()
                        }
                        return null
                    }
                }
            }
            event = p.next()
        }
        return null
    }

    /** Parser must sit on <value>; returns positioned after </value>. */
    private fun parseValue(p: XmlPullParser): Any? {
        var typed: Any? = null
        var hasTyped = false
        val implicit = StringBuilder()
        var event = p.next()
        while (event != XmlPullParser.END_DOCUMENT &&
            !(event == XmlPullParser.END_TAG && p.name == "value")) {
            if (event == XmlPullParser.START_TAG) {
                val name = p.name
                typed = when (name) {
                    "string" -> readText(p, name)
                    "int", "i4" -> readText(p, name).trim().toLongOrNull() ?: 0L
                    "boolean" -> readText(p, name).trim() == "1"
                    "double" -> readText(p, name).trim().toDoubleOrNull() ?: 0.0
                    "base64" -> Base64.decode(readText(p, name).trim(), Base64.DEFAULT)
                    "dateTime.iso8601" -> readText(p, name)
                    "struct" -> parseStruct(p)
                    "array" -> parseArray(p)
                    else -> readText(p, name)
                }
                hasTyped = true
            } else if (event == XmlPullParser.TEXT) {
                implicit.append(p.text)
            }
            event = p.next()
        }
        return if (hasTyped) typed else implicit.toString().trim()
    }

    private fun parseStruct(p: XmlPullParser): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        var event = p.next()
        while (event != XmlPullParser.END_DOCUMENT &&
            !(event == XmlPullParser.END_TAG && p.name == "struct")) {
            if (event == XmlPullParser.START_TAG && p.name == "member") {
                var name = ""
                var value: Any? = null
                var e = p.next()
                while (e != XmlPullParser.END_DOCUMENT &&
                    !(e == XmlPullParser.END_TAG && p.name == "member")) {
                    if (e == XmlPullParser.START_TAG) {
                        when (p.name) {
                            "name" -> name = readText(p, "name").trim()
                            "value" -> value = parseValue(p)
                        }
                    }
                    e = p.next()
                }
                map[name] = value
            }
            event = p.next()
        }
        return map
    }

    private fun parseArray(p: XmlPullParser): List<Any?> {
        val list = ArrayList<Any?>()
        var event = p.next()
        while (event != XmlPullParser.END_DOCUMENT &&
            !(event == XmlPullParser.END_TAG && p.name == "array")) {
            if (event == XmlPullParser.START_TAG && p.name == "value") {
                list.add(parseValue(p))
            }
            event = p.next()
        }
        return list
    }

    /** Collect character data until </tag>; parser ends on </tag>. */
    private fun readText(p: XmlPullParser, tag: String): String {
        val sb = StringBuilder()
        var event = p.next()
        while (event != XmlPullParser.END_DOCUMENT &&
            !(event == XmlPullParser.END_TAG && p.name == tag)) {
            if (event == XmlPullParser.TEXT) sb.append(p.text)
            event = p.next()
        }
        return sb.toString()
    }

    // ── request building ──────────────────────────────────────────────

    private fun strParam(s: String) = "<param><value><string>${xmlEscape(s)}</string></value></param>"

    private fun strValue(s: String) = "<value><string>${xmlEscape(s)}</string></value>"

    private fun member(name: String, valueXml: String) =
        "<member><name>$name</name>$valueXml</member>"

    private fun xmlEscape(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun gunzip(data: ByteArray): ByteArray? = try {
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }
}
