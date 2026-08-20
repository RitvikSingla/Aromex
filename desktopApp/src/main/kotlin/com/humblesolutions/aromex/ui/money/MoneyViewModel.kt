package com.humblesolutions.aromex.ui.money

import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.BackendErrors
import com.humblesolutions.aromex.data.BackendMoneyEntryRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.data.HL_API_BASE_URL
import com.humblesolutions.aromex.data.HlTokenRepository
import com.humblesolutions.aromex.data.KtorEntityLedgerRepository
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.MoneyAccountRef
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.model.MoneyEntryInput
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityLedgerRepository
import com.humblesolutions.aromex.repository.MoneyEntryRepository
import com.humblesolutions.aromex.usecase.GetEntityBalancesUseCase
import com.humblesolutions.aromex.usecase.MoneyEntryError
import com.humblesolutions.aromex.usecase.ObserveEntitiesUseCase
import com.humblesolutions.aromex.usecase.ObserveMoneyEntriesUseCase
import com.humblesolutions.aromex.usecase.RecordMoneyEntryUseCase
import com.humblesolutions.aromex.usecase.ReverseMoneyEntryUseCase
import com.humblesolutions.aromex.util.Money
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

/**
 * One selectable account in the From/To pickers: a party, or the shop's own Cash/Bank.
 *
 * [balance] is only ever HL's number, and only for parties — nothing here computes one. The legacy
 * screen's stored balances are exactly what this rebuild exists to remove.
 */
data class MoneyAccountOption(
    val ref: MoneyAccountRef,
    val label: String,
    val balance: EntityBalance? = null,
) {
    val isOwnAccount: Boolean get() = ref.isOwnAccount
}

data class MoneyUiState(
    val session: UserSession? = null,
    val canManage: Boolean = false,
    val noAccess: Boolean = false,
    val currency: String = "",
    // ── the form ─────────────────────────────────────────────────────────────
    val accounts: List<MoneyAccountOption> = emptyList(),
    val from: MoneyAccountRef? = null,
    val to: MoneyAccountRef? = null,
    val amount: String = "",
    val note: String = "",
    /** Accounting date; defaults to today, may be backdated. */
    val entryDate: Long = System.currentTimeMillis(),
    val fromQuery: String = "",
    val toQuery: String = "",
    // ── submission ───────────────────────────────────────────────────────────
    val isSaving: Boolean = false,
    val saveError: String? = null,
    /** i18n key for a recognised backend failure; [saveError] is the raw fallback. */
    val saveErrorKey: String? = null,
    val justSaved: Boolean = false,
    // ── the feed ─────────────────────────────────────────────────────────────
    val entries: List<MoneyEntry> = emptyList(),
    val isLoadingBalances: Boolean = false,
    /** When HL balances last came back, so the UI can show how fresh they are. */
    val balancesRefreshedAt: Long? = null,
    val reversingEntryId: String? = null,
    /**
     * True when the signed-in user may move money but may not see parties (`transactions` without
     * `profiles`). Cash ↔ Bank still works; the picker just can't offer people. Surfaced explicitly
     * because an empty party list that silently swallowed a permission error is indistinguishable
     * from a company with no parties yet.
     */
    val partiesUnavailable: Boolean = false,
    val error: String? = null,
    /** i18n key for a recognised backend failure; [error] is the raw fallback. */
    val errorKey: String? = null,
    // ── history controls (search / sort / date range) ────────────────────────
    val searchQuery: String = "",
    val sortBy: MoneySortColumn = MoneySortColumn.DATE,
    /** Newest first by default — the entry you just made is the one you want to see. */
    val sortAscending: Boolean = false,
    val rangeFrom: Long? = null,
    val rangeTo: Long? = null,
    /** The entry waiting on the Reverse confirmation. Reversing moves money; it gets a prompt. */
    val pendingReversal: MoneyEntry? = null,
) {
    /** Null until both sides and a valid amount are present — drives the inline hint. */
    val validationError: MoneyEntryError?
        get() {
            val f = from ?: return null
            val t = to ?: return null
            return RecordMoneyEntryUseCase.validationError(
                MoneyEntryInput(from = f, to = t, amount = amount.ifBlank { "0" }, note = note, entryDate = entryDate),
            )
        }

    val canSave: Boolean
        get() = canManage && from != null && to != null && validationError == null && !isSaving

    fun optionFor(ref: MoneyAccountRef?): MoneyAccountOption? =
        ref?.let { r -> accounts.firstOrNull { it.ref == r } }

    /** The selected party's balance, shown beside the picker so the cashier sees what's owed. */
    val fromBalance: EntityBalance? get() = optionFor(from)?.balance
    val toBalance: EntityBalance? get() = optionFor(to)?.balance

    /** The label the table shows for one side of an entry; falls back for an unresolvable party. */
    fun labelFor(ref: MoneyAccountRef): String = optionFor(ref)?.label
        ?: when (ref) {
            MoneyAccountRef.Cash -> "Cash"
            MoneyAccountRef.Bank -> "Bank"
            is MoneyAccountRef.Party -> "—"
        }

    val isFiltered: Boolean get() = searchQuery.isNotBlank() || rangeFrom != null || rangeTo != null

    /**
     * What the table actually shows: [entries] narrowed by the search text and date range, then
     * ordered. Derived here rather than in the UI so the ordering and the "showing N of M" count
     * can never disagree with each other.
     *
     * Search matches what someone would actually type — a party's name, the note, or the amount.
     */
    val visibleEntries: List<MoneyEntry>
        get() {
            val q = searchQuery.trim()
            val filtered = entries.filter { e ->
                // A reversal and the entry it cancelled are both hidden. Reversing is how someone
                // says "this didn't happen"; showing both halves straight back is an audit trail,
                // and Humble Ledger already keeps one of those.
                if (e.isCancelled) return@filter false
                val inRange = (rangeFrom == null || e.entryDate >= rangeFrom) &&
                    (rangeTo == null || e.entryDate <= rangeTo)
                if (!inRange) return@filter false
                if (q.isEmpty()) return@filter true
                labelFor(e.from).contains(q, true) ||
                    labelFor(e.to).contains(q, true) ||
                    e.note?.contains(q, true) == true ||
                    e.amount.contains(q)
            }
            val ordered = when (sortBy) {
                MoneySortColumn.DATE -> filtered.sortedWith(
                    // createdAt breaks ties so two entries on the same backdated day keep a stable,
                    // meaningful order instead of jittering between recompositions.
                    compareBy<MoneyEntry> { it.entryDate }.thenBy { it.createdAt ?: 0L },
                )
                MoneySortColumn.AMOUNT -> filtered.sortedWith { a, b -> Money.compare(a.amount, b.amount) }
            }
            return if (sortAscending) ordered else ordered.reversed()
        }
}

