package dev.anonrode.player

import android.content.Context
import android.content.SharedPreferences

/**
 * Small key-value store for player preferences that carry per-video keys:
 * subtitle drag position ("sub_pos_<mediaId>" with a "sub_pos_default"
 * fallback) and the global playback speed ("play_speed"). Lives in the app
 * module so core/ stays untouched; values are plain strings so no new
 * dependencies or schema migrations are needed.
 */
object PlayerPrefs {

    private const val FILE = "player_prefs"
    private const val KEY_SUB_POS_DEFAULT = "sub_pos_default"
    private const val KEY_PLAY_SPEED = "play_speed"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Per-video key: "sub_pos_<mediaId>", e.g. sub_pos_/storage/.../ep01.mp4 */
    private fun subPosKey(mediaId: String) = "sub_pos_$mediaId"

    /** Saved subtitle center as stage fractions (x,y); null when unset. */
    fun subtitlePosition(context: Context, mediaId: String): Pair<Float, Float>? =
        prefs(context).getString(subPosKey(mediaId), null)?.let(::parse)
            ?: prefs(context).getString(KEY_SUB_POS_DEFAULT, null)?.let(::parse)

    /** Persist the drag result for this video and as the new global default. */
    fun saveSubtitlePosition(context: Context, mediaId: String, x: Float, y: Float) {
        val value = "$x,$y"
        prefs(context).edit()
            .putString(subPosKey(mediaId), value)
            .putString(KEY_SUB_POS_DEFAULT, value)
            .apply()
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
