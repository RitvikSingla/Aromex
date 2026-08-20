package com.humblesolutions.aromex.ui.sales.history

import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.BackendSalesRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.data.FirestoreUserRepository
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleStatus
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.model.SalesCursor
import com.humblesolutions.aromex.model.SalesQuery
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.usecase.GetSaleUseCase
import com.humblesolutions.aromex.usecase.ObserveEntitiesUseCase
import com.humblesolutions.aromex.usecase.ObserveSaleInvoiceUseCase
import com.humblesolutions.aromex.usecase.QuerySalesUseCase
import com.humblesolutions.aromex.usecase.RetryInvoiceUseCase
import com.humblesolutions.aromex.usecase.VoidSaleUseCase
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
 * The structured filter behind the Sales History list (ticket #83). Every field maps to a
 * [SalesQuery] filter; [customerName] is resolved to entity ids against the cached entities
 * before querying (the sale doc stores an id, not a name). All fields combine (AND). Date
 * bounds are inclusive epoch-millis (already snapped to the shop day by the UI).
 */
data class SalesHistoryFilter(
    val customerName: String = "",
    val imei: String = "",
    val invoiceNumber: String = "",
    val dateFromMillis: Long? = null,
    val dateToMillis: Long? = null,
    val onlyWithBalance: Boolean = false,
) {
    /** How many filters are set — drives the "Filters (N)" badge on the toolbar button. */
    val activeCount: Int
        get() = listOf(
            customerName.isNotBlank(),
            imei.isNotBlank(),
            invoiceNumber.isNotBlank(),
            dateFromMillis != null,
            dateToMillis != null,
            onlyWithBalance,
        ).count { it }

    val isEmpty: Boolean get() = activeCount == 0
}

/**
 * Live invoice state for the open sale's detail view (ticket #83) — the inputs the reused #77
 * `InvoiceRow` needs. Mirrors the counter's fields so the same component renders View/Print/
 * Copy/Retry identically here.
 */
data class DetailInvoiceState(
    val invoice: SaleInvoice = SaleInvoice(),
    val isRetrying: Boolean = false,
    val retryError: Boolean = false,
) {
    val canRetry: Boolean get() = invoice.status == SaleInvoiceStatus.FAILED && !isRetrying
}

data class SalesHistoryUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val permissionDenied: Boolean = false,
    val error: String? = null,
    val currency: String = "",
    val timezone: String = "UTC",
    /** Every sale loaded so far (all pages). The server-side filter/paging feeds this. */
    val sales: List<SaleSummary> = emptyList(),
    /** [sales] after the **local** quick-search is applied — what the list renders. */
    val visibleSales: List<SaleSummary> = emptyList(),
    val hasMore: Boolean = false,
    /**
     * The quick-search box text — a **local** substring filter over [sales] (customer name,
     * invoice #, any item label, any IMEI). No Firestore round-trip; it only refines rows already
     * loaded. Full-history exact lookups (IMEI/invoice/customer/date/balance) live in [filter].
     */
    val searchText: String = "",
    val filter: SalesHistoryFilter = SalesHistoryFilter(),
    /**
     * How many parties the typed customer name matched, when that is more than Firestore's `in`
     * cap allows us to query for. Null when the name matched few enough to cover completely.
     *
     * Surfaced rather than swallowed: the query used to take the first 30 matches and say nothing,
     * so searching a common surname returned a confident-looking list that was quietly missing
     * sales. A result that is incomplete has to look incomplete.
     */
    val customerMatchesCapped: Int? = null,
    // ── Detail view ──────────────────────────────────────────────────────────────
    val detailLoading: Boolean = false,
    val detail: SaleDetail? = null,
    val detailInvoice: DetailInvoiceState = DetailInvoiceState(),
    /**
     * The open sale's seller as a **display name** (resolved from `createdBy` uid), not the raw
     * uid. Null while resolving or when it can't be resolved — the UI then shows a placeholder.
     */
    val sellerName: String? = null,
    /** True when the signed-in user is an admin — gates the Void action (ticket #85). */
    val isAdmin: Boolean = false,
    /** True while a void is in flight (optimistic) — locks the action + shows progress. */
    val isVoiding: Boolean = false,
    /** The last void failure reason (e.g. a re-used IMEI), shown in the confirm dialog. */
    val voidError: String? = null,
) {
    /** True once any filter or search is active — distinguishes "no sales yet" from "no match". */
    val hasActiveFilter: Boolean get() = !filter.isEmpty || searchText.isNotBlank()

    /** The open sale can be voided iff the user is an admin and it isn't already VOIDED (ticket #85). */
    val canVoidOpenSale: Boolean get() = isAdmin && detail?.isVoided == false
}