/** The columns the history can be ordered by (ticket #90). */
enum class MoneySortColumn { DATE, AMOUNT }

/**
 * Desktop money-movement screen (ticket #90) — the rebuilt transactions screen.
 *
 * Plain class + [CoroutineScope] like the other Desktop ViewModels; call [dispose] when the screen
 * leaves composition. Holds **no balances of its own**: party balances are whatever HL last
 * reported, refreshed explicitly, and never adjusted locally after an entry is recorded. If an
 * entry's effect isn't visible yet, the honest answer is to re-read HL, not to guess.
 */
class MoneyViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)

    private var moneyRepo: BackendMoneyEntryRepository? = null
    private var entityRepo: BackendEntityRepository? = null
    private var ledgerRepo: EntityLedgerRepository? = null
    private var recordUseCase: RecordMoneyEntryUseCase? = null
    private var observeEntriesUseCase: ObserveMoneyEntriesUseCase? = null
    private var reverseUseCase: ReverseMoneyEntryUseCase? = null
    private var observeEntitiesUseCase: ObserveEntitiesUseCase? = null
    private var balancesUseCase: GetEntityBalancesUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private var parties: List<Entity> = emptyList()
    private var balances: Map<String, EntityBalance> = emptyMap()

    private val _uiState = MutableStateFlow(MoneyUiState())
    val uiState: StateFlow<MoneyUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        val broker = FirestoreTokenBroker(authRepo, config)
        val money = BackendMoneyEntryRepository(broker, config, session.uid)
        val entities = BackendEntityRepository(broker, config, session.uid)
        val tokens = HlTokenRepository(authRepo, config)
        val ledger = KtorEntityLedgerRepository(tokens, HL_API_BASE_URL)

        moneyRepo = money
        entityRepo = entities
        ledgerRepo = ledger
        recordUseCase = RecordMoneyEntryUseCase(money)
        observeEntriesUseCase = ObserveMoneyEntriesUseCase(money)
        reverseUseCase = ReverseMoneyEntryUseCase(money)
        observeEntitiesUseCase = ObserveEntitiesUseCase(entities)
        balancesUseCase = GetEntityBalancesUseCase(ledger)

        val level = session.permissions.transactions
        if (level == PermissionLevel.NONE) {
            _uiState.update { it.copy(session = session, noAccess = true) }
            return
        }
        _uiState.update {
            it.copy(
                session = session,
                canManage = level == PermissionLevel.MANAGE,
                noAccess = false,
                currency = session.currency,
            )
        }
        observeParties()
        observeEntries()
        refreshBalances()
    }

    /** Test seam: inject fakes without touching Firestore or HL. */
    internal fun bindForTest(
        session: UserSession,
        moneyRepository: MoneyEntryRepository,
        entityRepository: com.humblesolutions.aromex.repository.EntityRepository,
        ledgerRepository: EntityLedgerRepository? = null,
    ) {
        this.session = session
        recordUseCase = RecordMoneyEntryUseCase(moneyRepository)
        observeEntriesUseCase = ObserveMoneyEntriesUseCase(moneyRepository)
        reverseUseCase = ReverseMoneyEntryUseCase(moneyRepository)
        observeEntitiesUseCase = ObserveEntitiesUseCase(entityRepository)
        ledgerRepo = ledgerRepository
        balancesUseCase = ledgerRepository?.let { GetEntityBalancesUseCase(it) }

        val level = session.permissions.transactions
        if (level == PermissionLevel.NONE) {
            _uiState.update { it.copy(session = session, noAccess = true) }
            return
        }
        _uiState.update {
            it.copy(
                session = session,
                canManage = level == PermissionLevel.MANAGE,
                noAccess = false,
                currency = session.currency,
            )
        }
        observeParties()
        observeEntries()
        if (balancesUseCase != null) refreshBalances()
    }

    private fun observeParties() {
        val useCase = observeEntitiesUseCase ?: return
        val current = session ?: return
        if (current.permissions.profiles == PermissionLevel.NONE) {
            // Say so rather than showing an empty picker: Cash ↔ Bank remains usable.
            _uiState.update { it.copy(partiesUnavailable = true) }
            rebuildAccounts()
            return
        }
        scope.launch {
            runCatching {
                useCase.execute(current, includeArchived = false)
                    .catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load parties") } }
                    .collect { list ->
                        parties = list
                        rebuildAccounts()
                    }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    private fun observeEntries() {
        val useCase = observeEntriesUseCase ?: return
        val current = session ?: return
        scope.launch {
            runCatching {
                useCase.execute(current)
                    .catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load entries") } }
                    .collect { list -> _uiState.update { it.copy(entries = list) } }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    /**
     * Re-reads HL if the numbers are old enough to be worth re-fetching — what the screen calls when
     * you navigate back to it. Throttled so bouncing between screens doesn't hammer the ledger;
     * [refreshBalances] ignores the throttle, because a manual tap should always actually do something.
     */
    fun refreshBalancesIfStale() {
        val last = _uiState.value.balancesRefreshedAt
        if (last != null && System.currentTimeMillis() - last < AUTO_REFRESH_MIN_INTERVAL_MS) return
        refreshBalances()
    }

    /** Re-reads HL. The only way a balance in this screen ever changes. */
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
                .onFailure { e ->
                    // Leave the previously-known balances rather than zeroing them: a stale number
                    // labelled stale is useful, a fabricated zero is dangerous.
                    _uiState.update { it.copy(error = e.message ?: "Couldn't reach the ledger") }
                }
            _uiState.update { it.copy(isLoadingBalances = false) }
            rebuildAccounts()
        }
    }

    private fun rebuildAccounts() {
        val options = buildList {
            add(MoneyAccountOption(MoneyAccountRef.Cash, CASH_LABEL))
            add(MoneyAccountOption(MoneyAccountRef.Bank, BANK_LABEL))
            parties.sortedBy { it.name.lowercase() }.forEach { party ->
                add(
                    MoneyAccountOption(
                        ref = MoneyAccountRef.Party(party.id),
                        label = party.name,
                        balance = balances[party.id],
                    ),
                )
            }
        }
        _uiState.update { it.copy(accounts = options) }
    }

    // ── form edits ───────────────────────────────────────────────────────────

    fun setFrom(ref: MoneyAccountRef?) = _uiState.update { it.copy(from = ref, saveError = null, saveErrorKey = null, justSaved = false) }
    fun setTo(ref: MoneyAccountRef?) = _uiState.update { it.copy(to = ref, saveError = null, saveErrorKey = null, justSaved = false) }
    fun setNote(note: String) = _uiState.update { it.copy(note = note, justSaved = false) }
    fun setEntryDate(millis: Long) = _uiState.update { it.copy(entryDate = millis, justSaved = false) }
    fun setFromQuery(q: String) = _uiState.update { it.copy(fromQuery = q) }
    fun setToQuery(q: String) = _uiState.update { it.copy(toQuery = q) }

    /** Digits and a single decimal point only — the same filtering the checkout money fields use. */
    fun setAmount(raw: String) {
        val filtered = buildString {
            var seenDot = false
            raw.forEach { c ->
                when {
                    c.isDigit() -> append(c)
                    (c == '.' || c == ',') && !seenDot -> { seenDot = true; append('.') }
                    else -> Unit
                }
            }
        }
        _uiState.update { it.copy(amount = filtered, saveError = null, saveErrorKey = null, justSaved = false) }
    }

    /** Swap the two sides — the common correction after picking them the wrong way round. */
    fun swapDirection() = _uiState.update { it.copy(from = it.to, to = it.from, saveError = null, saveErrorKey = null, justSaved = false) }

    // ── submission ───────────────────────────────────────────────────────────

    fun record() {
        val state = _uiState.value
        val useCase = recordUseCase ?: return
        val current = session ?: return
        val from = state.from ?: return
        val to = state.to ?: return
        if (!state.canSave) return

        _uiState.update { it.copy(isSaving = true, saveError = null, saveErrorKey = null, justSaved = false) }
        scope.launch {
            runCatching {
                useCase.execute(
                    current,
                    MoneyEntryInput(
                        from = from,
                        to = to,
                        amount = state.amount,
                        note = state.note.trim().takeIf { it.isNotEmpty() },
                        entryDate = state.entryDate,
                    ),
                )
            }.onSuccess {
                // Clear the amount and note for the next entry but keep From/To — recording several
                // movements against the same party in a row is the common case at a counter.
                _uiState.update {
                    it.copy(
                        isSaving = false, amount = "", note = "",
                        justSaved = true, saveError = null, saveErrorKey = null,
                    )
                }
                refreshBalances()
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isSaving = false, saveErrorKey = backendKey(e), saveError = rawFallback(e))
                }
            }
        }
    }

    // ── history controls ─────────────────────────────────────────────────────

    fun setSearch(query: String) = _uiState.update { it.copy(searchQuery = query) }

    /** Clicking a sortable header re-sorts by it; clicking the active one flips the direction. */
    fun toggleSort(column: MoneySortColumn) = _uiState.update {
        if (it.sortBy == column) it.copy(sortAscending = !it.sortAscending)
        // A new column starts descending: biggest amount / newest date first is what's wanted.
        else it.copy(sortBy = column, sortAscending = false)
    }

    fun setDateRange(from: Long?, to: Long?) = _uiState.update { it.copy(rangeFrom = from, rangeTo = to) }

    fun clearFilters() = _uiState.update { it.copy(searchQuery = "", rangeFrom = null, rangeTo = null) }

    // ── reversal (confirmed) ─────────────────────────────────────────────────

    /** Opens the confirmation. Reversing moves real money, so it never fires straight off a click. */
    fun askReverse(entry: MoneyEntry) {
        if (!entry.canReverse || _uiState.value.reversingEntryId != null) return
        _uiState.update { it.copy(pendingReversal = entry) }
    }

    fun dismissReverse() = _uiState.update { it.copy(pendingReversal = null) }

    fun confirmReverse() {
        val entry = _uiState.value.pendingReversal ?: return
        _uiState.update { it.copy(pendingReversal = null) }
        reverse(entry)
    }

    internal fun reverse(entry: MoneyEntry) {
        val useCase = reverseUseCase ?: return
        val current = session ?: return
        if (!entry.canReverse || _uiState.value.reversingEntryId != null) return

        _uiState.update { it.copy(reversingEntryId = entry.entryId, error = null) }
        scope.launch {
            runCatching { useCase.execute(current, entry) }
                .onFailure { e ->
                    _uiState.update { it.copy(errorKey = backendKey(e), error = rawFallback(e)) }
                }
            _uiState.update { it.copy(reversingEntryId = null) }
            refreshBalances()
        }
    }

    fun clearError() = _uiState.update {
        it.copy(error = null, errorKey = null, saveError = null, saveErrorKey = null)
    }

    /**
     * A recognised backend failure becomes a sentence a cashier can act on. Anything unrecognised
     * keeps its own message — the failure we didn't anticipate is exactly the one worth reading
     * verbatim rather than flattening into "something went wrong".
     */
    private fun backendKey(t: Throwable): String? =
        BackendErrors.messageKeyOrNull(BackendErrors.unwrap(t))

    private fun rawFallback(t: Throwable): String? =
        if (backendKey(t) != null) null else t.message ?: "Couldn't complete that"

    fun dispose() {
        ledgerRepo?.close()
        moneyRepo?.close()
        entityRepo?.close()
        scope.cancel()
    }

    private companion object {
        // Not i18n keys: these name the shop's own ledger accounts, which HL knows as
        // "Cash" and "Bank" — the picker shows what the account is actually called.
        const val CASH_LABEL = "Cash"
        const val BANK_LABEL = "Bank"

        /** Below this age, re-entering the screen reuses what's already loaded. */
        const val AUTO_REFRESH_MIN_INTERVAL_MS = 20_000L
    }
}
