package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.AddStockResult
import com.humblesolutions.aromex.model.NewProduct
import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.SkuKey

/**
 * Adds stock (MANAGE on `inventory`): find-or-create the SKU for the given attributes
 * and add its units. Builds the deterministic `skuKey` (which also validates that all
 * five SKU-defining attributes are present) and delegates the atomic write — including
 * the in-stock IMEI guard — to the repository transaction.
 */
class AddStockUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(session: UserSession, product: NewProduct, units: List<NewUnit>): AddStockResult {
        requireInventoryManage(session)
        // Selling price is optional (ticket #101) — set later at sale time. Only validated when
        // the user supplied one; blank means "not priced yet".
        if (product.defaultSellingPrice.isNotBlank()) {
            require(Money.isValidPositiveDecimal(product.defaultSellingPrice)) {
                "Default selling price must be a positive decimal"
            }
        }
        validateUnitsBatch(units)
        val skuKey = SkuKey.build(product.attributes) // throws if a SKU attribute is missing
        return repository.addStock(skuKey, product, units)
    }
}
