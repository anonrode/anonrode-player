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
    COBALT("COBALT"),
    AMBER("AMBER"),
    LIGHT("LIGHT"),
    OBSIDIAN("OBSIDIAN");

    fun next(): Skin = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromName(name: String?): Skin = when (name?.uppercase()) {
            "COBALT", "MX" -> COBALT
            "AMBER", "SIGNAL" -> AMBER
            "LIGHT" -> LIGHT
            "OBSIDIAN", "BLACK" -> OBSIDIAN
            else -> entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: COBALT
        }
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
            // ── COBALT (Deep blue + matte basalt) ───────────────────
            Skin.COBALT -> SkinPalette(
                accent = Color(0xFF2563EB),
                accentDeep = Color(0xFF1D4ED8),
                accentSoft = Color(0x1F2563EB),
                accentLine = Color(0x662563EB),
                background = Color(0xFF0E1015),
                surface = Color(0xFF161920),
                surfaceLine = Color(0xFF262B37),
                rowBg = Color(0xFF1B1F27),
                rowLine = Color(0xFF262B37),
                navBg = Color(0xFF12141A),
                navLine = Color(0xFF20242E),
                text = Color(0xFFF3F4F6),
                textDim = Color(0xFF9CA3AF),
                iconDim = Color(0xFFD1D5DB),
                toggleOff = Color(0xFF2B303D),
                tabOn = Color.White,
            )
            // ── AMBER (Warm media studio amber) ─────────────────────
            Skin.AMBER -> SkinPalette(
                accent = Color(0xFFD97706),
                accentDeep = Color(0xFFB45309),
                accentSoft = Color(0x1FD97706),
                accentLine = Color(0x66D97706),
                background = Color(0xFF0F1014),
                surface = Color(0xFF17181F),
                surfaceLine = Color(0xFF272935),
                rowBg = Color(0xFF1C1D26),
                rowLine = Color(0xFF272935),
                navBg = Color(0xFF13141A),
                navLine = Color(0xFF21232D),
                text = Color(0xFFF4F4F6),
                textDim = Color(0xFF9EA0AA),
                iconDim = Color(0xFFD2D4DC),
                toggleOff = Color(0xFF2E303D),
                tabOn = Color.White,
            )
            // ── LIGHT (Clean studio alabaster) ──────────────────────
            Skin.LIGHT -> SkinPalette(
                accent = Color(0xFF1D4ED8),
                accentDeep = Color(0xFF1E40AF),
                accentSoft = Color(0x1A1D4ED8),
                accentLine = Color(0x4D1D4ED8),
                background = Color(0xFFF6F8FA),
                surface = Color.White,
                surfaceLine = Color(0xFFE2E5EB),
                rowBg = Color.White,
                rowLine = Color(0xFFECEEF2),
                navBg = Color.White,
                navLine = Color(0xFFE2E5EB),
                text = Color(0xFF111827),
                textDim = Color(0xFF4B5563),
                iconDim = Color(0xFF374151),
                toggleOff = Color(0xFFD1D5DB),
                tabOn = Color.White,
            )
            // ── OBSIDIAN (Neutral monochrome slate) ─────────────────
            Skin.OBSIDIAN -> SkinPalette(
                accent = Color(0xFF64748B),
                accentDeep = Color(0xFF475569),
                accentSoft = Color(0x1F64748B),
                accentLine = Color(0x6664748B),
                background = Color(0xFF090A0D),
                surface = Color(0xFF121418),
                surfaceLine = Color(0xFF20232A),
                rowBg = Color(0xFF16181F),
                rowLine = Color(0xFF20232A),
                navBg = Color(0xFF0E1013),
                navLine = Color(0xFF1B1D23),
                text = Color(0xFFEDEDEF),
                textDim = Color(0xFF8E929E),
                iconDim = Color(0xFFC8CBD4),
                toggleOff = Color(0xFF282B33),
                tabOn = Color.White,
            )
        }
    }
}

/** Resolve the active palette inside a composition. */
@Composable
fun rememberSkinPalette(): SkinPalette = SkinPalette.forSkin(rememberSkin())
