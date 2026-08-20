package com.humblesolutions.aromex.ui.sales

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.WALK_IN_CUSTOMER_ID
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.MoneyFormat

/**
 * Android entry point (ticket #64): binds [SalesViewModel] and gates the whole feature on the
 * `sales` VIEW capability **in the UI layer only** — [SalesViewModel] carries no permission
 * logic (T2 is consumed as-is), unlike Inventory/Entities whose VMs own that check.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesFeature(authenticated: AuthenticatedSession, onExit: () -> Unit) {
    val vm: SalesViewModel = viewModel()
    LaunchedEffect(authenticated.session.uid, authenticated.config) {
        vm.bind(authenticated.session, authenticated.config)
    }

    if (authenticated.session.permissions.sales == PermissionLevel.NONE) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(strings(Strings.entities_sidebar_sales)) },
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, strings(Strings.sales_close_cd))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(strings(Strings.sales_no_access), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val state by vm.uiState.collectAsStateWithLifecycle()
    SalesScreen(state = state, vm = vm, onExit = onExit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SalesScreen(state: SalesUiState, vm: SalesViewModel, onExit: () -> Unit) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showAddCustom by rememberSaveable { mutableStateOf(false) }
    var showCustomerPicker by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val locked = state.confirmState is ConfirmState.Submitting

    LaunchedEffect(state.confirmState) {
        val current = state.confirmState
        if (current is ConfirmState.Error) {
            snackbarHostState.showSnackbar(current.message)
            vm.dismissConfirmState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings(Strings.entities_sidebar_sales)) },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, strings(Strings.sales_close_cd))
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(containerColor = MaterialTheme.colorScheme.errorContainer) {
                    Text(data.visuals.message, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Tap-outside-a-field dismisses the keyboard. detectTapGestures is transparent
                // to descendants (fields consume their taps first), so it only fires on blank space.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                }
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CartSection(
                state = state,
                vm = vm,
                enabled = !locked,
                onAddPhone = { showPicker = true },
                onAddItem = { showAddCustom = true },
            )
            CustomerSection(
                state = state,
                enabled = !locked,
                onOpenPicker = { showCustomerPicker = true },
                onWalkIn = vm::selectWalkIn,
            )
            if (state.isWalkIn) {
                BuyerSection(state = state, vm = vm, enabled = !locked)
            }
            if (state.selectedCustomer != null) {
                CustomerTaxNumberSection(state = state, vm = vm, enabled = !locked)
            }
            // Named customer only — a walk-in captures its phone in BuyerSection above.
            if (state.selectedCustomer != null && !state.isWalkIn) {
                CustomerPhoneSection(state = state, vm = vm, enabled = !locked)
            }
            PaymentSection(state = state, vm = vm, enabled = !locked)
            NoteSection(state = state, vm = vm, enabled = !locked)
            TaxInclusiveToggle(state = state, vm = vm, enabled = !locked)
            TotalsSection(state = state)

            if (state.errors.emptyCart) {
                ErrorHint(strings(Strings.sales_error_empty_cart))
            }

            Button(
                onClick = vm::confirmSale,
                enabled = state.canConfirm,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (locked) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(strings(Strings.sales_confirm_submitting))
                } else {
                    Text(strings(Strings.sales_confirm_button))
                }
            }
            // Bottom breathing room so the last row clears the gesture bar / Confirm stays reachable.
            Spacer(Modifier.height(4.dp))
        }
    }

    if (showPicker) {
        ItemPickerDialog(
            state = state,
            onSearchChange = vm::onPickerSearchChanged,
            onAdd = vm::addUnitToCart,
            onDismiss = { showPicker = false },
        )
    }
    if (showAddCustom) {
        AddCustomLineDialog(
            currency = state.currency,
            onDismiss = { showAddCustom = false },
            onAdd = { name, price -> vm.addCustomLine(name, price); showAddCustom = false },
        )
    }
    if (showCustomerPicker) {
        CustomerPickerDialog(
            state = state,
            onSearchChange = vm::onCustomerSearchChanged,
            onSelect = { customer ->
                if (customer.id == WALK_IN_CUSTOMER_ID) vm.selectWalkIn() else vm.selectCustomer(customer)
                showCustomerPicker = false
            },
            onDismiss = { showCustomerPicker = false },
        )
    }
    when (val confirmState = state.confirmState) {
        is ConfirmState.Success -> SaleCompleteDialog(
            state = state,
            onNewSale = vm::startNewSale,
            onRetryInvoice = vm::retryInvoice,
        )
        is ConfirmState.AlreadySold -> AlertDialog(
            onDismissRequest = vm::dismissConfirmState,
            title = { Text(strings(Strings.sales_already_sold_title)) },
            text = { Text(strings(Strings.sales_already_sold_body, confirmState.label)) },
            confirmButton = {
                TextButton(onClick = vm::dismissConfirmState) { Text(strings(Strings.sales_already_sold_dismiss)) }
            },
        )
        else -> Unit
    }
}

// ── Cart ───────────────────────────────────────────────────────────────────

@Composable
private fun CartSection(
    state: SalesUiState,
    vm: SalesViewModel,
    enabled: Boolean,
    onAddPhone: () -> Unit,
    onAddItem: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(strings(Strings.sales_cart_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onAddItem, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings(Strings.sales_cart_add_item))
            }
            Button(onClick = onAddPhone, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(strings(Strings.sales_cart_add_phone))
            }
        }

        if (state.cartLines.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(strings(Strings.sales_cart_empty_title), style = MaterialTheme.typography.bodyMedium)
                Text(
                    strings(Strings.sales_cart_empty_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            state.cartLines.forEach { line ->
                CartLineRow(
                    line = line,
                    currency = state.currency,
                    hasError = line.lineId in state.errors.lineDiscountExceedsPrice,
                    enabled = enabled,
                    onPriceChange = { vm.setUnitPrice(line.lineId, it) },
                    onDiscountChange = { vm.setLineDiscount(line.lineId, it) },
                    onRemove = { vm.removeLine(line.lineId) },
                )
                HorizontalDivider()
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(strings(Strings.sales_cart_sale_discount_label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            MoneyField(
                value = state.saleDiscount,
                onValueChange = vm::setSaleDiscount,
                enabled = enabled,
                imeAction = ImeAction.Next,
                modifier = Modifier.width(120.dp),
            )
        }
        if (state.errors.saleDiscountExceedsSubtotal) {
            ErrorHint(strings(Strings.sales_error_sale_discount))
        }
    }
}

@Composable
private fun CartLineRow(
    line: CartLine,
    currency: String,
    hasError: Boolean,
    enabled: Boolean,
    onPriceChange: (String) -> Unit,
    onDiscountChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val net = Money.subtract(line.unitPrice.ifBlankMoney(), line.lineDiscount.ifBlankMoney())
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (line) {
                        is CartLine.Inventory -> line.label
                        is CartLine.Custom -> line.name
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (line is CartLine.Inventory) {
                    Text(
                        line.imei,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Filled.Close, strings(Strings.sales_cart_remove_cd))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(strings(Strings.sales_cart_col_price), style = MaterialTheme.typography.labelSmall)
                MoneyField(value = line.unitPrice, onValueChange = onPriceChange, enabled = enabled, imeAction = ImeAction.Next)
            }
            Column(Modifier.weight(1f)) {
                Text(strings(Strings.sales_cart_col_discount), style = MaterialTheme.typography.labelSmall)
                MoneyField(value = line.lineDiscount, onValueChange = onDiscountChange, enabled = enabled, imeAction = ImeAction.Next, isError = hasError)
            }
            Column(Modifier.weight(1f)) {
                Text(strings(Strings.sales_cart_col_net), style = MaterialTheme.typography.labelSmall)
                Text(MoneyFormat.format(net, currency), style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (hasError) ErrorHint(strings(Strings.sales_cart_line_discount_error))
    }
}

// ── Customer ─────────────────────────────────────────────────────────────────

@Composable
private fun CustomerSection(state: SalesUiState, enabled: Boolean, onOpenPicker: () -> Unit, onWalkIn: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Strings.sales_checkout_customer_label), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenPicker, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(
                state.selectedCustomer?.name ?: strings(Strings.sales_checkout_customer_placeholder),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onWalkIn, enabled = enabled) {
            Text(strings(Strings.sales_checkout_walk_in_button))
        }
        if (state.errors.noCustomer) ErrorHint(strings(Strings.sales_error_no_customer))
    }
}

// ── Payment ──────────────────────────────────────────────────────────────────

@Composable
private fun PaymentSection(state: SalesUiState, vm: SalesViewModel, enabled: Boolean) {
    val symbol = MoneyFormat.symbolOf(state.currency)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(strings(Strings.sales_checkout_cash, symbol), style = MaterialTheme.typography.labelMedium)
                MoneyField(value = state.payments.cash, onValueChange = vm::setCash, enabled = enabled, imeAction = ImeAction.Next)
            }
            Column(Modifier.weight(1f)) {
                Text(strings(Strings.sales_checkout_card, symbol), style = MaterialTheme.typography.labelMedium)
                MoneyField(value = state.payments.card, onValueChange = vm::setCard, enabled = enabled, imeAction = ImeAction.Next)
            }
            Column(Modifier.weight(1f)) {
                Text(strings(Strings.sales_checkout_bank, symbol), style = MaterialTheme.typography.labelMedium)
                MoneyField(value = state.payments.bank, onValueChange = vm::setBank, enabled = enabled, imeAction = ImeAction.Done)
            }
        }
        if (state.errors.overpayment) ErrorHint(strings(Strings.sales_error_overpayment))
        if (state.errors.walkInMustPayInFull) ErrorHint(strings(Strings.sales_error_walk_in_full))
    }
}

// ── Walk-in buyer capture (ticket #77) ───────────────────────────────────────

/**
 * Optional Bill-To name/phone for a walk-in's invoice — shown only for the anonymous party
 * (the caller gates on [SalesUiState.isWalkIn]). Free-text, never blocks Confirm; blank → the
 * PDF reads "Walk-in Customer". Stock components (bare-but-stable per the platform strategy).
 */
