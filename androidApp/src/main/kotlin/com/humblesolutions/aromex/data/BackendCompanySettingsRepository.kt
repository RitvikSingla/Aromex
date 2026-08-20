package com.humblesolutions.aromex.data

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import android.content.Context
import com.humblesolutions.aromex.model.CompanyDetails
import com.humblesolutions.aromex.model.CompanySettingsChange
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.repository.CompanySettingsRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Firestore-backed [CompanySettingsRepository] for Android — `companySettings/profile`.
 *
 * Added so a phone's till follows a tax-rate change (M10). Without it, `UserSession.tax` is captured
 * at sign-in and never refreshed: an admin changing GST at noon would leave every open phone
 * charging the old rate for the rest of the day, silently — the same hole that was closed on
 * Desktop.
 *
 * Reads only. The Settings *screen* is Desktop-only for now, so the write paths throw rather than
 * pretend: a phone that appeared to save a tax rate but didn't would be worse than one that can't.
 */
class BackendCompanySettingsRepository(
    private val context: Context,
    private val config: FirebaseClientConfig,
) : CompanySettingsRepository {

    private val app get() = FirebaseAppFactory.get(context, config)
    private val db get() = Firebase.firestore(app)

    override fun observeProfile(): Flow<CompanyProfile> = callbackFlow {
        val registration = db.collection(SETTINGS).document(PROFILE)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null && snapshot.exists() -> trySend(snapshot.toProfile())
                    // An absent profile means provisioning hasn't finished. Emitting a default
                    // would quietly say "no tax"; staying silent lets the caller keep what it had.
                    else -> Unit
                }
            }
        awaitClose { registration.remove() }
    }

    override fun observeChanges(limit: Int): Flow<List<CompanySettingsChange>> = callbackFlow {
        val registration = db.collection(CHANGES)
            .orderBy("changedAt", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> close(error)
                    snapshot != null -> trySend(snapshot.documents.mapNotNull { it.toChange() })
                    else -> trySend(emptyList())
                }
            }
        awaitClose { registration.remove() }
    }

    override suspend fun updateTax(config: TaxConfig, changedBy: String, changedByName: String?) =
        throw UnsupportedOperationException("Tax settings are edited on Desktop")

    override suspend fun updateDetails(details: CompanyDetails, changedBy: String, changedByName: String?) =
        throw UnsupportedOperationException("Company details are edited on Desktop")

    // ── mapping (mirrors the Desktop implementation) ─────────────────────────

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

    /** Rates may be stored as a number (provisioning) or a string (the Settings screen) — accept both. */
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
            changedAt = getDate("changedAt")?.time ?: 0L,
        )
    }

    private companion object {
        const val SETTINGS = "companySettings"
        const val PROFILE = "profile"
        const val CHANGES = "companySettingsChanges"
    }
}
