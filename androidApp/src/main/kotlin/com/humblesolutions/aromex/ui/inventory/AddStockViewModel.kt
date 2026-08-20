package com.humblesolutions.aromex.ui.inventory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.BackendAttributeRepository
import com.humblesolutions.aromex.data.BackendCommissionRuleRepository
import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.BackendInventoryRepository
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
import com.humblesolutions.aromex.util.InventoryLimits
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.SkuKey
import com.humblesolutions.aromex.util.parseSickw
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImeiCheckState { IDLE, CHECKING, AVAILABLE, ALREADY_IN_BATCH, ALREADY_IN_STOCK, INVALID }

enum class AddInventoryRoute { PASTE, ENTRY, REVIEW }

/** The finish state of one review row, driving its status colour on screen 2. */
enum class ReviewRowStatus { CONFIDENT, MUST_FILL, PROBLEM }

/** A unit on the review screen — carries its own SKU snapshot so mixed-SKU batches are supported. */
data class ReviewUnit(
    val imei: String,
    val cost: String,
    val condition: Condition,
    val location: AttributeRef,
    val attributes: Map<AttributeType, AttributeRef> = emptyMap(),
    val sellingPrice: String = "",
) {
    /**
     * True once cost, selling price, location and all SKU-defining attributes are present.
     * Selling price is required because a new SKU's [NewProduct] needs one (SICKW carries
     * no price), so the paste flow surfaces it as a must-fill field.
     */
    val isComplete: Boolean
        get() = cost.isNotBlank() && sellingPrice.isNotBlank() &&
            location.attributeId.isNotBlank() &&
            AttributeType.SKU_DEFINING.all { attributes.containsKey(it) }
}

/**
 * The user's decision on one proposed [CommissionLine] in the intake dialog (ticket #97): kept
 * ([included]) or skipped, its [amount] editable ([overridden] when it no longer equals the rule's
 * figure), and either added to the payee's balance or **given now** ([giveNow]) as a [cash] + [bank]
 * split (mirroring the inventory-supplier UI). Keyed by the line's `ruleId` in state.
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
    // ── SICKW paste (#53) ────────────────────────────────────────────────────
    val pasteText: String = "",
    val parsing: Boolean = false,
    // Blocks the parser couldn't read — shown, never dropped
    val unreadable: List<UnreadableBlock> = emptyList(),
    // Attribute ids minted during resolution — tag those cells "new"
    val newlyCreatedIds: Set<String> = emptySet(),
    // IMEIs found already in stock (live review-table check)
    val inStockImeis: Set<String> = emptySet(),
    // Latest parse counts, for the summary banner (null = not from a paste)
    val lastParsedCount: Int? = null,
    val lastUnreadableCount: Int = 0,
    // ── Purchase dialog (#58) ──────────────────────────────────────────────────
    val showPurchaseDialog: Boolean = false,
    // Company currency (for the money fields), captured on bind.
    val currency: String = "",
    // All company entities (for the bought-from dropdown); suppliers sorted first below.
    val entities: List<Entity> = emptyList(),
    // The chosen party; defaults to the reserved Unspecified Supplier when the dialog opens.
    val purchaseParty: AttributeRef? = null,
    val purchaseCash: String = "",
    val purchaseBank: String = "",
    // ── Commission on intake (#97) ─────────────────────────────────────────────
    val activeCommissionRules: List<CommissionRule> = emptyList(),
    val commissionLines: List<CommissionLine> = emptyList(),
    val commissionDecisions: Map<String, CommissionDecision> = emptyMap(),
    // Async
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
) {
    /** The batch's real total cost = Σ each reviewed unit's cost (decimal-string sum). */
    val batchTotalCost: String get() = Money.sum(reviewUnits.map { it.cost })

    /** True when cash + bank exceeds the batch total — blocks confirm with an inline error. */
    val purchasePaidExceedsTotal: Boolean
        get() = !Money.lessThanOrEqual(
            Money.add(purchaseCash.trim().ifEmpty { "0" }, purchaseBank.trim().ifEmpty { "0" }),
            batchTotalCost,
        )

    /** True when any included commission's give-now split exceeds what's owed — blocks confirm (#97). */
    val commissionGiveExceeds: Boolean
        get() = commissionLines.any { line ->
            commissionDecisions[line.ruleId]?.let { it.included && it.giveExceedsAmount } == true
        }
    /** IMEIs appearing more than once across the review batch (in-paste duplicates). */
    val duplicateImeis: Set<String>
        get() = reviewUnits.groupingBy { it.imei }.eachCount()
            .filterValues { it > 1 }.keys

    /** True when the batch is over the safe transaction ceiling and must be split. */
    val exceedsBatchCap: Boolean get() = InventoryLimits.exceedsBatchCap(reviewUnits.size)

    /** Status colour for a given review row. */
    fun statusOf(unit: ReviewUnit): ReviewRowStatus = when {
        unit.imei in inStockImeis || unit.imei in duplicateImeis -> ReviewRowStatus.PROBLEM
        !unit.isComplete -> ReviewRowStatus.MUST_FILL
        else -> ReviewRowStatus.CONFIDENT
    }

    /** Confirm is allowed only when every row is complete, unique, not in stock, and under the cap. */
    val canConfirm: Boolean
        get() = reviewUnits.isNotEmpty() && !exceedsBatchCap &&
            reviewUnits.all { it.isComplete } &&
            duplicateImeis.isEmpty() &&
            reviewUnits.none { it.imei in inStockImeis }
}

