package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.ProductEdits
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository
import com.humblesolutions.aromex.util.Money

/**
 * Edits a SKU's **default selling price** (MANAGE on `inventory`) — the only mutable
 * product field. The SKU-defining attributes are immutable identity (they form the
 * `skuKey`), so there is deliberately no attribute-edit path.
 */
class UpdateProductUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(session: UserSession, productId: String, edits: ProductEdits) {
        requireInventoryManage(session)
        require(productId.isNotBlank()) { "productId is required" }
        edits.defaultSellingPrice?.let {
            require(Money.isValidPositiveDecimal(it)) { "Default selling price must be a positive decimal" }
        }
        repository.updateProduct(productId, edits)
    }
}
