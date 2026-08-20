package com.humblesolutions.aromex.sales

import com.humblesolutions.aromex.util.Money
import kotlin.test.Test
import kotlin.test.assertEquals

/** The decimal-string math added for Sales (ticket #61): [Money.subtract] + [Money.multiplyRate]. */
class MoneySalesTest {

    @Test
    fun subtract_basic() {
        assertEquals("684.00", Money.subtract("704.00", "20.00"))
        assertEquals("99.50", Money.subtract("100", "0.50"))
        assertEquals("999.00", Money.subtract("1000.00", "1"))
    }

    @Test
    fun subtract_clampsAtZero_whenBLargerOrEqual() {
        assertEquals("0", Money.subtract("5", "5"))
        assertEquals("0", Money.subtract("3", "5"))
    }

    @Test
    fun multiplyRate_roundsHalfUpToTwoDp() {
        assertEquals("35.20", Money.multiplyRate("704.00", "0.05"))
        assertEquals("13.00", Money.multiplyRate("100", "0.13"))
        assertEquals("0.47", Money.multiplyRate("3.00", "0.155")) // 0.465 → half-up
        assertEquals("0.00", Money.multiplyRate("0", "0.05"))
    }
}
