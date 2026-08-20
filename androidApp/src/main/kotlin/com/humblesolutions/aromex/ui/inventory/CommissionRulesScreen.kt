package com.humblesolutions.aromex.ui.inventory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.ui.components.FilterableDropdownField
import com.humblesolutions.aromex.ui.components.LabeledTextField
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.MoneyFormat

/** Admin-only commission-rules settings on Android (ticket #97) — bare but complete. */
@Composable
fun CommissionRulesFeature(authenticated: AuthenticatedSession, onExit: () -> Unit) {
    val vm: CommissionRulesViewModel = viewModel()
    LaunchedEffect(authenticated.session.uid) {
        vm.bind(authenticated.session, authenticated.config)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    CommissionRulesScreen(vm, state, onExit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommissionRulesScreen(
    vm: CommissionRulesViewModel,
    state: CommissionRulesUiState,
    onExit: () -> Unit,
) {
    val colors = AromexTheme.colors
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings(Strings.commission_rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
                },
            )
        },
        floatingActionButton = {
            if (state.isAdmin) {
                FloatingActionButton(onClick = vm::openAddDialog) { Icon(Icons.Filled.Add, null) }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !state.isAdmin -> Centered(strings(Strings.commission_rules_no_access))
                state.rules.isEmpty() -> Centered(strings(Strings.commission_rules_empty))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rules, key = { it.ruleId }) { rule ->
                        RuleRow(vm, state, rule)
                        HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                    }
                }
            }
            state.error?.let { err ->
                Text(
                    err,
                    color = colors.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }

    if (state.showDialog) {
        CommissionRuleDialog(vm, state)
    }
}

@Composable
private fun Centered(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = AromexTheme.colors.textTertiary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RuleRow(vm: CommissionRulesViewModel, state: CommissionRulesUiState, rule: CommissionRule) {
    val colors = AromexTheme.colors
    val locationName = state.locations.firstOrNull { it.attributeId == rule.locationAttributeId }?.name ?: rule.locationAttributeId
    val payeeName = state.payees.firstOrNull { it.id == rule.payeeEntityId }?.name ?: rule.payeeEntityId
    val rateText = when (rule.rateKind) {
        RateKind.PER_UNIT -> strings(Strings.commission_rate_per_unit_value, MoneyFormat.format(rule.rate, state.currency))
        RateKind.PERCENT_OF_COST -> strings(Strings.commission_rate_percent_value, percentLabel(rule.rate))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("$payeeName — $locationName", style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            Text(
                rateText + if (!rule.isActive) "  •  " + strings(Strings.commission_rule_off) else "",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        }
        TextButton(onClick = { vm.openEditDialog(rule) }) { Text(strings(Strings.commission_rule_edit)) }
        // On/off switch — toggles isActive both ways (off never touches earned commission).
        Switch(checked = rule.isActive, onCheckedChange = { vm.setRuleActive(rule, it) })
    }
}

@Composable
private fun CommissionRuleDialog(vm: CommissionRulesViewModel, state: CommissionRulesUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val form = state.form
    Dialog(onDismissRequest = vm::dismissDialog) {
        Surface(shape = RoundedCornerShape(dims.radiusCard), color = colors.surface, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(dims.space24).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(dims.fieldGap),
            ) {
                Text(
                    strings(if (form.isEditing) Strings.commission_rule_dialog_edit else Strings.commission_rule_dialog_add),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.textPrimary,
                )
                FilterableDropdownField(
                    label = strings(Strings.commission_rule_field_location),
                    items = state.locations,
                    selectedItem = form.location,
                    onItemSelected = { vm.setFormLocation(it) },
                    onAddNew = { vm.addLocationInline(it) },
                    placeholder = strings(Strings.commission_rule_field_location_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterableDropdownField(
                    label = strings(Strings.commission_rule_field_payee),
                    items = state.payees.map { it.asAttributeValue() },
                    selectedItem = form.payeeId.takeIf { it.isNotBlank() }?.let { AttributeRef(it, form.payeeName) },
                    onItemSelected = { picked -> state.payees.firstOrNull { it.id == picked.attributeId }?.let { vm.setFormPayee(it) } },
                    onAddNew = { vm.addPayeeInline(it) },
                    placeholder = strings(Strings.commission_rule_field_payee_hint),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(strings(Strings.commission_rule_field_kind), style = MaterialTheme.typography.labelMedium, color = colors.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleToggle(strings(Strings.commission_rate_per_unit), form.rateKind == RateKind.PER_UNIT) { vm.setFormRateKind(RateKind.PER_UNIT) }
                    RuleToggle(strings(Strings.commission_rate_percent), form.rateKind == RateKind.PERCENT_OF_COST) { vm.setFormRateKind(RateKind.PERCENT_OF_COST) }
                }
                LabeledTextField(
                    label = strings(Strings.commission_rule_field_rate),
                    value = form.rateInput,
                    onValueChange = vm::setFormRate,
                    placeholder = strings(
                        if (form.rateKind == RateKind.PER_UNIT) Strings.commission_rule_rate_hint_per_unit
                        else Strings.commission_rule_rate_hint_percent,
                    ),
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = vm::dismissDialog, modifier = Modifier.weight(1f)) {
                        Text(strings(Strings.commission_rule_cancel))
                    }
                    PrimaryButton(
                        label = strings(Strings.commission_rule_save),
                        onClick = vm::save,
                        enabled = !state.saving,
                        loading = state.saving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Box(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(dims.radiusField))
            .background(if (selected) colors.brand.copy(alpha = 0.14f) else colors.surface)
            .border(1.dp, if (selected) colors.brand else colors.border, RoundedCornerShape(dims.radiusField))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = if (selected) colors.brand else colors.textSecondary)
    }
}

/** Adapt an [Entity] to the [AttributeValue] the shared dropdown renders (id + name only used). */
private fun Entity.asAttributeValue(): AttributeValue =
    AttributeValue(attributeId = id, type = AttributeType.LOCATION, name = name)