@Composable
private fun BuyerSection(state: SalesUiState, vm: SalesViewModel, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Strings.sales_buyer_name_label), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = state.buyerName,
            onValueChange = vm::setBuyerName,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(strings(Strings.sales_buyer_name_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(strings(Strings.sales_buyer_phone_label), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = state.buyerPhone,
            onValueChange = vm::setBuyerPhone, // VM strips non-digits + caps at 10
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            placeholder = { Text(strings(Strings.sales_buyer_phone_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Customer tax number at checkout (ticket #106 follow-up): prefilled from the selected customer,
 * editable for this invoice, with an optional "Save to contact" (named customer + profiles:manage).
 * A walk-in gets the field but no save. Stock components per the platform strategy.
 */
@Composable
private fun CustomerTaxNumberSection(state: SalesUiState, vm: SalesViewModel, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Strings.sales_checkout_buyer_tax_number_label), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = state.buyerTaxNumber,
            onValueChange = vm::setBuyerTaxNumber,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(strings(Strings.sales_checkout_buyer_tax_number_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.savingTaxNumber -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(strings(Strings.sales_action_save_tax_to_contact), style = MaterialTheme.typography.bodySmall)
            }
            state.taxNumberSaveError -> ErrorHint(strings(Strings.sales_tax_save_error))
            state.taxNumberSaved -> Text(
                strings(Strings.sales_tax_saved_to_contact),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            state.canSaveTaxToContact -> TextButton(
                onClick = vm::saveBuyerTaxNumberToContact,
                enabled = enabled,
            ) {
                Text(strings(Strings.sales_action_save_tax_to_contact))
            }
        }
    }
}

/**
 * Customer phone at checkout — the twin of [CustomerTaxNumberSection]. Prefilled from the named
 * customer's primary number, editable for this invoice, with an optional "Save to contact"
 * (profiles:manage). Shown for a named customer only; a walk-in uses [BuyerSection] instead.
 */
@Composable
private fun CustomerPhoneSection(state: SalesUiState, vm: SalesViewModel, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Strings.sales_buyer_phone_label), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = state.buyerContactPhone,
            onValueChange = vm::setBuyerContactPhone,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            placeholder = { Text(strings(Strings.sales_buyer_phone_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )
        when {
            state.savingPhone -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(strings(Strings.sales_action_save_tax_to_contact), style = MaterialTheme.typography.bodySmall)
            }
            state.phoneSaveError -> ErrorHint(strings(Strings.sales_tax_save_error))
            state.phoneSaved -> Text(
                strings(Strings.sales_tax_saved_to_contact),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            state.canSavePhoneToContact -> TextButton(
                onClick = vm::saveBuyerPhoneToContact,
                enabled = enabled,
            ) {
                Text(strings(Strings.sales_action_save_tax_to_contact))
            }
        }
    }
}

// ── Note ───────────────────────────────────────────────────────────────────

@Composable
private fun NoteSection(state: SalesUiState, vm: SalesViewModel, enabled: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Strings.sales_checkout_note_label), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(
            value = state.note,
            onValueChange = vm::setNote,
            enabled = enabled,
            placeholder = { Text(strings(Strings.sales_checkout_note_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Tax-inclusive toggle (ticket #106) ───────────────────────────────────────

/** Per-sale toggle: when on, typed prices already contain tax and the totals back it out. Resets
 *  to off on each new sale (handled in the ViewModel). */
@Composable
private fun TaxInclusiveToggle(state: SalesUiState, vm: SalesViewModel, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            strings(Strings.sales_checkout_tax_inclusive_label),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = state.taxInclusive,
            onCheckedChange = { if (enabled) vm.setTaxInclusive(it) },
            enabled = enabled,
        )
    }
}

// ── Totals ───────────────────────────────────────────────────────────────────

@Composable
private fun TotalsSection(state: SalesUiState) {
    val currency = state.currency
    val totals = state.totals
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TotalsRow(strings(Strings.sales_totals_subtotal), MoneyFormat.format(totals.subtotal, currency))
        totals.taxLines.forEach { tax ->
            TotalsRow("${tax.name} (${tax.rate})", MoneyFormat.format(tax.amount, currency))
        }
        HorizontalDivider()
        TotalsRow(strings(Strings.sales_totals_grand_total), MoneyFormat.format(totals.grandTotal, currency), emphasize = true)
        TotalsRow(strings(Strings.sales_totals_paid), MoneyFormat.format(state.amountPaid, currency))
        TotalsRow(strings(Strings.sales_totals_balance), MoneyFormat.format(state.balanceRemaining, currency), emphasize = true)
    }
}

@Composable
private fun TotalsRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (emphasize) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
        Text(value, style = if (emphasize) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun ErrorHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    imeAction: ImeAction,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filterToDecimalInput()) },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        placeholder = { Text("0") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        // Done explicitly hides the keyboard. ImeAction.Next fields keep Compose's default
        // (advance focus) — a no-op onDone previously swallowed Done, leaving no way to dismiss.
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); keyboardController?.hide() }),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    )
}

private fun String.ifBlankMoney(): String = if (Money.isValidPositiveDecimal(trim())) trim() else "0"

/** Keeps only digits and (at most) one decimal point — [KeyboardType.Decimal] only hints a soft
 *  keyboard and doesn't stop a physical/Bluetooth keyboard from typing letters. */
private fun String.filterToDecimalInput(): String {
    val kept = filter { it.isDigit() || it == '.' }
    val firstDot = kept.indexOf('.')
    if (firstDot == -1) return kept
    return kept.substring(0, firstDot + 1) + kept.substring(firstDot + 1).replace(".", "")
}

// ── Item picker ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPickerDialog(
    state: SalesUiState,
    onSearchChange: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(strings(Strings.sales_picker_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, strings(Strings.sales_close_cd)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = state.pickerSearchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(strings(Strings.sales_picker_search)) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                val visible = state.visibleUnits
                if (visible.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(strings(Strings.sales_picker_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visible, key = { it.serialId }) { serial ->
                            PickerUnitRow(serial = serial, label = state.labelFor(serial), currency = state.currency, onAdd = { onAdd(serial.serialId) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PickerUnitRow(serial: Serial, label: String, currency: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                serial.imei,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(strings(Strings.sales_picker_add_cd))
        }
    }
}

// ── Customer picker ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomerPickerDialog(
    state: SalesUiState,
    onSearchChange: (String) -> Unit,
    onSelect: (Entity) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(strings(Strings.sales_checkout_customer_label)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, strings(Strings.sales_close_cd)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = state.customerSearchQuery,
                    onValueChange = onSearchChange,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text(strings(Strings.sales_checkout_customer_placeholder)) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                val customers = state.customerOptions
                if (customers.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(strings(Strings.inventory_dropdown_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(customers, key = { it.id }) { customer ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics { contentDescription = customer.name }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                            ) {
                                TextButton(onClick = { onSelect(customer) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(customer.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

// ── Add custom line ──────────────────────────────────────────────────────────

@Composable
private fun AddCustomLineDialog(currency: String, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    val symbol = MoneyFormat.symbolOf(currency)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings(Strings.sales_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    Text(strings(Strings.sales_custom_name_label), style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text(strings(Strings.sales_custom_name_placeholder)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    Text(strings(Strings.sales_custom_price_label, symbol), style = MaterialTheme.typography.labelMedium)
                    MoneyField(value = price, onValueChange = { price = it }, enabled = true, imeAction = ImeAction.Done)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(name.trim(), price.trim()) }, enabled = name.isNotBlank()) {
                Text(strings(Strings.sales_custom_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings(Strings.sales_custom_cancel)) } },
    )
}

// ── Sale complete ────────────────────────────────────────────────────────────

@Composable
private fun SaleCompleteDialog(state: SalesUiState, onNewSale: () -> Unit, onRetryInvoice: () -> Unit) {
    val currency = state.currency
    AlertDialog(
        // No-op: a stray tap outside / back-press must not silently discard the sale summary —
        // only the explicit "New sale" button resets the form (the sale itself is already
        // recorded server-side by the time this shows, so this only guards the confirmation UI).
        onDismissRequest = {},
        title = { Text(strings(Strings.sales_success_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TotalsRow(strings(Strings.sales_success_customer), state.selectedCustomer?.name ?: "—")
                TotalsRow(strings(Strings.sales_success_items), state.cartLines.size.toString())
                TotalsRow(strings(Strings.sales_success_total), MoneyFormat.format(state.totals.grandTotal, currency))
                TotalsRow(strings(Strings.sales_success_paid), MoneyFormat.format(state.amountPaid, currency))
                TotalsRow(strings(Strings.sales_success_balance), MoneyFormat.format(state.balanceRemaining, currency))
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                InvoiceRow(state = state, onRetry = onRetryInvoice)
            }
        },
        confirmButton = {
            // Labelled "Done" (ticket #106) — still calls startNewSale() (clears the cart) so the
            // previous customer's cart never carries over. Always available: a slow/failed invoice
            // must never block the cashier.
            TextButton(onClick = onNewSale) { Text(strings(Strings.sales_success_done)) }
        },
    )
}

/**
 * The invoice row on the Sale-complete dialog (ticket #77), bare-but-stable with stock
 * components. Resolves in place off the live [SalesUiState.invoice]: PENDING → a spinner +
 * "preparing", ISSUED → the number + Open (system browser) + Share (OS share sheet), FAILED →
 * a reassuring message + Retry. Never implies the sale itself failed — it's already committed.
 */
@Composable
private fun InvoiceRow(state: SalesUiState, onRetry: () -> Unit) {
    val context = LocalContext.current
    val invoice = state.invoice
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(strings(Strings.sales_invoice_label), style = MaterialTheme.typography.labelMedium)
        when (invoice.status) {
            SaleInvoiceStatus.PENDING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(strings(Strings.sales_invoice_preparing), style = MaterialTheme.typography.bodyMedium)
            }

            SaleInvoiceStatus.ISSUED -> {
                Text(
                    invoice.number ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val url = invoice.url
                if (url != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }) { Text(strings(Strings.sales_invoice_open)) }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            runCatching { context.startActivity(Intent.createChooser(send, null)) }
                        }) { Text(strings(Strings.sales_invoice_share)) }
                    }
                }
            }

            SaleInvoiceStatus.FAILED -> {
                Text(strings(Strings.sales_invoice_failed), style = MaterialTheme.typography.bodyMedium)
                if (state.isRetryingInvoice) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(strings(Strings.sales_invoice_retrying), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    if (state.invoiceRetryError) {
                        Text(
                            strings(Strings.sales_invoice_retry_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(onClick = onRetry, enabled = state.canRetryInvoice) {
                        Text(strings(Strings.sales_invoice_retry))
                    }
                }
            }
        }
    }
}
