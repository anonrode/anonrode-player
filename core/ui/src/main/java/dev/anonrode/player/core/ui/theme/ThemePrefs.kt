package dev.anonrode.player.core.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The four skins defined in docs/ui-app-final.html. Each skin has its own
 * accent (green/teal/dark-green), surface (card / row / nav), and foreground
 * colour. Player overlays stay dark over the video regardless of skin so
 * subtitles + transport stay readable on bright frames.
 */
enum class Skin(val displayName: String) {
    MX("MX GREEN"),
    SIGNAL("SIGNAL TEAL"),
    LIGHT("LIGHT"),
    BLACK("BLACK");

    fun next(): Skin = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): Skin =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: MX
    }
}

/**
 * Theme preference: a SharedPreferences-backed [Skin] selection with a
 * Compose-friendly [StateFlow] so the theme reacts in real-time when the
 * user cycles skins from the settings screen.
 *
 * Lives in core:ui so the player, the library, and the settings screen can
 * all share it without depending on a DataStore serializer. The persistent
 * store is just a SharedPreferences file (matches the pattern in
 * app/.../PlayerPrefs.kt).
 */
class ThemePrefs private constructor(
    private val prefs: SharedPreferences,
) {
    private val _skin = MutableStateFlow(
        Skin.fromName(prefs.getString(KEY_SKIN, null))
    )
    val skin: StateFlow<Skin> = _skin.asStateFlow()

    fun setSkin(skin: Skin) {
        prefs.edit().putString(KEY_SKIN, skin.name).apply()
        _skin.value = skin
    }

    /** Cycle MX → SIGNAL → LIGHT → BLACK → MX. */
    fun cycleSkin(): Skin {
        val next = skin.value.next()
        setSkin(next)
        return next
    }

    companion object {
        private const val FILE = "theme_prefs"
        private const val KEY_SKIN = "skin"

        @Volatile private var instance: ThemePrefs? = null

        fun get(context: Context): ThemePrefs =
            instance ?: synchronized(this) {
                instance ?: ThemePrefs(
                    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}

/** Read the active [Skin] inside a composition. */
@Composable
fun rememberSkin(): Skin {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ThemePrefs.get(context) }
    val skin by prefs.skin.collectAsState()
    return skin
}

/** A palette derived from a [Skin]. Player overlays use this. */
data class SkinPalette(
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val accentLine: Color,
    val background: Color,
    val surface: Color,
    val surfaceLine: Color,
    val rowBg: Color,
    val rowLine: Color,
    val navBg: Color,
    val navLine: Color,
    val text: Color,
    val textDim: Color,
    val iconDim: Color,
    val toggleOff: Color,
    val tabOn: Color,
) {
    companion object {
        fun forSkin(skin: Skin): SkinPalette = when (skin) {
            // ── MX GREEN ───────────────────────────────────────────
            // Colours mirror docs/ui-app-final.html body[data-pal="mx"].
            Skin.MX -> SkinPalette(
                accent = Color(0xFF00E676),
                accentDeep = Color(0xFF0B8A4A),
                accentSoft = Color(0x1400E676),
                accentLine = Color(0x7300E676),
                background = Color(0xFF0F0F13),
                surface = Color(0xFF18181E),
                surfaceLine = Color(0xFF26262E),
                rowBg = Color(0xFF17171D),
                rowLine = Color(0xFF23232B),
                navBg = Color(0xFF141419),
                navLine = Color(0xFF23232B),
                text = Color.White,
                textDim = Color(0xFFAAAAAA),
                iconDim = Color(0xFFCFCFD8),
                toggleOff = Color(0xFF3A3A44),
                tabOn = Color.Black,
            )
            // ── SIGNAL TEAL ───────────────────────────────────────
            Skin.SIGNAL -> SkinPalette(
                accent = Color(0xFF2DE0B6),
                accentDeep = Color(0xFF177E63),
                accentSoft = Color(0x142DE0B6),
                accentLine = Color(0x732DE0B6),
                background = Color(0xFF0B0E14),
                surface = Color(0xFF12161F),
                surfaceLine = Color(0xFF1E2530),
                rowBg = Color(0xFF12161F),
                rowLine = Color(0xFF1E2530),
                navBg = Color(0xFF0D1017),
                navLine = Color(0xFF20242E),
                text = Color.White,
                textDim = Color(0xFFAAAAAA),
                iconDim = Color(0xFFCFCFD8),
                toggleOff = Color(0xFF2A3240),
                tabOn = Color.Black,
            )
            // ── LIGHT ─────────────────────────────────────────────
            Skin.LIGHT -> SkinPalette(
                accent = Color(0xFF00A84D),
                accentDeep = Color(0xFF007A38),
                accentSoft = Color(0x1A00A84D),
                accentLine = Color(0x7300A84D),
                background = Color(0xFFF4F5F8),
                surface = Color.White,
                surfaceLine = Color(0xFFE0E3EC),
                rowBg = Color.White,
                rowLine = Color(0xFFE4E7EE),
                navBg = Color.White,
                navLine = Color(0xFFE4E7EE),
                text = Color(0xFF1A1C22),
                textDim = Color(0xFF6A7080),
                iconDim = Color(0xFF4A4F5C),
                toggleOff = Color(0xFFC7CDDA),
                tabOn = Color.White,
            )
            // ── BLACK ─────────────────────────────────────────────
            Skin.BLACK -> SkinPalette(
                accent = Color(0xFF00E676),
                accentDeep = Color(0xFF0B8A4A),
                accentSoft = Color(0x1400E676),
                accentLine = Color(0x7300E676),
                background = Color(0xFF000000),
                surface = Color(0xFF121214),
                surfaceLine = Color(0xFF232327),
                rowBg = Color(0xFF121214),
                rowLine = Color(0xFF232327),
                navBg = Color(0xFF0A0A0C),
                navLine = Color(0xFF1F1F23),
                text = Color.White,
                textDim = Color(0xFFAAAAAA),
                iconDim = Color(0xFFCFCFD8),
                toggleOff = Color(0xFF333338),
                tabOn = Color.Black,
            )
        }
    }
}

/** Resolve the active palette inside a composition. */
@Composable
fun rememberSkinPalette(): SkinPalette = SkinPalette.forSkin(rememberSkin())
