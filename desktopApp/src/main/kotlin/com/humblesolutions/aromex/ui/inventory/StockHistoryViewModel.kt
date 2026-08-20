package com.humblesolutions.aromex.ui.inventory

import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.BackendErrors
import com.humblesolutions.aromex.data.BackendInventoryRepository
import com.humblesolutions.aromex.data.BackendStockHistoryRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.BatchReversalBlock
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.StockBatch
import com.humblesolutions.aromex.model.UNSPECIFIED_SUPPLIER_ID
import com.humblesolutions.aromex.model.UNSPECIFIED_SUPPLIER_NAME
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.model.batchReversalBlock
import com.humblesolutions.aromex.repository.StockHistoryRepository
import com.humblesolutions.aromex.usecase.LoadBatchUnitsUseCase
import com.humblesolutions.aromex.usecase.ObserveEntitiesUseCase
import com.humblesolutions.aromex.usecase.ObserveInventoryUseCase
import com.humblesolutions.aromex.usecase.ObserveStockBatchesUseCase
import com.humblesolutions.aromex.usecase.RequestStockBatchReversalUseCase
import com.humblesolutions.aromex.util.Money
import kotlinx.coroutines.CancellationException
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

/** The columns the history can be ordered by. */
enum class BatchSortColumn { DATE, COST, UNITS }

/** Which batches the table shows. Reversed ones stay findable without cluttering the default view. */
enum class BatchStatusFilter { ALL, ACTIVE, REVERSED }

/**
 * A batch the user is about to reverse, with everything the confirmation needs — the units it
 * would pull, and the reason it can't be pulled if that's the case.
 */
data class PendingReversal(
    val batch: StockBatch,
    val units: List<Serial>,
    val block: BatchReversalBlock?,
    val reason: String = "",
) {
    val canConfirm: Boolean get() = block == null && reason.isNotBlank()
}

data class StockHistoryUiState(
    val session: UserSession? = null,
    val isAdmin: Boolean = false,
    val noAccess: Boolean = false,
    val isLoading: Boolean = true,
    val currency: String = "",
    val batches: List<StockBatch> = emptyList(),
    val error: String? = null,
    val errorKey: String? = null,
    // ── controls ─────────────────────────────────────────────────────────────
    val searchQuery: String = "",
    val sortBy: BatchSortColumn = BatchSortColumn.DATE,
    /** Newest first — the batch you just added is the one you want to see. */
    val sortAscending: Boolean = false,
    val rangeFrom: Long? = null,
    val rangeTo: Long? = null,
    val statusFilter: BatchStatusFilter = BatchStatusFilter.ACTIVE,
    /** How many batches the live query is currently asking for. Grown by Load more. */
    val windowSize: Int = ObserveStockBatchesUseCase.PAGE_SIZE,
    val isLoadingMore: Boolean = false,
    // ── row detail ───────────────────────────────────────────────────────────
    val expandedBatchId: String? = null,
    val expandedUnits: List<Serial> = emptyList(),
    val isLoadingUnits: Boolean = false,
    // ── reversal ─────────────────────────────────────────────────────────────
    val pendingReversal: PendingReversal? = null,
    /** Set while a batch's units are being fetched for the confirmation. */
    val preparingReversalFor: String? = null,
    val submittingReversalFor: String? = null,
    // ── labels (entityId → name, productId → SKU label) ──────────────────────
    val partyNames: Map<String, String> = emptyMap(),
    val productLabels: Map<String, String> = emptyMap(),
) {
    fun partyName(entityId: String): String = when {
        entityId == UNSPECIFIED_SUPPLIER_ID -> UNSPECIFIED_SUPPLIER_NAME
        else -> partyNames[entityId] ?: "—"
    }

    /** The SKU label for a unit, for the expanded list. Falls back to the IMEI. */
    fun unitLabel(unit: Serial): String = productLabels[unit.productId]?.takeIf { it.isNotBlank() } ?: unit.imei

    val isFiltered: Boolean
        get() = searchQuery.isNotBlank() || rangeFrom != null || rangeTo != null ||
            statusFilter != BatchStatusFilter.ACTIVE

    /**
     * True when the window is full, so there is probably older history behind it. Firestore can't
     * say "there are more" without fetching them, and claiming a definite count we don't have
     * would be worse than offering one more press.
     */
    val mayHaveMore: Boolean get() = batches.size >= windowSize

    /**
     * What the table shows: [batches] narrowed by status, date range and search text, then ordered.
     * Derived here rather than in the UI so the ordering and the "showing N of M" count can never
     * disagree.
     */
    val visibleBatches: List<StockBatch>
        get() {
            val q = searchQuery.trim()
            val filtered = batches.filter { b ->
                val statusOk = when (statusFilter) {
                    BatchStatusFilter.ALL -> true
                    BatchStatusFilter.ACTIVE -> !b.isReversed
                    BatchStatusFilter.REVERSED -> b.isReversed
                }
                if (!statusOk) return@filter false
                val inRange = (rangeFrom == null || b.createdAt >= rangeFrom) &&
                    (rangeTo == null || b.createdAt <= rangeTo)
                if (!inRange) return@filter false
                if (q.isEmpty()) return@filter true
                partyName(b.partyEntityId).contains(q, true) ||
                    b.totalCost.contains(q) ||
                    b.unitCount.toString() == q
            }
            val ordered = when (sortBy) {
                BatchSortColumn.DATE -> filtered.sortedBy { it.createdAt }
                BatchSortColumn.COST -> filtered.sortedWith { a, b -> Money.compare(a.totalCost, b.totalCost) }
                BatchSortColumn.UNITS -> filtered.sortedBy { it.unitCount }
            }
            return if (sortAscending) ordered else ordered.reversed()
        }
}

