package com.humblesolutions.aromex.model

/**
 * One SKU's slice of an Add-Inventory batch, written atomically with the batch's single
 * [PurchaseInput] in one Firestore transaction (ticket #58 — see
 * [com.humblesolutions.aromex.repository.InventoryRepository.addStockBatchWithPurchase]).
 *
 * @property skuKey the deterministic `products/{skuKey}` doc id (its attributes' key).
 * @property product the SKU to **find-or-create**: non-null when this may be a new SKU
 *   (find-or-create), `null` on the add-units-to-an-existing-SKU path (the product doc
 *   already exists and is never rewritten).
 * @property units the serialized units to create under [skuKey].
 */
data class StockBatchGroup(
    val skuKey: String,
    val product: NewProduct?,
    val units: List<NewUnit>,
)
