package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.OpeningBalance
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository
import com.humblesolutions.aromex.util.Money

/**
 * Creates or updates a party. Enforces the `profiles` MANAGE scope (shared-logic
 * enforcement per PRD §7.2) and validates the input before it ever reaches the
 * repository. On create the repo writes the doc as PENDING; the server-side Cloud
 * Function does the HL customer create — this use case never touches HL.
 */
class SaveEntityUseCase(
    private val repository: EntityRepository,
) {
    /**
     * @param existingId null → create; non-null → update that entity.
     * @return the entity id (new id on create, [existingId] on update).
     */
    suspend fun execute(
        session: UserSession,
        input: EntityInput,
        existingId: String? = null,
    ): String {
        if (session.permissions.profiles != PermissionLevel.MANAGE) {
            throw PermissionDeniedException("profiles")
        }

        val name = input.name.trim()
        require(name.isNotBlank()) { "Entity name is required" }

        val phones = input.phones.map { it.trim() }.filter { it.isNotEmpty() }
        require(phones.isNotEmpty()) { "At least one phone number is required" }

        val email = input.email?.trim()?.takeIf { it.isNotEmpty() }
        if (email != null) {
            require(EMAIL.matches(email)) { "Invalid email address" }
        }

        input.opening?.let(::validateOpening)

        val normalized = input.copy(name = name, phones = phones, email = email)
        return if (existingId == null) {
            repository.createEntity(normalized)
        } else {
            repository.updateEntity(existingId, normalized)
            existingId
        }
    }

    private fun validateOpening(opening: OpeningBalance) {
        require(Money.isValidPositiveDecimal(opening.amount)) {
            "Opening balance must be a positive amount"
        }
        require(
            opening.direction == BalanceDirection.RECEIVABLE ||
                opening.direction == BalanceDirection.CREDIT,
        ) { "Opening balance direction must be RECEIVABLE or CREDIT" }
    }

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
