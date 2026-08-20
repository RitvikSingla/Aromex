package com.humblesolutions.aromex.ui.inventory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.humblesolutions.aromex.data.BackendAttributeRepository
import com.humblesolutions.aromex.data.BackendCommissionRuleRepository
import com.humblesolutions.aromex.data.BackendEntityRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The commission-rule form's editable fields (ticket #97). [rateInput] is what the admin types:
 *  a money amount for PER_UNIT, or a percent number (`2` for 2%) for PERCENT_OF_COST. */
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
 * Android commission-rules settings VM (ticket #97). Admin-only: [bind] records whether the session
 * is an admin, and the use cases reject a non-admin regardless. Streams the rules (incl. switched-off
 * ones), plus LOCATION vocabulary and the party list for the add/edit form.
 */
class CommissionRulesViewModel(application: Application) : AndroidViewModel(application) {

    private var saveRuleUseCase: SaveCommissionRuleUseCase? = null
    private var archiveRuleUseCase: ArchiveCommissionRuleUseCase? = null
    private var observeRulesUseCase: ObserveCommissionRulesUseCase? = null
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
        if (!admin) return

        val rules = BackendCommissionRuleRepository(getApplication(), config)
        val attrs = BackendAttributeRepository(getApplication(), config)
        val entities = BackendEntityRepository(getApplication(), config)
        observeRulesUseCase = ObserveCommissionRulesUseCase(rules)
        saveRuleUseCase = SaveCommissionRuleUseCase(rules)
        archiveRuleUseCase = ArchiveCommissionRuleUseCase(rules)
        addAttributeUseCase = AddAttributeUseCase(attrs)
        addSupplierInlineUseCase = AddSupplierInlineUseCase(entities)

        viewModelScope.launch {
            runCatching {
                observeRulesUseCase?.execute(session, includeInactive = true)
                    ?.catch { e -> _uiState.update { it.copy(error = e.message ?: "Failed to load rules") } }
                    ?.collect { list -> _uiState.update { it.copy(rules = list) } }
            }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
        viewModelScope.launch {
            runCatching {
                attrs.observeAttributes()
                    .catch { /* leave locations empty */ }
                    .collect { list -> _uiState.update { st -> st.copy(locations = list.filter { it.type == AttributeType.LOCATION && it.isActive }) } }
            }
        }
        viewModelScope.launch {
            runCatching {
                entities.observeEntities(includeArchived = false)
                    .catch { /* leave payees empty */ }
                    .collect { list -> _uiState.update { it.copy(payees = list.filter { e -> e.isActive }) } }
            }
        }
    }

    fun openAddDialog() = _uiState.update { it.copy(showDialog = true, error = null, form = CommissionRuleForm()) }

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
    fun setFormRate(raw: String) = updateForm { it.copy(rateInput = sanitizeDecimalInput(raw)) }

    private fun updateForm(transform: (CommissionRuleForm) -> CommissionRuleForm) =
        _uiState.update { it.copy(form = transform(it.form)) }

    fun save() {
        val current = session ?: return
        val useCase = saveRuleUseCase ?: return
        val form = _uiState.value.form
        val location = form.location
        if (location == null || location.attributeId.isBlank()) {
            _uiState.update { it.copy(error = "A location is required") }; return
        }
        if (form.payeeId.isBlank()) {
            _uiState.update { it.copy(error = "A payee is required") }; return
        }
        val rate = when (form.rateKind) {
            RateKind.PER_UNIT -> form.rateInput.trim()
            RateKind.PERCENT_OF_COST -> percentToFraction(form.rateInput.trim())
        }
        _uiState.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
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
     * doc with `isActive = true`. Neither touches commission already earned.
     */
    fun setRuleActive(rule: CommissionRule, active: Boolean) {
        val current = session ?: return
        viewModelScope.launch {
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
        viewModelScope.launch {
            runCatching { useCase.execute(current, AttributeType.LOCATION, name) }
                .onSuccess { id -> _uiState.update { it.copy(form = it.form.copy(location = AttributeRef(id, name.trim()))) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Could not add location") } }
        }
    }

    /** Add-new-inline from the rule dialog's Payee dropdown: create a name-only party and select it. */
    fun addPayeeInline(name: String) {
        val current = session ?: return
        val useCase = addSupplierInlineUseCase ?: return
        viewModelScope.launch {
            runCatching { useCase.execute(current, name) }
                .onSuccess { id -> _uiState.update { it.copy(form = it.form.copy(payeeId = id, payeeName = name.trim())) } }
                .onFailure { e -> _uiState.update { it.copy(error = e.message ?: "Could not add payee") } }
        }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }
}

/** Percent number → stored fraction (shift two places left): `"2"` → `"0.02"`, `"2.5"` → `"0.025"`.
 *  Pure decimal-shift string maths — never `Money.multiplyRate`, whose 2-dp rounding would corrupt
 *  a fractional-percent rate (ticket #97). */
internal fun percentToFraction(percent: String): String {
    val t = percent.trim()
    if (t.isEmpty()) return ""
    val dot = t.indexOf('.')
    val intPart = if (dot < 0) t else t.substring(0, dot)
    val fracPart = if (dot < 0) "" else t.substring(dot + 1)
    val digits = (intPart + fracPart).ifEmpty { "0" }
    val newFracLen = fracPart.length + 2
    val padded = digits.padStart(newFracLen + 1, '0')
    val cut = padded.length - newFracLen
    val whole = padded.substring(0, cut).trimStart('0').ifEmpty { "0" }
    val frac = padded.substring(cut).trimEnd('0')
    return if (frac.isEmpty()) whole else "$whole.$frac"
}

/** Stored fraction → percent number (shift two places right): `"0.02"` → `"2"`. Inverse of the above. */
internal fun fractionToPercent(fraction: String): String {
    val t = fraction.trim()
    if (t.isEmpty()) return ""
    val dot = t.indexOf('.')
    val intPart = if (dot < 0) t else t.substring(0, dot)
    val fracPart = if (dot < 0) "" else t.substring(dot + 1)
    val digits = (intPart + fracPart)
    val fracLen = fracPart.length
    return if (fracLen <= 2) {
        (digits + "0".repeat(2 - fracLen)).trimStart('0').ifEmpty { "0" }
    } else {
        val newFracLen = fracLen - 2
        val whole = digits.substring(0, digits.length - newFracLen).trimStart('0').ifEmpty { "0" }
        val frac = digits.substring(digits.length - newFracLen).trimEnd('0')
        if (frac.isEmpty()) whole else "$whole.$frac"
    }
}
