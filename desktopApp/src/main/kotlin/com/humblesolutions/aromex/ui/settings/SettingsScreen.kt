package com.humblesolutions.aromex.ui.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.UserSession
import androidx.compose.ui.zIndex
import com.humblesolutions.aromex.ui.components.AromexDialog
import com.humblesolutions.aromex.ui.components.CollapsedSidebarWidth
import com.humblesolutions.aromex.ui.components.DesktopSection
import com.humblesolutions.aromex.ui.components.ExpandedSidebarWidth
import com.humblesolutions.aromex.ui.components.NavSidebar
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.money.FIELD_HEIGHT
import com.humblesolutions.aromex.ui.money.FieldLabel
import com.humblesolutions.aromex.ui.money.FieldShell
import com.humblesolutions.aromex.ui.money.LABEL_GAP
import com.humblesolutions.aromex.ui.money.ShellTextField
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.usecase.TaxConfigError
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Company settings (M10) — the app's first settings surface.
 *
 * Tax and the shop's invoice identity, both admin-only to change and read-only to everyone else.
 * A tax change asks for confirmation naming every field it will alter, because the number decides
 * what every future sale charges the customer and owes the government.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    vm: SettingsViewModel,
    session: UserSession?,
    onNavigateToEntities: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToSalesHistory: () -> Unit,
    onNavigateToMoney: () -> Unit,
    onNavigateToStockHistory: () -> Unit = {},
    onSignOut: () -> Unit,
) {
    val colors = AromexTheme.colors
    var isSidebarExpanded by remember { mutableStateOf(false) }
    val sidebarSrc = remember { MutableInteractionSource() }
    val contentSrc = remember { MutableInteractionSource() }
    val isSidebarHovered by sidebarSrc.collectIsHoveredAsState()
    val isContentHovered by contentSrc.collectIsHoveredAsState()
    LaunchedEffect(isSidebarHovered) { if (isSidebarHovered && !isSidebarExpanded) isSidebarExpanded = true }
    LaunchedEffect(isContentHovered, isSidebarHovered) { if (isContentHovered && !isSidebarHovered && isSidebarExpanded) isSidebarExpanded = false }
    val sidebarWidth by animateDpAsState(
        targetValue = if (isSidebarExpanded) ExpandedSidebarWidth else CollapsedSidebarWidth,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "sidebarWidth",
    )

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize()) {
            NavSidebar(
                expanded = isSidebarExpanded,
                width = sidebarWidth,
                modifier = Modifier.zIndex(1f),
                selectedSection = DesktopSection.SETTINGS,
                session = session,
                interactionSource = sidebarSrc,
                onNavigateToEntities = onNavigateToEntities,
                onNavigateToInventory = onNavigateToInventory,
                onNavigateToSales = onNavigateToSales,
                onNavigateToSalesHistory = onNavigateToSalesHistory,
                onNavigateToMoney = onNavigateToMoney,
                onNavigateToStockHistory = onNavigateToStockHistory,
                onNavigateToSettings = {},
                onSignOutRequest = onSignOut,
            )
            Box(Modifier.fillMaxSize().padding(start = CollapsedSidebarWidth).hoverable(contentSrc)) {
                SettingsContent(state, vm)
            }
        }
    }

    // Naming every field that will change is the point — "are you sure?" tells nobody anything.
    state.pendingTaxConfirm?.let { changes ->
        AromexDialog(
            title = strings(Strings.settings_tax_confirm_title),
            message = buildString {
                append(strings(Strings.settings_tax_confirm_intro))
                append("\n\n")
                changes.forEach { (field, old, new) ->
                    append("• $field: ${old ?: "—"} → ${new.ifEmpty { "—" }}\n")
                }
                append("\n")
                append(strings(Strings.settings_tax_confirm_past))
            },
            confirmLabel = strings(Strings.settings_save),
            dismissLabel = strings(Strings.money_cancel),
            onConfirm = vm::confirmSaveTax,
            onDismiss = vm::dismissTaxConfirm,
        )
    }
}

