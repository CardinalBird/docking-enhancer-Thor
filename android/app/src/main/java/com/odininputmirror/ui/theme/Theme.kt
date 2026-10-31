package com.odininputmirror.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.odininputmirror.R

// Switch 2 menu grey with the Cocoon Shell teal-blue for focus and selection.
object Palette {
    // Flat page colour everywhere: the Switch 2 menus have no lighter panel and no darker bands.
    val screen = Color(0xFF1C1C1C)
    val surface = Color(0xFF262626)
    val surfaceRaised = Color(0xFF303030)
    val border = Color(0xFF3A3A3A)
    val borderStrong = Color(0xFF5A5A5A)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFC8C8C8)
    val textMuted = Color(0xFF8E8E8E)
    // Cocoon blue: fills (A chip, switch track, selected radio).
    val accent = Color(0xFF0080B3)
    val accentSoft = Color(0xFF13303C)
    // Pale cyan for small accents, where the blue would be too dark to read as text.
    val cyan = Color(0xFFB4F2FD)
    val danger = Color(0xFFFF6B6B)
    val dangerSoft = Color(0xFF4A2626)
    // Solid stand-in for [focusBrush] where a brush can't go; the middle of the gradient.
    val focus = Color(0xFF008A90)
    val focusBg = Color(0xFF2A2D2E)
    // The focused item's outline, blue to teal to green like Cocoon's highlight.
    val focusBrush = Brush.linearGradient(listOf(Color(0xFF0080B3), Color(0xFF008A90), Color(0xFF1DAC85)))
    // Kept for callers that used to paint the header and hint bands; now the page colour, so the
    // hints sit on the page like the Switch 2's.
    val band = screen
    // Behind a prompt that owns the screen. Dark enough to say "answer this first", sheer enough to
    // leave the row it belongs to legible underneath.
    val scrim = Color(0xCC111111)
    // Green: player lamps, and a controller carrying a mapping the user captured themselves.
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

// Only the SemiBold cut is bundled, so every weight resolves to it: Poppins SemiBold throughout,
// like Cocoon. See third-party/poppins/NOTICE.md.
private val Poppins = FontFamily(Font(R.font.poppins_semibold, FontWeight.SemiBold))

private fun TextStyle.poppins() = copy(fontFamily = Poppins)

private val AppTypography = Typography().run {
    Typography(
        displayLarge.poppins(), displayMedium.poppins(), displaySmall.poppins(),
        headlineLarge.poppins(), headlineMedium.poppins(), headlineSmall.poppins(),
        titleLarge.poppins(), titleMedium.poppins(), titleSmall.poppins(),
        bodyLarge.poppins(), bodyMedium.poppins(), bodySmall.poppins(),
        labelLarge.poppins(), labelMedium.poppins(), labelSmall.poppins(),
    )
}

@Composable
fun DockingEnhancerTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
