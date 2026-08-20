package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Live party list for the Add-Inventory purchase dialog's "bought from" dropdown
 * (ticket #58).
 *
 * Unlike [ObserveEntitiesUseCase] (which gates on the `profiles` scope — the Parties
 * screen), this is gated on **`inventory`**: picking who a batch was bought from is part
 * of the inventory flow, so anyone who can add stock can see the party list here even
 * without party-management rights. Creating a *new* party inline still requires
 * `profiles` MANAGE (see [AddSupplierInlineUseCase]).
 *
 * Emits active parties only (a live Firestore snapshot stream).
 */
class ObservePartiesForPurchaseUseCase(
    private val repository: EntityRepository,
) {
    fun execute(session: UserSession): Flow<List<Entity>> {
        requireInventoryAccess(session)
        return repository.observeEntities(includeArchived = false)
            .map { entities -> entities.filter { it.isActive } }
    }
}
