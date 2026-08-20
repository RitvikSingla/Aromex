package com.humblesolutions.aromex.ui.sales

import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.BackendInventoryRepository
import com.humblesolutions.aromex.data.BackendCompanySettingsRepository
import com.humblesolutions.aromex.data.BackendSalesRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.model.AlreadySoldException
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.ResolvedSaleLine
import com.humblesolutions.aromex.model.SaleContentionException
import com.humblesolutions.aromex.model.SaleInput
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleLineInput
import com.humblesolutions.aromex.model.SaleTotals
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.model.WALK_IN_CUSTOMER_ID
import com.humblesolutions.aromex.model.WALK_IN_CUSTOMER_NAME
import com.humblesolutions.aromex.i18n.LocalizationRegistry
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.usecase.AddCustomerInlineUseCase
import com.humblesolutions.aromex.usecase.ObserveCompanyProfileUseCase
import com.humblesolutions.aromex.usecase.ObserveEntitiesUseCase
import com.humblesolutions.aromex.usecase.ObserveInventoryUseCase
import com.humblesolutions.aromex.usecase.ObserveSaleInvoiceUseCase
import com.humblesolutions.aromex.usecase.RecordSaleUseCase
import com.humblesolutions.aromex.usecase.RetryInvoiceUseCase
import com.humblesolutions.aromex.usecase.SaveBuyerPhoneUseCase
import com.humblesolutions.aromex.usecase.SaveBuyerTaxNumberUseCase
import com.humblesolutions.aromex.usecase.SaleCalculator
import com.humblesolutions.aromex.util.Countries
import com.humblesolutions.aromex.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
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

    /** `unitPrice − lineDiscount`, computed once here (not re-derived in the UI) so the
     *  cart row's displayed net can never drift from this same money math. */
    val netAmount: String get() = Money.subtract(unitPrice.orZero(), lineDiscount.orZero())

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
    ) : CartLine {
        /** True when the SKU has no default price yet (ticket #101) — the cashier must set one. */
        val isUnpriced: Boolean get() = unitPrice.isBlank()

        /** True when the edited price differs from the SKU's list price, or a discount is
         *  applied — the UI shows the struck-through original [listPrice] in this case.
         *  An unpriced SKU has no original to strike through, so it never reads as discounted. */
        val isDiscounted: Boolean
            get() = Money.isValidPositiveDecimal(listPrice.trim()) &&
                (Money.compare(unitPrice.orZero(), listPrice.orZero()) != 0 || !Money.isZero(lineDiscount.orZero()))
    }

    data class Custom(
        override val lineId: String,
        val name: String,
        override val unitPrice: String,
        override val lineDiscount: String = "0",
    ) : CartLine
}

/**
 * The checkout gating errors (ticket #62). Each maps to a UI hint; [canConfirm] is true
 * only when every flag is clear. [lineDiscountExceedsPrice] holds the offending [lineId]s
 * so a specific row can be highlighted.
 */
data class SaleErrors(
    val emptyCart: Boolean = false,
    val noCustomer: Boolean = false,
    val lineDiscountExceedsPrice: Set<String> = emptySet(),
    /**
     * Lines whose price was never typed. A SKU may now be stocked unpriced (ticket #101), and
     * a blank price sanitizes to "0" on the way to the ledger — so without this gate an unpriced
     * phone rings up free, with a green Confirm button and nothing on screen to suggest otherwise.
     * An explicit "0" is left alone: giving something away deliberately is a real transaction.
     */
    val unpricedLines: Set<String> = emptySet(),
    val saleDiscountExceedsSubtotal: Boolean = false,
    val overpayment: Boolean = false,
    val walkInMustPayInFull: Boolean = false,
)

