package com.humblesolutions.aromex.ui.inventory

import com.humblesolutions.aromex.data.BackendInventoryRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.ProductEdits
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialEdits
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.usecase.ArchiveProductUseCase
import com.humblesolutions.aromex.usecase.ArchiveUnitUseCase
import com.humblesolutions.aromex.usecase.ObserveInventoryUseCase
import com.humblesolutions.aromex.usecase.SetUnitStatusUseCase
import com.humblesolutions.aromex.usecase.UpdateProductUseCase
import com.humblesolutions.aromex.usecase.UpdateUnitUseCase
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

/** One product-list row: the SKU + its in-stock unit count (grouped in memory). */
data class SkuRow(val product: Product, val inStockCount: Int)

/** One location entry for the browse panel's left location list. */
data class LocationEntry(val id: String?, val name: String, val count: Int)

/** A single in-stock unit resolved for display in the expanded IMEI list. */
data class BrowseUnit(
    val serialId: String,
    val imei: String,
    val cost: String,
    val condition: Condition,
    val location: AttributeRef,
    val status: SerialStatus,
    val sellingPrice: String,
    val capacity: String,
    val color: String,
    val carrier: String,
)

data class ModelGroup(
    val modelName: String,
    val units: List<BrowseUnit>,
    val totalRetailValue: String = "0",
) {
    val totalUnits: Int get() = units.size
}

data class BrandGroup(
    val brandName: String,
    val models: List<ModelGroup>,
    val totalRetailValue: String = "0",
) {
    val totalUnits: Int get() = models.sumOf { it.totalUnits }
    val totalModels: Int get() = models.size
}

/** KPI summary bar data — derived from location-filtered in-stock serials. */
data class InventorySummary(
    val totalUnits: Int = 0,
    val totalCostValue: String = "0",
    val totalRetailValue: String = "0",
    val topModelName: String? = null,
    val topModelCount: Int = 0,
)

data class InventoryListUiState(
    val session: UserSession? = null,
    val canManage: Boolean = false,
    val noAccess: Boolean = false,
    val rows: List<SkuRow> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val actionError: String? = null,
    // Browse state (ticket #55)
    val selectedLocationId: String? = null,
    val locationEntries: List<LocationEntry> = emptyList(),
    val browseGroups: List<BrandGroup> = emptyList(),
    // Same shape as [browseGroups] but built from ALL in-stock units, ignoring the current
    // location/search filter — lets the UI size table columns to the full dataset so widths
    // stay stable while the user types a search or clicks a location, instead of jumping
    // around as the filtered result set shrinks/grows.
    val allBrowseGroups: List<BrandGroup> = emptyList(),
    val summary: InventorySummary = InventorySummary(),
)

/**
 * Desktop inventory list + edit/archive. Plain class + [CoroutineScope] (Desktop has no
 * AndroidViewModel lifecycle) — call [dispose] when the screen leaves composition.
 *
 * Holds live product + serial streams, groups serials by productId in memory for each SKU's
 * stock count, and runs search/filter/grouping client-side — no re-fetch per interaction.
 */
class InventoryListViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)

    private var repo: BackendInventoryRepository? = null
    private var observeUseCase: ObserveInventoryUseCase? = null
    private var updateProductUseCase: UpdateProductUseCase? = null
    private var archiveProductUseCase: ArchiveProductUseCase? = null
    private var setUnitStatusUseCase: SetUnitStatusUseCase? = null
    private var archiveUnitUseCase: ArchiveUnitUseCase? = null
    private var updateUnitUseCase: UpdateUnitUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private var products: List<Product> = emptyList()
    private var inStock: List<Serial> = emptyList()

    private val _uiState = MutableStateFlow(InventoryListUiState())
    val uiState: StateFlow<InventoryListUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        val broker = FirestoreTokenBroker(authRepo, config)
        val inv = BackendInventoryRepository(broker, config, session.uid)
        repo = inv
        observeUseCase = ObserveInventoryUseCase(inv)
        updateProductUseCase = UpdateProductUseCase(inv)
        archiveProductUseCase = ArchiveProductUseCase(inv)
        setUnitStatusUseCase = SetUnitStatusUseCase(inv)
        archiveUnitUseCase = ArchiveUnitUseCase(inv)
        updateUnitUseCase = UpdateUnitUseCase(inv)

        val level = session.permissions.inventory
        if (level == PermissionLevel.NONE) {
            _uiState.update { it.copy(session = session, noAccess = true) }
            return
        }
        _uiState.update {
            it.copy(session = session, canManage = level == PermissionLevel.MANAGE, noAccess = false)
        }
        observeProducts()
        observeSerials()
    }

    private fun observeProducts() {
        val useCase = observeUseCase ?: return
        val current = session ?: return
        scope.launch {
            runCatching {
                useCase.observeProducts(current)
                    .catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load products") } }
                    .collect { list ->
                        products = list
                        recompute()
                    }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    private fun observeSerials() {
        val useCase = observeUseCase ?: return
        val current = session ?: return
        scope.launch {
            runCatching {
                useCase.observeInStockSerials(current)
                    .catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load units") } }
                    .collect { list ->
                        inStock = list
                        recompute()
                    }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        recompute()
    }

    /** Select a location for browse filtering; null = show all locations. */
    fun selectLocation(id: String?) {
        _uiState.update { it.copy(selectedLocationId = id) }
        recompute()
    }

    /** In-stock units for a SKU (backs drill-in). */
    fun unitsFor(productId: String): List<Serial> = inStock.filter { it.productId == productId }

    fun editSellingPrice(productId: String, price: String) = runAction {
        updateProductUseCase?.execute(it, productId, ProductEdits(defaultSellingPrice = price))
    }

    fun archiveProduct(productId: String) = runAction {
        archiveProductUseCase?.execute(it, productId)
    }

    fun setUnitStatus(serialId: String, status: SerialStatus) = runAction {
        setUnitStatusUseCase?.execute(it, serialId, status)
    }

    fun archiveUnit(serialId: String) = runAction {
        archiveUnitUseCase?.execute(it, serialId)
    }

    fun editUnit(serialId: String, cost: String?, condition: Condition?) = runAction {
        updateUnitUseCase?.execute(it, serialId, SerialEdits(cost = cost, condition = condition))
    }

    private fun runAction(block: suspend (UserSession) -> Unit) {
        val current = session ?: return
        scope.launch {
            runCatching { block(current) }
                .onFailure { e -> _uiState.update { it.copy(actionError = e.message ?: "Action failed") } }
        }
    }

    fun clearActionError() = _uiState.update { it.copy(actionError = null) }

    private fun recompute() {
        val query = _uiState.value.query.trim()
        val selectedId = _uiState.value.selectedLocationId

        // --- Existing SkuRow list (backs Mode.Drill) ---
        val countByProduct = inStock.groupingBy { it.productId }.eachCount()
        val rows = products
            .asSequence()
            .filter { product -> matches(product, query) }
            .map { SkuRow(it, countByProduct[it.productId] ?: 0) }
            .sortedBy { it.product.attributes[AttributeType.BRAND]?.name?.lowercase() ?: "" }
            .toList()

        // --- Browse grouping (ticket #55) ---
        val productById = products.associateBy { it.productId }

        // Location filter first; query filter after.
        val locationFiltered = if (selectedId == null) inStock
            else inStock.filter { it.location.attributeId == selectedId }

        // KPI summary — computed from location-filtered serials (before query filter)
        var totalCost = java.math.BigDecimal.ZERO
        var totalRetail = java.math.BigDecimal.ZERO
        locationFiltered.forEach { serial ->
            totalCost = totalCost.add(serial.cost.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
            val sp = productById[serial.productId]?.defaultSellingPrice
            totalRetail = totalRetail.add(sp?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
        }
        val topModel = locationFiltered
            .groupBy { productById[it.productId]?.attributes?.get(AttributeType.MODEL)?.name ?: "Unknown" }
            .maxByOrNull { it.value.size }
        val summary = InventorySummary(
            totalUnits = locationFiltered.size,
            totalCostValue = totalCost.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
            totalRetailValue = totalRetail.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString(),
            topModelName = topModel?.key,
            topModelCount = topModel?.value?.size ?: 0,
        )

        val queryFiltered = if (query.isBlank()) locationFiltered
            else locationFiltered.filter { serial ->
                serial.imei.contains(query, ignoreCase = true) ||
                    productById[serial.productId]?.attributes?.values
                        ?.any { it.name.contains(query, ignoreCase = true) } == true
            }

        // Brand → Model → individual BrowseUnits (flat per-IMEI rows)
        val browseGroups = buildBrowseGroups(queryFiltered, productById)
        val allBrowseGroups = buildBrowseGroups(inStock, productById)

        // Location panel entries — always from all in-stock, unaffected by filters
        val countByLocation = inStock.groupBy { it.location.attributeId }.mapValues { it.value.size }
        val locationEntries = listOf(LocationEntry(null, "All", inStock.size)) +
            inStock.map { it.location }
                .distinctBy { it.attributeId }
                .filter { it.attributeId.isNotBlank() }
                .sortedBy { it.name.lowercase() }
                .map { ref -> LocationEntry(ref.attributeId, ref.name, countByLocation[ref.attributeId] ?: 0) }

        _uiState.update {
            it.copy(
                rows = rows,
                browseGroups = browseGroups,
                allBrowseGroups = allBrowseGroups,
                locationEntries = locationEntries,
                summary = summary,
            )
        }
    }

    /** Groups a serial list into the Brand → Model → BrowseUnit tree the browse table renders. */
    private fun buildBrowseGroups(serials: List<Serial>, productById: Map<String, Product>): List<BrandGroup> =
        serials
            .groupBy { productById[it.productId]?.attributes?.get(AttributeType.BRAND)?.name ?: "Unknown" }
            .entries.sortedBy { it.key.lowercase() }
            .map { (brand, brandSerials) ->
                val brandRetail = brandSerials.fold(java.math.BigDecimal.ZERO) { acc, s ->
                    val sp = productById[s.productId]?.defaultSellingPrice
                    acc.add(sp?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
                }.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

                BrandGroup(
                    brandName = brand,
                    totalRetailValue = brandRetail,
                    models = brandSerials
                        .groupBy {
                            productById[it.productId]?.attributes?.get(AttributeType.MODEL)?.name ?: "Unknown"
                        }
                        .entries.sortedBy { it.key.lowercase() }
                        .map { (model, modelSerials) ->
                            val modelRetail = modelSerials.fold(java.math.BigDecimal.ZERO) { acc, s ->
                                val sp = productById[s.productId]?.defaultSellingPrice
                                acc.add(sp?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
                            }.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

                            ModelGroup(
                                modelName = model,
                                totalRetailValue = modelRetail,
                                units = modelSerials.map { serial ->
                                    val p = productById[serial.productId]
                                    BrowseUnit(
                                        serialId = serial.serialId,
                                        imei = serial.imei,
                                        cost = serial.cost,
                                        condition = serial.condition,
                                        location = serial.location,
                                        status = serial.status,
                                        sellingPrice = p?.defaultSellingPrice ?: "0",
                                        // Optional attributes (ticket #101): a blank/missing one renders
                                        // as an empty cell, never a "—" placeholder.
                                        capacity = p?.attributes?.get(AttributeType.CAPACITY)?.name.orEmpty(),
                                        color = p?.attributes?.get(AttributeType.COLOR)?.name.orEmpty(),
                                        carrier = p?.attributes?.get(AttributeType.CARRIER)?.name.orEmpty(),
                                    )
                                },
                            )
                        },
                )
            }

    /** Client-side search over brand/model/color/carrier names and the SKU's in-stock IMEIs. */
    private fun matches(product: Product, query: String): Boolean {
        if (query.isBlank()) return true
        val attrHit = product.attributes.values.any { it.name.contains(query, ignoreCase = true) }
        if (attrHit) return true
        return inStock.any { it.productId == product.productId && it.imei.contains(query, ignoreCase = true) }
    }

    /** Release the resources this VM owns: cancel the observe scope (its awaitClose removes
     *  the Firestore listeners), then close the Admin-SDK Firestore client (its gRPC channel). */
    fun dispose() {
        scope.cancel()
        repo?.close()
    }
}
