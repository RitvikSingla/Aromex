package com.humblesolutions.aromex.ui.sales

import com.humblesolutions.aromex.model.AlreadySoldException
import com.humblesolutions.aromex.model.SaleContentionException
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.InvoiceRetryNotAcknowledgedException
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleRecord
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.model.WALK_IN_CUSTOMER_ID
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.repository.SalesRepository
import com.humblesolutions.aromex.usecase.ObserveSaleInvoiceUseCase
import com.humblesolutions.aromex.usecase.RecordSaleUseCase
import com.humblesolutions.aromex.usecase.RetryInvoiceUseCase
import com.humblesolutions.aromex.usecase.SaleCalculator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
 * Unit tests for the Desktop [SalesViewModel] (ticket #62) — the JVM home for the shared
 * cart/gating/totals logic mirrored on all three platforms. Uses fake repo + cached streams
 * via [SalesViewModel.bindForTest]; no Firestore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SalesViewModelTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    private class FakeSalesRepository(
        private val soldImei: String? = null,
        private val contention: Boolean = false,
        val saleId: String = "sale-1",
    ) : SalesRepository {
        val recorded = mutableListOf<SaleRecord>()
        /** Drives [observeSaleInvoice]; tests push ISSUED/FAILED to exercise the row + retry. */
        val invoiceFlow = MutableStateFlow(SaleInvoice())
        var retryCalls = 0
        var retryResult = SaleInvoice(status = SaleInvoiceStatus.PENDING)

        override suspend fun recordSale(record: SaleRecord): String {
            soldImei?.let { throw AlreadySoldException(it) }
            if (contention) throw SaleContentionException()
            recorded += record
            return saleId
        }

        /** When set (incomplete), [retryInvoice] suspends here so a test can observe the lock. */
        var retryGate: CompletableDeferred<Unit>? = null

        /** When set, [retryInvoice] throws it *after* the gate — the "call failed" path. */
        var retryError: Throwable? = null

        override fun observeSaleInvoice(saleId: String): Flow<SaleInvoice> = invoiceFlow

        override suspend fun retryInvoice(saleId: String): SaleInvoice {
            retryCalls++
            retryGate?.await()
            retryError?.let { throw it }
            return retryResult
        }

        // Sales History reads (ticket #83) — unused by these checkout tests.
        override suspend fun querySales(query: com.humblesolutions.aromex.model.SalesQuery) =
            com.humblesolutions.aromex.model.SalesPage(emptyList(), null)
        override suspend fun getSale(saleId: String): com.humblesolutions.aromex.model.SaleDetail? = null
        override suspend fun findSaleIdByImei(imei: String): String? = null
        override suspend fun voidSale(saleId: String, reason: String) {}
    }

    private fun session(sales: PermissionLevel = PermissionLevel.MANAGE) = UserSession(
        uid = "u1",
        email = "u@test",
        displayName = "U",
        role = UserRole.MEMBER,
        permissions = Permissions(sales = sales),
        companyId = "c1",
        hlCompanyId = "hl1",
        currency = "CAD",
        tax = TaxConfig(gstEnabled = true, gstRate = "0.05"),
        isActive = true,
    )

    private val product = Product(
        productId = "p1",
        attributes = mapOf(
            AttributeType.BRAND to AttributeRef("b", "Apple"),
            AttributeType.MODEL to AttributeRef("m", "iPhone 15"),
        ),
        defaultSellingPrice = "699.00",
    )

    private fun serial(id: String, imei: String, status: SerialStatus = SerialStatus.IN_STOCK, active: Boolean = true) =
        Serial(serialId = id, productId = "p1", imei = imei, cost = "560.00", status = status, isActive = active)

    private val s1 = serial("s1", "111111111111111")
    private val s2 = serial("s2", "222222222222222")
    private val customer = Entity(id = "cust-1", name = "Alice", roles = setOf(EntityRole.CUSTOMER))

    private fun bound(
        vm: SalesViewModel,
        repo: SalesRepository,
        serials: List<Serial> = listOf(s1, s2),
        products: List<Product> = listOf(product),
        entities: List<Entity> = listOf(customer),
        profile: Flow<CompanyProfile>? = null,
    ) {
        vm.bindForTest(
            session = session(),
            recordSaleUseCase = RecordSaleUseCase(repo),
            serials = flowOf(serials),
            products = flowOf(products),
            entities = flowOf(entities),
            observeSaleInvoiceUseCase = ObserveSaleInvoiceUseCase(repo),
            retryInvoiceUseCase = RetryInvoiceUseCase(repo),
            profile = profile,
        )
    }

    /** The live `companySettings/profile` stream, carrying whatever tax the test wants next. */
    private fun profileFlow(tax: TaxConfig) = MutableStateFlow(
        CompanyProfile(hlCompanyId = "hl1", currency = "CAD", companyName = "Acme", tax = tax),
    )

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun totals_alwaysEqualSaleCalculator() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository())
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.setUnitPrice("inv:s1", "699.00")
        vm.setSaleDiscount("0")

        val state = vm.uiState.value
        val expected = SaleCalculator.compute(
            lines = state.cartLines.map {
                com.humblesolutions.aromex.model.SaleLineInput.InventoryLineInput(
                    productId = "p1", serialId = "s1", unitPrice = "699.00", lineDiscount = "0",
                )
            },
            saleDiscount = "0",
            tax = session().tax,
            costBySerialId = mapOf("s1" to "560.00"),
        )
        assertEquals(expected, state.totals)
        assertEquals("733.95", state.totals.grandTotal) // 699 + 5% GST
        assertEquals("560.00", state.totals.cogsTotal)
    }

    @Test
    fun canConfirm_gating() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository())
        advanceUntilIdle()

        // Empty cart → cannot confirm.
        assertTrue(vm.uiState.value.errors.emptyCart)
        assertFalse(vm.uiState.value.canConfirm)

        // Line but no customer.
        vm.addUnitToCart("s1")
        assertTrue(vm.uiState.value.errors.noCustomer)
        assertFalse(vm.uiState.value.canConfirm)

        // Customer + full payment → confirmable.
        vm.selectCustomer(customer)
        vm.setCash("733.95")
        assertTrue(vm.uiState.value.canConfirm)
    }

    @Test
    fun lineDiscount_exceedingPrice_blocks_withOffendingLineFlagged() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository())
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setLineDiscount("inv:s1", "800.00") // > 699 unit price

        val errors = vm.uiState.value.errors
        assertTrue("inv:s1" in errors.lineDiscountExceedsPrice)
        assertFalse(vm.uiState.value.canConfirm)
    }

    @Test
    fun walkIn_mustPayInFull() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository())
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectWalkIn()
        assertTrue(vm.uiState.value.isWalkIn)

        // Short-paid walk-in → blocked.
        vm.setCash("700.00")
        assertTrue(vm.uiState.value.errors.walkInMustPayInFull)
        assertFalse(vm.uiState.value.canConfirm)

        // Paid in full → allowed.
        vm.setCash("733.95")
        assertFalse(vm.uiState.value.errors.walkInMustPayInFull)
        assertTrue(vm.uiState.value.canConfirm)
    }

    @Test
    fun overpayment_blocks() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository())
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setCash("800.00") // > 733.95 grand total

        assertTrue(vm.uiState.value.errors.overpayment)
        assertFalse(vm.uiState.value.canConfirm)
    }

    @Test
    fun confirmSale_success_mapsToSuccessState() = runTest {
        val repo = FakeSalesRepository(saleId = "sale-42")
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()

        assertEquals(ConfirmState.Success("sale-42"), vm.uiState.value.confirmState)
        assertEquals(1, repo.recorded.size)
        assertEquals("733.95", repo.recorded.single().grandTotal)
    }

    @Test
    fun confirmSale_alreadySold_flagsAndRemovesLine_neverCrashes() = runTest {
        val repo = FakeSalesRepository(soldImei = "111111111111111")
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.addUnitToCart("s2")
        vm.selectCustomer(customer)
        vm.setCash("0")
        vm.confirmSale()
        advanceUntilIdle()

        val state = vm.uiState.value
        val confirm = state.confirmState
        assertTrue(confirm is ConfirmState.AlreadySold)
        assertEquals("111111111111111", confirm.imei)
        assertEquals("Apple · iPhone 15", confirm.label)
        // Offending line removed; the other unit stays in the cart.
        assertTrue(state.cartLines.none { it is CartLine.Inventory && it.serialId == "s1" })
        assertTrue(state.cartLines.any { it is CartLine.Inventory && it.serialId == "s2" })
    }

    @Test
    fun confirmSale_contention_showsRetryError_keepsCart_neverCrashes() = runTest {
        // Simulates the repository translating a Firestore "too many retries"/livelock failure
        // (nothing actually sold) into SaleContentionException — the VM must surface a calm
        // retry prompt, not the raw backend exception, and leave the cart intact to re-try.
        val repo = FakeSalesRepository(contention = true)
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.addUnitToCart("s2")
        vm.selectCustomer(customer)
        vm.setCash("0")
        vm.confirmSale()
        advanceUntilIdle()

        val state = vm.uiState.value
        val confirm = state.confirmState
        assertTrue(confirm is ConfirmState.Error)
        assertTrue(confirm.message.contains("try again", ignoreCase = true))
        // Nothing was sold, so the whole cart stays put for a clean retry.
        assertTrue(state.cartLines.any { it is CartLine.Inventory && it.serialId == "s1" })
        assertTrue(state.cartLines.any { it is CartLine.Inventory && it.serialId == "s2" })
    }

    @Test
    fun startNewSale_preservesCache_clearsCart() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository())
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setCash("100.00")
        vm.setNote("hi")

        vm.startNewSale()

        val state = vm.uiState.value
        // Cart / customer / payment / note cleared.
        assertTrue(state.cartLines.isEmpty())
        assertEquals(null, state.selectedCustomer)
        assertEquals("0", state.payments.cash.ifEmpty { "0" })
        assertEquals("", state.note)
        // Cached streams + session preserved.
        assertEquals(listOf(s1, s2), state.allInStockUnits)
        assertEquals(listOf(product), state.products)
        assertEquals(listOf(customer), state.allCustomers)
        assertEquals("CAD", state.currency)
        assertTrue(state.taxConfig.gstEnabled)
    }

    @Test
    fun picker_excludesCartUnits_andNonInStockOrInactive() = runTest {
        val sold = serial("s3", "333333333333333", status = SerialStatus.SOLD)
        val archived = serial("s4", "444444444444444", active = false)
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository(), serials = listOf(s1, s2, sold, archived))
        advanceUntilIdle()

        // Non-in-stock / inactive never shown.
        var visible = vm.uiState.value.visibleUnits.map { it.serialId }
        assertEquals(listOf("s1", "s2"), visible)

        // Adding a unit removes it from the picker.
        vm.addUnitToCart("s1")
        visible = vm.uiState.value.visibleUnits.map { it.serialId }
        assertEquals(listOf("s2"), visible)
    }

    @Test
    fun observeError_clearsLoading_neverCrashes() = runTest {
        // A denied/failed inventory stream must not crash the VM; isLoading clears so the
        // screen degrades gracefully (guards the Risk #1 permission-throw / stream-error path).
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        vm.bindForTest(
            session = session(),
            recordSaleUseCase = RecordSaleUseCase(FakeSalesRepository()),
            serials = flow { throw PermissionDeniedException("inventory") },
            products = flowOf(listOf(product)),
            entities = flowOf(listOf(customer)),
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertTrue(state.allInStockUnits.isEmpty())
        // The rest of the VM still works — the customer stream loaded.
        assertEquals(listOf(customer), state.allCustomers)
    }

    @Test
    fun customerOptions_injectsWalkIn() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository())
        advanceUntilIdle()

        assertTrue(vm.uiState.value.customerOptions.any { it.id == WALK_IN_CUSTOMER_ID })
    }

    // ── Invoicing T2 (ticket #77) ──────────────────────────────────────────────

    @Test
    fun walkIn_capturesBuyerNameAndPhone_onTheSale() = runTest {
        val repo = FakeSalesRepository()
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectWalkIn()
        vm.setBuyerName("  John Smith  ") // trimmed by the use case
        vm.setBuyerPhone("(555) 012-3456x") // sanitized to digits, capped at 10
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()

        // The *field* stays digits-only, max 10 (the VM strips separators/letters as typed) — the
        // dial code lives in its own chip beside it, not in the text.
        assertEquals("5550123456", vm.uiState.value.buyerPhone)

        val rec = repo.recorded.single()
        assertEquals("John Smith", rec.buyerName)
        // What's *recorded* is the dial code joined to those digits, so the number on the sale (and
        // so on the invoice's Bill-To) is unambiguous rather than ten bare digits of unknown
        // country. The field and the recorded value are deliberately different shapes.
        assertEquals("+1 5550123456", rec.buyerPhone)
    }

    @Test
    fun namedCustomer_dropsBuyerCapture() = runTest {
        val repo = FakeSalesRepository()
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setBuyerName("Should be ignored") // buyer capture is walk-in only
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()

        val rec = repo.recorded.single()
        assertNull(rec.buyerName)
        assertNull(rec.buyerPhone)
    }

    @Test
    fun invoice_resolvesInPlace_asTheDocUpdates() = runTest {
        val repo = FakeSalesRepository(saleId = "sale-9")
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()

        // Starts PENDING (preparing…).
        assertEquals(SaleInvoiceStatus.PENDING, vm.uiState.value.invoice.status)

        // The CF issues → the row resolves in place to the number + url.
        repo.invoiceFlow.value = SaleInvoice(status = SaleInvoiceStatus.ISSUED, number = "INV-000042", url = "https://s3/x.pdf")
        advanceUntilIdle()

        val invoice = vm.uiState.value.invoice
        assertEquals(SaleInvoiceStatus.ISSUED, invoice.status)
        assertEquals("INV-000042", invoice.number)
        assertFalse(vm.uiState.value.canRetryInvoice) // Retry only shows for FAILED
    }

    @Test
    fun retry_locksWhileRunning_thenReEnablesIfItFailsAgain() = runTest {
        val repo = FakeSalesRepository(saleId = "sale-9")
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()

        // Render failed → Retry available.
        repo.invoiceFlow.value = SaleInvoice(status = SaleInvoiceStatus.FAILED, number = "INV-000042")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canRetryInvoice)

        // Gate the worker so we can observe the in-flight lock (single-click).
        val gate = CompletableDeferred<Unit>()
        repo.retryGate = gate
        vm.retryInvoice()
        advanceUntilIdle()
        assertEquals(1, repo.retryCalls)
        assertTrue(vm.uiState.value.isRetryingInvoice)      // locked
        assertFalse(vm.uiState.value.canRetryInvoice)       // can't be re-clicked
        vm.retryInvoice()                                   // a second click is a no-op while locked
        assertEquals(1, repo.retryCalls)

        // Worker returns; the doc failed again → the button is clickable once more.
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isRetryingInvoice)
        repo.invoiceFlow.value = SaleInvoice(status = SaleInvoiceStatus.PENDING, number = "INV-000042")
        repo.invoiceFlow.value = SaleInvoice(status = SaleInvoiceStatus.FAILED, number = "INV-000042")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canRetryInvoice)

        // A fresh attempt that succeeds → ISSUED, and Retry disappears.
        repo.retryGate = null
        vm.retryInvoice()
        repo.invoiceFlow.value = SaleInvoice(status = SaleInvoiceStatus.ISSUED, number = "INV-000042", url = "https://s3/x.pdf")
        advanceUntilIdle()
        assertEquals(2, repo.retryCalls)
        assertEquals(SaleInvoiceStatus.ISSUED, vm.uiState.value.invoice.status)
        assertFalse(vm.uiState.value.canRetryInvoice)
    }

    /**
     * A retry whose call fails outright has to *say so*. Without this the click is a silent
     * no-op: the spinner blinks, the same FAILED text returns, and nothing tells the cashier the
     * request never landed (functions undeployed, no network, no permission).
     */
    @Test
    fun retry_surfacesAnInlineError_whenTheCallFails() = runTest {
        val repo = FakeSalesRepository(saleId = "sale-9")
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()

        repo.invoiceFlow.value = SaleInvoice(status = SaleInvoiceStatus.FAILED, number = "INV-000042")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.invoiceRetryError)

        repo.retryError = InvoiceRetryNotAcknowledgedException()
        vm.retryInvoice()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.invoiceRetryError)
        assertEquals(SaleInvoiceStatus.FAILED, vm.uiState.value.invoice.status)
        assertFalse(vm.uiState.value.isRetryingInvoice)          // the lock always clears
        assertTrue(vm.uiState.value.canRetryInvoice)             // and Retry stays offered

        // The next click clears the stale message before it runs again.
        repo.retryError = null
        vm.retryInvoice()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.invoiceRetryError)
    }

    /**
     * The regression this guard exists for: a retry can fail on the client *after* the backend
     * succeeded (the call times out well inside the CF's 120 s budget). Forcing FAILED then would
     * bury a finished PDF behind the retry UI, and no further doc write is coming to correct it.
     */
    @Test
    fun retry_doesNotClobberAnInvoiceThatAlreadyIssued() = runTest {
        val repo = FakeSalesRepository(saleId = "sale-9")
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()

        repo.invoiceFlow.value = SaleInvoice(status = SaleInvoiceStatus.FAILED, number = "INV-000042")
        advanceUntilIdle()

        // Retry is in flight and destined to throw.
        val gate = CompletableDeferred<Unit>()
        repo.retryGate = gate
        repo.retryError = RuntimeException("deadline exceeded")
        vm.retryInvoice()
        advanceUntilIdle()

        // Meanwhile the server finished: the live stream delivers a real, openable invoice.
        repo.invoiceFlow.value =
            SaleInvoice(status = SaleInvoiceStatus.ISSUED, number = "INV-000042", url = "https://s3/x.pdf")
        advanceUntilIdle()

        // Now the call reports its failure. The settled invoice must survive it.
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(SaleInvoiceStatus.ISSUED, vm.uiState.value.invoice.status)
        assertEquals("https://s3/x.pdf", vm.uiState.value.invoice.url)
        assertFalse(vm.uiState.value.invoiceRetryError) // no error line over a working invoice
        assertFalse(vm.uiState.value.isRetryingInvoice)
        assertFalse(vm.uiState.value.canRetryInvoice)   // nothing left to retry
    }

    @Test
    fun retry_isNoOp_whenNotFailed() = runTest {
        val repo = FakeSalesRepository(saleId = "sale-9")
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()

        // Still PENDING (or ISSUED) → Retry does nothing.
        vm.retryInvoice()
        advanceUntilIdle()
        assertEquals(0, repo.retryCalls)
    }

    // ── Unpriced SKUs (ticket #101 seam) ──────────────────────────────────────
    // A SKU may now be stocked without a selling price. A blank price sanitizes to "0" on the
    // way to the ledger, so without a gate an unpriced phone rings up free.

    @Test
    fun anUnpricedUnit_cannotBeConfirmed_untilAPriceIsTyped() = runTest {
        val repo = FakeSalesRepository()
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        // The SKU carries no default selling price.
        bound(vm, repo, products = listOf(product.copy(defaultSellingPrice = "")))
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        advanceUntilIdle()

        assertEquals(setOf("inv:s1"), vm.uiState.value.errors.unpricedLines)
        assertFalse(vm.uiState.value.canConfirm)

        // Confirm is inert while the price is missing — no silent $0 sale.
        vm.confirmSale()
        advanceUntilIdle()
        assertTrue(repo.recorded.isEmpty())

        vm.setUnitPrice("inv:s1", "800.00")
        vm.setCash("840.00") // 800 + 5% GST
        assertTrue(vm.uiState.value.errors.unpricedLines.isEmpty())
        assertTrue(vm.uiState.value.canConfirm)
    }

    @Test
    fun anExplicitZeroPrice_isAllowed_givingSomethingAwayIsARealTransaction() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository(), products = listOf(product.copy(defaultSellingPrice = "")))
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectCustomer(customer)
        vm.setUnitPrice("inv:s1", "0")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.errors.unpricedLines.isEmpty())
        assertTrue(vm.uiState.value.canConfirm)
    }

    @Test
    fun anUnpricedLine_neverReadsAsDiscounted() = runTest {
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository(), products = listOf(product.copy(defaultSellingPrice = "")))
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.setUnitPrice("inv:s1", "800.00")

        // There is no original price to strike through — showing "was $0.00" would be a lie.
        val line = vm.uiState.value.cartLines.filterIsInstance<CartLine.Inventory>().single()
        assertFalse(line.isDiscounted)
    }

    // ── Live tax (ticket #98) ─────────────────────────────────────────────────
    // UserSession.tax is captured at sign-in and never refreshed. Without the live stream, an
    // admin changing GST at noon leaves every open till charging the old rate all day.

    @Test
    fun taxRateChange_reachesARunningTill_andRecalculatesTheCart() = runTest {
        val profile = profileFlow(TaxConfig(gstEnabled = true, gstRate = "0.05"))
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository(), profile = profile)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.setUnitPrice("inv:s1", "699.00")
        advanceUntilIdle()
        assertEquals("733.95", vm.uiState.value.totals.grandTotal) // 699 + 5%

        profile.value = profile.value.copy(tax = TaxConfig(gstEnabled = true, gstRate = "0.06"))
        advanceUntilIdle()

        assertEquals("740.94", vm.uiState.value.totals.grandTotal) // 699 + 6%
        // The cashier may already have read the old total out loud — say why it moved.
        assertTrue(vm.uiState.value.taxChangedMidSale)
    }

    @Test
    fun taxRateChange_withAnEmptyCart_isSilent() = runTest {
        val profile = profileFlow(TaxConfig(gstEnabled = true, gstRate = "0.05"))
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository(), profile = profile)
        advanceUntilIdle()

        profile.value = profile.value.copy(tax = TaxConfig(gstEnabled = true, gstRate = "0.06"))
        advanceUntilIdle()

        assertEquals("0.06", vm.uiState.value.taxConfig.gstRate)
        // Nothing on screen changed under anyone — a banner here would be noise.
        assertFalse(vm.uiState.value.taxChangedMidSale)
    }

    @Test
    fun theRecordedSaleUsesTheLiveRate_notTheOneFromSignIn() = runTest {
        val repo = FakeSalesRepository()
        val profile = profileFlow(TaxConfig(gstEnabled = true, gstRate = "0.05"))
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo, profile = profile)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.setUnitPrice("inv:s1", "699.00")
        vm.selectCustomer(customer)
        profile.value = profile.value.copy(tax = TaxConfig(gstEnabled = true, gstRate = "0.06"))
        advanceUntilIdle()
        vm.setCash("740.94")
        vm.confirmSale()
        advanceUntilIdle()

        // RecordSaleUseCase recomputes totals at record time — from the live rate, or the sale
        // would be taxed differently from the total the customer was quoted.
        val record = repo.recorded.single()
        assertEquals("740.94", record.grandTotal)
        assertEquals("0.06", record.taxLines.single().rate)
    }

    @Test
    fun startNewSale_clearsTheTaxChangeBanner() = runTest {
        val profile = profileFlow(TaxConfig(gstEnabled = true, gstRate = "0.05"))
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, FakeSalesRepository(), profile = profile)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        profile.value = profile.value.copy(tax = TaxConfig(gstEnabled = true, gstRate = "0.06"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value.taxChangedMidSale)

        vm.startNewSale()
        assertFalse(vm.uiState.value.taxChangedMidSale)
    }

    @Test
    fun startNewSale_clearsBuyerAndInvoice() = runTest {
        val repo = FakeSalesRepository(saleId = "sale-9")
        val vm = SalesViewModel(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        bound(vm, repo)
        advanceUntilIdle()

        vm.addUnitToCart("s1")
        vm.selectWalkIn()
        vm.setBuyerName("John")
        vm.setCash("733.95")
        vm.confirmSale()
        advanceUntilIdle()
        repo.invoiceFlow.value = SaleInvoice(status = SaleInvoiceStatus.ISSUED, number = "INV-1", url = "https://s3/x.pdf")
        advanceUntilIdle()

        vm.startNewSale()

        val state = vm.uiState.value
        assertEquals("", state.buyerName)
        assertEquals("", state.buyerPhone)
        assertEquals(SaleInvoiceStatus.PENDING, state.invoice.status)
        assertNull(state.invoice.number)
    }
}
