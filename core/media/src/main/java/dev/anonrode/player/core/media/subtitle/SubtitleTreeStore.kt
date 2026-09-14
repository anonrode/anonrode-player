package dev.anonrode.player.core.media.subtitle

import android.content.Context
import dev.anonrode.player.core.media.log.AppLog
import java.io.File

/**
 * Persists the SAF document tree the user designates as their subtitles
 * folder (v0.8 P0-1). On API 30+ without MANAGE_EXTERNAL_STORAGE, .srt and
 * friends are invisible to BOTH a direct File listing and MediaStore — the
 * only remaining route to a sidecar is a persistable content:// tree grant,
 * which the user creates once from Settings ("Subtitles folder access").
 *
 * Stored as a plain file in filesDir (core.media has no SharedPreferences
 * precedent); the URI STRING is what survives process death — the actual
 * permission lives in the platform's persisted-URI table and is re-verified
 * by the resolver on every read.
 */
object SubtitleTreeStore {
    private const val FILE_NAME = "subtitle-tree.txt"

    /** The stored tree URI string, or null when the user never granted one. */
    fun get(context: Context): String? = try {
        File(context.filesDir, FILE_NAME)
            .takeIf { it.isFile }
            ?.readText()?.trim()
            ?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        AppLog.e("SUB_TREE", "read failed", t)
        null
    }

    /** Record (null/empty clears) a tree the caller already holds a
     *  persistable read permission for. */
    fun set(context: Context, treeUri: String?) {
        try {
            val f = File(context.filesDir, FILE_NAME)
            if (treeUri.isNullOrEmpty()) f.delete() else f.writeText(treeUri)
        } catch (t: Throwable) {
            AppLog.e("SUB_TREE", "write failed", t)
        }
    }
}
