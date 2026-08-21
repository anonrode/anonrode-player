package dev.anonrode.player.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ANONRODE brand palette
val Purple = Color(0xFF6C63FF)
val Teal = Color(0xFF00D4AA)
val Warn = Color(0xFFFFD166)

val DarkBg = Color(0xFF0D0F14)
val DarkSurface = Color(0xFF161922)
val DarkSurfaceVariant = Color(0xFF1E2130)
val DarkOutline = Color(0xFF2A2F45)

private val DarkColors = darkColorScheme(
    primary = Purple,
    secondary = Teal,
    tertiary = Warn,
    background = DarkBg,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = Color(0xFFF0F2F8),
    onSurface = Color(0xFFF0F2F8),
    outline = DarkOutline,
)

private val LightColors = lightColorScheme(
    primary = Purple,
    secondary = Teal,
    tertiary = Warn,
)

@Composable
fun AnonrodeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