@Composable
private fun SettingsContent(state: SettingsUiState, vm: SettingsViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dims.space24, vertical = dims.space20),
        verticalArrangement = Arrangement.spacedBy(dims.space16),
    ) {
        Column {
            Text(strings(Strings.settings_title), style = typography.screenTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                if (state.isAdmin) strings(Strings.settings_subtitle) else strings(Strings.settings_readonly),
                style = typography.hint,
                color = colors.textTertiary,
            )
        }

        state.error?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dims.radiusField))
                    .background(colors.error.copy(alpha = 0.08f))
                    .border(1.dp, colors.error.copy(alpha = 0.25f), RoundedCornerShape(dims.radiusField))
                    .padding(horizontal = dims.space12, vertical = 8.dp),
            ) {
                Text(message, style = typography.hint, color = colors.error)
            }
        }

        TaxCard(state, vm)
        DetailsCard(state, vm)
        if (state.isAdmin) ChangeLogCard(state)
        Spacer(Modifier.height(dims.space16))
    }
}

// ── tax ─────────────────────────────────────────────────────────────────────

@Composable
private fun TaxCard(state: SettingsUiState, vm: SettingsViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val enabled = state.isAdmin
    val form = state.taxForm

    SettingsCard(
        title = strings(Strings.settings_tax_title),
        subtitle = strings(Strings.settings_tax_subtitle),
    ) {
        ToggleRow(
            label = strings(Strings.settings_gst),
            checked = form.gstEnabled,
            enabled = enabled,
            onCheckedChange = vm::setGstEnabled,
        )
        if (form.gstEnabled) {
            RateField(
                label = if (form.isHST) strings(Strings.settings_hst_rate) else strings(Strings.settings_gst_rate),
                value = form.gstPercent,
                enabled = enabled,
                invalid = state.taxError == TaxConfigError.GstRateInvalid,
                onChange = vm::setGstPercent,
            )
        }

        HorizontalDivider(color = colors.border.copy(alpha = 0.6f))

        ToggleRow(
            label = strings(Strings.settings_pst),
            checked = form.pstEnabled,
            // HST is one combined line; PST on top would double-charge, so it's locked off.
            enabled = enabled && !form.isHST,
            onCheckedChange = vm::setPstEnabled,
        )
        if (form.pstEnabled) {
            RateField(
                label = strings(Strings.settings_pst_rate),
                value = form.pstPercent,
                enabled = enabled,
                invalid = state.taxError == TaxConfigError.PstRateInvalid,
                onChange = vm::setPstPercent,
            )
        }

        HorizontalDivider(color = colors.border.copy(alpha = 0.6f))

        ToggleRow(
            label = strings(Strings.settings_hst),
            hint = strings(Strings.settings_hst_hint),
            checked = form.isHST,
            enabled = enabled,
            onCheckedChange = vm::setHst,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                when {
                    state.taxError == TaxConfigError.GstRateInvalid ->
                        Note(strings(Strings.settings_err_gst_rate), colors.error)
                    state.taxError == TaxConfigError.PstRateInvalid ->
                        Note(strings(Strings.settings_err_pst_rate), colors.error)
                    state.taxError == TaxConfigError.HstNeedsGst ->
                        Note(strings(Strings.settings_err_hst_needs_gst), colors.error)
                    // Not an error — a shop that isn't tax-registered charges nothing.
                    state.taxError == TaxConfigError.NothingEnabled ->
                        Note(strings(Strings.settings_warn_no_tax), colors.warning)
                    state.taxSaved -> Note(strings(Strings.settings_saved), colors.success)
                }
            }
            if (enabled) {
                PrimaryButton(
                    label = strings(Strings.settings_save),
                    onClick = vm::askSaveTax,
                    enabled = state.canSaveTax,
                    loading = state.isSavingTax,
                    loadingLabel = strings(Strings.settings_saving),
                    modifier = Modifier.widthIn(min = 130.dp).height(FIELD_HEIGHT),
                )
            }
        }
    }
}

// ── company details ─────────────────────────────────────────────────────────