/** Bucket for a unit whose product has no brand/model attribute — never dropped silently. */
private const val OTHER_GROUP = "Other"

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
     * the cashier's hands, possibly after they read it out to a customer, so it is explained rather
     * than left to be noticed.
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
    /** Set when [SalesViewModel.addNewCustomer] fails; cleared on the next attempt/success. */
    val customerAddError: String? = null,
    // ── Cart ───────────────────────────────────────────────────────────────────
    val cartLines: List<CartLine> = emptyList(),
    val saleDiscount: String = "0",
    /**
     * Tax-inclusive pricing toggle (ticket #106) — when true the typed prices already contain tax
     * and [SaleTotals] is computed by backing it out. Per sale, defaults to off, and reset by
     * [SalesViewModel.startNewSale] so it can never silently carry into the next customer.
     */
    val taxInclusive: Boolean = false,
    val payments: PaymentInput = PaymentInput(),
    val note: String = "",
    /**
     * The business date of this sale. Today unless the cashier picks otherwise — the case that
     * matters is entering old books, where the sale has to land on the day it happened.
     */
    val saleDate: Long = System.currentTimeMillis(),
    // ── Walk-in buyer capture (ticket #77; only used when [isWalkIn]) ───────────
    val buyerName: String = "",
    val buyerPhone: String = "",
    /** Dial-code country for the walk-in phone, matching the entity form's picker. Digits in [buyerPhone]. */
    val buyerCountryIso: String = Countries.DEFAULT_ISO,
    // ── Customer tax number at checkout (ticket #106 follow-up) ─────────────────
    /**
     * The buyer's tax number for this sale's invoice — prefilled from the selected customer on
     * select, editable per sale, snapshotted onto the sale. Reset on [SalesViewModel.startNewSale].
     */
    val buyerTaxNumber: String = "",
    /** True while a "Save to contact" write is in flight (disables the button). */
    val savingTaxNumber: Boolean = false,
    /** Set true briefly after a successful save; cleared on the next edit. */
    val taxNumberSaved: Boolean = false,
    /** Set true if the last save failed; cleared on retry/edit. */
    val taxNumberSaveError: Boolean = false,
    // ── Customer contact phone at checkout (mirrors the tax-number field) ───────
    /**
     * The buyer's phone for this sale's invoice — the *national digits*, prefilled from the selected
     * customer's primary number on select, editable per sale, snapshotted onto the sale (paired with
     * [buyerContactCountryIso] for the dial code). Uses the same dial-code-chip control as the walk-in
     * Bill-To phone. Reset on [startNewSale].
     */
    val buyerContactPhone: String = "",
    /** Dial-code country for [buyerContactPhone], matching the walk-in phone control. */
    val buyerContactCountryIso: String = Countries.DEFAULT_ISO,
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
    /** The Retry affordance shows only for a FAILED invoice, and is disabled mid-retry. */
    val canRetryInvoice: Boolean
        get() = invoice.status == SaleInvoiceStatus.FAILED && !isRetryingInvoice
    /** True when the buyer is the reserved anonymous party (forces pay-in-full). */
    val isWalkIn: Boolean
        get() = selectedCustomer?.let { it.isWalkIn || it.id == WALK_IN_CUSTOMER_ID } == true

    /**
     * Whether "Save to contact" is offered for the current tax-number field (ticket #106 follow-up):
     * a named customer is selected, the user can manage profiles, the field differs from the
     * customer's stored value, and no save is in flight. Never for a walk-in (no contact to save to).
     */
    val canSaveTaxToContact: Boolean
        get() {
            val customer = selectedCustomer ?: return false
            if (isWalkIn || !canManageProfiles || savingTaxNumber) return false
            return buyerTaxNumber.trim() != (customer.taxNumber ?: "")
        }

    /**
     * The contact phone as a full number (dial code + national digits), or "" when blank. This is
     * what rides onto the sale and what "Save to contact" persists — the same shape the walk-in
     * Bill-To phone records.
     */
    val buyerContactPhoneFull: String
        get() = buyerContactPhone.takeIf { it.isNotEmpty() }
            ?.let { "${Countries.byIso(buyerContactCountryIso).dialCode} $it" } ?: ""

    /**
     * Whether "Save to contact" is offered for the phone field — the mirror of [canSaveTaxToContact]:
     * a named customer is selected, the user can manage profiles, the field differs from the
     * customer's stored primary number, and no save is in flight. Never for a walk-in. Compared on
     * digits so a re-prefilled number (whatever its spacing) doesn't offer a no-op save.
     */
    val canSavePhoneToContact: Boolean
        get() {
            val customer = selectedCustomer ?: return false
            if (isWalkIn || !canManageProfiles || savingPhone) return false
            val stored = customer.phones.firstOrNull() ?: ""
            return buyerContactPhoneFull.filter(Char::isDigit) != stored.filter(Char::isDigit)
        }

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
        get() = pickerUnits.filter { it.serialId !in cartSerialIds }

    /**
     * Like [visibleUnits] but **keeps** units already in the cart, so the browse picker can
     * render them as "Added" instead of silently dropping the row the cashier just tapped
     * (they still can't be re-added — [cartSerialIds] drives the disabled state). Derived
     * here rather than in the UI so the filter rules live in one place.
     */
    val pickerUnits: List<Serial>
        get() {
            val q = pickerSearchQuery.trim()
            return allInStockUnits.asSequence()
                .filter { it.status == SerialStatus.IN_STOCK && it.isActive }
                .filter { pickerLocationFilter == null || it.location.attributeId == pickerLocationFilter }
                .filter { serial ->
                    q.isEmpty() ||
                        serial.imei.contains(q, ignoreCase = true) ||
                        labelFor(serial).contains(q, ignoreCase = true)
                }
                .toList()
        }

    /** The SKU attribute value (e.g. brand/model name) of a unit's product, or null. */
    fun attributeOf(serial: Serial, type: AttributeType): String? =
        products.firstOrNull { it.productId == serial.productId }?.attributes?.get(type)?.name

    /** The unit's SKU list price — what the picker shows before it's added to the cart. */
    fun listPriceOf(serial: Serial): String =
        products.firstOrNull { it.productId == serial.productId }?.defaultSellingPrice ?: "0"

    /** The cart line holding this unit, if any — lets the picker's Add act as a toggle. */
    fun lineIdForSerial(serialId: String): String? =
        cartLines.filterIsInstance<CartLine.Inventory>().firstOrNull { it.serialId == serialId }?.lineId

    /** In-stock units grouped by brand → model, honouring the current location + search filters. */
    val pickerByBrandModel: Map<String, Map<String, List<Serial>>>
        get() = pickerUnits
            .groupBy { attributeOf(it, AttributeType.BRAND) ?: OTHER_GROUP }
            .toSortedMap(compareBy { it.lowercase() })
            .mapValues { (_, units) ->
                units.groupBy { attributeOf(it, AttributeType.MODEL) ?: OTHER_GROUP }
                    .toSortedMap(compareBy { it.lowercase() })
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

    /** Human-readable SKU label for a serial, from its cached product (blank when unknown). */
    fun labelFor(serial: Serial): String {
        val product = products.firstOrNull { it.productId == serial.productId } ?: return serial.imei
        return product.attributes.skuLabel().ifBlank { serial.imei }
    }
}

