package app.stillroom.fractions

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QuantityFormatterTest {
    private val fractions = QuantityFormatter(QuantityStyle.Fractions)
    private val decimals = QuantityFormatter(QuantityStyle.Decimals)
    private fun f(value: String, locale: Locale = Locale.US) = fractions.format(BigDecimal(value), locale)
    private fun d(value: String, locale: Locale = Locale.US) = decimals.format(BigDecimal(value), locale)

    @Test fun zero() {
        assertEquals("0", f("0")); assertEquals("0", f("0.000"))
        assertEquals("0", d("0")); assertEquals("0", d("0.000"))
    }

    @Test fun commonKitchenFractions() {
        assertEquals("⅛", f("0.125")); assertEquals("¼", f("0.25")); assertEquals("½", f("0.5"))
        assertEquals("¾", f("0.75")); assertEquals("⅓", f("0.3333333333")); assertEquals("⅔", f("0.6666666667"))
    }

    @Test fun mixedNumbers() {
        assertEquals("1½", f("1.5")); assertEquals("2¼", f("2.25")); assertEquals("10⅔", f("10.6667"))
        assertEquals("3", f("3.000"))
    }

    @Test fun roundedRepeatingDecimalsAreTheFractionTheyRepresent() {
        // 0.333 and 0.6667 are within EXACT_TOLERANCE of 1/3 and 2/3, so no approximation marker.
        assertEquals("⅓", f("0.333")); assertEquals("⅔", f("0.6667")); assertEquals("⅔", f("0.667"))
        assertEquals("0.333", d("0.333")); assertEquals("0.667", d("0.6667")); assertEquals("0.333", d("0.3333333"))
    }

    @Test fun nearFractionsAreMarkedApproximate() {
        assertEquals("≈⅓", f("0.33")); assertEquals("≈⅔", f("0.67")); assertEquals("≈1½", f("1.505"))
        assertEquals("≈⅛", f("0.135")) // exactly at the 0.01 tolerance
    }

    @Test fun noCloseKitchenFractionFallsBackToDecimal() {
        assertEquals("0.2", f("0.2")); assertEquals("0.375", f("0.375")); assertEquals("0.875", f("0.875"))
        assertEquals("1.1", f("1.1")); assertEquals("0.136", f("0.136")) // just outside tolerance
        assertEquals("2.995", f("2.995")) // never rounds up into a misleading whole number
    }

    @Test fun decimalsTrimAndRoundHalfUp() {
        assertEquals("1.5", d("1.5000")); assertEquals("0.125", d("0.125")); assertEquals("2", d("2.0004"))
        assertEquals("0.13", d("0.1295")); assertEquals("1.001", d("1.0005"))
        assertEquals("0.0001", d("0.0001")) // a nonzero amount never displays as 0
        assertEquals("0.000123", d("0.0001234"))
    }

    @Test fun largeNumbersUsePlainNotation() {
        assertEquals("1234567½", f("1234567.5")); assertEquals("1234567.5", d("1234567.5"))
        assertEquals("1000000000000", d("1E+12")); assertEquals("1000000000000", f("1E+12"))
        assertEquals("123456789012345678901234567890¼", f("123456789012345678901234567890.25"))
    }

    @Test fun negativeJournalAmounts() {
        assertEquals("-1½", f("-1.5")); assertEquals("-0.2", f("-0.2")); assertEquals("-2.5", d("-2.5"))
    }

    @Test fun decimalSeparatorFollowsLocale() {
        assertEquals("1,5", d("1.5", Locale.GERMANY)); assertEquals("0,2", f("0.2", Locale.GERMANY))
        assertEquals("1½", f("1.5", Locale.GERMANY))
    }

    @Test fun everyDisplayParsesBackInBothStyles() {
        // Editors prefill with this text; the existing parser must accept it in either style.
        val parser = QuantityFractions()
        for (value in listOf("0", "0.125", "0.333", "0.33", "1.5", "2.995", "1234567.5", "0.0001234")) {
            assertNotNull(f(value), parser.parse(f(value), Locale.US))
            assertNotNull(d(value), parser.parse(d(value), Locale.US))
        }
    }

    @Test fun formattingNeverMutatesTheValue() {
        val stored = BigDecimal("0.33330")
        f(stored.toPlainString()); decimals.format(stored); fractions.format(stored)
        assertEquals("0.33330", stored.toPlainString())
    }
}
