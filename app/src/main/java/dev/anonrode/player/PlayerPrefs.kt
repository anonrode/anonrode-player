package dev.anonrode.player

import android.content.Context
import android.content.SharedPreferences

/**
 * Small key-value store for player preferences that carry per-video keys:
 * subtitle drag position ("sub_pos_<mediaId>" with a "sub_pos_default"
 * fallback) and the global playback speed ("play_speed"). Lives in the app
 * module so core/ stays untouched; values are plain strings so no new
 * dependencies or schema migrations are needed.
 *
 * Key contract (read by the player): "sub_pos_<mediaId>", "sub_pos_default"
 * and "play_speed" — formats are stable. The extra "sub_pos_mru" key is an
 * internal most-recently-used index that bounds memory: per-video keys are
 * pruned beyond [MAX_SUB_POS_ENTRIES], evicting the least recently saved
 * videos first (their positions silently fall back to the default).
 */
object PlayerPrefs {

    private const val FILE = "player_prefs"
    private const val KEY_SUB_POS_DEFAULT = "sub_pos_default"
    private const val KEY_PLAY_SPEED = "play_speed"
    private const val KEY_SUB_POS_MRU = "sub_pos_mru"
    private const val SUB_POS_PREFIX = "sub_pos_"
    private const val MAX_SUB_POS_ENTRIES = 256

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Per-video key: "sub_pos_<mediaId>", e.g. sub_pos_/storage/.../ep01.mp4 */
    private fun subPosKey(mediaId: String) = "$SUB_POS_PREFIX$mediaId"

    /** Saved subtitle center as stage fractions (x,y); null when unset. */
    fun subtitlePosition(context: Context, mediaId: String): Pair<Float, Float>? =
        prefs(context).getString(subPosKey(mediaId), null)?.let(::parse)
            ?: prefs(context).getString(KEY_SUB_POS_DEFAULT, null)?.let(::parse)

    /** Persist the drag result for this video and as the new global default. */
    fun saveSubtitlePosition(context: Context, mediaId: String, x: Float, y: Float) {
        val value = "$x,$y"
        val p = prefs(context)
        val editor = p.edit()
            .putString(subPosKey(mediaId), value)
            .putString(KEY_SUB_POS_DEFAULT, value)
        pruneMru(p, editor, mediaId)
        editor.apply()
    }

    /**
     * Maintain the MRU index of per-video keys: move [mediaId] to the front
     * and delete the stored keys of any videos evicted past the cap. Runs
     * inside the same editor as the save, so the index can't drift from the
     * data. Media ids are content URIs (no newlines), so '\n' is a safe
     * separator.
     */
    private fun pruneMru(p: SharedPreferences, editor: SharedPreferences.Editor, mediaId: String) {
        val mru = ArrayList<String>(MAX_SUB_POS_ENTRIES + 1)
        mru.add(mediaId)
        p.getString(KEY_SUB_POS_MRU, null)?.split('\n')?.forEach { id ->
            if (id.isNotEmpty() && id != mediaId && mru.size < MAX_SUB_POS_ENTRIES + 1) {
                mru.add(id)
            }
        }
        // Anything past the cap loses its stored position (falls back to
        // the global default on next read).
        if (mru.size > MAX_SUB_POS_ENTRIES) {
            mru.subList(MAX_SUB_POS_ENTRIES, mru.size).forEach { id ->
                editor.remove(subPosKey(id))
            }
        }
        val kept = if (mru.size > MAX_SUB_POS_ENTRIES) {
            mru.subList(0, MAX_SUB_POS_ENTRIES)
        } else {
            mru
        }
        editor.putString(KEY_SUB_POS_MRU, kept.joinToString("\n"))
    }

    /** Last globally chosen playback speed; null when never set or invalid. */
    fun globalSpeed(context: Context): Float? =
        prefs(context).getString(KEY_PLAY_SPEED, null)
            ?.toFloatOrNull()?.takeIf { it > 0f }

    fun saveGlobalSpeed(context: Context, speed: Float) {
        prefs(context).edit().putString(KEY_PLAY_SPEED, speed.toString()).apply()
    }

    private fun parse(value: String): Pair<Float, Float>? {
        val parts = value.split(',')
        if (parts.size != 2) return null
        val x = parts[0].toFloatOrNull() ?: return null
        val y = parts[1].toFloatOrNull() ?: return null
        return x to y
    }
}
