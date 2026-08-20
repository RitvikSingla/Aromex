package com.humblesolutions.aromex.util

import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType

/**
 * Builds the deterministic **SKU key** — the fingerprint that a [com.humblesolutions
 * .aromex.model.Product]'s id **is**. Joining the five SKU-defining attribute ids in a
 * fixed canonical order makes "add the same model again" recompute the *same* key, so
 * find-or-create is atomic and duplicate SKUs are impossible (Brief #41 PO decision #1).
 *
 * The result is **order-independent** (it uses [AttributeType.SKU_DEFINING]'s fixed
 * order, never the map's iteration order) and id-safe (attribute ids are alphanumeric).
 * LOCATION is excluded — it is a per-unit attribute, not part of the SKU.
 *
 * CAPACITY, COLOR and CARRIER are **optional** (ticket #101): a blank one contributes an
 * **empty segment**, never a dropped one. `brandId_modelId__colorId_carrierId` (blank capacity)
 * stays unambiguous because the separator holds the position. Filtering blanks out instead would
 * collide — a capacity-only unit and a colour-only unit would both produce `brandId_modelId_X`,
 * silently merging two different SKUs into one product. BRAND and MODEL stay required: they are
 * the identity a blank must never be allowed to degenerate.
 */
object SkuKey {
    private const val SEPARATOR = "_"
    private val REQUIRED = setOf(AttributeType.BRAND, AttributeType.MODEL)

    /**
     * @throws IllegalArgumentException if BRAND or MODEL is missing or blank. CAPACITY, COLOR
     * and CARRIER may be blank — each blank keeps its (empty) segment in the joined key.
     */
    fun build(attributes: Map<AttributeType, AttributeRef>): String =
        AttributeType.SKU_DEFINING.joinToString(SEPARATOR) { type ->
            val id = attributes[type]?.attributeId?.trim().orEmpty()
            if (type in REQUIRED) require(id.isNotBlank()) { "Missing SKU attribute: ${type.wire}" }
            id
        }
}
