package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository

/**
 * Soft-archives a party (MANAGE on `profiles`). Never a hard delete — the ledger is
 * immutable and a party may have history. The reserved Walk-in Customer cannot be
 * archived.
 */
class ArchiveEntityUseCase(
    private val repository: EntityRepository,
) {
    suspend fun execute(session: UserSession, entity: Entity) {
        if (session.permissions.profiles != PermissionLevel.MANAGE) {
            throw PermissionDeniedException("profiles")
        }
        require(!entity.isWalkIn) { "The Walk-in Customer cannot be archived" }
        repository.archiveEntity(entity.id)
    }
}
