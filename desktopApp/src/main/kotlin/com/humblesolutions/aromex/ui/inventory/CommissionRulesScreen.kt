package com.humblesolutions.aromex.ui.inventory

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.components.DesktopSection
import com.humblesolutions.aromex.ui.components.FilterableDropdownField
import androidx.compose.ui.zIndex
import com.humblesolutions.aromex.ui.components.CollapsedSidebarWidth
import com.humblesolutions.aromex.ui.components.ExpandedSidebarWidth
import com.humblesolutions.aromex.ui.components.NavSidebar
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.MoneyFormat

/**
 * Admin-only commission-rules settings (ticket #97) — the standing arrangements in plain terms:
 * location → payee → per-phone or percent-of-cost. Add / edit / switch off; a switched-off rule
 * stops firing at intake but never touches commission already earned. Ledger-style table
 * (hairlines, zebra, fixed row height) that resizes with the window like the inventory tables.
 */
@Composable
fun CommissionRulesScreen(
    vm: CommissionRulesViewModel,
    state: CommissionRulesUiState,
    session: UserSession?,
    onNavigateToEntities: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToSalesHistory: () -> Unit,
    onNavigateToMoney: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStockHistory: () -> Unit = {},
    onSignOut: () -> Unit,
) {
    val colors = AromexTheme.colors

    var isSidebarExpanded by remember { mutableStateOf(false) }
    val sidebarSrc = remember { MutableInteractionSource() }
    val contentSrc = remember { MutableInteractionSource() }
    val isSidebarHovered by sidebarSrc.collectIsHoveredAsState()
    val isContentHovered by contentSrc.collectIsHoveredAsState()
    androidx.compose.runtime.LaunchedEffect(isSidebarHovered) { if (isSidebarHovered && !isSidebarExpanded) isSidebarExpanded = true }
    androidx.compose.runtime.LaunchedEffect(isContentHovered, isSidebarHovered) { if (isContentHovered && !isSidebarHovered && isSidebarExpanded) isSidebarExpanded = false }
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
                selectedSection = DesktopSection.COMMISSION_RULES,
                session = session,
                interactionSource = sidebarSrc,
                onNavigateToEntities = onNavigateToEntities,
                onNavigateToInventory = onNavigateToInventory,
                onNavigateToSales = onNavigateToSales,
                onNavigateToSalesHistory = onNavigateToSalesHistory,
                onNavigateToMoney = onNavigateToMoney,
                onNavigateToCommissionRules = {},
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStockHistory = onNavigateToStockHistory,
                onSignOutRequest = onSignOut,
            )
            Box(Modifier.fillMaxSize().padding(start = CollapsedSidebarWidth).hoverable(contentSrc)) {
                CommissionRulesContent(vm, state)
            }
        }
    }

    if (state.showDialog) {
        CommissionRuleDialog(vm, state)
    }
}

@Composable
private fun CommissionRulesContent(vm: CommissionRulesViewModel, state: CommissionRulesUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    if (!state.isAdmin) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings(Strings.commission_rules_no_access), style = typography.body, color = colors.textSecondary)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = dims.space24, vertical = dims.space20),
        verticalArrangement = Arrangement.spacedBy(dims.space16),
    ) {
        // ── Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(strings(Strings.commission_rules_title), style = typography.screenTitle, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(strings(Strings.commission_rules_subtitle), style = typography.hint, color = colors.textTertiary)
            }
            Button(
                onClick = vm::openAddDialog,
                shape = RoundedCornerShape(dims.radiusButton),
                colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                modifier = Modifier.height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings(Strings.commission_rules_add), style = typography.button, softWrap = false)
            }
        }

        state.error?.let { err ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(err, color = colors.error, style = typography.hint, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::dismissError, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Text("Dismiss", color = colors.brand)
                }
            }
        }

        if (state.rules.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dims.space12)) {
                    Icon(Icons.Filled.Payments, null, tint = colors.textTertiary, modifier = Modifier.size(40.dp))
                    Text(strings(Strings.commission_rules_empty), color = colors.textTertiary, style = typography.body)
                }
            }
            return@Column
        }

        // ── Ledger-style table (resizes with the window via weighted columns)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface),
        ) {
            RulesTableHeader()
            HorizontalDivider(color = colors.border)
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(state.rules) { index, rule ->
                    RuleRow(vm, state, rule, zebra = index % 2 == 1)
                    HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun RulesTableHeader() {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt).padding(horizontal = dims.space16, vertical = dims.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = typography.fieldLabel
        val tint = colors.textTertiary
        Text(strings(Strings.commission_rules_col_location), style = label, color = tint, modifier = Modifier.weight(0.28f))
        Text(strings(Strings.commission_rules_col_payee), style = label, color = tint, modifier = Modifier.weight(0.28f))
        Text(strings(Strings.commission_rules_col_rate), style = label, color = tint, modifier = Modifier.weight(0.22f))
        Text(strings(Strings.commission_rules_col_status), style = label, color = tint, modifier = Modifier.weight(0.10f))
        Box(Modifier.weight(0.12f)) {}
    }
}

@Composable
private fun RuleRow(
    vm: CommissionRulesViewModel,
    state: CommissionRulesUiState,
    rule: CommissionRule,
    zebra: Boolean,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    val locationName = state.locations.firstOrNull { it.attributeId == rule.locationAttributeId }?.name
        ?: rule.locationAttributeId
    val payeeName = state.payees.firstOrNull { it.id == rule.payeeEntityId }?.name ?: rule.payeeEntityId
    val rateText = when (rule.rateKind) {
        RateKind.PER_UNIT -> strings(Strings.commission_rate_per_unit_value, MoneyFormat.format(rule.rate, state.currency))
        RateKind.PERCENT_OF_COST -> strings(Strings.commission_rate_percent_value, percentLabel(rule.rate))
    }
    val rowBg = if (zebra) colors.surfaceAlt.copy(alpha = 0.4f) else Color.Transparent

    // One SelectionContainer around the whole row: a press-and-drag selects location, payee and
    // rate together in a single motion; the on/off Switch and Edit button still click normally.
    SelectionContainer {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBg)
            .height(52.dp)
            .padding(horizontal = dims.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(locationName, style = typography.body, color = colors.textPrimary, modifier = Modifier.weight(0.28f))
        Text(payeeName, style = typography.body, color = colors.textPrimary, modifier = Modifier.weight(0.28f))
        Text(rateText, style = typography.body, color = colors.textSecondary, modifier = Modifier.weight(0.22f))
        // On/off switch — toggles isActive both ways (off never touches earned commission).
        Box(Modifier.weight(0.10f)) {
            Switch(
                checked = rule.isActive,
                onCheckedChange = { vm.setRuleActive(rule, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = colors.brand),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            )
        }
        Row(Modifier.weight(0.12f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.openEditDialog(rule) }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Text(strings(Strings.commission_rule_edit), color = colors.brand, style = typography.hint)
            }
        }
    }
    }
}

