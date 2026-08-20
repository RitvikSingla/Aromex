package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityInput
import kotlinx.coroutines.flow.Flow

/**
 * The operational store for parties — the client's Firebase (Firestore).
 * Implementations are per-platform (Android/iOS native SDK; Desktop Admin SDK).
 *
 * Writes only touch Firebase: [createEntity] records the doc with
 * `syncStatus = PENDING`; the server-side Cloud Function (ticket #27) performs the
 * HL customer create and flips it to SYNCED. The client never calls HL to write.
 *
 * [observeEntities] is the one **live** read (a Firestore snapshot listener) — it
 * is what lets the list reflect PENDING → SYNCED without a manual refresh. All
 * other methods are `suspend`.
 */
interface EntityRepository {
    /**
     * Live stream of the company's entities (a Firestore snapshot listener).
     * When [includeArchived] is false (the default surface) the query is narrowed to
     * `isActive == true` so archived parties aren't downloaded; pass true to stream
     * all parties (for an archived view). [ObserveEntitiesUseCase] also applies the
     * `isActive` filter as a safety net.
     */
    fun observeEntities(includeArchived: Boolean): Flow<List<Entity>>

    /** Writes a new entity as PENDING and returns its id (== the HL externalId). */
    suspend fun createEntity(input: EntityInput): String

    /** Updates an existing entity's profile fields. */
    suspend fun updateEntity(id: String, input: EntityInput)

    /**
     * Targeted single-field update of a party's [taxNumber] (ticket #106 follow-up) — used by the
     * "Save to contact" action at checkout, which persists an edited tax number without touching any
     * other profile field (and without re-validating name/phone the way [updateEntity] does). A
     * blank/null clears it.
     */
    suspend fun updateTaxNumber(id: String, taxNumber: String?)

    /**
     * Targeted single-field update of a party's [phones] list — the sibling of [updateTaxNumber]
     * for the "Save to contact" phone action at checkout. The caller passes the full list it wants
     * stored (the ViewModel edits only the primary number but preserves any others), so this is a
     * plain overwrite of `phones` with no re-validation of the rest of the profile. An empty list
     * clears all numbers.
     */
    suspend fun updatePhones(id: String, phones: List<String>)

    /** Soft-archives an entity (isActive = false). We never hard-delete. */
    suspend fun archiveEntity(id: String)
}