@Composable
private fun DetailsCard(state: SettingsUiState, vm: SettingsViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val enabled = state.isAdmin
    val form = state.detailsForm

    SettingsCard(
        title = strings(Strings.settings_company_title),
        subtitle = strings(Strings.settings_company_subtitle),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
            TextField(strings(Strings.settings_company_name), form.companyName, enabled, Modifier.weight(1f), vm::setCompanyName)
            TextField(strings(Strings.settings_legal_name), form.legalName, enabled, Modifier.weight(1f), vm::setLegalName)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
            TextField(strings(Strings.settings_tax_number), form.taxNumber, enabled, Modifier.weight(1f), vm::setTaxNumber)
            TextField(strings(Strings.settings_contact_phone), form.contactPhone, enabled, Modifier.weight(1f), vm::setContactPhone)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
            TextField(strings(Strings.settings_contact_email), form.contactEmail, enabled, Modifier.weight(1f), vm::setContactEmail)
            TextField(strings(Strings.settings_address), form.businessAddress, enabled, Modifier.weight(1f), vm::setBusinessAddress)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                if (state.detailsSaved) Note(strings(Strings.settings_saved), colors.success)
            }
            if (enabled) {
                PrimaryButton(
                    label = strings(Strings.settings_save),
                    onClick = vm::saveDetails,
                    enabled = state.canSaveDetails,
                    loading = state.isSavingDetails,
                    loadingLabel = strings(Strings.settings_saving),
                    modifier = Modifier.widthIn(min = 130.dp).height(FIELD_HEIGHT),
                )
            }
        }
    }
}

// ── change log ──────────────────────────────────────────────────────────────

@Composable
private fun ChangeLogCard(state: SettingsUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    SettingsCard(
        title = strings(Strings.settings_log_title),
        subtitle = strings(Strings.settings_log_subtitle),
    ) {
        if (state.changes.isEmpty()) {
            Text(strings(Strings.settings_log_empty), style = typography.hint, color = colors.textTertiary)
            return@SettingsCard
        }
        state.changes.forEach { change ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                change.field,
                                style = typography.body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${change.oldValue?.ifEmpty { "—" } ?: "—"} → ${change.newValue.ifEmpty { "—" }}",
                                style = typography.hint,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "${change.changedByName ?: change.changedBy} · ${logDate.format(Date(change.changedAt))}",
                            style = typography.hint.copy(fontSize = 10.sp),
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            HorizontalDivider(color = colors.border.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

// ── building blocks ─────────────────────────────────────────────────────────

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScopeMarker.() -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 900.dp)
            .clip(RoundedCornerShape(dims.radiusCard))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(dims.radiusCard))
            .padding(dims.space20),
        verticalArrangement = Arrangement.spacedBy(dims.space12),
    ) {
        Column {
            Text(title, style = typography.sectionTitle, color = colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = typography.hint, color = colors.textTertiary)
        }
        ColumnScopeMarker.content()
    }
}

/** Lets [SettingsCard] take a content block without leaking a Compose scope receiver. */
internal object ColumnScopeMarker

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    hint: String? = null,
) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = typography.body, color = colors.textPrimary)
            hint?.let {
                Text(it, style = typography.hint, color = colors.textTertiary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onBrand,
                checkedTrackColor = colors.brand,
            ),
        )
    }
}

@Composable
private fun RateField(
    label: String,
    value: String,
    enabled: Boolean,
    invalid: Boolean,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(180.dp)) {
        FieldLabel(label)
        Spacer(Modifier.height(LABEL_GAP))
        FieldShell(
            modifier = Modifier.fillMaxWidth(),
            focused = focused,
            invalid = invalid,
            enabled = enabled,
            // The unit lives in the field, so nobody has to guess whether 5 means 5% or 500%.
            trailing = {
                Text("%", style = AromexTheme.typography.body, color = AromexTheme.colors.textTertiary)
            },
        ) {
            ShellTextField(
                value = value,
                onValueChange = onChange,
                placeholder = "0",
                enabled = enabled,
                onFocusChanged = { focused = it },
            )
        }
    }
}

@Composable
private fun TextField(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(LABEL_GAP))
        FieldShell(modifier = Modifier.fillMaxWidth(), focused = focused, enabled = enabled) {
            ShellTextField(
                value = value,
                onValueChange = onChange,
                placeholder = "",
                enabled = enabled,
                onFocusChanged = { focused = it },
            )
        }
    }
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text, style = AromexTheme.typography.hint, color = color, maxLines = 2)
}

private val logDate = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault())
