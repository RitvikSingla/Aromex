package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository

/**
 * Persists a buyer's tax/GST number onto their contact from the Sales checkout — the "Save to
 * contact" action (ticket #106 follow-up). The number itself already rides on the sale as a snapshot;
 * this is the *optional* second step that makes it the contact's new default for future sales.
 *
 * Editing a contact is a `profiles` act, so this enforces `profiles` MANAGE — the same gate as
 * [SaveEntityUseCase]. A sales-only cashier may still type a number for the current bill; they just
 * can't write it back (the UI hides the button for them). A blank number clears the contact's value.
 */
class SaveBuyerTaxNumberUseCase(
    private val repository: EntityRepository,
) {
    /**
     * @param entityId the named customer to update. (A walk-in has no contact — callers must not
     *   invoke this for the walk-in party; the UI never offers it there.)
     */
    suspend fun execute(session: UserSession, entityId: String, taxNumber: String?) {
        if (session.permissions.profiles != PermissionLevel.MANAGE) {
            throw PermissionDeniedException("profiles")
        }
        require(entityId.isNotBlank()) { "An entity id is required" }
        repository.updateTaxNumber(entityId, taxNumber?.trim()?.ifEmpty { null })
    }
}
