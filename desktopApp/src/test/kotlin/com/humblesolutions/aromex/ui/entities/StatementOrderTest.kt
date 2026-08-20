package com.humblesolutions.aromex.ui.entities

import com.humblesolutions.aromex.model.StatementEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Statement ordering, pinned against real data from the dev project.
 *
 * The bug these exist to prevent: HL stores an accounting date as a calendar date, so every event
 * on the same day carries an identical date string. `sortedByDescending { it.date }` is stable, so
 * it reversed the *days* and left each day's events ascending. On a real party this printed
 *
 *     Payment for INV-000002      ← 9:18pm
 *     Refund: mistake             ← 9:21pm
 *
 * under "Newest first" — reading upward, the refund looked older than the payment it reversed.
 */
class StatementOrderTest {

    private fun event(date: String, label: String) = StatementEvent(
        date = date,
        description = label,
        postingType = "SALE",
        billed = null,
        cashIn = null,
        cashOut = null,
        balance = "0",
        recordedAt = null,
        transactionIds = listOf(label),
    )

    /**
     * Ansh Bajaj's actual statement, oldest first as HL returns it. Two events share 07-31 21:xx and
     * three more share 07-31 morning, so every same-day tie in the real data is represented.
     */
    private val ansh = listOf(
        event("2026-07-16", "Friday Payback"),          // backdated; business date 16 Jul
        event("2026-07-31", "Opening balance"),         // 10:47
        event("2026-07-31", "Inventory purchase"),      // 11:05
        event("2026-07-31", "Sale (Aromex)"),           // 11:30
        event("2026-08-01", "Payment for INV-000002"),  // 21:18 (dated 08-01 pre-timezone-fix)
        event("2026-08-01", "Refund: mistake"),         // 21:21
    )

    @Test
    fun `newest first puts the later of two same-day events on top`() {
        val labels = orderStatementEvents(ansh, ascending = false).map { it.description }

        assertEquals(
            listOf(
                "Refund: mistake",           // 21:21 — the newest thing that happened
                "Payment for INV-000002",    // 21:18
                "Sale (Aromex)",             // 11:30
                "Inventory purchase",        // 11:05
                "Opening balance",           // 10:47
                "Friday Payback",            // 16 Jul
            ),
            labels,
        )
    }

    @Test
    fun `oldest first is the ledger's own order, untouched`() {
        assertEquals(ansh.map { it.description }, orderStatementEvents(ansh, ascending = true).map { it.description })
    }

    /** The two directions must be exact mirrors, or one of them is lying about what came first. */
    @Test
    fun `the two directions are exact reverses of each other`() {
        val up = orderStatementEvents(ansh, ascending = true).map { it.description }
        val down = orderStatementEvents(ansh, ascending = false).map { it.description }
        assertEquals(up.reversed(), down)
    }

    /**
     * A page that arrives out of date order is still sorted by day, and same-day events keep the
     * order HL gave them — the only thing that knows which came first.
     */
    @Test
    fun `days are ordered even if the source is not, and same-day order is preserved`() {
        val jumbled = listOf(
            event("2026-08-01", "later same day"),
            event("2026-07-16", "oldest"),
            event("2026-08-01", "latest same day"),
        )
        assertEquals(
            listOf("oldest", "later same day", "latest same day"),
            orderStatementEvents(jumbled, ascending = true).map { it.description },
        )
        assertEquals(
            listOf("latest same day", "later same day", "oldest"),
            orderStatementEvents(jumbled, ascending = false).map { it.description },
        )
    }

    @Test
    fun `an empty statement orders to nothing in either direction`() {
        assertEquals(emptyList(), orderStatementEvents(emptyList(), ascending = true))
        assertEquals(emptyList(), orderStatementEvents(emptyList(), ascending = false))
    }
}
