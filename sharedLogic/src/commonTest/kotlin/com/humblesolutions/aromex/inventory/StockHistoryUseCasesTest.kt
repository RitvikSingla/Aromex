package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.BatchReversalBlock
import com.humblesolutions.aromex.model.BatchReversalStatus
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.StockBatch
import com.humblesolutions.aromex.model.StockBatchStatus
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.model.batchReversalBlock
import com.humblesolutions.aromex.repository.StockHistoryRepository
import com.humblesolutions.aromex.usecase.LoadBatchUnitsUseCase
import com.humblesolutions.aromex.usecase.ObserveStockBatchesUseCase
import com.humblesolutions.aromex.usecase.RequestStockBatchReversalUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun session(
    role: UserRole = UserRole.ADMIN,
    inventory: PermissionLevel = PermissionLevel.MANAGE,
) = UserSession(
    uid = "u1", email = "u@test", displayName = "U", role = role,
    permissions = Permissions(inventory = inventory), companyId = "c1", hlCompanyId = "hl1",
    currency = "CAD", isActive = true,
)

private fun batch(
    unitCount: Int = 2,
    status: StockBatchStatus = StockBatchStatus.ACTIVE,
    reversalStatus: BatchReversalStatus? = null,
    totalCost: String = "1000.00",
    cashPaid: String = "0",
    bankPaid: String = "0",
) = StockBatch(
    purchaseId = "p1",
    partyEntityId = "e1",
    totalCost = totalCost,
    cashPaid = cashPaid,
    bankPaid = bankPaid,
    unitCount = unitCount,
    createdAt = 1_000L,
    syncStatus = HlSyncStatus.SYNCED,
    status = status,
    reversalStatus = reversalStatus,
)

private fun unit(
    id: String,
    imei: String,
    status: SerialStatus = SerialStatus.IN_STOCK,
    active: Boolean = true,
) = Serial(serialId = id, productId = "sku", imei = imei, cost = "500.00", status = status, isActive = active, purchaseId = "p1")

private class FakeStockHistoryRepository : StockHistoryRepository {
    val batches = MutableStateFlow<List<StockBatch>>(emptyList())
    var units: List<Serial> = emptyList()
    var lastLimit: Int? = null
    val requests = mutableListOf<Triple<String, String, String>>()

    var lastFrom: Long? = null
    var lastTo: Long? = null

    override fun observeBatches(limit: Int, from: Long?, to: Long?): Flow<List<StockBatch>> {
        lastLimit = limit
        lastFrom = from
        lastTo = to
        return batches
    }

    override suspend fun loadBatchUnits(purchaseId: String): List<Serial> = units

    override suspend fun requestReversal(purchaseId: String, reason: String, requestedBy: String) {
        requests += Triple(purchaseId, reason, requestedBy)
    }
}

class StockHistoryUseCasesTest {

    // ── The blocking rules (pure) ───────────────────────────────────────────────

    @Test
    fun aWholeInStockBatch_canBeReversed() {
        assertNull(batchReversalBlock(batch(), listOf(unit("s1", "111"), unit("s2", "222"))))
    }

    @Test
    fun aSoldUnit_blocksIt_andNamesTheImei() {
        val block = batchReversalBlock(
            batch(),
            listOf(unit("s1", "111"), unit("s2", "222", status = SerialStatus.SOLD)),
        )
        // HL reverses whole transactions, so half a batch can't come back cleanly.
        assertEquals(BatchReversalBlock.UnitsSold(listOf("222")), block)
    }

    @Test
    fun anIndividuallyRemovedUnit_blocksIt() {
        val block = batchReversalBlock(
            batch(),
            listOf(unit("s1", "111"), unit("s2", "222", active = false)),
        )
        assertEquals(BatchReversalBlock.UnitsRemoved(listOf("222")), block)
    }

    @Test
    fun aBatchWhoseUnitsArentTagged_isUnknown_notEmpty() {
        // Batches added before serials carried purchaseId. Reversing would have to guess at
        // which phones to pull, so it refuses rather than pulling none.
        assertEquals(BatchReversalBlock.UnitsUnknown, batchReversalBlock(batch(unitCount = 5), emptyList()))
    }

    @Test
    fun fewerTaggedUnitsThanRecorded_isUnknown() {
        // The ledger would un-book 5 phones' worth while only 2 came off the shelf.
        assertEquals(
            BatchReversalBlock.UnitsUnknown,
            batchReversalBlock(batch(unitCount = 5), listOf(unit("s1", "111"), unit("s2", "222"))),
        )
    }

