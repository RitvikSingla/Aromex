package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The live stream of the company's parties (VIEW or MANAGE on `profiles`).
 * Defaults to active entities only; pass [includeArchived] to see soft-archived ones.
 * The ViewModel merges HL balances (via [GetEntityBalancesUseCase]) and does
 * search/filter client-side.
 */
class ObserveEntitiesUseCase(
    private val repository: EntityRepository,
) {
    /**
     * @throws PermissionDeniedException when the user has no `profiles` access.
     * Annotated `@Throws` so Kotlin/Native exports it as a *throwing* Swift function
     * (iOS can `try`/`catch` the denial instead of crashing) — this is what lets iOS
     * drop its duplicated permission gate and rely on this shared check. No effect on
     * Android/Desktop callers. (Ticket #34)
     */
    @Throws(PermissionDeniedException::class)
    fun execute(session: UserSession, includeArchived: Boolean = false): Flow<List<Entity>> {
        if (session.permissions.profiles == PermissionLevel.NONE) {
            throw PermissionDeniedException("profiles")
        }
        return repository.observeEntities(includeArchived).map { entities ->
            // Repo already narrows to active when !includeArchived; this filter is a
            // safety net so the rule holds even if a platform query returns extra rows.
            if (includeArchived) entities else entities.filter { it.isActive }
        }
    }
}
