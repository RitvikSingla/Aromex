package com.humblesolutions.aromex.model

/**
 * The kinds of managed-vocabulary values ([AttributeValue]) that describe a product.
 *
 * Five of these (BRAND, MODEL, CAPACITY, COLOR, CARRIER) define a phone SKU and form
 * its [com.humblesolutions.aromex.util.SkuKey]; LOCATION is a **per-unit** attribute
 * (a unit's storage location), not part of the SKU. CAPACITY is a managed type (PO-
 * approved, #41 review) so `"128 GB"` vs `"128GB"` can't fragment SKUs.
 *
 * Unlike our other enums, the wire form is **lowercase** ([wire]) — it is the
 * Firestore `attributes/{id}.type` field *and* the key of `Product.attributes`.
 */
enum class AttributeType(val wire: String) {
    BRAND("brand"),
    MODEL("model"),
    CAPACITY("capacity"),
    COLOR("color"),
    CARRIER("carrier"),
    LOCATION("location");

    companion object {
        /** The five attributes that define a SKU (their [SkuKey] signature), in canonical order. */
        val SKU_DEFINING: List<AttributeType> = listOf(BRAND, MODEL, CAPACITY, COLOR, CARRIER)

        /** Parses a stored (lowercase) type; returns null for anything unrecognised. */
        fun fromWire(value: String): AttributeType? =
            entries.firstOrNull { it.wire == value.trim() }
    }
}
