package com.humblesolutions.aromex.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.repository.EntityRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Firestore-backed [EntityRepository] for the signed-in company.
 *
 * Writes only touch Firestore — [createEntity] records the doc as PENDING and the
 * server-side Cloud Function (ticket #27) does the HL customer create. The client
 * never calls HL to write. Roles persist UPPERCASE (== enum name); time fields are
 * Firestore server timestamps.
 */
class BackendEntityRepository(
    private val context: Context,
    private val config: FirebaseClientConfig,
) : EntityRepository {

    private val app get() = FirebaseAppFactory.get(context, config)
    private val db get() = Firebase.firestore(app)
    private fun currentUid(): String? = FirebaseAuth.getInstance(app).currentUser?.uid

    override fun observeEntities(includeArchived: Boolean): Flow<List<Entity>> = callbackFlow {
        // Narrow to active-only unless an archived view is requested — avoids
        // downloading archived parties in the common case. ObserveEntitiesUseCase
        // also filters isActive as a safety net.
        val query: Query = if (includeArchived) {
            db.collection(COLLECTION)
        } else {
            db.collection(COLLECTION).whereEqualTo("isActive", true)
        }
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toEntity() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    override suspend fun createEntity(input: EntityInput): String = withContext(Dispatchers.IO) {
        val ref = db.collection(COLLECTION).document()
        val data = mutableMapOf<String, Any?>(
            "name" to input.name,
            "phones" to input.phones,
            "email" to input.email,
            "address" to input.address,
            "roles" to input.roles.map { it.name },
            "notes" to input.notes,
            "taxNumber" to input.taxNumber,
            "isWalkIn" to false,
            "isActive" to true,
            "syncStatus" to HlSyncStatus.PENDING.name,
            "createdBy" to currentUid(),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        input.opening?.let { opening ->
            data["opening"] = mapOf(
                "amount" to opening.amount,
                "direction" to opening.direction.name,
            )
        }
        ref.set(data).await()
        ref.id
    }

    override suspend fun updateEntity(id: String, input: EntityInput): Unit = withContext(Dispatchers.IO) {
        // Profile fields only — HL sync fields + syncStatus are Cloud-Function-owned.
        db.collection(COLLECTION).document(id).update(
            mapOf(
                "name" to input.name,
                "phones" to input.phones,
                "email" to input.email,
                "address" to input.address,
                "roles" to input.roles.map { it.name },
                "notes" to input.notes,
                "taxNumber" to input.taxNumber,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    override suspend fun updateTaxNumber(id: String, taxNumber: String?): Unit = withContext(Dispatchers.IO) {
        db.collection(COLLECTION).document(id).update(
            mapOf("taxNumber" to taxNumber, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    override suspend fun updatePhones(id: String, phones: List<String>): Unit = withContext(Dispatchers.IO) {
        db.collection(COLLECTION).document(id).update(
            mapOf("phones" to phones, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    override suspend fun archiveEntity(id: String): Unit = withContext(Dispatchers.IO) {
        db.collection(COLLECTION).document(id).update(
            mapOf("isActive" to false, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    private fun DocumentSnapshot.toEntity(): Entity? {
        if (!exists()) return null
        val roles = (get("roles") as? List<*>)
            ?.filterIsInstance<String>()
            ?.mapNotNull { EntityRole.fromWire(it) }
            ?.toSet()
            ?: emptySet()
        return Entity(
            id = id,
            name = getString("name").orEmpty(),
            phones = (get("phones") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            email = getString("email"),
            address = getString("address"),
            roles = roles,
            notes = getString("notes"),
            taxNumber = getString("taxNumber"),
            isWalkIn = getBoolean("isWalkIn") ?: false,
            isActive = getBoolean("isActive") ?: true,
            hlCustomerId = getString("hlCustomerId"),
            hlAccountId = getString("hlAccountId"),
            syncStatus = HlSyncStatus.fromWire(getString("syncStatus") ?: HlSyncStatus.PENDING.name),
        )
    }

    private companion object {
        const val COLLECTION = "entities"
    }
}
