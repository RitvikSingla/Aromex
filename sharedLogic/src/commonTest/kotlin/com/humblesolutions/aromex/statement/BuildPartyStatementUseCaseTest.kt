package com.humblesolutions.aromex.statement

import com.humblesolutions.aromex.model.AccountStatement
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.LedgerRow
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.repository.EntityLedgerRepository
import com.humblesolutions.aromex.usecase.BuildPartyStatementUseCase
import com.humblesolutions.aromex.usecase.StatementTooLargeException
import com.humblesolutions.aromex.util.Money
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val VIEW = Permissions(profiles = PermissionLevel.VIEW)
private val ENTITY = Entity(id = "e1", name = "Rajesh")

/** A programmable ledger: [range] answers the paging calls, [opening] the opening-balance call. */
private class FakeLedger(
    private val opening: String? = null,
    private val range: (page: Int) -> AccountStatement?,
) : EntityLedgerRepository {
    var openingCalls = 0
    var rangePages = 0

    override suspend fun getStatement(
        externalId: String,
        from: String?,
        to: String?,
        page: Int,
        limit: Int,
    ): AccountStatement? {
        // The opening-balance probe: from=null, limit=1.
        if (from == null && limit == 1) {
            openingCalls++
            return opening?.let { stmt(closing = it, rows = emptyList(), hasMore = false, page = 1) }
        }
        rangePages = maxOf(rangePages, page)
        return range(page)
    }

    override suspend fun getBalances(): Map<String, EntityBalance> = emptyMap()
    override suspend fun getBalance(externalId: String): EntityBalance? = null
    override fun close() {}
}

private fun stmt(closing: String, rows: List<LedgerRow>, hasMore: Boolean, page: Int) =
    AccountStatement("a1", "Rajesh", closing, rows, page, rows.size, hasMore)

/**
 * A ledger row. The posting type follows the side HL posted — a debit on an AR account is a charge,
 * a credit is money received — because the statement now classifies by posting type rather than by
 * which column happens to be filled. Transaction ids must be unique per row: rows sharing one are
 * legs of a single transaction and fold into one event.
 */
private fun row(
    date: String,
    debit: String?,
    credit: String?,
    balance: String,
    txn: String = date,
    postingType: String = if (debit != null) "SALE" else "PAYMENT",
) = LedgerRow(date, "desc", postingType, debit, credit, balance, txn)

private fun onePage(closing: String, rows: List<LedgerRow>, opening: String? = null) =
    FakeLedger(opening = opening) { page -> if (page == 1) stmt(closing, rows, false, 1) else null }

/** Sum of the four aging buckets, as a decimal string. */
private fun bucketSum(buckets: List<com.humblesolutions.aromex.model.AgingBucket>): String =
    Money.sum(buckets.map { it.amount })

class BuildPartyStatementUseCaseTest {

    @Test
    fun `permission gate — profiles NONE is denied`() = runTest {
        val uc = BuildPartyStatementUseCase(onePage("0", emptyList()))
        assertFailsWith<PermissionDeniedException> {
            uc.execute(ENTITY, Permissions(profiles = PermissionLevel.NONE), "2026-01-01", "2026-03-31", false)
        }
    }

    @Test
    fun `paging loop concatenates every page`() = runTest {
        val page1 = (1..100).map { row("2026-02-0${(it % 9) + 1}", "10", null, "$it", txn = "p1-$it") }
        val page2 = (1..40).map { row("2026-02-1${(it % 9) + 1}", "10", null, "${100 + it}", txn = "p2-$it") }
        val ledger = FakeLedger(opening = "0") { page ->
            when (page) {
                1 -> stmt("1400", page1, hasMore = true, page = 1)
                2 -> stmt("1400", page2, hasMore = false, page = 2)
                else -> null
            }
        }
        val doc = BuildPartyStatementUseCase(ledger).execute(ENTITY, VIEW, "2026-02-01", "2026-02-28", false)
        assertEquals(140, doc.rows.size)
        assertEquals(2, ledger.rangePages)
        // 140 debit rows of 10 each.
        assertEquals(0, Money.compare(doc.totalDebits, "1400.00"))
    }

    @Test
    fun `opening balance comes from the day-before call`() = runTest {
        val ledger = onePage("900", listOf(row("2026-02-10", "0", "0", "900")), opening = "1200")
        val doc = BuildPartyStatementUseCase(ledger).execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", false)
        assertEquals(1, ledger.openingCalls)
        assertEquals(0, Money.compare(doc.openingBalance, "1200.00"))
    }

