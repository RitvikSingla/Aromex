package com.humblesolutions.aromex.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.AndroidPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseAuthRepository
import com.humblesolutions.aromex.data.HlLedgerRepository
import com.humblesolutions.aromex.data.HlTokenRepository
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.HlException
import com.humblesolutions.aromex.model.LedgerAccount
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.usecase.GetAccountBalancesUseCase
import com.humblesolutions.aromex.usecase.LogoutUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val session: UserSession? = null,
    val accounts: List<LedgerAccount> = emptyList(),
    val isLoadingBalances: Boolean = false,
    val balancesError: HlError? = null,
    val isSigningOut: Boolean = false,
    val signedOut: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AndroidPreferencesRepository(application)
    private val authRepo = FirebaseAuthRepository(application)
    private val logoutUseCase = LogoutUseCase(authRepo)

    private var activeConfig: FirebaseClientConfig? = null
    private var tokenRepo: HlTokenRepository? = null
    private var balancesUseCase: GetAccountBalancesUseCase? = null

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Bind the active session + config and kick off the HL balances fetch.
     * Safe to call multiple times — only the first call wires up the HL stack
     * and triggers a load. Subsequent calls with the same session are no-ops.
     */
    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (_uiState.value.session?.uid == session.uid && activeConfig == config) return
        activeConfig = config
        val tokens = HlTokenRepository(authRepo = authRepo, activeConfig = config)
        val ledger = HlLedgerRepository(tokenProvider = tokens)
        tokenRepo = tokens
        balancesUseCase = GetAccountBalancesUseCase(ledger)
        _uiState.update { it.copy(session = session) }
        loadBalances()
    }

    fun retryBalances() {
        if (_uiState.value.isLoadingBalances) return
        loadBalances()
    }

    private fun loadBalances() {
        val useCase = balancesUseCase ?: return
        _uiState.update { it.copy(isLoadingBalances = true, balancesError = null) }
        viewModelScope.launch {
            try {
                val accounts = useCase.execute()
                _uiState.update {
                    it.copy(
                        accounts = accounts,
                        isLoadingBalances = false,
                        balancesError = null,
                    )
                }
            } catch (he: HlException) {
                _uiState.update {
                    it.copy(isLoadingBalances = false, balancesError = he.error)
                }
            } catch (t: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoadingBalances = false,
                        balancesError = HlError.Unexpected(t.message.orEmpty()),
                    )
                }
            }
        }
    }

    fun signOut() {
        val config = activeConfig ?: return
        val state = _uiState.value
        if (state.isSigningOut) return
        _uiState.update { it.copy(isSigningOut = true) }
        viewModelScope.launch {
            logoutUseCase.execute(config)
            prefs.setLastSignedInEmail(null)
            prefs.setLastCompanyId(null)
            _uiState.update { it.copy(isSigningOut = false, signedOut = true, session = null) }
        }
    }
}
