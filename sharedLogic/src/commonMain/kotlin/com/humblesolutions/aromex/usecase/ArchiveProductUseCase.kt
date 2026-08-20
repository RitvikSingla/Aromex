package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository

/**
 * Soft-archives a SKU (MANAGE on `inventory`). Never a hard delete — a product may have
 * history (units, sales).
 */
class ArchiveProductUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(session: UserSession, productId: String) {
        requireInventoryManage(session)
        require(productId.isNotBlank()) { "productId is required" }
        repository.archiveProduct(productId)
    }
}
