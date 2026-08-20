package com.humblesolutions.aromex.ui.sales.history

import android.app.Application
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleRecord
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.model.SalesCursor
import com.humblesolutions.aromex.model.SalesPage
import com.humblesolutions.aromex.model.SalesQuery
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.SalesRepository
import com.humblesolutions.aromex.usecase.GetSaleUseCase
import com.humblesolutions.aromex.usecase.QuerySalesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the Android [SalesHistoryViewModel] (ticket #84): the single-box search-type
 * detection, the instant local filter, and server-side paging — over a fake [SalesRepository]
 * via [SalesHistoryViewModel.bindForTest], no Firebase. Robolectric supplies the [Application]
 * the AndroidViewModel needs; the shared query layer is exercised through the real use cases.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SalesHistoryViewModelTest {

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val app: Application get() = RuntimeEnvironment.getApplication()

    // ── Search-type detection (pure) ─────────────────────────────────────────────

    @Test fun detect_blank_isNone() {
        assertEquals(SalesSearchKind.NONE, detectSearchKind(""))
        assertEquals(SalesSearchKind.NONE, detectSearchKind("   "))
    }

    @Test fun detect_fifteenDigits_isImei() {
        assertEquals(SalesSearchKind.IMEI, detectSearchKind("356938035699001")) // 15 digits (IMEI)
        assertEquals(SalesSearchKind.IMEI, detectSearchKind("35693803569900")) // 14
        assertEquals(SalesSearchKind.IMEI, detectSearchKind("3569380356990015")) // 16
        assertEquals(SalesSearchKind.IMEI, detectSearchKind(" 356938035699001 ")) // trimmed
    }

    @Test fun detect_digitsOutsideImeiRange_isCustomer() {
        assertEquals(SalesSearchKind.CUSTOMER, detectSearchKind("1234567890123")) // 13 → too short
        assertEquals(SalesSearchKind.CUSTOMER, detectSearchKind("123456789012345678")) // 18 → too long
    }

    @Test fun detect_invoicePrefix_isInvoice() {
        assertEquals(SalesSearchKind.INVOICE, detectSearchKind("INV-000042"))
        assertEquals(SalesSearchKind.INVOICE, detectSearchKind("inv-1")) // case-insensitive
    }

    @Test fun detect_name_isCustomer() {
        assertEquals(SalesSearchKind.CUSTOMER, detectSearchKind("Alice"))
        assertEquals(SalesSearchKind.CUSTOMER, detectSearchKind("iphone 15")) // letters+digits, no prefix
    }

    // ── Paging ───────────────────────────────────────────────────────────────────

    @Test fun loadsFirstPage() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(listOf(summary("s1"), summary("s2")), null)))
        val vm = bound(repo)
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("s1", "s2"), state.sales.map { it.saleId })
        assertFalse(state.hasMore)
    }

    @Test fun permissionDenied_showsNothingAndNeverQueries() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(listOf(summary("s1")), null)))
        val vm = bound(repo, sales = PermissionLevel.NONE)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.permissionDenied)
        assertTrue(vm.uiState.value.sales.isEmpty())
        assertTrue(repo.queries.isEmpty(), "a user without access must never query")
    }

    @Test fun loadMore_appendsAndPassesCursor() = runTest {
        val cursor = SalesCursor(1_000L, "s1")
        val repo = FakeSalesRepository(
            pages = listOf(
                SalesPage(listOf(summary("s1")), cursor), // first page: has more
                SalesPage(listOf(summary("s2")), null),   // next page: end
            ),
        )
        val vm = bound(repo)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.hasMore)

        vm.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("s1", "s2"), vm.uiState.value.sales.map { it.saleId }, "second page must append, not replace")
        assertFalse(vm.uiState.value.hasMore)
        assertEquals(cursor, repo.queries[1].cursor, "loadMore must pass the previous page's cursor")
    }

    // ── Single-box submit → server query ─────────────────────────────────────────

    @Test fun submit_imei_setsKindAndResolvesViaSerial() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(emptyList(), null)), // initial newest-first load
            salesById = mapOf("s9" to detail("s9")),
            imeiToSaleId = mapOf("356938035699001" to "s9"),
        )
        val vm = bound(repo)
        advanceUntilIdle()

        vm.onSearchChanged("356938035699001")
        vm.onSearchSubmit()
        advanceUntilIdle()

        assertEquals(SalesSearchKind.IMEI, vm.uiState.value.searchKind)
        assertEquals("356938035699001", repo.lastImeiLookup)
        assertEquals(listOf("s9"), vm.uiState.value.sales.map { it.saleId })
    }

    @Test fun submit_invoice_setsKindAndQueriesInvoiceNumber() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(emptyList(), null)))
        val vm = bound(repo)
        advanceUntilIdle()

        vm.onSearchChanged("INV-000042")
        vm.onSearchSubmit()
        advanceUntilIdle()

        assertEquals(SalesSearchKind.INVOICE, vm.uiState.value.searchKind)
        assertEquals("INV-000042", repo.queries.last().invoiceNumber)
    }

    @Test fun submit_customer_resolvesEntityIds() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(emptyList(), null)))
        val entities = listOf(
            Entity(id = "cust-1", name = "Alice Smith", roles = setOf(EntityRole.CUSTOMER)),
            Entity(id = "cust-2", name = "Bob Jones", roles = setOf(EntityRole.CUSTOMER)),
        )
        val vm = bound(repo, entities = entities)
        advanceUntilIdle()

        vm.onSearchChanged("alice")
        vm.onSearchSubmit()
        advanceUntilIdle()

        assertEquals(SalesSearchKind.CUSTOMER, vm.uiState.value.searchKind)
        assertEquals(listOf("cust-1"), repo.queries.last().customerEntityIds)
    }

    @Test fun submit_customer_noMatch_yieldsSentinelNotAll() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(emptyList(), null)))
        val vm = bound(repo)
        advanceUntilIdle()

        vm.onSearchChanged("nobody-here")
        vm.onSearchSubmit()
        advanceUntilIdle()

        val ids = repo.queries.last().customerEntityIds
        assertNotNull(ids, "an unmatched name must yield a sentinel id list, not null (which would list all)")
        assertEquals(1, ids.size)
    }

    // ── Instant local filter (no Firebase) ───────────────────────────────────────

    @Test fun localSearch_matchesImeiWithoutQuerying() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(SalesPage(listOf(summary("s1", imei = "356938035699001"), summary("s2", imei = "999999999999999")), null)),
        )
        val vm = bound(repo)
        advanceUntilIdle()
        val queriesAfterLoad = repo.queries.size

        vm.onSearchChanged("356938035699001")

        assertEquals(listOf("s1"), vm.uiState.value.visibleSales.map { it.saleId })
        assertEquals(2, vm.uiState.value.sales.size, "the underlying loaded set is untouched")
        assertEquals(queriesAfterLoad, repo.queries.size, "typing must not hit Firebase")
    }

    @Test fun localSearch_matchesLabelInvoiceAndCustomer() = runTest {
        val repo = FakeSalesRepository(
            pages = listOf(
                SalesPage(
                    listOf(
                        summary("s1", label = "Apple iPhone 15", invoice = "INV-000042"),
                        summary("s2", label = "Samsung Galaxy S24", invoice = "INV-000099"),
                    ),
                    null,
                ),
            ),
        )
        val entities = listOf(Entity(id = "cust-1", name = "Alice", roles = setOf(EntityRole.CUSTOMER)))
        val vm = bound(repo, entities = entities)
        advanceUntilIdle()

        vm.onSearchChanged("galaxy")
        assertEquals(listOf("s2"), vm.uiState.value.visibleSales.map { it.saleId })

        vm.onSearchChanged("000099")
        assertEquals(listOf("s2"), vm.uiState.value.visibleSales.map { it.saleId })

        vm.onSearchChanged("alice") // both rows resolve to customer "cust-1" → Alice
        assertEquals(listOf("s1", "s2"), vm.uiState.value.visibleSales.map { it.saleId })
    }

    @Test fun clearSearch_resetsToPlainList() = runTest {
        val repo = FakeSalesRepository(pages = listOf(SalesPage(listOf(summary("s1")), null)))
        val vm = bound(repo)
        advanceUntilIdle()
        vm.onSearchChanged("INV-000042"); vm.onSearchSubmit()
        advanceUntilIdle()
        assertEquals(SalesSearchKind.INVOICE, vm.uiState.value.searchKind)

        vm.clearSearch()
        advanceUntilIdle()

        assertEquals(SalesSearchKind.NONE, vm.uiState.value.searchKind)
        assertEquals("", vm.uiState.value.searchText)
        assertFalse(vm.uiState.value.hasActiveSearch)
    }

    // ── Fakes / builders ─────────────────────────────────────────────────────────

    private fun bound(
        repo: SalesRepository,
        sales: PermissionLevel = PermissionLevel.VIEW,
        entities: List<Entity> = listOf(Entity(id = "cust-1", name = "Alice", roles = setOf(EntityRole.CUSTOMER))),
    ): SalesHistoryViewModel {
        val vm = SalesHistoryViewModel(app)
        vm.bindForTest(
            session = session(sales),
            querySalesUseCase = QuerySalesUseCase(repo),
            getSaleUseCase = GetSaleUseCase(repo),
            entities = entities,
        )
        return vm
    }

    private fun session(sales: PermissionLevel = PermissionLevel.VIEW) = UserSession(
        uid = "u1", email = "u@test", displayName = "U", role = UserRole.MEMBER,
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

    private fun detail(id: String, createdBy: String = "u1") = SaleDetail(
        saleId = id, createdAtMillis = 1_000L, createdBy = createdBy, customerEntityId = "cust-1",
        isWalkIn = false, buyerName = null, buyerPhone = null, lines = emptyList(),
        subtotal = "0", saleDiscount = "0", taxableAmount = "0", taxLines = emptyList(),
        taxTotal = "0", grandTotal = "0", cogsTotal = "0", payment = PaymentInput(),
        amountPaid = "0", balanceRemaining = "0", note = null, syncStatus = HlSyncStatus.SYNCED,
        invoice = SaleInvoice(),
    )

    private class FakeSalesRepository(
        private val pages: List<SalesPage> = listOf(SalesPage(emptyList(), null)),
        private val salesById: Map<String, SaleDetail> = emptyMap(),
        private val imeiToSaleId: Map<String, String> = emptyMap(),
    ) : SalesRepository {
        val queries = mutableListOf<SalesQuery>()
        var lastImeiLookup: String? = null
        private var pageIdx = 0

        override suspend fun recordSale(record: SaleRecord): String = "s"
        override fun observeSaleInvoice(saleId: String): Flow<SaleInvoice> = flowOf(SaleInvoice())
        override suspend fun retryInvoice(saleId: String): SaleInvoice = SaleInvoice()

        override suspend fun querySales(query: SalesQuery): SalesPage {
            queries += query
            return pages[pageIdx.coerceAtMost(pages.lastIndex)].also { pageIdx++ }
        }

        override suspend fun getSale(saleId: String): SaleDetail? = salesById[saleId]
        override suspend fun findSaleIdByImei(imei: String): String? {
            lastImeiLookup = imei.trim()
            return imeiToSaleId[imei.trim()]
        }
        override suspend fun voidSale(saleId: String, reason: String) {}
    }
}
