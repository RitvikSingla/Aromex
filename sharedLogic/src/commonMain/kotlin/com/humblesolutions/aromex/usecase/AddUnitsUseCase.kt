package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository

/**
 * Adds units to an existing SKU (MANAGE on `inventory`) — the same in-stock IMEI
 * transaction as [AddStockUseCase], without creating a new product.
 */
class AddUnitsUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(session: UserSession, productId: String, units: List<NewUnit>): List<String> {
        requireInventoryManage(session)
        require(productId.isNotBlank()) { "productId is required" }
        validateUnitsBatch(units)
        return repository.addUnits(productId, units)
    }
}
