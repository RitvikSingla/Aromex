package com.humblesolutions.aromex.ui.entities

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The balance column has to say which *way* it runs, not just how much.
 *
 * It used to strip the minus sign, so "you owe them a thousand" and "they owe you a thousand"
 * printed as the same characters. A real statement in the dev project crossed from +$400 to −$1,000
 * across a single $1,400 purchase and gave the reader no way to tell.
 */
class StatementBalanceSignTest {

    private val cad = "CAD"

    @Test
    fun `money owed to the shop is marked positive`() {
        val text = signedBalance("400.00", cad)
        assertTrue(text.startsWith("+"), "expected a leading + in \"$text\"")
        assertTrue(text.contains("400"), text)
    }

    @Test
    fun `money the shop owes is marked negative`() {
        val text = signedBalance("-1000.00", cad)
        assertTrue(text.startsWith("−"), "expected a leading minus sign in \"$text\"")
        assertTrue(text.contains("1,000") || text.contains("1000"), text)
    }

    /** The magnitude is identical either way; only the sign distinguishes them. */
    @Test
    fun `the two directions differ only by their sign`() {
        val owed = signedBalance("1000.00", cad)
        val owing = signedBalance("-1000.00", cad)
        assertEquals(owed.removePrefix("+"), owing.removePrefix("−"))
        assertTrue(owed != owing, "the two directions must not render identically")
    }

    /** Settled has no direction, so it carries no sign — a bare figure. */
    @Test
    fun `a zero balance is unsigned`() {
        val text = signedBalance("0.00", cad)
        assertTrue(!text.startsWith("+") && !text.startsWith("−"), "expected no sign in \"$text\"")
    }

    /** HL can return a negative zero; it means settled, not "you owe nothing-and-a-bit". */
    @Test
    fun `negative zero is settled, not owing`() {
        assertEquals(signedBalance("0.00", cad), signedBalance("-0.00", cad))
    }
}
