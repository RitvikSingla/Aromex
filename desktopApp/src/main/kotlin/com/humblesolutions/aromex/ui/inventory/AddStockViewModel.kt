package com.humblesolutions.aromex.ui.inventory

import com.humblesolutions.aromex.data.BackendAttributeRepository
import com.humblesolutions.aromex.data.BackendCommissionRuleRepository
import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.BackendInventoryRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.CommissionInput
import com.humblesolutions.aromex.model.CommissionLine
import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.DuplicateImeiException
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.NewProduct
import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.StockBatchGroup
import com.humblesolutions.aromex.model.UNSPECIFIED_SUPPLIER_ID
import com.humblesolutions.aromex.model.UNSPECIFIED_SUPPLIER_NAME
import com.humblesolutions.aromex.model.UnreadableBlock
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.usecase.AddAttributeUseCase
import com.humblesolutions.aromex.usecase.AddSupplierInlineUseCase
import com.humblesolutions.aromex.usecase.CheckImeiAvailabilityUseCase
import com.humblesolutions.aromex.usecase.CommissionCalculator
import com.humblesolutions.aromex.usecase.ObserveActiveCommissionRulesUseCase
import com.humblesolutions.aromex.usecase.ObservePartiesForPurchaseUseCase
import com.humblesolutions.aromex.usecase.RecordInventoryPurchaseUseCase
import com.humblesolutions.aromex.usecase.ResolveParsedPhonesUseCase
import com.humblesolutions.aromex.util.Imei
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.SkuKey
import com.humblesolutions.aromex.util.parseSickw
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

enum class ImeiCheckState { IDLE, CHECKING, AVAILABLE, ALREADY_IN_BATCH, ALREADY_IN_STOCK, INVALID }

enum class AddInventoryRoute { ENTRY, PASTE, REVIEW }

/**
 * Per-row IMEI advisory flag on the review table (ticket #53). Duplicated-within-the-paste
 * is computed synchronously; already-in-stock is an async advisory check. The confirm-time
 * transaction remains the real guard.
 */
enum class RowImeiFlag { NONE, DUP_IN_BATCH, IN_STOCK }

/**
 * A review row is confirmable once every **required** shop field is present (ticket #101):
 * a **positive cost** (booked as the inventory asset → COGS on sale, so it can never be blank
 * or zero), a location, and **brand + model** (the SKU identity). Capacity/colour/carrier and
 * selling price are optional — the latter set later at sale time — but a selling price that *is*
 * typed must still be a positive decimal (the use case would otherwise throw).
 */
fun ReviewUnit.isComplete(): Boolean =
    com.humblesolutions.aromex.util.Money.isValidPositiveDecimal(cost.trim()) &&
        (sellingPrice.isBlank() ||
            com.humblesolutions.aromex.util.Money.isValidPositiveDecimal(sellingPrice.trim())) &&
        location.attributeId.isNotBlank() &&
        attributes[AttributeType.BRAND]?.attributeId?.isNotBlank() == true &&
        attributes[AttributeType.MODEL]?.attributeId?.isNotBlank() == true

/** A staged unit — carries its own full SKU snapshot so units in the same session can belong to different products. */
data class ReviewUnit(
    val imei: String,
    val cost: String,
    val condition: Condition,
    val location: AttributeRef,
    val attributes: Map<AttributeType, AttributeRef> = emptyMap(),
    val sellingPrice: String = "",
)

/**
 * The user's decision on one proposed [CommissionLine] in the intake dialog (ticket #97).
 * The rule proposes; the person saving decides — each line is [included] (or skipped), its
 * [amount] editable ([overridden] when it no longer equals the rule's figure), and either added
 * to the payee's balance or **given now** ([giveNow]) as a [cash] + [bank] split (mirroring the
 * inventory-supplier UI). Keyed by the line's `ruleId` in state.
 */
data class CommissionDecision(
    val included: Boolean = true,
    val giveNow: Boolean = false,
    val cash: String = "",
    val bank: String = "",
    val amount: String = "",
    val overridden: Boolean = false,
) {
    /** Sum given now (cash + bank), as a decimal string. */
    val givenNow: String get() = Money.add(cash.trim().ifEmpty { "0" }, bank.trim().ifEmpty { "0" })

    /** What stays on the payee's balance after giving now: owed − given (≥ 0 when not exceeding). */
    val leftOnBalance: String get() = Money.subtract(amount.trim().ifEmpty { "0" }, givenNow)

    /** True when the give-now split exceeds the amount owed — blocks confirm. */
    val giveExceedsAmount: Boolean
        get() = giveNow && !Money.lessThanOrEqual(givenNow, amount.trim().ifEmpty { "0" })
}

