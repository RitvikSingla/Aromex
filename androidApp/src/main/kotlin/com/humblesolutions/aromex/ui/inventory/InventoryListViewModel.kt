package com.humblesolutions.aromex.ui.inventory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.BackendInventoryRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One product-list row: the SKU + its in-stock unit count (grouped in memory). */
data class SkuRow(val product: Product, val inStockCount: Int)

data class InventoryListUiState(
    val session: UserSession? = null,
    val canManage: Boolean = false,
    val noAccess: Boolean = false,
    val rows: List<SkuRow> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val actionError: String? = null,
)

/**
 * Live inventory list + edit/archive (test-only UI). The ViewModel is the cache: it
 * holds the live product stream and the in-stock-serial stream (both Firestore snapshot
 * listeners via [ObserveInventoryUseCase]), **groups serials by `productId` in memory**
 * for each SKU's stock count, and runs search client-side — no re-fetch (`/kmp-arch`).
 *
 * Mutations (price edit, archive, unit status/archive) go through the shared use cases,
 * which enforce the `inventory` MANAGE gate; the snapshot listeners refresh the UI.
 */
class InventoryListViewModel(application: Application) : AndroidViewModel(application) {

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

        val inv = BackendInventoryRepository(getApplication(), config)
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            runCatching { block(current) }
                .onFailure { e -> _uiState.update { it.copy(actionError = e.message ?: "Action failed") } }
            // Snapshot listeners refresh the list/counts.
        }
    }

    fun clearActionError() = _uiState.update { it.copy(actionError = null) }

    private fun recompute() {
        val query = _uiState.value.query.trim()
        val countByProduct = inStock.groupingBy { it.productId }.eachCount()
        val rows = products
            .asSequence()
            .filter { product -> matches(product, query) }
            .map { SkuRow(it, countByProduct[it.productId] ?: 0) }
            .sortedBy { it.product.attributes[AttributeType.BRAND]?.name?.lowercase() ?: "" }
            .toList()
        _uiState.update { it.copy(rows = rows) }
    }

    /** Client-side search over brand/model/color/carrier names and the SKU's in-stock IMEIs. */
    private fun matches(product: Product, query: String): Boolean {
        if (query.isBlank()) return true
        val attrHit = product.attributes.values.any { it.name.contains(query, ignoreCase = true) }
        if (attrHit) return true
        return inStock.any { it.productId == product.productId && it.imei.contains(query, ignoreCase = true) }
    }
}
