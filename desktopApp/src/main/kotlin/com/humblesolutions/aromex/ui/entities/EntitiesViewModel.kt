package com.humblesolutions.aromex.ui.entities

import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.data.HL_API_BASE_URL
import com.humblesolutions.aromex.data.HlTokenRepository
import com.humblesolutions.aromex.data.KtorEntityLedgerRepository
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityLedgerRepository
import com.humblesolutions.aromex.data.BackendMoneyEntryRepository
import com.humblesolutions.aromex.model.AccountStatement
import com.humblesolutions.aromex.model.BalanceDirection
import java.math.BigDecimal
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.data.BackendStatementPdfRepository
import com.humblesolutions.aromex.repository.StatementPdfRepository
import com.humblesolutions.aromex.usecase.ArchiveEntityUseCase
import com.humblesolutions.aromex.usecase.BuildPartyStatementUseCase
import com.humblesolutions.aromex.usecase.StatementTooLargeException
import com.humblesolutions.aromex.usecase.GetAccountStatementUseCase
import com.humblesolutions.aromex.usecase.ObserveMoneyEntriesUseCase
import com.humblesolutions.aromex.usecase.ReverseMoneyEntryUseCase
import com.humblesolutions.aromex.usecase.GetEntityBalancesUseCase
import com.humblesolutions.aromex.usecase.ObserveEntitiesUseCase
import com.humblesolutions.aromex.usecase.SaveEntityUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EntitiesFilter { ALL, CUSTOMER, SUPPLIER, MIDDLEMAN }

/**
 * Narrows the list to who owes money, and in which direction (part of #86's intent).
 *
 * Separate from [EntitiesFilter] on purpose: "customers who owe me" is a real question, so role and
 * balance have to be independent rather than crammed into one list of chips.
 */
enum class BalanceFilter { ALL, OWES_ME, I_OWE }

/**
 * List order. [BALANCE] puts the largest debt first — the collections view — and is the reason this
 * exists at all; alphabetical is useless when the question is "who do I chase".
 */
enum class EntitySort { NAME, BALANCE }

data class EntityRow(val entity: Entity, val balance: EntityBalance?)

data class EntitiesUiState(
    val session: UserSession? = null,
    val canManage: Boolean = false,
    val noAccess: Boolean = false,
    val rows: List<EntityRow> = emptyList(),
    val query: String = "",
    val filter: EntitiesFilter = EntitiesFilter.ALL,
    val balanceFilter: BalanceFilter = BalanceFilter.ALL,
    val sortBy: EntitySort = EntitySort.NAME,
    val isLoadingBalances: Boolean = false,
    /** When HL balances last came back, so the UI can show how fresh they are. */
    val balancesRefreshedAt: Long? = null,
    val error: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
    // ── Party statement (ticket #91), for the selected party in the detail panel ──
    val statement: AccountStatement? = null,
    val statementEntityId: String? = null,
    val isLoadingStatement: Boolean = false,
    /** Set when HL couldn't be reached — never render an empty statement as "no history". */
    val statementError: String? = null,
    // ── statement controls (ticket #108), mirroring the money ledger's ───────────
    /** Free text over the rows already loaded — HL's ledger has no text search. */
    val statementSearch: String = "",
    /** Bounds sent to HL, so a range reaches history that hasn't been paged in yet. */
    val statementFrom: Long? = null,
    val statementTo: Long? = null,
    /**
     * Newest first by default. Ordering is by the **transaction date** — the day the money moved,
     * not the day it was keyed in — so a backdated sale sits where it belongs rather than at the
     * top because someone entered it this morning.
     */
    val statementAscending: Boolean = false,
    /**
     * The app's own money movements, so a statement row can be traced back to the entry that
     * created it — which is what lets Reverse appear here as well as on the Money screen.
     */
    val moneyEntries: List<MoneyEntry> = emptyList(),
    val pendingReversal: MoneyEntry? = null,
    val reversingEntryId: String? = null,
    // ── Print statement (ticket #109) ────────────────────────────────────────────
    val printDialogOpen: Boolean = false,
    /** Range for the PDF, defaulting to the on-screen range (else last 3 months) when opened. */
    val printFrom: Long? = null,
    val printTo: Long? = null,
    /** Off by default — a customer copy shouldn't carry staff notes. */
    val printIncludeNotes: Boolean = false,
    val printGenerating: Boolean = false,
    val printError: String? = null,
    /** Set once the PDF is rendered; the detail panel shows the viewer while non-null. */
    val printPdfUrl: String? = null,
) {
    /** Ledger transaction id → the money entry that posted it. */
    val moneyByTransaction: Map<String, MoneyEntry>
        get() = moneyEntries.mapNotNull { e -> e.hlTransactionId?.let { it to e } }.toMap()

    /**
     * Rows to omit from the statement: those posted by a money entry that has since been reversed,
     * and the reversing entries themselves. HL's own REVERSAL pairs are already dropped in the
     * repository; these are ours, which post as ordinary payments/payouts and so can't be spotted
     * from the ledger alone.
     */
    val cancelledTransactionIds: Set<String>
        get() = moneyEntries.filter { it.isCancelled }.mapNotNull { it.hlTransactionId }.toSet()
}

