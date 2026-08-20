package com.humblesolutions.aromex.model

/**
 * One row of a managed vocabulary (a brand, model, capacity, color, carrier, or
 * location) — the `attributes/{attributeId}` collection, discriminated by [type].
 * [parentId] wires a MODEL to its brand's [attributeId] (the model list is filtered
 * by the picked brand); it is null for the flat types.
 *
 * [name] is the display form; [nameKey] is its case-folded dedupe key
 * ([com.humblesolutions.aromex.util.AttributeName.matchKey]) — the repository dedupes on
 * `(type, parentId, nameKey)` so `"Apple"` and `"apple"` can never both exist.
 *
 * Read model — [attributeId] is stored inside the doc (== the doc key).
 */
data class AttributeValue(
    val attributeId: String = "",
    val type: AttributeType = AttributeType.BRAND,
    val name: String = "",
    val nameKey: String = "",
    val parentId: String? = null,
    val isActive: Boolean = true,
)
