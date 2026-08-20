package com.humblesolutions.aromex.ui.entities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.BackendStatementPdfRepository
import com.humblesolutions.aromex.data.FirebaseAuthRepository
import com.humblesolutions.aromex.data.HL_API_BASE_URL
import com.humblesolutions.aromex.data.HlTokenRepository
import com.humblesolutions.aromex.data.KtorEntityLedgerRepository
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityLedgerRepository
import com.humblesolutions.aromex.repository.StatementPdfRepository
import com.humblesolutions.aromex.usecase.BuildPartyStatementUseCase
import com.humblesolutions.aromex.usecase.StatementTooLargeException
import com.humblesolutions.aromex.util.CalendarDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the "Print statement" dialog (ticket #109). [fromIso]/[toIso] are `yyyy-MM-dd`; a
 * blank [fromIso] means an all-time statement. [pdfUrl] is set once the PDF is ready to open.
 */
data class PrintStatementUiState(
    val fromIso: String = "",
    val toIso: String = "",
    val includeNotes: Boolean = false,
    val generating: Boolean = false,
    val error: String? = null,
    val pdfUrl: String? = null,
)

/**
 * Drives statement PDF generation for one party. The heavy lifting (paging, opening balance, FIFO
 * aging) lives in the shared [BuildPartyStatementUseCase]; this only wires the dependencies, holds
 * the dialog's form state, and calls the `renderStatement` Cloud Function via
 * [BackendStatementPdfRepository].
 */
class PrintStatementViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepo = FirebaseAuthRepository(application)

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null
    private var ledger: EntityLedgerRepository? = null
    private var buildUseCase: BuildPartyStatementUseCase? = null
    private var pdfRepo: StatementPdfRepository? = null

    private val _uiState = MutableStateFlow(defaultState())
    val uiState: StateFlow<PrintStatementUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config
        val tokens = HlTokenRepository(authRepo = authRepo, activeConfig = config)
        val ledgerRepo = KtorEntityLedgerRepository(tokens, HL_API_BASE_URL)
        ledger = ledgerRepo
        buildUseCase = BuildPartyStatementUseCase(ledgerRepo)
        pdfRepo = BackendStatementPdfRepository(getApplication(), config)
    }

    /** Resets the form to a fresh default range each time the dialog opens. */
    fun openFor() {
        _uiState.value = defaultState()
    }

    fun onFromChange(value: String) = _uiState.update { it.copy(fromIso = value.trim(), error = null) }
    fun onToChange(value: String) = _uiState.update { it.copy(toIso = value.trim(), error = null) }
    fun onToggleNotes(value: Boolean) = _uiState.update { it.copy(includeNotes = value) }

    /** Clears a consumed result so the same dialog can generate again. */
    fun consumeResult() = _uiState.update { it.copy(pdfUrl = null) }

    fun generate(entity: Entity) {
        val currentSession = session ?: return
        val useCase = buildUseCase ?: return
        val repo = pdfRepo ?: return
        val state = _uiState.value
        val fromIso = state.fromIso.ifBlank { null }
        if (state.toIso.isBlank()) {
            _uiState.update { it.copy(error = "Choose an end date.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(generating = true, error = null, pdfUrl = null) }
            try {
                val document = useCase.execute(
                    entity = entity,
                    permissions = currentSession.permissions,
                    fromIso = fromIso,
                    toIso = state.toIso,
                    includeNotes = state.includeNotes,
                )
                val url = repo.render(entity.id, document)
                _uiState.update { it.copy(generating = false, pdfUrl = url) }
            } catch (e: StatementTooLargeException) {
                _uiState.update { it.copy(generating = false, error = e.message) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(generating = false, error = e.message ?: "Couldn't generate the statement.")
                }
            }
        }
    }

    override fun onCleared() {
        ledger?.close()
    }

    /** Default range: the last ~3 months, editable in the dialog. */
    private fun defaultState(): PrintStatementUiState {
        val todayEpochDay = System.currentTimeMillis() / MILLIS_PER_DAY
        return PrintStatementUiState(
            fromIso = CalendarDate.fromEpochDay(todayEpochDay - 90),
            toIso = CalendarDate.fromEpochDay(todayEpochDay),
        )
    }

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