    @Test
    fun anAlreadyReversedBatch_andOneInFlight_areBlocked() {
        assertEquals(
            BatchReversalBlock.AlreadyReversed,
            batchReversalBlock(batch(status = StockBatchStatus.REVERSED), emptyList()),
        )
        // The window between the request and the ledger moving — offering Reverse again here
        // would let a slow function take two.
        assertEquals(
            BatchReversalBlock.InFlight,
            batchReversalBlock(batch(reversalStatus = BatchReversalStatus.PENDING), listOf(unit("s1", "111"), unit("s2", "222"))),
        )
    }

    // ── Derived money ───────────────────────────────────────────────────────────

    @Test
    fun balanceAdded_isWhatTheBatchLeftOwing() {
        val b = batch(totalCost = "1000.00", cashPaid = "300.00", bankPaid = "200.00")
        assertEquals("500.00", b.paid)
        assertEquals("500.00", b.balanceAdded)
    }

    @Test
    fun blankAndGarbageAmounts_readAsZero_neverCrashARow() {
        val b = batch(totalCost = "1000.00", cashPaid = "", bankPaid = "n/a")
        assertEquals("0", b.paid)
        assertEquals("1000.00", b.balanceAdded)
    }

    // ── Gates ───────────────────────────────────────────────────────────────────

    @Test
    fun reversal_isAdminOnly_andWritesNothingOtherwise() = runTest {
        val repo = FakeStockHistoryRepository()
        assertFailsWith<PermissionDeniedException> {
            RequestStockBatchReversalUseCase(repo).execute(
                session(role = UserRole.MEMBER),
                batch(),
                listOf(unit("s1", "111"), unit("s2", "222")),
                "Wrong supplier",
            )
        }
        assertTrue(repo.requests.isEmpty())
    }

    @Test
    fun reversal_requiresAReason() = runTest {
        val repo = FakeStockHistoryRepository()
        assertFailsWith<IllegalArgumentException> {
            RequestStockBatchReversalUseCase(repo).execute(
                session(),
                batch(),
                listOf(unit("s1", "111"), unit("s2", "222")),
                "   ",
            )
        }
        assertTrue(repo.requests.isEmpty())
    }

    @Test
    fun reversal_refusesABlockedBatch_beforeItReachesTheRepository() = runTest {
        val repo = FakeStockHistoryRepository()
        assertFailsWith<IllegalStateException> {
            RequestStockBatchReversalUseCase(repo).execute(
                session(),
                batch(),
                listOf(unit("s1", "111"), unit("s2", "222", status = SerialStatus.SOLD)),
                "Wrong supplier",
            )
        }
        assertTrue(repo.requests.isEmpty())
    }

    @Test
    fun reversal_recordsTheRequestWithTheTrimmedReasonAndTheRequester() = runTest {
        val repo = FakeStockHistoryRepository()
        RequestStockBatchReversalUseCase(repo).execute(
            session(),
            batch(),
            listOf(unit("s1", "111"), unit("s2", "222")),
            "  Wrong supplier  ",
        )
        assertEquals(listOf(Triple("p1", "Wrong supplier", "u1")), repo.requests)
    }

    @Test
    fun readingHistory_needsInventoryAccess() = runTest {
        val repo = FakeStockHistoryRepository()
        assertFailsWith<PermissionDeniedException> {
            ObserveStockBatchesUseCase(repo).execute(session(inventory = PermissionLevel.NONE))
        }
        assertFailsWith<PermissionDeniedException> {
            LoadBatchUnitsUseCase(repo).execute(session(inventory = PermissionLevel.NONE), "p1")
        }
        // VIEW is enough — seeing what came in is the same bar as seeing the stock.
        repo.batches.value = listOf(batch())
        val rows = ObserveStockBatchesUseCase(repo)
            .execute(session(role = UserRole.MEMBER, inventory = PermissionLevel.VIEW), limit = 50, from = 10L, to = 20L)
            .first()
        assertEquals(1, rows.size)
        assertEquals(50, repo.lastLimit)
        // The range reaches the query, so old history is fetched rather than filtered away.
        assertEquals(10L, repo.lastFrom)
        assertEquals(20L, repo.lastTo)
    }
}
