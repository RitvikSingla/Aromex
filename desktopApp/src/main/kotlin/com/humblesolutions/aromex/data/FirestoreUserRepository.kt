package com.humblesolutions.aromex.data

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.OAuth2Credentials
import com.google.cloud.firestore.EventListener
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.cloud.firestore.ListenerRegistration
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.LoginError
import com.humblesolutions.aromex.model.LoginException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.repository.UserProfile
import com.humblesolutions.aromex.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Implements the shared [UserRepository] against Firestore using
 * `google-cloud-firestore` — the only JVM Firestore client that supports
 * real-time listeners. Auth flows through [FirestoreTokenBroker]: on Desktop
 * the SA key never leaves the gateway; we receive a short-lived,
 * datastore-scoped OAuth token instead.
 *
 * Because that brokered token authenticates as the company's SA, reads here
 * BYPASS Firestore security rules. Permissions are enforced in shared app
 * logic — that's the PRD's Desktop stance and the reason ticket #15 exists.
 *
 * All calls use [Dispatchers.IO] because `google-cloud-firestore` is
 * synchronous gRPC under the hood.
 */
class FirestoreUserRepository(
    private val broker: FirestoreTokenBroker,
) : UserRepository {

    private val clientLock = Mutex()
    @Volatile private var cachedClient: CachedFirestore? = null

    /**
     * A Firestore client + the token it was built with. When the broker
     * rotates the token, we rebuild the client so the next gRPC call carries
     * the fresh credentials.
     */
    private data class CachedFirestore(val projectId: String, val token: String, val firestore: Firestore)

    override suspend fun getUserProfile(config: FirebaseClientConfig, uid: String): UserProfile? {
        return read(config) { firestore ->
            val snap = firestore.collection("users").document(uid).get().get()
            if (!snap.exists()) return@read null
            UserProfile(
                uid = snap.id,
                email = snap.getString("email").orEmpty(),
                displayName = snap.getString("displayName").orEmpty(),
                role = parseRole(snap.getString("role")),
                permissions = parsePermissions(snap.get("permissions")),
                isActive = snap.getBoolean("isActive") ?: false,
            )
        }
    }

    override suspend fun getCompanyProfile(config: FirebaseClientConfig): CompanyProfile? {
        return read(config) { firestore ->
            val snap = firestore.collection("companySettings").document("profile").get().get()
            if (!snap.exists()) return@read null
            companyProfileOf(snap)
        }
    }

    /** Maps a `companySettings/profile` snapshot to [CompanyProfile] (letterhead incl. ticket #76). */
    private fun companyProfileOf(snap: com.google.cloud.firestore.DocumentSnapshot): CompanyProfile =
        CompanyProfile(
            hlCompanyId = snap.getString("hlCompanyId").orEmpty(),
            currency = snap.getString("currency").orEmpty(),
            companyName = snap.getString("companyName").orEmpty(),
            tax = parseTax(snap.get("tax")),
            timezone = snap.getString("timezone")?.takeIf { it.isNotBlank() } ?: "UTC",
            legalName = snap.getString("legalName"),
            taxNumber = snap.getString("taxNumber"),
            logoUrl = snap.getString("logoUrl"),
            businessAddress = snap.getString("businessAddress"),
            contactEmail = snap.getString("contactEmail"),
            contactPhone = snap.getString("contactPhone"),
        )

    /** Builds a [TaxConfig] from the nested `tax` map; rates kept as decimal strings. */
    private fun parseTax(raw: Any?): TaxConfig {
        val tax = raw as? Map<*, *> ?: return TaxConfig()
        return TaxConfig(
            gstEnabled = tax["gstEnabled"] as? Boolean ?: false,
            gstRate = rateToString(tax["gstRate"]),
            pstEnabled = tax["pstEnabled"] as? Boolean ?: false,
            pstRate = rateToString(tax["pstRate"]),
            isHST = tax["isHST"] as? Boolean ?: false,
        )
    }

    private fun rateToString(v: Any?): String = when (v) {
        is String -> v.trim().ifEmpty { "0" }
        is Number -> v.toString()
        else -> "0"
    }

    /**
     * Real-time listener proof (ticket #19 acceptance criterion). Register a
     * snapshot listener on `companySettings/profile`; the caller supplies a
     * callback that runs every time Firestore pushes an update. Returns a
     * [ListenerRegistration] the caller must close on tear-down.
     */
    suspend fun listenToCompanyProfile(
        config: FirebaseClientConfig,
        onUpdate: (CompanyProfile?) -> Unit,
    ): ListenerRegistration = withContext(Dispatchers.IO) {
        val firestore = firestoreFor(config)
        val listener = EventListener<com.google.cloud.firestore.DocumentSnapshot> { snap, err ->
            if (err != null) {
                NetLog.w(TAG, "companySettings listener error: ${err.message}", err)
                return@EventListener
            }
            if (snap == null || !snap.exists()) {
                onUpdate(null)
                return@EventListener
            }
            val profile = companyProfileOf(snap)
            NetLog.d(
                TAG,
                "listener fired: companySettings/profile hlCompanyId=${profile.hlCompanyId} " +
                    "currency=${profile.currency}",
            )
            onUpdate(profile)
        }
        firestore.collection("companySettings").document("profile").addSnapshotListener(listener)
    }

    private suspend fun <T> read(config: FirebaseClientConfig, block: (Firestore) -> T): T =
        withContext(Dispatchers.IO) {
            val firestore = firestoreFor(config)
            try {
                block(firestore)
            } catch (t: Throwable) {
                NetLog.w(TAG, "Firestore read failed: ${t.message}", t)
                // On UNAUTHENTICATED, invalidate the brokered token so the next
                // attempt goes through /firestore-token again.
                if (isUnauthenticated(t)) {
                    broker.invalidate()
                    clientLock.withLock { cachedClient = null }
                }
                throw LoginException(LoginError.FirebaseFailure(t.message ?: "Firestore read failed"))
            }
        }

    private suspend fun firestoreFor(config: FirebaseClientConfig): Firestore {
        val token = broker.currentToken()
        val cached = cachedClient
        if (cached != null && cached.projectId == config.projectId && cached.token == token) {
            return cached.firestore
        }
        return clientLock.withLock {
            val current = cachedClient
            if (current != null && current.projectId == config.projectId && current.token == token) {
                return@withLock current.firestore
            }
            current?.let { runCatching { it.firestore.close() } }
            val credentials = OAuth2Credentials.create(
                // Firestore checks expiry via credentials refresh; provide a
                // conservative expiry so gRPC doesn't try to refresh via a
                // credentials provider we don't have.
                AccessToken(token, Date(System.currentTimeMillis() + 55 * 60 * 1000L)),
            )
            val firestore = FirestoreOptions.newBuilder()
                .setProjectId(config.projectId)
                .setCredentials(credentials)
                .build()
                .service
            NetLog.d(TAG, "built Firestore client project=${config.projectId} token=${NetLog.redact(token)}")
            cachedClient = CachedFirestore(config.projectId, token, firestore)
            firestore
        }
    }

    private fun isUnauthenticated(t: Throwable): Boolean {
        val message = t.message ?: return false
        return message.contains("UNAUTHENTICATED", ignoreCase = true) ||
            message.contains("PERMISSION_DENIED", ignoreCase = true) ||
            message.contains("401") ||
            message.contains("403")
    }

    private fun parseRole(raw: String?): UserRole = when (raw?.lowercase()) {
        "admin" -> UserRole.ADMIN
        else -> UserRole.MEMBER
    }

    private fun parsePermissions(raw: Any?): Permissions {
        @Suppress("UNCHECKED_CAST")
        val map = (raw as? Map<String, Any?>).orEmpty()
        fun level(key: String): PermissionLevel = when ((map[key] as? String)?.lowercase()) {
            "manage" -> PermissionLevel.MANAGE
            "view" -> PermissionLevel.VIEW
            else -> PermissionLevel.NONE
        }
        return Permissions(
            sales = level("sales"),
            purchases = level("purchases"),
            inventory = level("inventory"),
            transactions = level("transactions"),
            profiles = level("profiles"),
            balances = level("balances"),
            reports = level("reports"),
            statistics = level("statistics"),
            histories = level("histories"),
            ledgers = level("ledgers"),
            settings = level("settings"),
            userMgmt = (map["userMgmt"] as? Boolean) ?: false,
        )
    }

    private companion object {
        const val TAG = "Aromex/Firestore"
    }
}

private fun Map<String, Any?>?.orEmpty(): Map<String, Any?> = this ?: emptyMap()
