package app.stillroom.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stillroom.domain.ThemeChoice

val LocalReducedMotion = staticCompositionLocalOf { false }
val LocalKitchen = staticCompositionLocalOf { KitchenColors.Light }

internal object KitchenSurfaces {
    val LightPage = Color(0xFFFBF6EF)
    val LightGroup = Color(0xFFF3E8DB)
    val DarkPage = Color(0xFF211B18)
    val DarkGroup = Color(0xFF302620)
}

@Immutable
data class KitchenColors(val due: Color, val overdue: Color, val expiring: Color, val stocked: Color, val photoScrim: Color) {
    companion object {
        val Light = KitchenColors(Color(0xFF80531C), Color(0xFF9B2C2C), Color(0xFF80531C), Color(0xFF4D6548), Color(0x66000000))
        val Dark = KitchenColors(Color(0xFFDBBD88), Color(0xFFFFB4AB), Color(0xFFE8BF82), Color(0xFFABC79E), Color(0x99000000))
    }
}

internal val StillroomLightColors = lightColorScheme(
    primary = Color(0xFF8B4A32), onPrimary = Color.White,
    primaryContainer = Color(0xFFEED9C8), onPrimaryContainer = Color(0xFF3A160C),
    secondary = Color(0xFF66584B), onSecondary = Color.White,
    secondaryContainer = KitchenSurfaces.LightGroup, onSecondaryContainer = Color(0xFF30251E),
    tertiary = Color(0xFF66584B), onTertiary = Color.White,
    tertiaryContainer = KitchenSurfaces.LightGroup, onTertiaryContainer = Color(0xFF30251E),
    background = KitchenSurfaces.LightPage, onBackground = Color(0xFF30251E),
    surface = KitchenSurfaces.LightPage, onSurface = Color(0xFF30251E),
    surfaceVariant = KitchenSurfaces.LightGroup, onSurfaceVariant = Color(0xFF66584B),
    surfaceContainerLowest = KitchenSurfaces.LightPage,
    surfaceContainerLow = KitchenSurfaces.LightGroup,
    surfaceContainer = KitchenSurfaces.LightGroup,
    surfaceContainerHigh = Color(0xFFEEE0D2), surfaceContainerHighest = Color(0xFFE8D8C8),
    outline = Color(0xFF806C5B), outlineVariant = Color(0xFFDFCEBD),
    error = Color(0xFF9B2C2C), onError = Color.White,
    errorContainer = Color(0xFFFFF1F0), onErrorContainer = Color(0xFF9B2C2C),
    inverseSurface = Color(0xFF392D25), inverseOnSurface = Color(0xFFF7EEE4),
    inversePrimary = Color(0xFFE8B4A0), scrim = Color.Black,
    surfaceTint = Color.Transparent,
)
internal val StillroomDarkColors = darkColorScheme(
    primary = Color(0xFFE8B4A0), onPrimary = KitchenSurfaces.DarkPage,
    primaryContainer = Color(0xFF52382B), onPrimaryContainer = Color(0xFFE8B4A0),
    secondary = Color(0xFFD0BAA7), onSecondary = KitchenSurfaces.DarkPage,
    secondaryContainer = KitchenSurfaces.DarkGroup, onSecondaryContainer = Color(0xFFF7EEE4),
    tertiary = Color(0xFFD0BAA7), onTertiary = KitchenSurfaces.DarkPage,
    tertiaryContainer = KitchenSurfaces.DarkGroup, onTertiaryContainer = Color(0xFFF7EEE4),
    background = KitchenSurfaces.DarkPage, onBackground = Color(0xFFF7EEE4),
    surface = KitchenSurfaces.DarkPage, onSurface = Color(0xFFF7EEE4),
    surfaceVariant = KitchenSurfaces.DarkGroup, onSurfaceVariant = Color(0xFFD0BAA7),
    surfaceContainerLowest = KitchenSurfaces.DarkPage,
    surfaceContainerLow = KitchenSurfaces.DarkGroup, surfaceContainer = KitchenSurfaces.DarkGroup,
    surfaceContainerHigh = Color(0xFF392D25), surfaceContainerHighest = Color(0xFF403229),
    outline = Color(0xFFA38C7B), outlineVariant = Color(0xFF554438),
    error = Color(0xFFFFB4AB), onError = Color(0xFF3B1C1A),
    errorContainer = Color(0xFF3B1C1A), onErrorContainer = Color(0xFFFFB4AB),
    inverseSurface = Color(0xFFF7EEE4), inverseOnSurface = Color(0xFF30251E),
    inversePrimary = Color(0xFF8B4A32), scrim = Color.Black,
    surfaceTint = Color.Transparent,
)

/** Wallpaper preference contributes an accent while warm household surfaces stay stable. */
internal fun withDynamicAccent(base: ColorScheme, dynamic: ColorScheme): ColorScheme {
    val primary = dynamic.primary
    val blackContrast = (primary.luminance() + 0.05f) / 0.05f
    val whiteContrast = 1.05f / (primary.luminance() + 0.05f)
    val onPrimary = if (blackContrast >= whiteContrast) Color.Black else Color.White
    // System-generated M3 primary is intended for this theme's surface. Fall back if unsuitable.
    val surfaceContrast = (maxOf(primary.luminance(), base.surface.luminance()) + 0.05f) /
        (minOf(primary.luminance(), base.surface.luminance()) + 0.05f)
    val containerContrast = (maxOf(primary.luminance(), base.surfaceContainerHigh.luminance()) + 0.05f) /
        (minOf(primary.luminance(), base.surfaceContainerHigh.luminance()) + 0.05f)
    if (surfaceContrast < 4.5f || containerContrast < 4.5f) return base
    return base.copy(primary = primary, onPrimary = onPrimary,
        primaryContainer = base.surfaceContainerHigh, onPrimaryContainer = primary,
        inversePrimary = dynamic.inversePrimary)
}

private val KitchenShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(24.dp),
    medium = RoundedCornerShape(24.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp, lineHeight = height.sp,
)
private val KitchenType = Typography(
    displayLarge = type(32, 40, FontWeight.SemiBold), displayMedium = type(32, 40, FontWeight.SemiBold), displaySmall = type(32, 40, FontWeight.SemiBold),
    headlineLarge = type(24, 32, FontWeight.SemiBold), headlineMedium = type(24, 32, FontWeight.SemiBold), headlineSmall = type(20, 28, FontWeight.SemiBold),
    titleLarge = type(20, 28, FontWeight.SemiBold), titleMedium = type(16, 24, FontWeight.Medium), titleSmall = type(14, 20, FontWeight.Medium),
    bodyLarge = type(16, 24), bodyMedium = type(14, 20), bodySmall = type(12, 16),
    labelLarge = type(14, 20, FontWeight.Medium), labelMedium = type(12, 16, FontWeight.Medium), labelSmall = type(12, 16, FontWeight.Medium),
)

@Composable
fun StillroomTheme(choice: ThemeChoice = ThemeChoice.System, dynamicColor: Boolean = false, reducedMotion: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (choice) { ThemeChoice.System -> isSystemInDarkTheme(); ThemeChoice.Light -> false; ThemeChoice.Dark -> true }
    val base = if (dark) StillroomDarkColors else StillroomLightColors
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        val context = LocalContext.current
        withDynamicAccent(base, if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
    } else base
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalKitchen provides if (dark) KitchenColors.Dark else KitchenColors.Light) {
        MaterialTheme(colorScheme = colors, typography = KitchenType, shapes = KitchenShapes, content = content)
    }
}
