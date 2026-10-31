package com.odininputmirror.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.odininputmirror.R
import com.odininputmirror.domain.model.AppTheme

/** Selection, focus borders and teal fills: the same in every theme. */
val Teal = Color(0xFF00C2D6)

/** One theme's colours. Read through [Palette], which follows the theme picked in Additional Settings. */
@Immutable
data class AppPalette(
    val light: Boolean,
    // Flat page colour everywhere: the Switch 2 menus have no lighter panel and no darker bands.
    val screen: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    // Small accent text (selected values, spinners), where a teal fill would be too light to read.
    val cyan: Color,
    val danger: Color,
    val focusBg: Color,
    // Behind a prompt that owns the screen: says "answer this first", leaves the row legible.
    val scrim: Color,
    // Green: player lamps, and a controller carrying a mapping the user captured themselves.
    val custom: Color,
) {
    // Fills: the A chip, switch track, selected radio.
    val accent get() = Teal
    // Text and icons drawn on a teal fill.
    val onAccent get() = Color(0xFF001A1D)
    // Solid focus outline for the items that don't use focusOutline.
    val focus get() = Teal
    // Kept for callers that used to paint the header and hint bands; now the page colour.
    val band get() = screen
}

private val DarkPalette = AppPalette(
    light = false,
    screen = Color(0xFF1E1E1E), surface = Color(0xFF2A2A2A), surfaceRaised = Color(0xFF353535),
    border = Color(0xFF3F3F3F), borderStrong = Color(0xFF5E5E5E),
    textPrimary = Color(0xFFF5F5F5), textSecondary = Color(0xFFB8B8B8), textMuted = Color(0xFF8E8E8E),
    cyan = Teal, danger = Color(0xFFFF6B6B), focusBg = Color(0xFF2C3133),
    scrim = Color(0xCC111111), custom = Color(0xFF4ADE80),
)

private val SuperDarkPalette = DarkPalette.copy(
    screen = Color(0xFF0C0C0C), surface = Color(0xFF161616), surfaceRaised = Color(0xFF202020),
    border = Color(0xFF2A2A2A), borderStrong = Color(0xFF4A4A4A), focusBg = Color(0xFF151C1E),
    scrim = Color(0xE6000000),
)

private val LightPalette = AppPalette(
    light = true,
    screen = Color(0xFFFFFFFF), surface = Color(0xFFF2F2F2), surfaceRaised = Color(0xFFE8ECEE),
    border = Color(0xFFD5D9DB), borderStrong = Color(0xFFA9AFB2),
    textPrimary = Color(0xFF171717), textSecondary = Color(0xFF555B5E), textMuted = Color(0xFF70777A),
    cyan = Color(0xFF006B77), danger = Color(0xFFC62828), focusBg = Color(0xFFE3F7F9),
    scrim = Color(0x99000000), custom = Color(0xFF1E8E3E),
)

fun paletteFor(theme: AppTheme) = when (theme) {
    AppTheme.DARK -> DarkPalette
    AppTheme.SUPER_DARK -> SuperDarkPalette
    AppTheme.LIGHT -> LightPalette
}

private val LocalPalette = staticCompositionLocalOf { DarkPalette }

/** The active theme's colours. Same name the whole UI already used, so call sites didn't change. */
val Palette: AppPalette
    @Composable @ReadOnlyComposable get() = LocalPalette.current

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
fun DockingEnhancerTheme(theme: AppTheme = AppTheme.DARK, content: @Composable () -> Unit) {
    val p = paletteFor(theme)
    val colors = if (p.light) {
        lightColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.screen, onBackground = p.textPrimary,
            surface = p.surface, onSurface = p.textPrimary, surfaceVariant = p.surfaceRaised, onSurfaceVariant = p.textSecondary,
            outline = p.border, error = p.danger)
    } else {
        darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.screen, onBackground = p.textPrimary,
            surface = p.surface, onSurface = p.textPrimary, surfaceVariant = p.surfaceRaised, onSurfaceVariant = p.textSecondary,
            outline = p.border, error = p.danger)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}
