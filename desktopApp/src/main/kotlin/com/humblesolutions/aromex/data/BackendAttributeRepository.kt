package com.humblesolutions.aromex.data

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2CredentialsWithRefresh
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.EventListener
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QuerySnapshot
import com.google.cloud.firestore.Transaction
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.repository.AttributeRepository
import com.humblesolutions.aromex.util.AttributeName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Firestore-backed [AttributeRepository] for Desktop, using the **Admin SDK**
 * (`google-cloud-firestore`) — the `attributes/{attributeId}` managed vocabularies
 * (brand/model/capacity/color/carrier/location). Firebase-only.
 *
 * ⚠️ Admin-SDK access bypasses Firestore rules; `addAttribute` is reached only through the
 * shared `AddAttributeUseCase`, which is the sole permission gate on Desktop.
 *
 * `addAttribute` is the **add-new-inline** path: it dedupes case-insensitively on
 * `(type, parentId, nameKey)` so `"Apple"`/`"apple"` collapse to one row — the guarantee
 * under the picker that keeps SKU grouping reliable. `nameKey` is computed identically on
 * every platform via the shared [AttributeName.matchKey].
 *
 * All calls hop to [Dispatchers.IO] — `google-cloud-firestore` is blocking gRPC.
 */
class BackendAttributeRepository(
    private val broker: FirestoreTokenBroker,
    private val config: FirebaseClientConfig,
    private val uid: String,
) : AttributeRepository {

    private val clientLock = Mutex()
    @Volatile private var cached: Firestore? = null

    private suspend fun firestore(): Firestore = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        clientLock.withLock {
            cached?.let { return@withLock it }
            // Seed with a current token, then let the Admin SDK re-broker via the handler
            // when it expires, so a long-lived attributes listener survives the ~1h token TTL.
            val credentials = OAuth2CredentialsWithRefresh.newBuilder()
                .setAccessToken(brokeredAccessToken())
                .setRefreshHandler { runBlocking { brokeredAccessToken() } }
                .build()
            val firestore = FirestoreOptions.newBuilder()
                .setProjectId(config.projectId)
                .setCredentials(credentials)
                .build()
                .service
            cached = firestore
            firestore
        }
    }

    /** Nominal token lifetime hint so the Admin SDK refreshes ahead of the real ~1h expiry. */
    /**
     * The credential, stamped with the expiry the gateway actually issued.
     *
     * Never invent a lifetime here: the gateway's TTL tracks the underlying Google token and has
     * been seen anywhere from ~24 to ~60 minutes. Guessing 50 meant that whenever the real one was
     * shorter, the SDK kept sending a dead token — and because it believed the token was still
     * good, it never called the refresh handler. That surfaced as UNAUTHENTICATED partway through
     * a session, with nothing in the app having changed.
     */
    private suspend fun brokeredAccessToken(): AccessToken {
        val token = broker.currentTokenWithExpiry()
        return AccessToken(token.value, Date(token.expiresAtMs))
    }

    override fun observeAttributes(): Flow<List<AttributeValue>> = callbackFlow {
        // Whole active vocabulary; callers filter by type (and model→brand parentId) client-side.
        val firestore = firestore()
        val registration = firestore.collection(COLLECTION)
            .whereEqualTo("isActive", true)
            .addSnapshotListener(
                EventListener<QuerySnapshot> { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@EventListener
                    }
                    trySend(snapshot?.documents?.mapNotNull { it.toAttribute() }.orEmpty())
                },
            )
        awaitClose { registration.remove() }
    }

    override suspend fun addAttribute(
        type: AttributeType,
        name: String,
        parentId: String?,
    ): String = withContext(Dispatchers.IO) {
        val db = firestore()
        val nameKey = AttributeName.matchKey(name)
        // Dedupe query: same type (+ parent for models) + same case-folded key.
        var query: Query = db.collection(COLLECTION)
            .whereEqualTo("type", type.wire)
            .whereEqualTo("nameKey", nameKey)
        query = if (parentId == null) {
            query.whereEqualTo("parentId", null)
        } else {
            query.whereEqualTo("parentId", parentId)
        }
        val existing = query.limit(1).get().get().documents.firstOrNull()
        if (existing != null) return@withContext existing.id

        // Deterministic document ID: (type, parentId, nameKey) tuple → no duplicate creates under concurrency.
        val deterministicId = "${type.wire}_${parentId.orEmpty()}_$nameKey"
        val ref = db.collection(COLLECTION).document(deterministicId)
        db.runTransaction(Transaction.Function<Void?> { txn ->
            val snap = txn.get(ref).get()
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
                        "createdBy" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }
            null
        }).get()
        deterministicId
    }

    /**
     * Close the cached Admin-SDK Firestore client (releases its gRPC channel). Call on
     * ViewModel teardown.
     */
    fun close() {
        cached?.let { runCatching { it.close() } }
        cached = null
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
