package dev.anonrode.player.core.media.subtitle

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.SubtitleCue
import java.io.File

/**
 * Resolves a video's active subtitle source to parsed cues. One code path
 * for every origin so PlayerActivity (playback) and SyncFingerprintJob
 * (auto-sync) can never disagree about what "the selected subtitle" is.
 *
 * Choice grammar (persisted in MediaStateEntity.subtitleChoice):
 *   ""                 → AUTO: best sidecar file next to the video
 *   "none"             → subtitles off
 *   "embedded:<index>" → in-container text track (MediaExtractor index)
 *   "sidecar:<name>"   → sibling file by display name
 *   "online:<name>"    → downloaded file in [SubtitleDownloadStore]
 */
object SubtitleSourceResolver {

    private const val TAG = "SUB_RESOLVER"

    /** Text subtitle extensions scanned in the video's folder. */
    val SUB_EXTS = listOf("srt", "vtt", "ass", "ssa", "sub", "tmp", "ttml")

    data class Sidecar(val uri: Uri, val name: String)

    /** Parsed cues for the persisted choice; empty list = show nothing. */
    fun resolveCues(
        context: Context,
        videoUri: String,
        videoPath: String?,
        choice: String,
    ): List<SubtitleCue> {
        if (choice == "none") return emptyList()
        if (choice.isEmpty()) {
            val sidecar = videoPath?.let { pickAutoSidecar(context, it) } ?: return emptyList()
            AppLog.d(TAG, "auto sidecar: ${sidecar.name}")
            return parseSidecar(context, sidecar)
        }
        val kind = choice.substringBefore(':')
        val value = choice.substringAfter(':', "")
        return when (kind) {
            "embedded" -> {
                val idx = value.toIntOrNull() ?: return emptyList()
                extractEmbedded(context, videoUri, videoPath, idx)
            }
            "sidecar" -> {
                val path = videoPath ?: return emptyList()
                val sidecar = listSidecars(context, path).firstOrNull { it.name == value }
                if (sidecar == null) {
                    AppLog.d(TAG, "sidecar choice gone: $value")
                    emptyList()
                } else parseSidecar(context, sidecar)
            }
            "online" -> {
                val file = SubtitleDownloadStore(context).list(videoUri)
                    .firstOrNull { it.name == value }
                if (file == null) {
                    AppLog.d(TAG, "online choice gone: $value")
                    emptyList()
                } else parseFile(file)
            }
            else -> emptyList()
        }
    }

    // ── enumeration (picker feeds) ────────────────────────────────────

    /** Embedded text tracks in the container (empty when unresolvable). */
    fun listEmbedded(
        context: Context,
        videoUri: String,
        videoPath: String?,
    ): List<EmbeddedSubtitleExtractor.Track> {
        if (videoPath != null && File(videoPath).isFile()) {
            val tracks = EmbeddedSubtitleExtractor.listTracks(videoPath)
            if (tracks.isNotEmpty()) return tracks
        }
        val fd = try {
            context.contentResolver.openFileDescriptor(Uri.parse(videoUri), "r")
        } catch (t: Throwable) {
            null
        }
        return fd?.use { EmbeddedSubtitleExtractor.listTracks(it.fileDescriptor) } ?: emptyList()
    }

    /**
     * Subtitle files living in the same folder as the video. MediaStore.Files
     * first (fast, no storage permission beyond media on old Android); on
     * Android 11+ non-media files like .srt are invisible there, so a
     * direct directory scan is merged in as fallback (works once the user
     * grants all-files access).
     */
    fun listSidecars(context: Context, videoPath: String): List<Sidecar> {
        val parentDir = videoPath.substringBeforeLast('/')
        val filesUri = MediaStore.Files.getContentUri("external")
        // Folder filter pushed into the query (DATA LIKE "<parentDir>/%") so
        // we don't pull every subtitle row device-wide and filter in Kotlin;
        // the LIKE-per-extension OR group stays parenthesized inside it.
        val selection = buildString {
            append("(")
            SUB_EXTS.forEachIndexed { i, ext ->
                if (i > 0) append(" OR ")
                append(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    .append(" LIKE '%.").append(ext).append("'")
            }
            append(") AND (")
            append(MediaStore.Files.FileColumns.DATA)
            append(" LIKE ?)")
        }
        val selectionArgs = arrayOf("$parentDir/%")
        val out = ArrayList<Sidecar>()
        try {
            context.contentResolver.query(
                filesUri,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATA,
                ),
                selection, selectionArgs, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in SUB_EXTS) continue
                    val path = c.getString(dataCol) ?: continue
                    if (path.substringBeforeLast('/') != parentDir) continue
                    out.add(Sidecar(ContentUris.withAppendedId(filesUri, c.getLong(idCol)), name))
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "sidecar scan failed", t)
        }
        // Direct scan fallback/merge: the only way to see .srt on API 30+.
        try {
            val dir = File(parentDir)
            if (dir.isDirectory) {
                val known = out.mapTo(HashSet()) { it.name }
                dir.listFiles()?.forEach { f ->
                    if (!f.isFile) return@forEach
                    val ext = f.name.substringAfterLast('.', "").lowercase()
                    if (ext !in SUB_EXTS || f.name in known) return@forEach
                    out.add(Sidecar(Uri.fromFile(f), f.name))
                }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "direct sidecar scan failed", t)
        }
        return out
    }

    /**
     * AUTO-mode pick: exact base-name match first, then prefix match,
     * then whatever exists (mirrors the original PlayerActivity logic).
     */
    fun pickAutoSidecar(context: Context, videoPath: String): Sidecar? {
        val candidates = listSidecars(context, videoPath)
        if (candidates.isEmpty()) return null
        val base = videoPath.substringAfterLast('/').substringBeforeLast('.')
        return candidates.firstOrNull {
            it.name.substringBeforeLast('.').equals(base, ignoreCase = true)
        } ?: candidates.firstOrNull {
            it.name.substringBeforeLast('.').startsWith(base, ignoreCase = true)
        } ?: candidates.first()
    }

    // ── internals ─────────────────────────────────────────────────────

    private fun extractEmbedded(
        context: Context,
        videoUri: String,
        videoPath: String?,
        index: Int,
    ): List<SubtitleCue> {
        if (videoPath != null && File(videoPath).isFile()) {
            val cues = EmbeddedSubtitleExtractor.extractCues(videoPath, index)
            if (cues.isNotEmpty()) return cues
        }
        val fd = try {
            context.contentResolver.openFileDescriptor(Uri.parse(videoUri), "r")
        } catch (t: Throwable) {
            null
        }
        return fd?.use { EmbeddedSubtitleExtractor.extractCues(it.fileDescriptor, index) }
            ?: emptyList()
    }

    private fun parseSidecar(context: Context, sidecar: Sidecar): List<SubtitleCue> {
        val bytes = try {
            context.contentResolver.openInputStream(sidecar.uri)?.use { it.readBytes() }
        } catch (t: Throwable) {
            AppLog.e(TAG, "read failed: ${sidecar.name}", t)
            null
        } ?: return emptyList()
        return SubtitleParser.parseBytes(sidecar.name, bytes)
    }

    private fun parseFile(file: File): List<SubtitleCue> = try {
        SubtitleParser.parseBytes(file.name, file.readBytes())
    } catch (t: Throwable) {
        AppLog.e(TAG, "parse failed: ${file.name}", t)
        emptyList()
    }
}
