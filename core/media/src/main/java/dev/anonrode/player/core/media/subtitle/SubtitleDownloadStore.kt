package dev.anonrode.player.core.media.subtitle

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * App-private storage for subtitles downloaded from online providers.
 * Files live under filesDir/online_subs/, grouped by an MD5 key of the
 * video's content URI (URIs contain characters that are illegal in file
 * names, and the same video must resolve to the same folder across
 * sessions). Nothing here is visible to other apps or the gallery — the
 * user's subtitle downloads are theirs alone.
 */
class SubtitleDownloadStore(context: Context) {

    private val root = File(context.filesDir, "online_subs")

    /**
     * Persist downloaded subtitle bytes. [provider] + [id] identify the
     * source (e.g. "os" + IDSubtitleFile) so re-downloading the same file
     * overwrites instead of duplicating. Returns the stored file.
     */
    fun save(
        videoUri: String,
        provider: String,
        id: String,
        fileName: String,
        bytes: ByteArray,
    ): File {
        val dir = File(root, key(videoUri)).apply { mkdirs() }
        val ext = fileName.substringAfterLast('.', "srt").lowercase().take(4)
        val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val file = File(dir, "${provider}_${safeId}.$ext")
        file.writeBytes(bytes)
        return file
    }

    /** All stored subtitle files for this video, newest first. */
    fun list(videoUri: String): List<File> {
        val dir = File(root, key(videoUri))
        val files = dir.listFiles { f -> f.isFile } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }
    }

    private fun key(videoUri: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(videoUri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
