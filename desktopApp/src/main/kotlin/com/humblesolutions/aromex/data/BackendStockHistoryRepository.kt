package com.humblesolutions.aromex.data

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2CredentialsWithRefresh
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Query
import com.google.cloud.Timestamp
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.BatchReversalStatus
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.StockBatch
import com.humblesolutions.aromex.model.StockBatchStatus
import com.humblesolutions.aromex.repository.StockHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Desktop [StockHistoryRepository] (ticket #106), Admin SDK.
 *
 * Reads `purchases/{id}` — one doc per Add-Inventory submission — and records a reversal request
 * on it. It never reverses anything itself: [requestReversal] writes the request fields and stops,
 * and the `onPurchaseWrite` Cloud Function does the work. The Admin SDK bypasses Firestore rules,
 * so a client-side ledger reversal would be a client-side promise about the books; the function
 * re-checks the requester is an admin and that the batch is still whole.
 */
class BackendStockHistoryRepository(
    private val broker: FirestoreTokenBroker,
    private val config: FirebaseClientConfig,
) : StockHistoryRepository {

    private val clientLock = Mutex()
    @Volatile private var cached: Firestore? = null

    private suspend fun firestore(): Firestore = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        clientLock.withLock {
            cached?.let { return@withLock it }
            val credentials = OAuth2CredentialsWithRefresh.newBuilder()
                .setAccessToken(brokeredAccessToken())
                .setRefreshHandler { runBlocking { brokeredAccessToken() } }
                .build()
            FirestoreOptions.newBuilder()
                .setProjectId(config.projectId)
                .setCredentials(credentials)
                .build()
                .service
                .also { cached = it }
        }
    }

    /** The credential, stamped with the expiry the gateway actually issued — never a guessed one. */
    private suspend fun brokeredAccessToken(): AccessToken {
        val token = broker.currentTokenWithExpiry()
        return AccessToken(token.value, Date(token.expiresAtMs))
    }

    override fun observeBatches(limit: Int, from: Long?, to: Long?): Flow<List<StockBatch>> = callbackFlow {
        val db = firestore()
        // A range and the ordering are on the same field, so this needs no composite index.
        var query: Query = db.collection(PURCHASES)
        from?.let { query = query.whereGreaterThanOrEqualTo("createdAt", Timestamp.of(Date(it))) }
        to?.let { query = query.whereLessThanOrEqualTo("createdAt", Timestamp.of(Date(it))) }
        val registration = query
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null -> trySend(snapshot.documents.mapNotNull { it.toBatch() })
                    else -> trySend(emptyList())
                }
            }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    override suspend fun loadBatchUnits(purchaseId: String): List<Serial> = withContext(Dispatchers.IO) {
        firestore().collection(SERIALS)
            .whereEqualTo("purchaseId", purchaseId)
            .get()
            .get()
            .documents
            .mapNotNull { it.toSerial() }
    }

    override suspend fun requestReversal(
        purchaseId: String,
        reason: String,
        requestedBy: String,
    ): Unit = withContext(Dispatchers.IO) {
        firestore().collection(PURCHASES).document(purchaseId).set(
            mapOf(
                // The Cloud Function edge-triggers on this timestamp changing, so a re-request
                // after a failure re-runs the (idempotent) worker rather than sitting inert.
                "reversalRequestedAt" to FieldValue.serverTimestamp(),
                "reversalRequestedBy" to requestedBy,
                "reversalReason" to reason,
                "reversalStatus" to BatchReversalStatus.PENDING.name,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            com.google.cloud.firestore.SetOptions.merge(),
        ).get()
        Unit
    }

    // ── mapping ─────────────────────────────────────────────────────────────

    /** Null when the row is unreadable — a malformed doc is skipped, never guessed at. */
    private fun DocumentSnapshot.toBatch(): StockBatch? {
        if (!exists()) return null
        return StockBatch(
            purchaseId = id,
            partyEntityId = getString("partyEntityId").orEmpty(),
            totalCost = getString("totalCost") ?: "0",
            cashPaid = getString("cashPaid") ?: "0",
            bankPaid = getString("bankPaid") ?: "0",
            // Absent on batches written before intake recorded it (ticket #106) — 0 then reads as
            // "unknown", which is exactly what blocks their reversal.
            unitCount = (get("unitCount") as? Number)?.toInt() ?: 0,
            // A just-written doc has no server timestamp yet; 0 sorts it last rather than crashing.
            createdAt = getDate("createdAt")?.time ?: 0L,
            createdBy = getString("createdBy").orEmpty(),
            createdByName = getString("createdByName"),
            syncStatus = HlSyncStatus.fromWire(getString("syncStatus").orEmpty()),
            status = StockBatchStatus.fromWire(getString("status")),
            reversalStatus = BatchReversalStatus.fromWire(getString("reversalStatus")),
            reversalReason = getString("reversalReason"),
            reversedAt = getDate("reversedAt")?.time,
            reversedByName = getString("reversedByName"),
            reversalError = getString("reversalError"),
        )
    }

    private fun DocumentSnapshot.toSerial(): Serial? {
        if (!exists()) return null
        val loc = get("location") as? Map<*, *>
        return Serial(
            serialId = getString("serialId") ?: id,
            productId = getString("productId").orEmpty(),
            imei = getString("imei").orEmpty(),
            cost = getString("cost") ?: "0",
            condition = Condition.fromWire(getString("condition") ?: "") ?: Condition.NEW,
            status = SerialStatus.fromWire(getString("status") ?: "") ?: SerialStatus.IN_STOCK,
            location = AttributeRef(
                attributeId = loc?.get("attributeId") as? String ?: "",
                name = loc?.get("name") as? String ?: "",
            ),
            isActive = getBoolean("isActive") ?: true,
            saleId = getString("saleId"),
            purchaseId = getString("purchaseId"),
        )
    }

    /**
     * Release the Admin-SDK client (a gRPC channel and its threads). Without this, every
     * sign-out/sign-in cycle strands one — they are per-ViewModel, so they accumulate.
     */
    fun close() {
        cached?.let { runCatching { it.close() } }
        cached = null
    }

    private companion object {
        const val PURCHASES = "purchases"
        const val SERIALS = "serials"
    }
}
