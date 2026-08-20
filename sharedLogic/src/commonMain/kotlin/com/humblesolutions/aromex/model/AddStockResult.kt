package com.humblesolutions.aromex.model

/**
 * The outcome of adding stock: the SKU the units landed on ([productId] == the
 * `skuKey`, whether found or created) and the ids of the newly created [Serial]s.
 */
data class AddStockResult(
    val productId: String,
    val createdSerialIds: List<String>,
)
