package dev.anonrode.player.core.media.subtitle

import android.util.Base64
import android.util.Xml
import dev.anonrode.player.core.media.log.AppLog
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * OpenSubtitles legacy XML-RPC client — EXPERIMENTAL.
 *
 * The XML-RPC API (api.opensubtitles.org/xml-rpc) has been deprecated by
 * OpenSubtitles in favor of the keyed REST API (api.opensubtitles.com),
 * and anonymous guest logins — the zero-setup path this client relies
 * on — are being retired. There is no REST API key available for this
 * app, so this feature is kept as a best-effort extra:
 *
 *   - Every path fails FAST and gracefully: short timeouts, clean empty
 *     results, and no exception ever escapes to the UI.
 *   - Once the endpoint definitively refuses the session (HTTP
 *     401/403/404/410 or a login fault), the client LATCHES: all later
 *     calls short-circuit locally with the recorded [serviceNotice]
 *     instead of attempting further network round-trips.
 *   - UI should treat this feature as experimental (see [EXPERIMENTAL])
 *     and surface [serviceNotice] when a search comes back empty.
 *
 * Deliberately dependency-free: requests are hand-built XML over
 * [HttpURLConnection], responses parsed with the platform XmlPullParser.
 * All calls are blocking — run on Dispatchers.IO.
 */
object OpenSubtitlesClient {

    private const val ENDPOINT = "https://api.opensubtitles.org/xml-rpc"
    private const val TAG = "OS_XMLRPC"
    private const val USER_AGENT = "anonrode-player 1.0"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000

    /** ISO 639-2 ids used when the caller doesn't specify languages. */
    const val DEFAULT_LANGS = "chi,eng"

    /**
     * Online search runs against OpenSubtitles' deprecated XML-RPC API
     * (the REST replacement needs an API key this app doesn't have).
     * UI should label the feature experimental.
     */
    const val EXPERIMENTAL = true

    /** Latched once the legacy endpoint definitively refuses this session. */
    @Volatile
    private var endpointRefused = false

    /** Human-readable explanation of the last failure / refusal. */
    @Volatile
    private var notice: String? = null

    /** Fault string from the most recent XML-RPC response (internal). */
    @Volatile
    private var lastFault: String? = null

    /**
     * Status message for the UI: why a search/download came back empty.
     * Null means "no message" (feature not yet exercised or working).
     */
    fun serviceNotice(): String? = notice

    /** True once the endpoint refused the session — calls now fail fast. */
    fun isUnavailable(): Boolean = endpointRefused

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
     * Returns an empty list on any network/protocol failure — never throws.
     */
    fun searchByHash(
        hash: String,
        sizeBytes: Long,
        langIds: String = DEFAULT_LANGS,
    ): List<SearchResult> {
        if (endpointRefused) return emptyList()
        val token = login() ?: return emptyList()
        try {
            val query =
                "<param><value><array><data><value><struct>" +
                    member("sublanguageid", strValue(langIds)) +
                    member("moviehash", strValue(hash)) +
                    member("moviebytesize", strValue(sizeBytes.toString())) +
                    "</struct></value></data></array></param>"
            val resp = call("SearchSubtitles", strParam(token) + query) as? Map<*, *>
            if (resp == null) {
                lastFault?.let { notice = "OpenSubtitles returned an error: $it" }
                return emptyList()
            }
            if (!statusOk(resp)) {
                noteBadStatus(s(resp["status"]))
                return emptyList()
            }
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
            recordFailure(t)
            return emptyList()
        } finally {
            logout(token)
        }
    }

