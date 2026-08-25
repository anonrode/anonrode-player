package dev.anonrode.player.audio

import android.media.audiofx.Equalizer
import dev.anonrode.player.core.media.log.AppLog

/**
 * Owns the [android.media.audiofx.Equalizer] effect bound to the player's
 * audio session id. Handles the case where the session id changes (after a
 * decoder swap) by releasing the stale instance and binding a fresh one on
 * the next [setSessionId] call.
 *
 * The [android.media.audiofx.Equalizer] constructor throws on a bad
 * session id (0 / STREAM_MUSIC unsupported) — every call here is
 * defensive and degrades to "effect not available" instead of crashing.
 *
 * Lifecycle:
 *  - Call [setSessionId] every time the host knows the session id has
 *    changed (after a [PlaybackEngine.rebuild], after the first prepared
 *    playback if the previous id was 0).
 *  - Call [setEnabled] from the EQ chip — this is a no-op when no effect
 *    is bound yet, so the chip stays responsive even before the player
 *    is ready.
 *  - Call [release] from the host's onDestroy.
 */
class EqualizerManager {

    private var equalizer: Equalizer? = null
    private var lastSessionId: Int = 0
    private var lastEnabled: Boolean = false

    /**
     * Bind (or rebind) the effect to [sessionId]. Releases any existing
     * effect first. Returns true if an Equalizer was successfully created
     * and applied with the prior [lastEnabled] state, false otherwise.
     */
    fun setSessionId(sessionId: Int): Boolean {
        if (sessionId == 0 || sessionId == lastSessionId && equalizer != null) {
            return equalizer != null
        }
        release()
        if (sessionId == 0) return false
        return try {
            val eq = Equalizer(/* priority = */ 0, sessionId)
            val bands = eq.numberOfBands.toInt()
            val range = eq.bandLevelRange
            AppLog.d("EQ", "bound: sessionId=" + sessionId + " bands=" + bands +
                " range=" + range[0].toInt() + ".." + range[1].toInt() + " mB")
            // Log the centre frequencies so the band panel can be wired up
            // later without re-instantiating the effect.
            val freqs = ShortArray(bands)
            eq.getBandFreqRange(freqs) // pairs (low, high) per band, but cheap
            AppLog.d("EQ", "band range low=" + freqs[0] + "Hz, high=" + freqs[1] + "Hz (band 0)")
            eq.enabled = lastEnabled
            equalizer = eq
            lastSessionId = sessionId
            true
        } catch (e: Throwable) {
            // Most common: device doesn't support audiofx (some emulators,
            // some headless profiles). Don't crash; surface to log + chip.
            AppLog.e("EQ", "failed to bind sessionId=" + sessionId, e)
            false
        }
    }

    /** Enable or disable the bound effect. No-op when no effect is bound. */
    fun setEnabled(enabled: Boolean): Boolean {
        lastEnabled = enabled
        val eq = equalizer ?: return false
        return try {
            eq.enabled = enabled
            AppLog.d("EQ", "enabled=" + enabled + " sessionId=" + lastSessionId)
            true
        } catch (e: Throwable) {
            AppLog.e("EQ", "setEnabled failed", e)
            false
        }
    }

    /** True when the bound effect is currently enabled. */
    val isEnabled: Boolean
        get() = equalizer?.enabled == true

    /** True when the manager has a live effect bound. */
    val isBound: Boolean
        get() = equalizer != null

    /** The number of bands on the bound effect, or 0 when unbound. */
    val bandCount: Int
        get() = equalizer?.numberOfBands?.toInt() ?: 0

    /**
     * Band level range as `[minMb, maxMb]` in millibels (e.g. -1500..+1500).
     * Returns `[0, 0]` when unbound. Callers can divide by 100 for dB.
     */
    val bandLevelRange: IntArray
        get() = equalizer?.bandLevelRange?.let { intArrayOf(it[0].toInt(), it[1].toInt()) }
            ?: intArrayOf(0, 0)

    /** Centre frequency (mHz) of [bandIndex], or 0 if out of range. */
    fun getCentreFreqMhz(bandIndex: Int): Int {
        val eq = equalizer ?: return 0
        return try {
            eq.getCentreFreq(bandIndex.toShort())
        } catch (e: Throwable) {
            0
        }
    }

    /** Get the current gain in mB of [bandIndex], or 0 if unavailable. */
    fun getBandLevel(bandIndex: Int): Int {
        val eq = equalizer ?: return 0
        return try {
            eq.getBandLevel(bandIndex.toShort()).toInt()
        } catch (e: Throwable) {
            0
        }
    }

    /** Set the gain of [bandIndex] in mB (millibels). Clamped by the effect. */
    fun setBandLevel(bandIndex: Int, levelMb: Int): Boolean {
        val eq = equalizer ?: return false
        return try {
            eq.setBandLevel(bandIndex.toShort(), levelMb.toShort())
            true
        } catch (e: Throwable) {
            AppLog.e("EQ", "setBandLevel failed", e)
            false
        }
    }

    fun release() {
        equalizer?.run {
            try { release() } catch (e: Throwable) { AppLog.e("EQ", "release failed", e) }
        }
        equalizer = null
        lastSessionId = 0
    }
}
