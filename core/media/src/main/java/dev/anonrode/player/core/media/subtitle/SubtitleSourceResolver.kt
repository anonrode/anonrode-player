package dev.anonrode.player.core.media.subtitle

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.anonrode.player.core.media.log.AppLog
import dev.anonrode.player.core.model.SubtitleCue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * Resolves a video's active subtitle source to parsed cues. One code path
 * for every origin so PlayerActivity (playback) and SyncFingerprintJob
 * (auto-sync) can never disagree about what "the selected subtitle" is.
 *
 * Choice grammar (persisted in MediaStateEntity.subtitleChoice):
 *   ""                 → AUTO: see [resolveAutoCues] (embedded-fast-path /
 *                        best-sidecar fallback)
 *   "none"             → subtitles off
 *   "embedded:<index>" → in-container text track (MediaExtractor index)
 *   "sidecar:<name>"   → sibling file by display name
 *   "online:<name>"    → downloaded file in [SubtitleDownloadStore]
 *
 * MKV EMBEDDED FAST-PATH (v0.6.2 sub-sync UX pass): when no persisted
 * choice exists and the container exposes any text sub track, that
 * track is preferred over sidecars — MKV embedded wins by default,
 * sync is NOT scheduled against an embedded track unless the user
 * opts in explicitly (the SyncFingerprintJob's `opt-in` flag is the
 * entry-point that lives in the resolved cues path; see SyncFingerprint.
 * schedule in PlayerActivity for the gate). A persisted preference of
 * any kind (embedded:N, sidecar:name, online:name) always wins.
 *
 * Sidecar enumeration (Android 11+/13+ reality): non-media files such as
 * .srt are NOT visible through MediaStore.Files under READ_MEDIA_VIDEO,
 * so with all-files access granted (the app's permission gate asks for
 * it) the video's parent directory is listed directly on the filesystem —
 * fast, complete, permission-proof. The MediaStore.Files query remains as
 * the fallback for legacy storage / no all-files access. Neither path may
 * ever throw upward: a resolver failure simply means "no sidecars".
 */
object SubtitleSourceResolver {

    private const val TAG = "SUB_RESOLVER"

    /** Text subtitle extensions scanned in the video's folder. */
    val SUB_EXTS = listOf("srt", "vtt", "ass", "ssa", "sub", "tmp", "ttml")

    /** Sidecar files larger than this are not subtitle text — refuse to load. */
    private const val MAX_SIDECAR_BYTES = 32L * 1024 * 1024

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
            return resolveAutoCues(context, videoUri, videoPath)
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

