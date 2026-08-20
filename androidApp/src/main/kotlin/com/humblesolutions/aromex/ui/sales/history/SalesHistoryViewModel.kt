package com.humblesolutions.aromex.ui.sales.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.BackendSalesRepository
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How the phone's single search box read what was typed (ticket #84). Unlike Desktop's five
 * separate filter controls, phones get **one** box: as-you-type it filters the already-loaded
 * rows locally (customer / invoice # / item label / IMEI); on submit it runs a **server-side**
 * query against full history using the reading below, so a full-history result is reachable and
 * explainable. Kept in the ViewModel layer per the KMP architecture (filter enums aren't shared).
 */
enum class SalesSearchKind { NONE, IMEI, INVOICE, CUSTOMER }

/**
 * Classify a submitted search string (ticket #84):
 * - all digits and 14–17 long → [IMEI] (an IMEI is 15, allowing a little slack for serials);
 * - starts with the invoice prefix `INV-` (case-insensitive) → [INVOICE];
 * - anything else → [CUSTOMER] (a free-text name, resolved to entity ids before querying);
 * - blank → [NONE] (the plain newest-first list).
 *
 * Pure + deterministic so it's unit-tested directly.
 */
fun detectSearchKind(text: String): SalesSearchKind {
    val t = text.trim()
    if (t.isEmpty()) return SalesSearchKind.NONE
    if (t.all { it.isDigit() } && t.length in 14..17) return SalesSearchKind.IMEI
    if (t.uppercase().startsWith(INVOICE_PREFIX)) return SalesSearchKind.INVOICE
    return SalesSearchKind.CUSTOMER
}

/** The issued-invoice number prefix (`INV-000042`); a typed value starting with it is an invoice #. */
const val INVOICE_PREFIX = "INV-"

/**
 * Live invoice state for the open sale's detail view (ticket #84) — the inputs the reused #77
 * invoice row needs (Open / Share / Download / Retry). Mirrors the counter so the same states
 * render identically here.
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
    /** Every sale loaded so far for the current query (all pages). */
    val sales: List<SaleSummary> = emptyList(),
    /** [sales] after the **local** as-you-type filter — what the list renders. */
    val visibleSales: List<SaleSummary> = emptyList(),
    val hasMore: Boolean = false,
    /** The single search box text — an instant local filter; submit runs the server search. */
    val searchText: String = "",
    /**
     * How the **last submitted** server search was read (ticket #84) — drives the "Searched by …"
     * hint. [SalesSearchKind.NONE] when the list is the plain newest-first list (no hint shown).
     */
    val searchKind: SalesSearchKind = SalesSearchKind.NONE,
    // ── Detail view ──────────────────────────────────────────────────────────────
    val detailLoading: Boolean = false,
    val detail: SaleDetail? = null,
    val detailInvoice: DetailInvoiceState = DetailInvoiceState(),
    /** The open sale's seller resolved to a display name (from `createdBy` uid); null while resolving. */
    val sellerName: String? = null,
    /** True when the signed-in user is an admin — gates the Void action (ticket #85). */
    val isAdmin: Boolean = false,
    /** True while a void is in flight (optimistic) — locks the action + shows progress. */
    val isVoiding: Boolean = false,
    /** The last void failure reason (e.g. a re-used IMEI), shown in the confirm dialog. */
    val voidError: String? = null,
) {
    /** True once a search has narrowed the list — distinguishes "no sales yet" from "no match". */
    val hasActiveSearch: Boolean get() = searchText.isNotBlank() || searchKind != SalesSearchKind.NONE

    /** The open sale can be voided iff the user is an admin and it isn't already VOIDED (ticket #85). */
    val canVoidOpenSale: Boolean get() = isAdmin && detail?.isVoided == false
}

/**
 * Local as-you-type predicate (ticket #84): does [sale] match [query] by customer name, invoice
 * number, any item label, or any IMEI? Substring, case-insensitive, over already-loaded rows only.
 * [customerName] is the row's resolved display name (name resolution needs the entities cache).
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
 * Android Sales History ViewModel (ticket #84) — bare-but-stable phone counterpart of the
 * Desktop screen (#83), bound to the **same** shared query layer (no new shared code). Sales are
 * unbounded, so this pages the collection: [QuerySalesUseCase] runs the query and [loadMore]
 * appends the next page via the cursor; nothing is re-fetched to filter client-side. The single
 * search box filters loaded rows instantly ([onSearchChanged]) and reaches full history on submit
 * ([onSearchSubmit]). Manual DI in [bind]; [bindForTest] injects fakes for the paging/detection tests.
 */
class SalesHistoryViewModel(application: Application) : AndroidViewModel(application) {

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null
    private var userRepo: FirestoreUserRepository? = null

    private var querySalesUseCase: QuerySalesUseCase? = null
    private var getSaleUseCase: GetSaleUseCase? = null
    private var observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = null
    private var retryInvoiceUseCase: RetryInvoiceUseCase? = null
    private var voidSaleUseCase: VoidSaleUseCase? = null

    /** Cached parties, for resolving a typed customer name → entity ids (bounded collection). */
    private var entities: List<Entity> = emptyList()
    /** uid → display name, so re-opening a sale by the same seller doesn't re-read `users/{uid}`. */
    private val sellerNameCache = mutableMapOf<String, String>()

    private var nextCursor: SalesCursor? = null
    private var invoiceJob: Job? = null

    private val _uiState = MutableStateFlow(SalesHistoryUiState())
    val uiState: StateFlow<SalesHistoryUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        val app = getApplication<Application>()
        val sales = BackendSalesRepository(app, config)
        val entityRepo = BackendEntityRepository(app, config)
        userRepo = FirestoreUserRepository(app)
        querySalesUseCase = QuerySalesUseCase(sales)
        getSaleUseCase = GetSaleUseCase(sales)
        observeSaleInvoiceUseCase = ObserveSaleInvoiceUseCase(sales)
        retryInvoiceUseCase = RetryInvoiceUseCase(sales)
        voidSaleUseCase = VoidSaleUseCase(sales)

        _uiState.update {
            it.copy(currency = session.currency, timezone = session.timezone, isAdmin = session.role == UserRole.ADMIN)
        }

        // Cache entities for name → id resolution (bounded; a live stream keeps it fresh). The
        // observe throws PermissionDenied synchronously without profiles access — swallow it; name
        // search just won't resolve then, while the sales list (its own gate) still works.
        val entitiesUseCase = ObserveEntitiesUseCase(entityRepo)
        viewModelScope.launch {
            runCatching {
                entitiesUseCase.execute(session).catch { }.collect { entities = it }
            }
        }

        loadFirstPage()
    }

    /** Test seam — inject fakes over the use cases + entities, skipping Firebase wiring. */
    internal fun bindForTest(
        session: UserSession,
        querySalesUseCase: QuerySalesUseCase,
        getSaleUseCase: GetSaleUseCase,
        entities: List<Entity> = emptyList(),
        retryInvoiceUseCase: RetryInvoiceUseCase? = null,
        observeSaleInvoiceUseCase: ObserveSaleInvoiceUseCase? = null,
        voidSaleUseCase: VoidSaleUseCase? = null,
    ) {
        this.session = session
        this.querySalesUseCase = querySalesUseCase
        this.getSaleUseCase = getSaleUseCase
        this.retryInvoiceUseCase = retryInvoiceUseCase
        this.observeSaleInvoiceUseCase = observeSaleInvoiceUseCase
        this.voidSaleUseCase = voidSaleUseCase
        this.entities = entities
        _uiState.update {
            it.copy(currency = session.currency, timezone = session.timezone, isAdmin = session.role == UserRole.ADMIN)
        }
        loadFirstPage()
    }

    // ── Search ─────────────────────────────────────────────────────────────────────

    /**
     * As-you-type: an instant **local** refine over loaded rows (customer / invoice # / item label
     * / IMEI). No Firestore round-trip — full-history lookups happen on [onSearchSubmit].
     */
    fun onSearchChanged(text: String) {
        _uiState.update { it.copy(searchText = text).withLocalSearch() }
    }

    /**
     * Submit (keyboard Search): run the **server-side** smart search over full history. The typed
     * string is classified by [detectSearchKind] and mapped to the matching [SalesQuery] field; a
     * blank submit resets to the plain newest-first list. The interpretation is surfaced as a hint.
     */
    fun onSearchSubmit() {
        val text = _uiState.value.searchText.trim()
        val kind = detectSearchKind(text)
        _uiState.update { it.copy(searchKind = kind) }
        loadFirstPage()
    }

    /** Clear the box and return to the plain newest-first list. */
    fun clearSearch() {
        _uiState.update { it.copy(searchText = "", searchKind = SalesSearchKind.NONE) }
        loadFirstPage()
    }

    /** Recompute [SalesHistoryUiState.visibleSales] from [sales] + the local [searchText]. */
    private fun SalesHistoryUiState.withLocalSearch(): SalesHistoryUiState =
        copy(visibleSales = sales.filter { it.matchesLocalSearch(searchText, customerNameOf(it)) })

    /**
     * Translate the submitted search into a [SalesQuery] per its [SalesSearchKind]. A customer name
     * is resolved to entity ids against the cached parties (capped at Firestore's `in` limit); no
     * match → a sentinel id so the query returns nothing rather than everything.
     */
    private fun buildQuery(cursor: SalesCursor?): SalesQuery {
        val text = _uiState.value.searchText.trim()
        return when (_uiState.value.searchKind) {
            SalesSearchKind.NONE -> SalesQuery(cursor = cursor)
            SalesSearchKind.IMEI -> SalesQuery(imei = text, cursor = cursor)
            SalesSearchKind.INVOICE -> SalesQuery(invoiceNumber = text, cursor = cursor)
            SalesSearchKind.CUSTOMER -> {
                val ids = entities.asSequence()
                    .filter { it.name.contains(text, ignoreCase = true) }
                    .map { it.id }
                    .take(CUSTOMER_IN_LIMIT)
                    .toList()
                    .ifEmpty { listOf(NO_MATCH_SENTINEL) }
                SalesQuery(customerEntityIds = ids, cursor = cursor)
            }
        }
    }

    // ── Paging ───────────────────────────────────────────────────────────────────

    private fun loadFirstPage() {
        val useCase = querySalesUseCase ?: return
        val current = session ?: return
        nextCursor = null
        _uiState.update { it.copy(isLoading = true, error = null, permissionDenied = false) }
        viewModelScope.launch {
            runCatching { useCase.execute(current, buildQuery(cursor = null)) }
                .onSuccess { page ->
                    nextCursor = page.nextCursor
                    _uiState.update {
                        it.copy(isLoading = false, sales = page.sales, hasMore = page.nextCursor != null).withLocalSearch()
                    }
                }
                .onFailure { e -> onLoadFailure(e) }
        }
    }

    /**
     * Append the next page (ticket #84 UI rule: never blank the list — append). No-op while a page
     * is already loading or the end has been reached.
     */
    fun loadMore() {
        val useCase = querySalesUseCase ?: return
        val current = session ?: return
        val cursor = nextCursor ?: return
        if (_uiState.value.isLoadingMore || _uiState.value.isLoading) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
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
                .onFailure { e -> onLoadFailure(e) }
        }
    }

    private fun onLoadFailure(e: Throwable) {
        if (e is PermissionDeniedException) {
            _uiState.update {
                it.copy(isLoading = false, isLoadingMore = false, permissionDenied = true, sales = emptyList(), visibleSales = emptyList())
            }
            return
        }
        _uiState.update {
            it.copy(isLoading = false, isLoadingMore = false, error = e.message ?: "Couldn't load sales")
        }
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
        viewModelScope.launch {
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
                    _uiState.update { it.copy(detailLoading = false, error = e.message ?: "Couldn't load sale") }
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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Clears a stale void-error banner (e.g. when the admin reopens the confirm dialog). */
    fun clearVoidError() {
        _uiState.update { it.copy(voidError = null) }
    }

    /**
     * Void the open sale (ticket #85) — a full reversal, admin-only. Calls the `voidSale` callable
     * via [VoidSaleUseCase] (which gates on admin + a non-blank reason; the CF re-verifies admin
     * server-side). Optimistically locks the action; on success reloads the now-VOIDED detail and
     * marks the list row; on failure surfaces the reason for the dialog.
     */
    fun voidSale(reason: String) {
        val saleId = _uiState.value.detail?.saleId ?: return
        val useCase = voidSaleUseCase ?: return
        val current = session ?: return
        if (_uiState.value.isVoiding) return
        _uiState.update { it.copy(isVoiding = true, voidError = null) }
        viewModelScope.launch {
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

    /**
     * Resolve a sale's `createdBy` uid to a display name for the "Sold by" line. The current user
     * comes from the session; other sellers are read from `users/{uid}` once and cached. Applied
     * only if it's still the open sale (a fast re-open can't overwrite a newer detail).
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
        viewModelScope.launch {
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
        invoiceJob = viewModelScope.launch {
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
     * Retry a FAILED invoice from history (ticket #84) — same behaviour as the counter (#77):
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
        viewModelScope.launch {
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

    private companion object {
        /** Firestore `whereIn` caps at 30 values. */
        const val CUSTOMER_IN_LIMIT = 30
        /** No entity matched the typed name → force an empty result (not "all sales"). */
        const val NO_MATCH_SENTINEL = "__no_customer_match__"
        const val WALK_IN_LABEL = "Walk-in Customer"
    }
}
