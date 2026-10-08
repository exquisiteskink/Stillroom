package app.stillroom

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.stillroom.ui.KitchenColors
import app.stillroom.ui.StillroomDarkColors
import app.stillroom.ui.StillroomLightColors
import app.stillroom.ui.withDynamicAccent
import org.junit.Assert.*
import org.junit.Test

/** Contrast checks protect legibility across actual shared page and group roles. */
class SectionAccentTest {
    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + 0.05f) / (minOf(a.luminance(), b.luminance()) + 0.05f)
    private fun checkText(color: Color, background: Color) {
        assertTrue("Contrast ${contrast(color, background)} must be at least 4.5:1", contrast(color, background) >= 4.5f)
    }
    private fun checkTheme(colors: ColorScheme, status: KitchenColors) {
        for (surface in listOf(colors.surface, colors.surfaceContainerLow, colors.surfaceContainerHigh)) {
            checkText(colors.onSurface, surface)
            checkText(colors.onSurfaceVariant, surface)
            checkText(status.due, surface)
            checkText(status.overdue, surface)
            checkText(status.expiring, surface)
            checkText(status.stocked, surface)
            assertTrue("Input boundaries require 3:1", contrast(colors.outline, surface) >= 3f)
        }
        checkText(colors.onPrimary, colors.primary)
        checkText(colors.onErrorContainer, colors.errorContainer)
        checkText(colors.onPrimaryContainer, colors.primaryContainer)
    }
    @Test fun launcherLayersKeepGlyphContrast() {
        // Adaptive icon glyph-to-tile contrast. 3:1 is the non-text UI minimum; themed icons tint the monochrome layer instead.
        assertTrue(contrast(Color(0xFFFBF6EF), Color(0xFF8B4A32)) >= 3f)
        assertTrue(contrast(Color(0xFFF3D5C6), Color(0xFF3A241C)) >= 3f)
    }
    @Test fun lightTextStatusAndControlBoundariesAreLegible() = checkTheme(StillroomLightColors, KitchenColors.Light)
    @Test fun darkTextStatusAndControlBoundariesAreLegible() = checkTheme(StillroomDarkColors, KitchenColors.Dark)
    @Test fun dynamicPreferenceDoesNotRecolorNeutralOrErrorRoles() {
        val dynamic = StillroomLightColors.copy(primary = Color(0xFF003399), secondary = Color.Magenta, surface = Color.Yellow, error = Color.Green)
        val actual = withDynamicAccent(StillroomLightColors, dynamic)
        assertEquals(dynamic.primary, actual.primary)
        assertEquals(StillroomLightColors.surface, actual.surface)
        assertEquals(StillroomLightColors.secondary, actual.secondary)
        assertEquals(StillroomLightColors.error, actual.error)
        checkText(actual.onPrimary, actual.primary)
    }
    @Test fun illegibleDynamicAccentFallsBackToAccessibleDefault() {
        val actual = withDynamicAccent(StillroomLightColors, StillroomLightColors.copy(primary = Color.White))
        assertEquals(StillroomLightColors.primary, actual.primary)
    }
}
