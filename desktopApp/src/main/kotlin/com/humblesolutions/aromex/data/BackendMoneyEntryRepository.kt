package com.humblesolutions.aromex.data

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2CredentialsWithRefresh
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Query
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.MoneyAccountRef
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.model.MoneyEntryInput
import com.humblesolutions.aromex.repository.MoneyEntryRepository
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
 * Desktop [MoneyEntryRepository] (ticket #90), Admin SDK.
 *
 * Writes `moneyEntries/{id}` as `PENDING` and stops there — the `onMoneyEntryWrite` Cloud Function
 * is the only thing that talks to Humble Ledger. Nothing here computes or stores a balance, which
 * is the whole point: the legacy implementation kept a running total on each party and mutated it
 * from the client with a read taken outside any transaction, so two cashiers recording against the
 * same party at the same moment could silently lose one entry's money with nothing to detect it.
 *
 * The Admin SDK bypasses Firestore rules, so the permission gate lives in the use cases.
 */
class BackendMoneyEntryRepository(
    private val broker: FirestoreTokenBroker,
    private val config: FirebaseClientConfig,
    private val uid: String,
) : MoneyEntryRepository {

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

    override suspend fun recordEntry(input: MoneyEntryInput): String = withAuthRetry { db ->
        val ref = db.collection(MONEY_ENTRIES).document()
        ref.set(entryData(input, ref.id, reversesEntryId = null)).get()
        ref.id
    }

    /**
     * Runs [block] against Firestore and, if the backend rejects our credentials, throws the
     * cached token away and tries **once** more.
     *
     * The SDK only calls its refresh handler when *it* believes the token has expired. A token the
     * server rejects while the SDK still considers it valid — a revoked credential, a clock that
     * disagrees, a gateway that reissued — never triggers that path, so the operation just fails.
     * Re-brokering and retrying turns that into something the cashier never sees. One retry only:
     * if a freshly brokered token is also refused, the problem is real and hiding it would be worse.
     */
    private suspend fun <T> withAuthRetry(block: suspend (Firestore) -> T): T = withContext(Dispatchers.IO) {
        try {
            block(firestore())
        } catch (first: Exception) {
            if (!BackendErrors.isAuthFailure(BackendErrors.unwrap(first))) throw first
            broker.invalidate()
            clientLock.withLock { cached = null }
            block(firestore())
        }
    }

    /**
     * Creates the mirror entry and marks the original, **in one transaction**.
     *
     * Both halves together or neither: a reversal that landed without marking its original would
     * leave the UI offering Reverse again, and a second click would post the money back a second
     * time. The transaction also re-reads the original inside itself, so two simultaneous clicks
     * can't both pass the "not yet reversed" check.
     */
    override suspend fun reverseEntry(entryId: String): String = withAuthRetry { db ->
        val originalRef = db.collection(MONEY_ENTRIES).document(entryId)
        val reversalRef = db.collection(MONEY_ENTRIES).document()

        db.runTransaction { txn ->
            val snap = txn.get(originalRef).get()
            require(snap.exists()) { "That entry no longer exists" }
            val original = snap.toMoneyEntry()
            require(original.canReverse) {
                when {
                    !original.isSettled -> "This entry hasn't reached the ledger yet"
                    original.isReversed -> "This entry was already reversed"
                    else -> "A reversal can't itself be reversed"
                }
            }

            // The mirror: same amount, sides swapped. Posting the inverse rather than calling HL's
            // /transactions/{id}/reverse keeps every route working — see syncMoneyEntry.
            txn.set(
                reversalRef,
                entryData(
                    MoneyEntryInput(
                        from = original.to,
                        to = original.from,
                        amount = original.amount,
                        note = original.note,
                        entryDate = System.currentTimeMillis(),
                    ),
                    reversalRef.id,
                    reversesEntryId = entryId,
                ),
            )
            txn.update(
                originalRef,
                mapOf(
                    "reversedByEntryId" to reversalRef.id,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            null
        }.get()

        reversalRef.id
    }

    /**
     * Live feed, newest first. Ordered by the **accounting** date so a backdated entry files itself
     * where it belongs rather than jumping to the top; `createdAt` breaks ties within a day.
     */
    override fun observeRecentEntries(limit: Int): Flow<List<MoneyEntry>> = callbackFlow {
        val db = firestore()
        val registration = db.collection(MONEY_ENTRIES)
            .orderBy("entryDate", Query.Direction.DESCENDING)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null -> trySend(snapshot.documents.mapNotNull { it.toMoneyEntryOrNull() })
                    else -> trySend(emptyList())
                }
            }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    private fun entryData(
        input: MoneyEntryInput,
        entryId: String,
        reversesEntryId: String?,
    ): Map<String, Any?> = buildMap {
        put("entryId", entryId)
        put("from", accountData(input.from))
        put("to", accountData(input.to))
        put("amount", input.amount)
        put("note", input.note?.trim()?.takeIf { it.isNotEmpty() })
        put("entryDate", Date(input.entryDate))
        put("syncStatus", HlSyncStatus.PENDING.name)
        put("createdBy", uid)
        put("createdAt", FieldValue.serverTimestamp())
        put("updatedAt", FieldValue.serverTimestamp())
        if (reversesEntryId != null) put("reversesEntryId", reversesEntryId)
    }

    private fun accountData(ref: MoneyAccountRef): Map<String, Any?> =
        mapOf("kind" to ref.kind, "entityId" to ref.entityIdOrNull)

    /** Null when either side is unreadable — a malformed row is skipped, never guessed at. */
    private fun DocumentSnapshot.toMoneyEntryOrNull(): MoneyEntry? {
        val from = accountRef("from") ?: return null
        val to = accountRef("to") ?: return null
        val amount = getString("amount") ?: return null
        return MoneyEntry(
            entryId = id,
            from = from,
            to = to,
            amount = amount,
            note = getString("note"),
            entryDate = getDate("entryDate")?.time ?: 0L,
            createdBy = getString("createdBy"),
            createdAt = getDate("createdAt")?.time,
            syncStatus = HlSyncStatus.fromWire(getString("syncStatus").orEmpty()),
            hlTransactionId = getString("hlTransactionId"),
            hlSyncError = getString("hlSyncError"),
            reversedByEntryId = getString("reversedByEntryId"),
            reversesEntryId = getString("reversesEntryId"),
        )
    }

    /** Same mapping, but for a doc we've already established exists (inside the reverse txn). */
    private fun DocumentSnapshot.toMoneyEntry(): MoneyEntry =
        toMoneyEntryOrNull() ?: error("Money entry $id is malformed")

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.accountRef(field: String): MoneyAccountRef? {
        val map = get(field) as? Map<String, Any?> ?: return null
        return MoneyAccountRef.fromWire(map["kind"] as? String, map["entityId"] as? String)
    }

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

    /**
     * Release the Admin-SDK client (a gRPC channel and its threads). Without this, every
     * sign-out/sign-in cycle strands one — they are per-ViewModel, so they accumulate.
     */
    fun close() {
        cached?.let { runCatching { it.close() } }
        cached = null
    }

    private companion object {
        const val MONEY_ENTRIES = "moneyEntries"
    }
}