/**
 * Desktop list + add/edit/archive for Profiles/Entities (test-only UI).
 *
 * Plain class + [CoroutineScope] (Desktop has no AndroidViewModel lifecycle), so
 * call [dispose] when the screen leaves composition. Reaches Firestore via the
 * Admin SDK ([BackendEntityRepository]) — rules DON'T apply here, so all writes go
 * through the shared use cases, which are the only permission enforcement.
 */
class EntitiesViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private companion object {
        /** Enough recent movements to cover anything a statement page can show. */
        const val MONEY_LOOKUP_LIMIT = 200

        /** Below this age, re-entering the screen reuses what's already loaded. */
        const val AUTO_REFRESH_MIN_INTERVAL_MS = 20_000L

        /** Default print range when none is on screen: the last 3 months. */
        const val DEFAULT_RANGE_DAYS = 90L
        const val MILLIS_PER_DAY = 86_400_000L
    }

    private val prefs = DesktopPreferencesRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)

    private var entityRepo: BackendEntityRepository? = null
    private var ledgerRepo: EntityLedgerRepository? = null
    private var observeUseCase: ObserveEntitiesUseCase? = null
    private var balancesUseCase: GetEntityBalancesUseCase? = null
    private var archiveUseCase: ArchiveEntityUseCase? = null
    private var saveUseCase: SaveEntityUseCase? = null
    private var statementUseCase: GetAccountStatementUseCase? = null
    private var buildStatementUseCase: BuildPartyStatementUseCase? = null
    private var statementPdfRepo: StatementPdfRepository? = null
    private var observeMoneyUseCase: ObserveMoneyEntriesUseCase? = null
    private var reverseMoneyUseCase: ReverseMoneyEntryUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private var entities: List<Entity> = emptyList()
    private var balances: Map<String, EntityBalance> = emptyMap()

    private val _uiState = MutableStateFlow(EntitiesUiState())
    val uiState: StateFlow<EntitiesUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        val broker = FirestoreTokenBroker(authRepo, config)
        val entRepo = BackendEntityRepository(broker, config, session.uid)
        val tokens = HlTokenRepository(authRepo, config)
        val ledger = KtorEntityLedgerRepository(tokens, HL_API_BASE_URL)
        entityRepo = entRepo
        ledgerRepo = ledger
        observeUseCase = ObserveEntitiesUseCase(entRepo)
        balancesUseCase = GetEntityBalancesUseCase(ledger)
        archiveUseCase = ArchiveEntityUseCase(entRepo)
        saveUseCase = SaveEntityUseCase(entRepo)
        statementUseCase = GetAccountStatementUseCase(ledger)
        buildStatementUseCase = BuildPartyStatementUseCase(ledger)
        statementPdfRepo = BackendStatementPdfRepository(authRepo, config)
        val money = BackendMoneyEntryRepository(broker, config, session.uid)
        observeMoneyUseCase = ObserveMoneyEntriesUseCase(money)
        reverseMoneyUseCase = ReverseMoneyEntryUseCase(money)

        val profiles = session.permissions.profiles
        if (profiles == PermissionLevel.NONE) {
            _uiState.update { it.copy(session = session, noAccess = true) }
            return
        }
        _uiState.update {
            it.copy(session = session, canManage = profiles == PermissionLevel.MANAGE, noAccess = false)
        }
        observeEntities()
        refreshBalances()
        observeMoneyEntries()
    }

    /** Money movements, so the statement knows which of its rows this app can reverse. */
    private fun observeMoneyEntries() {
        val useCase = observeMoneyUseCase ?: return
        val current = session ?: return
        if (current.permissions.transactions == PermissionLevel.NONE) return
        scope.launch {
            runCatching {
                useCase.execute(current, limit = MONEY_LOOKUP_LIMIT)
                    .catch { /* the statement still renders; only Reverse goes missing */ }
                    .collect { list -> _uiState.update { it.copy(moneyEntries = list) } }
            }
        }
    }

    // ── reversing a money entry, from the statement ──────────────────────────

    fun askReverse(entry: MoneyEntry) {
        if (!entry.canReverse || _uiState.value.reversingEntryId != null) return
        _uiState.update { it.copy(pendingReversal = entry) }
    }

    fun dismissReverse() = _uiState.update { it.copy(pendingReversal = null) }

    fun confirmReverse() {
        val entry = _uiState.value.pendingReversal ?: return
        val useCase = reverseMoneyUseCase ?: return
        val current = session ?: return
        _uiState.update { it.copy(pendingReversal = null, reversingEntryId = entry.entryId, error = null) }
        scope.launch {
            runCatching { useCase.execute(current, entry) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Couldn't reverse that entry") } }
            _uiState.update { it.copy(reversingEntryId = null) }
            // The ledger has moved; re-read both the balance and the statement rather than guessing.
            refreshBalances()
            _uiState.value.statementEntityId?.let { loadStatement(it) }
        }
    }

    private fun observeEntities() {
        val useCase = observeUseCase ?: return
        val current = session ?: return
        scope.launch {
            runCatching {
                useCase.execute(current)
                    .catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load parties") } }
                    .collect { list ->
                        entities = list
                        recompute()
                    }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Re-reads HL if the numbers are old enough to be worth it — what the screen calls when you
     * navigate back to it. Throttled so flipping between screens doesn't hammer the ledger;
     * [refreshBalances] ignores the throttle, because a manual tap should always do something.
     */
    fun refreshBalancesIfStale() {
        val last = _uiState.value.balancesRefreshedAt
        if (last != null && System.currentTimeMillis() - last < AUTO_REFRESH_MIN_INTERVAL_MS) return
        refreshBalances()
    }

    fun refreshBalances() {
        val useCase = balancesUseCase ?: return
        val current = session ?: return
        _uiState.update { it.copy(isLoadingBalances = true) }
        scope.launch {
            runCatching { useCase.execute(current) }
                .onSuccess {
                    balances = it
                    _uiState.update { s -> s.copy(balancesRefreshedAt = System.currentTimeMillis()) }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load balances") } }
            _uiState.update { it.copy(isLoadingBalances = false) }
            recompute()
        }
    }

    /**
     * Loads a party's ledger statement (ticket #91) from HL. Every row and its running balance are
     * HL's — nothing is accumulated here, because a client-side total is wrong across pages and
     * wrong for any date range, and once it disagrees with the ledger nobody can say which is right.
     */
    fun loadStatement(entityId: String, from: String? = null, to: String? = null, page: Int = 1) {
        val useCase = statementUseCase ?: return
        val current = session ?: return
        _uiState.update {
            it.copy(
                isLoadingStatement = true,
                statementEntityId = entityId,
                statementError = null,
                // Drop a previous party's rows immediately so the panel can't briefly show
                // someone else's history under this party's name.
                statement = if (it.statementEntityId == entityId) it.statement else null,
            )
        }
        scope.launch {
            val f = from ?: _uiState.value.statementFrom?.let(::isoDay)
            val t = to ?: _uiState.value.statementTo?.let(::isoDay)
            runCatching { useCase.execute(current, entityId, f, t, page) }
                .onSuccess { result ->
                    _uiState.update {
                        if (it.statementEntityId != entityId) return@update it // selection moved on
                        // Page 2+ APPENDS. Replacing would make "Load more" destroy the rows the
                        // reader already has, with no way back — the button says more, not next.
                        val merged = if (page > 1 && it.statement != null && result != null) {
                            result.copy(rows = it.statement.rows + result.rows)
                        } else {
                            result
                        }
                        it.copy(statement = merged, isLoadingStatement = false)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        if (it.statementEntityId != entityId) it
                        else it.copy(isLoadingStatement = false, statementError = e.message ?: "Couldn't load the statement")
                    }
                }
        }
    }

    // ── statement controls ───────────────────────────────────────────────────

    fun setStatementSearch(q: String) = _uiState.update { it.copy(statementSearch = q) }

    /**
     * The range goes to **HL**, not a client-side filter: the statement is paged, so filtering
     * loaded rows would search only what happens to have been paged in.
     */
    fun setStatementDateRange(from: Long?, to: Long?) {
        _uiState.update { it.copy(statementFrom = from, statementTo = to) }
        _uiState.value.statementEntityId?.let { loadStatement(it) }
    }

    fun toggleStatementSort() = _uiState.update { it.copy(statementAscending = !it.statementAscending) }

    fun clearStatementFilters() {
        _uiState.update { it.copy(statementSearch = "", statementFrom = null, statementTo = null) }
        _uiState.value.statementEntityId?.let { loadStatement(it) }
    }

    fun clearStatement() = _uiState.update {
        it.copy(statement = null, statementEntityId = null, statementError = null, isLoadingStatement = false)
    }

    // ── Print statement (ticket #109) ──────────────────────────────────────────

    /** Opens the print dialog, defaulting the range to the on-screen one (else last 3 months). */
    fun openPrintDialog() {
        val s = _uiState.value
        val today = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                printDialogOpen = true,
                printFrom = s.statementFrom ?: (today - DEFAULT_RANGE_DAYS * MILLIS_PER_DAY),
                printTo = s.statementTo ?: today,
                printIncludeNotes = false,
                printGenerating = false,
                printError = null,
            )
        }
    }

    fun closePrintDialog() = _uiState.update { it.copy(printDialogOpen = false, printError = null) }
    fun setPrintRange(from: Long?, to: Long?) = _uiState.update { it.copy(printFrom = from, printTo = to, printError = null) }
    fun togglePrintNotes(value: Boolean) = _uiState.update { it.copy(printIncludeNotes = value) }
    fun closePdf() = _uiState.update { it.copy(printPdfUrl = null) }

    /**
     * Builds the statement document (shared use case: paging, opening balance, FIFO aging) for the
     * selected party and renders it to a PDF via the `renderStatement` callable. Money-entry notes
     * are resolved from the entries this VM already holds; a blank range start means all-time.
     */
    fun generateStatementPdf() {
        val useCase = buildStatementUseCase ?: return
        val repo = statementPdfRepo ?: return
        val current = session ?: return
        val s = _uiState.value
        val entityId = s.statementEntityId ?: return
        val entity = s.rows.firstOrNull { it.entity.id == entityId }?.entity ?: return
        val fromIso = s.printFrom?.let(::isoDay)
        val toIso = isoDay(s.printTo ?: System.currentTimeMillis())
        val notes = s.moneyByTransaction.mapNotNull { (txn, e) -> e.note?.let { txn to it } }.toMap()
        scope.launch {
            _uiState.update { it.copy(printGenerating = true, printError = null) }
            runCatching {
                val document = useCase.execute(
                    entity = entity,
                    permissions = current.permissions,
                    fromIso = fromIso,
                    toIso = toIso,
                    includeNotes = s.printIncludeNotes,
                    noteByTransactionId = notes,
                )
                repo.render(entityId, document)
            }.onSuccess { url ->
                _uiState.update { it.copy(printGenerating = false, printDialogOpen = false, printPdfUrl = url) }
            }.onFailure { e ->
                val msg = (e as? StatementTooLargeException)?.message
                    ?: e.message ?: "Couldn't generate the statement."
                _uiState.update { it.copy(printGenerating = false, printError = msg) }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        recompute()
    }

    fun onFilterChange(filter: EntitiesFilter) {
        _uiState.update { it.copy(filter = filter) }
        recompute()
    }

    fun archive(entity: Entity) {
        val useCase = archiveUseCase ?: return
        val current = session ?: return
        scope.launch {
            runCatching { useCase.execute(current, entity) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Archive failed") } }
        }
    }

    fun save(input: EntityInput, existingId: String?, onDone: (Boolean) -> Unit) {
        val useCase = saveUseCase ?: return onDone(false)
        val current = session ?: return onDone(false)
        _uiState.update { it.copy(saving = true, saveError = null) }
        scope.launch {
            val result = runCatching { useCase.execute(current, input, existingId) }
            _uiState.update { it.copy(saving = false, saveError = result.exceptionOrNull()?.message) }
            refreshBalances()
            onDone(result.isSuccess)
        }
    }

    private fun recompute() {
        val state = _uiState.value
        val rows = filterAndSortEntities(
            entities = entities,
            balances = balances,
            query = state.query,
            filter = state.filter,
            balanceFilter = state.balanceFilter,
            sortBy = state.sortBy,
        )
        _uiState.update { it.copy(rows = rows) }
    }

    fun onBalanceFilterChange(value: BalanceFilter) {
        // Tapping the active one clears it — the summary tiles double as toggles.
        _uiState.update {
            it.copy(balanceFilter = if (it.balanceFilter == value) BalanceFilter.ALL else value)
        }
        recompute()
    }

    fun onSortChange(value: EntitySort) {
        _uiState.update { it.copy(sortBy = value) }
        recompute()
    }

    /**
     * Carry-over #1: release the resources this VM owns.
     * scope.cancel() ends the observe collection → the flow's awaitClose removes the
     * Firestore listener; then close the HL Ktor client and the Admin-SDK Firestore
     * client (its gRPC channel).
     */
    fun dispose() {
        scope.cancel()
        ledgerRepo?.close()
        entityRepo?.close()
    }
}

/**
 * Narrow and order the party list — the collections view (#86's useful half).
 *
 * Pure so it can be tested without a Firestore or a ledger behind it, and so the ordering the table
 * shows can't drift from the ordering anyone reasons about.
 *
 * Role, text and balance filters are independent: "customers who owe me" is a question people
 * actually ask, so cramming balance into the role chips would make it unanswerable.
 */
internal fun filterAndSortEntities(
    entities: List<Entity>,
    balances: Map<String, EntityBalance>,
    query: String,
    filter: EntitiesFilter,
    balanceFilter: BalanceFilter,
    sortBy: EntitySort,
): List<EntityRow> {
    val q = query.trim()
    return entities
        .asSequence()
        .filter { entity ->
            when (filter) {
                EntitiesFilter.ALL -> true
                EntitiesFilter.CUSTOMER -> EntityRole.CUSTOMER in entity.roles
                EntitiesFilter.SUPPLIER -> EntityRole.SUPPLIER in entity.roles
                EntitiesFilter.MIDDLEMAN -> EntityRole.MIDDLEMAN in entity.roles
            }
        }
        .filter { entity ->
            q.isBlank() ||
                entity.name.contains(q, ignoreCase = true) ||
                entity.phones.any { it.contains(q, ignoreCase = true) }
        }
        .map { EntityRow(it, balances[it.id]) }
        .filter { row ->
            when (balanceFilter) {
                BalanceFilter.ALL -> true
                // A settled party owes nothing and is owed nothing, so it belongs in neither list —
                // the whole point is to shorten this to people worth acting on.
                BalanceFilter.OWES_ME -> row.balance?.direction == BalanceDirection.RECEIVABLE
                BalanceFilter.I_OWE -> row.balance?.direction == BalanceDirection.CREDIT
            }
        }
        .toList()
        .let { list ->
            when (sortBy) {
                EntitySort.NAME -> list.sortedBy { it.entity.name.lowercase() }
                // Largest amount first, whichever direction. A party whose balance hasn't loaded
                // sorts last rather than as zero — unknown is not the same as settled.
                EntitySort.BALANCE -> list.sortedWith(
                    compareByDescending<EntityRow> { it.balance != null }
                        .thenByDescending { row ->
                            row.balance?.let { BigDecimal(Money.abs(it.net)) } ?: BigDecimal.ZERO
                        }
                        .thenBy { it.entity.name.lowercase() },
                )
            }
        }
}

/** `yyyy-MM-dd` for HL's inclusive statement bounds, in the machine's zone (the shop's in practice). */
private fun isoDay(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(millis))
