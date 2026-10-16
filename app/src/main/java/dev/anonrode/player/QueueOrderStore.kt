package dev.anonrode.player

import android.content.Context

/**
 * Per-folder custom episode order — the playlist drawer's drag-reorder.
 *
 * Keyed by the folder's parentPath; the value is the ordered uri list joined
 * with NUL, a byte that can never appear in a content uri. Deliberately not
 * a Room entity: this is presentation order for one folder, tiny, and has no
 * relational needs beyond "give it back exactly as written".
 */
object QueueOrderStore {

    private const val PREFS = "queue_order"
    private const val SEP = '\u0000'

    /** The stored order for [folderPath], or null when the folder was never
     *  customised (callers keep their natural episode ordering). */
    fun get(context: Context, folderPath: String): List<String>? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(folderPath, null)
            ?.split(SEP)
            ?.filter { it.isNotEmpty() }

    /** Persist the user's order for [folderPath]. */
    fun set(context: Context, folderPath: String, uris: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(folderPath, uris.joinToString(SEP.toString()))
            .apply()
    }
}
