package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The live inventory streams (VIEW or MANAGE on `inventory`): SKUs and in-stock units.
 * The ViewModel groups serials by `productId` for per-SKU stock counts and does
 * search/filter client-side (no re-fetch).
 */
class ObserveInventoryUseCase(
    private val repository: InventoryRepository,
) {
    /** Live SKUs; defaults to active only. @throws PermissionDeniedException without `inventory` access. */
    @Throws(PermissionDeniedException::class)
    fun observeProducts(session: UserSession, includeArchived: Boolean = false): Flow<List<Product>> {
        requireInventoryAccess(session)
        return repository.observeProducts(includeArchived).map { products ->
            // Repo already narrows to active when !includeArchived; this is a safety net.
            if (includeArchived) products else products.filter { it.isActive }
        }
    }

    /** Live in-stock units (backs stock counts + drill-in). @throws PermissionDeniedException without access. */
    @Throws(PermissionDeniedException::class)
    fun observeInStockSerials(session: UserSession): Flow<List<Serial>> {
        requireInventoryAccess(session)
        return repository.observeInStockSerials()
    }
}
