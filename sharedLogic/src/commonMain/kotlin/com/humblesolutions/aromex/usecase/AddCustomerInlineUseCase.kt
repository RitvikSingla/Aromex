package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository

/**
 * Creates a party from a name alone, tagged CUSTOMER — the "add new" path of the
 * customer dropdown at Sales checkout (ticket #63), mirroring
 * [AddSupplierInlineUseCase]'s role in the Add-Inventory purchase dialog.
 *
 * Unlike [SaveEntityUseCase] (the full party form) this deliberately allows **no phone**:
 * a cashier may ring up a customer they don't have contact details for yet, and the
 * party can be fleshed out later on the Entities screen. Gated on `profiles` MANAGE —
 * it creates a party — so the checkout dropdown only offers add-new when the user can
 * manage profiles.
 *
 * The entity is written PENDING; the existing `onEntityWrite` Cloud Function creates the
 * HL customer, unchanged.
 */
class AddCustomerInlineUseCase(
    private val repository: EntityRepository,
) {
    suspend fun execute(session: UserSession, name: String): String {
        if (session.permissions.profiles != PermissionLevel.MANAGE) {
            throw PermissionDeniedException("profiles")
        }
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Customer name is required" }
        return repository.createEntity(
            EntityInput(
                name = trimmed,
                phones = emptyList(),
                roles = setOf(EntityRole.CUSTOMER),
            ),
        )
    }
}
