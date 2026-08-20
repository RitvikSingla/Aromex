package com.humblesolutions.aromex.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.repository.AttributeRepository
import com.humblesolutions.aromex.util.AttributeName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Firestore-backed [AttributeRepository] — the `attributes/{attributeId}` managed
 * vocabularies (brand/model/capacity/color/carrier/location). Firebase-only.
 *
 * `addAttribute` is the **add-new-inline** path: it dedupes case-insensitively on
 * `(type, parentId, nameKey)` so `"Apple"`/`"apple"` collapse to one row — the guarantee
 * under the picker that keeps SKU grouping reliable. `nameKey` is computed identically on
 * every platform via the shared [AttributeName.matchKey].
 */
class BackendAttributeRepository(
    private val context: Context,
    private val config: FirebaseClientConfig,
) : AttributeRepository {

    private val app get() = FirebaseAppFactory.get(context, config)
    private val db get() = Firebase.firestore(app)
    private fun currentUid(): String? = FirebaseAuth.getInstance(app).currentUser?.uid

    override fun observeAttributes(): Flow<List<AttributeValue>> = callbackFlow {
        // Whole active vocabulary; callers filter by type (and model→brand parentId) client-side.
        val query = db.collection(COLLECTION).whereEqualTo("isActive", true)
        val registration = query.addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            trySend(snapshot?.documents?.mapNotNull { it.toAttribute() }.orEmpty())
        }
        awaitClose { registration.remove() }
    }

    override suspend fun addAttribute(
        type: AttributeType,
        name: String,
        parentId: String?,
    ): String = withContext(Dispatchers.IO) {
        val nameKey = AttributeName.matchKey(name)
        // Dedupe query: same type (+ parent for models) + same case-folded key.
        var query = db.collection(COLLECTION)
            .whereEqualTo("type", type.wire)
            .whereEqualTo("nameKey", nameKey)
        query = if (parentId == null) {
            query.whereEqualTo("parentId", null)
        } else {
            query.whereEqualTo("parentId", parentId)
        }
        val existing = query.limit(1).get().await().documents.firstOrNull()
        if (existing != null) return@withContext existing.id

        // Deterministic document ID: (type, parentId, nameKey) tuple → no duplicate creates under concurrency.
        val deterministicId = "${type.wire}_${parentId.orEmpty()}_$nameKey"
        val ref = db.collection(COLLECTION).document(deterministicId)
        db.runTransaction { txn ->
            val snap = txn.get(ref)
            if (!snap.exists()) {
                txn.set(
                    ref,
                    mapOf(
                        "attributeId" to deterministicId,
                        "type" to type.wire,
                        "name" to name,
                        "nameKey" to nameKey,
                        "parentId" to parentId,
                        "isActive" to true,
                        "createdBy" to currentUid(),
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
        }.await()
        deterministicId
    }

    private fun DocumentSnapshot.toAttribute(): AttributeValue? {
        if (!exists()) return null
        val type = AttributeType.fromWire(getString("type") ?: "") ?: return null
        return AttributeValue(
            attributeId = getString("attributeId") ?: id,
            type = type,
            name = getString("name").orEmpty(),
            nameKey = getString("nameKey").orEmpty(),
            parentId = getString("parentId"),
            isActive = getBoolean("isActive") ?: true,
        )
    }

    private companion object {
        const val COLLECTION = "attributes"
    }
}