/**
 * Desktop checkout ViewModel (ticket #62): builds a cart from cached in-stock units, edits
 * prices/discounts, picks a customer or the Walk-in, takes a split payment, shows live
 * totals via the shared [SaleCalculator] (never re-implemented here), and confirms through
 * [RecordSaleUseCase] — mapping the "already sold" race to a graceful state. No screens.
 *
 * Mirrors `AddStockViewModel`: manual DI in [bind], the synthetic-default injection (here
 * the Walk-in Customer), and [startNewSale] preserving the cached inventory/entities/session
 * (the #58 reset-preserves-cache lesson). Plain class + [CoroutineScope]; call [dispose] on
 * teardown. [bindForTest] injects fakes so the gating/totals logic is unit-tested on the JVM.
 */
class SalesViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // Lazy so the test path ([bindForTest]) never constructs desktop preference IO.
    private val authRepo by lazy { FirebaseRestAuthRepository(DesktopPreferencesRepository()) }

    private var invRepo: BackendInventoryRepository? = null
    private var entityRepo: BackendEntityRepository? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null
    private var recordSaleUseCase: RecordSaleUseCase? = null
    private var saveBuyerTaxNumberUseCase: SaveBuyerTaxNumberUseCase? = null
    private var saveBuyerPhoneUseCase: SaveBuyerPhoneUseCase? = null
    private var addCustomerInlineUseCase: AddCustomerInlineUseCase? = null
    private var observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = null
    private var retryInvoiceUseCase: RetryInvoiceUseCase? = null

    /** The live invoice observation for the just-completed sale; cancelled on new-sale/dispose. */
    private var invoiceJob: Job? = null

    private var customSeq = 0L

    private val _uiState = MutableStateFlow(SalesUiState())
    val uiState: StateFlow<SalesUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        val broker = FirestoreTokenBroker(authRepo, config)
        val sales = BackendSalesRepository(broker, config, session.uid)
        val inv = BackendInventoryRepository(broker, config, session.uid)
        val entities = BackendEntityRepository(broker, config, session.uid)
        invRepo = inv
        entityRepo = entities
        recordSaleUseCase = RecordSaleUseCase(sales)
        saveBuyerTaxNumberUseCase = SaveBuyerTaxNumberUseCase(entities)
        saveBuyerPhoneUseCase = SaveBuyerPhoneUseCase(entities)
        addCustomerInlineUseCase = AddCustomerInlineUseCase(entities)
        observeSaleInvoiceUseCase = ObserveSaleInvoiceUseCase(sales)
        retryInvoiceUseCase = RetryInvoiceUseCase(sales)

        val settings = BackendCompanySettingsRepository(broker, config)
        val profileUseCase = ObserveCompanyProfileUseCase(settings)

        val inventoryUseCase = ObserveInventoryUseCase(inv)
        val entitiesUseCase = ObserveEntitiesUseCase(entities)
        // Pass flow *factories*, not constructed flows: the observe use cases throw
        // PermissionDeniedException synchronously (before returning the Flow) when the user
        // lacks inventory/profiles access, so construction must happen inside the guarded
        // launches — never on the bind() thread (mirror of AddStockViewModel).
        start(
            session = session,
            serials = { inventoryUseCase.observeInStockSerials(session) },
            products = { inventoryUseCase.observeProducts(session) },
            entities = { entitiesUseCase.execute(session) },
            profile = { profileUseCase.execute() },
        )
    }

    /**
     * Test seam — inject a [RecordSaleUseCase] over a fake repo and the three cached streams
     * as plain flows, skipping the Firestore wiring so the cart/gating/totals logic runs on
     * the JVM. Production uses [bind]; this is `internal` for `desktopTest` only.
     */
    internal fun bindForTest(
        session: UserSession,
        recordSaleUseCase: RecordSaleUseCase,
        serials: Flow<List<Serial>>,
        products: Flow<List<Product>>,
        entities: Flow<List<Entity>>,
        observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = null,
        retryInvoiceUseCase: RetryInvoiceUseCase? = null,
        profile: Flow<CompanyProfile>? = null,
    ) {
        this.session = session
        this.recordSaleUseCase = recordSaleUseCase
        this.observeSaleInvoiceUseCase = observeSaleInvoiceUseCase
        this.retryInvoiceUseCase = retryInvoiceUseCase
        start(session, { serials }, { products }, { entities }, profile?.let { p -> { p } })
    }

    private fun start(
        session: UserSession,
        serials: () -> Flow<List<Serial>>,
        products: () -> Flow<List<Product>>,
        entities: () -> Flow<List<Entity>>,
        profile: (() -> Flow<CompanyProfile>)? = null,
    ) {
        _uiState.update {
            it.copy(
                isLoading = true,
                currency = session.currency,
                canManageProfiles = session.permissions.profiles == PermissionLevel.MANAGE,
                taxConfig = session.tax,
            )
        }
        // Live reads: stock changes as units sell, the customer list changes as parties are
        // added — the cart itself is filtered/derived client-side (the ViewModel is the cache).
        // runCatching guards the synchronous permission throw; .catch guards stream errors;
        // either way isLoading clears so the screen degrades instead of hanging/crashing.
        scope.launch {
            runCatching {
                serials()
                    .catch { _uiState.update { it.copy(isLoading = false) } }
                    .collect { list -> _uiState.update { it.copy(allInStockUnits = list, isLoading = false).recomputed() } }
            }.onFailure { _uiState.update { it.copy(isLoading = false) } }
        }
        scope.launch {
            runCatching {
                products()
                    .catch { }
                    .collect { list -> _uiState.update { it.copy(products = list).recomputed() } }
            }
        }
        scope.launch {
            runCatching {
                entities()
                    .catch { }
                    .collect { list -> _uiState.update { it.copy(allCustomers = list).recomputed() } }
            }
        }
        // The tax rate an admin can now change from Settings. [UserSession.tax] is captured at
        // sign-in and never refreshed, so without this a rate changed at noon would leave every
        // open till charging the old one for the rest of the day, with nothing on screen saying so.
        // A stream failure keeps the rate we already have — a stale rate still beats no checkout.
        profile?.let { source ->
            scope.launch {
                runCatching {
                    source()
                        .catch { }
                        .collect { p -> applyTaxConfig(p.tax) }
                }
            }
        }
    }

    /**
     * Adopt a tax configuration and re-derive the totals under it.
     *
     * When the cart already has lines, the cashier is looking at a total that is about to change —
     * possibly one they just read out to a customer — so [SalesUiState.taxChangedMidSale] flags it
     * for the banner instead of letting the number move unexplained.
     */
    private fun applyTaxConfig(tax: TaxConfig) = _uiState.update { s ->
        if (tax == s.taxConfig) return@update s
        s.copy(
            taxConfig = tax,
            taxChangedMidSale = s.cartLines.isNotEmpty(),
        ).recomputed()
    }

    /** Dismiss the mid-sale tax-change banner once the cashier has seen it. */
    fun dismissTaxChangeNotice() = _uiState.update { it.copy(taxChangedMidSale = false) }

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
        // Blank when the SKU isn't priced yet (ticket #101) — the cart shows an empty price
        // field to be filled in, not a "0" that reads like a real price.
        val listPrice = product?.defaultSellingPrice.orEmpty()
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
        // Prefill the tax-number field from the picked customer (ticket #106 follow-up); clear any
        // stale save status from the previous selection.
        // Split the stored primary number into its dial-code country + national digits so it
        // prefills the same chip control the walk-in phone uses.
        val storedPhone = customer.phones.firstOrNull() ?: ""
        val contactCountry = Countries.byDialPrefix(storedPhone)
        val contactDigits = storedPhone.removePrefix(contactCountry.dialCode).filter(Char::isDigit).take(10)
        it.copy(
            selectedCustomer = customer,
            buyerTaxNumber = customer.taxNumber ?: "",
            taxNumberSaved = false,
            taxNumberSaveError = false,
            buyerContactPhone = contactDigits,
            buyerContactCountryIso = contactCountry.iso,
            phoneSaved = false,
            phoneSaveError = false,
        ).recomputed()
    }

    /** Update the per-sale tax number (ticket #106 follow-up); clears any prior save status. */
    fun setBuyerTaxNumber(value: String) = _uiState.update {
        it.copy(buyerTaxNumber = value, taxNumberSaved = false, taxNumberSaveError = false)
    }

    /** Update the per-sale contact phone (digits-only, capped at 10 like the walk-in phone); clears save status. */
    fun setBuyerContactPhone(value: String) = _uiState.update {
        it.copy(buyerContactPhone = value.filter(Char::isDigit).take(10), phoneSaved = false, phoneSaveError = false)
    }

    /** Update the dial-code country for the contact phone; clears any prior save status. */
    fun setBuyerContactCountryIso(iso: String) = _uiState.update {
        it.copy(buyerContactCountryIso = iso, phoneSaved = false, phoneSaveError = false)
    }

    /**
     * "Save to contact" (ticket #106 follow-up): persist the current tax-number field onto the
     * selected named customer so it becomes their default. Gated on `profiles` MANAGE in the use
     * case; the button is only offered when [SalesUiState.canSaveTaxToContact]. On success the cached
     * customer is updated so the button settles (nothing left to save) and future selects prefill it.
     */
    fun saveBuyerTaxNumberToContact() {
        val useCase = saveBuyerTaxNumberUseCase ?: return
        val current = session ?: return
        val state = _uiState.value
        val customer = state.selectedCustomer ?: return
        if (!state.canSaveTaxToContact) return
        val value = state.buyerTaxNumber.trim().ifEmpty { null }
        _uiState.update { it.copy(savingTaxNumber = true, taxNumberSaved = false, taxNumberSaveError = false) }
        scope.launch {
            runCatching { useCase.execute(current, customer.id, value) }
                .onSuccess {
                    _uiState.update { s ->
                        val updated = customer.copy(taxNumber = value)
                        s.copy(
                            savingTaxNumber = false,
                            taxNumberSaved = true,
                            // Reflect the write in the cached list + selection so canSaveTaxToContact
                            // settles to false and the next select prefills the saved value.
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
     * current phone field as the selected customer's *primary* number, preserving any secondary
     * numbers they already have. Gated on `profiles` MANAGE in the use case; offered only when
     * [SalesUiState.canSavePhoneToContact]. On success the cached customer is updated so the button
     * settles and future selects prefill the saved value.
     */
    fun saveBuyerPhoneToContact() {
        val useCase = saveBuyerPhoneUseCase ?: return
        val current = session ?: return
        val state = _uiState.value
        val customer = state.selectedCustomer ?: return
        if (!state.canSavePhoneToContact) return
        // Edit only the primary number (the full dial-code + digits form); keep any others.
        val newPhones = buildList {
            state.buyerContactPhoneFull.takeIf { it.isNotEmpty() }?.let { add(it) }
            addAll(customer.phones.drop(1))
        }
        _uiState.update { it.copy(savingPhone = true, phoneSaved = false, phoneSaveError = false) }
        scope.launch {
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

    /** Add-new-inline is offered only when the user can manage profiles (it creates a party). */
    fun canAddCustomerInline(): Boolean = session?.permissions?.profiles == PermissionLevel.MANAGE

    /**
     * Add-new-inline (ticket #63): create a name-only CUSTOMER party and select it
     * immediately (mirrors [AddCustomerInlineUseCase] / the purchase dialog's
     * `addNewSupplier` — no need to wait for the live entities stream to catch up).
     */
    fun addNewCustomer(name: String) {
        val useCase = addCustomerInlineUseCase ?: return
        val current = session ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        _uiState.update { it.copy(customerAddError = null) }
        scope.launch {
            runCatching { useCase.execute(current, trimmed) }
                .onSuccess { id ->
                    val entity = Entity(id = id, name = trimmed, roles = setOf(EntityRole.CUSTOMER))
                    _uiState.update {
                        it.copy(
                            selectedCustomer = entity,
                            buyerTaxNumber = "", // a brand-new party has no tax number yet
                            taxNumberSaved = false,
                            taxNumberSaveError = false,
                            buyerContactPhone = "", // …nor a phone yet
                            buyerContactCountryIso = Countries.DEFAULT_ISO,
                            phoneSaved = false,
                            phoneSaveError = false,
                        ).recomputed()
                    }
                }
                .onFailure { e ->
                    val fallback = LocalizationRegistry.get("en", Strings.sales_error_add_customer_generic)
                    _uiState.update { it.copy(customerAddError = e.message ?: fallback) }
                }
        }
    }

    /** Dismiss the inline add-customer error banner. */
    fun dismissCustomerAddError() = _uiState.update { it.copy(customerAddError = null) }

    /** Select the reserved Walk-in Customer (forces pay-in-full gating). */
    fun selectWalkIn() = _uiState.update { state ->
        val walkIn = state.allCustomers.firstOrNull { it.id == WALK_IN_CUSTOMER_ID }
            ?: Entity(
                id = WALK_IN_CUSTOMER_ID,
                name = WALK_IN_CUSTOMER_NAME,
                roles = setOf(EntityRole.CUSTOMER),
                isWalkIn = true,
            )
        // A walk-in has no contact, so start the tax field empty (the cashier may still type one for
        // the bill); clear any stale save status. The phone for a walk-in is captured by the separate
        // Bill-To phone field below, so leave [buyerContactPhone] empty here.
        state.copy(
            selectedCustomer = walkIn,
            buyerTaxNumber = "",
            taxNumberSaved = false,
            taxNumberSaveError = false,
            buyerContactPhone = "",
            buyerContactCountryIso = Countries.DEFAULT_ISO,
            phoneSaved = false,
            phoneSaveError = false,
        ).recomputed()
    }

    // ── Payment + note ───────────────────────────────────────────────────────────

    fun setCash(amount: String) = _uiState.update { it.copy(payments = it.payments.copy(cash = amount)).recomputed() }
    fun setCard(amount: String) = _uiState.update { it.copy(payments = it.payments.copy(card = amount)).recomputed() }
    fun setBank(amount: String) = _uiState.update { it.copy(payments = it.payments.copy(bank = amount)).recomputed() }

    fun setNote(note: String) = _uiState.update { it.copy(note = note) }

    /** Pick the sale's business date. The picker itself refuses future dates. */
    fun setSaleDate(millis: Long) = _uiState.update { it.copy(saleDate = millis) }

    // ── Walk-in buyer capture (ticket #77) ────────────────────────────────────────
    // Free-text; no gating (never blocks Confirm). The use case keeps these only for a
    // walk-in and trims blank → null → the CF falls back to "Walk-in Customer".
    fun setBuyerName(name: String) = _uiState.update { it.copy(buyerName = name) }
    /** Phone is digits-only, capped at 10 — a keyboard hint alone can't stop paste/hardware input. */
    fun setBuyerPhone(phone: String) =
        _uiState.update { it.copy(buyerPhone = phone.filter(Char::isDigit).take(10)) }
    fun setBuyerCountryIso(iso: String) = _uiState.update { it.copy(buyerCountryIso = iso) }

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
            // Walk-in: the Bill-To field (digits + dial-code chip) — prefix the code so the invoice
            // carries a full number. Named customer: the contact-phone field, prefilled from their
            // Entity and stored as a full number already. The two fields never both apply.
            buyerPhone = if (state.isWalkIn) {
                state.buyerPhone.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { "${Countries.byIso(state.buyerCountryIso).dialCode} $it" }
                    ?: ""
            } else {
                state.buyerContactPhoneFull
            },
            saleDate = state.saleDate,
            taxInclusive = state.taxInclusive,
            // The per-sale tax number as edited at checkout (prefilled from the customer). Snapshotted
            // onto the sale for the invoice; carried for a walk-in too. Blank → null in the use case.
            buyerTaxNumber = state.buyerTaxNumber,
        )

        _uiState.update { it.copy(confirmState = ConfirmState.Submitting) }
        scope.launch {
            // The live rate this screen is showing, not the one captured at sign-in — otherwise
            // the recorded sale could be taxed differently from the total the customer was quoted.
            runCatching { useCase.execute(current, saleInput, resolved, state.taxConfig, System.currentTimeMillis()) }
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
                        is SaleContentionException -> _uiState.update {
                            // The commit lost a race to concurrent activity but nothing was sold
                            // (a re-check found the units still in stock). Calm retry prompt
                            // instead of leaking the raw "too many retries" backend exception.
                            it.copy(confirmState = ConfirmState.Error("Another sale is being completed for one of these items. Please try again."))
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
        invoiceJob = scope.launch {
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
        scope.launch {
            runCatching { useCase.execute(saleId) }
                .onFailure {
                    // Roll the optimistic PENDING back to FAILED and explain — *unless* the live
                    // stream has already settled. A client-side failure does not mean the server
                    // did nothing: the call can time out while the CF runs on to issue the PDF,
                    // and the stream then delivers ISSUED before we get here. Overwriting that
                    // with FAILED would hide a finished invoice behind the retry UI, and nothing
                    // would correct it — no further doc write is coming.
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
                customerAddError = null,
                cartLines = emptyList(),
                saleDiscount = "0",
                // Reset to tax-exclusive so a toggle flipped for one customer can't silently
                // under-charge tax on the next sale (ticket #106).
                taxInclusive = false,
                payments = PaymentInput(),
                note = "",
                buyerName = "",
                buyerPhone = "",
                buyerCountryIso = Countries.DEFAULT_ISO,
                saleDate = System.currentTimeMillis(),
                buyerTaxNumber = "",
                savingTaxNumber = false,
                taxNumberSaved = false,
                taxNumberSaveError = false,
                buyerContactPhone = "",
                buyerContactCountryIso = Countries.DEFAULT_ISO,
                savingPhone = false,
                phoneSaved = false,
                phoneSaveError = false,
                confirmState = ConfirmState.Idle,
                invoice = SaleInvoice(),
                isRetryingInvoice = false,
                invoiceRetryError = false,
                taxChangedMidSale = false,
            ).recomputed()
        }
    }

    /**
     * Release the resources this VM owns: cancel the scope + close the Admin-SDK clients.
     * Note: `BackendSalesRepository` exposes no `close()` (unlike the inventory/entity repos)
     * — a small T1 gap flagged in the handoff; its client is only created on an actual sale.
     */
    fun dispose() {
        scope.cancel()
        invRepo?.close()
        entityRepo?.close()
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
