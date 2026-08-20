package com.humblesolutions.aromex.ui.entities

import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.StatementEvent
import com.humblesolutions.aromex.model.LedgerRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The collections view: narrow the party list to who owes money, ordered by how much.
 *
 * This is the half of #86 worth building. The other half — invoice-level aging — was dropped
 * because payments recorded on the Money screen deliberately settle a party's overall balance
 * rather than a named invoice, so invoice ages drift for exactly the customers who pay.
 */
class EntityListFilterTest {

    private val rajesh = Entity(id = "e1", name = "Rajesh Traders", roles = setOf(EntityRole.CUSTOMER))
    private val priya = Entity(id = "e2", name = "priya mobiles", roles = setOf(EntityRole.CUSTOMER))
    private val supplier = Entity(id = "e3", name = "Acme Supply", roles = setOf(EntityRole.SUPPLIER))
    private val settled = Entity(id = "e4", name = "Zed Ltd", roles = setOf(EntityRole.CUSTOMER))
    private val unknown = Entity(id = "e5", name = "New Party", roles = setOf(EntityRole.CUSTOMER))

    private val all = listOf(rajesh, priya, supplier, settled, unknown)

    private val balances = mapOf(
        "e1" to EntityBalance("500.00", BalanceDirection.RECEIVABLE),   // owes us
        "e2" to EntityBalance("1200.00", BalanceDirection.RECEIVABLE),  // owes us more
        "e3" to EntityBalance("8300.00", BalanceDirection.CREDIT),      // we owe them
        "e4" to EntityBalance("0", BalanceDirection.SETTLED),
        // e5 deliberately absent — balance not loaded yet
    )

    private fun run(
        query: String = "",
        filter: EntitiesFilter = EntitiesFilter.ALL,
        balanceFilter: BalanceFilter = BalanceFilter.ALL,
        sortBy: EntitySort = EntitySort.NAME,
    ) = filterAndSortEntities(all, balances, query, filter, balanceFilter, sortBy).map { it.entity.id }

    @Test
    fun owesMe_keepsOnlyPartiesWithMoneyOwedToUs() {
        assertEquals(setOf("e1", "e2"), run(balanceFilter = BalanceFilter.OWES_ME).toSet())
    }

    @Test
    fun iOwe_keepsOnlyPartiesWeOwe() {
        assertEquals(listOf("e3"), run(balanceFilter = BalanceFilter.I_OWE))
    }

    /** A settled party is in neither list — the filter exists to shorten this to people worth chasing. */
    @Test
    fun settledAndUnknownParties_appearInNeitherDirection() {
        val owed = run(balanceFilter = BalanceFilter.OWES_ME)
        val owing = run(balanceFilter = BalanceFilter.I_OWE)
        assertTrue("e4" !in owed && "e4" !in owing, "settled party should be filtered out")
        assertTrue("e5" !in owed && "e5" !in owing, "party with no balance loaded should be filtered out")
    }

    /** Biggest debt first, whichever direction — that's the whole point of the sort. */
    @Test
    fun sortByBalance_putsTheLargestAmountFirst() {
        assertEquals(listOf("e3", "e2", "e1", "e4", "e5"), run(sortBy = EntitySort.BALANCE))
    }

    /**
     * A party whose balance hasn't loaded sorts last, not as zero. Unknown is not settled, and
     * showing it mid-list would imply we know something we don't.
     */
    @Test
    fun sortByBalance_putsUnknownBalancesLast() {
        assertEquals("e5", run(sortBy = EntitySort.BALANCE).last())
    }

    @Test
    fun sortByName_isCaseInsensitive() {
        // "priya mobiles" lowercase must not sort after every capitalised name.
        assertEquals(listOf("e3", "e5", "e2", "e1", "e4"), run(sortBy = EntitySort.NAME))
    }

    /** Role and balance are independent, so "customers who owe me" is answerable. */
    @Test
    fun roleAndBalanceFiltersCompose() {
        assertEquals(
            setOf("e1", "e2"),
            run(filter = EntitiesFilter.CUSTOMER, balanceFilter = BalanceFilter.OWES_ME).toSet(),
        )
        // The supplier we owe is a supplier, so this pairing is legitimately empty.
        assertTrue(
            run(filter = EntitiesFilter.CUSTOMER, balanceFilter = BalanceFilter.I_OWE).isEmpty(),
        )
    }

    @Test
    fun searchComposesWithBothFilters() {
        assertEquals(
            listOf("e2"),
            run(query = "priya", balanceFilter = BalanceFilter.OWES_ME),
        )
        assertTrue(run(query = "nobody", balanceFilter = BalanceFilter.OWES_ME).isEmpty())
    }

    @Test
    fun noFilters_returnsEveryone() {
        assertEquals(all.size, run().size)
    }
}

/**
 * Green and red on a statement mean cash actually moved.
 *
 * The case that forced this: a $1,111 sale to a customer holding $1,000 of credit moved only $111
 * of real money, but the sale row was rendering a green +$1,111 — claiming eleven hundred came in.
 */
class StatementDirectionTest {

    @Test
    fun onlyRealMovementsAreMoney() {
        assertEquals("100", eventFor("PAYMENT").cashIn)
        assertEquals("100", eventFor("PAYOUT").cashOut)
        assertEquals("100", eventFor("EXPENSE").cashOut)
        assertEquals("100", eventFor("REFUND").cashOut)
    }

    /** A sale bills someone; it doesn't collect from them. Same for a purchase. */
    @Test
    fun chargesAreNotMoney() {
        // The statement says so by putting the value beside the row instead of in the money column.
        listOf("SALE", "PURCHASE", "JOURNAL").forEach { type ->
            val e = eventFor(type)
            assertTrue(e.isCharge, "$type should carry a billed value")
            assertTrue(e.movedNoCash, "$type moved no cash on its own")
        }
        assertFalse(eventFor("PAYMENT").isCharge)
    }

    @Test
    fun postingTypeMatchingIsCaseInsensitive() {
        assertTrue(eventFor("sale").isCharge)
        assertEquals("100", eventFor("payment").cashIn)
    }

    /** An unrecognised type is never counted as cash — that would overstate what was collected. */
    @Test
    fun unknownTypesAreNeverCash() {
        val unknown = eventFor("SOMETHING_NEW")
        assertTrue(unknown.movedNoCash)
        assertTrue(unknown.isCharge)
    }

    /** One $100 row of the given posting type, folded the way the statement folds it. */
    private fun eventFor(postingType: String) = StatementEvent.from(
        listOf(
            LedgerRow(
                date = "2026-07-31", description = "x", postingType = postingType,
                debit = null, credit = "100", balance = "0", transactionId = "t1",
            ),
        ),
    ).single()
}
