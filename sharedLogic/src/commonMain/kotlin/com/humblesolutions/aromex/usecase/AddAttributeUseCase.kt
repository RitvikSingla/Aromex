package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.AttributeRepository
import com.humblesolutions.aromex.util.AttributeName

/**
 * Adds a managed-vocabulary value inline (MANAGE on `inventory`). Normalizes the name for
 * display (trim + collapse internal whitespace); the repository then dedupes on its
 * **case-folded** key ([AttributeName.matchKey]), so `" iPhone  15 "`, `"iPhone 15"`, and
 * `"iphone 15"` all resolve to one value and can't fragment SKUs. A MODEL must name its
 * parent brand (the model list is filtered by brand).
 */
class AddAttributeUseCase(
    private val repository: AttributeRepository,
) {
    suspend fun execute(
        session: UserSession,
        type: AttributeType,
        name: String,
        parentId: String? = null,
    ): String {
        requireInventoryManage(session)
        val normalized = AttributeName.normalize(name)
        require(normalized.isNotBlank()) { "Attribute name is required" }
        if (type == AttributeType.MODEL) {
            require(!parentId.isNullOrBlank()) { "A model requires a parent brand" }
        }
        return repository.addAttribute(type, normalized, parentId)
    }
}
