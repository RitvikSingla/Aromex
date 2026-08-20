package com.humblesolutions.aromex.model

enum class UserRole { ADMIN, MEMBER }

data class UserSession(
    val uid: String,
    val email: String,
    val displayName: String,
    val role: UserRole,
    val permissions: Permissions,
    val companyId: String,
    val hlCompanyId: String,
    val currency: String,
    val tax: TaxConfig = TaxConfig(),
    /**
     * IANA timezone id of the shop (e.g. "America/Vancouver"), from
     * `companySettings/profile.timezone` (ticket #80). Sales History renders sale times in this
     * zone. Defaults to "UTC" so older sessions/companies without the field never crash a formatter.
     */
    val timezone: String = "UTC",
    val isActive: Boolean,
)
