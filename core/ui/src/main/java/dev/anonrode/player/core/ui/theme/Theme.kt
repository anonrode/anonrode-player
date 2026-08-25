package dev.anonrode.player.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Legacy brand accents — kept exported for any non-themed callers.
val Purple = Color(0xFF6C63FF)
val Teal = Color(0xFF00D4AA)
val Warn = Color(0xFFFFD166)

/**
 * Compose Material3 [androidx.compose.material3.ColorScheme] derived from a
 * [SkinPalette]. App screens that don't paint their own backgrounds
 * (settings row icons, toggles, switches) pick this up automatically.
 */
private fun schemeForPalette(p: SkinPalette) = if (p.background.luminance() < 0.5f) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.tabOn,
        secondary = p.accent,
        onSecondary = p.tabOn,
        tertiary = p.accentDeep,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.textDim,
        outline = p.surfaceLine,
        outlineVariant = p.rowLine,
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = Color.White,
        secondary = p.accent,
        onSecondary = Color.White,
        tertiary = p.accentDeep,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.textDim,
        outline = p.surfaceLine,
        outlineVariant = p.rowLine,
    )
}

/** Heuristic luminance from RGB; < 0.5 = "dark" scheme. */
private fun Color.luminance(): Float {
    val r = red
    val g = green
    val b = blue
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

@Composable
fun AnonrodeTheme(
    skin: Skin = rememberSkin(),
    content: @Composable () -> Unit,
) {
    val palette = SkinPalette.forSkin(skin)
    MaterialTheme(
        colorScheme = schemeForPalette(palette),
        content = content,
    )
}
