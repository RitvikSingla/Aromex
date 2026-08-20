package com.humblesolutions.aromex.ui.money

import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.MoneyAccountRef
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.model.MoneyEntryInput
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityLedgerRepository
import com.humblesolutions.aromex.repository.EntityRepository
import com.humblesolutions.aromex.repository.MoneyEntryRepository
import com.humblesolutions.aromex.model.AccountStatement
import com.humblesolutions.aromex.usecase.MoneyEntryError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MoneyViewModelTest {

    // ── fakes ────────────────────────────────────────────────────────────────

    private class FakeMoneyRepo : MoneyEntryRepository {
        val entriesFlow = MutableStateFlow<List<MoneyEntry>>(emptyList())
        val recorded = mutableListOf<MoneyEntryInput>()
        val reversed = mutableListOf<String>()
        var failWith: Throwable? = null

        override suspend fun recordEntry(input: MoneyEntryInput): String {
            failWith?.let { throw it }
            recorded += input
            return "entry-${recorded.size}"
        }

        override fun observeRecentEntries(limit: Int): Flow<List<MoneyEntry>> = entriesFlow

        override suspend fun reverseEntry(entryId: String): String {
            failWith?.let { throw it }
            reversed += entryId
            return "rev-$entryId"
        }
    }

    private class FakeEntityRepo(parties: List<Entity>) : EntityRepository {
        val flow = MutableStateFlow(parties)
        override fun observeEntities(includeArchived: Boolean): Flow<List<Entity>> = flow
        override suspend fun createEntity(input: EntityInput): String = "new"
        override suspend fun updateEntity(id: String, input: EntityInput) = Unit
        override suspend fun updateTaxNumber(id: String, taxNumber: String?) = Unit
        override suspend fun updatePhones(id: String, phones: List<String>) = Unit
        override suspend fun archiveEntity(id: String) = Unit
    }

    private class FakeLedgerRepo(private val balances: Map<String, EntityBalance>) : EntityLedgerRepository {
        var calls = 0
        override suspend fun getBalances(): Map<String, EntityBalance> {
            calls++
            return balances
        }
        override suspend fun getBalance(externalId: String): EntityBalance? = balances[externalId]
        override suspend fun getStatement(
            externalId: String,
            from: String?,
            to: String?,
            page: Int,
            limit: Int,
        ): AccountStatement? = null
        override fun close() = Unit
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private fun session(
        level: PermissionLevel = PermissionLevel.MANAGE,
        profiles: PermissionLevel = PermissionLevel.VIEW,
    ) = UserSession(
        uid = "u1",
        email = "u@test",
        displayName = "U",
        role = UserRole.MEMBER,
        permissions = Permissions(transactions = level, profiles = profiles),
        companyId = "co1",
        hlCompanyId = "c1",
        currency = "CAD",
        isActive = true,
    )

    private val rajesh = Entity(id = "e-rajesh", name = "Rajesh Traders")
    private val priya = Entity(id = "e-priya", name = "Priya Mobiles")

    private fun bound(
        vm: MoneyViewModel,
        money: FakeMoneyRepo = FakeMoneyRepo(),
        parties: List<Entity> = listOf(rajesh, priya),
        balances: Map<String, EntityBalance> = emptyMap(),
        level: PermissionLevel = PermissionLevel.MANAGE,
        profiles: PermissionLevel = PermissionLevel.VIEW,
    ): Pair<FakeMoneyRepo, FakeLedgerRepo> {
        val ledger = FakeLedgerRepo(balances)
        vm.bindForTest(session(level, profiles), money, FakeEntityRepo(parties), ledger)
        return money to ledger
    }

    // ── tests ────────────────────────────────────────────────────────────────

    /** Cash and Bank are ordinary picker entries — the legacy `myself_special_id` is gone. */
    @Test
    fun accountPicker_offersCashAndBankAlongsideParties() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm)
        advanceUntilIdle()

        val refs = vm.uiState.value.accounts.map { it.ref }
        assertTrue(MoneyAccountRef.Cash in refs)
        assertTrue(MoneyAccountRef.Bank in refs)
        assertTrue(MoneyAccountRef.Party("e-rajesh") in refs)
        assertTrue(MoneyAccountRef.Party("e-priya") in refs)
        // The shop's own accounts come first, so the most common counterparty is one click away.
        assertTrue(vm.uiState.value.accounts.take(2).all { it.isOwnAccount })
    }

    @Test
    fun records_everyDirection() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()

        val pairs = listOf(
            MoneyAccountRef.Party("e-rajesh") to MoneyAccountRef.Cash,
            MoneyAccountRef.Bank to MoneyAccountRef.Party("e-rajesh"),
            MoneyAccountRef.Party("e-rajesh") to MoneyAccountRef.Party("e-priya"),
            MoneyAccountRef.Cash to MoneyAccountRef.Bank,
        )
        pairs.forEach { (from, to) ->
            vm.setFrom(from); vm.setTo(to); vm.setAmount("250")
            vm.record()
            advanceUntilIdle()
        }

        assertEquals(4, repo.recorded.size)
        assertEquals(pairs.map { it.first }, repo.recorded.map { it.from })
        assertEquals(pairs.map { it.second }, repo.recorded.map { it.to })
    }

    @Test
    fun cannotSave_untilBothSidesAndAValidAmountArePresent() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canSave)
        vm.setFrom(MoneyAccountRef.Cash)
        assertFalse(vm.uiState.value.canSave)
        vm.setTo(MoneyAccountRef.Party("e-rajesh"))
        assertFalse(vm.uiState.value.canSave)     // no amount yet
        vm.setAmount("100")
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun sameAccountBothSides_blocksSaveWithAnExplicitReason() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()

        vm.setFrom(MoneyAccountRef.Cash)
        vm.setTo(MoneyAccountRef.Cash)
        vm.setAmount("100")

        assertEquals(MoneyEntryError.SameAccount, vm.uiState.value.validationError)
        assertFalse(vm.uiState.value.canSave)
        vm.record()
        advanceUntilIdle()
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun amountField_acceptsOnlyDigitsAndOneDecimalPoint() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm)

        vm.setAmount("12a3.4b5")
        assertEquals("123.45", vm.uiState.value.amount)
        vm.setAmount("1.2.3")
        assertEquals("1.23", vm.uiState.value.amount)   // second dot dropped, not a second point
        vm.setAmount("-50")
        assertEquals("50", vm.uiState.value.amount)     // sign can't sneak in; direction is from/to
    }

    /** The most common slip is picking the sides the wrong way round. */
    @Test
    fun swap_exchangesTheTwoSides() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm)
        advanceUntilIdle()

        vm.setFrom(MoneyAccountRef.Cash)
        vm.setTo(MoneyAccountRef.Party("e-rajesh"))
        vm.swapDirection()

        assertEquals(MoneyAccountRef.Party("e-rajesh"), vm.uiState.value.from)
        assertEquals(MoneyAccountRef.Cash, vm.uiState.value.to)
    }

    /** Recording several movements against the same party in a row is the counter's normal rhythm. */
    @Test
    fun afterSaving_clearsAmountAndNoteButKeepsTheAccounts() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm)
        advanceUntilIdle()

        vm.setFrom(MoneyAccountRef.Party("e-rajesh"))
        vm.setTo(MoneyAccountRef.Cash)
        vm.setAmount("500")
        vm.setNote("part payment")
        vm.record()
        advanceUntilIdle()

        val s = vm.uiState.value
        assertEquals("", s.amount)
        assertEquals("", s.note)
        assertTrue(s.justSaved)
        assertEquals(MoneyAccountRef.Party("e-rajesh"), s.from)
        assertEquals(MoneyAccountRef.Cash, s.to)
    }

    @Test
    fun aFailedSave_surfacesTheReasonAndKeepsWhatWasTyped() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()
        repo.failWith = RuntimeException("network down")

        vm.setFrom(MoneyAccountRef.Cash)
        vm.setTo(MoneyAccountRef.Party("e-rajesh"))
        vm.setAmount("500")
        vm.record()
        advanceUntilIdle()

        assertEquals("network down", vm.uiState.value.saveError)
        assertEquals("500", vm.uiState.value.amount)   // not thrown away
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun viewPermission_canReadTheFeedButNotRecord() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm, level = PermissionLevel.VIEW)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.canManage)
        assertFalse(vm.uiState.value.noAccess)
        vm.setFrom(MoneyAccountRef.Cash)
        vm.setTo(MoneyAccountRef.Party("e-rajesh"))
        vm.setAmount("100")
        assertFalse(vm.uiState.value.canSave)
        vm.record()
        advanceUntilIdle()
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun noPermission_showsNoAccessAndLoadsNothing() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, level = PermissionLevel.NONE)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.noAccess)
        assertTrue(vm.uiState.value.accounts.isEmpty())
    }

    /** Balances come from HL and nowhere else — the whole point of the rebuild. */
    @Test
    fun partyBalances_comeFromTheLedger_andAreNeverAdjustedLocally() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (_, ledger) = bound(
            vm,
            balances = mapOf("e-rajesh" to EntityBalance("880.00", BalanceDirection.RECEIVABLE)),
        )
        advanceUntilIdle()

        val before = vm.uiState.value.accounts.first { it.ref == MoneyAccountRef.Party("e-rajesh") }
        assertEquals("880.00", before.balance?.net)

        // Record a payment; the displayed balance must come from a fresh HL read, never from local
        // arithmetic against the amount just entered.
        val callsBefore = ledger.calls
        vm.setFrom(MoneyAccountRef.Party("e-rajesh"))
        vm.setTo(MoneyAccountRef.Cash)
        vm.setAmount("500")
        vm.record()
        advanceUntilIdle()

        assertTrue(ledger.calls > callsBefore, "recording should trigger a ledger re-read")
        val after = vm.uiState.value.accounts.first { it.ref == MoneyAccountRef.Party("e-rajesh") }
        assertEquals("880.00", after.balance?.net) // still HL's number, not 880 − 500
    }

    /** Cash and Bank are the shop's own accounts — no party balance is claimed for them. */
    @Test
    fun ownAccounts_carryNoPartyBalance() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, balances = mapOf("e-rajesh" to EntityBalance("880.00", BalanceDirection.RECEIVABLE)))
        advanceUntilIdle()

        val cash = vm.uiState.value.accounts.first { it.ref == MoneyAccountRef.Cash }
        assertNull(cash.balance)
    }

    /**
     * `transactions` and `profiles` are separate permissions, so this combination is reachable.
     * The screen must say parties are unavailable rather than look like a company with none —
     * and Cash ↔ Bank must still work.
     */
    @Test
    fun withoutProfilesPermission_saysSo_andStillOffersCashAndBank() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, profiles = PermissionLevel.NONE)
        advanceUntilIdle()

        val s = vm.uiState.value
        assertTrue(s.partiesUnavailable)
        assertFalse(s.noAccess)
        assertEquals(listOf(MoneyAccountRef.Cash, MoneyAccountRef.Bank), s.accounts.map { it.ref })

        vm.setFrom(MoneyAccountRef.Cash)
        vm.setTo(MoneyAccountRef.Bank)
        vm.setAmount("200")
        assertTrue(vm.uiState.value.canSave)
    }

    @Test
    fun feed_reflectsWhatTheRepositoryEmits() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()

        repo.entriesFlow.value = listOf(
            MoneyEntry(
                entryId = "e1",
                from = MoneyAccountRef.Party("e-rajesh"),
                to = MoneyAccountRef.Cash,
                amount = "500.00",
                entryDate = 1_700_000_000_000,
                syncStatus = HlSyncStatus.SYNCED,
            ),
        )
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.entries.size)
        assertEquals("e1", vm.uiState.value.entries[0].entryId)
    }

    @Test
    fun reverse_onlyRunsForAnEntryThatCanBeReversed() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()

        val settled = MoneyEntry(
            entryId = "e1",
            from = MoneyAccountRef.Party("e-rajesh"),
            to = MoneyAccountRef.Cash,
            amount = "500.00",
            entryDate = 1L,
            syncStatus = HlSyncStatus.SYNCED,
        )
        // Reverse asks first — the click alone must not move money.
        vm.askReverse(settled)
        advanceUntilIdle()
        assertTrue(repo.reversed.isEmpty())
        assertEquals("e1", vm.uiState.value.pendingReversal?.entryId)

        vm.confirmReverse()
        advanceUntilIdle()
        assertEquals(listOf("e1"), repo.reversed)
        assertNull(vm.uiState.value.pendingReversal)

        // Not yet in the ledger → nothing to mirror.
        vm.askReverse(settled.copy(entryId = "e2", syncStatus = HlSyncStatus.PENDING))
        // Already reversed → reversing again would post the money back twice.
        vm.askReverse(settled.copy(entryId = "e3", reversedByEntryId = "x"))
        // A reversal itself — HL refuses these.
        vm.askReverse(settled.copy(entryId = "e4", reversesEntryId = "e1"))
        advanceUntilIdle()

        assertNull(vm.uiState.value.pendingReversal) // none of them even opened the prompt
        assertEquals(listOf("e1"), repo.reversed)
    }

    // ── history controls ─────────────────────────────────────────────────────

    private fun entry(
        id: String,
        from: MoneyAccountRef,
        to: MoneyAccountRef,
        amount: String,
        dateMillis: Long,
        note: String? = null,
    ) = MoneyEntry(
        entryId = id,
        from = from,
        to = to,
        amount = amount,
        note = note,
        entryDate = dateMillis,
        createdAt = dateMillis,
        syncStatus = HlSyncStatus.SYNCED,
    )

    private val day1 = 1_700_000_000_000L          // oldest
    private val day2 = day1 + 86_400_000L
    private val day3 = day1 + 2 * 86_400_000L      // newest

    private suspend fun seeded(vm: MoneyViewModel, repo: FakeMoneyRepo) {
        repo.entriesFlow.value = listOf(
            entry("a", MoneyAccountRef.Party("e-rajesh"), MoneyAccountRef.Cash, "500.00", day1, "rent"),
            entry("b", MoneyAccountRef.Bank, MoneyAccountRef.Party("e-priya"), "1200.00", day2, "loan"),
            entry("c", MoneyAccountRef.Cash, MoneyAccountRef.Bank, "75.00", day3, "deposit"),
        )
    }

    /** Newest first by default — the entry you just recorded is the one you're looking for. */
    @Test
    fun history_defaultsToNewestFirst() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()
        seeded(vm, repo)
        advanceUntilIdle()

        assertEquals(listOf("c", "b", "a"), vm.uiState.value.visibleEntries.map { it.entryId })
    }

    @Test
    fun sortingByDate_flipsDirectionOnTheSecondClick() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()
        seeded(vm, repo)
        advanceUntilIdle()

        vm.toggleSort(MoneySortColumn.DATE)
        assertEquals(listOf("a", "b", "c"), vm.uiState.value.visibleEntries.map { it.entryId })
        vm.toggleSort(MoneySortColumn.DATE)
        assertEquals(listOf("c", "b", "a"), vm.uiState.value.visibleEntries.map { it.entryId })
    }

    /** Amounts sort numerically, not as text — otherwise "75.00" beats "500.00". */
    @Test
    fun sortingByAmount_isNumericAndStartsWithTheLargest() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()
        seeded(vm, repo)
        advanceUntilIdle()

        vm.toggleSort(MoneySortColumn.AMOUNT)
        assertEquals(listOf("b", "a", "c"), vm.uiState.value.visibleEntries.map { it.entryId })
        vm.toggleSort(MoneySortColumn.AMOUNT)
        assertEquals(listOf("c", "a", "b"), vm.uiState.value.visibleEntries.map { it.entryId })
    }

    @Test
    fun search_matchesPartyNames_notesAndAmounts() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()
        seeded(vm, repo)
        advanceUntilIdle()

        vm.setSearch("rajesh")                      // party on the FROM side, case-insensitive
        assertEquals(listOf("a"), vm.uiState.value.visibleEntries.map { it.entryId })

        vm.setSearch("priya")                       // party on the TO side
        assertEquals(listOf("b"), vm.uiState.value.visibleEntries.map { it.entryId })

        vm.setSearch("deposit")                     // note
        assertEquals(listOf("c"), vm.uiState.value.visibleEntries.map { it.entryId })

        vm.setSearch("1200")                        // amount
        assertEquals(listOf("b"), vm.uiState.value.visibleEntries.map { it.entryId })

        vm.setSearch("Cash")                        // one of the shop's own accounts
        assertEquals(setOf("a", "c"), vm.uiState.value.visibleEntries.map { it.entryId }.toSet())
    }

    @Test
    fun dateRange_narrowsToTheWindowInclusive() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()
        seeded(vm, repo)
        advanceUntilIdle()

        vm.setDateRange(day2, day3)
        assertEquals(setOf("b", "c"), vm.uiState.value.visibleEntries.map { it.entryId }.toSet())

        vm.setDateRange(null, day1)                 // open-ended lower bound
        assertEquals(listOf("a"), vm.uiState.value.visibleEntries.map { it.entryId })

        vm.setDateRange(day3, null)                 // open-ended upper bound
        assertEquals(listOf("c"), vm.uiState.value.visibleEntries.map { it.entryId })
    }

    /** Search and range compose; the flag drives "showing N of M" so a filter never reads as data loss. */
    @Test
    fun filtersCombine_andIsFilteredTracksThem() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()
        seeded(vm, repo)
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isFiltered)

        vm.setSearch("a")                            // matches several
        vm.setDateRange(day1, day1)                  // ...but only day1 survives
        assertTrue(vm.uiState.value.isFiltered)
        assertEquals(listOf("a"), vm.uiState.value.visibleEntries.map { it.entryId })

        vm.clearFilters()
        assertFalse(vm.uiState.value.isFiltered)
        assertEquals(3, vm.uiState.value.visibleEntries.size)
    }

    /** Filtering must never touch the underlying data — only what the table shows. */
    @Test
    fun filtering_leavesTheEntriesListIntact() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()
        seeded(vm, repo)
        advanceUntilIdle()

        vm.setSearch("nothing matches this")
        assertTrue(vm.uiState.value.visibleEntries.isEmpty())
        assertEquals(3, vm.uiState.value.entries.size)
    }

    /**
     * Reversing is how someone says "this didn't happen" — so neither the reversal nor the entry it
     * cancelled belongs in the day-to-day table. The records stay in Firestore and the ledger.
     */
    @Test
    fun reversedEntriesAndTheirMirrors_vanishFromTheTable() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()

        repo.entriesFlow.value = listOf(
            entry("live", MoneyAccountRef.Party("e-rajesh"), MoneyAccountRef.Cash, "500.00", day1),
            entry("undone", MoneyAccountRef.Party("e-rajesh"), MoneyAccountRef.Cash, "300.00", day2)
                .copy(reversedByEntryId = "mirror"),
            entry("mirror", MoneyAccountRef.Cash, MoneyAccountRef.Party("e-rajesh"), "300.00", day3)
                .copy(reversesEntryId = "undone"),
        )
        advanceUntilIdle()

        assertEquals(listOf("live"), vm.uiState.value.visibleEntries.map { it.entryId })
        assertEquals(3, vm.uiState.value.entries.size) // still all there underneath
    }

    /** A hidden pair must not be findable by searching for it either. */
    @Test
    fun searchDoesNotResurrectAReversedEntry() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (repo, _) = bound(vm)
        advanceUntilIdle()

        repo.entriesFlow.value = listOf(
            entry("undone", MoneyAccountRef.Party("e-rajesh"), MoneyAccountRef.Cash, "300.00", day1, "cancelled thing")
                .copy(reversedByEntryId = "m"),
        )
        advanceUntilIdle()

        vm.setSearch("cancelled")
        assertTrue(vm.uiState.value.visibleEntries.isEmpty())
    }

    // ── balance freshness ────────────────────────────────────────────────────

    /** Arriving on the screen must not show numbers of unknown age. */
    @Test
    fun bindingRecordsWhenBalancesWereRead() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.balancesRefreshedAt)
    }

    /**
     * Navigating back and forth shouldn't hammer the ledger — but a manual tap must always do
     * something, or the button is a lie.
     */
    @Test
    fun autoRefreshIsThrottled_butAManualRefreshAlwaysFetches() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val (_, ledger) = bound(vm)
        advanceUntilIdle()
        val afterBind = ledger.calls

        // Straight back onto the screen — the numbers are seconds old, so reuse them.
        vm.refreshBalancesIfStale()
        advanceUntilIdle()
        assertEquals(afterBind, ledger.calls)

        // The user asked. Go and look.
        vm.refreshBalances()
        advanceUntilIdle()
        assertEquals(afterBind + 1, ledger.calls)
    }

    /** A failed read must not stamp a fresh timestamp — that would claim currency it doesn't have. */
    @Test
    fun aFailedRefreshDoesNotClaimToBeFresh() = runTest {
        val vm = MoneyViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val ledger = FailingLedgerRepo()
        vm.bindForTest(session(), FakeMoneyRepo(), FakeEntityRepo(listOf(rajesh)), ledger)
        advanceUntilIdle()

        assertNull(vm.uiState.value.balancesRefreshedAt)
        assertNotNull(vm.uiState.value.error)
        // ...and because nothing was stamped, the next arrival genuinely retries.
        vm.refreshBalancesIfStale()
        advanceUntilIdle()
        assertTrue(ledger.calls >= 2)
    }

    private class FailingLedgerRepo : EntityLedgerRepository {
        var calls = 0
        override suspend fun getBalances(): Map<String, EntityBalance> {
            calls++
            throw RuntimeException("ledger unreachable")
        }
        override suspend fun getBalance(externalId: String): EntityBalance? = null
        override suspend fun getStatement(
            externalId: String,
            from: String?,
            to: String?,
            page: Int,
            limit: Int,
        ): AccountStatement? = null
        override fun close() = Unit
    }
}
