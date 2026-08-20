package com.humblesolutions.aromex.ui.settings

import com.humblesolutions.aromex.data.BackendCompanySettingsRepository
import com.humblesolutions.aromex.data.DesktopPreferencesRepository
import com.humblesolutions.aromex.data.FirebaseRestAuthRepository
import com.humblesolutions.aromex.data.FirestoreTokenBroker
import com.humblesolutions.aromex.model.CompanyDetails
import com.humblesolutions.aromex.model.CompanySettingsChange
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.repository.CompanySettingsRepository
import com.humblesolutions.aromex.usecase.ObserveCompanyProfileUseCase
import com.humblesolutions.aromex.usecase.ObserveSettingsChangesUseCase
import com.humblesolutions.aromex.usecase.SettingsAudit
import com.humblesolutions.aromex.usecase.TaxConfigError
import com.humblesolutions.aromex.usecase.UpdateCompanyDetailsUseCase
import com.humblesolutions.aromex.usecase.UpdateTaxConfigUseCase
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
 * The tax form's own state, held as **percent strings as typed** ("5", "9.975") rather than the
 * stored fraction. Converting on every keystroke would fight the user mid-edit — "0.0" is not a
 * number anyone is finished typing.
 */
data class TaxForm(
    val gstEnabled: Boolean = false,
    val gstPercent: String = "0",
    val pstEnabled: Boolean = false,
    val pstPercent: String = "0",
    val isHST: Boolean = false,
) {
    fun toConfig() = TaxConfig(
        gstEnabled = gstEnabled,
        gstRate = SettingsAudit.percentToFraction(gstPercent),
        pstEnabled = pstEnabled,
        pstRate = SettingsAudit.percentToFraction(pstPercent),
        isHST = isHST,
    )

    companion object {
        fun from(config: TaxConfig) = TaxForm(
            gstEnabled = config.gstEnabled,
            gstPercent = SettingsAudit.asPercent(config.gstRate).removeSuffix("%"),
            pstEnabled = config.pstEnabled,
            pstPercent = SettingsAudit.asPercent(config.pstRate).removeSuffix("%"),
            isHST = config.isHST,
        )
    }
}

data class DetailsForm(
    val companyName: String = "",
    val legalName: String = "",
    val taxNumber: String = "",
    val businessAddress: String = "",
    val contactEmail: String = "",
    val contactPhone: String = "",
) {
    fun toDetails() = CompanyDetails(
        companyName = companyName,
        legalName = legalName.ifBlank { null },
        taxNumber = taxNumber.ifBlank { null },
        businessAddress = businessAddress.ifBlank { null },
        contactEmail = contactEmail.ifBlank { null },
        contactPhone = contactPhone.ifBlank { null },
    )

    companion object {
        fun from(p: CompanyProfile) = DetailsForm(
            companyName = p.companyName,
            legalName = p.legalName.orEmpty(),
            taxNumber = p.taxNumber.orEmpty(),
            businessAddress = p.businessAddress.orEmpty(),
            contactEmail = p.contactEmail.orEmpty(),
            contactPhone = p.contactPhone.orEmpty(),
        )
    }
}

data class SettingsUiState(
    val session: UserSession? = null,
    val isAdmin: Boolean = false,
    val profile: CompanyProfile? = null,
    val taxForm: TaxForm = TaxForm(),
    val detailsForm: DetailsForm = DetailsForm(),
    val changes: List<CompanySettingsChange> = emptyList(),
    val isSavingTax: Boolean = false,
    val isSavingDetails: Boolean = false,
    val taxSaved: Boolean = false,
    val detailsSaved: Boolean = false,
    val error: String? = null,
    /** Set while the confirmation for a tax change is open — it spells out what will change. */
    val pendingTaxConfirm: List<Triple<String, String?, String>>? = null,
) {
    val taxError: TaxConfigError? get() = UpdateTaxConfigUseCase.validate(taxForm.toConfig())

    val canSaveTax: Boolean
        get() = isAdmin && !isSavingTax &&
            UpdateTaxConfigUseCase.blockingError(taxForm.toConfig()) == null &&
            // Compared as forms, not configs: a rate provisioned as "0.0" and one typed as "0"
            // are the same rate, and Save must not light up on a screen nobody has touched.
            profile?.let { TaxForm.from(it.tax) != taxForm } ?: false

    val canSaveDetails: Boolean
        get() = isAdmin && !isSavingDetails && detailsForm.companyName.isNotBlank() &&
            // False while the profile is still loading: there is no baseline to save against yet.
            profile?.let { DetailsForm.from(it) != detailsForm } ?: false
}

