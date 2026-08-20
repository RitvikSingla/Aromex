package com.humblesolutions.aromex.ui.inventory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.CommissionLine
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.scanner.ImeiScanner
import com.humblesolutions.aromex.scanner.ImeiScannerScreen
import com.humblesolutions.aromex.ui.components.AromexDialog
import com.humblesolutions.aromex.ui.components.BrandHeader
import com.humblesolutions.aromex.ui.components.FilterableDropdownField
import com.humblesolutions.aromex.ui.components.LabeledTextField
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.util.MoneyFormat
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.SkuKey

@Serializable
private sealed class InventoryRoute {
    @Serializable data object List : InventoryRoute()
    @Serializable data class Detail(val productId: String) : InventoryRoute()
    @Serializable data object AddInventory : InventoryRoute()
}

@Composable
fun InventoryFeature(authenticated: AuthenticatedSession, onExit: () -> Unit) {
    val listVm: InventoryListViewModel = viewModel()
    val addVm: AddStockViewModel = viewModel()

    LaunchedEffect(authenticated.session.uid) {
        listVm.bind(authenticated.session, authenticated.config)
        addVm.bind(authenticated.session, authenticated.config)
    }

    val navController = rememberNavController()
    val listState by listVm.uiState.collectAsStateWithLifecycle()
    val addState by addVm.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(addState.done) {
        if (addState.done) {
            navController.popBackStack()
            addVm.reset()
        }
    }

    NavHost(navController = navController, startDestination = InventoryRoute.List) {
        composable<InventoryRoute.List> {
            InventoryListScreen(
                state = listState,
                onQueryChange = listVm::onQueryChange,
                onOpen = { navController.navigate(InventoryRoute.Detail(it.productId)) },
                onAdd = {
                    addVm.reset()
                    navController.navigate(InventoryRoute.AddInventory)
                },
                onPaste = {
                    addVm.startPaste()
                    navController.navigate(InventoryRoute.AddInventory)
                },
                onExit = onExit,
            )
        }
        composable<InventoryRoute.Detail> { backStack ->
            val route = backStack.toRoute<InventoryRoute.Detail>()
            val product = listState.rows.firstOrNull { it.product.productId == route.productId }?.product
            if (product == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                SkuDetailScreen(
                    product = product,
                    units = listVm.unitsFor(product.productId),
                    canManage = listState.canManage,
                    onBack = { navController.popBackStack() },
                    onAddUnits = {
                        addVm.reset()
                        addVm.startAddUnits(product.productId, product.attributes, product.defaultSellingPrice)
                        navController.navigate(InventoryRoute.AddInventory)
                    },
                    onEditPrice = { price -> listVm.editSellingPrice(product.productId, price) },
                    onArchiveSku = {
                        listVm.archiveProduct(product.productId)
                        navController.popBackStack()
                    },
                    onSellUnit = { listVm.setUnitStatus(it, SerialStatus.SOLD) },
                    onArchiveUnit = { listVm.archiveUnit(it) },
                    onEditUnit = { id, cost, cond -> listVm.editUnit(id, cost, cond) },
                )
            }
        }
        composable<InventoryRoute.AddInventory> {
            AddInventoryScreen(
                vm = addVm,
                state = addState,
                existingProducts = listState.rows.map { it.product },
                onExit = { navController.popBackStack() },
            )
        }
    }
}

// ── Add-Inventory: two-screen flow ──────────────────────────────────────────

@Composable
private fun AddInventoryScreen(
    vm: AddStockViewModel,
    state: AddStockUiState,
    existingProducts: List<Product>,
    onExit: () -> Unit,
) {
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

    val guardedClose = {
        if (vm.hasUnsavedChanges) showDiscardDialog = true else onExit()
    }

    val isPasteBatch = state.lastParsedCount != null

    BackHandler {
        when (state.route) {
            // Manual review returns to entry (non-destructive, #52). Paste review exits
            // via the discard guard — there is no manual entry screen behind it.
            AddInventoryRoute.REVIEW -> if (isPasteBatch) guardedClose() else vm.addMoreFromReview()
            AddInventoryRoute.ENTRY -> guardedClose()
            AddInventoryRoute.PASTE -> guardedClose()
        }
    }

    if (showDiscardDialog) {
        AromexDialog(
            title = strings(Strings.inventory_discard_title),
            message = strings(Strings.inventory_discard_body),
            confirmLabel = strings(Strings.inventory_discard_confirm),
            onConfirm = { showDiscardDialog = false; onExit() },
            dismissLabel = strings(Strings.inventory_discard_cancel),
            onDismiss = { showDiscardDialog = false },
            destructive = true,
        )
    }

    when (state.route) {
        AddInventoryRoute.PASTE -> AddInventoryPasteScreen(vm, state, guardedClose)
        AddInventoryRoute.ENTRY -> AddInventoryEntryScreen(vm, state, existingProducts, guardedClose)
        AddInventoryRoute.REVIEW -> ReviewConfirmScreen(
            vm, state,
            isPasteBatch = isPasteBatch,
            onBack = { if (isPasteBatch) vm.pasteMore() else vm.addMoreFromReview() },
        )
    }
}

// ── Screen 0 (paste): SICKW bulk intake ──────────────────────────────────────

