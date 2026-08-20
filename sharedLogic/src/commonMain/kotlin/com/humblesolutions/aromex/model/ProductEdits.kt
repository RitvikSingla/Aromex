package com.humblesolutions.aromex.model

/**
 * The editable fields of a SKU. **Only [defaultSellingPrice]** — the SKU-defining
 * attributes are immutable identity (editing one would change the `skuKey`, i.e. a
 * different SKU), so there is deliberately no attribute-edit path here.
 */
data class ProductEdits(
    val defaultSellingPrice: String? = null,
)