/**
 * Company settings (M10) — the first settings surface in the app.
 *
 * Changing a tax rate is safe precisely because nothing recomputes history: each sale snapshots its
 * own tax lines, the ledger holds the posted amounts, and a void reverses the original transaction
 * by id. A new rate reaches new sales and nothing else, which is why a plain overwrite is correct
 * here and effective-dated rate history isn't needed.
 */
class SettingsViewModel(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val prefs = DesktopPreferencesRepository()
    private val authRepo = FirebaseRestAuthRepository(prefs)

    private var repo: BackendCompanySettingsRepository? = null
    private var observeProfile: ObserveCompanyProfileUseCase? = null
    private var observeChanges: ObserveSettingsChangesUseCase? = null
    private var updateTax: UpdateTaxConfigUseCase? = null
    private var updateDetails: UpdateCompanyDetailsUseCase? = null

    private var session: UserSession? = null
    private var config: FirebaseClientConfig? = null

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    fun bind(session: UserSession, config: FirebaseClientConfig) {
        if (this.session?.uid == session.uid && this.config == config) return
        this.session = session
        this.config = config

        val repository = BackendCompanySettingsRepository(FirestoreTokenBroker(authRepo, config), config)
        repo = repository
        observeProfile = ObserveCompanyProfileUseCase(repository)
        observeChanges = ObserveSettingsChangesUseCase(repository)
        updateTax = UpdateTaxConfigUseCase(repository)
        updateDetails = UpdateCompanyDetailsUseCase(repository)

        _uiState.update { it.copy(session = session, isAdmin = session.role == UserRole.ADMIN) }
        observeProfileStream()
        if (session.role == UserRole.ADMIN) observeChangeLog()
    }

    /** Test seam: inject a fake repository without touching Firestore. */
    internal fun bindForTest(session: UserSession, repository: CompanySettingsRepository) {
        this.session = session
        observeProfile = ObserveCompanyProfileUseCase(repository)
        observeChanges = ObserveSettingsChangesUseCase(repository)
        updateTax = UpdateTaxConfigUseCase(repository)
        updateDetails = UpdateCompanyDetailsUseCase(repository)
        _uiState.update { it.copy(session = session, isAdmin = session.role == UserRole.ADMIN) }
        observeProfileStream()
        if (session.role == UserRole.ADMIN) observeChangeLog()
    }

    private fun observeProfileStream() {
        val useCase = observeProfile ?: return
        scope.launch {
            useCase.execute()
                .catch { e -> _uiState.update { it.copy(error = e.message ?: "Couldn't load settings") } }
                .collect { profile ->
                    _uiState.update { s ->
                        // Only re-seed a form the user hasn't started editing, or a change made
                        // elsewhere would wipe what they're halfway through typing.
                        s.copy(
                            profile = profile,
                            taxForm = if (s.profile == null || !s.taxDirty) TaxForm.from(profile.tax) else s.taxForm,
                            detailsForm = if (s.profile == null || !s.detailsDirty) DetailsForm.from(profile) else s.detailsForm,
                        )
                    }
                }
        }
    }

    private fun observeChangeLog() {
        val useCase = observeChanges ?: return
        val current = session ?: return
        scope.launch {
            runCatching {
                useCase.execute(current)
                    .catch { /* the forms still work; only the log is missing */ }
                    .collect { list -> _uiState.update { it.copy(changes = list) } }
            }
        }
    }

    // ── tax form ─────────────────────────────────────────────────────────────

    fun setGstEnabled(on: Boolean) = _uiState.update {
        // HST rides on the GST line, so it can't outlive it.
        it.copy(taxForm = it.taxForm.copy(gstEnabled = on, isHST = if (on) it.taxForm.isHST else false), taxSaved = false)
    }
    fun setGstPercent(v: String) = _uiState.update { it.copy(taxForm = it.taxForm.copy(gstPercent = filterRate(v)), taxSaved = false) }
    fun setPstEnabled(on: Boolean) = _uiState.update { it.copy(taxForm = it.taxForm.copy(pstEnabled = on), taxSaved = false) }
    fun setPstPercent(v: String) = _uiState.update { it.copy(taxForm = it.taxForm.copy(pstPercent = filterRate(v)), taxSaved = false) }

    fun setHst(on: Boolean) = _uiState.update {
        // HST replaces GST+PST with one combined line; leaving PST on would double-charge.
        it.copy(
            taxForm = it.taxForm.copy(isHST = on, gstEnabled = if (on) true else it.taxForm.gstEnabled, pstEnabled = if (on) false else it.taxForm.pstEnabled),
            taxSaved = false,
        )
    }

    /** Digits and a single decimal point — a rate is typed as a percentage, never as text. */
    private fun filterRate(raw: String): String = buildString {
        var seenDot = false
        raw.forEach { c ->
            when {
                c.isDigit() -> append(c)
                (c == '.' || c == ',') && !seenDot -> { seenDot = true; append('.') }
                else -> Unit
            }
        }
    }

    /** Opens the confirmation, which names every field that will change. */
    fun askSaveTax() {
        val s = _uiState.value
        if (!s.canSaveTax) return
        val before = SettingsAudit.taxFields(s.profile?.tax ?: TaxConfig())
        val after = SettingsAudit.taxFields(s.taxForm.toConfig())
        _uiState.update { it.copy(pendingTaxConfirm = SettingsAudit.diff(before, after)) }
    }

    fun dismissTaxConfirm() = _uiState.update { it.copy(pendingTaxConfirm = null) }

    fun confirmSaveTax() {
        val useCase = updateTax ?: return
        val current = session ?: return
        val config = _uiState.value.taxForm.toConfig()
        _uiState.update { it.copy(pendingTaxConfirm = null, isSavingTax = true, error = null) }
        scope.launch {
            runCatching { useCase.execute(current, config) }
                .onSuccess { _uiState.update { it.copy(isSavingTax = false, taxSaved = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isSavingTax = false, error = e.message ?: "Couldn't save the tax settings") }
                }
        }
    }

    // ── details form ─────────────────────────────────────────────────────────

    fun setCompanyName(v: String) = _uiState.update { it.copy(detailsForm = it.detailsForm.copy(companyName = v), detailsSaved = false) }
    fun setLegalName(v: String) = _uiState.update { it.copy(detailsForm = it.detailsForm.copy(legalName = v), detailsSaved = false) }
    fun setTaxNumber(v: String) = _uiState.update { it.copy(detailsForm = it.detailsForm.copy(taxNumber = v), detailsSaved = false) }
    fun setBusinessAddress(v: String) = _uiState.update { it.copy(detailsForm = it.detailsForm.copy(businessAddress = v), detailsSaved = false) }
    fun setContactEmail(v: String) = _uiState.update { it.copy(detailsForm = it.detailsForm.copy(contactEmail = v), detailsSaved = false) }
    fun setContactPhone(v: String) = _uiState.update { it.copy(detailsForm = it.detailsForm.copy(contactPhone = v), detailsSaved = false) }

    fun saveDetails() {
        val useCase = updateDetails ?: return
        val current = session ?: return
        val s = _uiState.value
        if (!s.canSaveDetails) return
        _uiState.update { it.copy(isSavingDetails = true, error = null) }
        scope.launch {
            runCatching { useCase.execute(current, s.detailsForm.toDetails()) }
                .onSuccess { _uiState.update { it.copy(isSavingDetails = false, detailsSaved = true) } }
                .onFailure { e ->
                    _uiState.update { it.copy(isSavingDetails = false, error = e.message ?: "Couldn't save the company details") }
                }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }

    fun dispose() {
        scope.cancel()
        repo?.close()
    }
}

/** True once the form differs from the loaded profile — i.e. someone is mid-edit. */
private val SettingsUiState.taxDirty: Boolean
    get() = profile?.let { TaxForm.from(it.tax) != taxForm } ?: false

private val SettingsUiState.detailsDirty: Boolean
    get() = profile?.let { DetailsForm.from(it) != detailsForm } ?: false
