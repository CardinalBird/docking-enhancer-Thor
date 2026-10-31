package com.odininputmirror.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Thor fork: Switch-style flat charcoal with one AYN orange accent for focus and selection.
object Palette {
    val screen = Color(0xFF2B2B2B)
    val surface = Color(0xFF363636)
    val surfaceRaised = Color(0xFF404040)
    val border = Color(0xFF4A4A4A)
    val borderStrong = Color(0xFF6A6A6A)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFC8C8C8)
    val textMuted = Color(0xFF9A9A9A)
    val accent = Color(0xFFFF8A1F)
    val accentSoft = Color(0xFF4A3220)
    val danger = Color(0xFFFF6B6B)
    val dangerSoft = Color(0xFF4A2626)
    val focus = Color(0xFFFF8A1F)
    val focusBg = Color(0xFF474038)
    // The header and hint bar bands, a step darker than the screen like the Switch's.
    val band = Color(0xFF1E1E1E)
    // Behind a prompt that owns the screen. Dark enough to say "answer this first", sheer enough to
    // leave the row it belongs to legible underneath.
    val scrim = Color(0xCC111111)
    // Reserved for a controller carrying a mapping the user captured themselves: it marks the card
    // as "yours", distinct from the blue that means "selected".
    val custom = Color(0xFF4ADE80)
    val customSoft = Color(0xFF14301F)
}

private val DarkColors = darkColorScheme(
    primary = Palette.accent,
    onPrimary = Palette.textPrimary,
    background = Palette.screen,
    onBackground = Palette.textPrimary,
    surface = Palette.surface,
    onSurface = Palette.textPrimary,
    surfaceVariant = Palette.surfaceRaised,
    onSurfaceVariant = Palette.textSecondary,
    outline = Palette.border,
    error = Palette.danger,
)

@Composable
fun DockingEnhancerTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