data class AddStockUiState(
    val attributes: List<AttributeValue> = emptyList(),
    // Picked SKU-defining attributes (brand/model/capacity/color/carrier)
    val picked: Map<AttributeType, AttributeRef> = emptyMap(),
    val defaultSellingPrice: String = "",
    // Batch-shared fields (apply to every unit in this batch)
    val batchCost: String = "",
    val batchCondition: Condition = Condition.NEW,
    val batchLocation: AttributeRef? = null,
    // IMEI entry
    val pendingImei: String = "",
    val imeiCheckState: ImeiCheckState = ImeiCheckState.IDLE,
    // Staged IMEI list (Screen 1)
    val stagedImeis: List<String> = emptyList(),
    // Two-screen flow
    val route: AddInventoryRoute = AddInventoryRoute.ENTRY,
    // Review units with per-unit overrides (Screen 2)
    val reviewUnits: List<ReviewUnit> = emptyList(),
    // Pre-selected existing SKU (add-units path)
    val existingProductId: String? = null,
    val attributesLocked: Boolean = false,
    // ── SICKW paste (Screen: PASTE) ──────────────────────────────────────────
    val pasteText: String = "",
    val parsing: Boolean = false,
    // Summary banner shown on the review screen after a parse (null = no paste ran).
    val parseSummary: String? = null,
    // Blocks the parser could not read (never silently dropped); user drops/dismisses.
    val unreadable: List<UnreadableBlock> = emptyList(),
    // Ids of vocab values minted during resolution → cells tagged "new".
    val newlyCreatedIds: Set<String> = emptySet(),
    // Async advisory in-stock results, keyed by IMEI (in-batch dups are derived, not stored).
    val imeiInStock: Set<String> = emptySet(),
    // ── Purchase dialog (#58) ──────────────────────────────────────────────────
    val showPurchaseDialog: Boolean = false,
    val currency: String = "",
    // All company entities (for the bought-from dropdown); suppliers sorted first.
    val entities: List<Entity> = emptyList(),
    val purchaseParty: AttributeRef? = null,
    /**
     * The business date of this batch. Today unless the user picks otherwise — the case that
     * matters is entering old books, where the stock has to land on the day it was bought.
     */
    val purchaseDate: Long = System.currentTimeMillis(),
    val purchaseCash: String = "",
    val purchaseBank: String = "",
    // ── Commission on intake (#97) ─────────────────────────────────────────────
    // Active rules streamed for the logged-in user (empty if none / no access).
    val activeCommissionRules: List<CommissionRule> = emptyList(),
    // Lines proposed for the current batch, computed when the purchase dialog opens.
    val commissionLines: List<CommissionLine> = emptyList(),
    // Per-line user decision, keyed by the line's ruleId.
    val commissionDecisions: Map<String, CommissionDecision> = emptyMap(),
    // Async
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

/**
 * Keeps only digits and at most one decimal point — the hardware-keyboard equivalent of the
 * numeric-only money input the ticket calls for (a physical keyboard can otherwise type letters
 * into a Decimal field). Money stays a decimal string; there is never a float.
 */
internal fun sanitizeDecimalInput(raw: String): String {
    val kept = raw.filter { it.isDigit() || it == '.' }
    val firstDot = kept.indexOf('.')
    if (firstDot == -1) return kept
    return kept.substring(0, firstDot + 1) + kept.substring(firstDot + 1).replace(".", "")
}

/** The batch's real total cost = Σ each reviewed unit's cost (decimal-string sum). */
val AddStockUiState.batchTotalCost: String get() = Money.sum(reviewUnits.map { it.cost })

/** True when cash + bank exceeds the batch total — blocks confirm with an inline error. */
val AddStockUiState.purchasePaidExceedsTotal: Boolean
    get() = !Money.lessThanOrEqual(
        Money.add(purchaseCash.trim().ifEmpty { "0" }, purchaseBank.trim().ifEmpty { "0" }),
        batchTotalCost,
    )

/** True when any included commission's give-now split exceeds what's owed — blocks confirm (#97). */
val AddStockUiState.commissionGiveExceeds: Boolean
    get() = commissionLines.any { line ->
        commissionDecisions[line.ruleId]?.let { it.included && it.giveExceedsAmount } == true
    }

/**
 * Desktop add-stock form: attribute pickers (add-new-inline, model filtered by brand),
 * batch-shared cost / condition / location, IMEI ✓ validation with advisory pre-check,
 * two-screen route (Entry → Review → save). Plain class + [CoroutineScope]; call [dispose]
 * on teardown.
 */
class AddStockViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)

    private var invRepo: BackendInventoryRepository? = null
    private var attrRepo: BackendAttributeRepository? = null
    private var entityRepo: BackendEntityRepository? = null
    private var commissionRuleRepo: BackendCommissionRuleRepository? = null
    private var observeActiveCommissionRulesUseCase: ObserveActiveCommissionRulesUseCase? = null
    private var addAttributeUseCase: AddAttributeUseCase? = null
    private var checkAvailabilityUseCase: CheckImeiAvailabilityUseCase? = null
    private var resolveParsedPhonesUseCase: ResolveParsedPhonesUseCase? = null
    private var recordInventoryPurchaseUseCase: RecordInventoryPurchaseUseCase? = null
    private var observePartiesUseCase: ObservePartiesForPurchaseUseCase? = null
    private var addSupplierInlineUseCase: AddSupplierInlineUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private val _uiState = MutableStateFlow(AddStockUiState())
    val uiState: StateFlow<AddStockUiState> = _uiState.asStateFlow()

    val hasUnsavedChanges: Boolean
        get() = _uiState.value.let {
            it.pendingImei.isNotBlank() || it.stagedImeis.isNotEmpty() ||
                it.reviewUnits.isNotEmpty() || it.picked.isNotEmpty() ||
                it.batchCost.isNotBlank() || it.batchLocation != null ||
                it.pasteText.isNotBlank() || it.unreadable.isNotEmpty()
        }

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config
        _uiState.update { it.copy(currency = session.currency) }

        val broker = FirestoreTokenBroker(authRepo, config)
        val inv = BackendInventoryRepository(broker, config, session.uid)
        val attrs = BackendAttributeRepository(broker, config, session.uid)
        val entities = BackendEntityRepository(broker, config, session.uid)
        val commissionRules = BackendCommissionRuleRepository(broker, config, session.uid)
        invRepo = inv
        attrRepo = attrs
        entityRepo = entities
        commissionRuleRepo = commissionRules
        addAttributeUseCase = AddAttributeUseCase(attrs)
        checkAvailabilityUseCase = CheckImeiAvailabilityUseCase(inv)
        resolveParsedPhonesUseCase = ResolveParsedPhonesUseCase(attrs)
        recordInventoryPurchaseUseCase = RecordInventoryPurchaseUseCase(inv)
        observePartiesUseCase = ObservePartiesForPurchaseUseCase(entities)
        addSupplierInlineUseCase = AddSupplierInlineUseCase(entities)
        observeActiveCommissionRulesUseCase = ObserveActiveCommissionRulesUseCase(commissionRules)

        scope.launch {
            runCatching {
                attrs.observeAttributes()
                    .catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load attributes") } }
                    .collect { list -> _uiState.update { it.copy(attributes = list) } }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }

        // Live party list for the bought-from dropdown, gated on `inventory` (not `profiles`)
        // so anyone who can add stock sees the supplier list. Falls back to just the
        // Unspecified Supplier default if even inventory access is missing.
        scope.launch {
            runCatching {
                observePartiesUseCase?.execute(session)
                    ?.catch { /* stream error — leave list empty, default still works */ }
                    ?.collect { list -> _uiState.update { it.copy(entities = list) } }
            }
        }

        // Active commission rules (ticket #97) — gated on inventory access so the cashier who
        // adds stock sees each proposed commission at intake. No access / no rules → empty list,
        // and the commission section simply never appears.
        scope.launch {
            runCatching {
                observeActiveCommissionRulesUseCase?.execute(session)
                    ?.catch { /* stream error — leave rules empty; the section just won't show */ }
                    ?.collect { list -> _uiState.update { it.copy(activeCommissionRules = list) } }
            }
        }
    }

    // ── Attribute pickers ────────────────────────────────────────────────────────

    /** Vocabulary values of [type]; models are filtered by the picked brand. */
    fun options(type: AttributeType): List<AttributeValue> {
        val all = _uiState.value.attributes.filter { it.type == type && it.isActive }
        if (type != AttributeType.MODEL) return all
        val brandId = _uiState.value.picked[AttributeType.BRAND]?.attributeId ?: return emptyList()
        return all.filter { it.parentId == brandId }
    }

    /**
     * Vocabulary values of [type] for an inline review-table cell, scoped to **that row's**
     * brand (not the shared picker's) so a row's MODEL dropdown lists the right models.
     */
    fun optionsFor(type: AttributeType, brandId: String?): List<AttributeValue> {
        val all = _uiState.value.attributes.filter { it.type == type && it.isActive }
        if (type != AttributeType.MODEL) return all
        if (brandId.isNullOrBlank()) return emptyList()
        return all.filter { it.parentId == brandId }
    }

    fun pick(type: AttributeType, value: AttributeValue) {
        _uiState.update { state ->
            val picked = state.picked.toMutableMap()
            picked[type] = AttributeRef(value.attributeId, value.name)
            if (type == AttributeType.BRAND) picked.remove(AttributeType.MODEL)
            state.copy(picked = picked)
        }
    }

    fun clearPick(type: AttributeType) = _uiState.update { state ->
        val picked = state.picked.toMutableMap()
        picked.remove(type)
        if (type == AttributeType.BRAND) picked.remove(AttributeType.MODEL)
        state.copy(picked = picked)
    }

    fun setPrice(price: String) = _uiState.update { it.copy(defaultSellingPrice = price) }

    // ── Batch shared fields ──────────────────────────────────────────────────────

    fun setBatchCost(cost: String) = _uiState.update { it.copy(batchCost = cost) }

    fun setBatchCondition(condition: Condition) = _uiState.update { it.copy(batchCondition = condition) }

    fun setBatchLocation(value: AttributeValue) = _uiState.update {
        it.copy(batchLocation = AttributeRef(value.attributeId, value.name))
    }

    fun clearBatchLocation() = _uiState.update { it.copy(batchLocation = null) }

    // ── IMEI entry ───────────────────────────────────────────────────────────────

    fun onPendingImeiChange(imei: String) = _uiState.update {
        it.copy(pendingImei = imei, imeiCheckState = ImeiCheckState.IDLE)
    }

    /**
     * ✓ button action: validates format → in-batch dedup → advisory in-stock pre-check →
     * on success adds to [AddStockUiState.stagedImeis] and clears the field; on failure
     * sets [AddStockUiState.imeiCheckState] for the inline error.
     */
    fun checkAndAddImei() {
        val state = _uiState.value
        val imei = state.pendingImei.trim()

        if (!Imei.isValid(imei)) {
            _uiState.update { it.copy(imeiCheckState = ImeiCheckState.INVALID) }
            return
        }
        if (state.stagedImeis.any { it.equals(imei, ignoreCase = true) }) {
            _uiState.update { it.copy(imeiCheckState = ImeiCheckState.ALREADY_IN_BATCH) }
            return
        }

        val sess = session ?: return
        val useCase = checkAvailabilityUseCase ?: run {
            addImeiDirectly(imei)
            return
        }

        _uiState.update { it.copy(imeiCheckState = ImeiCheckState.CHECKING) }
        scope.launch {
            runCatching { useCase.execute(sess, imei) }
                .onSuccess { inStock ->
                    if (inStock) {
                        _uiState.update { it.copy(imeiCheckState = ImeiCheckState.ALREADY_IN_STOCK) }
                    } else {
                        addImeiDirectly(imei)
                    }
                }
                .onFailure {
                    // Pre-check failed — still allow adding; the transactional guard at
                    // save time will catch real duplicates.
                    addImeiDirectly(imei)
                }
        }
    }

    private fun addImeiDirectly(imei: String) {
        _uiState.update { state ->
            state.copy(
                stagedImeis = state.stagedImeis + imei,
                pendingImei = "",
                imeiCheckState = ImeiCheckState.IDLE,
            )
        }
    }

    fun removeImei(imei: String) = _uiState.update {
        it.copy(stagedImeis = it.stagedImeis.filter { s -> s != imei })
    }

    // ── Two-screen flow ──────────────────────────────────────────────────────────

    /** Build review units from staged IMEIs + batch fields, then navigate to Screen 2. */
    fun proceedToReview() {
        val state = _uiState.value
        val location = state.batchLocation ?: return
        val reviewUnits = state.stagedImeis.map { imei ->
            ReviewUnit(
                imei = imei,
                cost = state.batchCost,
                condition = state.batchCondition,
                location = location,
                attributes = state.picked,
                sellingPrice = state.defaultSellingPrice,
            )
        }
        _uiState.update { it.copy(reviewUnits = reviewUnits, route = AddInventoryRoute.REVIEW) }
    }

    /** Pre-load picked + price from an existing unit's snapshot so the dialog pre-fills correctly. */
    fun setPickedFromUnit(attributes: Map<AttributeType, AttributeRef>, sellingPrice: String) {
        _uiState.update { it.copy(picked = attributes, defaultSellingPrice = sellingPrice) }
    }

    fun addMoreFromReview() = _uiState.update { it.copy(route = AddInventoryRoute.ENTRY) }

    // ── SICKW paste flow ─────────────────────────────────────────────────────────

    /** Open the paste screen (does NOT clear an in-progress batch). */
    fun openPaste() = _uiState.update { it.copy(route = AddInventoryRoute.PASTE) }

    fun onPasteTextChange(text: String) = _uiState.update { it.copy(pasteText = text) }

    fun clearPasteText() = _uiState.update { it.copy(pasteText = "") }

    /**
     * "Parse & add": run the shared pure parser, resolve name strings → managed vocab
     * (find-or-create), build review units carrying their own SKU snapshot, stash the
     * unreadable blocks + newly-created ids + summary, then navigate to REVIEW. Nothing is
     * silently dropped. Never auto-runs — only this explicit action parses.
     */
    fun parseAndAdd() {
        val current = session ?: return
        val text = _uiState.value.pasteText
        if (text.isBlank()) return
        _uiState.update { it.copy(parsing = true, error = null) }
        scope.launch {
            val result = parseSickw(text)
            val resolve = resolveParsedPhonesUseCase
            val known = _uiState.value.attributes
            val resolved = if (resolve != null && result.phones.isNotEmpty()) {
                runCatching { resolve.execute(current, result.phones, known) }.getOrNull()
            } else null

            val newUnits = resolved?.phones.orEmpty().map { phone ->
                ReviewUnit(
                    imei = phone.imei,
                    cost = "",
                    condition = Condition.NEW,
                    location = AttributeRef("", ""),
                    attributes = phone.attributes,
                    sellingPrice = phone.sellingPrice,
                )
            }

            if (resolved == null && result.phones.isNotEmpty()) {
                // Resolution failed (e.g. permission / network) — surface, don't swallow.
                _uiState.update {
                    it.copy(
                        parsing = false,
                        error = "network:Couldn't map the pasted phones to your vocabulary.",
                        unreadable = result.unreadable,
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    parsing = false,
                    pasteText = "",
                    reviewUnits = it.reviewUnits + newUnits,
                    unreadable = it.unreadable + result.unreadable,
                    newlyCreatedIds = it.newlyCreatedIds + resolved?.newlyCreatedIds.orEmpty(),
                    parseSummary = summaryFor(newUnits.size, result.unreadable.size),
                    route = AddInventoryRoute.REVIEW,
                )
            }
            refreshInStockChecks()
        }
    }

    private fun summaryFor(parsed: Int, unreadable: Int): String = when {
        parsed == 0 && unreadable == 0 -> "inventory_parse_none"
        unreadable == 0 -> "inventory_parse_summary_one|$parsed"
        else -> "inventory_parse_summary|$parsed|$unreadable"
    }

    fun dismissParseSummary() = _uiState.update { it.copy(parseSummary = null) }

    fun dismissUnreadable(index: Int) = _uiState.update {
        it.copy(unreadable = it.unreadable.filterIndexed { i, _ -> i != index })
    }

    fun dismissAllUnreadable() = _uiState.update { it.copy(unreadable = emptyList()) }

    // ── Apply-to-all bar (review table) ───────────────────────────────────────────

    /**
     * Fill cost / condition / location on **every** review unit at once (a paste is
     * usually one lot). Only non-null fields are applied; per-row overrides made afterward
     * are preserved because this is a one-shot fill, not a binding.
     */
    fun applyToAllRows(
        cost: String?,
        condition: Condition?,
        location: AttributeRef?,
        sellingPrice: String? = null,
    ) = _uiState.update { state ->
        state.copy(
            reviewUnits = state.reviewUnits.map { u ->
                u.copy(
                    cost = cost ?: u.cost,
                    condition = condition ?: u.condition,
                    location = location ?: u.location,
                    sellingPrice = sellingPrice ?: u.sellingPrice,
                )
            },
        )
    }

    // ── Live IMEI advisory checks for the review table ────────────────────────────

    /** Advisory in-stock check for the current review IMEIs; the confirm-time txn is the real guard. */
    fun refreshInStockChecks() {
        val useCase = checkAvailabilityUseCase ?: return
        val sess = session ?: return
        val imeis = _uiState.value.reviewUnits.map { it.imei }.filter { Imei.isValid(it) }.toSet()
        if (imeis.isEmpty()) {
            _uiState.update { it.copy(imeiInStock = emptySet()) }
            return
        }
        scope.launch {
            val inStock = mutableSetOf<String>()
            for (imei in imeis) {
                runCatching { useCase.execute(sess, imei) }.onSuccess { if (it) inStock += imei }
            }
            _uiState.update { it.copy(imeiInStock = inStock) }
        }
    }

    // ── Review screen mutations ──────────────────────────────────────────────────

    /**
     * Dialog IMEI check: validates format → in-list dedup → advisory in-stock pre-check.
     * [existingImeis] are the IMEIs already in the staging table (to detect in-batch dups).
     * Returns the final [ImeiCheckState]; callers drive the CHECKING spinner themselves.
     * Pre-check failure is treated as AVAILABLE — the transactional guard at save time
     * catches real duplicates.
     */
    suspend fun checkImeiAvailability(imei: String, existingImeis: Set<String>): ImeiCheckState {
        if (!Imei.isValid(imei)) return ImeiCheckState.INVALID
        if (imei in existingImeis) return ImeiCheckState.ALREADY_IN_BATCH
        val useCase = checkAvailabilityUseCase ?: return ImeiCheckState.AVAILABLE
        val sess = session ?: return ImeiCheckState.AVAILABLE
        return runCatching { useCase.execute(sess, imei) }
            .map { inStock -> if (inStock) ImeiCheckState.ALREADY_IN_STOCK else ImeiCheckState.AVAILABLE }
            .getOrElse { ImeiCheckState.AVAILABLE }
    }

    /** Desktop table flow: add a fully-specified unit directly (skips the staged-IMEI batch path). */
    fun addReviewUnit(unit: ReviewUnit) = _uiState.update {
        it.copy(reviewUnits = it.reviewUnits + unit)
    }

    fun editReviewUnit(index: Int, unit: ReviewUnit) {
        _uiState.update {
            val list = it.reviewUnits.toMutableList()
            if (index in list.indices) list[index] = unit
            it.copy(reviewUnits = list)
        }
        refreshInStockChecks()
    }

    /** Inline-cell edit of the empty shop fields on a review row (cost / selling price / condition / location). */
    fun updateReviewCell(
        index: Int,
        cost: String? = null,
        condition: Condition? = null,
        location: AttributeRef? = null,
        sellingPrice: String? = null,
    ) = _uiState.update {
        val list = it.reviewUnits.toMutableList()
        if (index in list.indices) {
            val u = list[index]
            list[index] = u.copy(
                cost = cost ?: u.cost,
                condition = condition ?: u.condition,
                location = location ?: u.location,
                sellingPrice = sellingPrice ?: u.sellingPrice,
            )
        }
        it.copy(reviewUnits = list)
    }

    /**
     * Inline edit of a SKU-defining attribute (brand/model/capacity/color/carrier) on a
     * review row. Changing BRAND clears that row's MODEL (models are brand-scoped), so a
     * stale model can't survive a brand change.
     */
    fun updateReviewAttribute(index: Int, type: AttributeType, ref: AttributeRef) = _uiState.update {
        val list = it.reviewUnits.toMutableList()
        if (index in list.indices) {
            val u = list[index]
            val attrs = u.attributes.toMutableMap()
            attrs[type] = ref
            if (type == AttributeType.BRAND) attrs.remove(AttributeType.MODEL)
            list[index] = u.copy(attributes = attrs)
        }
        it.copy(reviewUnits = list)
    }

    /** Clear a SKU-defining attribute on a review row (e.g. the cell's × button). */
    fun clearReviewAttribute(index: Int, type: AttributeType) = _uiState.update {
        val list = it.reviewUnits.toMutableList()
        if (index in list.indices) {
            val u = list[index]
            val attrs = u.attributes.toMutableMap()
            attrs.remove(type)
            if (type == AttributeType.BRAND) attrs.remove(AttributeType.MODEL)
            list[index] = u.copy(attributes = attrs)
        }
        it.copy(reviewUnits = list)
    }

    fun removeReviewUnit(index: Int) {
        _uiState.update {
            it.copy(reviewUnits = it.reviewUnits.filterIndexed { i, _ -> i != index })
        }
        refreshInStockChecks()
    }

    // ── Add-to-existing-SKU entry ────────────────────────────────────────────────

    fun startAddUnits(productId: String, attributes: Map<AttributeType, AttributeRef>, sellingPrice: String = "") {
        _uiState.update {
            AddStockUiState(
                attributes = it.attributes,
                entities = it.entities,
                currency = it.currency,
                picked = attributes,
                existingProductId = productId,
                attributesLocked = true,
                defaultSellingPrice = sellingPrice,
            )
        }
    }

    // ── Add-new-inline ───────────────────────────────────────────────────────────

    fun addNewAttribute(type: AttributeType, name: String) {
        val useCase = addAttributeUseCase ?: return
        val current = session ?: return
        val parentId = if (type == AttributeType.MODEL) {
            _uiState.value.picked[AttributeType.BRAND]?.attributeId
        } else null
        scope.launch {
            runCatching { useCase.execute(current, type, name, parentId) }
                .onSuccess { id ->
                    val ref = AttributeRef(id, name.trim())
                    if (type == AttributeType.LOCATION) {
                        _uiState.update { it.copy(batchLocation = ref) }
                    } else {
                        _uiState.update { s ->
                            val picked = s.picked.toMutableMap().apply { put(type, ref) }
                            if (type == AttributeType.BRAND) picked.remove(AttributeType.MODEL)
                            s.copy(picked = picked)
                        }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = e.message ?: "Could not add ${type.wire}") }
                }
        }
    }

    // ── Purchase dialog (#58) ─────────────────────────────────────────────────────

    /** Company entities as dropdown items — suppliers first, always incl. Unspecified Supplier. */
    fun purchasePartyOptions(): List<AttributeValue> {
        val list = _uiState.value.entities.filter { it.isActive }
        val withDefault = if (list.any { it.id == UNSPECIFIED_SUPPLIER_ID }) {
            list
        } else {
            list + Entity(
                id = UNSPECIFIED_SUPPLIER_ID,
                name = UNSPECIFIED_SUPPLIER_NAME,
                roles = setOf(EntityRole.SUPPLIER),
            )
        }
        return withDefault
            .sortedWith(
                compareByDescending<Entity> { EntityRole.SUPPLIER in it.roles }
                    .thenBy { it.name.lowercase() },
            )
            .map { AttributeValue(attributeId = it.id, name = it.name) }
    }

    /** Add-new-inline is offered only when the user can manage profiles (it creates a party). */
    fun canAddSupplierInline(): Boolean = session?.permissions?.profiles == PermissionLevel.MANAGE

    /**
     * Open the purchase dialog. The party is left **unselected** so the dropdown shows the
     * full party list (a pre-selected value would pre-fill the search box and filter the
     * list down to just that one row). "Unspecified Supplier" is the placeholder + the
     * default applied on confirm when nothing is picked — the "skip" case.
     */
    fun openPurchaseDialog() = _uiState.update { state ->
        // Compute the commission the active rules propose for this batch (ticket #97). Grouped
        // by location inside the calculator, so a batch spanning two shops gets the right lines
        // for each. Seed one editable decision per line (default: accrue, at the rule's figure).
        val units = state.reviewUnits.map {
            NewUnit(imei = it.imei, cost = it.cost, condition = it.condition, location = it.location)
        }
        val lines = CommissionCalculator.compute(units, state.activeCommissionRules)
        val decisions = lines.associate { line ->
            line.ruleId to CommissionDecision(amount = line.amount)
        }
        state.copy(
            showPurchaseDialog = true,
            purchaseParty = null,
            purchaseDate = System.currentTimeMillis(),
            purchaseCash = "",
            purchaseBank = "",
            commissionLines = lines,
            commissionDecisions = decisions,
        )
    }

    // ── Commission decisions (#97) ────────────────────────────────────────────────

    private fun updateDecision(ruleId: String, transform: (CommissionDecision) -> CommissionDecision) =
        _uiState.update { state ->
            val current = state.commissionDecisions[ruleId] ?: return@update state
            state.copy(commissionDecisions = state.commissionDecisions + (ruleId to transform(current)))
        }

    /** Skip a proposed line entirely (writes no commission) or bring it back. */
    fun setCommissionIncluded(ruleId: String, included: Boolean) =
        updateDecision(ruleId) { it.copy(included = included) }

    /** Add-to-balance (accrue) vs give-now (cash + bank split). */
    fun setCommissionGiveNow(ruleId: String, giveNow: Boolean) =
        updateDecision(ruleId) { it.copy(giveNow = giveNow) }

    /** Cash given now (numeric-only). */
    fun setCommissionCash(ruleId: String, raw: String) =
        updateDecision(ruleId) { it.copy(cash = sanitizeDecimalInput(raw)) }

    /** Bank given now (numeric-only). */
    fun setCommissionBank(ruleId: String, raw: String) =
        updateDecision(ruleId) { it.copy(bank = sanitizeDecimalInput(raw)) }

    /**
     * Hand-edit a line's amount for this batch. Digits + one decimal point only (never a float).
     * Marked [CommissionDecision.overridden] when it no longer equals the rule's computed figure,
     * so a reviewer can tell it wasn't the rule — and the persisted commission drops its ruleId.
     */
    fun setCommissionAmount(ruleId: String, raw: String) {
        val cleaned = sanitizeDecimalInput(raw)
        _uiState.update { state ->
            val current = state.commissionDecisions[ruleId] ?: return@update state
            val line = state.commissionLines.firstOrNull { it.ruleId == ruleId }
            val overridden = line == null || cleaned != line.amount
            state.copy(
                commissionDecisions = state.commissionDecisions +
                    (ruleId to current.copy(amount = cleaned, overridden = overridden)),
            )
        }
    }

    /** Display name of a commission payee (from the loaded entity list); falls back to the id. */
    fun payeeName(entityId: String): String =
        _uiState.value.entities.firstOrNull { it.id == entityId }?.name ?: entityId

    /** Display name of a location attribute (from the loaded vocabulary); falls back to the id. */
    fun locationName(attributeId: String): String =
        _uiState.value.attributes.firstOrNull { it.attributeId == attributeId }?.name ?: attributeId

    fun setPurchaseParty(value: AttributeValue) = _uiState.update {
        it.copy(purchaseParty = AttributeRef(value.attributeId, value.name))
    }

    fun setPurchaseCash(v: String) = _uiState.update { it.copy(purchaseCash = sanitizeDecimalInput(v)) }
    fun setPurchaseBank(v: String) = _uiState.update { it.copy(purchaseBank = sanitizeDecimalInput(v)) }

    /** Add-new-inline: create a name-only SUPPLIER party and select it. */
    fun addNewSupplier(name: String) {
        val useCase = addSupplierInlineUseCase ?: return
        val current = session ?: return
        scope.launch {
            runCatching { useCase.execute(current, name) }
                .onSuccess { id ->
                    _uiState.update { it.copy(purchaseParty = AttributeRef(id, name.trim())) }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Could not add supplier") } }
        }
    }

    /** Pick the batch's business date. The picker itself refuses future dates. */
    fun setPurchaseDate(millis: Long) = _uiState.update { it.copy(purchaseDate = millis) }

    /** Confirm the dialog: record against the chosen party + entered amounts, then save. */
    fun confirmPurchaseAndSave() {
        val s = _uiState.value
        if (s.purchasePaidExceedsTotal || s.commissionGiveExceeds) return // guarded; button disabled too
        val partyId = s.purchaseParty?.attributeId?.takeIf { it.isNotBlank() } ?: UNSPECIFIED_SUPPLIER_ID
        saveInventoryWithPurchase(
            partyId,
            s.purchaseCash.trim(),
            s.purchaseBank.trim(),
            buildCommissionInputs(s),
            s.purchaseDate,
        )
    }

    /**
     * Turn the user's kept-and-decided commission lines into [CommissionInput]s (ticket #97).
     * Skipped lines are dropped; a hand-edited line drops its `ruleId` (per schema, a null ruleId
     * marks an amount that wasn't the rule's). A line whose amount was cleared to a non-positive
     * value is treated as skipped — the use case would reject it, and an empty owe is not a debt.
     */
    private fun buildCommissionInputs(s: AddStockUiState): List<CommissionInput> =
        s.commissionLines.mapNotNull { line ->
            val d = s.commissionDecisions[line.ruleId] ?: return@mapNotNull null
            if (!d.included) return@mapNotNull null
            val amount = d.amount.trim()
            if (!Money.isValidPositiveDecimal(amount)) return@mapNotNull null
            CommissionInput(
                payeeEntityId = line.payeeEntityId,
                locationAttributeId = line.locationAttributeId,
                ruleId = if (d.overridden) null else line.ruleId,
                unitCount = line.unitCount,
                basisAmount = line.basisAmount,
                amount = amount,
                paidCash = if (d.giveNow) d.cash.trim().ifEmpty { "0" } else "0",
                paidBank = if (d.giveNow) d.bank.trim().ifEmpty { "0" } else "0",
            )
        }

    /**
     * Cancel the dialog (Escape / ✕ / click-away): just close it and save nothing. The
     * batch stays on the review screen so the user can reopen and Confirm. Only the
     * Confirm button commits the inventory + purchase.
     */
    fun dismissPurchaseDialog() = _uiState.update { it.copy(showPurchaseDialog = false) }

    // ── Save ─────────────────────────────────────────────────────────────────────

    /**
     * Saves the batch: its stock **and** its purchase record land in **one Firestore
     * transaction** (either both commit or neither) — so a failed purchase write can never
     * leave phantom inventory the books never learn about (ticket #58). A real failure is
     * surfaced to the cashier, never swallowed.
     */
    private fun saveInventoryWithPurchase(
        partyId: String,
        cash: String,
        bank: String,
        commissions: List<CommissionInput>,
        purchaseDate: Long,
    ) {
        val current = session ?: return
        val groups = buildStockGroups(_uiState.value)
        _uiState.update { it.copy(showPurchaseDialog = false, saving = true, error = null) }
        scope.launch {
            runCatching {
                recordInventoryPurchaseUseCase?.execute(
                    current, groups, partyId, cash, bank, commissions,
                    purchaseDate, System.currentTimeMillis(),
                )
            }
                .onSuccess { _uiState.update { it.copy(saving = false, done = true) } }
                .onFailure { e ->
                    when (e) {
                        is DuplicateImeiException -> _uiState.update {
                            it.copy(saving = false, error = "duplicate:${e.imeis.joinToString()}")
                        }
                        else -> _uiState.update {
                            it.copy(saving = false, error = "network:${e.message ?: "Save failed"}")
                        }
                    }
                }
        }
    }

    /**
     * Splits the reviewed batch into per-SKU write groups — grouped by SKU (sorted
     * attribute-id pairs → stable key). The single add-units-to-an-existing-SKU path
     * carries a null product (the product doc already exists); every other group carries a
     * [NewProduct] to find-or-create.
     */
    private fun buildStockGroups(state: AddStockUiState): List<StockBatchGroup> {
        val groups = state.reviewUnits
            .groupBy { unit ->
                unit.attributes.entries
                    .sortedBy { it.key.wire }
                    .joinToString(",") { "${it.key.wire}:${it.value.attributeId}" }
            }
            .values
            .toList()

        val existing = state.existingProductId
        return groups.map { group ->
            val newUnits = group.map { r ->
                NewUnit(imei = r.imei, cost = r.cost, condition = r.condition, location = r.location)
            }
            if (existing != null && groups.size == 1) {
                // add-units-to-existing-product path (single locked SKU)
                StockBatchGroup(skuKey = existing, product = null, units = newUnits)
            } else {
                val rep = group.first()
                StockBatchGroup(
                    skuKey = SkuKey.build(rep.attributes),
                    product = NewProduct(
                        attributes = rep.attributes,
                        defaultSellingPrice = rep.sellingPrice.trim(),
                    ),
                    units = newUnits,
                )
            }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    // Preserve the observed party list + currency across a reset — they come from live
    // streams / the session, not the form, and the Firestore listener won't re-emit them
    // just because the form was cleared (otherwise the purchase dialog's dropdown goes empty).
    fun reset() = _uiState.update {
        AddStockUiState(
            attributes = it.attributes,
            entities = it.entities,
            currency = it.currency,
            activeCommissionRules = it.activeCommissionRules,
        )
    }

    /** Release the resources this VM owns: cancel the scope + close the Admin-SDK clients. */
    fun dispose() {
        scope.cancel()
        invRepo?.close()
        attrRepo?.close()
        entityRepo?.close()
        commissionRuleRepo?.close()
    }
}
