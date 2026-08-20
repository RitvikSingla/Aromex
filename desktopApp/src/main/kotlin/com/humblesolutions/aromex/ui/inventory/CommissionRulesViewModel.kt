package com.humblesolutions.aromex.ui.inventory

import com.humblesolutions.aromex.data.BackendAttributeRepository
import com.humblesolutions.aromex.data.BackendCommissionRuleRepository
import com.humblesolutions.aromex.data.BackendEntityRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.CommissionRuleInput
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.usecase.AddAttributeUseCase
import com.humblesolutions.aromex.usecase.AddSupplierInlineUseCase
import com.humblesolutions.aromex.usecase.ArchiveCommissionRuleUseCase
import com.humblesolutions.aromex.usecase.ObserveCommissionRulesUseCase
import com.humblesolutions.aromex.usecase.SaveCommissionRuleUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The commission-rule form's editable fields (ticket #97). [rateInput] is what the admin types:
 * a money amount for [RateKind.PER_UNIT], or a **percent number** (e.g. `"2"` for 2%) for
 * [RateKind.PERCENT_OF_COST] — converted to/from the stored fraction at the VM boundary so the
 * admin never has to think in fractions.
 */
data class CommissionRuleForm(
    val ruleId: String = "",
    val location: AttributeRef? = null,
    val payeeId: String = "",
    val payeeName: String = "",
    val rateKind: RateKind = RateKind.PER_UNIT,
    val rateInput: String = "",
    val isActive: Boolean = true,
) {
    val isEditing: Boolean get() = ruleId.isNotBlank()
}

