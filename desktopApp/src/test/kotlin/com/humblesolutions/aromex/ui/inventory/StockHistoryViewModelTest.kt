package com.humblesolutions.aromex.ui.inventory

import com.humblesolutions.aromex.model.BatchReversalBlock
import com.humblesolutions.aromex.model.BatchReversalStatus
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.StockBatch
import com.humblesolutions.aromex.model.StockBatchStatus
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.StockHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the Stock History ViewModel (ticket #106) — the filtering the table shows and the
 * reversal handshake. Fake repository via [StockHistoryViewModel.bindForTest]; no Firestore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StockHistoryViewModelTest {

    private class FakeRepo : StockHistoryRepository {
        val batches = MutableStateFlow<List<StockBatch>>(emptyList())
        var units: List<Serial> = emptyList()
        var unitLoads = 0
        val requests = mutableListOf<Triple<String, String, String>>()

        var lastLimit: Int? = null
        var lastFrom: Long? = null
        var lastTo: Long? = null
        var subscriptions = 0

        override fun observeBatches(limit: Int, from: Long?, to: Long?): Flow<List<StockBatch>> {
            lastLimit = limit
            lastFrom = from
            lastTo = to
            subscriptions++
            return batches
        }
        override suspend fun loadBatchUnits(purchaseId: String): List<Serial> {
            unitLoads++
            return units
        }
        override suspend fun requestReversal(purchaseId: String, reason: String, requestedBy: String) {
            requests += Triple(purchaseId, reason, requestedBy)
        }
    }

    private fun session(role: UserRole = UserRole.ADMIN) = UserSession(
        uid = "u1", email = "u@test", displayName = "U", role = role,
        permissions = Permissions(inventory = PermissionLevel.MANAGE),
        companyId = "c1", hlCompanyId = "hl1", currency = "CAD", isActive = true,
    )

    private fun batch(
        id: String,
        createdAt: Long = 1_000L,
        party: String = "e1",
        cost: String = "1000.00",
        units: Int = 2,
        status: StockBatchStatus = StockBatchStatus.ACTIVE,
    ) = StockBatch(
        purchaseId = id,
        partyEntityId = party,
        totalCost = cost,
        cashPaid = "0",
        bankPaid = "0",
        unitCount = units,
        createdAt = createdAt,
        syncStatus = HlSyncStatus.SYNCED,
        status = status,
    )

    private fun unit(id: String, imei: String, status: SerialStatus = SerialStatus.IN_STOCK) =
        Serial(serialId = id, productId = "sku", imei = imei, cost = "500.00", status = status, purchaseId = "p1")

    private fun bound(vm: StockHistoryViewModel, repo: FakeRepo, role: UserRole = UserRole.ADMIN) {
        vm.bindForTest(
            session = session(role),
            repository = repo,
            parties = flowOf(listOf(Entity(id = "e1", name = "Kaur Traders", roles = setOf(EntityRole.SUPPLIER)))),
        )
    }

    // ── what the table shows ─────────────────────────────────────────────────

    @Test
    fun newestFirstByDefault() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        repo.batches.value = listOf(batch("p1", createdAt = 100), batch("p2", createdAt = 300), batch("p3", createdAt = 200))
        advanceUntilIdle()

        assertEquals(listOf("p2", "p3", "p1"), vm.uiState.value.visibleBatches.map { it.purchaseId })
    }

    @Test
    fun reversedBatchesAreOutOfTheDefaultViewButStillFindable() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        repo.batches.value = listOf(batch("p1"), batch("p2", status = StockBatchStatus.REVERSED))
        advanceUntilIdle()

        // A reversed batch describes stock that isn't there — but "what happened to that purchase"
        // is exactly what this screen is for, so it stays one click away.
        assertEquals(listOf("p1"), vm.uiState.value.visibleBatches.map { it.purchaseId })
        vm.setStatusFilter(BatchStatusFilter.REVERSED)
        assertEquals(listOf("p2"), vm.uiState.value.visibleBatches.map { it.purchaseId })
        vm.setStatusFilter(BatchStatusFilter.ALL)
        assertEquals(setOf("p1", "p2"), vm.uiState.value.visibleBatches.map { it.purchaseId }.toSet())
    }

    // ── window + date range (they go to the query, not a client-side filter) ──

    @Test
    fun theDateRangeGoesToTheQuery_soOldHistoryIsFetchedNotFilteredAway() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()
        assertEquals(null, repo.lastFrom)

        vm.setDateRange(1_000L, 2_000L)
        advanceUntilIdle()

        // The bug this replaces: the range was applied to a fixed recent window, so a month from
        // years ago showed "no batches match" when the rows had simply never been fetched.
        assertEquals(1_000L, repo.lastFrom)
        assertEquals(2_000L, repo.lastTo)
    }

    @Test
    fun loadMore_widensTheWindow_andOnlyWhenThereMightBeMore() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        // A short list means the window isn't full, so there is nothing behind it.
        repo.batches.value = listOf(batch("p1"))
        advanceUntilIdle()
        val first = repo.lastLimit!!
        assertFalse(vm.uiState.value.mayHaveMore)
        vm.loadMore()
        advanceUntilIdle()
        assertEquals(first, repo.lastLimit)

        // A full window might have more behind it.
        repo.batches.value = List(first) { batch("p$it", createdAt = it.toLong()) }
        advanceUntilIdle()
        assertTrue(vm.uiState.value.mayHaveMore)
        vm.loadMore()
        advanceUntilIdle()
        assertTrue(repo.lastLimit!! > first)
    }

    @Test
    fun changingTheRangeResetsTheWindow() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        repo.batches.value = List(100) { batch("p$it", createdAt = it.toLong()) }
        advanceUntilIdle()
        vm.loadMore()
        advanceUntilIdle()
        val widened = repo.lastLimit!!

        vm.setDateRange(1_000L, 2_000L)
        advanceUntilIdle()

        // A new range starts at page one — carrying a widened window over would re-read a lot for
        // a query that is already narrow.
        assertTrue(repo.lastLimit!! < widened)
    }

    @Test
    fun clearingFiltersDropsTheRangeFromTheQueryToo() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        vm.setDateRange(1_000L, 2_000L)
        advanceUntilIdle()

        vm.clearFilters()
        advanceUntilIdle()

        assertEquals(null, repo.lastFrom)
        assertEquals(null, repo.lastTo)
    }

    @Test
    fun searchMatchesThePartyName() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        repo.batches.value = listOf(batch("p1", party = "e1"), batch("p2", party = "e-unknown"))
        advanceUntilIdle()

        vm.setSearch("kaur")
        assertEquals(listOf("p1"), vm.uiState.value.visibleBatches.map { it.purchaseId })
    }

    @Test
    fun dateRangeNarrowsTheTable_andTheFilteredFlagIsHonest() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        repo.batches.value = listOf(batch("p1", createdAt = 100), batch("p2", createdAt = 500))
        advanceUntilIdle()

        assertFalse(vm.uiState.value.isFiltered)
        vm.setDateRange(400, 600)
        assertEquals(listOf("p2"), vm.uiState.value.visibleBatches.map { it.purchaseId })
        assertTrue(vm.uiState.value.isFiltered)

        vm.clearFilters()
        assertEquals(2, vm.uiState.value.visibleBatches.size)
        assertFalse(vm.uiState.value.isFiltered)
    }

    @Test
    fun sortingByCostFlipsOnTheSecondClick() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        repo.batches.value = listOf(batch("p1", cost = "900.00"), batch("p2", cost = "1200.00"))
        advanceUntilIdle()

        vm.toggleSort(BatchSortColumn.COST)
        assertEquals(listOf("p2", "p1"), vm.uiState.value.visibleBatches.map { it.purchaseId })
        vm.toggleSort(BatchSortColumn.COST)
        assertEquals(listOf("p1", "p2"), vm.uiState.value.visibleBatches.map { it.purchaseId })
    }

    @Test
    fun partyNamesSurviveALaterBatchUpdate() = runTest {
        val repo = FakeRepo()
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()
        // A regression guard: the label cache used to live outside the state, so the next
        // copy() of the state dropped it and every row fell back to "—".
        repo.batches.value = listOf(batch("p1"))
        advanceUntilIdle()
        assertEquals("Kaur Traders", vm.uiState.value.partyName("e1"))
    }

    // ── the reversal handshake ───────────────────────────────────────────────

    @Test
    fun askReverse_loadsTheUnitsSoTheDialogCanStateWhatWillHappen() = runTest {
        val repo = FakeRepo()
        repo.units = listOf(unit("s1", "111"), unit("s2", "222"))
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.askReverse(batch("p1"))
        advanceUntilIdle()

        val pending = vm.uiState.value.pendingReversal!!
        assertEquals(2, pending.units.size)
        assertNull(pending.block)
        // Still needs a reason — reversing without one leaves no record of why.
        assertFalse(pending.canConfirm)
        vm.setReversalReason("Wrong supplier")
        assertTrue(vm.uiState.value.pendingReversal!!.canConfirm)
    }

    @Test
    fun askReverse_onASoldBatch_carriesTheReasonItCantHappen() = runTest {
        val repo = FakeRepo()
        repo.units = listOf(unit("s1", "111"), unit("s2", "222", status = SerialStatus.SOLD))
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.askReverse(batch("p1"))
        advanceUntilIdle()

        val pending = vm.uiState.value.pendingReversal!!
        assertEquals(BatchReversalBlock.UnitsSold(listOf("222")), pending.block)
        // No reason field, no confirm — the dialog explains instead of offering a dead button.
        vm.setReversalReason("Wrong supplier")
        assertFalse(vm.uiState.value.pendingReversal!!.canConfirm)
    }

    @Test
    fun confirmReverse_sendsTheRequestAndClosesTheDialog() = runTest {
        val repo = FakeRepo()
        repo.units = listOf(unit("s1", "111"), unit("s2", "222"))
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.askReverse(batch("p1"))
        advanceUntilIdle()
        vm.setReversalReason("  Wrong supplier  ")
        vm.confirmReverse()
        advanceUntilIdle()

        assertEquals(listOf(Triple("p1", "Wrong supplier", "u1")), repo.requests)
        assertNull(vm.uiState.value.pendingReversal)
    }

    @Test
    fun confirmReverse_isRefusedForANonAdmin_andSurfacesWhy() = runTest {
        val repo = FakeRepo()
        repo.units = listOf(unit("s1", "111"), unit("s2", "222"))
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo, role = UserRole.MEMBER)
        advanceUntilIdle()

        vm.askReverse(batch("p1"))
        advanceUntilIdle()
        vm.setReversalReason("Wrong supplier")
        vm.confirmReverse()
        advanceUntilIdle()

        assertTrue(repo.requests.isEmpty())
        assertTrue(vm.uiState.value.error != null || vm.uiState.value.errorKey != null)
    }

    @Test
    fun aBatchAlreadyReversing_isBlocked_soASlowFunctionCantTakeTwo() = runTest {
        val repo = FakeRepo()
        repo.units = listOf(unit("s1", "111"), unit("s2", "222"))
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.askReverse(batch("p1").copy(reversalStatus = BatchReversalStatus.PENDING))
        advanceUntilIdle()

        assertEquals(BatchReversalBlock.InFlight, vm.uiState.value.pendingReversal!!.block)
    }

    @Test
    fun expandingARowLoadsItsUnits_andCollapsingClearsThem() = runTest {
        val repo = FakeRepo()
        repo.units = listOf(unit("s1", "111"))
        val vm = StockHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        val b = batch("p1")
        vm.toggleExpanded(b)
        advanceUntilIdle()
        assertEquals("p1", vm.uiState.value.expandedBatchId)
        assertEquals(1, vm.uiState.value.expandedUnits.size)

        vm.toggleExpanded(b)
        assertNull(vm.uiState.value.expandedBatchId)
        assertTrue(vm.uiState.value.expandedUnits.isEmpty())
    }
}