    /**
     * Download one subtitle file by IDSubtitleFile. Returns the DECODED,
     * DECOMPRESSED subtitle bytes (the API wraps gzip in base64), or null
     * on any failure — never throws.
     */
    fun downloadSubtitle(idFile: String): ByteArray? {
        if (endpointRefused) return null
        val token = login() ?: return null
        try {
            val ids =
                "<param><value><array><data>" +
                    "<value><string>${xmlEscape(idFile)}</string></value>" +
                    "</data></array></param>"
            val resp = call("DownloadSubtitles", strParam(token) + ids) as? Map<*, *>
            if (resp == null) {
                lastFault?.let { notice = "OpenSubtitles returned an error: $it" }
                return null
            }
            if (!statusOk(resp)) {
                noteBadStatus(s(resp["status"]))
                return null
            }
            val first = (resp["data"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
            val raw = when (val d = first["data"]) {
                is ByteArray -> d
                is String -> Base64.decode(d.trim(), Base64.DEFAULT)
                else -> return null
            }
            return gunzip(raw) ?: raw // a few uploads arrive uncompressed
        } catch (t: Throwable) {
            AppLog.e(TAG, "download failed", t)
            recordFailure(t)
            return null
        } finally {
            logout(token)
        }
    }

    // ── refusal / notice bookkeeping ──────────────────────────────────

    private const val RETIRED_MSG =
        "OpenSubtitles' legacy XML-RPC API is deprecated and no longer " +
            "accepts this request; the replacement REST API requires an " +
            "API key. Online subtitle search is experimental and " +
            "currently unavailable."

    /** Latch when the refusal is final (server policy, not a hiccup). */
    private fun refuseIfFinal(reason: String) {
        endpointRefused = true
        notice = "$RETIRED_MSG ($reason)"
        AppLog.e(TAG, "endpoint latched unavailable: $reason")
    }

    /**
     * XML-RPC "status" handling: auth/gone codes are final (the legacy
     * endpoint is retired); anything else (5xx, quota) is transient and
     * must not latch the client.
     */
    private fun noteBadStatus(status: String) {
        if (status.startsWith("401") || status.startsWith("403") ||
            status.startsWith("404") || status.startsWith("410")
        ) {
            refuseIfFinal("status $status")
        } else {
            notice = "OpenSubtitles returned status: $status"
        }
    }

    private fun recordFailure(t: Throwable) {
        // A latched (final) refusal keeps its authoritative message.
        if (endpointRefused) return
        if (t is IOException) {
            // Network-level: possibly transient, don't latch.
            notice = "Network error while contacting OpenSubtitles: " +
                (t.message ?: t.javaClass.simpleName)
        } else {
            notice = "OpenSubtitles request failed: " +
                (t.message ?: t.javaClass.simpleName)
        }
    }

    // ── XML-RPC plumbing ──────────────────────────────────────────────

    private fun login(): String? {
        if (endpointRefused) return null
        return try {
            lastFault = null
            val resp = call(
                "LogIn",
                strParam("") + strParam("") + strParam("en") + strParam(USER_AGENT),
            ) as? Map<*, *>
            if (resp == null) {
                // XML-RPC fault or unparseable response. Guest-login
                // refusal is server policy — latch and fail fast from
                // now on instead of re-probing on every tap.
                val fault = lastFault
                if (fault != null) {
                    refuseIfFinal("guest login fault: $fault")
                } else {
                    notice = "OpenSubtitles did not answer the login request."
                }
                return null
            }
            if (!statusOk(resp)) {
                noteBadStatus(s(resp["status"]))
                return null
            }
            resp["token"] as? String
        } catch (t: Throwable) {
            AppLog.e(TAG, "login failed", t)
            recordFailure(t)
            null
        }
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
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 401 || code == 403 || code == 404 || code == 410) {
                // Final: the legacy endpoint is gone / rejecting us.
                refuseIfFinal("HTTP $code")
                throw IOException("HTTP $code")
            }
            val stream: InputStream = if (code in 200..299) conn.inputStream
                else conn.errorStream ?: throw IOException("HTTP $code, no body")
            if (code !in 200..299) throw IOException("HTTP $code")
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
                                lastFault = faultText(f)
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

    private fun faultText(fault: Any?): String = when (fault) {
        is Map<*, *> -> s(fault["faultString"]).ifEmpty { fault.toString() }
        else -> fault.toString()
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
