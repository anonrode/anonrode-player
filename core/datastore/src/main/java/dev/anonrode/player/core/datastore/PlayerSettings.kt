package dev.anonrode.player.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Global player defaults. Per-video overrides live in Room (media_state). */
@Serializable
data class PlayerSettings(
    val decoderPriority: DecoderPriority = DecoderPriority.PREFER_DEVICE,
    val resumeBehavior: ResumeBehavior = ResumeBehavior.ALWAYS_ASK,
    /**
     * Legacy auto-sync gate: enabled = the background subtitle fingerprint
     * job may run on a video with no persisted lock. Kept ON by default for
     * backward compatibility — users who relied on v0.6.1 keep the same
     * behavior. The new user-facing sub-sync UX toggle is the SEPARATE
     * [subtitleAutoSyncEnabled] below, default OFF.
     */
    val autoSyncEnabled: Boolean = true,
    /**
     * Sub-sync UX toggle (v0.6.2): when ON, the user has opted into the
     * full sub-sync experience and:
     *   - the sync fingerprint job is scheduled for any video that lacks
     *     a high-recall lock;
     *   - the live AudioSyncProcessor re-lock is enabled during playback.
     * Default is OFF — sync runs only when the user explicitly wants it,
     * matching the v0.6.1 contract. The bottom-row sync toggle in the
     * player chrome writes through to this field; PersistOnce the toggle
     * is ON it stays ON for every video until the user flips it back.
     */
    val subtitleAutoSyncEnabled: Boolean = false,
    val autoAdvance: Boolean = true,
    val keepScreenOn: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val subtitleSize: Int = 1,          // 0=S 1=M 2=L 3=XL
    val subtitlePosition: Int = 1,      // 0=LOW 1=MID 2=HIGH 3=TOP
    // Default matches the MX-style bold outlined look the renderer ships.
    val subtitleBold: Boolean = true,
    // SubtitleColor ordinal: 0=WHITE 1=YELLOW 2=GREEN 3=CYAN.
    val subtitleColor: Int = 0,
    val defaultSubtitleLanguage: String? = null,
    val seekIncrementSec: Int = 10,
    val doubleTapSeek: Boolean = true,
    val swipeToSeek: Boolean = true,
    val volumeGesture: Boolean = true,
    val brightnessGesture: Boolean = true,
    val pinchZoom: Boolean = true,
    val autoHideControlsMs: Long = 3500L,
    /** Sleep timer in minutes; 0=off, -1=end of episode. */
    val sleepTimerMinutes: Int = 0,
    /** Volume boost percent over system max: 0/50/100/200 → gain 1–3×. */
    val volumeBoostPct: Int = 0,
    val theme: String = "dark",
    val dynamicColor: Boolean = true,
    val amoledBlack: Boolean = false,
    val fastSeekThresholdSec: Long = 120L,
    /**
     * Global default playback speed applied when a video has no per-video
     * speed of its own (1.0 = normal). Kept in sync with the legacy
     * "play_speed" SharedPreferences key by the settings screen so the
     * player picks it up through its existing global-speed fallback.
     */
    val defaultPlaybackSpeed: Float = 1.0f,
    /**
     * Persisted FOLDERS sort mode (FolderSort name: NAME_ASC / NAME_DESC /
     * RECENT / MOST_VIDEOS). Unknown values fall back to NAME_ASC.
     */
    val librarySort: String = "NAME_ASC",
)

enum class DecoderPriority { PREFER_DEVICE, PREFER_APP, DEVICE_ONLY }
enum class ResumeBehavior { ALWAYS_ASK, ALWAYS_RESUME, ALWAYS_START_OVER }

object PlayerSettingsSerializer : Serializer<PlayerSettings> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: PlayerSettings = PlayerSettings()

    override suspend fun readFrom(input: InputStream): PlayerSettings =
        try {
            json.decodeFromString(PlayerSettings.serializer(), input.readBytes().decodeToString())
        } catch (_: Exception) {
            defaultValue
        }

    override suspend fun writeTo(t: PlayerSettings, output: OutputStream) {
        output.write(json.encodeToString(PlayerSettings.serializer(), t).toByteArray())
    }
}

val Context.playerSettingsDataStore: DataStore<PlayerSettings> by dataStore(
    fileName = "player_settings.json",
    serializer = PlayerSettingsSerializer,
)