@Composable
private fun AddInventoryPasteScreen(
    vm: AddStockViewModel,
    state: AddStockUiState,
    onClose: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        BrandHeader(bottomRadius = 0.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InvHeaderCircleButton(
                    icon = Icons.Default.Close,
                    contentDescription = strings(Strings.inventory_close_cd),
                    onClick = onClose,
                )
                Spacer(Modifier.width(dims.space12))
                Column {
                    Text(
                        strings(Strings.inventory_paste_title),
                        style = typography.sectionTitle,
                        color = colors.onBrand,
                    )
                    Text(
                        strings(Strings.inventory_paste_subtitle),
                        style = typography.hint,
                        color = colors.onBrand.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    start = dims.screenPadding,
                    end = dims.screenPadding,
                    top = dims.space16,
                    bottom = imeBottom + dims.space16,
                ),
        ) {
            OutlinedTextField(
                value = state.pasteText,
                onValueChange = vm::onPasteTextChange,
                placeholder = {
                    Text(
                        strings(Strings.inventory_paste_hint),
                        style = typography.body,
                        color = colors.textTertiary,
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.brand,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.brand,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                ),
                shape = RoundedCornerShape(dims.radiusField),
                textStyle = typography.body.copy(color = colors.textPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            if (state.pasteText.isNotBlank()) {
                TextButton(onClick = vm::clearPasteText, modifier = Modifier.align(Alignment.End)) {
                    Text(strings(Strings.inventory_paste_clear), style = typography.button, color = colors.textSecondary)
                }
            } else {
                Spacer(Modifier.height(dims.space8))
                Text(
                    strings(Strings.inventory_paste_empty),
                    style = typography.hint,
                    color = colors.textTertiary,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(
                    start = dims.screenPadding,
                    end = dims.screenPadding,
                    top = dims.space16,
                    bottom = navBarPadding.calculateBottomPadding() + dims.space16,
                ),
        ) {
            PrimaryButton(
                label = strings(Strings.inventory_paste_parse),
                onClick = vm::parseAndAdd,
                enabled = state.pasteText.isNotBlank() && !state.parsing,
                loading = state.parsing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Screen 1: entry ─────────────────────────────────────────────────────────

@Composable
private fun AddInventoryEntryScreen(
    vm: AddStockViewModel,
    state: AddStockUiState,
    existingProducts: List<Product>,
    onClose: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    // Auto-fill selling price when the picked SKU matches an existing product
    LaunchedEffect(state.picked) {
        if (state.attributesLocked) return@LaunchedEffect
        if (!AttributeType.SKU_DEFINING.all { state.picked.containsKey(it) }) return@LaunchedEffect
        val key = runCatching { SkuKey.build(state.picked) }.getOrNull() ?: return@LaunchedEffect
        val match = existingProducts.firstOrNull { it.productId == key }
        if (match != null) vm.setPrice(match.defaultSellingPrice)
    }

    var scanning by rememberSaveable { mutableStateOf(false) }

    if (scanning) {
        ImeiScannerScreen(onResult = { result ->
            scanning = false
            if (result is ImeiScanner.Result.Scanned) {
                vm.onPendingImeiChange(result.imei.trim())
                vm.checkAndAddImei()
            }
        })
        return
    }

    val isReadyToReview = AttributeType.SKU_DEFINING.all { state.picked.containsKey(it) } &&
        state.defaultSellingPrice.isNotBlank() &&
        state.batchCost.isNotBlank() &&
        state.batchLocation != null &&
        state.stagedImeis.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // ── Gradient header ──────────────────────────────────────────────────
        BrandHeader(bottomRadius = 0.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InvHeaderCircleButton(
                    icon = Icons.Default.Close,
                    contentDescription = strings(Strings.inventory_close_cd),
                    onClick = onClose,
                )
                Text(
                    text = strings(Strings.inventory_add_title),
                    style = typography.sectionTitle,
                    color = colors.onBrand,
                    modifier = Modifier.weight(1f).padding(horizontal = dims.space12),
                )
                TextButton(
                    onClick = vm::proceedToReview,
                    enabled = isReadyToReview,
                ) {
                    Text(
                        text = strings(Strings.inventory_review_action),
                        style = typography.button,
                        color = if (isReadyToReview) colors.onBrand else colors.onBrand.copy(alpha = 0.4f),
                    )
                }
            }
        }

        // ── Scrollable form ──────────────────────────────────────────────────
        LazyColumn(
            contentPadding = PaddingValues(
                start = dims.screenPadding,
                end = dims.screenPadding,
                top = dims.space24,
                bottom = imeBottom + dims.space16,
            ),
            verticalArrangement = Arrangement.spacedBy(dims.fieldGap),
            modifier = Modifier.weight(1f),
        ) {
            // ── SKU ──────────────────────────────────────────────────────────
            item { FormSectionLabel(strings(Strings.inventory_sku_section)) }

            if (state.attributesLocked) {
                item {
                    Text(
                        state.picked.skuLabel(),
                        style = typography.bodyStrong,
                        color = colors.textPrimary,
                    )
                }
            } else {
                item {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_brand_label),
                        items = vm.options(AttributeType.BRAND),
                        selectedItem = state.picked[AttributeType.BRAND],
                        onItemSelected = { vm.pick(AttributeType.BRAND, it) },
                        onClear = { vm.clearPick(AttributeType.BRAND) },
                        onAddNew = { vm.addNewAttribute(AttributeType.BRAND, it) },
                        placeholder = strings(Strings.inventory_brand_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    val brandPicked = state.picked.containsKey(AttributeType.BRAND)
                    FilterableDropdownField(
                        label = strings(Strings.inventory_model_label),
                        items = vm.options(AttributeType.MODEL),
                        selectedItem = state.picked[AttributeType.MODEL],
                        onItemSelected = { vm.pick(AttributeType.MODEL, it) },
                        onClear = { vm.clearPick(AttributeType.MODEL) },
                        onAddNew = if (brandPicked) { { vm.addNewAttribute(AttributeType.MODEL, it) } } else null,
                        enabled = brandPicked,
                        placeholder = if (!brandPicked) strings(Strings.inventory_model_hint_brand)
                                      else strings(Strings.inventory_model_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_capacity_label),
                        items = vm.options(AttributeType.CAPACITY),
                        selectedItem = state.picked[AttributeType.CAPACITY],
                        onItemSelected = { vm.pick(AttributeType.CAPACITY, it) },
                        onClear = { vm.clearPick(AttributeType.CAPACITY) },
                        onAddNew = { vm.addNewAttribute(AttributeType.CAPACITY, it) },
                        placeholder = strings(Strings.inventory_capacity_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_color_label),
                        items = vm.options(AttributeType.COLOR),
                        selectedItem = state.picked[AttributeType.COLOR],
                        onItemSelected = { vm.pick(AttributeType.COLOR, it) },
                        onClear = { vm.clearPick(AttributeType.COLOR) },
                        onAddNew = { vm.addNewAttribute(AttributeType.COLOR, it) },
                        placeholder = strings(Strings.inventory_color_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_carrier_label),
                        items = vm.options(AttributeType.CARRIER),
                        selectedItem = state.picked[AttributeType.CARRIER],
                        onItemSelected = { vm.pick(AttributeType.CARRIER, it) },
                        onClear = { vm.clearPick(AttributeType.CARRIER) },
                        onAddNew = { vm.addNewAttribute(AttributeType.CARRIER, it) },
                        placeholder = strings(Strings.inventory_carrier_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    LabeledTextField(
                        label = strings(Strings.inventory_selling_price_label),
                        value = state.defaultSellingPrice,
                        onValueChange = vm::setPrice,
                        placeholder = strings(Strings.inventory_selling_price_placeholder),
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── Batch details ────────────────────────────────────────────────
            item { FormSectionSpacer() }
            item { FormSectionLabel(strings(Strings.inventory_batch_section)) }
            item {
                LabeledTextField(
                    label = strings(Strings.inventory_cost_label),
                    value = state.batchCost,
                    onValueChange = vm::setBatchCost,
                    placeholder = strings(Strings.inventory_cost_placeholder),
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column {
                    Text(
                        strings(Strings.inventory_condition_label),
                        style = typography.fieldLabel,
                        color = colors.textTertiary,
                    )
                    Spacer(Modifier.height(dims.space8))
                    ConditionToggle(
                        selected = state.batchCondition,
                        onSelect = vm::setBatchCondition,
                        newLabel = strings(Strings.inventory_condition_new),
                        usedLabel = strings(Strings.inventory_condition_used),
                    )
                }
            }
            item {
                FilterableDropdownField(
                    label = strings(Strings.inventory_location_label),
                    items = vm.options(AttributeType.LOCATION),
                    selectedItem = state.batchLocation,
                    onItemSelected = { vm.setBatchLocation(it) },
                    onClear = { vm.clearBatchLocation() },
                    onAddNew = { vm.addNewAttribute(AttributeType.LOCATION, it) },
                    placeholder = strings(Strings.inventory_location_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── IMEI section ─────────────────────────────────────────────────
            item { FormSectionSpacer() }
            item { FormSectionLabel(strings(Strings.inventory_imei_section)) }
            item {
                ImeiEntryRow(
                    imei = state.pendingImei,
                    checkState = state.imeiCheckState,
                    onImeiChange = vm::onPendingImeiChange,
                    onAdd = vm::checkAndAddImei,
                    onScanClick = { scanning = true },
                )
            }

            if (state.stagedImeis.isNotEmpty()) {
                item {
                    val count = state.stagedImeis.size
                    Text(
                        if (count == 1) strings(Strings.inventory_imei_count_one)
                        else strings(Strings.inventory_imei_count, count),
                        style = typography.bodyStrong.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        modifier = Modifier.padding(top = dims.space4),
                    )
                }
                items(state.stagedImeis, key = { it }) { imei ->
                    StagedImeiRow(imei = imei, onRemove = { vm.removeImei(imei) })
                }
            }
        }

        // ── Fixed bottom: Review button ──────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(
                    start = dims.screenPadding,
                    end = dims.screenPadding,
                    top = dims.space16,
                    bottom = navBarPadding.calculateBottomPadding() + dims.space16,
                ),
        ) {
            PrimaryButton(
                label = strings(Strings.inventory_review_action),
                onClick = vm::proceedToReview,
                enabled = isReadyToReview,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Screen 2: review & confirm ───────────────────────────────────────────────

@Composable
private fun ReviewConfirmScreen(
    vm: AddStockViewModel,
    state: AddStockUiState,
    isPasteBatch: Boolean,
    onBack: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    var editingIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    val rawError = state.error
    val errorMessage = when {
        rawError == null -> null
        rawError.startsWith("cap:") -> {
            val parts = rawError.removePrefix("cap:").split(":")
            strings(Strings.inventory_batch_cap_body, parts.getOrNull(0) ?: "", parts.getOrNull(1) ?: "")
        }
        rawError.startsWith("duplicate:") -> strings(Strings.inventory_save_error_duplicate)
        else -> strings(Strings.inventory_save_error_network)
    }

    editingIndex?.let { idx ->
        val unit = state.reviewUnits.getOrNull(idx)
        if (unit != null) {
            EditUnitDialog(
                unit = unit,
                vm = vm,
                state = state,
                otherImeis = state.reviewUnits.mapIndexedNotNull { i, u -> if (i != idx) u.imei else null }.toSet(),
                onSave = { edited ->
                    vm.editReviewUnit(idx, edited)
                    editingIndex = null
                },
                onDismiss = { editingIndex = null },
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // ── Gradient header ──────────────────────────────────────────────────
        BrandHeader(bottomRadius = 0.dp) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InvHeaderCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = strings(Strings.inventory_back_cd),
                    onClick = onBack,
                )
                Spacer(Modifier.width(dims.space12))
                Column {
                    val count = state.reviewUnits.size
                    Text(
                        if (count == 1) strings(Strings.inventory_review_unit_count_one)
                        else strings(Strings.inventory_review_unit_count, count),
                        style = typography.hint,
                        color = colors.onBrand.copy(alpha = 0.7f),
                    )
                    Text(
                        strings(Strings.inventory_review_title),
                        style = typography.sectionTitle,
                        color = colors.onBrand,
                    )
                }
            }
        }

        // ── Unit list — grouped by SKU ───────────────────────────────────────
        val skuGroups = remember(state.reviewUnits) {
            state.reviewUnits.withIndex()
                .groupBy { (_, unit) ->
                    unit.attributes.entries
                        .sortedBy { it.key.wire }
                        .joinToString(",") { "${it.key.wire}:${it.value.attributeId}" }
                }
                .values.toList()
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = imeBottom + dims.space16),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            modifier = Modifier.weight(1f),
        ) {
            // Parse summary banner (paste only)
            if (isPasteBatch && state.lastParsedCount != null) {
                item(key = "parse-summary") { ParseSummaryBanner(state) }
            }

            // Apply-to-all bar (paste only — a paste is usually one lot)
            if (isPasteBatch) {
                item(key = "apply-all") { ApplyToAllBar(vm, state) }
            }

            item(key = "list-top-gap") { Spacer(Modifier.height(dims.space8)) }

            skuGroups.forEachIndexed { groupIdx, indexedUnits ->
                val repAttrs = indexedUnits.first().value.attributes
                val groupLabel = repAttrs.skuLabel()
                val unitCount = indexedUnits.size
                val hasNewVocab = repAttrs.values.any { it.attributeId in state.newlyCreatedIds }
                item(key = "sku-header-$groupIdx") {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .background(colors.brand.copy(alpha = 0.07f))
                                .padding(horizontal = dims.screenPadding, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(dims.space8),
                        ) {
                            Text(
                                groupLabel,
                                style = typography.hint.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.brand,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (hasNewVocab) NewTag()
                            Text(
                                "· $unitCount ${if (unitCount == 1) "unit" else "units"}",
                                style = typography.hint,
                                color = colors.textTertiary,
                            )
                        }
                        HorizontalDivider(color = colors.border)
                    }
                }
                indexedUnits.forEach { (originalIndex, unit) ->
                    item(key = "unit-$originalIndex") {
                        Column(modifier = Modifier.padding(horizontal = dims.screenPadding)) {
                            Spacer(Modifier.height(dims.space8))
                            if (isPasteBatch) {
                                ReviewUnitEditableCard(
                                    vm = vm,
                                    state = state,
                                    index = originalIndex,
                                    unit = unit,
                                    onDelete = { vm.removeReviewUnit(originalIndex) },
                                    onEditSku = { editingIndex = originalIndex },
                                )
                            } else {
                                ReviewUnitCard(
                                    unit = unit,
                                    onEdit = { editingIndex = originalIndex },
                                    onDelete = { vm.removeReviewUnit(originalIndex) },
                                )
                            }
                        }
                    }
                }
                if (groupIdx < skuGroups.lastIndex) {
                    item(key = "gap-$groupIdx") { Spacer(Modifier.height(dims.space8)) }
                }
            }

            // Couldn't-read list (paste only) — shown, never dropped
            if (state.unreadable.isNotEmpty()) {
                item(key = "unreadable-header") {
                    Spacer(Modifier.height(dims.space16))
                    FormSectionLabelError(
                        strings(Strings.inventory_unreadable_count, state.unreadable.size),
                    )
                }
                itemsIndexed(state.unreadable, key = { i, _ -> "unreadable-$i" }) { i, block ->
                    Column(modifier = Modifier.padding(horizontal = dims.screenPadding)) {
                        Spacer(Modifier.height(dims.space8))
                        UnreadableCard(block = block, onDismiss = { vm.dismissUnreadable(i) })
                    }
                }
            }
        }

        // ── Fixed bottom: Add/Paste more + error + Confirm ────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.background)
                .padding(
                    start = dims.screenPadding,
                    end = dims.screenPadding,
                    top = dims.space8,
                    bottom = navBarPadding.calculateBottomPadding() + dims.space16,
                ),
            verticalArrangement = Arrangement.spacedBy(dims.space8),
        ) {
            TextButton(
                onClick = { if (isPasteBatch) vm.pasteMore() else vm.addMoreFromReview() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isPasteBatch) strings(Strings.inventory_paste_more)
                    else strings(Strings.inventory_review_add_more),
                    style = typography.button,
                    color = colors.brand,
                )
            }
            if (errorMessage != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dims.space8),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(errorMessage, style = typography.hint, color = colors.error)
                }
            }
            PrimaryButton(
                label = strings(Strings.inventory_confirm_btn),
                onClick = vm::openPurchaseDialog,
                enabled = state.canConfirm && !state.saving,
                loading = state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (state.showPurchaseDialog) {
        PurchaseDialog(vm = vm, state = state)
    }
}

// ── Purchase dialog (#58) ────────────────────────────────────────────────────

/**
 * Captures the bookkeeping side of an Add-Inventory batch: who it was bought from and
 * how much was paid on the spot. Every field is optional and pre-filled with safe
 * defaults (Unspecified Supplier, nothing paid), so simply confirming — or dismissing
 * via Escape/click-away — is the valid "skip" path. Dismissal never cancels the save.
 */
@Composable
private fun PurchaseDialog(vm: AddStockViewModel, state: AddStockUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val symbol = MoneyFormat.symbolOf(state.currency)

    // Escape / click-away behaves identically to confirming at all-defaults.
    Dialog(onDismissRequest = { vm.dismissPurchaseDialog() }) {
        Surface(
            shape = RoundedCornerShape(dims.radiusCard),
            color = colors.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(dims.space24)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(dims.fieldGap),
            ) {
                Text(
                    strings(Strings.inventory_purchase_title),
                    style = typography.sectionTitle,
                    color = colors.textPrimary,
                )
                // Supplier block — labelled so it's clearly distinct from the Commission block.
                Text(
                    strings(Strings.commission_section_supplier),
                    style = typography.fieldLabel,
                    color = colors.textTertiary,
                )
                Text(
                    strings(
                        Strings.inventory_purchase_total,
                        MoneyFormat.format(state.batchTotalCost, state.currency),
                    ),
                    style = typography.hint,
                    color = colors.textTertiary,
                )

                FilterableDropdownField(
                    label = strings(Strings.inventory_purchase_bought_from),
                    items = vm.purchasePartyOptions(),
                    selectedItem = state.purchaseParty,
                    onItemSelected = { vm.setPurchaseParty(it) },
                    onAddNew = if (vm.canAddSupplierInline()) { { vm.addNewSupplier(it) } } else null,
                    placeholder = strings(Strings.inventory_purchase_bought_from_hint),
                    modifier = Modifier.fillMaxWidth(),
                )

                LabeledTextField(
                    label = strings(Strings.inventory_purchase_cash, symbol),
                    value = state.purchaseCash,
                    onValueChange = vm::setPurchaseCash,
                    placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledTextField(
                    label = strings(Strings.inventory_purchase_bank, symbol),
                    value = state.purchaseBank,
                    onValueChange = vm::setPurchaseBank,
                    placeholder = "0",
                    keyboardType = KeyboardType.Decimal,
                    errorMessage = if (state.purchasePaidExceedsTotal) {
                        strings(
                            Strings.inventory_purchase_exceeds,
                            MoneyFormat.format(state.batchTotalCost, state.currency),
                        )
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                // Commission on intake (#97) — only shown when a rule matched this batch.
                if (state.commissionLines.isNotEmpty()) {
                    CommissionSection(vm = vm, state = state)
                }

                PrimaryButton(
                    label = strings(Strings.inventory_purchase_confirm),
                    onClick = vm::confirmPurchaseAndSave,
                    enabled = !state.purchasePaidExceedsTotal && !state.commissionGiveExceeds && !state.saving,
                    loading = state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Commission on intake (#97) — bare but complete. One block per payee a rule proposed for this
 * batch, each decided separately: accrue ("add to what I owe") or pay now, its amount editable
 * (marked Edited when changed), or skipped. Every block shows how the figure was reached.
 */
@Composable
private fun CommissionSection(vm: AddStockViewModel, state: AddStockUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val symbol = MoneyFormat.symbolOf(state.currency)

    HorizontalDivider(color = colors.border)
    Text(strings(Strings.commission_section_title), style = typography.fieldLabel, color = colors.textTertiary)
    state.commissionLines.forEach { line ->
        val decision = state.commissionDecisions[line.ruleId] ?: return@forEach
        val reach = when (line.rateKind) {
            RateKind.PER_UNIT -> strings(
                Strings.commission_reach_per_unit,
                vm.payeeName(line.payeeEntityId),
                line.unitCount.toString(),
                vm.locationName(line.locationAttributeId),
                MoneyFormat.format(line.rate, state.currency),
            )
            RateKind.PERCENT_OF_COST -> strings(
                Strings.commission_reach_percent,
                vm.payeeName(line.payeeEntityId),
                percentLabel(line.rate),
                MoneyFormat.format(line.basisAmount, state.currency),
                vm.locationName(line.locationAttributeId),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reach, style = typography.hint, color = colors.textSecondary, modifier = Modifier.weight(1f))
                Text(MoneyFormat.format(line.amount, state.currency), style = typography.body, color = colors.textPrimary)
            }
            if (!decision.included) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(strings(Strings.commission_skipped), style = typography.hint, color = colors.textTertiary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.setCommissionIncluded(line.ruleId, true) }) {
                        Text(strings(Strings.commission_undo_skip))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToggleChip(strings(Strings.commission_accrue), !decision.giveNow) { vm.setCommissionGiveNow(line.ruleId, false) }
                    ToggleChip(strings(Strings.commission_pay_now), decision.giveNow) { vm.setCommissionGiveNow(line.ruleId, true) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledTextField(
                        label = strings(Strings.commission_amount_label, symbol) + if (decision.overridden) "  •  " + strings(Strings.commission_overridden) else "",
                        value = decision.amount,
                        onValueChange = { vm.setCommissionAmount(line.ruleId, it) },
                        placeholder = "0",
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.setCommissionIncluded(line.ruleId, false) }) {
                        Text(strings(Strings.commission_skip), color = colors.error)
                    }
                }
                // Give-now split: cash + bank, the amount to give above (like the supplier UI),
                // and the running sum below.
                if (decision.giveNow) {
                    Text(
                        strings(Strings.commission_give_owed, MoneyFormat.format(decision.amount.ifBlank { "0" }, state.currency)),
                        style = typography.hint,
                        color = colors.textTertiary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledTextField(
                            label = strings(Strings.commission_cash_field, symbol),
                            value = decision.cash,
                            onValueChange = { vm.setCommissionCash(line.ruleId, it) },
                            placeholder = "0",
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                        LabeledTextField(
                            label = strings(Strings.commission_bank_field, symbol),
                            value = decision.bank,
                            onValueChange = { vm.setCommissionBank(line.ruleId, it) },
                            placeholder = "0",
                            keyboardType = KeyboardType.Decimal,
                            errorMessage = if (decision.giveExceedsAmount) {
                                strings(Strings.commission_give_exceeds, MoneyFormat.format(decision.amount.ifBlank { "0" }, state.currency))
                            } else {
                                null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!decision.giveExceedsAmount) {
                        Text(
                            strings(Strings.commission_giving_now, MoneyFormat.format(decision.givenNow, state.currency)) +
                                "  ·  " +
                                strings(Strings.commission_left_on_balance, MoneyFormat.format(decision.leftOnBalance, state.currency)),
                            style = typography.hint,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/** A minimal two-way pill toggle (accrue/pay-now, Cash/Bank) — no dropdown. */
@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(dims.radiusField))
            .background(if (selected) colors.brand.copy(alpha = 0.14f) else colors.surface)
            .border(1.dp, if (selected) colors.brand else colors.border, RoundedCornerShape(dims.radiusField))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            style = AromexTheme.typography.hint,
            color = if (selected) colors.brand else colors.textSecondary,
        )
    }
}

/** Fraction string → percent label, e.g. "0.02" → "2%". Pure string maths. */
internal fun percentLabel(fraction: String): String {
    val pct = Money.multiplyRate(fraction, "100")
    val trimmed = if (pct.contains('.')) pct.trimEnd('0').trimEnd('.') else pct
    return "$trimmed%"
}

// ── Review-screen sub-composables (#53) ──────────────────────────────────────

@Composable
private fun ParseSummaryBanner(state: AddStockUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val parsed = state.lastParsedCount ?: 0
    val unread = state.lastUnreadableCount
    val text = when {
        parsed == 0 -> strings(Strings.inventory_parse_none)
        unread == 0 -> strings(Strings.inventory_parse_summary_one, parsed)
        else -> strings(Strings.inventory_parse_summary, parsed, unread)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.brand.copy(alpha = 0.10f))
            .padding(horizontal = dims.screenPadding, vertical = dims.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dims.space8),
    ) {
        Icon(Icons.Default.Check, null, tint = colors.brand, modifier = Modifier.size(18.dp))
        Text(text, style = typography.bodyStrong, color = colors.textPrimary)
    }
}

@Composable
private fun ApplyToAllBar(vm: AddStockViewModel, state: AddStockUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    var cost by rememberSaveable { mutableStateOf("") }
    var sellingPrice by rememberSaveable { mutableStateOf("") }
    var condition by remember { mutableStateOf(Condition.NEW) }
    var location by remember { mutableStateOf<AttributeRef?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = dims.screenPadding, vertical = dims.space8),
        shape = RoundedCornerShape(dims.radiusCard),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceAlt),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(dims.space16),
            verticalArrangement = Arrangement.spacedBy(dims.space8),
        ) {
            Text(strings(Strings.inventory_apply_all_title), style = typography.bodyStrong, color = colors.textPrimary)
            Text(strings(Strings.inventory_apply_all_hint), style = typography.hint, color = colors.textSecondary)
            LabeledTextField(
                label = strings(Strings.inventory_cost_label),
                value = cost,
                onValueChange = { cost = it },
                placeholder = strings(Strings.inventory_cost_placeholder),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth(),
            )
            LabeledTextField(
                label = strings(Strings.inventory_selling_price_label),
                value = sellingPrice,
                onValueChange = { sellingPrice = it },
                placeholder = strings(Strings.inventory_selling_price_placeholder),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth(),
            )
            ConditionToggle(
                selected = condition,
                onSelect = { condition = it },
                newLabel = strings(Strings.inventory_condition_new),
                usedLabel = strings(Strings.inventory_condition_used),
            )
            FilterableDropdownField(
                label = strings(Strings.inventory_location_label),
                items = vm.options(AttributeType.LOCATION),
                selectedItem = location,
                onItemSelected = { location = AttributeRef(it.attributeId, it.name) },
                onClear = { location = null },
                onAddNew = { vm.addNewAttribute(AttributeType.LOCATION, it) },
                placeholder = strings(Strings.inventory_location_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(
                label = strings(Strings.inventory_apply_all_action),
                onClick = {
                    if (cost.isNotBlank()) vm.applyCostToAll(cost.trim())
                    if (sellingPrice.isNotBlank()) vm.applyPriceToAll(sellingPrice.trim())
                    vm.applyConditionToAll(condition)
                    location?.let { vm.applyLocationToAll(it) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun NewTag() {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.warning.copy(alpha = 0.18f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(strings(Strings.inventory_status_new_tag), style = typography.hint, color = colors.warning)
    }
}

@Composable
private fun FormSectionLabelError(text: String) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    Row(
        modifier = Modifier.padding(horizontal = dims.screenPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dims.space8),
    ) {
        Icon(Icons.Default.Error, null, tint = colors.error, modifier = Modifier.size(16.dp))
        Text(
            text.uppercase(),
            style = typography.hint.copy(fontWeight = FontWeight.Bold),
            color = colors.error,
        )
        HorizontalDivider(color = colors.border, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun UnreadableCard(block: com.humblesolutions.aromex.model.UnreadableBlock, onDismiss: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val reason = when (block.reason) {
        com.humblesolutions.aromex.model.UnreadableBlock.Reason.NO_MODEL -> strings(Strings.inventory_unreadable_no_model)
        com.humblesolutions.aromex.model.UnreadableBlock.Reason.NOT_IPHONE -> strings(Strings.inventory_unreadable_not_iphone)
        com.humblesolutions.aromex.model.UnreadableBlock.Reason.NO_IMEI -> strings(Strings.inventory_unreadable_no_imei)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dims.radiusCard),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.error.copy(alpha = 0.4f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(dims.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reason, style = typography.bodyStrong, color = colors.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(strings(Strings.inventory_unreadable_dismiss), style = typography.button, color = colors.textSecondary)
                }
            }
            Text(
                block.rawText,
                style = typography.hint,
                color = colors.textSecondary,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReviewUnitEditableCard(
    vm: AddStockViewModel,
    state: AddStockUiState,
    index: Int,
    unit: ReviewUnit,
    onDelete: () -> Unit,
    onEditSku: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    val status = state.statusOf(unit)
    val statusColor = when (status) {
        ReviewRowStatus.CONFIDENT -> colors.success
        ReviewRowStatus.MUST_FILL -> colors.warning
        ReviewRowStatus.PROBLEM -> colors.error
    }
    val problem = when {
        unit.imei in state.inStockImeis -> strings(Strings.inventory_status_in_stock)
        unit.imei in state.duplicateImeis -> strings(Strings.inventory_status_dup_batch)
        else -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dims.radiusCard),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.5.dp, statusColor.copy(alpha = 0.55f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(dims.space12), verticalArrangement = Arrangement.spacedBy(dims.space8)) {
            // Header row: status dot + IMEI + edit SKU + delete
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Text(
                    unit.imei,
                    style = typography.bodyStrong,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onEditSku, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, strings(Strings.inventory_review_edit_cd), tint = colors.brand, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, strings(Strings.inventory_review_delete_cd), tint = colors.error, modifier = Modifier.size(16.dp))
                }
            }
            if (problem != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space4)) {
                    Icon(Icons.Default.Error, null, tint = colors.error, modifier = Modifier.size(14.dp))
                    Text(problem, style = typography.hint, color = colors.error)
                }
            }
            // Inline editable shop fields
            LabeledTextField(
                label = strings(Strings.inventory_cost_label),
                value = unit.cost,
                onValueChange = { vm.setRowCost(index, it) },
                placeholder = strings(Strings.inventory_cost_placeholder),
                keyboardType = KeyboardType.Decimal,
                modifier = Modifier.fillMaxWidth(),
            )
            ConditionToggle(
                selected = unit.condition,
                onSelect = { vm.setRowCondition(index, it) },
                newLabel = strings(Strings.inventory_condition_new),
                usedLabel = strings(Strings.inventory_condition_used),
            )
            FilterableDropdownField(
                label = strings(Strings.inventory_location_label),
                items = vm.options(AttributeType.LOCATION),
                selectedItem = unit.location.takeIf { it.attributeId.isNotBlank() },
                onItemSelected = { vm.setRowLocation(index, AttributeRef(it.attributeId, it.name)) },
                onClear = { vm.setRowLocation(index, AttributeRef()) },
                onAddNew = { vm.addNewAttribute(AttributeType.LOCATION, it) },
                placeholder = strings(Strings.inventory_location_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────────

@Composable
private fun ImeiEntryRow(
    imei: String,
    checkState: ImeiCheckState,
    onImeiChange: (String) -> Unit,
    onAdd: () -> Unit,
    onScanClick: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val isChecking = checkState == ImeiCheckState.CHECKING

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.space8),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedTextField(
                value = imei,
                onValueChange = onImeiChange,
                placeholder = {
                    Text(
                        strings(Strings.inventory_imei_placeholder),
                        style = typography.body,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onAdd() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.brand,
                    unfocusedBorderColor = colors.border,
                    cursorColor = colors.brand,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                ),
                shape = RoundedCornerShape(dims.radiusField),
                textStyle = typography.body.copy(color = colors.textPrimary),
                isError = checkState == ImeiCheckState.INVALID ||
                    checkState == ImeiCheckState.ALREADY_IN_BATCH ||
                    checkState == ImeiCheckState.ALREADY_IN_STOCK,
                modifier = Modifier.weight(1f),
            )
            // Camera scanner icon
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(dims.radiusField))
                    .background(colors.surfaceAlt)
                    .clickable(onClick = onScanClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.QrCodeScanner,
                    contentDescription = "Scan IMEI",
                    tint = colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            // Green ✓ add button
            val canAdd = imei.isNotBlank() && !isChecking
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(if (canAdd) colors.success else colors.surfaceAlt)
                    .clickable(enabled = canAdd, onClick = onAdd),
                contentAlignment = Alignment.Center,
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = strings(Strings.inventory_imei_add_cd),
                        tint = if (canAdd) Color.White else colors.textTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        // Inline feedback below the field row
        val feedbackText = when (checkState) {
            ImeiCheckState.INVALID -> strings(Strings.inventory_imei_error_invalid)
            ImeiCheckState.ALREADY_IN_BATCH -> strings(Strings.inventory_imei_error_in_batch)
            ImeiCheckState.ALREADY_IN_STOCK -> strings(Strings.inventory_imei_error_in_stock)
            ImeiCheckState.CHECKING -> strings(Strings.inventory_imei_checking)
            else -> null
        }
        if (feedbackText != null) {
            Spacer(Modifier.height(dims.space4))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dims.space4),
            ) {
                if (checkState != ImeiCheckState.CHECKING) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    feedbackText,
                    style = typography.hint,
                    color = if (checkState == ImeiCheckState.CHECKING) colors.textSecondary else colors.error,
                )
            }
        }
    }
}

@Composable
private fun StagedImeiRow(imei: String, onRemove: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = dims.space8),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                imei,
                style = typography.body,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = strings(Strings.inventory_remove_unit_cd),
                    tint = colors.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(color = colors.border)
    }
}

@Composable
private fun ReviewUnitCard(unit: ReviewUnit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(dims.radiusCard),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.border),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.space16, vertical = dims.space12),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    unit.imei,
                    style = typography.bodyStrong,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(dims.space4))
                val condLabel = when (unit.condition) {
                    Condition.NEW -> strings(Strings.inventory_condition_new)
                    Condition.USED -> strings(Strings.inventory_condition_used)
                }
                Text(
                    "${unit.cost}  ·  $condLabel  ·  ${unit.location.name}",
                    style = typography.hint,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = strings(Strings.inventory_review_edit_cd),
                        tint = colors.brand,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = strings(Strings.inventory_review_delete_cd),
                        tint = colors.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditUnitDialog(
    unit: ReviewUnit,
    vm: AddStockViewModel,
    state: AddStockUiState,
    otherImeis: Set<String>,
    onSave: (ReviewUnit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val dialogScope = rememberCoroutineScope()

    var imei by remember { mutableStateOf(unit.imei) }
    var imeiError by remember { mutableStateOf<String?>(null) }
    var checkState by remember { mutableStateOf(ImeiCheckState.IDLE) }
    var cost by remember { mutableStateOf(unit.cost) }
    var condition by remember { mutableStateOf(unit.condition) }
    var location by remember { mutableStateOf<AttributeRef?>(unit.location) }

    // Pre-load this unit's own SKU into the shared picker state
    LaunchedEffect(unit.imei) {
        vm.setPickedFromUnit(unit.attributes, unit.sellingPrice)
    }

    // Pre-read @Composable strings before the local save function
    val strInvalid = strings(Strings.inventory_imei_error_invalid)
    val strInBatch = strings(Strings.inventory_imei_error_in_batch)
    val strInStock = strings(Strings.inventory_imei_error_in_stock)

    // Auto-save when async IMEI check resolves AVAILABLE
    LaunchedEffect(checkState) {
        if (checkState == ImeiCheckState.AVAILABLE) {
            val loc = location ?: return@LaunchedEffect
            onSave(unit.copy(
                imei = imei.trim(),
                cost = cost.trim(),
                condition = condition,
                location = loc,
                attributes = state.picked,
                sellingPrice = state.defaultSellingPrice,
            ))
        }
    }

    fun attemptSave() {
        val trimmed = imei.trim()
        val loc = location ?: return
        imeiError = null
        if (trimmed == unit.imei) {
            onSave(unit.copy(
                cost = cost.trim(),
                condition = condition,
                location = loc,
                attributes = state.picked,
                sellingPrice = state.defaultSellingPrice,
            ))
        } else {
            checkState = ImeiCheckState.IDLE
            dialogScope.launch {
                checkState = ImeiCheckState.CHECKING
                val result = vm.checkImeiAvailability(trimmed, otherImeis)
                if (result != ImeiCheckState.AVAILABLE) {
                    checkState = result
                    imeiError = when (result) {
                        ImeiCheckState.INVALID -> strInvalid
                        ImeiCheckState.ALREADY_IN_BATCH -> strInBatch
                        ImeiCheckState.ALREADY_IN_STOCK -> strInStock
                        else -> null
                    }
                } else {
                    checkState = result
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(dims.radiusCard),
            color = colors.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .padding(dims.space24)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(dims.fieldGap),
            ) {
                Text(
                    strings(Strings.inventory_edit_unit_title),
                    style = typography.sectionTitle,
                    color = colors.textPrimary,
                )

                // ── PHONE (SKU + price) ──────────────────────────────────────
                FormSectionLabel(strings(Strings.inventory_sku_section))

                FilterableDropdownField(
                    label = strings(Strings.inventory_brand_label),
                    items = vm.options(AttributeType.BRAND),
                    selectedItem = state.picked[AttributeType.BRAND],
                    onItemSelected = { vm.pick(AttributeType.BRAND, it) },
                    onClear = { vm.clearPick(AttributeType.BRAND) },
                    onAddNew = { vm.addNewAttribute(AttributeType.BRAND, it) },
                    placeholder = strings(Strings.inventory_brand_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                val brandPicked = state.picked.containsKey(AttributeType.BRAND)
                FilterableDropdownField(
                    label = strings(Strings.inventory_model_label),
                    items = vm.options(AttributeType.MODEL),
                    selectedItem = state.picked[AttributeType.MODEL],
                    onItemSelected = { vm.pick(AttributeType.MODEL, it) },
                    onClear = { vm.clearPick(AttributeType.MODEL) },
                    onAddNew = if (brandPicked) { { vm.addNewAttribute(AttributeType.MODEL, it) } } else null,
                    enabled = brandPicked,
                    placeholder = if (!brandPicked) strings(Strings.inventory_model_hint_brand)
                                  else strings(Strings.inventory_model_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterableDropdownField(
                    label = strings(Strings.inventory_capacity_label),
                    items = vm.options(AttributeType.CAPACITY),
                    selectedItem = state.picked[AttributeType.CAPACITY],
                    onItemSelected = { vm.pick(AttributeType.CAPACITY, it) },
                    onClear = { vm.clearPick(AttributeType.CAPACITY) },
                    onAddNew = { vm.addNewAttribute(AttributeType.CAPACITY, it) },
                    placeholder = strings(Strings.inventory_capacity_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterableDropdownField(
                    label = strings(Strings.inventory_color_label),
                    items = vm.options(AttributeType.COLOR),
                    selectedItem = state.picked[AttributeType.COLOR],
                    onItemSelected = { vm.pick(AttributeType.COLOR, it) },
                    onClear = { vm.clearPick(AttributeType.COLOR) },
                    onAddNew = { vm.addNewAttribute(AttributeType.COLOR, it) },
                    placeholder = strings(Strings.inventory_color_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterableDropdownField(
                    label = strings(Strings.inventory_carrier_label),
                    items = vm.options(AttributeType.CARRIER),
                    selectedItem = state.picked[AttributeType.CARRIER],
                    onItemSelected = { vm.pick(AttributeType.CARRIER, it) },
                    onClear = { vm.clearPick(AttributeType.CARRIER) },
                    onAddNew = { vm.addNewAttribute(AttributeType.CARRIER, it) },
                    placeholder = strings(Strings.inventory_carrier_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                LabeledTextField(
                    label = strings(Strings.inventory_selling_price_label),
                    value = state.defaultSellingPrice,
                    onValueChange = vm::setPrice,
                    placeholder = strings(Strings.inventory_selling_price_placeholder),
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── IMEI ─────────────────────────────────────────────────────
                FormSectionLabel(strings(Strings.inventory_imei_section))

                val isChecking = checkState == ImeiCheckState.CHECKING
                OutlinedTextField(
                    value = imei,
                    onValueChange = { imei = it; imeiError = null; checkState = ImeiCheckState.IDLE },
                    label = null,
                    placeholder = {
                        Text(strings(Strings.inventory_imei_placeholder), style = typography.body, color = colors.textTertiary)
                    },
                    singleLine = true,
                    isError = imeiError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        cursorColor = colors.brand,
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                    ),
                    shape = RoundedCornerShape(dims.radiusField),
                    textStyle = typography.body.copy(color = colors.textPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (imeiError != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dims.space4),
                    ) {
                        Icon(Icons.Default.Error, null, tint = colors.error, modifier = Modifier.size(14.dp))
                        Text(imeiError!!, style = typography.hint, color = colors.error)
                    }
                }

                // ── UNIT DETAILS ──────────────────────────────────────────────
                FormSectionLabel(strings(Strings.inventory_batch_section))

                LabeledTextField(
                    label = strings(Strings.inventory_cost_label),
                    value = cost,
                    onValueChange = { cost = it },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column {
                    Text(strings(Strings.inventory_condition_label), style = typography.fieldLabel, color = colors.textTertiary)
                    Spacer(Modifier.height(dims.space8))
                    ConditionToggle(
                        selected = condition,
                        onSelect = { condition = it },
                        newLabel = strings(Strings.inventory_condition_new),
                        usedLabel = strings(Strings.inventory_condition_used),
                    )
                }
                FilterableDropdownField(
                    label = strings(Strings.inventory_location_label),
                    items = vm.options(AttributeType.LOCATION),
                    selectedItem = location,
                    onItemSelected = { location = AttributeRef(it.attributeId, it.name) },
                    onClear = { location = null },
                    onAddNew = { vm.addNewAttribute(AttributeType.LOCATION, it) },
                    placeholder = strings(Strings.inventory_location_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )

                // ── Actions ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(strings(Strings.inventory_dialog_cancel), style = typography.button, color = colors.textSecondary)
                    }
                    Spacer(Modifier.width(dims.space8))
                    TextButton(
                        onClick = ::attemptSave,
                        enabled = !isChecking && location != null,
                    ) {
                        if (isChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.brand)
                        } else {
                            Text(strings(Strings.inventory_dialog_save), style = typography.button, color = colors.brand)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConditionToggle(
    selected: Condition,
    onSelect: (Condition) -> Unit,
    newLabel: String,
    usedLabel: String,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    Row(horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
        listOf(Condition.NEW to newLabel, Condition.USED to usedLabel).forEach { (cond, label) ->
            val isSelected = selected == cond
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(dims.fieldHeight)
                    .clip(RoundedCornerShape(dims.radiusButton))
                    .background(if (isSelected) colors.brand else colors.surfaceAlt)
                    .clickable { onSelect(cond) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = typography.button,
                    color = if (isSelected) colors.onBrand else colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun FormSectionLabel(text: String) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dims.space12),
    ) {
        Text(
            text.uppercase(),
            style = typography.hint.copy(fontWeight = FontWeight.Bold),
            color = colors.brand,
        )
        HorizontalDivider(color = colors.border, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FormSectionSpacer() {
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun InvHeaderCircleButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.onBrand.copy(alpha = 0.15f)),
    ) {
        Icon(icon, contentDescription, tint = colors.onBrand, modifier = Modifier.size(20.dp))
    }
}

// ── Bare list + detail screens (out of scope for #51, kept as-is) ────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InventoryListScreen(
    state: InventoryListUiState,
    onQueryChange: (String) -> Unit,
    onOpen: (Product) -> Unit,
    onAdd: () -> Unit,
    onPaste: () -> Unit,
    onExit: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Inventory") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.canManage) {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExtendedFloatingActionButton(
                        text = { Text(strings(Strings.inventory_paste_button)) },
                        icon = { Icon(Icons.Default.ContentPaste, null) },
                        onClick = onPaste,
                        containerColor = AromexTheme.colors.surface,
                        contentColor = AromexTheme.colors.brand,
                    )
                    ExtendedFloatingActionButton(
                        text = { Text("Add stock") },
                        icon = { Icon(Icons.Default.Add, null) },
                        onClick = onAdd,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (state.noAccess) {
                Text(strings(Strings.inventory_no_access))
                return@Column
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("Search brand / model / IMEI") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            state.error?.let { Spacer(Modifier.height(8.dp)); Text("Error: $it") }
            Spacer(Modifier.height(12.dp))
            if (state.rows.isEmpty()) {
                Text("No products yet.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.rows) { row ->
                        Card(Modifier.fillMaxWidth().clickable { onOpen(row.product) }) {
                            Column(Modifier.padding(12.dp)) {
                                Text(row.product.skuLabel(), fontWeight = FontWeight.Bold)
                                Text("${row.inStockCount} in stock · ${row.product.defaultSellingPrice}")
                                if (!row.product.isActive) Text("ARCHIVED")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkuDetailScreen(
    product: Product,
    units: List<Serial>,
    canManage: Boolean,
    onBack: () -> Unit,
    onAddUnits: () -> Unit,
    onEditPrice: (String) -> Unit,
    onArchiveSku: () -> Unit,
    onSellUnit: (String) -> Unit,
    onArchiveUnit: (String) -> Unit,
    onEditUnit: (String, String?, Condition?) -> Unit,
) {
    BackHandler(onBack = onBack)
    var priceText by remember(product.productId) { mutableStateOf(product.defaultSellingPrice) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(product.skuLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        floatingActionButton = {
            if (canManage) {
                ExtendedFloatingActionButton(
                    text = { Text("Add units") },
                    icon = { Icon(Icons.Default.Add, null) },
                    onClick = onAddUnits,
                )
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(product.skuLabel(), fontWeight = FontWeight.Bold)
                Text("${units.size} in stock")
                if (canManage) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            label = { Text("Selling price") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.width(180.dp),
                        )
                        TextButton(onClick = { onEditPrice(priceText) }) { Text("Save") }
                    }
                    TextButton(onClick = onArchiveSku) { Text("Archive SKU") }
                }
            }
            items(units) { unit ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("IMEI ${unit.imei}", fontWeight = FontWeight.Bold)
                        Text("${unit.cost} · ${unit.condition} · ${unit.status} · ${unit.location.name}")
                        if (canManage) {
                            Row {
                                TextButton(onClick = { onSellUnit(unit.serialId) }) { Text("Mark SOLD") }
                                TextButton(onClick = { onArchiveUnit(unit.serialId) }) { Text("Archive") }
                                TextButton(onClick = {
                                    onEditUnit(unit.serialId, null, if (unit.condition == Condition.NEW) Condition.USED else Condition.NEW)
                                }) { Text("Toggle N/U") }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun Product.skuLabel(): String = attributes.skuLabel()

private fun Map<AttributeType, AttributeRef>.skuLabel(): String =
    AttributeType.SKU_DEFINING.mapNotNull { this[it]?.name }.joinToString(" · ").ifBlank { "(incomplete SKU)" }