/**
 * Drives the Add-Inventory flow on Android: attribute pickers (add-new-inline, model
 * filtered by brand), batch-shared cost/condition/location, IMEI ✓ validation with
 * advisory pre-check, two-screen route (Entry → Review → save).
 *
 * Survives configuration changes as [AndroidViewModel]; all form data is preserved.
 */
class AddStockViewModel(application: Application) : AndroidViewModel(application) {

    private var invRepo: BackendInventoryRepository? = null
    private var addAttributeUseCase: AddAttributeUseCase? = null
    private var checkAvailabilityUseCase: CheckImeiAvailabilityUseCase? = null
    private var resolveParsedPhonesUseCase: ResolveParsedPhonesUseCase? = null
    private var recordInventoryPurchaseUseCase: RecordInventoryPurchaseUseCase? = null
    private var observePartiesUseCase: ObservePartiesForPurchaseUseCase? = null
    private var addSupplierInlineUseCase: AddSupplierInlineUseCase? = null
    private var observeActiveCommissionRulesUseCase: ObserveActiveCommissionRulesUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private val _uiState = MutableStateFlow(AddStockUiState())
    val uiState: StateFlow<AddStockUiState> = _uiState.asStateFlow()

    val hasUnsavedChanges: Boolean
        get() = _uiState.value.let {
            it.pendingImei.isNotBlank() || it.stagedImeis.isNotEmpty() ||
                it.reviewUnits.isNotEmpty() || it.picked.isNotEmpty() ||
                it.batchCost.isNotBlank() || it.batchLocation != null ||
                it.pasteText.isNotBlank()
        }

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config
        _uiState.update { it.copy(currency = session.currency) }

        val inv = BackendInventoryRepository(getApplication(), config)
        val attrs = BackendAttributeRepository(getApplication(), config)
        val entities = BackendEntityRepository(getApplication(), config)
        val commissionRules = BackendCommissionRuleRepository(getApplication(), config)
        invRepo = inv
        addAttributeUseCase = AddAttributeUseCase(attrs)
        checkAvailabilityUseCase = CheckImeiAvailabilityUseCase(inv)
        resolveParsedPhonesUseCase = ResolveParsedPhonesUseCase(attrs)
        recordInventoryPurchaseUseCase = RecordInventoryPurchaseUseCase(inv)
        observePartiesUseCase = ObservePartiesForPurchaseUseCase(entities)
        addSupplierInlineUseCase = AddSupplierInlineUseCase(entities)
        observeActiveCommissionRulesUseCase = ObserveActiveCommissionRulesUseCase(commissionRules)

