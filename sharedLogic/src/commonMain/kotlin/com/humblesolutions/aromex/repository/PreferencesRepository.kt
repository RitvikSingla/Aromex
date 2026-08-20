package com.humblesolutions.aromex.repository

interface PreferencesRepository {
    suspend fun getSelectedLanguageCode(): String?
    suspend fun setSelectedLanguageCode(code: String)

    /**
     * Last email used to sign in successfully. Used by RestoreSessionUseCase
     * on app launch to re-resolve the company from the gateway and check
     * whether Firebase Auth still has a session.
     */
    suspend fun getLastSignedInEmail(): String?
    suspend fun setLastSignedInEmail(email: String?)

    /** Last companyId that the lastSignedInEmail resolved to, if remembered. */
    suspend fun getLastCompanyId(): String?
    suspend fun setLastCompanyId(companyId: String?)
}
