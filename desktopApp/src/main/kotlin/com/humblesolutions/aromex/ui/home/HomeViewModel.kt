package com.humblesolutions.aromex.ui.home

import com.google.cloud.firestore.ListenerRegistration
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.data.FirestoreUserRepository
import com.humblesolutions.aromex.data.HlLedgerRepository
import com.humblesolutions.aromex.data.HlTokenRepository
import com.humblesolutions.aromex.data.NetLog
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.HlException
import com.humblesolutions.aromex.model.LedgerAccount
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.usecase.GetAccountBalancesUseCase
import com.humblesolutions.aromex.usecase.LogoutUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    /** Ticks up each time the Firestore listener fires — proof of real-time. */
    val listenerFireCount: Int = 0,
)

class HomeViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)
    private val logoutUseCase = LogoutUseCase(authRepo)

    private var activeConfig: FirebaseClientConfig? = null
    private var balancesUseCase: GetAccountBalancesUseCase? = null
    private var listenerRegistration: ListenerRegistration? = null

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (_uiState.value.session?.uid == session.uid && activeConfig == config) return
        activeConfig = config
        val tokens = HlTokenRepository(authRepo, config)
        val ledger = HlLedgerRepository(tokens)
        balancesUseCase = GetAccountBalancesUseCase(ledger)
        _uiState.update { it.copy(session = session, signedOut = false) }
        loadBalances()
        attachCompanyProfileListener(config)
    }

    fun retryBalances() {
        if (_uiState.value.isLoadingBalances) return
        loadBalances()
    }

    private fun loadBalances() {
        val useCase = balancesUseCase ?: return
        _uiState.update { it.copy(isLoadingBalances = true, balancesError = null) }
        scope.launch {
            try {
                val accounts = useCase.execute()
                _uiState.update {
                    it.copy(accounts = accounts, isLoadingBalances = false, balancesError = null)
                }
            } catch (he: HlException) {
                _uiState.update { it.copy(isLoadingBalances = false, balancesError = he.error) }
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

    /**
     * Attach a real-time Firestore listener on `companySettings/profile`.
     * This is the proof (per ticket #19 acceptance criterion) that
     * google-cloud-firestore + the brokered datastore token supports
     * onSnapshot on Desktop. Every fire bumps [HomeUiState.listenerFireCount]
     * — the UI can render a small "live" chip that reflects it.
     */
    private fun attachCompanyProfileListener(config: FirebaseClientConfig) {
        listenerRegistration?.runCatching { remove() }
        val repo = FirestoreUserRepository(FirestoreTokenBroker(authRepo, config))
        scope.launch {
            try {
                listenerRegistration = repo.listenToCompanyProfile(config) {
                    _uiState.update { s -> s.copy(listenerFireCount = s.listenerFireCount + 1) }
                }
                NetLog.d("Aromex/Home", "companySettings/profile listener attached")
            } catch (t: Throwable) {
                NetLog.w("Aromex/Home", "failed to attach companySettings listener", t)
            }
        }
    }

    fun signOut() {
        val config = activeConfig ?: return
        val state = _uiState.value
        if (state.isSigningOut) return
        _uiState.update { it.copy(isSigningOut = true) }
        scope.launch {
            runCatching { logoutUseCase.execute(config) }
            prefs.setLastSignedInEmail(null)
            prefs.setLastCompanyId(null)
            listenerRegistration?.runCatching { remove() }
            listenerRegistration = null
            _uiState.update { it.copy(isSigningOut = false, signedOut = true, session = null) }
        }
    }

    /**
     * Clear the one-shot [HomeUiState.signedOut] flag once the router has acted
     * on it (returned to Login). Without this the flag stays sticky: on the next
     * login the Home branch re-enters composition while `signedOut` is still
     * true, the sign-out observer fires again, and it bounces the user right
     * back to the Login screen.
     */
    fun acknowledgeSignedOut() {
        _uiState.update { it.copy(signedOut = false) }
    }

    fun close() {
        listenerRegistration?.runCatching { remove() }
        listenerRegistration = null
        scope.cancel()
    }
}
