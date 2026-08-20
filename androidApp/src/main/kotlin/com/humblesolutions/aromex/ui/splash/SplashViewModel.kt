package com.humblesolutions.aromex.ui.splash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.AndroidPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseAuthRepository
import com.humblesolutions.aromex.data.FirestoreUserRepository
import com.humblesolutions.aromex.data.HttpCompanyDirectoryRepository
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.usecase.RestoreSessionUseCase
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed class SplashResult {
    /** Splash animation hasn't finished yet AND restore is still running. */
    object Loading : SplashResult()
    /** No saved session — go to Login. */
    object NeedsLogin : SplashResult()
    /** Saved session is still valid — go to Home. */
    data class Authenticated(val authenticated: AuthenticatedSession) : SplashResult()
}

data class SplashUiState(
    val result: SplashResult = SplashResult.Loading,
)

class SplashViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AndroidPreferencesRepository(application)
    private val directory = HttpCompanyDirectoryRepository()
    private val authRepo = FirebaseAuthRepository(application)
    private val userRepo = FirestoreUserRepository(application)
    private val restoreUseCase = RestoreSessionUseCase(directory, authRepo, userRepo)

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        restore()
    }

    private fun restore() {
        viewModelScope.launch {
            // Ensure Splash stays visible for at least this long so users see
            // the brand treatment on cold launch, even when restore is
            // instant (no persisted refresh token). Run restore in parallel
            // with the timer so we don't extend total launch time when the
            // restore is slower than the minimum.
            val minSplashJob = async { delay(SPLASH_MIN_DISPLAY_MS) }

            val authenticated = try {
                val email = prefs.getLastSignedInEmail()
                val companyId = prefs.getLastCompanyId()
                restoreUseCase.execute(email, companyId)
            } catch (t: Throwable) {
                null
            }

            // Wait for the minimum-display timer if restore finished first.
            minSplashJob.await()

            _uiState.update {
                it.copy(
                    result = if (authenticated != null) SplashResult.Authenticated(authenticated)
                    else SplashResult.NeedsLogin,
                )
            }
        }
    }

    private companion object {
        const val SPLASH_MIN_DISPLAY_MS = 2_000L
    }
}
