package com.humblesolutions.aromex.data

import android.content.Context
import com.humblesolutions.aromex.repository.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPreferencesRepository(context: Context) : PreferencesRepository {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun getSelectedLanguageCode(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_LANGUAGE, null)
    }

    override suspend fun setSelectedLanguageCode(code: String) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_LANGUAGE, code).apply()
        }
    }

    override suspend fun getLastSignedInEmail(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_LAST_EMAIL, null)
    }

    override suspend fun setLastSignedInEmail(email: String?) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_LAST_EMAIL, email).apply()
        }
    }

    override suspend fun getLastCompanyId(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_LAST_COMPANY_ID, null)
    }

    override suspend fun setLastCompanyId(companyId: String?) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_LAST_COMPANY_ID, companyId).apply()
        }
    }

    private companion object {
        const val PREFS_NAME = "aromex_prefs"
        const val KEY_LANGUAGE = "selected_language_code"
        const val KEY_LAST_EMAIL = "last_signed_in_email"
        const val KEY_LAST_COMPANY_ID = "last_company_id"
    }
}
