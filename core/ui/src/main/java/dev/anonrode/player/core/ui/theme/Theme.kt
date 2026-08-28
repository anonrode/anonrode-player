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
 * (settings row icons, toggles, switches, dialogs, sheets) pick this up
 * automatically.
 *
 * Every slot that Material3 components visibly paint is mapped onto the
 * skin so popups (AlertDialog, ModalBottomSheet, menus, snackbars) never
 * fall back to the default purple-grey M3 surfaces mid-skin.
 */
private fun schemeForPalette(p: SkinPalette) = if (p.background.luminance() < 0.5f) {
    darkColorScheme(
        primary = p.accent,
        onPrimary = p.tabOn,
        primaryContainer = p.accentDeep,
        onPrimaryContainer = Color.White,
        secondary = p.accent,
        onSecondary = p.tabOn,
        secondaryContainer = p.surface,
        onSecondaryContainer = p.text,
        tertiary = p.accentDeep,
        tertiaryContainer = p.surface,
        onTertiaryContainer = p.text,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.textDim,
        surfaceContainerLowest = p.background,
        surfaceContainerLow = p.background,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.rowBg,
        surfaceContainerHighest = p.rowBg,
        surfaceDim = p.background,
        surfaceBright = p.rowBg,
        surfaceTint = p.accent,
        inverseSurface = p.text,
        inverseOnSurface = p.background,
        inversePrimary = p.accentDeep,
        outline = p.surfaceLine,
        outlineVariant = p.rowLine,
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = p.accent,
        onPrimary = Color.White,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.accentDeep,
        secondary = p.accent,
        onSecondary = Color.White,
        secondaryContainer = p.surface,
        onSecondaryContainer = p.text,
        tertiary = p.accentDeep,
        tertiaryContainer = p.surface,
        onTertiaryContainer = p.text,
        background = p.background,
        onBackground = p.text,
        surface = p.surface,
        onSurface = p.text,
        surfaceVariant = p.surface,
        onSurfaceVariant = p.textDim,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = p.background,
        surfaceContainer = p.surface,
        surfaceContainerHigh = p.rowBg,
        surfaceContainerHighest = p.rowBg,
        surfaceDim = p.background,
        surfaceBright = Color.White,
        surfaceTint = p.accent,
        inverseSurface = p.text,
        inverseOnSurface = p.background,
        inversePrimary = p.accent,
        outline = p.surfaceLine,
        outlineVariant = p.rowLine,
        scrim = Color.Black,
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
