package com.humblesolutions.aromex.ui.sales

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.BackendCompanySettingsRepository
import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.BackendInventoryRepository
import com.humblesolutions.aromex.data.BackendSalesRepository
import com.humblesolutions.aromex.model.AlreadySoldException
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.ResolvedSaleLine
import com.humblesolutions.aromex.model.SaleInput
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleLineInput
import com.humblesolutions.aromex.model.SaleTotals
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.model.WALK_IN_CUSTOMER_ID
import com.humblesolutions.aromex.model.WALK_IN_CUSTOMER_NAME
import com.humblesolutions.aromex.usecase.ObserveCompanyProfileUseCase
import com.humblesolutions.aromex.usecase.ObserveEntitiesUseCase
import com.humblesolutions.aromex.usecase.ObserveInventoryUseCase
import com.humblesolutions.aromex.usecase.ObserveSaleInvoiceUseCase
import com.humblesolutions.aromex.usecase.RecordSaleUseCase
import com.humblesolutions.aromex.usecase.RetryInvoiceUseCase
import com.humblesolutions.aromex.usecase.SaleCalculator
import com.humblesolutions.aromex.usecase.SaveBuyerPhoneUseCase
import com.humblesolutions.aromex.usecase.SaveBuyerTaxNumberUseCase
import com.humblesolutions.aromex.util.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Submission lifecycle of a checkout (ticket #62). [Idle] before/after a fresh cart,
 * [Submitting] while [RecordSaleUseCase] runs, [Success] with the new `saleId`,
 * [AlreadySold] when a unit lost the race (its cart line is flagged + removed so the
 * cashier can re-ring), and [Error] for a permission/network failure. Never a crash.
 */
sealed interface ConfirmState {
    data object Idle : ConfirmState
    data object Submitting : ConfirmState
    data class Success(val saleId: String) : ConfirmState
    data class AlreadySold(val imei: String, val label: String) : ConfirmState
    data class Error(val message: String) : ConfirmState
}

/**
 * One cart line (ticket #62): an [Inventory] unit (a serialized phone leaving stock, with
 * its cost/label/listPrice snapshotted from cache at add-time) or a revenue-only [Custom]
 * line. All money is a decimal **string**. [lineId] is stable so per-line edits/removes
 * target the right row.
 */
sealed interface CartLine {
    val lineId: String
    val unitPrice: String
    val lineDiscount: String

    data class Inventory(
        override val lineId: String,
        val productId: String,
        val serialId: String,
        val imei: String,
        val label: String,
        val listPrice: String,
        override val unitPrice: String,
        override val lineDiscount: String = "0",
        val cost: String,
    ) : CartLine

    data class Custom(
        override val lineId: String,
        val name: String,
        override val unitPrice: String,
        override val lineDiscount: String = "0",
    ) : CartLine
}

/**
 * The checkout gating errors (ticket #62). Each maps to a UI hint; [SalesUiState.canConfirm]
 * is true only when every flag is clear. [lineDiscountExceedsPrice] holds the offending
 * [lineId]s so a specific row can be highlighted.
 */
data class SaleErrors(
    val emptyCart: Boolean = false,
    val noCustomer: Boolean = false,
    val lineDiscountExceedsPrice: Set<String> = emptySet(),
    /**
     * Lines whose price was never typed. A SKU may be stocked unpriced (ticket #101), and a blank
     * price sanitizes to "0" on the way to the ledger — so without this gate an unpriced phone
     * rings up FREE, with nothing on screen to suggest otherwise. An explicit "0" is left alone:
     * giving something away deliberately is a real transaction; only a never-typed price is the
     * accident. Mirrors the Desktop gate.
     */
    val unpricedLines: Set<String> = emptySet(),
    val saleDiscountExceedsSubtotal: Boolean = false,
    val overpayment: Boolean = false,
    val walkInMustPayInFull: Boolean = false,
)

private val EMPTY_TOTALS = SaleTotals(
    subtotal = "0",
    taxableAmount = "0",
    taxLines = emptyList(),
    taxTotal = "0",
    grandTotal = "0",
    cogsTotal = "0",
)

data class SalesUiState(
    val isLoading: Boolean = true,
    val currency: String = "",
    /** Whether the signed-in user can edit contacts (`profiles` MANAGE) — gates "Save to contact". */
    val canManageProfiles: Boolean = false,
    val taxConfig: TaxConfig = TaxConfig(),
    /**
     * Set when an admin changed the tax rate while this cart had items in it. The total moved under
     * the cashier's hands, so it is explained rather than left to be noticed. Mirrors Desktop.
     */
    val taxChangedMidSale: Boolean = false,
    // ── Item picker (cached, filtered client-side) ─────────────────────────────
    val allInStockUnits: List<Serial> = emptyList(),
    val products: List<Product> = emptyList(),
    val pickerSearchQuery: String = "",
    /** A location attributeId to narrow the picker; null = all locations. */
    val pickerLocationFilter: String? = null,
    // ── Customer picker (cached, incl. injected Walk-in) ───────────────────────
    val allCustomers: List<Entity> = emptyList(),
    val customerSearchQuery: String = "",
    val selectedCustomer: Entity? = null,
    // ── Cart ───────────────────────────────────────────────────────────────────
    val cartLines: List<CartLine> = emptyList(),
    val saleDiscount: String = "0",
    /**
     * Tax-inclusive pricing toggle (ticket #106) — when true the typed prices already contain tax
     * and [SaleTotals] is computed by backing it out. Per sale, defaults to off, reset by
     * [SalesViewModel.startNewSale] so it can never carry into the next customer.
     */
    val taxInclusive: Boolean = false,
    val payments: PaymentInput = PaymentInput(),
    val note: String = "",
    // ── Walk-in buyer capture (ticket #77; only used when [isWalkIn]) ───────────
    val buyerName: String = "",
    val buyerPhone: String = "",
    // ── Customer tax number at checkout (ticket #106 follow-up) ─────────────────
    /** Prefilled from the selected customer, editable per sale, snapshotted onto the sale. */
    val buyerTaxNumber: String = "",
    /** True while a "Save to contact" write is in flight (disables the button). */
    val savingTaxNumber: Boolean = false,
    /** Set true briefly after a successful save; cleared on the next edit. */
    val taxNumberSaved: Boolean = false,
    /** Set true if the last save failed; cleared on retry/edit. */
    val taxNumberSaveError: Boolean = false,
    // ── Customer contact phone at checkout (mirrors the tax-number field) ───────
    /** Prefilled from the selected customer's primary number, editable per sale, snapshotted. */
    val buyerContactPhone: String = "",
    /** True while a phone "Save to contact" write is in flight (disables the button). */
    val savingPhone: Boolean = false,
    /** Set true briefly after a successful phone save; cleared on the next edit. */
    val phoneSaved: Boolean = false,
    /** Set true if the last phone save failed; cleared on retry/edit. */
    val phoneSaveError: Boolean = false,
    // ── Derived (rebuilt by recompute) ─────────────────────────────────────────
    val totals: SaleTotals = EMPTY_TOTALS,
    val errors: SaleErrors = SaleErrors(emptyCart = true, noCustomer = true),
    // ── Submission ──────────────────────────────────────────────────────────────
    val confirmState: ConfirmState = ConfirmState.Idle,
    // ── Invoice (ticket #77): observed live off the sale doc after a successful sale ──
    val invoice: SaleInvoice = SaleInvoice(),
    /** True while a manual Retry is in flight — disables the button so it can't be re-clicked. */
    val isRetryingInvoice: Boolean = false,
    /**
     * True when the last manual Retry call itself couldn't reach the invoice service (callable
     * missing / unauthenticated / functions not deployed) — the live stream never settles in that
     * case, so we surface an inline reassurance instead of a silent no-op. Cleared on the next tap.
     */
    val invoiceRetryError: Boolean = false,
) {
    /** True when the buyer is the reserved anonymous party (forces pay-in-full). */
    val isWalkIn: Boolean
        get() = selectedCustomer?.let { it.isWalkIn || it.id == WALK_IN_CUSTOMER_ID } == true

    /**
     * Whether "Save to contact" is offered for the tax-number field (ticket #106 follow-up): a named
     * customer is selected, the user can manage profiles, the field differs from the stored value,
     * and no save is in flight. Never for a walk-in (no contact to save to).
     */
    val canSaveTaxToContact: Boolean
        get() {
            val customer = selectedCustomer ?: return false
            if (isWalkIn || !canManageProfiles || savingTaxNumber) return false
            return buyerTaxNumber.trim() != (customer.taxNumber ?: "")
        }

    /** The phone twin of [canSaveTaxToContact] — compares against the stored primary number. */
    val canSavePhoneToContact: Boolean
        get() {
            val customer = selectedCustomer ?: return false
            if (isWalkIn || !canManageProfiles || savingPhone) return false
            return buyerContactPhone.trim() != (customer.phones.firstOrNull() ?: "")
        }

    /** The Retry affordance shows only for a FAILED invoice, and is disabled mid-retry. */
    val canRetryInvoice: Boolean
        get() = invoice.status == SaleInvoiceStatus.FAILED && !isRetryingInvoice

    /** Σ of the three payment methods (blank → 0). */
    val amountPaid: String
        get() = Money.sum(listOf(payments.cash, payments.card, payments.bank).map { it.orZero() })

    /** `grandTotal − amountPaid` — a named customer's carried balance (0 for a walk-in). */
    val balanceRemaining: String
        get() = Money.subtract(totals.grandTotal, amountPaid)

    /** The serialIds currently in the cart — excluded from the picker so a unit can't be added twice. */
    val cartSerialIds: Set<String>
        get() = cartLines.filterIsInstance<CartLine.Inventory>().map { it.serialId }.toSet()

    /**
     * The picker rows: in-stock/active units, minus anything already in the cart, narrowed
     * by the optional location filter and the search query (matches IMEI or SKU label).
     */
    val visibleUnits: List<Serial>
        get() {
            val inCart = cartSerialIds
            val q = pickerSearchQuery.trim()
            return allInStockUnits.asSequence()
                .filter { it.status == SerialStatus.IN_STOCK && it.isActive }
                .filter { it.serialId !in inCart }
                .filter { pickerLocationFilter == null || it.location.attributeId == pickerLocationFilter }
                .filter { serial ->
                    q.isEmpty() ||
                        serial.imei.contains(q, ignoreCase = true) ||
                        labelFor(serial).contains(q, ignoreCase = true)
                }
                .toList()
        }

    /**
     * Company parties as selectable customers — always including the reserved Walk-in
     * Customer (injected if the observe hasn't materialized it yet), sorted with customers
     * first then by name, then filtered by the search query.
     */
    val customerOptions: List<Entity>
        get() {
            val withWalkIn = if (allCustomers.any { it.id == WALK_IN_CUSTOMER_ID }) {
                allCustomers
            } else {
                allCustomers + Entity(
                    id = WALK_IN_CUSTOMER_ID,
                    name = WALK_IN_CUSTOMER_NAME,
                    roles = setOf(EntityRole.CUSTOMER),
                    isWalkIn = true,
                )
            }
            val q = customerSearchQuery.trim()
            return withWalkIn.asSequence()
                .filter { it.isActive }
                .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
                .sortedWith(
                    compareByDescending<Entity> { EntityRole.CUSTOMER in it.roles }
                        .thenBy { it.name.lowercase() },
                )
                .toList()
        }

    /** Confirm is allowed only when the cart is valid and no gate is tripped. */
    val canConfirm: Boolean
        get() = confirmState !is ConfirmState.Submitting &&
            cartLines.isNotEmpty() &&
            selectedCustomer != null &&
            errors.lineDiscountExceedsPrice.isEmpty() &&
            errors.unpricedLines.isEmpty() &&
            !errors.saleDiscountExceedsSubtotal &&
            !errors.overpayment &&
            !errors.walkInMustPayInFull

    /** Human-readable SKU label for a serial, from its cached product (blank → the IMEI). */
    fun labelFor(serial: Serial): String {
        val product = products.firstOrNull { it.productId == serial.productId } ?: return serial.imei
        return product.attributes.skuLabel().ifBlank { serial.imei }
    }
}

/**
 * Android checkout ViewModel (ticket #62): builds a cart from cached in-stock units, edits
 * prices/discounts, picks a customer or the Walk-in, takes a split payment, shows live
 * totals via the shared [SaleCalculator] (never re-implemented here), and confirms through
 * [RecordSaleUseCase] — mapping the "already sold" race to a graceful state. No screens.
 *
 * Mirrors `AddStockViewModel`: manual DI in [bind], the synthetic-default injection (here
 * the Walk-in Customer), and [startNewSale] preserving the cached inventory/entities/session
 * (the #58 reset-preserves-cache lesson). The unit-tested twin is the Desktop VM.
 */
class SalesViewModel(application: Application) : AndroidViewModel(application) {

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null
    private var recordSaleUseCase: RecordSaleUseCase? = null
    private var saveBuyerTaxNumberUseCase: SaveBuyerTaxNumberUseCase? = null
    private var saveBuyerPhoneUseCase: SaveBuyerPhoneUseCase? = null
    private var observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = null
    private var retryInvoiceUseCase: RetryInvoiceUseCase? = null

    /** The live invoice observation for the just-completed sale; cancelled on new-sale/teardown. */
    private var invoiceJob: Job? = null

    private var customSeq = 0L

    private val _uiState = MutableStateFlow(SalesUiState())
    val uiState: StateFlow<SalesUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config
        _uiState.update {
            it.copy(
                isLoading = true,
                currency = session.currency,
                canManageProfiles = session.permissions.profiles == PermissionLevel.MANAGE,
                taxConfig = session.tax,
            )
        }

        val app = getApplication<Application>()
        val sales = BackendSalesRepository(app, config)
        val inv = BackendInventoryRepository(app, config)
        val entities = BackendEntityRepository(app, config)
        recordSaleUseCase = RecordSaleUseCase(sales)
        saveBuyerTaxNumberUseCase = SaveBuyerTaxNumberUseCase(entities)
        saveBuyerPhoneUseCase = SaveBuyerPhoneUseCase(entities)
        observeSaleInvoiceUseCase = ObserveSaleInvoiceUseCase(sales)
        retryInvoiceUseCase = RetryInvoiceUseCase(sales)

        // Follow companySettings/profile so a tax change reaches this till within seconds.
        // UserSession.tax is captured at sign-in and never refreshed, so without this an admin
        // changing GST at noon leaves the phone charging the old rate all day (ticket #98).
        val profileUseCase = ObserveCompanyProfileUseCase(BackendCompanySettingsRepository(app, config))
        viewModelScope.launch {
            runCatching {
                profileUseCase.execute()
                    .catch { /* keep the rate we have; a stale rate beats no checkout at all */ }
                    .collect { profile ->
                        _uiState.update { st ->
                            if (profile.tax == st.taxConfig) return@update st
                            st.copy(
                                taxConfig = profile.tax,
                                // Only worth flagging if it changes a total someone can already see.
                                taxChangedMidSale = st.cartLines.isNotEmpty(),
                            ).recomputed()
                        }
                    }
            }
        }

        val inventoryUseCase = ObserveInventoryUseCase(inv)
        val entitiesUseCase = ObserveEntitiesUseCase(entities)

        // Live reads: stock changes as units sell, the customer list changes as parties are
        // added — the cart itself is filtered/derived client-side (the ViewModel is the cache).
        // The observe use cases throw PermissionDeniedException *synchronously* (before the
        // Flow), which .catch can't see — so runCatching guards construction; .catch guards
        // stream errors; either way isLoading clears so the screen degrades, never crashes.
        viewModelScope.launch {
            runCatching {
                inventoryUseCase.observeInStockSerials(session)
                    .catch { _uiState.update { it.copy(isLoading = false) } }
                    .collect { list -> _uiState.update { it.copy(allInStockUnits = list, isLoading = false).recomputed() } }
            }.onFailure { _uiState.update { it.copy(isLoading = false) } }
        }
        viewModelScope.launch {
            runCatching {
                inventoryUseCase.observeProducts(session)
                    .catch { }
                    .collect { list -> _uiState.update { it.copy(products = list).recomputed() } }
            }
        }
        viewModelScope.launch {
            runCatching {
                entitiesUseCase.execute(session)
                    .catch { }
                    .collect { list -> _uiState.update { it.copy(allCustomers = list).recomputed() } }
            }
        }
    }

    // ── Item picker ──────────────────────────────────────────────────────────────

    fun onPickerSearchChanged(query: String) = _uiState.update { it.copy(pickerSearchQuery = query) }

    fun onPickerLocationFilterChanged(locationId: String?) =
        _uiState.update { it.copy(pickerLocationFilter = locationId) }

    /**
     * Add an in-stock unit to the cart, snapshotting its cost/label/listPrice/imei from the
     * cached [Serial] + [Product] right now (the sale freezes those; T1 won't re-read products).
     * The default editable [CartLine.Inventory.unitPrice] is the SKU's list price. No-op if the
     * serial isn't cached or is already in the cart.
     */
    fun addUnitToCart(serialId: String) {
        val state = _uiState.value
        if (serialId in state.cartSerialIds) return
        val serial = state.allInStockUnits.firstOrNull { it.serialId == serialId } ?: return
        val product = state.products.firstOrNull { it.productId == serial.productId }
        val listPrice = product?.defaultSellingPrice ?: "0"
        val line = CartLine.Inventory(
            lineId = "inv:$serialId",
            productId = serial.productId,
            serialId = serialId,
            imei = serial.imei,
            label = state.labelFor(serial),
            listPrice = listPrice,
            unitPrice = listPrice,
            lineDiscount = "0",
            cost = serial.cost,
        )
        _uiState.update { it.copy(cartLines = it.cartLines + line).recomputed() }
    }

    /** Add a revenue-only ad-hoc line (a case, a fee) — no stock, no cost-of-goods. */
    fun addCustomLine(name: String, price: String) {
        if (name.isBlank()) return
        val line = CartLine.Custom(
            lineId = "custom:${customSeq++}",
            name = name.trim(),
            unitPrice = price.orZero(),
            lineDiscount = "0",
        )
        _uiState.update { it.copy(cartLines = it.cartLines + line).recomputed() }
    }

    // ── Cart line edits ────────────────────────────────────────────────────────

    fun setUnitPrice(lineId: String, price: String) = mutateLine(lineId) { line ->
        when (line) {
            is CartLine.Inventory -> line.copy(unitPrice = price)
            is CartLine.Custom -> line.copy(unitPrice = price)
        }
    }

    fun setLineDiscount(lineId: String, discount: String) = mutateLine(lineId) { line ->
        when (line) {
            is CartLine.Inventory -> line.copy(lineDiscount = discount)
            is CartLine.Custom -> line.copy(lineDiscount = discount)
        }
    }

    fun removeLine(lineId: String) =
        _uiState.update { it.copy(cartLines = it.cartLines.filterNot { l -> l.lineId == lineId }).recomputed() }

    fun setSaleDiscount(discount: String) = _uiState.update { it.copy(saleDiscount = discount).recomputed() }

    /** Flip tax-inclusive pricing for this sale (ticket #106); recomputes the totals live. */
    fun setTaxInclusive(inclusive: Boolean) =
        _uiState.update { it.copy(taxInclusive = inclusive).recomputed() }

    private fun mutateLine(lineId: String, transform: (CartLine) -> CartLine) = _uiState.update { state ->
        state.copy(cartLines = state.cartLines.map { if (it.lineId == lineId) transform(it) else it }).recomputed()
    }

    // ── Customer picker ──────────────────────────────────────────────────────────

    fun onCustomerSearchChanged(query: String) = _uiState.update { it.copy(customerSearchQuery = query) }

    fun selectCustomer(customer: Entity) = _uiState.update {
        // Prefill the tax-number field from the picked customer (ticket #106 follow-up).
        it.copy(
            selectedCustomer = customer,
            buyerTaxNumber = customer.taxNumber ?: "",
            taxNumberSaved = false,
            taxNumberSaveError = false,
            buyerContactPhone = customer.phones.firstOrNull() ?: "",
            phoneSaved = false,
            phoneSaveError = false,
        ).recomputed()
    }

    /** Select the reserved Walk-in Customer (forces pay-in-full gating). */
    fun selectWalkIn() = _uiState.update { state ->
        val walkIn = state.allCustomers.firstOrNull { it.id == WALK_IN_CUSTOMER_ID }
            ?: Entity(
                id = WALK_IN_CUSTOMER_ID,
                name = WALK_IN_CUSTOMER_NAME,
                roles = setOf(EntityRole.CUSTOMER),
                isWalkIn = true,
            )
        // A walk-in has no contact; start the tax field empty (still usable for the bill). The
        // walk-in phone is captured by the separate Bill-To phone field, so leave this one empty.
        state.copy(
            selectedCustomer = walkIn,
            buyerTaxNumber = "",
            taxNumberSaved = false,
            taxNumberSaveError = false,
            buyerContactPhone = "",
            phoneSaved = false,
            phoneSaveError = false,
        ).recomputed()
    }

    // ── Customer tax number (ticket #106 follow-up) ───────────────────────────────

    /** Update the per-sale tax number; clears any prior save status. */
    fun setBuyerTaxNumber(value: String) = _uiState.update {
        it.copy(buyerTaxNumber = value, taxNumberSaved = false, taxNumberSaveError = false)
    }

    /** Update the per-sale contact phone; clears any prior save status. */
    fun setBuyerContactPhone(value: String) = _uiState.update {
        it.copy(buyerContactPhone = value, phoneSaved = false, phoneSaveError = false)
    }

    /**
     * "Save to contact": persist the current tax number onto the selected named customer. Gated on
     * `profiles` MANAGE in the use case; offered only when [SalesUiState.canSaveTaxToContact]. On
     * success the cached customer is updated so the button settles and future selects prefill it.
     */
    fun saveBuyerTaxNumberToContact() {
        val useCase = saveBuyerTaxNumberUseCase ?: return
        val current = session ?: return
        val state = _uiState.value
        val customer = state.selectedCustomer ?: return
        if (!state.canSaveTaxToContact) return
        val value = state.buyerTaxNumber.trim().ifEmpty { null }
        _uiState.update { it.copy(savingTaxNumber = true, taxNumberSaved = false, taxNumberSaveError = false) }
        viewModelScope.launch {
            runCatching { useCase.execute(current, customer.id, value) }
                .onSuccess {
                    _uiState.update { s ->
                        val updated = customer.copy(taxNumber = value)
                        s.copy(
                            savingTaxNumber = false,
                            taxNumberSaved = true,
                            selectedCustomer = if (s.selectedCustomer?.id == customer.id) updated else s.selectedCustomer,
                            allCustomers = s.allCustomers.map { if (it.id == customer.id) updated else it },
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(savingTaxNumber = false, taxNumberSaveError = true) }
                }
        }
    }

    /**
     * "Save to contact" for the phone — the mirror of [saveBuyerTaxNumberToContact]. Persists the
     * current phone as the selected customer's *primary* number, preserving any secondary numbers.
     * Gated on `profiles` MANAGE; offered only when [SalesUiState.canSavePhoneToContact].
     */
    fun saveBuyerPhoneToContact() {
        val useCase = saveBuyerPhoneUseCase ?: return
        val current = session ?: return
        val state = _uiState.value
        val customer = state.selectedCustomer ?: return
        if (!state.canSavePhoneToContact) return
        val newPhones = buildList {
            state.buyerContactPhone.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(customer.phones.drop(1))
        }
        _uiState.update { it.copy(savingPhone = true, phoneSaved = false, phoneSaveError = false) }
        viewModelScope.launch {
            runCatching { useCase.execute(current, customer.id, newPhones) }
                .onSuccess {
                    _uiState.update { s ->
                        val updated = customer.copy(phones = newPhones)
                        s.copy(
                            savingPhone = false,
                            phoneSaved = true,
                            selectedCustomer = if (s.selectedCustomer?.id == customer.id) updated else s.selectedCustomer,
                            allCustomers = s.allCustomers.map { if (it.id == customer.id) updated else it },
                        )
                    }
                }
                .onFailure {
                    _uiState.update { it.copy(savingPhone = false, phoneSaveError = true) }
                }
        }
    }

    // ── Payment + note ───────────────────────────────────────────────────────────

    fun setCash(amount: String) = _uiState.update { it.copy(payments = it.payments.copy(cash = amount)).recomputed() }
    fun setCard(amount: String) = _uiState.update { it.copy(payments = it.payments.copy(card = amount)).recomputed() }
    fun setBank(amount: String) = _uiState.update { it.copy(payments = it.payments.copy(bank = amount)).recomputed() }

    fun setNote(note: String) = _uiState.update { it.copy(note = note) }

    // ── Walk-in buyer capture (ticket #77) ────────────────────────────────────────
    // Free-text; no gating (never blocks Confirm). The use case keeps these only for a
    // walk-in and trims blank → null → the CF falls back to "Walk-in Customer".
    fun setBuyerName(name: String) = _uiState.update { it.copy(buyerName = name) }
    /** Phone is digits-only, capped at 10 — a keyboard hint alone can't stop paste/hardware input. */
    fun setBuyerPhone(phone: String) =
        _uiState.update { it.copy(buyerPhone = phone.filter(Char::isDigit).take(10)) }

    // ── Submission ───────────────────────────────────────────────────────────────

    /**
     * Build a [SaleInput] + per-inventory-line [ResolvedSaleLine] snapshots from the cart and
     * commit through [RecordSaleUseCase]. Maps success → [ConfirmState.Success]; an
     * [AlreadySoldException] → [ConfirmState.AlreadySold] **and flags/removes the offending
     * line** so the cashier can re-ring; permission/validation/network → [ConfirmState.Error].
     * Never crashes.
     */
    fun confirmSale() {
        val current = session ?: return
        val useCase = recordSaleUseCase ?: return
        val state = _uiState.value
        if (!state.canConfirm) return

        val lines = state.cartLines.map { it.toInput() }
        val resolved = state.cartLines.filterIsInstance<CartLine.Inventory>().map {
            ResolvedSaleLine(
                serialId = it.serialId,
                imei = it.imei,
                label = it.label,
                listPrice = it.listPrice,
                cost = it.cost,
            )
        }
        val customer = state.selectedCustomer ?: return
        val saleInput = SaleInput(
            customerEntityId = customer.id,
            isWalkIn = state.isWalkIn,
            lines = lines,
            saleDiscount = state.saleDiscount.orZero(),
            payment = PaymentInput(
                cash = state.payments.cash.orZero(),
                card = state.payments.card.orZero(),
                bank = state.payments.bank.orZero(),
            ),
            note = state.note.trim().ifEmpty { null },
            buyerName = state.buyerName,
            // Walk-in: the Bill-To phone field. Named customer: the contact-phone field, prefilled
            // from their Entity and editable for this sale (mirrors buyerTaxNumber). Never both.
            buyerPhone = if (state.isWalkIn) state.buyerPhone else state.buyerContactPhone.trim(),
            taxInclusive = state.taxInclusive,
            // The per-sale tax number as edited at checkout (prefilled from the customer). Snapshotted
            // onto the sale for the invoice; carried for a walk-in too. Blank → null in the use case.
            buyerTaxNumber = state.buyerTaxNumber,
            // The business date (ticket #107). Android has no date picker yet, so a phone always
            // records today — which is correct for a sale rung up at the counter. Backdating is
            // Desktop-only for now; when the picker lands here, this reads from state.
            saleDate = System.currentTimeMillis(),
        )

        _uiState.update { it.copy(confirmState = ConfirmState.Submitting) }
        viewModelScope.launch {
            runCatching { useCase.execute(current, saleInput, resolved, now = System.currentTimeMillis()) }
                .onSuccess { saleId ->
                    _uiState.update {
                        it.copy(
                            confirmState = ConfirmState.Success(saleId),
                            invoice = SaleInvoice(),
                            isRetryingInvoice = false,
                        )
                    }
                    observeInvoice(saleId)
                }
                .onFailure { e ->
                    when (e) {
                        is AlreadySoldException -> _uiState.update { s ->
                            val offending = s.cartLines.filterIsInstance<CartLine.Inventory>()
                                .firstOrNull { it.imei == e.imei }
                            s.copy(
                                cartLines = s.cartLines.filterNot { it.lineId == offending?.lineId },
                                confirmState = ConfirmState.AlreadySold(e.imei, offending?.label ?: e.imei),
                            ).recomputed()
                        }
                        is PermissionDeniedException -> _uiState.update {
                            it.copy(confirmState = ConfirmState.Error("You don't have permission to record sales."))
                        }
                        else -> _uiState.update {
                            it.copy(confirmState = ConfirmState.Error(e.message ?: "Sale failed"))
                        }
                    }
                }
        }
    }

    /** Dismiss a terminal confirm state (e.g. after showing the AlreadySold/Error message). */
    fun dismissConfirmState() = _uiState.update { it.copy(confirmState = ConfirmState.Idle) }

    // ── Invoice (ticket #77) ──────────────────────────────────────────────────────

    /**
     * Observe the just-completed sale's invoice live so the Sale-complete row resolves in place
     * (PENDING → ISSUED/FAILED) as the CF issues the PDF. Errors are swallowed — a stalled stream
     * simply leaves the row "preparing"; it must never make a committed sale read as failed.
     */
    private fun observeInvoice(saleId: String) {
        val useCase = observeSaleInvoiceUseCase ?: return
        invoiceJob?.cancel()
        invoiceJob = viewModelScope.launch {
            runCatching {
                useCase.execute(saleId)
                    .catch { }
                    .collect { inv -> _uiState.update { it.copy(invoice = inv) } }
            }
        }
    }

    /**
     * Cashier-facing Retry for a FAILED invoice: re-issue **now** instead of waiting for the
     * reconcile sweep. Optimistically shows "preparing" and locks the button (single-click); the
     * live [observeInvoice] stream carries the settled result — ISSUED hides the button, a fresh
     * FAILED re-enables it (the lock clears when the call returns). Never touches the sale itself.
     */
    fun retryInvoice() {
        val state = _uiState.value
        val saleId = (state.confirmState as? ConfirmState.Success)?.saleId ?: return
        val useCase = retryInvoiceUseCase ?: return
        if (!state.canRetryInvoice) return
        _uiState.update {
            it.copy(
                isRetryingInvoice = true,
                invoiceRetryError = false,
                invoice = it.invoice.copy(status = SaleInvoiceStatus.PENDING),
            )
        }
        viewModelScope.launch {
            runCatching { useCase.execute(saleId) }
                .onFailure {
                    // Roll the optimistic PENDING back to FAILED and explain — *unless* the live
                    // stream has already settled. A client-side failure does not mean the server
                    // did nothing: the callable client gives up well before the CF's 120 s budget,
                    // so a slow-but-successful re-issue lands ISSUED on the doc while this throws.
                    // Overwriting that with FAILED would hide a finished invoice behind the retry
                    // UI, and nothing would correct it — no further doc write is coming.
                    _uiState.update { state ->
                        if (state.invoice.hasSettled) {
                            state
                        } else {
                            state.copy(
                                invoiceRetryError = true,
                                invoice = state.invoice.copy(status = SaleInvoiceStatus.FAILED),
                            )
                        }
                    }
                }
            _uiState.update { it.copy(isRetryingInvoice = false) }
        }
    }

    /**
     * Clear the cart/customer/payments/note for the next sale but **preserve** the cached
     * inventory/customers/products + session/currency/tax — the observed lists won't re-emit
     * just because the form cleared, so wiping them would empty the pickers (the #58 lesson).
     */
    fun startNewSale() {
        invoiceJob?.cancel()
        invoiceJob = null
        _uiState.update {
            it.copy(
                pickerSearchQuery = "",
                pickerLocationFilter = null,
                customerSearchQuery = "",
                selectedCustomer = null,
                cartLines = emptyList(),
                saleDiscount = "0",
                // Reset to tax-exclusive so a toggle flipped for one customer can't silently
                // under-charge tax on the next sale (ticket #106).
                taxInclusive = false,
                payments = PaymentInput(),
                note = "",
                buyerName = "",
                buyerPhone = "",
                buyerTaxNumber = "",
                buyerContactPhone = "",
                taxChangedMidSale = false,
                savingTaxNumber = false,
                taxNumberSaved = false,
                taxNumberSaveError = false,
                savingPhone = false,
                phoneSaved = false,
                phoneSaveError = false,
                confirmState = ConfirmState.Idle,
                invoice = SaleInvoice(),
                isRetryingInvoice = false,
                invoiceRetryError = false,
            ).recomputed()
        }
    }
}

// ── Pure derivation helpers ───────────────────────────────────────────────────

/**
 * Rebuild the derived [SalesUiState.totals] (always the shared [SaleCalculator] output — no
 * duplicated math) and [SalesUiState.errors]. Called after every cart/customer/payment
 * mutation so the live figures and gating can never drift from what T1 would compute.
 */
private fun SalesUiState.recomputed(): SalesUiState {
    val lineInputs = cartLines.map { it.toInput() }
    val costBySerialId = cartLines.filterIsInstance<CartLine.Inventory>().associate { it.serialId to it.cost.orZero() }
    val totals = SaleCalculator.compute(lineInputs, saleDiscount.orZero(), taxConfig, costBySerialId, taxInclusive)

    val paid = Money.sum(listOf(payments.cash, payments.card, payments.bank).map { it.orZero() })
    val lineDiscViolations = cartLines
        .filterNot { Money.lessThanOrEqual(it.lineDiscount.orZero(), it.unitPrice.orZero()) }
        .map { it.lineId }
        .toSet()

    val errors = SaleErrors(
        emptyCart = cartLines.isEmpty(),
        noCustomer = selectedCustomer == null,
        lineDiscountExceedsPrice = lineDiscViolations,
        unpricedLines = cartLines.filter { it.unitPrice.isBlank() }.map { it.lineId }.toSet(),
        saleDiscountExceedsSubtotal = !Money.lessThanOrEqual(saleDiscount.orZero(), totals.subtotal),
        overpayment = Money.compare(paid, totals.grandTotal) > 0,
        walkInMustPayInFull = isWalkIn && cartLines.isNotEmpty() && Money.compare(paid, totals.grandTotal) != 0,
    )
    return copy(totals = totals, errors = errors)
}

/** Convert a cart line to the shared write model, sanitizing money to safe decimal strings. */
private fun CartLine.toInput(): SaleLineInput = when (this) {
    is CartLine.Inventory -> SaleLineInput.InventoryLineInput(
        productId = productId,
        serialId = serialId,
        unitPrice = unitPrice.orZero(),
        lineDiscount = lineDiscount.orZero(),
    )
    is CartLine.Custom -> SaleLineInput.CustomLineInput(
        name = name,
        unitPrice = unitPrice.orZero(),
        lineDiscount = lineDiscount.orZero(),
    )
}

/** SKU label from the SKU-defining attributes, mirroring the inventory browse label. */
private fun Map<AttributeType, AttributeRef>.skuLabel(): String =
    AttributeType.SKU_DEFINING
        .mapNotNull { this[it]?.name?.takeIf { n -> n.isNotBlank() } }
        .joinToString(" · ")

/**
 * Normalizes a money field to a safe decimal string for live math: a valid positive amount
 * is kept as-is, everything else (blank, "0", or partial/invalid text the UI feeds while
 * typing) collapses to "0". [RecordSaleUseCase] does the authoritative validation at confirm.
 */
private fun String.orZero(): String {
    val t = trim()
    return if (Money.isValidPositiveDecimal(t)) t else "0"
}
