package com.humblesolutions.aromex.statement

import com.humblesolutions.aromex.model.LedgerRow
import com.humblesolutions.aromex.model.StatementEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The statement shows money, not double entry — these pin the collapse that makes that true.
 *
 * The numbers are the real ones from the dev project (INV-000006 paid in full at the counter,
 * INV-000008 taken entirely on credit), so a regression here is a regression a shopkeeper would see.
 */
class StatementEventTest {

    private fun charge(
        source: String,
        amount: String,
        balance: String,
        type: String = "SALE",
        desc: String = "Sale (Aromex)",
        date: String = "2026-07-31",
        txn: String = source,
    ) = LedgerRow(
        date = date, description = desc, postingType = type,
        debit = amount, credit = null, balance = balance, transactionId = txn, sourceId = source,
    )

    private fun cash(
        source: String,
        amount: String,
        balance: String,
        type: String = "PAYMENT",
        desc: String = "Payment for INV-000006",
        date: String = "2026-07-31",
        txn: String = source,
    ) = LedgerRow(
        date = date, description = desc, postingType = type,
        debit = null, credit = amount, balance = balance, transactionId = txn, sourceId = source,
    )

    @Test
    fun `a sale paid at the counter becomes one row showing the cash, with the sale value alongside`() {
        val events = StatementEvent.from(
            listOf(
                charge("sale_A:sale", "918", "918"),
                cash("sale_A:payment_cash", "918", "0"),
            ),
        )

        assertEquals(1, events.size, "the two legs are one event")
        val e = events.single()
        assertEquals("918", e.cashIn, "the money column shows what actually came in")
        assertEquals("918", e.billed, "the sale value rides alongside as context")
        assertNull(e.cashOut)
        assertEquals("0", e.balance, "balance is HL's, after the whole event")
        assertEquals("Sale (Aromex)", e.description, "the charge anchors the label, not the payment")
        assertEquals(listOf("sale_A:sale", "sale_A:payment_cash"), e.transactionIds)
    }

    @Test
    fun `a sale taken on credit shows no money and says so`() {
        val e = StatementEvent.from(listOf(charge("sale_B:sale", "408", "408"))).single()

        assertNull(e.cashIn, "nothing came in")
        assertNull(e.cashOut)
        assertTrue(e.movedNoCash)
        assertEquals("408", e.billed)
        assertEquals("408", e.balance, "the balance is what explains the row")
    }

    /** The case in the screenshot: billed more than was collected, the rest left owing. */
    @Test
    fun `a part-paid sale shows the cash taken, not the amount billed`() {
        val e = StatementEvent.from(
            listOf(
                charge("sale_C:sale", "1111", "1111"),
                cash("sale_C:payment_cash", "1000", "111"),
            ),
        ).single()

        assertEquals("1000", e.cashIn, "only the money that moved")
        assertEquals("1111", e.billed)
        assertEquals("111", e.balance, "…and the balance shows what is still owed")
    }

    @Test
    fun `split tender sums into one money figure`() {
        val e = StatementEvent.from(
            listOf(
                charge("sale_D:sale", "1000", "1000"),
                cash("sale_D:payment_cash", "400", "600"),
                cash("sale_D:payment_bank", "600", "0"),
            ),
        ).single()

        assertEquals("1000", e.cashIn, "cash + bank, one number")
        assertEquals("0", e.balance)
    }

    @Test
    fun `a standalone payment stays its own event with no billed figure`() {
        val e = StatementEvent.from(
            listOf(cash("money_X", "111", "0", desc = "Payment from Ansh Bajaj")),
        ).single()

        assertEquals("111", e.cashIn)
        assertNull(e.billed, "a payment on its own was never billed here")
        assertEquals("Payment from Ansh Bajaj", e.description)
    }

    @Test
    fun `a purchase settles the same way, as money out`() {
        val e = StatementEvent.from(
            listOf(
                charge("purchase_E:purchase", "2000", "2000", type = "PURCHASE", desc = "Purchase (Aromex)"),
                LedgerRow(
                    date = "2026-07-31", description = "Payout", postingType = "PAYOUT",
                    debit = "1500", credit = null, balance = "500",
                    transactionId = "purchase_E:payout_cash", sourceId = "purchase_E:payout_cash",
                ),
            ),
        ).single()

        assertEquals("1500", e.cashOut)
        assertNull(e.cashIn)
        assertEquals("2000", e.billed)
        assertEquals("500", e.balance)
    }

    /**
     * Two sales synced concurrently can interleave in the ledger. Grouping keys on the sourceId, not
     * on adjacency, so the pairing survives it — the reason this isn't done by "nearest preceding
     * charge".
     */
    @Test
    fun `interleaved legs from two sales still pair correctly`() {
        val events = StatementEvent.from(
            listOf(
                charge("sale_F:sale", "100", "100", txn = "t1"),
                charge("sale_G:sale", "200", "300", txn = "t2"),
                cash("sale_F:payment_cash", "100", "200", txn = "t3"),
                cash("sale_G:payment_cash", "200", "0", txn = "t4"),
            ),
        )

        assertEquals(2, events.size)
        val f = events.first()
        assertEquals("100", f.billed)
        assertEquals("100", f.cashIn, "F's cash, not G's")
        assertEquals("200", f.balance, "the balance after F's own last leg")
        val g = events.last()
        assertEquals("200", g.billed)
        assertEquals("200", g.cashIn)
        assertEquals("0", g.balance)
    }

    /**
     * The sourceId join is best-effort — HL's `/ledger` doesn't return it. An unresolved row must
     * render exactly as it does today rather than be folded into a neighbour's event.
     */
    @Test
    fun `rows with no sourceId are never merged`() {
        val events = StatementEvent.from(
            listOf(
                LedgerRow("2026-07-31", "Sale (Aromex)", "SALE", "918", null, "918", "t1"),
                LedgerRow("2026-07-31", "Payment for INV-000006", "PAYMENT", null, "918", "0", "t2"),
            ),
        )

        assertEquals(2, events.size, "unknown provenance must not be guessed at")
        assertEquals("918", events[0].billed)
        assertEquals("918", events[1].cashIn)
    }

    @Test
    fun `an empty statement yields no events`() {
        assertEquals(emptyList(), StatementEvent.from(emptyList()))
    }

    /**
     * The clock comes from the charge, not from whichever leg posted last — a sale reads as when it
     * was rung up.
     */
    @Test
    fun `the event's recorded time is the charge's own`() {
        val e = StatementEvent.from(
            listOf(
                charge("sale_H:sale", "918", "918").copy(recordedAt = "2026-07-31T21:45:12.000Z"),
                cash("sale_H:payment_cash", "918", "0").copy(recordedAt = "2026-07-31T21:45:19.000Z"),
            ),
        ).single()

        assertEquals("2026-07-31T21:45:12.000Z", e.recordedAt)
    }

    /** A leg with no resolvable time must not leave the event timeless if another leg has one. */
    @Test
    fun `falls back to any leg that has a time`() {
        val e = StatementEvent.from(
            listOf(
                charge("sale_I:sale", "918", "918"),
                cash("sale_I:payment_cash", "918", "0").copy(recordedAt = "2026-07-31T21:45:19.000Z"),
            ),
        ).single()

        assertEquals("2026-07-31T21:45:19.000Z", e.recordedAt)
        assertNull(StatementEvent.from(listOf(charge("sale_J:sale", "1", "1"))).single().recordedAt)
    }
}