@Composable
private fun CommissionRuleDialog(vm: CommissionRulesViewModel, state: CommissionRulesUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val form = state.form
    val scrimColor = colors.background.copy(alpha = 0.75f)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { vm.dismissDialog(); true } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.dismissDialog() },
        contentAlignment = Alignment.Center,
    ) {
        // Cap the card to the window so a short window scrolls the body instead of clipping.
        val maxCardHeight = maxHeight * 0.94f
        Column(
            modifier = Modifier
                .width(480.dp)
                .heightIn(max = maxCardHeight)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                    .padding(dims.space20),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Payments, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(dims.space12))
                    Column(Modifier.weight(1f)) {
                        Text(strings(Strings.commission_rules_eyebrow), style = typography.fieldLabel, color = Color.White.copy(alpha = 0.6f))
                        Text(
                            strings(if (form.isEditing) Strings.commission_rule_dialog_edit else Strings.commission_rule_dialog_add),
                            style = typography.sectionTitle,
                            color = Color.White,
                        )
                    }
                    IconButton(onClick = vm::dismissDialog, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.Filled.Close, strings(Strings.commission_rule_close_cd), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            // Body (scrolls between the pinned header + footer when the window is short)
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(dims.space20),
                verticalArrangement = Arrangement.spacedBy(dims.space16),
            ) {
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

                // Rate type toggle
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings(Strings.commission_rule_field_kind), style = typography.fieldLabel, color = colors.textTertiary)
                    Row(horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                        CommissionToggle(
                            label = strings(Strings.commission_rate_per_unit),
                            selected = form.rateKind == RateKind.PER_UNIT,
                            onClick = { vm.setFormRateKind(RateKind.PER_UNIT) },
                        )
                        CommissionToggle(
                            label = strings(Strings.commission_rate_percent),
                            selected = form.rateKind == RateKind.PERCENT_OF_COST,
                            onClick = { vm.setFormRateKind(RateKind.PERCENT_OF_COST) },
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings(Strings.commission_rule_field_rate), style = typography.fieldLabel, color = colors.textTertiary)
                    OutlinedTextField(
                        value = form.rateInput,
                        onValueChange = vm::setFormRate,
                        singleLine = true,
                        placeholder = {
                            Text(
                                strings(
                                    if (form.rateKind == RateKind.PER_UNIT) Strings.commission_rule_rate_hint_per_unit
                                    else Strings.commission_rule_rate_hint_percent,
                                ),
                                color = colors.textTertiary,
                            )
                        },
                        colors = fieldColors(),
                        shape = RoundedCornerShape(dims.radiusField),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HorizontalDivider(color = colors.border)
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space16), horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
                TextButton(onClick = vm::dismissDialog, modifier = Modifier.weight(1f).height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand)) {
                    Text(strings(Strings.commission_rule_cancel), color = colors.textSecondary)
                }
                Button(
                    onClick = vm::save,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f).height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(strings(Strings.commission_rule_save), style = typography.button, softWrap = false)
                    }
                }
            }
        }
    }
}

/** Adapt an [Entity] to the [AttributeValue] the shared dropdown renders (id + name only used). */
private fun Entity.asAttributeValue(): AttributeValue =
    AttributeValue(attributeId = id, type = AttributeType.LOCATION, name = name)