/**
 * Local quick-search predicate (ticket #83): does [sale] match [query] by customer name, invoice
 * number, any item label, or any IMEI? Substring, case-insensitive. Runs over already-loaded rows
 * only — no Firestore. [customerName] is the row's resolved display name (passed in, since name
 * resolution needs the entities cache).
 */
internal fun SaleSummary.matchesLocalSearch(query: String, customerName: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    if (customerName.contains(q, ignoreCase = true)) return true
    if (invoiceNumber?.contains(q, ignoreCase = true) == true) return true
    if (itemLabels.any { it.contains(q, ignoreCase = true) }) return true
    if (imeis.any { it.contains(q, ignoreCase = true) }) return true
    return false
}

/**
 * Desktop Sales History ViewModel (ticket #83). Unlike every cached-list screen, sales are
 * **unbounded**, so this pages the collection — [QuerySalesUseCase] runs the filters as
 * Firestore queries and [loadMore] appends the next page via the cursor; nothing is ever
 * re-fetched to filter client-side. Customer names are resolved to entity ids against a small
 * cached [entities] snapshot. Manual DI in [bind]; plain class + [CoroutineScope]; call
 * [dispose] on teardown. [bindForTest] injects fakes so the paging/filter logic is unit-tested.
 */
class SalesHistoryViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val authRepo by lazy { FirebaseRestAuthRepository(DesktopPreferencesRepository()) }

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null
    private var salesRepo: BackendSalesRepository? = null
    private var entityRepo: BackendEntityRepository? = null
    private var userRepo: FirestoreUserRepository? = null
    /** uid → display name, so re-opening a sale by the same seller doesn't re-read `users/{uid}`. */
    private val sellerNameCache = mutableMapOf<String, String>()
    private var querySalesUseCase: QuerySalesUseCase? = null
    private var getSaleUseCase: GetSaleUseCase? = null
    private var observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = null
    private var retryInvoiceUseCase: RetryInvoiceUseCase? = null
    private var voidSaleUseCase: VoidSaleUseCase? = null

    /** Cached parties, for resolving a typed customer name → entity ids (bounded collection). */
    private var entities: List<Entity> = emptyList()

    private var nextCursor: SalesCursor? = null
    private var invoiceJob: Job? = null

    private val _uiState = MutableStateFlow(SalesHistoryUiState())
    val uiState: StateFlow<SalesHistoryUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        val broker = FirestoreTokenBroker(authRepo, config)
        val sales = BackendSalesRepository(broker, config, session.uid)
        val entityRepository = BackendEntityRepository(broker, config, session.uid)
        salesRepo = sales
        entityRepo = entityRepository
        userRepo = FirestoreUserRepository(broker)
        querySalesUseCase = QuerySalesUseCase(sales)
        getSaleUseCase = GetSaleUseCase(sales)
        observeSaleInvoiceUseCase = ObserveSaleInvoiceUseCase(sales)
        retryInvoiceUseCase = RetryInvoiceUseCase(sales)
        voidSaleUseCase = VoidSaleUseCase(sales)

        _uiState.update {
            it.copy(currency = session.currency, timezone = session.timezone, isAdmin = session.role == UserRole.ADMIN)
        }

        // Cache entities for name → id resolution (bounded; a live stream keeps it fresh). The
        // observe throws PermissionDenied synchronously without profiles access — swallow it, name
        // search just won't resolve then; the sales list (its own gate) still works.
        val entitiesUseCase = ObserveEntitiesUseCase(entityRepository)
        scope.launch {
            runCatching {
                entitiesUseCase.execute(session).catch { }.collect { entities = it }
            }
        }

        loadFirstPage()
    }

    /** Test seam — inject fakes over the use cases + entities, skipping Firestore wiring. */
    internal fun bindForTest(
        session: UserSession,
        querySalesUseCase: QuerySalesUseCase,
        getSaleUseCase: GetSaleUseCase,
        entities: List<Entity> = emptyList(),
        retryInvoiceUseCase: RetryInvoiceUseCase? = null,
        observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = null,
        voidSaleUseCase: VoidSaleUseCase? = null,
        sellerNames: Map<String, String> = emptyMap(),
    ) {
        this.session = session
        this.querySalesUseCase = querySalesUseCase
        this.getSaleUseCase = getSaleUseCase
        this.retryInvoiceUseCase = retryInvoiceUseCase
        this.observeSaleInvoiceUseCase = observeSaleInvoiceUseCase
        this.voidSaleUseCase = voidSaleUseCase
        this.entities = entities
        sellerNameCache.putAll(sellerNames)
        _uiState.update {
            it.copy(currency = session.currency, timezone = session.timezone, isAdmin = session.role == UserRole.ADMIN)
        }
        loadFirstPage()
    }

    // ── Search + filters ───────────────────────────────────────────────────────────

    /**
     * Quick-search box — a **local** refine over the rows already loaded ([SalesHistoryUiState.sales]):
     * customer name / invoice # / any item label / any IMEI. No Firestore query, instant. It only
     * narrows what's been paged in; full-history exact lookups use the Filters dialog ([applyFilter]).
     */
    fun onSearchChanged(text: String) {
        _uiState.update { it.copy(searchText = text).withLocalSearch() }
    }

    /**
     * Apply the structured filter from the dialog — the **server-side** path (customer / IMEI /
     * invoice / date / balance run as Firestore queries, so they find sales anywhere in history,
     * not just loaded pages). Independent of the local [searchText], which keeps refining results.
     */
    fun applyFilter(filter: SalesHistoryFilter) {
        _uiState.update { it.copy(filter = filter) }
        loadFirstPage()
    }

    fun clearFilters() {
        _uiState.update { it.copy(filter = SalesHistoryFilter(), searchText = "") }
        loadFirstPage()
    }

    /**
     * Reload the list from the top (ticket #85 UX) — keeps the current filters/search but re-fetches
     * the first page from Firestore, so newly-voided/synced sales and fresh rows show without
     * reopening the screen. Sales are unbounded, so this re-pages from scratch rather than diffing.
     */
    fun refresh() {
        loadFirstPage()
    }

    /** Recompute [SalesHistoryUiState.visibleSales] from [sales] + the local [searchText]. */
    private fun SalesHistoryUiState.withLocalSearch(): SalesHistoryUiState =
        copy(visibleSales = sales.filter { it.matchesLocalSearch(searchText, customerNameOf(it)) })

    // ── Paging ───────────────────────────────────────────────────────────────────

    private fun loadFirstPage() {
        val useCase = querySalesUseCase ?: return
        val current = session ?: return
        nextCursor = null
        _uiState.update { it.copy(isLoading = true, error = null, permissionDenied = false) }
        scope.launch {
            runCatching { useCase.execute(current, buildQuery(cursor = null)) }
                .onSuccess { page ->
                    nextCursor = page.nextCursor
                    _uiState.update {
                        it.copy(isLoading = false, sales = page.sales, hasMore = page.nextCursor != null).withLocalSearch()
                    }
                }
                .onFailure { e -> onLoadFailure(e, firstPage = true) }
        }
    }

    /**
     * Append the next page (ticket #83 UI rule: never blank the list — append). No-op while a
     * page is already loading or the end has been reached.
     */
    fun loadMore() {
        val useCase = querySalesUseCase ?: return
        val current = session ?: return
        val cursor = nextCursor ?: return
        if (_uiState.value.isLoadingMore || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoadingMore = true) }
        scope.launch {
            runCatching { useCase.execute(current, buildQuery(cursor)) }
                .onSuccess { page ->
                    nextCursor = page.nextCursor
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            sales = it.sales + page.sales,
                            hasMore = page.nextCursor != null,
                        ).withLocalSearch()
                    }
                }
                .onFailure { e -> onLoadFailure(e, firstPage = false) }
        }
    }

    private fun onLoadFailure(e: Throwable, firstPage: Boolean) {
        if (e is PermissionDeniedException) {
            _uiState.update {
                it.copy(isLoading = false, isLoadingMore = false, permissionDenied = true, sales = emptyList())
            }
            return
        }
        _uiState.update {
            it.copy(isLoading = false, isLoadingMore = false, error = e.message ?: "Couldn't load sales")
        }
    }

    /**
     * Translate the current filter into a [SalesQuery]. A typed customer name is resolved to
     * entity ids against the cached parties (capped at Firestore's `in` limit); if it matches no
     * one, a sentinel id makes the query return nothing rather than everything.
     */
    private fun buildQuery(cursor: SalesCursor?): SalesQuery {
        val f = _uiState.value.filter
        val typedName = f.customerName.trim().takeIf { it.isNotEmpty() }
        val allMatches = typedName?.let { name ->
            entities.filter { it.name.contains(name, ignoreCase = true) }.map { it.id }
        }
        // Report the shortfall rather than hiding it. The cap is Firestore's, not ours, and the
        // honest answer is "narrow the name", not a silently partial list.
        _uiState.update {
            it.copy(customerMatchesCapped = allMatches?.size?.takeIf { n -> n > CUSTOMER_IN_LIMIT })
        }
        val customerIds = allMatches?.take(CUSTOMER_IN_LIMIT)?.ifEmpty { listOf(NO_MATCH_SENTINEL) }
        return SalesQuery(
            customerEntityIds = customerIds,
            invoiceNumber = f.invoiceNumber.trim().ifEmpty { null },
            imei = f.imei.trim().ifEmpty { null },
            dateFromMillis = f.dateFromMillis,
            dateToMillis = f.dateToMillis,
            onlyWithBalance = f.onlyWithBalance,
            cursor = cursor,
        )
    }

    // ── Detail view ────────────────────────────────────────────────────────────────

    /** The display name for a summary row — a named customer's entity name, else the walk-in label. */
    fun customerNameOf(summary: SaleSummary): String = resolveCustomerName(
        summary.customerEntityId, summary.isWalkIn, summary.buyerName,
    )

    fun customerNameOf(detail: SaleDetail): String = resolveCustomerName(
        detail.customerEntityId, detail.isWalkIn, detail.buyerName,
    )

    private fun resolveCustomerName(customerEntityId: String, isWalkIn: Boolean, buyerName: String?): String {
        entities.firstOrNull { it.id == customerEntityId }?.name?.takeIf { it.isNotBlank() }?.let { return it }
        if (isWalkIn) return buyerName?.takeIf { it.isNotBlank() } ?: WALK_IN_LABEL
        return buyerName?.takeIf { it.isNotBlank() } ?: "—"
    }

    fun openSale(saleId: String) {
        val useCase = getSaleUseCase ?: return
        val current = session ?: return
        invoiceJob?.cancel()
        _uiState.update { it.copy(detailLoading = true, detail = null, detailInvoice = DetailInvoiceState(), sellerName = null) }
        scope.launch {
            runCatching { useCase.execute(current, saleId) }
                .onSuccess { detail ->
                    _uiState.update {
                        it.copy(
                            detailLoading = false,
                            detail = detail,
                            detailInvoice = DetailInvoiceState(invoice = detail?.invoice ?: SaleInvoice()),
                        )
                    }
                    if (detail != null) {
                        resolveSeller(detail.createdBy)
                        observeInvoice(saleId)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(detailLoading = false, error = e.message ?: "Couldn't load sale")
                    }
                }
        }
    }

    fun closeDetail() {
        invoiceJob?.cancel()
        invoiceJob = null
        _uiState.update {
            it.copy(detail = null, detailInvoice = DetailInvoiceState(), sellerName = null, voidError = null)
        }
    }

    /**
     * Resolve a sale's `createdBy` **uid** to a display name for the "Sold by" line. The current
     * user comes from the session; other sellers are read from `users/{uid}` once and cached.
     * Applied only if it's still the open sale (a fast re-open can't overwrite a newer detail).
     */
    private fun resolveSeller(uid: String) {
        if (uid.isBlank()) {
            _uiState.update { it.copy(sellerName = null) }
            return
        }
        session?.takeIf { it.uid == uid }?.let { s ->
            _uiState.update { it.copy(sellerName = s.displayName.takeIf { n -> n.isNotBlank() }) }
            return
        }
        sellerNameCache[uid]?.let { name ->
            _uiState.update { it.copy(sellerName = name) }
            return
        }
        val repo = userRepo ?: return
        val cfg = config ?: return
        scope.launch {
            val name = runCatching { repo.getUserProfile(cfg, uid)?.displayName }
                .getOrNull()?.takeIf { it.isNotBlank() }
            if (name != null) sellerNameCache[uid] = name
            _uiState.update { if (it.detail?.createdBy == uid) it.copy(sellerName = name) else it }
        }
    }

    /**
     * Observe the open sale's invoice live so the reused row resolves in place (a slow render, a
     * Retry, or a reconcile fix all surface without reopening). Also patches the corresponding row
     * in the list so a newly-issued number shows there too. Errors are swallowed.
     */
    private fun observeInvoice(saleId: String) {
        val useCase = observeSaleInvoiceUseCase ?: return
        invoiceJob = scope.launch {
            runCatching {
                useCase.execute(saleId).catch { }.collect { inv ->
                    _uiState.update { s ->
                        s.copy(
                            detailInvoice = s.detailInvoice.copy(invoice = inv),
                            sales = s.sales.map {
                                if (it.saleId == saleId) {
                                    it.copy(invoiceNumber = inv.number ?: it.invoiceNumber, invoiceStatus = inv.status)
                                } else {
                                    it
                                }
                            },
                        ).withLocalSearch()
                    }
                }
            }
        }
    }

    /**
     * Retry a FAILED invoice from history (ticket #83) — same behaviour as the counter (#77):
     * optimistic PENDING + button lock, the live stream carries the settled result, and a
     * client-side failure never forces FAILED over an invoice the stream has already settled.
     */
    fun retryInvoice() {
        val saleId = _uiState.value.detail?.saleId ?: return
        val useCase = retryInvoiceUseCase ?: return
        if (!_uiState.value.detailInvoice.canRetry) return
        _uiState.update {
            it.copy(
                detailInvoice = it.detailInvoice.copy(
                    isRetrying = true,
                    retryError = false,
                    invoice = it.detailInvoice.invoice.copy(status = SaleInvoiceStatus.PENDING),
                ),
            )
        }
        scope.launch {
            runCatching { useCase.execute(saleId) }
                .onFailure {
                    _uiState.update { s ->
                        if (s.detailInvoice.invoice.hasSettled) {
                            s
                        } else {
                            s.copy(
                                detailInvoice = s.detailInvoice.copy(
                                    retryError = true,
                                    invoice = s.detailInvoice.invoice.copy(status = SaleInvoiceStatus.FAILED),
                                ),
                            )
                        }
                    }
                }
            _uiState.update { it.copy(detailInvoice = it.detailInvoice.copy(isRetrying = false)) }
        }
    }

    /**
     * Void the open sale (ticket #85) — a full reversal, admin-only. Optimistically locks the action
     * (`isVoiding`) while [VoidSaleUseCase] runs; on success reloads the now-VOIDED detail and marks
     * the list row VOIDED; on failure surfaces the reason (e.g. a re-used IMEI) for the dialog. The
     * admin/reason gates live in the use case + the CF — this only drives the UI state machine.
     */
    fun voidSale(reason: String) {
        val saleId = _uiState.value.detail?.saleId ?: return
        val useCase = voidSaleUseCase ?: return
        val current = session ?: return
        if (_uiState.value.isVoiding) return
        _uiState.update { it.copy(isVoiding = true, voidError = null) }
        scope.launch {
            runCatching { useCase.execute(current, saleId, reason) }
                .onSuccess {
                    reloadAfterVoid(saleId)
                    _uiState.update { it.copy(isVoiding = false) }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isVoiding = false, voidError = e.message ?: "Couldn't void the sale") }
                }
        }
    }

    /** Clears a stale void-error banner (e.g. when the admin reopens the confirm dialog). */
    fun clearVoidError() {
        _uiState.update { it.copy(voidError = null) }
    }

    /** Re-fetch the voided sale so the detail flips to VOIDED read-only, and mark its list row. */
    private suspend fun reloadAfterVoid(saleId: String) {
        val useCase = getSaleUseCase ?: return
        val current = session ?: return
        val detail = runCatching { useCase.execute(current, saleId) }.getOrNull()
        _uiState.update { s ->
            s.copy(
                detail = detail ?: s.detail,
                sales = s.sales.map { if (it.saleId == saleId) it.copy(status = SaleStatus.VOIDED) else it },
            ).withLocalSearch()
        }
    }

    fun dispose() {
        scope.cancel()
        entityRepo?.close()
    }

    companion object {
        /** Firestore `whereIn` caps at 30 values. */
        const val CUSTOMER_IN_LIMIT = 30
        /** No entity matched the typed name → force an empty result (not "all sales"). */
        const val NO_MATCH_SENTINEL = "__no_customer_match__"
        const val WALK_IN_LABEL = "Walk-in Customer"
    }
}
