package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository

/**
 * Creates a party from a name alone, tagged SUPPLIER — the "add new" path of the
 * bought-from dropdown in the Add-Inventory purchase dialog (ticket #58).
 *
 * Unlike [SaveEntityUseCase] (the full party form) this deliberately allows **no phone**:
 * a purchase may name a supplier you don't yet have contact details for, and the party
 * can be fleshed out later on the Entities screen. Gated on `profiles` MANAGE — it creates
 * a party — so the dialog only offers add-new when the user can manage profiles.
 *
 * The entity is written PENDING; the existing `onEntityWrite` Cloud Function creates the
 * HL customer, unchanged.
 */
class AddSupplierInlineUseCase(
    private val repository: EntityRepository,
) {
    suspend fun execute(session: UserSession, name: String): String {
        if (session.permissions.profiles != PermissionLevel.MANAGE) {
            throw PermissionDeniedException("profiles")
        }
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Supplier name is required" }
        return repository.createEntity(
            EntityInput(
                name = trimmed,
                phones = emptyList(),
                roles = setOf(EntityRole.SUPPLIER),
            ),
        )
    }
}
