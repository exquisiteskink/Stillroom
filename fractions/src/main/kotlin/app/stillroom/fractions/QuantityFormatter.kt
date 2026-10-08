package app.stillroom.fractions

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

/** How quantities are shown. Display only: stored and sent values are never touched. */
enum class QuantityStyle { Fractions, Decimals }

/**
 * The single display formatter for every quantity in the app.
 *
 * **Fractions** shows the whole part plus the closest common kitchen fraction
 * (⅛, ¼, ⅓, ½, ⅔, ¾), e.g. `1½`, `⅓`, `2¾`. The fraction is used only when the fractional
 * part is within [FRACTION_TOLERANCE] (0.01) of it; otherwise the value falls back to the
 * decimal display below. A fraction that differs from the value by more than
 * [EXACT_TOLERANCE] (0.001) is prefixed with `≈`. Values such as `0.333`, `0.3333` or `0.6667`
 * are only rounded representations of ⅓ or ⅔, so they show unmarked; `0.33` shows `≈⅓`.
 *
 * **Decimals** rounds half-up to [DECIMAL_PLACES] (3) places, trims trailing zeros, uses the
 * locale's decimal separator, never uses grouping or scientific notation, and keeps three
 * significant digits for a nonzero amount that would otherwise round to `0`.
 *
 * Negative values (stock journal reversals) are formatted as a leading `-` plus the absolute
 * value. Zero is `0` in both styles.
 *
 * Returned text is presentation only. It must never be parsed back in place of the stored
 * decimal; editors keep the original value until the user actually edits the field.
 * Input parsing stays in [QuantityFractions.parse], which accepts both forms in either style.
 */
class QuantityFormatter(val style: QuantityStyle = QuantityStyle.Fractions) {
    fun format(value: BigDecimal, locale: Locale = Locale.ROOT): String {
        if (value.signum() < 0) return "-" + format(value.negate(), locale)
        if (value.signum() == 0) return "0"
        if (style == QuantityStyle.Fractions) fraction(value)?.let { return it }
        return decimal(value, locale)
    }

    private fun fraction(value: BigDecimal): String? {
        val whole = value.setScale(0, RoundingMode.DOWN)
        val part = value.subtract(whole)
        if (part.signum() == 0) return whole.toPlainString()
        val (glyph, error) = KITCHEN_FRACTIONS
            .map { (glyph, fraction) -> glyph to part.subtract(fraction).abs() }
            .minBy { it.second }
        if (error > FRACTION_TOLERANCE) return null
        val marker = if (error > EXACT_TOLERANCE) "≈" else ""
        val wholeText = if (whole.signum() == 0) "" else whole.toPlainString()
        return marker + wholeText + glyph
    }

    private fun decimal(value: BigDecimal, locale: Locale): String {
        var rounded = value.setScale(DECIMAL_PLACES, RoundingMode.HALF_UP)
        if (rounded.signum() == 0) rounded = value.round(MathContext(3, RoundingMode.HALF_UP))
        val text = rounded.stripTrailingZeros().toPlainString()
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == '.') text else text.replace('.', separator)
    }

    companion object {
        val FRACTION_TOLERANCE = BigDecimal("0.01")
        val EXACT_TOLERANCE = BigDecimal("0.001")
        const val DECIMAL_PLACES = 3
        private val KITCHEN_FRACTIONS = listOf(
            "⅛" to BigDecimal("0.125"),
            "¼" to BigDecimal("0.25"),
            "⅓" to BigDecimal.ONE.divide(BigDecimal(3), MathContext.DECIMAL128),
            "½" to BigDecimal("0.5"),
            "⅔" to BigDecimal(2).divide(BigDecimal(3), MathContext.DECIMAL128),
            "¾" to BigDecimal("0.75"),
        )
    }
}
