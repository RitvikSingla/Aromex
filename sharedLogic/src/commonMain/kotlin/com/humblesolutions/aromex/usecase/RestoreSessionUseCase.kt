package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.AuthRepository
import com.humblesolutions.aromex.repository.CompanyDirectoryRepository
import com.humblesolutions.aromex.repository.UserRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Rebuild an AuthenticatedSession on app launch if Firebase Auth still has the
 * user signed in.
 *
 * Strategy: the platform (Android) caches the *last successful* email +
 * companyId locally (SharedPreferences). We re-resolve via the gateway to get a
 * fresh config (gateway's source of truth), then check whether Firebase still
 * has a uid for that app. If yes, we load the profile and return both session
 * + config.
 *
 * Returns null if nobody is signed in or the previous email cannot be resolved.
 */
class RestoreSessionUseCase(
    private val directory: CompanyDirectoryRepository,
    private val auth: AuthRepository,
    private val user: UserRepository,
) {
    @Throws(CancellationException::class)
    suspend fun execute(lastEmail: String?, lastCompanyId: String?): AuthenticatedSession? {
        if (lastEmail.isNullOrBlank() || lastCompanyId.isNullOrBlank()) return null

        val candidates = runCatching { directory.resolveCompanies(lastEmail) }.getOrNull() ?: return null
        val company = candidates.firstOrNull { it.companyId == lastCompanyId } ?: return null

        val uid = runCatching { auth.currentUid(company.firebaseConfig) }.getOrNull() ?: return null
        val profile = runCatching { user.getUserProfile(company.firebaseConfig, uid) }.getOrNull()
        if (profile == null || !profile.isActive) {
            runCatching { auth.signOut(company.firebaseConfig) }
            return null
        }
        val companyProfile = runCatching { user.getCompanyProfile(company.firebaseConfig) }.getOrNull()
            ?: return null
        return AuthenticatedSession(
            session = UserSession(
                uid = profile.uid,
                email = profile.email,
                displayName = profile.displayName,
                role = profile.role,
                permissions = profile.permissions,
                companyId = company.companyId,
                hlCompanyId = companyProfile.hlCompanyId,
                currency = companyProfile.currency,
                tax = companyProfile.tax,
                timezone = companyProfile.timezone,
                isActive = profile.isActive,
            ),
            config = company.firebaseConfig,
        )
    }
}
