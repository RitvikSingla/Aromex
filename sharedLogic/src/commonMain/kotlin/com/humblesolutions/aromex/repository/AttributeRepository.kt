package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import kotlinx.coroutines.flow.Flow

/**
 * The managed vocabularies — the `attributes/{attributeId}` collection (brand / model /
 * capacity / color / carrier / location). Firebase-only; per-platform impls in T3/T4.
 * Consistency here is what keeps SKU grouping reliable, so add-new is deduped.
 */
interface AttributeRepository {
    /**
     * Live stream of all vocabulary values across every [AttributeType]. Callers filter
     * by type (and, for models, by `parentId` = brand) **client-side**.
     */
    fun observeAttributes(): Flow<List<AttributeValue>>

    /**
     * Adds a vocabulary value (the "add-new inline" path), **deduped case-insensitively**:
     * compute `AttributeName.matchKey(name)` and, if a value of the same [type] (and same
     * [parentId]) already has that `nameKey`, return its id; otherwise create one — storing
     * both the display [name] and its `nameKey` — and return the new id. So `"Apple"` and
     * `"apple"` collapse to one row. [name] arrives already display-normalized (from
     * [com.humblesolutions.aromex.usecase.AddAttributeUseCase]); [parentId] is the brand's
     * id for `type == MODEL`, null otherwise.
     */
    suspend fun addAttribute(type: AttributeType, name: String, parentId: String?): String
}
