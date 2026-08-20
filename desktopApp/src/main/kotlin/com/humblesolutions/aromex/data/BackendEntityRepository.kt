package com.humblesolutions.aromex.data

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2Credentials
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.EventListener
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Query
import com.google.cloud.firestore.QuerySnapshot
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Firestore-backed [EntityRepository] for Desktop, using the **Admin SDK**
 * (`google-cloud-firestore`) authenticated with a short-lived, datastore-scoped
 * OAuth token brokered by the gateway ([FirestoreTokenBroker]) — the SA key never
 * reaches the device.
 *
 * ⚠️ Because this authenticates as the company's service account, these calls
 * **bypass Firestore security rules**. Permission enforcement therefore lives
 * entirely in the shared use cases (SaveEntityUseCase/ArchiveEntityUseCase) — every
 * write must go through them. Writes still trigger the T2 `onEntityWrite` Cloud
 * Function, so PENDING→SYNCED works exactly as on mobile.
 *
 * All calls hop to [Dispatchers.IO] — `google-cloud-firestore` is blocking gRPC.
 */
class BackendEntityRepository(
    private val broker: FirestoreTokenBroker,
    private val config: FirebaseClientConfig,
    private val uid: String,
) : EntityRepository {

    private val clientLock = Mutex()
    @Volatile private var cached: Pair<String, Firestore>? = null

    private suspend fun firestore(): Firestore = withContext(Dispatchers.IO) {
        val token = broker.currentToken()
        cached?.let { if (it.first == token) return@withContext it.second }
        clientLock.withLock {
            cached?.let { if (it.first == token) return@withLock it.second }
            cached?.second?.let { runCatching { it.close() } }
            val credentials = OAuth2Credentials.create(
                AccessToken(token, Date(System.currentTimeMillis() + 55 * 60 * 1000L)),
            )
            val firestore = FirestoreOptions.newBuilder()
                .setProjectId(config.projectId)
                .setCredentials(credentials)
                .build()
                .service
            cached = token to firestore
            firestore
        }
    }

    override fun observeEntities(includeArchived: Boolean): Flow<List<Entity>> = callbackFlow {
        val firestore = firestore()
        // Active-only unless an archived view is requested (parity with mobile).
        // ObserveEntitiesUseCase also filters isActive as a safety net.
        val query: Query = if (includeArchived) {
            firestore.collection(COLLECTION)
        } else {
            firestore.collection(COLLECTION).whereEqualTo("isActive", true)
        }
        val registration = query
            .addSnapshotListener(
                EventListener<QuerySnapshot> { snapshot, error ->
                    if (error != null) {
                        close(error)
                        return@EventListener
                    }
                    trySend(snapshot?.documents?.mapNotNull { it.toEntity() }.orEmpty())
                },
            )
        awaitClose { registration.remove() }
    }

    override suspend fun createEntity(input: EntityInput): String = withContext(Dispatchers.IO) {
        val ref = firestore().collection(COLLECTION).document()
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
            "createdBy" to uid,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        input.opening?.let { opening ->
            data["opening"] = mapOf("amount" to opening.amount, "direction" to opening.direction.name)
        }
        ref.set(data).get()
        ref.id
    }

    override suspend fun updateEntity(id: String, input: EntityInput): Unit = withContext(Dispatchers.IO) {
        // Profile fields only — the CF owns the HL sync fields + syncStatus.
        firestore().collection(COLLECTION).document(id).update(
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
        ).get()
    }

    override suspend fun updateTaxNumber(id: String, taxNumber: String?): Unit = withContext(Dispatchers.IO) {
        firestore().collection(COLLECTION).document(id).update(
            mapOf("taxNumber" to taxNumber, "updatedAt" to FieldValue.serverTimestamp()),
        ).get()
    }

    override suspend fun updatePhones(id: String, phones: List<String>): Unit = withContext(Dispatchers.IO) {
        firestore().collection(COLLECTION).document(id).update(
            mapOf("phones" to phones, "updatedAt" to FieldValue.serverTimestamp()),
        ).get()
    }

    override suspend fun archiveEntity(id: String): Unit = withContext(Dispatchers.IO) {
        firestore().collection(COLLECTION).document(id).update(
            mapOf("isActive" to false, "updatedAt" to FieldValue.serverTimestamp()),
        ).get()
    }

    /**
     * Close the cached Admin-SDK Firestore client (releases its gRPC channel).
     * Call on ViewModel teardown — google-cloud-firestore clients are not GC'd
     * cheaply, so a new one per screen-open would otherwise leak a channel each time.
     */
    fun close() {
        cached?.second?.let { runCatching { it.close() } }
        cached = null
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
