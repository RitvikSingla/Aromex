package com.humblesolutions.aromex.model

/**
 * A **SKU** — a kind of product that groups identical units. For phones (the v1
 * mode) a SKU is one unique combination of brand + model + capacity + color + carrier,
 * carried generically in [attributes] (never top-level "phone" columns — North star).
 *
 * [productId] **is** the deterministic `skuKey`
 * ([com.humblesolutions.aromex.util.SkuKey]) so find-or-create is atomic and two
 * cashiers can never create duplicate SKUs (Brief #41 PO decision #1). Because the id
 * is derived from the attributes, the attributes are **immutable identity** — only
 * [defaultSellingPrice] is editable (see `ProductEdits`). There is deliberately **no
 * stock-count field**: the count is computed by grouping in-stock [Serial]s client-side.
 */
data class Product(
    val productId: String = "",
    val trackingMode: TrackingMode = TrackingMode.SERIALIZED,
    val attributes: Map<AttributeType, AttributeRef> = emptyMap(),
    /** Default selling price (a decimal String, never float); overridable at sale. */
    val defaultSellingPrice: String = "0",
    /** False when soft-archived. We never hard-delete a SKU that has history. */
    val isActive: Boolean = true,
)
