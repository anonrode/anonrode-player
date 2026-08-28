package dev.anonrode.player.core.media.subtitle

import android.content.Context
import dev.anonrode.player.core.media.log.AppLog
import java.io.File
import java.io.IOException

/**
 * App-private storage for subtitles downloaded from online providers.
 * Files live under filesDir/online_subs/, grouped by an MD5 key of the
 * video's content URI (URIs contain characters that are illegal in file
 * names, and the same video must resolve to the same folder across
 * sessions). Nothing here is visible to other apps or the gallery — the
 * user's subtitle downloads are theirs alone.
 *
 * Writes are crash-safe: bytes go to a `.tmp` sibling first and are then
 * atomically renamed onto the target, so a kill mid-download can never
 * leave a half-written file that later parses as garbage (or truncates a
 * previously good download).
 */
class SubtitleDownloadStore(context: Context) {

    companion object {
        private const val TAG = "SUB_STORE"
        private const val TMP_SUFFIX = ".tmp"

        /** prune() keeps at most this many downloads per video. */
        const val DEFAULT_MAX_FILES_PER_VIDEO = 20
    }

    private val root = File(context.filesDir, "online_subs")

    /**
     * Persist downloaded subtitle bytes. [provider] + [id] identify the
     * source (e.g. "os" + IDSubtitleFile) so re-downloading the same file
     * overwrites instead of duplicating. Returns the stored file.
     *
     * Never throws: on IO failure the returned file is either the
     * previously stored version or an empty placeholder, so callers that
     * read + parse it degrade to "not a readable subtitle" instead of
     * crashing.
     */
    fun save(
        videoUri: String,
        provider: String,
        id: String,
        fileName: String,
        bytes: ByteArray,
    ): File {
        val dir = File(root, key(videoUri))
        val ext = fileName.substringAfterLast('.', "srt").lowercase()
            .take(4).filter { it.isLetterOrDigit() }.ifEmpty { "srt" }
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "unknown" }
        val target = File(dir, "${provider}_${safeId}.$ext")
        try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("mkdirs failed: $dir")
            }
            val tmp = File(dir, target.name + TMP_SUFFIX)
            tmp.outputStream().use { it.write(bytes) }
            if (!tmp.renameTo(target)) {
                // renameTo can fail defensively (same-dir renames are
                // atomic on ext4/f2fs, but be safe): copy + delete.
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "save failed: ${target.name}", t)
            // Keep an older good version if present; otherwise leave an
            // empty placeholder so downstream read+parse fails graceful.
            if (!target.isFile) {
                try {
                    target.createNewFile()
                } catch (ignored: Throwable) {
                }
            }
        }
        return target
    }

    /** All stored subtitle files for this video, newest first. */
    fun list(videoUri: String): List<File> {
        val dir = File(root, key(videoUri))
        val files = dir.listFiles { f ->
            f.isFile && !f.name.endsWith(TMP_SUFFIX)
        } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    /** Delete one stored subtitle by display name. Best effort. */
    fun delete(videoUri: String, fileName: String): Boolean = try {
        val f = File(File(root, key(videoUri)), fileName)
        f.isFile && f.delete()
    } catch (t: Throwable) {
        AppLog.e(TAG, "delete failed: $fileName", t)
        false
    }

    /** Remove every stored subtitle for this video. Best effort. */
    fun clear(videoUri: String) {
        try {
            File(root, key(videoUri)).listFiles()?.forEach { f ->
                if (!f.delete()) f.deleteOnExit()
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "clear failed", t)
        }
    }

    /**
     * Housekeeping: keep at most [maxFilesPerVideo] newest downloads per
     * video folder and sweep stray temp files from interrupted writes.
     * Safe to run on any schedule; never throws, never touches files it
     * doesn't own.
     */
    fun prune(maxFilesPerVideo: Int = DEFAULT_MAX_FILES_PER_VIDEO) {
        try {
            val dirs = root.listFiles() ?: return
            for (dir in dirs) {
                if (!dir.isDirectory) continue
                val files = dir.listFiles() ?: continue
                val temps = files.filter { it.name.endsWith(TMP_SUFFIX) }
                temps.forEach { it.delete() }
                val kept = files
                    .filter { it.isFile && !it.name.endsWith(TMP_SUFFIX) }
                    .sortedByDescending { it.lastModified() }
                kept.drop(maxFilesPerVideo).forEach { it.delete() }
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "prune failed", t)
        }
    }

    private fun key(videoUri: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(videoUri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
