package app.stillroom.fractions

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class QuantityFractionsTest {
    private val codec = QuantityFractions()
    private fun equal(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun parsesRequestedFormsAndLocaleDecimals() {
        for (text in listOf("1/2", "½", "0.5")) equal("0.5", codec.parse(text)!!.value)
        for (text in listOf("1 1/2", "1½", "1 ½", "1.5")) equal("1.5", codec.parse(text)!!.value)
        equal("1.25", codec.parse("1,25", Locale.GERMANY)!!.value)
        equal("1.25", codec.parse("١٫٢٥", Locale.forLanguageTag("ar-EG"))!!.value)
        assertNull(codec.parse("1,25", Locale.US))
        for (text in listOf("", "NaN", "Infinity", "1/0", "1 3/2", "1//2", "1,000", "1e3", "+1", "1/2junk", "1½½", "1 0/2")) {
            assertNull(text, codec.parse(text))
        }
    }

    @Test fun requestedDisplaysAndConfigurableDenominator() {
        assertEquals("½", codec.format(BigDecimal("0.5")).text)
        assertEquals("1¼", codec.format(BigDecimal("1.25")).text)
        assertEquals("⅞", codec.format(BigDecimal("0.875")).text)
        assertEquals("0.25", QuantityFractions(FractionOptions(2)).format(BigDecimal("0.25")).text)
        assertEquals("1/20", QuantityFractions(FractionOptions(20)).format(BigDecimal("0.05")).text)
        assertEquals("0,25", QuantityFractions(FractionOptions(2)).format(BigDecimal("0.25"), Locale.GERMANY).text)
        for (maximum in listOf(0, -1, 10001)) {
            assertThrows(IllegalArgumentException::class.java) { QuantityFractions(FractionOptions(maximum)) }
        }
    }

    @Test fun propertyExactRoundTripsAcrossLocalesAndWholeParts() {
        val random = Random(4001)
        repeat(2000) {
            val denominator = listOf(2, 4, 5, 8, 10, 16)[random.nextInt(6)]
            val value = BigDecimal(random.nextInt(0, 100000)).divide(BigDecimal(denominator))
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.forLanguageTag("ar-EG"))) {
                val display = codec.format(value, locale)
                assertFalse(display.approximate)
                equal(value.toPlainString(), codec.parse(display.text, locale)!!.value)
            }
        }
    }

    @Test fun propertyZeroAndNegativeRejection() {
        repeat(100) { scale ->
            assertEquals("0", codec.format(BigDecimal.ZERO.setScale(scale)).text)
            equal("0", codec.parse("0." + "0".repeat(scale + 1))!!.value)
            assertNull(codec.parse("-${scale + 1}/2"))
            assertNull(codec.parse("-${scale + 1}½"))
            assertNull(codec.parse("-${scale + 1}.25"))
            assertThrows(IllegalArgumentException::class.java) { codec.format(BigDecimal(-scale - 1)) }
        }
        assertNull(codec.parse("-0"))
    }

    @Test fun propertyRepeatingDecimalsAreExplicitlyApproximate() {
        for (denominator in listOf(3, 6, 7, 9, 11, 13, 15)) {
            val parsed = codec.parse("1/$denominator")!!
            assertTrue(parsed.approximate)
            val expected = BigDecimal.ONE.divide(BigDecimal(denominator), MathContext.DECIMAL128)
            equal(expected.toPlainString(), parsed.value)
            val display = codec.format(parsed.value)
            assertTrue(display.fraction)
            assertTrue(display.approximate)
            assertTrue(display.text.startsWith("≈"))
            val reparsed = codec.parse(display.text)!!
            assertTrue(reparsed.approximate)
            equal(expected.toPlainString(), reparsed.value)
        }
        assertFalse(codec.parse("1/8")!!.approximate)
        assertTrue(codec.parse("≈0.5")!!.approximate)
    }

    @Test fun propertyLargeValuesAndStorageAreNeverRoundedByDisplay() {
        val random = Random(4002)
        repeat(100) {
            val whole = BigInteger(256, java.util.Random(random.nextLong()))
            val value = BigDecimal(whole).add(BigDecimal("0.25"))
            val original = value.toPlainString()
            val display = codec.format(value)
            assertEquals("${whole}¼", display.text)
            equal(original, codec.parse(display.text)!!.value)
            assertEquals(original, value.toPlainString())
        }
    }

    @Test fun misleadingFractionsFallBackWithoutChangingValue() {
        for (text in listOf("0.00001", "0.123456", "0.9999", "1000000000000000000.123456", "1.00001")) {
            val value = BigDecimal(text)
            val display = codec.format(value)
            assertFalse(display.fraction)
            assertFalse(display.approximate)
            equal(text, codec.parse(display.text)!!.value)
            assertEquals(text, value.toPlainString())
        }
        val value = BigDecimal("0.5001")
        assertEquals("≈½", codec.format(value).text)
        assertEquals("0.5001", value.toPlainString())
    }

    @Test fun forbiddenFieldsNeverUseFractionHandling() {
        for (field in NumericField.entries.filter { it != NumericField.QUANTITY }) {
            for (text in listOf("1/2", "½", "1½", "0.5", "2026/10/06", "00123")) {
                assertNull(codec.parse(text, field = field))
            }
            val display = codec.format(BigDecimal("1.25"), field = field)
            assertEquals("1.25", display.text)
            assertFalse(display.fraction)
            assertFalse(display.approximate)
            assertEquals("-1.25", codec.format(BigDecimal("-1.25"), field = field).text)
        }
    }

    @Test fun parserBoundariesAndUnusualFractions() {
        assertNull(codec.parse("1".repeat(4097)))
        equal("12.5", codec.parse("  12 1/2  ")!!.value)
        equal("2.5", codec.parse("5/2")!!.value)
        equal("0", codec.parse("0/3")!!.value)
        equal("0.2", codec.parse("⅕")!!.value)
        assertNull(codec.parse("12 ½junk"))
        assertNull(codec.parse("≈≈½"))
        assertEquals("0.5", QuantityFractions(FractionOptions(1)).format(BigDecimal("0.5")).text)
        assertEquals("≈½", codec.format(BigDecimal("0.5005")).text)
        assertEquals("0.50050001", codec.format(BigDecimal("0.50050001")).text)
        val maximum = QuantityFractions(FractionOptions(10000))
        assertEquals("1/10000", maximum.format(BigDecimal("0.0001")).text)
        assertEquals("1 1/20", QuantityFractions(FractionOptions(20)).format(BigDecimal("1.05")).text)
    }

    @Test fun propertyArbitraryDecimalsEitherRoundTripOrMeetBothErrorBounds() {
        val random = Random(4003)
        repeat(2000) {
            val value = BigDecimal(random.nextInt(1, 100000000)).movePointLeft(6)
            val maximum = random.nextInt(1, 129)
            val formatter = QuantityFractions(FractionOptions(maximum))
            val display = formatter.format(value)
            val parsed = formatter.parse(display.text)!!
            val error = parsed.value.subtract(value).abs()
            if (!display.approximate) {
                equal(value.toPlainString(), parsed.value)
            } else {
                assertTrue(display.fraction)
                assertTrue(error <= BigDecimal("0.0005"))
                val part = value.remainder(BigDecimal.ONE)
                assertTrue(error <= part.multiply(BigDecimal("0.005")))
            }
        }
    }

    @Test fun parseApproximationSurvivesDecimalFallback() {
        val parsed = codec.parse("1/99991")!!
        val display = codec.format(parsed)
        assertFalse(display.fraction)
        assertTrue(display.approximate)
        assertEquals("≈${parsed.value.toPlainString()}", display.text)
        val markedExact = codec.parse("≈0.5")!!
        assertEquals("≈½", codec.format(markedExact).text)
        assertEquals("½", codec.format(codec.parse("1/2")!!).text)
        assertEquals("0.5", codec.format(markedExact, field = NumericField.PRICE).text)
        assertEquals("≈⅓", codec.format(codec.parse("1/3")!!).text)
        assertEquals("0.2", codec.format(ParsedQuantity(BigDecimal("0.2"), false), field = NumericField.PRICE).text)
    }
}