/**
 * Stock History (ticket #106) — every Add-Inventory batch, and the one place a batch can be
 * reversed.
 *
 * Reversing is a **request**: this asks the backend, which re-checks the requester is an admin and
 * that the batch is still whole, reverses the Humble Ledger legs and pulls the stock. The row then
 * settles under the user through the live listener, exactly like a sale void.
 */
class StockHistoryViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)

    private var historyRepo: BackendStockHistoryRepository? = null
    private var inventoryRepo: BackendInventoryRepository? = null
    private var entityRepo: BackendEntityRepository? = null
    private var observeBatchesUseCase: ObserveStockBatchesUseCase? = null
    private var loadUnitsUseCase: LoadBatchUnitsUseCase? = null
    private var reverseUseCase: RequestStockBatchReversalUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private val _uiState = MutableStateFlow(StockHistoryUiState())
    val uiState: StateFlow<StockHistoryUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        if (session.permissions.inventory == PermissionLevel.NONE) {
            _uiState.update { it.copy(session = session, noAccess = true, isLoading = false) }
            return
        }

        val broker = FirestoreTokenBroker(authRepo, config)
        val history = BackendStockHistoryRepository(broker, config)
        val inventory = BackendInventoryRepository(broker, config, session.uid)
        val entities = BackendEntityRepository(broker, config, session.uid)
        historyRepo = history
        inventoryRepo = inventory
        entityRepo = entities
        observeBatchesUseCase = ObserveStockBatchesUseCase(history)
        loadUnitsUseCase = LoadBatchUnitsUseCase(history)
        reverseUseCase = RequestStockBatchReversalUseCase(history)

        _uiState.update {
            it.copy(
                session = session,
                isAdmin = session.role == UserRole.ADMIN,
                currency = session.currency,
                isLoading = true,
            )
        }

        start(
            batches = { limit, from, to -> ObserveStockBatchesUseCase(history).execute(session, limit, from, to) },
            parties = { ObserveEntitiesUseCase(entities).execute(session) },
            products = { ObserveInventoryUseCase(inventory).observeProducts(session) },
        )
    }

    /** Test seam: inject a fake repository and the two label streams, skipping Firestore. */
    internal fun bindForTest(
        session: UserSession,
        repository: StockHistoryRepository,
        parties: Flow<List<Entity>> = kotlinx.coroutines.flow.flowOf(emptyList()),
        products: Flow<List<Product>> = kotlinx.coroutines.flow.flowOf(emptyList()),
    ) {
        this.session = session
        observeBatchesUseCase = ObserveStockBatchesUseCase(repository)
        loadUnitsUseCase = LoadBatchUnitsUseCase(repository)
        reverseUseCase = RequestStockBatchReversalUseCase(repository)
        _uiState.update {
            it.copy(session = session, isAdmin = session.role == UserRole.ADMIN, currency = session.currency)
        }
        start(
            batches = { limit, from, to -> ObserveStockBatchesUseCase(repository).execute(session, limit, from, to) },
            parties = { parties },
            products = { products },
        )
    }

    /** The live batches subscription; cancelled and replaced when the window or range changes. */
    private var batchesJob: Job? = null

    /** Rebuilt on every re-subscribe, so the query always matches the state that asked for it. */
    private var batchesSource: ((Int, Long?, Long?) -> Flow<List<StockBatch>>)? = null

    private fun start(
        batches: (Int, Long?, Long?) -> Flow<List<StockBatch>>,
        parties: () -> Flow<List<Entity>>,
        products: () -> Flow<List<Product>>,
    ) {
        batchesSource = batches
        subscribeBatches()
        // Labels only. A user with inventory but not `profiles` still gets the table; the party
        // column falls back rather than the screen failing.
        scope.launch {
            runCatching {
                parties()
                    .catch { }
                    .collect { list ->
                        _uiState.update { s -> s.copy(partyNames = list.associate { it.id to it.name }) }
                    }
            }
        }
        scope.launch {
            runCatching {
                products()
                    .catch { }
                    .collect { list ->
                        _uiState.update { s ->
                            s.copy(productLabels = list.associate { it.productId to it.attributes.skuLabel() })
                        }
                    }
            }
        }
    }

    /**
     * (Re)open the live batches query for the current window and date range.
     *
     * Flow *factory*, not a constructed flow: the observe use case throws
     * PermissionDeniedException synchronously, so construction must happen inside the guarded
     * launch rather than on the caller's thread.
     */
    private fun subscribeBatches() {
        val source = batchesSource ?: return
        val s = _uiState.value
        batchesJob?.cancel()
        batchesJob = scope.launch {
            try {
                source(s.windowSize, s.rangeFrom, s.rangeTo)
                    .catch { e ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isLoadingMore = false,
                                errorKey = BackendErrors.messageKeyOrNull(e),
                                error = e.message,
                            )
                        }
                    }
                    .collect { list ->
                        _uiState.update { cur ->
                            val submitted = cur.submittingReversalFor
                            // Hold the local "submitting" flag until the batch itself reports a
                            // reversal — otherwise the row flips back to idle in the gap before
                            // the doc arrives and a second click could fire a second reversal.
                            val settled = submitted != null &&
                                list.firstOrNull { it.purchaseId == submitted }
                                    ?.let { it.isReversed || it.reversalStatus != null } == true
                            cur.copy(
                                batches = list,
                                isLoading = false,
                                isLoadingMore = false,
                                submittingReversalFor = if (settled) null else submitted,
                            )
                        }
                    }
            } catch (e: CancellationException) {
                // Re-subscribing (a date-range change, Load more) cancels this live listener. That
                // is not a failure — let it propagate, or the catch below would misread the tear-down
                // as a permission denial and drop the whole screen into the no-access state.
                throw e
            } catch (e: Throwable) {
                // A genuine synchronous throw from the use case — e.g. PermissionDeniedException.
                _uiState.update { it.copy(isLoading = false, isLoadingMore = false, noAccess = true) }
            }
        }
    }

    // ── controls ─────────────────────────────────────────────────────────────

    fun setSearch(q: String) = _uiState.update { it.copy(searchQuery = q) }

    /**
     * The range bounds the **query**, so picking a month from years ago fetches it rather than
     * filtering an empty recent window. The window resets: a fresh range starts at page one.
     */
    fun setDateRange(from: Long?, to: Long?) {
        _uiState.update {
            it.copy(
                rangeFrom = from,
                rangeTo = to,
                windowSize = ObserveStockBatchesUseCase.PAGE_SIZE,
                isLoading = true,
            )
        }
        subscribeBatches()
    }

    /** Widen the live window by one page. One query, so every row stays as live as the first. */
    fun loadMore() {
        val s = _uiState.value
        if (s.isLoadingMore || !s.mayHaveMore) return
        _uiState.update {
            it.copy(windowSize = it.windowSize + ObserveStockBatchesUseCase.PAGE_SIZE, isLoadingMore = true)
        }
        subscribeBatches()
    }

    fun setStatusFilter(filter: BatchStatusFilter) = _uiState.update { it.copy(statusFilter = filter) }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                rangeFrom = null,
                rangeTo = null,
                statusFilter = BatchStatusFilter.ACTIVE,
                windowSize = ObserveStockBatchesUseCase.PAGE_SIZE,
                isLoading = true,
            )
        }
        subscribeBatches()
    }

    /** Clicking the active column flips direction; a new column starts newest/largest first. */
    fun toggleSort(column: BatchSortColumn) = _uiState.update {
        if (it.sortBy == column) it.copy(sortAscending = !it.sortAscending)
        else it.copy(sortBy = column, sortAscending = false)
    }

    fun clearError() = _uiState.update { it.copy(error = null, errorKey = null) }

    // ── row detail ───────────────────────────────────────────────────────────

    /** Expand a row to show the phones it brought in; clicking the open row closes it. */
    fun toggleExpanded(batch: StockBatch) {
        val current = _uiState.value
        if (current.expandedBatchId == batch.purchaseId) {
            _uiState.update { it.copy(expandedBatchId = null, expandedUnits = emptyList()) }
            return
        }
        _uiState.update { it.copy(expandedBatchId = batch.purchaseId, expandedUnits = emptyList(), isLoadingUnits = true) }
        val useCase = loadUnitsUseCase ?: return
        val current2 = session ?: return
        scope.launch {
            runCatching { useCase.execute(current2, batch.purchaseId) }
                .onSuccess { units ->
                    _uiState.update { s ->
                        // A slower load for a row the user already closed must not repopulate it.
                        if (s.expandedBatchId != batch.purchaseId) s
                        else s.copy(expandedUnits = units, isLoadingUnits = false)
                    }
                }
                .onFailure { _uiState.update { it.copy(isLoadingUnits = false) } }
        }
    }

    // ── reversal ─────────────────────────────────────────────────────────────

    /**
     * Open the confirmation. The batch's units are fetched first so the dialog can state what will
     * happen — or exactly why it can't — rather than asking the user to find out by pressing it.
     */
    fun askReverse(batch: StockBatch) {
        val useCase = loadUnitsUseCase ?: return
        val current = session ?: return
        if (_uiState.value.preparingReversalFor != null) return
        _uiState.update { it.copy(preparingReversalFor = batch.purchaseId) }
        scope.launch {
            val units = runCatching { useCase.execute(current, batch.purchaseId) }.getOrElse { emptyList() }
            _uiState.update {
                it.copy(
                    preparingReversalFor = null,
                    pendingReversal = PendingReversal(
                        batch = batch,
                        units = units,
                        block = batchReversalBlock(batch, units),
                    ),
                )
            }
        }
    }

    fun setReversalReason(reason: String) = _uiState.update { s ->
        s.copy(pendingReversal = s.pendingReversal?.copy(reason = reason))
    }

    fun dismissReversal() = _uiState.update { it.copy(pendingReversal = null) }

    fun confirmReverse() {
        val pending = _uiState.value.pendingReversal ?: return
        val useCase = reverseUseCase ?: return
        val current = session ?: return
        if (!pending.canConfirm) return

        _uiState.update {
            it.copy(pendingReversal = null, submittingReversalFor = pending.batch.purchaseId)
        }
        scope.launch {
            runCatching {
                useCase.execute(current, pending.batch, pending.units, pending.reason)
            }
                .onSuccess {
                    // Deliberately NOT cleared here: the flag is released by the batches collector
                    // once the doc itself reports the reversal, so the button can't be pressed
                    // twice in the gap before that lands.
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            submittingReversalFor = null,
                            errorKey = BackendErrors.messageKeyOrNull(e),
                            error = e.message,
                        )
                    }
                }
        }
    }

    fun dispose() {
        scope.cancel()
        historyRepo?.close()
        inventoryRepo?.close()
        entityRepo?.close()
    }
}

/** SKU label from the SKU-defining attributes, mirroring the inventory browse label. */
private fun Map<AttributeType, com.humblesolutions.aromex.model.AttributeRef>.skuLabel(): String =
    AttributeType.SKU_DEFINING
        .mapNotNull { this[it]?.name?.takeIf { n -> n.isNotBlank() } }
        .joinToString(" · ")