    /**
     * AUTO pick for the empty-choice path. Two-stage:
     *   1. If the container exposes any text subtitle track, the first
     *      one wins (MKV embedded fast-path). The sync fingerprint job
     *      will refuse to schedule against this path unless the caller
     *      explicitly opts in — embedded timing is already container-
     *      aligned, so fingerprinting would just churn CPU.
     *   2. Otherwise: the canonical score-based sidecar pick
     *      ([pickAutoSidecar]).
     *
     * The "embedded preferred" rule applies ONLY when no persisted
     * preference exists; once the user picks anything — even an embedded
     * track index — that persists and the auto path is no longer used
     * until they reset it back to "".
     */
    fun resolveAutoCues(
        context: Context,
        videoUri: String,
        videoPath: String?,
    ): List<SubtitleCue> {
        val embedded = listEmbedded(context, videoUri, videoPath)
        if (embedded.isNotEmpty()) {
            AppLog.d(TAG, "auto embedded: track ${embedded.first().index}")
            return extractEmbedded(context, videoUri, videoPath, embedded.first().index)
        }
        val sidecar = videoPath?.let { pickAutoSidecar(context, it) } ?: return emptyList()
        AppLog.d(TAG, "auto sidecar: ${sidecar.name}")
        return parseSidecar(context, sidecar)
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
     * Subtitle files living in the same folder as the video.
     *
     * Strategy (fixes the Android 13+ "sidecars invisible" defect):
     *   1. All-files access granted (or legacy storage, API < 30): list the
     *      parent directory directly. This is the only complete source for
     *      non-media files on API 30+ — MediaStore.Files hides them under
     *      READ_MEDIA_VIDEO. A readable-but-empty directory is trusted
     *      (returns an empty list, no redundant MediaStore round-trip).
     *   2. Otherwise fall back to the MediaStore.Files query (legacy
     *      devices, or all-files access denied — best effort).
     * Never throws: any failure degrades to an empty list.
     */
    fun listSidecars(context: Context, videoPath: String): List<Sidecar> {
        val parentDir = videoPath.substringBeforeLast('/')
        if (canListDirectories()) {
            val direct = listSidecarsDirect(parentDir)
            if (direct != null) return direct
        }
        return listSidecarsMediaStore(context, parentDir)
    }

    /**
     * AUTO-mode pick — THE canonical score-based sidecar picker. Both
     * playback (resolveCues) and the sync fingerprint job route through
     * here, so they can never fit/render different files.
     *
     * Scoring (see [SubtitleMatcher.scoreSidecar]): exact stem (100) >
     * stem + language/edition tag (80) > episode-agreeing fuzzy token
     * overlap. Candidates whose season/episode numbers conflict with the
     * video are DISQUALIFIED, not merely penalized — in a multi-episode
     * folder episode 2's subtitle must never auto-load for episode 3.
     * With several candidates a positive score is required (no garbage
     * auto-pick); a lone sidecar is shown regardless of its name since
     * it is almost certainly meant for the video.
     */
    fun pickAutoSidecar(context: Context, videoPath: String): Sidecar? {
        val candidates = listSidecars(context, videoPath)
        if (candidates.isEmpty()) return null
        val videoName = videoPath.substringAfterLast('/')
        var best: Sidecar? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (c in candidates) {
            if (SubtitleMatcher.episodeConflict(videoName, c.name)) continue
            val s = SubtitleMatcher.scoreSidecar(videoName, c.name)
            if (best == null ||
                s > bestScore ||
                (s == bestScore && c.name.lowercase() < best.name.lowercase())
            ) {
                best = c
                bestScore = s
            }
        }
        if (best == null) return null
        if (bestScore <= 0.0 && candidates.size > 1) return null
        return best
    }

    // ── sidecar enumeration internals ─────────────────────────────────

    /**
     * Direct filesystem access is expected to work: legacy storage
     * (API < 30, READ_EXTERNAL_STORAGE gated in the manifest) or
     * MANAGE_EXTERNAL_STORAGE granted on API 30+. The probe is advisory —
     * a null from listFiles() still falls back to MediaStore.
     */
    private fun canListDirectories(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return try {
            Environment.isExternalStorageManager()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Direct directory scan. Returns null when the directory cannot be
     * listed (missing / no permission) so the caller can fall back; an
     * empty list means "listed fine, just no sidecars".
     */
    private fun listSidecarsDirect(parentDir: String): List<Sidecar>? = try {
        val files = File(parentDir).listFiles() ?: return null
        files
            .filter { f ->
                f.isFile && f.name.substringAfterLast('.', "").lowercase() in SUB_EXTS
            }
            .sortedBy { it.name.lowercase() }
            .map { Sidecar(Uri.fromFile(it), it.name) }
    } catch (t: Throwable) {
        AppLog.e(TAG, "direct sidecar scan failed", t)
        null
    }

    /**
     * MediaStore.Files fallback. On API 33+ with only READ_MEDIA_VIDEO this
     * returns nothing for non-media files — that is expected; the direct
     * scan above is the primary path there. Folder filter is pushed into
     * the query (DATA LIKE "<parentDir>/%") so we never pull device-wide
     * rows and filter in Kotlin.
     */
    private fun listSidecarsMediaStore(context: Context, parentDir: String): List<Sidecar> {
        val filesUri = MediaStore.Files.getContentUri("external")
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
        val out = ArrayList<Sidecar>()
        try {
            context.contentResolver.query(
                filesUri,
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.DATA,
                ),
                selection, arrayOf("$parentDir/%"), null,
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
            // SecurityException included: never surface to callers.
            AppLog.e(TAG, "mediastore sidecar scan failed", t)
        }
        return out.sortedBy { it.name.lowercase() }
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
        val bytes = readSidecarBytes(context, sidecar) ?: return emptyList()
        return try {
            SubtitleParser.parseBytes(sidecar.name, bytes)
        } catch (t: Throwable) {
            AppLog.e(TAG, "parse failed: ${sidecar.name}", t)
            emptyList()
        }
    }

    /**
     * file:// URIs (direct-scan results) are read straight off disk —
     * works with all-files access and skips ContentResolver overhead;
     * content:// rows from MediaStore go through the resolver. Reads are
     * capped so a mislabeled multi-GB file can't OOM the process.
     */
    private fun readSidecarBytes(context: Context, sidecar: Sidecar): ByteArray? {
        if (sidecar.uri.scheme == "file") {
            val path = sidecar.uri.path
            if (path != null) {
                try {
                    val f = File(path)
                    if (f.length() in 1..MAX_SIDECAR_BYTES) return f.readBytes()
                } catch (t: Throwable) {
                    AppLog.e(TAG, "direct read failed: ${sidecar.name}", t)
                }
            }
        }
        return try {
            context.contentResolver.openInputStream(sidecar.uri)?.use { readCapped(it) }
        } catch (t: Throwable) {
            AppLog.e(TAG, "read failed: ${sidecar.name}", t)
            null
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < MAX_SIDECAR_BYTES) {
            val want = minOf(buf.size.toLong(), MAX_SIDECAR_BYTES - total).toInt()
            val n = input.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun parseFile(file: File): List<SubtitleCue> = try {
        if (file.length() > MAX_SIDECAR_BYTES) return emptyList()
        SubtitleParser.parseBytes(file.name, file.readBytes())
    } catch (t: Throwable) {
        AppLog.e(TAG, "parse failed: ${file.name}", t)
        emptyList()
    }
}