    @Test
    fun `all-time statement opens at zero without an opening call`() = runTest {
        val ledger = onePage("500", listOf(row("2026-02-10", "500", null, "500")), opening = "9999")
        val doc = BuildPartyStatementUseCase(ledger).execute(ENTITY, VIEW, fromIso = null, toIso = "2026-03-31", includeNotes = false)
        assertEquals(0, ledger.openingCalls)
        assertEquals(0, Money.compare(doc.openingBalance, "0.00"))
    }

    @Test
    fun `opening plus debits minus credits equals closing`() = runTest {
        val rows = listOf(
            row("2026-02-12", debit = "400", credit = null, balance = "1600"),
            row("2026-02-20", debit = null, credit = "700", balance = "900"),
        )
        val doc = BuildPartyStatementUseCase(onePage("900", rows, opening = "1200"))
            .execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", false)
        // 1200 + 400 − 700 == 900
        val lhs = Money.subtract(Money.add(doc.openingBalance, doc.totalDebits), doc.totalCredits)
        assertEquals(0, Money.compare(lhs, doc.closingBalance))
    }

    @Test
    fun `aging buckets sum to closing — payment partly covers the oldest charge`() = runTest {
        // opening 1200 (old) + 400 charge − 700 payment = 900 closing.
        val rows = listOf(
            row("2026-02-12", "400", null, "1600"),
            row("2026-02-20", null, "700", "900"),
        )
        val doc = BuildPartyStatementUseCase(onePage("900", rows, opening = "1200"))
            .execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", false)
        assertEquals(4, doc.agingBuckets.size)
        assertEquals(0, Money.compare(bucketSum(doc.agingBuckets), "900.00"))
        // 700 paid off the top of the 1200 opening → 500 of it remains outstanding.
        assertEquals(0, Money.compare(doc.agingBuckets.first { it.label == BuildPartyStatementUseCase.LABEL_61_90 }.amount, "500.00"))
        assertEquals(0, Money.compare(doc.agingBuckets.first { it.label == BuildPartyStatementUseCase.LABEL_31_60 }.amount, "400.00"))
    }

    @Test
    fun `aging derived from movements — an unapplied credit reduces the bucket, not receivables`() = runTest {
        // A 1000 charge, then a 300 payment left unapplied to any invoice → 700 outstanding.
        val rows = listOf(
            row("2026-01-15", "1000", null, "1000"),
            row("2026-02-01", null, "300", "700"),
        )
        val doc = BuildPartyStatementUseCase(onePage("700", rows, opening = "0"))
            .execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", false)
        assertEquals(0, Money.compare(bucketSum(doc.agingBuckets), "700.00"))
    }

    @Test
    fun `party in credit shows no aging block`() = runTest {
        val rows = listOf(row("2026-02-01", null, "300", "-300"))
        val doc = BuildPartyStatementUseCase(onePage("-300", rows, opening = "0"))
            .execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", false)
        assertTrue(doc.agingBuckets.isEmpty())
    }

    @Test
    fun `range with no activity — opening equals closing, no rows`() = runTest {
        val doc = BuildPartyStatementUseCase(onePage("1200", emptyList(), opening = "1200"))
            .execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", false)
        assertTrue(doc.rows.isEmpty())
        assertEquals(0, Money.compare(doc.openingBalance, doc.closingBalance))
        // The opening balance itself is outstanding, so it still ages.
        assertEquals(0, Money.compare(bucketSum(doc.agingBuckets), "1200.00"))
    }

    @Test
    fun `notes appear only when the toggle is on`() = runTest {
        val rows = listOf(row("2026-02-10", "500", null, "500", txn = "t9"))
        val notes = mapOf("t9" to "paid by cheque")
        val off = BuildPartyStatementUseCase(onePage("500", rows, opening = "0"))
            .execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", includeNotes = false, noteByTransactionId = notes)
        assertEquals(null, off.rows.single().note)
        val on = BuildPartyStatementUseCase(onePage("500", rows, opening = "0"))
            .execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", includeNotes = true, noteByTransactionId = notes)
        assertEquals("paid by cheque", on.rows.single().note)
    }

    @Test
    fun `over the row cap throws rather than truncating`() = runTest {
        val fullPage = (1..100).map { row("2026-02-01", "1", null, "$it") }
        val ledger = FakeLedger(opening = "0") { page ->
            if (page <= 21) stmt("0", fullPage, hasMore = page < 21, page = page) else null
        }
        assertFailsWith<StatementTooLargeException> {
            BuildPartyStatementUseCase(ledger).execute(ENTITY, VIEW, "2026-01-01", "2026-03-31", false)
        }
    }
}
