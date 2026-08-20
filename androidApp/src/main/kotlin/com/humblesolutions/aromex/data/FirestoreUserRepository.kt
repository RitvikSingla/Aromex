package com.humblesolutions.aromex.data

import android.content.Context
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirestoreUserRepository(
    private val context: Context,
) : UserRepository {

    private fun firestoreFor(config: FirebaseClientConfig) =
        Firebase.firestore(FirebaseAppFactory.get(context, config))

    override suspend fun getUserProfile(
        config: FirebaseClientConfig,
        uid: String,
    ): UserProfile? = withContext(Dispatchers.IO) {
        val doc = try {
            firestoreFor(config).collection("users").document(uid).get().await()
        } catch (t: Throwable) {
            throw LoginException(LoginError.FirebaseFailure(t.message ?: "Firestore read failed"))
        }
        if (!doc.exists()) return@withContext null

        UserProfile(
            uid = doc.id,
            email = doc.getString("email").orEmpty(),
            displayName = doc.getString("displayName").orEmpty(),
            role = parseRole(doc.getString("role")),
            permissions = parsePermissions(doc),
            isActive = doc.getBoolean("isActive") ?: false,
        )
    }

    override suspend fun getCompanyProfile(config: FirebaseClientConfig): CompanyProfile? =
        withContext(Dispatchers.IO) {
            val doc = try {
                firestoreFor(config).collection("companySettings").document("profile").get().await()
            } catch (t: Throwable) {
                throw LoginException(LoginError.FirebaseFailure(t.message ?: "Firestore read failed"))
            }
            if (!doc.exists()) return@withContext null
            CompanyProfile(
                hlCompanyId = doc.getString("hlCompanyId").orEmpty(),
                currency = doc.getString("currency").orEmpty(),
                companyName = doc.getString("companyName").orEmpty(),
                tax = parseTax(doc.get("tax") as? Map<*, *>),
                // Shop timezone (ticket #80) for Sales History date formatting (#84); "UTC" fallback.
                timezone = doc.getString("timezone")?.takeIf { it.isNotBlank() } ?: "UTC",
                legalName = doc.getString("legalName"),
                taxNumber = doc.getString("taxNumber"),
                logoUrl = doc.getString("logoUrl"),
                businessAddress = doc.getString("businessAddress"),
                contactEmail = doc.getString("contactEmail"),
                contactPhone = doc.getString("contactPhone"),
            )
        }

    /** Builds a [TaxConfig] from the nested `tax` map; rates kept as decimal strings. */
    private fun parseTax(tax: Map<*, *>?): TaxConfig {
        if (tax == null) return TaxConfig()
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

    private fun parseRole(s: String?): UserRole = when (s?.lowercase()) {
        "admin" -> UserRole.ADMIN
        else -> UserRole.MEMBER
    }

    private fun parsePermissions(doc: DocumentSnapshot): Permissions {
        @Suppress("UNCHECKED_CAST")
        val map = (doc.get("permissions") as? Map<String, Any?>).orEmpty()
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
}

private fun Map<String, Any?>?.orEmpty(): Map<String, Any?> = this ?: emptyMap()