        viewModelScope.launch {
            runCatching {
                attrs.observeAttributes()
                    .catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load attributes") } }
                    .collect { list -> _uiState.update { it.copy(attributes = list) } }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }

        // Live party list for the bought-from dropdown, gated on `inventory` (not `profiles`)
        // so anyone who can add stock sees the supplier list. Falls back to just the
        // Unspecified Supplier default if even inventory access is missing.
        viewModelScope.launch {
            runCatching {
                observePartiesUseCase?.execute(session)
                    ?.catch { /* stream error — leave list empty, default still works */ }
                    ?.collect { list -> _uiState.update { it.copy(entities = list) } }
            }
        }

        // Active commission rules (ticket #97) — gated on inventory access so the cashier who
        // adds stock sees each proposed commission at intake. No access / no rules → empty list,
        // and the commission section simply never appears.
        viewModelScope.launch {
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
            // Fallback if repo not yet bound: add directly without the pre-check
            addImeiDirectly(imei)
            return
        }

        _uiState.update { it.copy(imeiCheckState = ImeiCheckState.CHECKING) }
        viewModelScope.launch {
            runCatching { useCase.execute(sess, imei) }
                .onSuccess { inStock ->
                    if (inStock) {
                        _uiState.update { it.copy(imeiCheckState = ImeiCheckState.ALREADY_IN_STOCK) }
                    } else {
                        addImeiDirectly(imei)
                    }
                }
                .onFailure {
                    // Pre-check failed (network/permission) — still allow adding; the
                    // transactional guard will catch real duplicates at confirm time.
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

    fun addMoreFromReview() = _uiState.update { it.copy(route = AddInventoryRoute.ENTRY) }

    /** Pre-load picked + price from an existing unit's snapshot so the dialog pre-fills correctly. */
    fun setPickedFromUnit(attributes: Map<AttributeType, AttributeRef>, sellingPrice: String) {
        _uiState.update { it.copy(picked = attributes, defaultSellingPrice = sellingPrice) }
    }

    // ── Review screen mutations ──────────────────────────────────────────────────

    /** Advisory IMEI check for the edit dialog — returns the check state; callers drive the spinner. */
    suspend fun checkImeiAvailability(imei: String, existingImeis: Set<String>): ImeiCheckState {
        if (!Imei.isValid(imei)) return ImeiCheckState.INVALID
        if (imei in existingImeis) return ImeiCheckState.ALREADY_IN_BATCH
        val sess = session ?: return ImeiCheckState.AVAILABLE
        val useCase = checkAvailabilityUseCase ?: return ImeiCheckState.AVAILABLE
        return runCatching { useCase.execute(sess, imei) }
            .map { inStock -> if (inStock) ImeiCheckState.ALREADY_IN_STOCK else ImeiCheckState.AVAILABLE }
            .getOrElse { ImeiCheckState.AVAILABLE }
    }

    fun editReviewUnit(index: Int, unit: ReviewUnit) = _uiState.update {
        val list = it.reviewUnits.toMutableList()
        if (index in list.indices) list[index] = unit
        it.copy(reviewUnits = list)
    }

    fun removeReviewUnit(index: Int) = _uiState.update {
        it.copy(reviewUnits = it.reviewUnits.filterIndexed { i, _ -> i != index })
    }

    // ── SICKW paste flow (#53) ────────────────────────────────────────────────────

    /** Open a fresh paste screen (clears any prior paste batch). */
    fun startPaste() = _uiState.update {
        AddStockUiState(attributes = it.attributes, entities = it.entities, currency = it.currency, route = AddInventoryRoute.PASTE)
    }

    fun onPasteTextChange(text: String) = _uiState.update { it.copy(pasteText = text) }

    fun clearPasteText() = _uiState.update { it.copy(pasteText = "") }

    /** Return from Review to the paste box to append more phones ("Paste more"). */
    fun pasteMore() = _uiState.update { it.copy(pasteText = "", route = AddInventoryRoute.PASTE) }

    /**
     * Parse the pasted SICKW text, resolve its attributes to vocab (find-or-create), append
     * the parsed phones as review rows (cost/condition/location left for the user), keep any
     * unreadable blocks, then land on the review table. Never auto-runs — only on demand.
     */
    fun parseAndAdd() {
        val text = _uiState.value.pasteText
        if (text.isBlank()) return
        val sess = session ?: return
        _uiState.update { it.copy(parsing = true, error = null) }

        viewModelScope.launch {
            val result = parseSickw(text)
            val resolve = resolveParsedPhonesUseCase
            val resolved = if (resolve != null) {
                runCatching { resolve.execute(sess, result.phones, _uiState.value.attributes) }
                    .getOrNull()
            } else null

            val newUnits = (resolved?.phones ?: result.phones.map {
                com.humblesolutions.aromex.model.ResolvedPhone(it.imei, emptyMap(), "", it.rawText)
            }).map { rp ->
                ReviewUnit(
                    imei = rp.imei,
                    cost = "",
                    condition = Condition.NEW,
                    location = AttributeRef(),
                    attributes = rp.attributes,
                    sellingPrice = rp.sellingPrice,
                )
            }

            _uiState.update {
                it.copy(
                    parsing = false,
                    reviewUnits = it.reviewUnits + newUnits,
                    unreadable = it.unreadable + result.unreadable,
                    newlyCreatedIds = it.newlyCreatedIds + (resolved?.newlyCreatedIds ?: emptySet()),
                    lastParsedCount = newUnits.size,
                    lastUnreadableCount = result.unreadable.size,
                    pasteText = "",
                    route = AddInventoryRoute.REVIEW,
                )
            }
            refreshInStockChecks()
        }
    }

    fun dismissUnreadable(index: Int) = _uiState.update {
        it.copy(unreadable = it.unreadable.filterIndexed { i, _ -> i != index })
    }

    // ── Apply-to-all bar ──────────────────────────────────────────────────────────

    fun applyCostToAll(cost: String) = _uiState.update { s ->
        s.copy(reviewUnits = s.reviewUnits.map { it.copy(cost = cost) })
    }

    fun applyConditionToAll(condition: Condition) = _uiState.update { s ->
        s.copy(reviewUnits = s.reviewUnits.map { it.copy(condition = condition) })
    }

    fun applyLocationToAll(location: AttributeRef) = _uiState.update { s ->
        s.copy(reviewUnits = s.reviewUnits.map { it.copy(location = location) })
    }

    /** SICKW carries no price; set the SKU selling price once across the whole paste. */
    fun applyPriceToAll(price: String) = _uiState.update { s ->
        s.copy(reviewUnits = s.reviewUnits.map { it.copy(sellingPrice = price) })
    }

    // ── Inline per-row cell edits ───────────────────────────────────────────────────

    private fun mutateRow(index: Int, transform: (ReviewUnit) -> ReviewUnit) = _uiState.update { s ->
        val list = s.reviewUnits.toMutableList()
        if (index in list.indices) list[index] = transform(list[index])
        s.copy(reviewUnits = list)
    }

    fun setRowCost(index: Int, cost: String) = mutateRow(index) { it.copy(cost = cost) }
    fun setRowCondition(index: Int, condition: Condition) = mutateRow(index) { it.copy(condition = condition) }
    fun setRowLocation(index: Int, location: AttributeRef) = mutateRow(index) { it.copy(location = location) }

    /** Live "already in stock" check for every review IMEI; flags rows inline. */
    private fun refreshInStockChecks() {
        val sess = session ?: return
        val useCase = checkAvailabilityUseCase ?: return
        val imeis = _uiState.value.reviewUnits.map { it.imei }.distinct()
        viewModelScope.launch {
            val inStock = mutableSetOf<String>()
            for (imei in imeis) {
                if (!Imei.isValid(imei)) continue
                runCatching { useCase.execute(sess, imei) }.onSuccess { if (it) inStock += imei }
            }
            _uiState.update { it.copy(inStockImeis = inStock) }
        }
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
        viewModelScope.launch {
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
    fun canAddSupplierInline(): Boolean =
        session?.permissions?.profiles == com.humblesolutions.aromex.model.PermissionLevel.MANAGE

    /**
     * Open the purchase dialog. The party is left **unselected** so the dropdown shows the
     * full party list (a pre-selected value would pre-fill the search box and filter the
     * list down to just that one row). "Unspecified Supplier" is the placeholder + the
     * default applied on confirm when nothing is picked — the "skip" case.
     */
    fun openPurchaseDialog() = _uiState.update { state ->
        // Compute the commission the active rules propose for this batch (ticket #97). Grouped by
        // location inside the calculator, so a batch spanning two shops gets the right lines for
        // each. Seed one editable decision per line (default: accrue, at the rule's figure).
        val units = state.reviewUnits.map {
            NewUnit(imei = it.imei, cost = it.cost, condition = it.condition, location = it.location)
        }
        val lines = CommissionCalculator.compute(units, state.activeCommissionRules)
        val decisions = lines.associate { it.ruleId to CommissionDecision(amount = it.amount) }
        state.copy(
            showPurchaseDialog = true,
            purchaseParty = null,
            purchaseCash = "",
            purchaseBank = "",
            commissionLines = lines,
            commissionDecisions = decisions,
        )
    }

    fun setPurchaseParty(value: AttributeValue) = _uiState.update {
        it.copy(purchaseParty = AttributeRef(value.attributeId, value.name))
    }

    fun setPurchaseCash(v: String) = _uiState.update { it.copy(purchaseCash = sanitizeDecimalInput(v)) }
    fun setPurchaseBank(v: String) = _uiState.update { it.copy(purchaseBank = sanitizeDecimalInput(v)) }

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

    /** Add-new-inline: create a name-only SUPPLIER party and select it. */
    fun addNewSupplier(name: String) {
        val useCase = addSupplierInlineUseCase ?: return
        val current = session ?: return
        viewModelScope.launch {
            runCatching { useCase.execute(current, name) }
                .onSuccess { id ->
                    _uiState.update { it.copy(purchaseParty = AttributeRef(id, name.trim())) }
                }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Could not add supplier") } }
        }
    }

    /** Confirm the dialog: record against the chosen party + entered amounts, then save. */
    fun confirmPurchaseAndSave() {
        val s = _uiState.value
        if (s.purchasePaidExceedsTotal || s.commissionGiveExceeds) return // guarded; button disabled too
        val partyId = s.purchaseParty?.attributeId?.takeIf { it.isNotBlank() } ?: UNSPECIFIED_SUPPLIER_ID
        saveInventoryWithPurchase(partyId, s.purchaseCash.trim(), s.purchaseBank.trim(), buildCommissionInputs(s))
    }

    /**
     * Turn the user's kept-and-decided commission lines into [CommissionInput]s (ticket #97).
     * Skipped lines are dropped; a hand-edited line drops its `ruleId` (a null ruleId marks an
     * amount that wasn't the rule's). A non-positive amount is treated as skipped.
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
     * The inventory write is unchanged and goes first (same race-safe transaction, same
     * speed). Once it commits, a purchase record is written best-effort — a failure there
     * must never fail the save the cashier just completed (a retry would hit a duplicate
     * IMEI), so it's swallowed; the background HL sync is the CF's concern, not the UI's.
     */
    private fun saveInventoryWithPurchase(
        partyId: String,
        cash: String,
        bank: String,
        commissions: List<CommissionInput>,
    ) {
        val current = session ?: return
        val state = _uiState.value
        // Guard the Firestore ~500-write transaction cap: refuse an over-size batch.
        if (state.exceedsBatchCap) {
            _uiState.update {
                it.copy(
                    showPurchaseDialog = false,
                    error = "cap:${state.reviewUnits.size}:${InventoryLimits.SAFE_BATCH_CEILING}",
                )
            }
            return
        }
        val groups = buildStockGroups(state)
        _uiState.update { it.copy(showPurchaseDialog = false, saving = true, error = null) }
        viewModelScope.launch {
            // Stock + its purchase record commit in ONE Firestore transaction (both or
            // neither) — no phantom inventory the books never learn about. A real failure
            // is surfaced, never swallowed (ticket #58).
            runCatching {
                // Business date (ticket #107): today on a phone — no picker here yet, and stock
                // logged at the counter is logged on the day it arrives. Desktop can backdate.
                val nowMs = System.currentTimeMillis()
                recordInventoryPurchaseUseCase?.execute(
                    current, groups, partyId, cash, bank, commissions,
                    purchaseDate = nowMs, now = nowMs,
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
}

/**
 * Keeps only digits and at most one decimal point — the hardware/software-keyboard equivalent of
 * the numeric-only money input the ticket calls for (ticket #97). Money stays a decimal string;
 * there is never a float.
 */
internal fun sanitizeDecimalInput(raw: String): String {
    val kept = raw.filter { it.isDigit() || it == '.' }
    val firstDot = kept.indexOf('.')
    if (firstDot == -1) return kept
    return kept.substring(0, firstDot + 1) + kept.substring(firstDot + 1).replace(".", "")
}
