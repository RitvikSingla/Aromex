package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserRole

/**
 * Per-user profile data loaded from Firestore. NOT a session yet — the use case
 * combines this with the resolved company to produce a UserSession.
 */
data class UserProfile(
    val uid: String,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val permissions: Permissions,
    val isActive: Boolean,
)

/**
 * Per-company settings the client needs at login (hlCompanyId, currency, and the tax
 * config used to compute tax at checkout). Read from companySettings/profile.
 */
data class CompanyProfile(
    val hlCompanyId: String,
    val currency: String,
    val companyName: String,
    val tax: TaxConfig = TaxConfig(),
    /**
     * IANA timezone id (ticket #80), from `companySettings/profile.timezone`. Defaults to "UTC"
     * when absent (older companies); carried into [com.humblesolutions.aromex.model.UserSession].
     */
    val timezone: String = "UTC",
    /**
     * The shop's legal name for invoice letterheads (falls back to [companyName] when absent).
     */
    val legalName: String? = null,
    /**
     * The seller's GST/HST registration number (ticket #76) — legally required on a Canadian
     * tax invoice. Null when the shop hasn't set one; the invoice then omits the whole tax line.
     */
    val taxNumber: String? = null,
    val logoUrl: String? = null,
    val businessAddress: String? = null,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
)

interface UserRepository {
    /** Reads users/{uid} from the company's Firestore. Returns null if doc missing. */
    suspend fun getUserProfile(config: FirebaseClientConfig, uid: String): UserProfile?

    /** Reads companySettings/profile from the company's Firestore. Returns null if doc missing. */
    suspend fun getCompanyProfile(config: FirebaseClientConfig): CompanyProfile?
}
