package com.humblesolutions.aromex.model

/**
 * The SKU-level fields a user supplies when adding stock. Carries the **5 SKU-defining
 * attributes** (brand/model/capacity/color/carrier) — **location is NOT here** (it's a
 * per-unit field on each [NewUnit]). The `skuKey` is derived from these attributes.
 *
 * [defaultSellingPrice] is **optional** (ticket #101) — it may be set later at sale time, so a
 * blank means "not priced yet". When non-blank it must be a positive decimal (validated by the
 * use cases); when blank it is stored as such and treated as unpriced downstream.
 */
data class NewProduct(
    val attributes: Map<AttributeType, AttributeRef>,
    val defaultSellingPrice: String = "",
    val trackingMode: TrackingMode = TrackingMode.SERIALIZED,
)