data class CommissionRulesUiState(
    val isAdmin: Boolean = false,
    val currency: String = "",
    val rules: List<CommissionRule> = emptyList(),
    val locations: List<AttributeValue> = emptyList(),
    val payees: List<Entity> = emptyList(),
    val showDialog: Boolean = false,
    val form: CommissionRuleForm = CommissionRuleForm(),
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * Desktop commission-rules settings screen VM (ticket #97). Admin-only: [bind] records whether
 * the session is an admin and the use cases reject a non-admin regardless. Streams the rules
 * (incl. switched-off ones), plus LOCATION vocabulary and the party list for the add/edit form.
 * Plain class + [CoroutineScope]; call [dispose] on teardown.
 */
class CommissionRulesViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)

    private var ruleRepo: BackendCommissionRuleRepository? = null
    private var attrRepo: BackendAttributeRepository? = null
    private var entityRepo: BackendEntityRepository? = null
    private var observeRulesUseCase: ObserveCommissionRulesUseCase? = null
    private var saveRuleUseCase: SaveCommissionRuleUseCase? = null
    private var archiveRuleUseCase: ArchiveCommissionRuleUseCase? = null
    private var addAttributeUseCase: AddAttributeUseCase? = null
    private var addSupplierInlineUseCase: AddSupplierInlineUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private val _uiState = MutableStateFlow(CommissionRulesUiState())
    val uiState: StateFlow<CommissionRulesUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config
        val admin = session.role == UserRole.ADMIN
        _uiState.update { it.copy(isAdmin = admin, currency = session.currency) }
        if (!admin) return // a non-admin sees the no-access state; nothing is streamed

        val broker = FirestoreTokenBroker(authRepo, config)
        val rules = BackendCommissionRuleRepository(broker, config, session.uid)
        val attrs = BackendAttributeRepository(broker, config, session.uid)
        val entities = BackendEntityRepository(broker, config, session.uid)
        ruleRepo = rules
        attrRepo = attrs
        entityRepo = entities
        observeRulesUseCase = ObserveCommissionRulesUseCase(rules)
        saveRuleUseCase = SaveCommissionRuleUseCase(rules)
        archiveRuleUseCase = ArchiveCommissionRuleUseCase(rules)
        addAttributeUseCase = AddAttributeUseCase(attrs)
        addSupplierInlineUseCase = AddSupplierInlineUseCase(entities)

        scope.launch {
            runCatching {
                observeRulesUseCase?.execute(session, includeInactive = true)
                    ?.catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load rules") } }
                    ?.collect { list -> _uiState.update { it.copy(rules = list) } }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
        scope.launch {
            runCatching {
                attrs.observeAttributes()
                    .catch { /* leave locations empty; the picker just shows nothing */ }
                    .collect { list ->
                        _uiState.update { st -> st.copy(locations = list.filter { it.type == AttributeType.LOCATION && it.isActive }) }
                    }
            }
        }
        scope.launch {
            runCatching {
                entities.observeEntities(includeArchived = false)
                    .catch { /* leave payees empty */ }
                    .collect { list -> _uiState.update { it.copy(payees = list.filter { e -> e.isActive }) } }
            }
        }
    }

    // ── Dialog ────────────────────────────────────────────────────────────────────

    fun openAddDialog() = _uiState.update {
        it.copy(showDialog = true, error = null, form = CommissionRuleForm())
    }

    fun openEditDialog(rule: CommissionRule) = _uiState.update { state ->
        val locName = state.locations.firstOrNull { it.attributeId == rule.locationAttributeId }?.name.orEmpty()
        val payeeName = state.payees.firstOrNull { it.id == rule.payeeEntityId }?.name.orEmpty()
        state.copy(
            showDialog = true,
            error = null,
            form = CommissionRuleForm(
                ruleId = rule.ruleId,
                location = AttributeRef(rule.locationAttributeId, locName),
                payeeId = rule.payeeEntityId,
                payeeName = payeeName,
                rateKind = rule.rateKind,
                rateInput = when (rule.rateKind) {
                    RateKind.PER_UNIT -> rule.rate
                    RateKind.PERCENT_OF_COST -> fractionToPercent(rule.rate)
                },
                isActive = rule.isActive,
            ),
        )
    }

    fun dismissDialog() = _uiState.update { it.copy(showDialog = false) }

    fun setFormLocation(value: AttributeValue) = updateForm { it.copy(location = AttributeRef(value.attributeId, value.name)) }

    fun setFormPayee(entity: Entity) = updateForm { it.copy(payeeId = entity.id, payeeName = entity.name) }

    fun setFormRateKind(kind: RateKind) = updateForm { it.copy(rateKind = kind, rateInput = "") }

    /** Rate field: digits + at most one decimal point only (never a float). */
    fun setFormRate(raw: String) = updateForm { it.copy(rateInput = sanitizeDecimalInput(raw)) }

    private fun updateForm(transform: (CommissionRuleForm) -> CommissionRuleForm) =
        _uiState.update { it.copy(form = transform(it.form)) }

    // ── Persist ───────────────────────────────────────────────────────────────────

    fun save() {
        val current = session ?: return
        val useCase = saveRuleUseCase ?: return
        val form = _uiState.value.form
        val location = form.location
        if (location == null || location.attributeId.isBlank()) {
            _uiState.update { it.copy(error = "A location is required") }
            return
        }
        if (form.payeeId.isBlank()) {
            _uiState.update { it.copy(error = "A payee is required") }
            return
        }
        // Percent entered as a number (2 → 0.02); per-unit is a money amount as typed.
        val rate = when (form.rateKind) {
            RateKind.PER_UNIT -> form.rateInput.trim()
            RateKind.PERCENT_OF_COST -> percentToFraction(form.rateInput.trim())
        }
        _uiState.update { it.copy(saving = true, error = null) }
        scope.launch {
            runCatching {
                useCase.execute(
                    current,
                    CommissionRuleInput(
                        ruleId = form.ruleId,
                        locationAttributeId = location.attributeId,
                        payeeEntityId = form.payeeId,
                        rateKind = form.rateKind,
                        rate = rate,
                        isActive = form.isActive,
                    ),
                )
            }.onSuccess { _uiState.update { it.copy(saving = false, showDialog = false) } }
                .onFailure { e -> _uiState.update { it.copy(saving = false, error = e.message ?: "Could not save rule") } }
        }
    }

    /**
     * Flip a rule on/off from the row's switch. Off reuses the archive path; on re-saves the same
     * doc with `isActive = true` (SaveCommissionRuleUseCase updates in place). Neither touches
     * commission already earned — what's owed is owed; this only changes whether it fires next time.
     */
    fun setRuleActive(rule: CommissionRule, active: Boolean) {
        val current = session ?: return
        scope.launch {
            runCatching {
                if (!active) {
                    archiveRuleUseCase?.execute(current, rule.ruleId)
                } else {
                    saveRuleUseCase?.execute(
                        current,
                        CommissionRuleInput(
                            ruleId = rule.ruleId,
                            locationAttributeId = rule.locationAttributeId,
                            payeeEntityId = rule.payeeEntityId,
                            rateKind = rule.rateKind,
                            rate = rule.rate,
                            isActive = true,
                        ),
                    )
                }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Could not update rule") } }
        }
    }

    /** Add-new-inline from the rule dialog's Location dropdown: mint a LOCATION value and select it. */
    fun addLocationInline(name: String) {
        val current = session ?: return
        val useCase = addAttributeUseCase ?: return
        scope.launch {
            runCatching { useCase.execute(current, AttributeType.LOCATION, name) }
                .onSuccess { id -> updateForm { it.copy(location = AttributeRef(id, name.trim())) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Could not add location") } }
        }
    }

    /** Add-new-inline from the rule dialog's Payee dropdown: create a name-only party and select it. */
    fun addPayeeInline(name: String) {
        val current = session ?: return
        val useCase = addSupplierInlineUseCase ?: return
        scope.launch {
            runCatching { useCase.execute(current, name) }
                .onSuccess { id -> updateForm { it.copy(payeeId = id, payeeName = name.trim()) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Could not add payee") } }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun dispose() {
        scope.cancel()
        ruleRepo?.close()
        attrRepo?.close()
        entityRepo?.close()
    }
}

/**
 * Percent number → stored fraction by shifting the decimal point two places left (`"2"` → `"0.02"`,
 * `"2.5"` → `"0.025"`). Pure string maths — never a float, and never the 2-dp rounding of
 * `Money.multiplyRate`, which would corrupt a fractional-percent rate.
 */
internal fun percentToFraction(percent: String): String {
    val t = percent.trim()
    if (t.isEmpty()) return ""
    val dot = t.indexOf('.')
    val intPart = if (dot < 0) t else t.substring(0, dot)
    val fracPart = if (dot < 0) "" else t.substring(dot + 1)
    val digits = (intPart + fracPart).ifEmpty { "0" }
    // New fraction length = existing frac length + 2 (dividing by 100).
    val newFracLen = fracPart.length + 2
    val padded = digits.padStart(newFracLen + 1, '0') // ensure at least one integer digit
    val cut = padded.length - newFracLen
    val whole = padded.substring(0, cut).trimStart('0').ifEmpty { "0" }
    val frac = padded.substring(cut).trimEnd('0')
    return if (frac.isEmpty()) whole else "$whole.$frac"
}

/**
 * Stored fraction → percent number by shifting two places right (`"0.02"` → `"2"`, `"0.025"` →
 * `"2.5"`). The inverse of [percentToFraction], for pre-filling the edit form.
 */
internal fun fractionToPercent(fraction: String): String {
    val t = fraction.trim()
    if (t.isEmpty()) return ""
    val dot = t.indexOf('.')
    val intPart = if (dot < 0) t else t.substring(0, dot)
    val fracPart = if (dot < 0) "" else t.substring(dot + 1)
    val digits = (intPart + fracPart)
    val fracLen = fracPart.length
    return if (fracLen <= 2) {
        // Shifting right by 2 makes it a whole number: append trailing zeros.
        val whole = (digits + "0".repeat(2 - fracLen)).trimStart('0').ifEmpty { "0" }
        whole
    } else {
        val newFracLen = fracLen - 2
        val whole = digits.substring(0, digits.length - newFracLen).trimStart('0').ifEmpty { "0" }
        val frac = digits.substring(digits.length - newFracLen).trimEnd('0')
        if (frac.isEmpty()) whole else "$whole.$frac"
    }
}
