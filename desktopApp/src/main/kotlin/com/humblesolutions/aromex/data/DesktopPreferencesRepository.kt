package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.prefs.Preferences

/**
 * Desktop preferences backed by [java.util.prefs.Preferences]. Stores:
 *   - selected UI language
 *   - last signed-in email + companyId (used by RestoreSessionUseCase)
 *   - the Firebase refresh token per company (Desktop has no Firebase SDK, so
 *     we manage the session ourselves — see FirebaseRestAuthRepository).
 *
 * NOTE: `java.util.prefs` is not OS-secure storage. This is deliberate scope
 * per ticket #19 — moving the refresh token to Keychain / DPAPI / Secret
 * Service is a follow-up hardening ticket.
 */
class DesktopPreferencesRepository : PreferencesRepository {
    private val prefs: Preferences = Preferences.userRoot().node(NODE_PATH)

    override suspend fun getSelectedLanguageCode(): String? = withContext(Dispatchers.IO) {
        prefs.get(KEY_LANGUAGE, null)
    }

    override suspend fun setSelectedLanguageCode(code: String) {
        withContext(Dispatchers.IO) {
            prefs.put(KEY_LANGUAGE, code)
        }
    }

    override suspend fun getLastSignedInEmail(): String? = withContext(Dispatchers.IO) {
        prefs.get(KEY_LAST_EMAIL, null)
    }

    override suspend fun setLastSignedInEmail(email: String?) {
        withContext(Dispatchers.IO) {
            if (email == null) prefs.remove(KEY_LAST_EMAIL) else prefs.put(KEY_LAST_EMAIL, email)
        }
    }

    override suspend fun getLastCompanyId(): String? = withContext(Dispatchers.IO) {
        prefs.get(KEY_LAST_COMPANY_ID, null)
    }

    override suspend fun setLastCompanyId(companyId: String?) {
        withContext(Dispatchers.IO) {
            if (companyId == null) prefs.remove(KEY_LAST_COMPANY_ID)
            else prefs.put(KEY_LAST_COMPANY_ID, companyId)
        }
    }

    /**
     * Firebase refresh token, scoped per Firebase project so a device that
     * has signed into multiple projects doesn't cross-wire sessions. Not part
     * of the shared PreferencesRepository interface — Desktop-only concern
     * because Desktop implements Firebase Auth over REST.
     */
    suspend fun getFirebaseRefreshToken(projectId: String): String? = withContext(Dispatchers.IO) {
        prefs.get(refreshTokenKey(projectId), null)
    }

    suspend fun setFirebaseRefreshToken(projectId: String, refreshToken: String?) {
        withContext(Dispatchers.IO) {
            val key = refreshTokenKey(projectId)
            if (refreshToken == null) prefs.remove(key) else prefs.put(key, refreshToken)
        }
    }

    private fun refreshTokenKey(projectId: String): String = "$KEY_REFRESH_TOKEN_PREFIX$projectId"

    private companion object {
        const val NODE_PATH = "com/humblesolutions/aromex"
        const val KEY_LANGUAGE = "selected_language_code"
        const val KEY_LAST_EMAIL = "last_signed_in_email"
        const val KEY_LAST_COMPANY_ID = "last_company_id"
        const val KEY_REFRESH_TOKEN_PREFIX = "firebase_refresh_token."
    }
}
