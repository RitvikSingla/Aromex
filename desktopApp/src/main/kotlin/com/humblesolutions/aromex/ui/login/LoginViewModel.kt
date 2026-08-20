package com.humblesolutions.aromex.ui.login

import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.data.FirestoreUserRepository
import com.humblesolutions.aromex.data.HttpCompanyDirectoryRepository
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.LoginError
import com.humblesolutions.aromex.model.LoginException
import com.humblesolutions.aromex.model.ResolvedCompany
import com.humblesolutions.aromex.ui.splash.PerConfigUserRepository
import com.humblesolutions.aromex.usecase.LoginResult
import com.humblesolutions.aromex.usecase.LoginUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isSubmitting: Boolean = false,
    val error: LoginError? = null,
    /** Non-null while the user is on the choose-company step. */
    val candidates: List<ResolvedCompany>? = null,
    /** Filled once login succeeds. The host UI navigates to Home. */
    val authenticated: AuthenticatedSession? = null,
)

/**
 * Compose-Desktop equivalent of Android's LoginViewModel + iOS's
 * LoginViewModel. Same state machine (email/password → submit → success or
 * needs-company-choice → finishLogin), same error mapping, same "persist
 * last email + companyId on success" behavior. The only Desktop-specific
 * wiring is that [FirebaseRestAuthRepository] speaks HTTPS to Firebase Auth
 * directly, and [FirestoreUserRepository] reads through the gateway
 * /firestore-token broker.
 */
class LoginViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val directory = HttpCompanyDirectoryRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)
    private val userRepo = PerConfigUserRepository { config ->
        FirestoreUserRepository(FirestoreTokenBroker(authRepo, config))
    }
    private val loginUseCase = LoginUseCase(directory, authRepo, userRepo)

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) {
        _uiState.update { it.copy(email = value, error = null) }
    }

    fun onPasswordChange(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun onDismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onSubmit() {
        val state = _uiState.value
        if (state.isSubmitting) return
        val email = state.email.trim()
        val password = state.password
        if (email.isEmpty() || password.isEmpty()) return

        _uiState.update { it.copy(isSubmitting = true, error = null) }
        scope.launch {
            try {
                when (val result = loginUseCase.execute(email, password)) {
                    is LoginResult.Success -> {
                        rememberLastLogin(email, result.authenticated.session.companyId)
                        _uiState.update {
                            it.copy(isSubmitting = false, authenticated = result.authenticated, candidates = null)
                        }
                    }
                    is LoginResult.NeedsCompanyChoice -> {
                        _uiState.update {
                            it.copy(isSubmitting = false, candidates = result.candidates)
                        }
                    }
                }
            } catch (le: LoginException) {
                _uiState.update { it.copy(isSubmitting = false, error = le.error) }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = LoginError.Unexpected(t.message ?: "unknown"))
                }
            }
        }
    }

    fun onChooseCompany(company: ResolvedCompany) {
        val state = _uiState.value
        if (state.isSubmitting) return
        val email = state.email.trim()
        val password = state.password
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        scope.launch {
            try {
                val authenticated = loginUseCase.finishLogin(company, email, password)
                rememberLastLogin(email, authenticated.session.companyId)
                _uiState.update {
                    it.copy(isSubmitting = false, authenticated = authenticated, candidates = null)
                }
            } catch (le: LoginException) {
                _uiState.update { it.copy(isSubmitting = false, error = le.error, candidates = null) }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(isSubmitting = false, error = LoginError.Unexpected(t.message ?: "unknown"))
                }
            }
        }
    }

    fun onCancelChooseCompany() {
        _uiState.update { it.copy(candidates = null) }
    }

    fun reset() {
        _uiState.value = LoginUiState()
    }

    private suspend fun rememberLastLogin(email: String, companyId: String) {
        prefs.setLastSignedInEmail(email)
        prefs.setLastCompanyId(companyId)
    }

    fun close() {
        scope.cancel()
    }
}
