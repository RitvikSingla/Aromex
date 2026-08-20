package com.humblesolutions.aromex.ui.splash

import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.data.FirestoreUserRepository
import com.humblesolutions.aromex.data.HttpCompanyDirectoryRepository
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.usecase.RestoreSessionUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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

/**
 * On Desktop we don't inherit a lifecycle from AndroidViewModel or SwiftUI
 * @MainActor; the top-level Compose-Desktop application owns the VM's scope
 * and cancels it on close. Everything else mirrors Android/iOS: manual DI in
 * init, StateFlow to the UI, restore in the background on init.
 *
 * Session restore uses [FirebaseRestAuthRepository.currentUid], which knows
 * how to exchange a persisted refresh token for a fresh ID token via
 * securetoken. That's how "already signed in" survives an app relaunch on
 * Desktop.
 */
class SplashViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val directory = HttpCompanyDirectoryRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)

    // Firestore reads during restore need the brokered datastore token. The
    // broker itself depends on the config, so we build one per attempted
    // restore inside the coroutine.
    private val userRepoFor: (FirebaseClientConfig) -> FirestoreUserRepository = { config ->
        FirestoreUserRepository(FirestoreTokenBroker(authRepo, config))
    }
    // RestoreSessionUseCase takes a single UserRepository, but on Desktop that
    // repo is per-config. To fit the shared interface, we use a small adapter
    // that lazily builds the real repo for whatever config the use case hands us.
    private val userRepoAdapter = PerConfigUserRepository(userRepoFor)
    private val restoreUseCase = RestoreSessionUseCase(directory, authRepo, userRepoAdapter)

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        restore()
    }

    private fun restore() {
        scope.launch {
            // Keep the splash visible for a minimum time on cold launch so
            // users actually see the brand treatment. When there is no
            // persisted refresh token the restore returns in ~ms, and without
            // this timer the splash would flash for a single frame before
            // jumping to Login. Runs in parallel with restore so we never
            // extend total launch time when the restore itself is slower.
            val minSplashJob = async { delay(SPLASH_MIN_DISPLAY_MS) }

            val email = prefs.getLastSignedInEmail()
            val companyId = prefs.getLastCompanyId()
            val authenticated = try {
                restoreUseCase.execute(email, companyId)
            } catch (_: Throwable) {
                null
            }

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

    /**
     * Called by the top-level router after a successful sign-out so the
     * splash's "authenticated" state doesn't survive across sign-outs.
     */
    fun returnToLogin() {
        _uiState.update { it.copy(result = SplashResult.NeedsLogin) }
    }

    fun close() {
        scope.cancel()
    }
}
