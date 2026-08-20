package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.util.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyTest {

    @Test
    fun signOf_readsSignWithoutFloat() {
        assertEquals(1, Money.signOf("500.00"))
        assertEquals(1, Money.signOf("+5"))
        assertEquals(-1, Money.signOf("-500.00"))
        assertEquals(0, Money.signOf("0"))
        assertEquals(0, Money.signOf("0.00"))
        assertEquals(0, Money.signOf("-0.0"))
        assertEquals(0, Money.signOf("garbage")) // unparseable → 0 (safe)
    }

    @Test
    fun isZero() {
        assertTrue(Money.isZero("0"))
        assertTrue(Money.isZero("0.00"))
        assertTrue(Money.isZero("-0.0"))
        assertFalse(Money.isZero("5"))
        assertFalse(Money.isZero("-0.01"))
    }

    @Test
    fun isValidPositiveDecimal() {
        assertTrue(Money.isValidPositiveDecimal("5"))
        assertTrue(Money.isValidPositiveDecimal("500.00"))
        assertTrue(Money.isValidPositiveDecimal(" 12.5 ")) // trimmed
        assertFalse(Money.isValidPositiveDecimal("0"))
        assertFalse(Money.isValidPositiveDecimal("0.00"))
        assertFalse(Money.isValidPositiveDecimal("-5")) // signed not allowed
        assertFalse(Money.isValidPositiveDecimal("+5")) // signed not allowed
        assertFalse(Money.isValidPositiveDecimal("abc"))
        assertFalse(Money.isValidPositiveDecimal(""))
    }

    @Test
    fun abs_dropsSign() {
        assertEquals("500.00", Money.abs("-500.00"))
        assertEquals("500", Money.abs("500"))
        assertEquals("5", Money.abs("+5"))
    }

    @Test
    fun add_wholeAndFractional() {
        assertEquals("1400", Money.add("700", "700"))
        assertEquals("4.00", Money.add("1.50", "2.50")) // fractional carry into integer
        assertEquals("2400.50", Money.add("1400.50", "1000"))
        assertEquals("10.05", Money.add("10", "0.05"))
        assertEquals("0", Money.add("0", "0"))
        assertEquals("5", Money.add("5", "garbage")) // invalid entry counts as 0
    }

    @Test
    fun sum_addsBatchWithoutFloatDrift() {
        assertEquals("2400", Money.sum(listOf("700", "700", "1000")))
        assertEquals("2400.75", Money.sum(listOf("700.25", "700.25", "1000.25")))
        assertEquals("0", Money.sum(emptyList()))
        // 0.1 + 0.2 == 0.3 exactly (no binary-float rounding)
        assertEquals("0.3", Money.sum(listOf("0.1", "0.2")))
    }

    @Test
    fun compare_and_lessThanOrEqual() {
        assertTrue(Money.compare("2400", "2400") == 0)
        assertTrue(Money.compare("999", "1000") < 0) // shorter integer part is smaller
        assertTrue(Money.compare("1000", "999") > 0)
        assertTrue(Money.compare("10.5", "10.50") == 0) // trailing zero irrelevant
        assertTrue(Money.compare("10.5", "10.05") > 0)
        assertTrue(Money.lessThanOrEqual("1000", "2400"))
        assertTrue(Money.lessThanOrEqual("2400", "2400"))
        assertFalse(Money.lessThanOrEqual("2400.01", "2400"))
    }

    @Test
    fun divide_exactAndRepeatingHalfUp() {
        // The BC tax-inclusive case: 700.00 ÷ 1.12 == 625.00 exactly.
        assertEquals("625.00", Money.divide("700.00", "1.12"))
        // Divisor of exactly 1 → the amount, rounded to scale.
        assertEquals("700.00", Money.divide("700.00", "1"))
        assertEquals("12.35", Money.divide("12.345", "1"))
        // Repeating decimals, half-up to 2 places.
        assertEquals("0.33", Money.divide("1", "3")) // 0.333… → 0.33
        assertEquals("0.67", Money.divide("2", "3")) // 0.666… → 0.67
        assertEquals("0.00", Money.divide("0.01", "3")) // 0.00333… → 0.00
        // Half-up boundary: 1 ÷ 8 == 0.125 → 0.13.
        assertEquals("0.13", Money.divide("1", "8"))
        // A fractional divisor with a fractional dividend.
        assertEquals("100.00", Money.divide("105.00", "1.05"))
        // Arbitrary scale.
        assertEquals("0.3333", Money.divide("1", "3", scale = 4))
    }

    @Test
    fun divide_scaleZeroRoundsHalfUp() {
        assertEquals("2", Money.divide("5", "3", scale = 0)) // 1.666… → 2
        assertEquals("2", Money.divide("3", "2", scale = 0)) // 1.5 → half-up → 2
        assertEquals("1", Money.divide("4", "3", scale = 0)) // 1.333… → 1
    }

    @Test
    fun divide_byZeroThrows() {
        assertFailsWith<IllegalArgumentException> { Money.divide("700.00", "0") }
        assertFailsWith<IllegalArgumentException> { Money.divide("700.00", "0.00") }
    }
}
