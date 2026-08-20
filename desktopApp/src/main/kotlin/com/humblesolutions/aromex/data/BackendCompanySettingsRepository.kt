package com.humblesolutions.aromex.data

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2CredentialsWithRefresh
import com.google.cloud.firestore.DocumentSnapshot
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.Query
import com.humblesolutions.aromex.model.CompanyDetails
import com.humblesolutions.aromex.model.CompanySettingsChange
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.repository.CompanySettingsRepository
import com.humblesolutions.aromex.usecase.SettingsAudit
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
 * Desktop [CompanySettingsRepository], Admin SDK.
 *
 * The profile is **observed**, not fetched. A tax rate captured at sign-in goes stale the moment an
 * admin changes it, and a till charging last week's GST looks exactly like one charging this week's.
 *
 * Every write lands in a **single transaction** with the change-log entries it produces. A rate that
 * changed with no record of who changed it is precisely the gap the log exists to close, and two
 * separate writes would eventually produce one.
 */
class BackendCompanySettingsRepository(
    private val broker: FirestoreTokenBroker,
    private val config: FirebaseClientConfig,
) : CompanySettingsRepository {

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

    override fun observeProfile(): Flow<CompanyProfile> = callbackFlow {
        val db = firestore()
        val registration = db.collection(SETTINGS).document(PROFILE)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null && snapshot.exists() -> trySend(snapshot.toProfile())
                    // An absent profile means provisioning hasn't finished. Emitting a default
                    // would quietly say "no tax"; staying silent lets the caller keep showing
                    // whatever it already had.
                    else -> Unit
                }
            }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    override suspend fun updateTax(
        config: TaxConfig,
        changedBy: String,
        changedByName: String?,
    ) = withContext(Dispatchers.IO) {
        val db = firestore()
        val profileRef = db.collection(SETTINGS).document(PROFILE)

        db.runTransaction { txn ->
            val before = txn.get(profileRef).get().toProfile()
            val changes = SettingsAudit.diff(
                SettingsAudit.taxFields(before.tax),
                SettingsAudit.taxFields(config),
            )
            // Nothing actually changed — don't write, and don't log. Opening the form and pressing
            // Save should leave no trace.
            if (changes.isEmpty()) return@runTransaction null

            txn.set(
                profileRef,
                mapOf(
                    // Rates are written as decimal STRINGS. Every platform reads them through a
                    // helper that accepts either, and a string can't acquire a float's rounding.
                    "tax" to mapOf(
                        "gstEnabled" to config.gstEnabled,
                        "gstRate" to config.gstRate,
                        "pstEnabled" to config.pstEnabled,
                        "pstRate" to config.pstRate,
                        "isHST" to config.isHST,
                    ),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                com.google.cloud.firestore.SetOptions.merge(),
            )
            writeChanges(db, txn, changes, changedBy, changedByName)
            null
        }.get()
        Unit
    }

    override suspend fun updateDetails(
        details: CompanyDetails,
        changedBy: String,
        changedByName: String?,
    ) = withContext(Dispatchers.IO) {
        val db = firestore()
        val profileRef = db.collection(SETTINGS).document(PROFILE)

        db.runTransaction { txn ->
            val before = txn.get(profileRef).get().toProfile()
            val changes = SettingsAudit.diff(
                SettingsAudit.detailFields(before.toDetails()),
                SettingsAudit.detailFields(details),
            )
            if (changes.isEmpty()) return@runTransaction null

            txn.set(
                profileRef,
                mapOf(
                    "companyName" to details.companyName,
                    "legalName" to details.legalName,
                    "taxNumber" to details.taxNumber,
                    "businessAddress" to details.businessAddress,
                    "contactEmail" to details.contactEmail,
                    "contactPhone" to details.contactPhone,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                com.google.cloud.firestore.SetOptions.merge(),
            )
            writeChanges(db, txn, changes, changedBy, changedByName)
            null
        }.get()
        Unit
    }

    /** One log entry per changed field, in the same transaction as the profile write. */
    private fun writeChanges(
        db: Firestore,
        txn: com.google.cloud.firestore.Transaction,
        changes: List<Triple<String, String?, String>>,
        changedBy: String,
        changedByName: String?,
    ) {
        changes.forEach { (field, old, new) ->
            val ref = db.collection(CHANGES).document()
            txn.set(
                ref,
                mapOf(
                    "changeId" to ref.id,
                    "field" to field,
                    "oldValue" to old,
                    "newValue" to new,
                    "changedBy" to changedBy,
                    "changedByName" to changedByName,
                    "changedAt" to FieldValue.serverTimestamp(),
                ),
            )
        }
    }

    override fun observeChanges(limit: Int): Flow<List<CompanySettingsChange>> = callbackFlow {
        val db = firestore()
        val registration = db.collection(CHANGES)
            .orderBy("changedAt", Query.Direction.DESCENDING)
            .limit(limit)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null -> trySend(snapshot.documents.mapNotNull { it.toChange() })
                    else -> trySend(emptyList())
                }
            }
        awaitClose { registration.remove() }
    }.flowOn(Dispatchers.IO)

    // ── mapping ─────────────────────────────────────────────────────────────

    private fun DocumentSnapshot.toProfile() = CompanyProfile(
        hlCompanyId = getString("hlCompanyId").orEmpty(),
        currency = getString("currency").orEmpty(),
        companyName = getString("companyName").orEmpty(),
        tax = parseTax(get("tax")),
        timezone = getString("timezone")?.takeIf { it.isNotBlank() } ?: "UTC",
        legalName = getString("legalName"),
        taxNumber = getString("taxNumber"),
        logoUrl = getString("logoUrl"),
        businessAddress = getString("businessAddress"),
        contactEmail = getString("contactEmail"),
        contactPhone = getString("contactPhone"),
    )

    /** Rates may be stored as a number (provisioning) or a string (this screen) — accept both. */
    private fun parseTax(raw: Any?): TaxConfig {
        val tax = raw as? Map<*, *> ?: return TaxConfig()
        fun rate(v: Any?): String = when (v) {
            is String -> v.trim().ifEmpty { "0" }
            is Number -> v.toString()
            else -> "0"
        }
        return TaxConfig(
            gstEnabled = tax["gstEnabled"] as? Boolean ?: false,
            gstRate = rate(tax["gstRate"]),
            pstEnabled = tax["pstEnabled"] as? Boolean ?: false,
            pstRate = rate(tax["pstRate"]),
            isHST = tax["isHST"] as? Boolean ?: false,
        )
    }

    /** Null when the row is unreadable — a malformed entry is skipped, never guessed at. */
    private fun DocumentSnapshot.toChange(): CompanySettingsChange? {
        val field = getString("field") ?: return null
        val new = getString("newValue") ?: return null
        return CompanySettingsChange(
            changeId = id,
            field = field,
            oldValue = getString("oldValue"),
            newValue = new,
            changedBy = getString("changedBy").orEmpty(),
            changedByName = getString("changedByName"),
            // A just-written entry has no server timestamp yet; 0 sorts it last rather than crashing.
            changedAt = getDate("changedAt")?.time ?: 0L,
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
        const val SETTINGS = "companySettings"
        const val PROFILE = "profile"
        const val CHANGES = "companySettingsChanges"
    }
}

/** The editable half of a profile, for diffing against a proposed change. */
internal fun CompanyProfile.toDetails() = CompanyDetails(
    companyName = companyName,
    legalName = legalName,
    taxNumber = taxNumber,
    businessAddress = businessAddress,
    contactEmail = contactEmail,
    contactPhone = contactPhone,
)
