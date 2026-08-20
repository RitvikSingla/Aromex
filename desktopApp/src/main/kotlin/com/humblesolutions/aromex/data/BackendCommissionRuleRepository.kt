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
import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.CommissionRuleInput
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.repository.CommissionRuleRepository
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
 * Firestore-backed [CommissionRuleRepository] for Desktop, using the **Admin SDK**
 * (`google-cloud-firestore`) — the `commissionRules/{ruleId}` standing arrangements
 * (ticket #97). Firebase-only (a rule touches no Humble Ledger; it only decides what a later
 * intake will owe).
 *
 * ⚠️ Admin-SDK access bypasses Firestore rules; every write is reached only through the shared
 * commission-rule use cases, which gate on `role == ADMIN` — the sole permission line on Desktop.
 *
 * All calls hop to [Dispatchers.IO] — `google-cloud-firestore` is blocking gRPC.
 */
class BackendCommissionRuleRepository(
    private val broker: FirestoreTokenBroker,
    private val config: FirebaseClientConfig,
    private val uid: String,
) : CommissionRuleRepository {

    private val clientLock = Mutex()
    @Volatile private var cached: Firestore? = null

    private suspend fun firestore(): Firestore = withContext(Dispatchers.IO) {
        cached?.let { return@withContext it }
        clientLock.withLock {
            cached?.let { return@withLock it }
            // Seed with a current token, then let the Admin SDK re-broker via the handler
            // when it expires, so a long-lived rules listener survives the token's TTL.
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

    /**
     * The credential, stamped with the expiry the gateway actually issued.
     *
     * Never invent a lifetime here: the gateway's TTL tracks the underlying Google token and has
     * been seen anywhere from ~24 to ~60 minutes. A hardcoded guess meant that whenever the real
     * one was shorter, the SDK kept sending a dead token — and because it believed the token was
     * still good, it never called the refresh handler. That surfaced as UNAUTHENTICATED partway
     * through a session with nothing in the app having changed.
     */
    private suspend fun brokeredAccessToken(): AccessToken {
        val token = broker.currentTokenWithExpiry()
        return AccessToken(token.value, Date(token.expiresAtMs))
    }

    override fun observeRules(includeInactive: Boolean): Flow<List<CommissionRule>> = callbackFlow {
        val firestore = firestore()
        val query: Query = if (includeInactive) {
            firestore.collection(COLLECTION)
        } else {
            firestore.collection(COLLECTION).whereEqualTo("isActive", true)
        }
        val registration = query.addSnapshotListener(
            EventListener<QuerySnapshot> { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@EventListener
                }
                trySend(snapshot?.documents?.mapNotNull { it.toRule() }.orEmpty())
            },
        )
        awaitClose { registration.remove() }
    }

    override suspend fun saveRule(input: CommissionRuleInput): String = withContext(Dispatchers.IO) {
        val db = firestore()
        val editing = input.ruleId.isNotBlank()
        val ref = if (editing) db.collection(COLLECTION).document(input.ruleId)
        else db.collection(COLLECTION).document()
        // Editing keeps the same doc so earned commissions keep pointing at a live rule; a
        // create sets createdBy/createdAt, an edit leaves them and just bumps updatedAt.
        val base = mutableMapOf<String, Any?>(
            "ruleId" to ref.id,
            "locationAttributeId" to input.locationAttributeId,
            "payeeEntityId" to input.payeeEntityId,
            "rateKind" to input.rateKind.name,
            "rate" to input.rate, // decimal String — never a Double
            "isActive" to input.isActive,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        if (!editing) {
            base["createdBy"] = uid
            base["createdAt"] = FieldValue.serverTimestamp()
        }
        ref.set(base, com.google.cloud.firestore.SetOptions.merge()).get()
        ref.id
    }

    override suspend fun archiveRule(ruleId: String): Unit = withContext(Dispatchers.IO) {
        firestore().collection(COLLECTION).document(ruleId).update(
            mapOf("isActive" to false, "updatedAt" to FieldValue.serverTimestamp()),
        ).get()
    }

    /** Close the cached Admin-SDK Firestore client (releases its gRPC channel). */
    override fun close() {
        cached?.let { runCatching { it.close() } }
        cached = null
    }

    private fun DocumentSnapshot.toRule(): CommissionRule? {
        if (!exists()) return null
        val rateKind = RateKind.fromWire(getString("rateKind") ?: "") ?: return null
        return CommissionRule(
            ruleId = getString("ruleId") ?: id,
            locationAttributeId = getString("locationAttributeId").orEmpty(),
            payeeEntityId = getString("payeeEntityId").orEmpty(),
            rateKind = rateKind,
            rate = getString("rate") ?: "0",
            isActive = getBoolean("isActive") ?: true,
            createdBy = getString("createdBy"),
            createdAt = getTimestamp("createdAt")?.toDate()?.time,
            updatedAt = getTimestamp("updatedAt")?.toDate()?.time,
        )
    }

    private companion object {
        const val COLLECTION = "commissionRules"
    }
}
