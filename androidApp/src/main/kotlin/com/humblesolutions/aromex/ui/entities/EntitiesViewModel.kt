package com.humblesolutions.aromex.ui.entities

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.FirebaseAuthRepository
import com.humblesolutions.aromex.data.HL_API_BASE_URL
import com.humblesolutions.aromex.data.HlTokenRepository
import com.humblesolutions.aromex.data.KtorEntityLedgerRepository
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityLedgerRepository
import com.humblesolutions.aromex.usecase.ArchiveEntityUseCase
import com.humblesolutions.aromex.usecase.GetEntityBalancesUseCase
import com.humblesolutions.aromex.usecase.ObserveEntitiesUseCase
import com.humblesolutions.aromex.usecase.SaveEntityUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EntitiesFilter { ALL, CUSTOMER, SUPPLIER, MIDDLEMAN }

/** One list row: the party + its live HL balance (null until balances load / while PENDING). */
data class EntityRow(val entity: Entity, val balance: EntityBalance?)

data class EntitiesUiState(
    val session: UserSession? = null,
    val canManage: Boolean = false,
    val noAccess: Boolean = false,
    val rows: List<EntityRow> = emptyList(),
    val query: String = "",
    val filter: EntitiesFilter = EntitiesFilter.ALL,
    val isLoadingBalances: Boolean = false,
    val error: String? = null,
    val saving: Boolean = false,
    val saveError: String? = null,
)

/**
 * List + add/edit/archive for Profiles/Entities (test-only UI).
 *
 * The ViewModel is the cache: it holds the live entity list (Firestore snapshot
 * listener via [ObserveEntitiesUseCase]) and the HL balances, merges them by
 * externalId (== entity id), and does search/filter client-side. Balances are
 * re-fetched on demand (HL has no push).
 */
class EntitiesViewModel(application: Application) : AndroidViewModel(application) {

    private val authRepo = FirebaseAuthRepository(application)

    private var entityRepo: BackendEntityRepository? = null
    private var ledgerRepo: EntityLedgerRepository? = null
    private var observeUseCase: ObserveEntitiesUseCase? = null
    private var balancesUseCase: GetEntityBalancesUseCase? = null
    private var archiveUseCase: ArchiveEntityUseCase? = null
    private var saveUseCase: SaveEntityUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private var entities: List<Entity> = emptyList()
    private var balances: Map<String, EntityBalance> = emptyMap()

    private val _uiState = MutableStateFlow(EntitiesUiState())
    val uiState: StateFlow<EntitiesUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        val entRepo = BackendEntityRepository(getApplication(), config)
        val tokens = HlTokenRepository(authRepo = authRepo, activeConfig = config)
        val ledger = KtorEntityLedgerRepository(tokens, HL_API_BASE_URL)
        entityRepo = entRepo
        ledgerRepo = ledger
        observeUseCase = ObserveEntitiesUseCase(entRepo)
        balancesUseCase = GetEntityBalancesUseCase(ledger)
        archiveUseCase = ArchiveEntityUseCase(entRepo)
        saveUseCase = SaveEntityUseCase(entRepo)

        val profiles = session.permissions.profiles
        if (profiles == PermissionLevel.NONE) {
            _uiState.update { it.copy(session = session, noAccess = true) }
            return
        }
        _uiState.update {
            it.copy(session = session, canManage = profiles == PermissionLevel.MANAGE, noAccess = false)
        }
        observeEntities()
        refreshBalances()
    }

    private fun observeEntities() {
        val useCase = observeUseCase ?: return
        val current = session ?: return
        viewModelScope.launch {
            // ObserveEntitiesUseCase throws PermissionDeniedException at call time for NONE — we
            // already gate on that in bind(), and collect here so a stream error can't crash init.
            runCatching {
                useCase.execute(current)
                    .catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load parties") } }
                    .collect { list ->
                        entities = list
                        recompute()
                    }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun refreshBalances() {
        val useCase = balancesUseCase ?: return
        val current = session ?: return
        _uiState.update { it.copy(isLoadingBalances = true) }
        viewModelScope.launch {
            runCatching { useCase.execute(current) }
                .onSuccess { balances = it }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load balances") } }
            _uiState.update { it.copy(isLoadingBalances = false) }
            recompute()
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        recompute()
    }

    fun onFilterChange(filter: EntitiesFilter) {
        _uiState.update { it.copy(filter = filter) }
        recompute()
    }

    fun archive(entity: Entity) {
        val useCase = archiveUseCase ?: return
        val current = session ?: return
        viewModelScope.launch {
            runCatching { useCase.execute(current, entity) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Archive failed") } }
            // the snapshot listener refreshes the list
        }
    }

    /** Create (existingId == null) or update a party. Returns true on success. */
    fun save(input: EntityInput, existingId: String?, onDone: (Boolean) -> Unit) {
        val useCase = saveUseCase ?: return onDone(false)
        val current = session ?: return onDone(false)
        _uiState.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val result = runCatching { useCase.execute(current, input, existingId) }
            _uiState.update {
                it.copy(saving = false, saveError = result.exceptionOrNull()?.message)
            }
            refreshBalances()
            onDone(result.isSuccess)
        }
    }

    private fun recompute() {
        val query = _uiState.value.query.trim()
        val filter = _uiState.value.filter
        val rows = entities
            .asSequence()
            .filter { entity ->
                when (filter) {
                    EntitiesFilter.ALL -> true
                    EntitiesFilter.CUSTOMER -> EntityRole.CUSTOMER in entity.roles
                    EntitiesFilter.SUPPLIER -> EntityRole.SUPPLIER in entity.roles
                    EntitiesFilter.MIDDLEMAN -> EntityRole.MIDDLEMAN in entity.roles
                }
            }
            .filter { entity ->
                query.isBlank() ||
                    entity.name.contains(query, ignoreCase = true) ||
                    entity.phones.any { it.contains(query, ignoreCase = true) }
            }
            .map { EntityRow(it, balances[it.id]) }
            .toList()
        _uiState.update { it.copy(rows = rows) }
    }

    override fun onCleared() {
        // Carry-over from T1 review: release the shared Ktor HttpClient this repo owns.
        ledgerRepo?.close()
        super.onCleared()
    }
}
