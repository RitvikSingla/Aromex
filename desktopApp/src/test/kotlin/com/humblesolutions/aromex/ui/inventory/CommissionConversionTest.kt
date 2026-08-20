package com.humblesolutions.aromex.ui.inventory

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The commission-rule form converts between the percent a user types (`2` for 2%) and the stored
 * fraction (`0.02`) with pure decimal-shift string maths — never `Money.multiplyRate`, whose
 * 2-dp rounding would corrupt a fractional-percent rate (ticket #97). These are the inverse pair
 * plus the numeric input filter.
 */
class CommissionConversionTest {

    @Test
    fun percentToFraction_shiftsTwoPlacesLeft() {
        assertEquals("0.02", percentToFraction("2"))
        assertEquals("0.025", percentToFraction("2.5"))   // fractional percent keeps precision
        assertEquals("0.125", percentToFraction("12.5"))
        assertEquals("0.005", percentToFraction("0.5"))
        assertEquals("0.1", percentToFraction("10"))
        assertEquals("1", percentToFraction("100"))       // 100% == fraction 1
        assertEquals("", percentToFraction(""))
    }

    @Test
    fun fractionToPercent_isTheInverse() {
        assertEquals("2", fractionToPercent("0.02"))
        assertEquals("2.5", fractionToPercent("0.025"))
        assertEquals("12.5", fractionToPercent("0.125"))
        assertEquals("0.5", fractionToPercent("0.005"))
        assertEquals("10", fractionToPercent("0.1"))
        assertEquals("100", fractionToPercent("1"))
    }

    @Test
    fun roundTrip_preservesValue() {
        for (p in listOf("1", "2", "2.5", "3.75", "10", "12.5", "50", "100", "0.5")) {
            assertEquals(p, fractionToPercent(percentToFraction(p)), "round-trip failed for $p%")
        }
    }

    @Test
    fun sanitizeDecimalInput_keepsDigitsAndOneDot() {
        assertEquals("12.34", sanitizeDecimalInput("12.34"))
        assertEquals("12.34", sanitizeDecimalInput("1a2.3\$4"))   // strips letters/symbols
        assertEquals("12.34", sanitizeDecimalInput("12.3.4"))     // only the first dot survives
        assertEquals("5", sanitizeDecimalInput("5"))
        assertEquals("", sanitizeDecimalInput("abc"))
    }

    @Test
    fun percentLabel_formatsFractionAsPercent() {
        assertEquals("2%", percentLabel("0.02"))
        assertEquals("2.5%", percentLabel("0.025"))
        assertEquals("10%", percentLabel("0.1"))
    }
}
