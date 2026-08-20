package com.humblesolutions.aromex.ui.sales.history

import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleStatus
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.model.SaleVoidFailedException
import com.humblesolutions.aromex.model.SaleVoidState
import com.humblesolutions.aromex.model.SalesCursor
import com.humblesolutions.aromex.model.SalesPage
import com.humblesolutions.aromex.model.SalesQuery
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.model.VoidStatus
import com.humblesolutions.aromex.repository.SalesRepository
import com.humblesolutions.aromex.model.SaleRecord
import com.humblesolutions.aromex.usecase.GetSaleUseCase
import com.humblesolutions.aromex.usecase.QuerySalesUseCase
import com.humblesolutions.aromex.usecase.VoidSaleUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the Desktop [SalesHistoryViewModel] (ticket #83): paging, each filter, the
 * IMEI indirection, empty states, and permission gating — over a fake [SalesRepository] via
 * [SalesHistoryViewModel.bindForTest]; no Firestore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SalesHistoryViewModelTest {

    private class FakeSalesRepository(
        /** Pages returned by successive [querySales] calls (paging). */
        private val pages: List<SalesPage> = listOf(SalesPage(emptyList(), null)),
        salesById: Map<String, SaleDetail> = emptyMap(),
        private val imeiToSaleId: Map<String, String> = emptyMap(),
        /** When set, [voidSale] throws with this reason (a re-used IMEI / FAILED void). */
        private val voidFailReason: String? = null,
    ) : SalesRepository {
        val queries = mutableListOf<SalesQuery>()
        val voided = mutableListOf<Pair<String, String>>()
        private var salesById = salesById
        private var pageIdx = 0

        override suspend fun recordSale(record: SaleRecord): String = "s"
        override fun observeSaleInvoice(saleId: String): Flow<SaleInvoice> = flowOf(SaleInvoice())
        override suspend fun retryInvoice(saleId: String): SaleInvoice = SaleInvoice()

        override suspend fun querySales(query: SalesQuery): SalesPage {
            queries += query
            return pages[pageIdx.coerceAtMost(pages.lastIndex)].also { pageIdx++ }
        }

        override suspend fun getSale(saleId: String): SaleDetail? = salesById[saleId]
        override suspend fun findSaleIdByImei(imei: String): String? = imeiToSaleId[imei.trim()]

        override suspend fun voidSale(saleId: String, reason: String) {
            voided += saleId to reason
            if (voidFailReason != null) throw SaleVoidFailedException(voidFailReason)
            // On success, the backend flips the sale VOIDED — reflect it so a re-read settles the UI.
            salesById = salesById.mapValues { (id, d) ->
                if (id == saleId) {
                    d.copy(status = SaleStatus.VOIDED, voidState = SaleVoidState(VoidStatus.DONE, reason))
                } else {
                    d
                }
            }
        }
    }

    private fun session(sales: PermissionLevel = PermissionLevel.VIEW, role: UserRole = UserRole.MEMBER) = UserSession(
        uid = "u1", email = "u@test", displayName = "U", role = role,
        permissions = Permissions(sales = sales), companyId = "c1", hlCompanyId = "hl1",
        currency = "CAD", tax = TaxConfig(), timezone = "America/Vancouver", isActive = true,
    )

    private fun summary(
        id: String,
        createdAt: Long = 1_000L,
        balance: String = "0.00",
        label: String = "Apple iPhone 15",
        imei: String = "356938035699001",
        invoice: String = "INV-000042",
    ) = SaleSummary(
        saleId = id, createdAtMillis = createdAt, customerEntityId = "cust-1", isWalkIn = false,
        buyerName = null, firstItemLabel = label, itemCount = 1, firstImei = imei,
        itemLabels = listOf(label), imeis = listOf(imei),
        grandTotal = "100.00", amountPaid = "0.00", balanceRemaining = balance,
        syncStatus = HlSyncStatus.SYNCED, invoiceNumber = invoice, invoiceStatus = SaleInvoiceStatus.ISSUED,
    )

    private fun detail(id: String, createdBy: String = "u1", status: SaleStatus = SaleStatus.COMPLETED) = SaleDetail(
        saleId = id, createdAtMillis = 1_000L, createdBy = createdBy, customerEntityId = "cust-1",
        isWalkIn = false, buyerName = null, buyerPhone = null, lines = emptyList(),
        subtotal = "0", saleDiscount = "0", taxableAmount = "0", taxLines = emptyList(),
        taxTotal = "0", grandTotal = "0", cogsTotal = "0", payment = PaymentInput(),
        amountPaid = "0", balanceRemaining = "0", note = null, syncStatus = HlSyncStatus.SYNCED,
        invoice = SaleInvoice(), status = status,
    )

    private fun bound(
        repo: SalesRepository,
        scope: CoroutineScope,
        sales: PermissionLevel = PermissionLevel.VIEW,
        role: UserRole = UserRole.MEMBER,
        entities: List<Entity> = listOf(Entity(id = "cust-1", name = "Alice", roles = setOf(EntityRole.CUSTOMER))),
    ): SalesHistoryViewModel {
        val vm = SalesHistoryViewModel(scope)
        vm.bindForTest(
            session = session(sales, role),
            querySalesUseCase = QuerySalesUseCase(repo),
            getSaleUseCase = GetSaleUseCase(repo),
            entities = entities,
            voidSaleUseCase = VoidSaleUseCase(repo),
        )
        return vm
    }

    @Test
    fun loadsFirstPage() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(listOf(summary("s1"), summary("s2")), null)))
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("s1", "s2"), state.sales.map { it.saleId })
        assertFalse(state.hasMore)
    }

    @Test
    fun permissionDenied_showsNothing() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(listOf(summary("s1")), null)))
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), sales = PermissionLevel.NONE)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.permissionDenied)
        assertTrue(vm.uiState.value.sales.isEmpty())
        assertTrue(repo.queries.isEmpty(), "a user without access must never query")
    }

    @Test
    fun loadMore_appendsAndDoesNotBlank() = runTest {
        val cursor = SalesCursor(1_000L, "s1")
        val repo = FakeSalesRepository(
            pages = listOf(
                SalesPage(listOf(summary("s1")), cursor),   // first page: has more
                SalesPage(listOf(summary("s2")), null),      // next page: end
            ),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasMore)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), vm.uiState.value.sales.map { it.saleId }, "second page must append, not replace")
        assertFalse(vm.uiState.value.hasMore)
        assertEquals(cursor, repo.queries[1].cursor, "loadMore must pass the previous page's cursor")
    }

    @Test
    fun customerNameFilter_resolvesToEntityIds() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(emptyList(), null)))
        val entities = listOf(
            Entity(id = "cust-1", name = "Alice Smith", roles = setOf(EntityRole.CUSTOMER)),
            Entity(id = "cust-2", name = "Bob Jones", roles = setOf(EntityRole.CUSTOMER)),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), entities = entities)
        advanceUntilIdle()

        vm.applyFilter(SalesHistoryFilter(customerName = "alice"))
        advanceUntilIdle()

        assertEquals(listOf("cust-1"), repo.queries.last().customerEntityIds)
    }

    @Test
    fun customerNameFilter_noMatch_returnsEmptyNotAll() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(emptyList(), null)))
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        vm.applyFilter(SalesHistoryFilter(customerName = "nobody-here"))
        advanceUntilIdle()

        val ids = repo.queries.last().customerEntityIds
        assertNotNull(ids, "an unmatched name must yield a sentinel id list, not null (which would list all)")
        assertEquals(1, ids.size)
    }

    @Test
    fun balanceFilter_passedThrough() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(emptyList(), null)))
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        vm.applyFilter(SalesHistoryFilter(onlyWithBalance = true))
        advanceUntilIdle()

        assertTrue(repo.queries.last().onlyWithBalance)
    }

    @Test
    fun localSearch_matchesImeiInLoadedSales_withoutQueryingFirebase() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1", imei = "356938035699001"), summary("s2", imei = "999999999999999")), null)),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        val queriesAfterLoad = repo.queries.size

        vm.onSearchChanged("356938035699001")

        assertEquals(listOf("s1"), vm.uiState.value.visibleSales.map { it.saleId })
        assertEquals(2, vm.uiState.value.sales.size, "the underlying loaded set is untouched")
        assertEquals(queriesAfterLoad, repo.queries.size, "local search must not hit Firebase")
    }

    @Test
    fun localSearch_matchesItemLabel() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1", label = "Apple iPhone 15"), summary("s2", label = "Samsung Galaxy S24")), null)),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        vm.onSearchChanged("galaxy")
        assertEquals(listOf("s2"), vm.uiState.value.visibleSales.map { it.saleId })
    }

    @Test
    fun localSearch_matchesCustomerNameAndInvoice() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1", invoice = "INV-000042"), summary("s2", invoice = "INV-000099")), null)),
        )
        val entities = listOf(Entity(id = "cust-1", name = "Alice", roles = setOf(EntityRole.CUSTOMER)))
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), entities = entities)
        advanceUntilIdle()

        vm.onSearchChanged("000099")
        assertEquals(listOf("s2"), vm.uiState.value.visibleSales.map { it.saleId })

        vm.onSearchChanged("alice") // both rows are customer "cust-1" → Alice
        assertEquals(listOf("s1", "s2"), vm.uiState.value.visibleSales.map { it.saleId })

        vm.onSearchChanged("") // cleared → everything visible again
        assertEquals(2, vm.uiState.value.visibleSales.size)
    }

    @Test
    fun emptyState_distinguishesNoSalesFromNoMatch() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(emptyList(), null)))
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.hasActiveFilter, "no filter → 'no sales yet'")

        vm.applyFilter(SalesHistoryFilter(customerName = "x"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasActiveFilter, "with a filter → 'no match'")
    }

    @Test
    fun openSale_loadsDetail() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1")),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        vm.openSale("s1")
        advanceUntilIdle()

        assertEquals("s1", vm.uiState.value.detail?.saleId)
        vm.closeDetail()
        assertNull(vm.uiState.value.detail)
    }

    @Test
    fun openSale_resolvesSellerName_forCurrentUser() = runTest {
        // The sale's createdBy uid ("u1") == the session user → the session display name ("U"),
        // never the raw uid. No users/{uid} read needed for the current user.
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1", createdBy = "u1")),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        vm.openSale("s1")
        advanceUntilIdle()

        assertEquals("U", vm.uiState.value.sellerName)
    }

    @Test
    fun openSale_resolvesSellerName_fromCache() = runTest {
        // A different seller ("u2") resolves to a display name looked up once and cached — the UI
        // shows "Bob", not the uid. (bindForTest seeds the cache in place of a Firestore read.)
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1", createdBy = "u2")),
        )
        val vm = SalesHistoryViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.bindForTest(
            session = session(),
            querySalesUseCase = QuerySalesUseCase(repo),
            getSaleUseCase = GetSaleUseCase(repo),
            entities = listOf(Entity(id = "cust-1", name = "Alice", roles = setOf(EntityRole.CUSTOMER))),
            sellerNames = mapOf("u2" to "Bob"),
        )
        advanceUntilIdle()

        vm.openSale("s1")
        advanceUntilIdle()

        assertEquals("Bob", vm.uiState.value.sellerName)
    }

    @Test
    fun closeDetail_clearsSellerName() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1")),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()

        vm.openSale("s1")
        advanceUntilIdle()
        assertEquals("U", vm.uiState.value.sellerName)

        vm.closeDetail()
        assertNull(vm.uiState.value.sellerName)
    }

    @Test
    fun clearFilters_resets() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(emptyList(), null)))
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        advanceUntilIdle()
        vm.applyFilter(SalesHistoryFilter(onlyWithBalance = true, customerName = "x"))
        advanceUntilIdle()

        vm.clearFilters()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.filter.isEmpty)
        assertFalse(repo.queries.last().onlyWithBalance)
    }

    // ── Void a sale (ticket #85) ────────────────────────────────────────────────

    @Test
    fun admin_seesVoidAvailableOnOpenSale() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1")),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), role = UserRole.ADMIN)
        advanceUntilIdle()
        vm.openSale("s1"); advanceUntilIdle()

        assertTrue(vm.uiState.value.isAdmin)
        assertTrue(vm.uiState.value.canVoidOpenSale)
    }

    @Test
    fun nonAdmin_cannotVoid() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1")),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), role = UserRole.MEMBER)
        advanceUntilIdle()
        vm.openSale("s1"); advanceUntilIdle()

        assertFalse(vm.uiState.value.isAdmin)
        assertFalse(vm.uiState.value.canVoidOpenSale, "a non-admin must not see the void action")
        // Even if voidSale is called, the use case gate blocks it → nothing reaches the repo.
        vm.voidSale("mistake"); advanceUntilIdle()
        assertTrue(repo.voided.isEmpty())
    }

    @Test
    fun alreadyVoidedSale_cannotVoidAgain() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1", status = SaleStatus.VOIDED)),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), role = UserRole.ADMIN)
        advanceUntilIdle()
        vm.openSale("s1"); advanceUntilIdle()

        assertTrue(vm.uiState.value.detail?.isVoided == true)
        assertFalse(vm.uiState.value.canVoidOpenSale)
    }

    @Test
    fun voidSale_success_flipsDetailVoidedAndMarksListRow() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1")),
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), role = UserRole.ADMIN)
        advanceUntilIdle()
        vm.openSale("s1"); advanceUntilIdle()

        vm.voidSale("  rung up by mistake  "); advanceUntilIdle()

        // Reason is trimmed before it reaches the repo.
        assertEquals(listOf("s1" to "rung up by mistake"), repo.voided)
        val state = vm.uiState.value
        assertFalse(state.isVoiding)
        assertNull(state.voidError)
        assertTrue(state.detail?.isVoided == true)
        assertEquals(SaleStatus.VOIDED, state.sales.first { it.saleId == "s1" }.status)
        assertFalse(state.canVoidOpenSale, "a voided sale can't be voided again")
    }

    @Test
    fun voidSale_failure_surfacesReasonAndLeavesSaleIntact() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1")),
            voidFailReason = "IMEI 111 has been re-added to stock",
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), role = UserRole.ADMIN)
        advanceUntilIdle()
        vm.openSale("s1"); advanceUntilIdle()

        vm.voidSale("mistake"); advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isVoiding)
        assertEquals("IMEI 111 has been re-added to stock", state.voidError)
        assertFalse(state.detail?.isVoided == true, "a failed void must not mark the sale voided")
        assertTrue(state.canVoidOpenSale, "still voidable after a failure")
    }

    @Test
    fun clearVoidError_clearsTheBanner() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1")), null)),
            salesById = mapOf("s1" to detail("s1")),
            voidFailReason = "boom",
        )
        val vm = bound(repo, CoroutineScope(UnconfinedTestDispatcher(testScheduler)), role = UserRole.ADMIN)
        advanceUntilIdle()
        vm.openSale("s1"); advanceUntilIdle()
        vm.voidSale("mistake"); advanceUntilIdle()
        assertNotNull(vm.uiState.value.voidError)

        vm.clearVoidError()
        assertNull(vm.uiState.value.voidError)
    }
}
