package app.stillroom.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import app.stillroom.fractions.QuantityFormatter
import java.math.BigDecimal
import java.util.Locale

/**
 * The app's one quantity formatter, chosen in Settings → Appearance → Show quantities as.
 * Provided once by [StillroomShell]; every screen reads it from here instead of formatting
 * quantities itself. Display only: it never changes what is stored or sent to Grocy.
 */
val LocalQuantityFormatter = staticCompositionLocalOf { QuantityFormatter() }

/** Display text for a quantity in the user's chosen style. */
@Composable
@ReadOnlyComposable
fun quantityText(value: BigDecimal): String = LocalQuantityFormatter.current.format(value, Locale.getDefault())
