package app.stillroom.fractions

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

enum class NumericField { QUANTITY, PRICE, BARCODE, DATE, ID }
data class ParsedQuantity(val value: BigDecimal, val approximate: Boolean)
data class QuantityDisplay(val text: String, val approximate: Boolean, val fraction: Boolean)
data class FractionOptions(val maxDenominator: Int = 16)

/** Quantity-only presentation. Returned text must never replace the stored decimal. */
class QuantityFractions(private val options: FractionOptions = FractionOptions()) {
    init { require(options.maxDenominator in 1..10000) }

    fun format(
        quantity: ParsedQuantity,
        locale: Locale = Locale.ROOT,
        field: NumericField = NumericField.QUANTITY,
    ): QuantityDisplay {
        val display = format(quantity.value, locale, field)
        if (field != NumericField.QUANTITY || !quantity.approximate || display.approximate) return display
        return display.copy(text = "≈${display.text}", approximate = true)
    }

    /** Strict locale input; repeating fractions are rounded to DECIMAL128 and marked. */
    fun parse(
        text: String,
        locale: Locale = Locale.ROOT,
        field: NumericField = NumericField.QUANTITY,
    ): ParsedQuantity? {
        if (field != NumericField.QUANTITY || text.length > MAX_INPUT_LENGTH) return null
        val trimmed = text.trim()
        val marked = trimmed.startsWith("≈")
        val input = normalizeDigits(trimmed.removePrefix("≈").trim())
        val glyph = GLYPHS[input.lastOrNull()]
        if (glyph != null) {
            val whole = input.dropLast(1).trim()
            if (whole.isNotEmpty() && !INTEGER.matches(whole)) return null
            return divideFraction(whole.ifEmpty { "0" }, glyph.first.toString(), glyph.second.toString(), marked)
        }
        val fraction = FRACTION.matchEntire(input)
        if (fraction != null) {
            val whole = fraction.groupValues[1]
            val numerator = fraction.groupValues[2]
            val denominator = fraction.groupValues[3]
            val n = BigInteger(numerator)
            val d = BigInteger(denominator)
            if (d == BigInteger.ZERO || (whole.isNotEmpty() && (n == BigInteger.ZERO || n >= d))) return null
            return divideFraction(whole.ifEmpty { "0" }, numerator, denominator, marked)
        }
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val decimal = input.replace(separator, '.')
        // A dot is accepted only when it is the locale's decimal separator.
        if (separator != '.' && '.' in input) return null
        if (!DECIMAL.matches(decimal)) return null
        return ParsedQuantity(BigDecimal(decimal), marked)
    }

    /** Pure display conversion; misleading bounded fractions fall back to an unrounded decimal. */
    fun format(
        value: BigDecimal,
        locale: Locale = Locale.ROOT,
        field: NumericField = NumericField.QUANTITY,
    ): QuantityDisplay {
        val decimal = value.stripTrailingZeros().toPlainString()
            .replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
        val fallback = QuantityDisplay(decimal, approximate = false, fraction = false)
        if (field != NumericField.QUANTITY) return fallback
        require(value.signum() >= 0) { "Quantity cannot be negative" }
        val whole = value.toBigInteger()
        val part = value.subtract(BigDecimal(whole))
        if (part.signum() == 0) return fallback

        // Compare rational errors by cross multiplication; no floating-point rounding.
        var bestNumerator = 0
        var bestDenominator = 1
        var bestError: BigDecimal? = null
        for (denominator in 2..options.maxDenominator) {
            val scaled = part.multiply(BigDecimal(denominator))
            val numerator = scaled.setScale(0, RoundingMode.HALF_UP).intValueExact()
            if (numerator == 0 || numerator == denominator) continue
            val error = scaled.subtract(BigDecimal(numerator)).abs()
            val previous = bestError
            if (previous == null || error.multiply(BigDecimal(bestDenominator)) < previous.multiply(BigDecimal(denominator))) {
                bestNumerator = numerator
                bestDenominator = denominator
                bestError = error
            }
        }
        val error = bestError ?: return fallback
        val denominator = BigDecimal(bestDenominator)
        if (error > ABSOLUTE_ERROR.multiply(denominator) ||
            error > part.multiply(RELATIVE_ERROR).multiply(denominator)) return fallback

        val glyph = GLYPHS.entries.firstOrNull { it.value == (bestNumerator to bestDenominator) }?.key
        val fraction = glyph?.toString() ?: "$bestNumerator/$bestDenominator"
        val wholeText = if (whole == BigInteger.ZERO) "" else whole.toString() + if (glyph == null) " " else ""
        val approximate = error.signum() != 0
        return QuantityDisplay((if (approximate) "≈" else "") + wholeText + fraction, approximate, fraction = true)
    }

    private fun divideFraction(whole: String, numerator: String, denominator: String, marked: Boolean): ParsedQuantity {
        val n = BigDecimal(numerator)
        val d = BigDecimal(denominator)
        val exact = try { n.divide(d) } catch (_: ArithmeticException) { null }
        val part = exact ?: n.divide(d, MathContext.DECIMAL128)
        return ParsedQuantity(BigDecimal(whole).add(part), marked || exact == null)
    }

    private fun normalizeDigits(input: String): String = buildString {
        for (character in input) {
            val digit = Character.digit(character, 10)
            append(if (digit >= 0) ('0'.code + digit).toChar() else character)
        }
    }

    private companion object {
        const val MAX_INPUT_LENGTH = 4096
        val ABSOLUTE_ERROR = BigDecimal("0.0005")
        val RELATIVE_ERROR = BigDecimal("0.005")
        val INTEGER = Regex("[0-9]+")
        val DECIMAL = Regex("[0-9]+(?:\\.[0-9]+)?")
        val FRACTION = Regex("(?:([0-9]+)\\s+)?([0-9]+)/([0-9]+)")
        val GLYPHS = mapOf(
            '½' to (1 to 2), '⅓' to (1 to 3), '⅔' to (2 to 3), '¼' to (1 to 4), '¾' to (3 to 4),
            '⅕' to (1 to 5), '⅖' to (2 to 5), '⅗' to (3 to 5), '⅘' to (4 to 5),
            '⅙' to (1 to 6), '⅚' to (5 to 6), '⅐' to (1 to 7), '⅛' to (1 to 8),
            '⅜' to (3 to 8), '⅝' to (5 to 8), '⅞' to (7 to 8), '⅑' to (1 to 9), '⅒' to (1 to 10),
        )
    }
}
