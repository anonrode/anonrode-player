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
    val autoSyncEnabled: Boolean = true,
    val autoAdvance: Boolean = true,
    val keepScreenOn: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val subtitleSize: Int = 1,          // 0=S 1=M 2=L 3=XL
    val subtitlePosition: Int = 1,      // 0=LOW 1=MID 2=HIGH 3=TOP
    val subtitleBold: Boolean = false,
    val defaultSubtitleLanguage: String? = null,
    val seekIncrementSec: Int = 10,
    val doubleTapSeek: Boolean = true,
    val swipeToSeek: Boolean = true,
    val volumeGesture: Boolean = true,
    val brightnessGesture: Boolean = true,
    val pinchZoom: Boolean = true,
    val autoHideControlsMs: Long = 3500L,
    val theme: String = "dark",
    val dynamicColor: Boolean = true,
    val amoledBlack: Boolean = false,
    val fastSeekThresholdSec: Long = 120L,
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
