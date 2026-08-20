package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityRepository
import com.humblesolutions.aromex.repository.HlTokenProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Builds a UserSession whose only interesting field is the `profiles` scope. */
internal fun sessionWith(profiles: PermissionLevel): UserSession = UserSession(
    uid = "u1",
    email = "u@test",
    displayName = "U",
    role = UserRole.MEMBER,
    permissions = Permissions(profiles = profiles),
    companyId = "c1",
    hlCompanyId = "hl1",
    currency = "CAD",
    isActive = true,
)

/** Records repository calls for use-case tests; no real backend. */
internal class FakeEntityRepository : EntityRepository {
    val created = mutableListOf<EntityInput>()
    val updated = mutableListOf<Pair<String, EntityInput>>()
    val taxNumberUpdates = mutableListOf<Pair<String, String?>>()
    val phoneUpdates = mutableListOf<Pair<String, List<String>>>()
    val archived = mutableListOf<String>()
    var nextId: String = "entity-1"
    val entities = MutableStateFlow<List<Entity>>(emptyList())

    var lastIncludeArchived: Boolean? = null
    override fun observeEntities(includeArchived: Boolean): Flow<List<Entity>> {
        lastIncludeArchived = includeArchived
        return entities
    }

    override suspend fun createEntity(input: EntityInput): String {
        created += input
        return nextId
    }

    override suspend fun updateEntity(id: String, input: EntityInput) {
        updated += id to input
    }

    override suspend fun updateTaxNumber(id: String, taxNumber: String?) {
        taxNumberUpdates += id to taxNumber
    }

    override suspend fun updatePhones(id: String, phones: List<String>) {
        phoneUpdates += id to phones
    }

    override suspend fun archiveEntity(id: String) {
        archived += id
    }
}

/** Token provider that counts invalidations and swaps the token on invalidate. */
internal class FakeTokenProvider(initial: String = "token-1") : HlTokenProvider {
    var token: String = initial
    var invalidateCount: Int = 0

    override suspend fun currentToken(): String = token

    override suspend fun invalidate() {
        invalidateCount++
        token = "token-fresh"
    }
}
