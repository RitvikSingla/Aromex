package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository

/**
 * Persists a buyer's phone number onto their contact from the Sales checkout — the "Save to
 * contact" action, the sibling of [SaveBuyerTaxNumberUseCase]. The number itself already rides on
 * the sale as a snapshot; this is the *optional* second step that makes it the contact's default
 * primary number for future sales.
 *
 * Editing a contact is a `profiles` act, so this enforces `profiles` MANAGE — the same gate as
 * [SaveEntityUseCase]. A sales-only cashier may still type a number for the current bill; they just
 * can't write it back (the UI hides the button for them).
 *
 * The caller passes the full [phones] list it wants stored: the checkout only edits the *primary*
 * (first) number, but computes the new list against the contact's cached numbers so any secondary
 * numbers are preserved. An empty list clears the contact's numbers.
 */
class SaveBuyerPhoneUseCase(
    private val repository: EntityRepository,
) {
    /**
     * @param entityId the named customer to update. (A walk-in has no contact — callers must not
     *   invoke this for the walk-in party; the UI never offers it there.)
     * @param phones the full list to store; blanks are dropped and each entry trimmed.
     */
    suspend fun execute(session: UserSession, entityId: String, phones: List<String>) {
        if (session.permissions.profiles != PermissionLevel.MANAGE) {
            throw PermissionDeniedException("profiles")
        }
        require(entityId.isNotBlank()) { "An entity id is required" }
        repository.updatePhones(entityId, phones.map { it.trim() }.filter { it.isNotEmpty() })
    }
}
