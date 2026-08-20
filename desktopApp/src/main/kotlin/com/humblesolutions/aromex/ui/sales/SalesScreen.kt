package com.humblesolutions.aromex.ui.sales

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.WALK_IN_CUSTOMER_ID
import com.humblesolutions.aromex.ui.components.AromexDialog
import com.humblesolutions.aromex.ui.components.DesktopSection
import com.humblesolutions.aromex.ui.components.FilterableDropdownField
import androidx.compose.ui.zIndex
import com.humblesolutions.aromex.ui.components.CollapsedSidebarWidth
import com.humblesolutions.aromex.ui.components.CountryPickerDialog
import com.humblesolutions.aromex.ui.components.ExpandedSidebarWidth
import com.humblesolutions.aromex.ui.components.NavSidebar
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.money.DateField
import com.humblesolutions.aromex.ui.inventory.MoneyCell
import com.humblesolutions.aromex.ui.inventory.fieldColors
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.usecase.SettingsAudit
import com.humblesolutions.aromex.util.Countries
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.MoneyFormat
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.net.URI

/**
 * Desktop counter screen (ticket #63) — renders [SalesViewModel]'s [SalesUiState] and
 * dispatches its actions. No business logic here: totals/errors/canConfirm all come
 * from the ViewModel; this file only lays out the cart + checkout panes, the item
 * picker, and the confirm-outcome overlays.
 */
@Composable
fun SalesScreen(
    vm: SalesViewModel,
    session: UserSession?,
    onNavigateToEntities: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToSalesHistory: () -> Unit,
    onNavigateToMoney: () -> Unit = {},
    onNavigateToCommissionRules: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStockHistory: () -> Unit = {},
    onSignOut: () -> Unit,
) {
    val state by vm.uiState.collectAsState()
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

    var showPicker by remember { mutableStateOf(false) }
    var showAddCustom by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    val locked = state.confirmState is ConfirmState.Submitting

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize()) {
            NavSidebar(
                expanded = isSidebarExpanded,
                width = sidebarWidth,
                modifier = Modifier.zIndex(1f),
                selectedSection = DesktopSection.SALES,
                session = session,
                interactionSource = sidebarSrc,
                onNavigateToEntities = onNavigateToEntities,
                onNavigateToInventory = onNavigateToInventory,
                onNavigateToSales = {},
                onNavigateToSalesHistory = onNavigateToSalesHistory,
                onNavigateToMoney = onNavigateToMoney,
                onNavigateToCommissionRules = onNavigateToCommissionRules,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStockHistory = onNavigateToStockHistory,
                onSignOutRequest = { showSignOutDialog = true },
            )

            Column(Modifier.fillMaxSize().padding(start = CollapsedSidebarWidth).hoverable(contentSrc)) {
                SalesTopBar(sidebarExpanded = isSidebarExpanded, onExpandSidebar = { isSidebarExpanded = true })
                HorizontalDivider(color = colors.border)

                if (state.confirmState is ConfirmState.Error) {
                    ErrorBanner(
                        message = (state.confirmState as ConfirmState.Error).message,
                        onDismiss = vm::dismissConfirmState,
                    )
                }

                if (state.taxChangedMidSale) {
                    TaxChangedBanner(tax = state.taxConfig, onDismiss = vm::dismissTaxChangeNotice)
                }

                // Sales counter reflow (#72): side-by-side on a comfortably wide window, but
                // stack the panes vertically below ~860dp so neither is squeezed into clipping
                // as the window shrinks toward its 420dp minimum. Weighted children in both
                // arrangements keep the inner LazyColumn/scroll bounded (no nested-scroll crash).
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    if (maxWidth < 860.dp) {
                        Column(Modifier.fillMaxSize()) {
                            CartPane(
                                state = state,
                                vm = vm,
                                enabled = !locked,
                                onAddPhone = { showPicker = true },
                                onAddItem = { showAddCustom = true },
                                modifier = Modifier.fillMaxWidth().weight(1.3f),
                            )
                            HorizontalDivider(color = colors.border)
                            CheckoutPane(
                                state = state,
                                vm = vm,
                                enabled = !locked,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        }
                    } else {
                        Row(Modifier.fillMaxSize()) {
                            CartPane(
                                state = state,
                                vm = vm,
                                enabled = !locked,
                                onAddPhone = { showPicker = true },
                                onAddItem = { showAddCustom = true },
                                modifier = Modifier.weight(1.4f).fillMaxHeight(),
                            )
                            CheckoutPane(
                                state = state,
                                vm = vm,
                                enabled = !locked,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
        }

        if (showPicker) {
            ItemPickerDialog(state = state, vm = vm, onDismiss = { showPicker = false })
        }
        if (showAddCustom) {
            AddCustomLineDialog(
                currency = state.currency,
                onDismiss = { showAddCustom = false },
                onAdd = { name, price -> vm.addCustomLine(name, price); showAddCustom = false },
            )
        }
        if (state.confirmState is ConfirmState.Success) {
            SaleCompleteDialog(
                state = state,
                saleId = (state.confirmState as ConfirmState.Success).saleId,
                onNewSale = vm::startNewSale,
                onRetryInvoice = vm::retryInvoice,
            )
        }
        if (state.confirmState is ConfirmState.AlreadySold) {
            val already = state.confirmState as ConfirmState.AlreadySold
            AromexDialog(
                title = strings(Strings.sales_already_sold_title),
                message = strings(Strings.sales_already_sold_body, already.label),
                confirmLabel = strings(Strings.sales_already_sold_dismiss),
                dismissLabel = strings(Strings.sales_already_sold_dismiss),
                onConfirm = vm::dismissConfirmState,
                onDismiss = vm::dismissConfirmState,
            )
        }
        if (showSignOutDialog) {
            AromexDialog(
                title = strings(Strings.entities_sign_out),
                message = "You'll be returned to the sign-in screen.",
                confirmLabel = strings(Strings.entities_sign_out),
                dismissLabel = strings(Strings.entity_detail_cancel),
                onConfirm = { showSignOutDialog = false; onSignOut() },
                onDismiss = { showSignOutDialog = false },
            )
        }
    }
}

@Composable
private fun SalesTopBar(sidebarExpanded: Boolean, onExpandSidebar: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface)
            .padding(horizontal = dims.space20, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dims.space16),
    ) {
        if (!sidebarExpanded) {
            IconButton(onClick = onExpandSidebar) {
                Icon(Icons.Filled.Menu, "Expand menu", tint = colors.textSecondary)
            }
        }
        Text(
            strings(Strings.entities_sidebar_sales),
            style = AromexTheme.typography.sectionTitle,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.error.copy(alpha = 0.1f))
            .padding(horizontal = dims.space20, vertical = dims.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            strings(Strings.sales_error_title) + ": " + message,
            color = colors.error,
            style = AromexTheme.typography.hint,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Text(strings(Strings.sales_error_dismiss), color = colors.error)
        }
    }
}

/**
 * Shown when an admin changed the tax rate while this cart had items in it.
 *
 * The totals on screen have already moved. A cashier who read the old total out to the customer
 * needs to know why the number changed — silence here looks like the till glitching.
 */
@Composable
private fun TaxChangedBanner(tax: TaxConfig, onDismiss: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val summary = taxSummary(tax)
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.brandTint)
            .padding(horizontal = dims.space20, vertical = dims.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (summary == null) strings(Strings.sales_tax_changed_none)
            else strings(Strings.sales_tax_changed, summary),
            color = colors.textPrimary,
            style = AromexTheme.typography.hint,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Text(strings(Strings.sales_error_dismiss), color = colors.brand)
        }
    }
}

/** `"GST 5%, PST 7%"`, or null when nothing is charged. Rates render exactly (QST 9.975% stays so). */
private fun taxSummary(tax: TaxConfig): String? {
    val parts = buildList {
        if (tax.gstEnabled) add((if (tax.isHST) "HST " else "GST ") + SettingsAudit.asPercent(tax.gstRate))
        if (tax.pstEnabled) add("PST " + SettingsAudit.asPercent(tax.pstRate))
    }
    return parts.joinToString(", ").ifEmpty { null }
}

// ── Cart pane (left) ─────────────────────────────────────────────────────────

@Composable
private fun CartPane(
    state: SalesUiState,
    vm: SalesViewModel,
    enabled: Boolean,
    onAddPhone: () -> Unit,
    onAddItem: () -> Unit,
    modifier: Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    Column(modifier.background(colors.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(dims.space20),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.space12),
        ) {
            Text(
                strings(Strings.sales_cart_title),
                style = AromexTheme.typography.sectionTitle,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onAddItem,
                enabled = enabled,
                modifier = Modifier.height(40.dp).pointerHoverIcon(PointerIcon.Hand),
                shape = RoundedCornerShape(dims.radiusButton),
            ) {
                Text(strings(Strings.sales_cart_add_item), style = AromexTheme.typography.button, color = colors.brand)
            }
            Button(
                onClick = onAddPhone,
                enabled = enabled,
                modifier = Modifier.height(40.dp).pointerHoverIcon(PointerIcon.Hand),
                shape = RoundedCornerShape(dims.radiusButton),
                colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
            ) {
                Text(strings(Strings.sales_cart_add_phone), style = AromexTheme.typography.button, softWrap = false)
            }
        }
        HorizontalDivider(color = colors.border)

        if (state.cartLines.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                    Icon(Icons.Filled.Receipt, null, tint = colors.textTertiary, modifier = Modifier.size(40.dp))
                    Text(strings(Strings.sales_cart_empty_title), style = AromexTheme.typography.body, color = colors.textSecondary)
                    Text(strings(Strings.sales_cart_empty_body), style = AromexTheme.typography.hint, color = colors.textTertiary)
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(state.cartLines, key = { it.lineId }) { line ->
                    CartLineRow(
                        line = line,
                        currency = state.currency,
                        hasError = line.lineId in state.errors.lineDiscountExceedsPrice,
                        isUnpriced = line.lineId in state.errors.unpricedLines,
                        enabled = enabled,
                        onPriceChange = { vm.setUnitPrice(line.lineId, it) },
                        onDiscountChange = { vm.setLineDiscount(line.lineId, it) },
                        onRemove = { vm.removeLine(line.lineId) },
                    )
                    HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                }
            }
        }
        // The whole-sale discount moved to CheckoutPane (ticket #106) — it belongs next to the
        // totals it changes, not under the cart list.
    }
}

@Composable
private fun CartLineRow(
    line: CartLine,
    currency: String,
    hasError: Boolean,
    isUnpriced: Boolean,
    enabled: Boolean,
    onPriceChange: (String) -> Unit,
    onDiscountChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    // Derived from the model (CartLine.netAmount / Inventory.isDiscounted below) — never
    // re-derived here, so the UI can't drift from that single source of truth.
    val net = line.netAmount

    // Reusable cell composables so the wide (single-row) and compact (wrapped) layouts stay
    // in lockstep — #72 reflow: fixed-width columns overflowed a narrow cart pane, so below
    // ~520dp the price/discount fields drop under the name instead of clipping off the edge.
    @Composable
    fun LineIcon() = Box(
        Modifier.size(36.dp).clip(CircleShape).background(colors.brand.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (line is CartLine.Inventory) Icons.Filled.Smartphone else Icons.Filled.ShoppingBag,
            null,
            tint = colors.brand,
            modifier = Modifier.size(18.dp),
        )
    }

    @Composable
    fun LineIdentity(modifier: Modifier) = SelectionContainer(modifier) {
        Column {
            Text(
                when (line) {
                    is CartLine.Inventory -> line.label
                    is CartLine.Custom -> line.name
                },
                style = AromexTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (line is CartLine.Inventory) {
                Text(
                    line.imei,
                    style = AromexTheme.typography.hint,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (line is CartLine.Inventory && line.isDiscounted) {
                Text(
                    MoneyFormat.format(line.listPrice, currency),
                    style = AromexTheme.typography.hint.copy(textDecoration = TextDecoration.LineThrough),
                    color = colors.textTertiary,
                )
            }
        }
    }

    @Composable
    fun PriceCell(modifier: Modifier) = Column(modifier) {
        Text(strings(Strings.sales_cart_col_price), style = AromexTheme.typography.hint.copy(fontSize = 10.sp), color = colors.textTertiary)
        MoneyCell(
            value = line.unitPrice,
            onValue = onPriceChange,
            readOnly = !enabled,
            modifier = Modifier.fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(dims.radiusField)),
        )
    }

    @Composable
    fun DiscountCell(modifier: Modifier) = Column(modifier) {
        Text(strings(Strings.sales_cart_col_discount), style = AromexTheme.typography.hint.copy(fontSize = 10.sp), color = colors.textTertiary)
        MoneyCell(
            value = line.lineDiscount,
            onValue = onDiscountChange,
            readOnly = !enabled,
            modifier = Modifier.fillMaxWidth()
                .border(1.dp, if (hasError) colors.error else colors.border, RoundedCornerShape(dims.radiusField)),
        )
    }

    @Composable
    fun NetCell(modifier: Modifier, alignEnd: Boolean) = Column(
        modifier,
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
    ) {
        Text(strings(Strings.sales_cart_col_net), style = AromexTheme.typography.hint.copy(fontSize = 10.sp), color = colors.textTertiary)
        Text(
            MoneyFormat.format(net, currency),
            style = AromexTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    @Composable
    fun RemoveButton() = IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
        Icon(Icons.Filled.Close, strings(Strings.sales_cart_remove_cd), tint = colors.textTertiary, modifier = Modifier.size(18.dp))
    }

    BoxWithConstraints {
        if (maxWidth < 520.dp) {
            // Compact: identity on top, price/discount/net wrap beneath — nothing clips.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space12),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
            ) {
                LineIcon()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                    LineIdentity(Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(dims.space12), verticalAlignment = Alignment.Bottom) {
                        PriceCell(Modifier.weight(1f))
                        DiscountCell(Modifier.weight(1f))
                        NetCell(Modifier.weight(1f), alignEnd = true)
                    }
                }
                RemoveButton()
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
            ) {
                LineIcon()
                LineIdentity(Modifier.weight(1.3f))
                PriceCell(Modifier.width(110.dp))
                DiscountCell(Modifier.width(100.dp))
                NetCell(Modifier.width(90.dp), alignEnd = true)
                RemoveButton()
            }
        }
    }
    if (hasError || isUnpriced) {
        Box(Modifier.padding(start = 68.dp, bottom = 4.dp)) {
            HintText(
                strings(
                    if (hasError) Strings.sales_cart_line_discount_error
                    else Strings.sales_cart_line_unpriced,
                ),
            )
        }
    }
}

// ── Checkout pane (right) ────────────────────────────────────────────────────

@Composable
private fun CheckoutPane(
    state: SalesUiState,
    vm: SalesViewModel,
    enabled: Boolean,
    modifier: Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val symbol = MoneyFormat.symbolOf(state.currency)
    var showBuyerCountryPicker by remember { mutableStateOf(false) }
    var showContactCountryPicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.background(colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(dims.space20),
        verticalArrangement = Arrangement.spacedBy(dims.space20),
    ) {
        Text(strings(Strings.sales_checkout_title), style = AromexTheme.typography.sectionTitle, color = colors.textPrimary)

        Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
            FilterableDropdownField(
                label = strings(Strings.sales_checkout_customer_label),
                items = state.customerOptions.map { AttributeValue(attributeId = it.id, name = it.name) },
                selectedItem = state.selectedCustomer?.let { AttributeRef(it.id, it.name) },
                onItemSelected = { picked ->
                    if (picked.attributeId == WALK_IN_CUSTOMER_ID) {
                        vm.selectWalkIn()
                    } else {
                        state.customerOptions.firstOrNull { it.id == picked.attributeId }?.let(vm::selectCustomer)
                    }
                },
                onAddNew = if (enabled && vm.canAddCustomerInline()) { { name -> vm.addNewCustomer(name) } } else null,
                enabled = enabled,
                placeholder = strings(Strings.sales_checkout_customer_placeholder),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.customerAddError != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                    HintText(state.customerAddError)
                    TextButton(onClick = vm::dismissCustomerAddError, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Text(strings(Strings.sales_error_dismiss), color = colors.error, style = AromexTheme.typography.hint)
                    }
                }
            }
            TextButton(
                onClick = { if (enabled) vm.selectWalkIn() },
                enabled = enabled,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(Icons.Filled.PersonOutline, null, tint = colors.brand, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings(Strings.sales_checkout_walk_in_button), style = AromexTheme.typography.hint, color = colors.brand)
            }
            if (state.errors.noCustomer) HintText(strings(Strings.sales_error_no_customer))
        }

        // Customer tax number (ticket #106 follow-up): shown once a customer is selected, prefilled
        // from their contact and editable for this invoice. "Save to contact" persists it back (named
        // customer + profiles:manage only); a walk-in gets the field but no save.
        if (state.selectedCustomer != null) {
            Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                Text(
                    strings(Strings.sales_checkout_buyer_tax_number_label),
                    style = AromexTheme.typography.fieldLabel,
                    color = colors.textTertiary,
                )
                OutlinedTextField(
                    value = state.buyerTaxNumber,
                    onValueChange = vm::setBuyerTaxNumber,
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(strings(Strings.sales_checkout_buyer_tax_number_placeholder), color = colors.textTertiary) },
                    colors = fieldColors(),
                    shape = RoundedCornerShape(dims.radiusField),
                    modifier = Modifier.fillMaxWidth(),
                )
                when {
                    state.savingTaxNumber -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dims.space8),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.brand)
                        Text(strings(Strings.sales_action_save_tax_to_contact), style = AromexTheme.typography.hint, color = colors.textSecondary)
                    }
                    state.taxNumberSaveError -> HintText(strings(Strings.sales_tax_save_error))
                    state.taxNumberSaved -> Text(
                        strings(Strings.sales_tax_saved_to_contact),
                        style = AromexTheme.typography.hint,
                        color = colors.success,
                    )
                    state.canSaveTaxToContact -> TextButton(
                        onClick = { if (enabled) vm.saveBuyerTaxNumberToContact() },
                        enabled = enabled,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(strings(Strings.sales_action_save_tax_to_contact), style = AromexTheme.typography.hint, color = colors.brand)
                    }
                }
            }
        }

        // Customer phone: the twin of the tax-number field above — shown for a named customer,
        // prefilled from their primary number and editable for this invoice. "Save to contact"
        // persists it back as their primary number (profiles:manage only). A walk-in uses the
        // separate Bill-To phone field below instead, so this is hidden for the walk-in party.
        if (state.selectedCustomer != null && !state.isWalkIn) {
            Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                Text(
                    strings(Strings.sales_buyer_phone_label),
                    style = AromexTheme.typography.fieldLabel,
                    color = colors.textTertiary,
                )
                // Same dial-code-chip control as the walk-in Bill-To phone, so the two match.
                BuyerPhoneField(
                    phone = state.buyerContactPhone,
                    country = Countries.byIso(state.buyerContactCountryIso),
                    enabled = enabled,
                    onPhoneChange = vm::setBuyerContactPhone,
                    onCountryClick = { showContactCountryPicker = true },
                )
                when {
                    state.savingPhone -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dims.space8),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.brand)
                        Text(strings(Strings.sales_action_save_tax_to_contact), style = AromexTheme.typography.hint, color = colors.textSecondary)
                    }
                    state.phoneSaveError -> HintText(strings(Strings.sales_tax_save_error))
                    state.phoneSaved -> Text(
                        strings(Strings.sales_tax_saved_to_contact),
                        style = AromexTheme.typography.hint,
                        color = colors.success,
                    )
                    state.canSavePhoneToContact -> TextButton(
                        onClick = { if (enabled) vm.saveBuyerPhoneToContact() },
                        enabled = enabled,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text(strings(Strings.sales_action_save_tax_to_contact), style = AromexTheme.typography.hint, color = colors.brand)
                    }
                }
            }
            if (showContactCountryPicker) {
                CountryPickerDialog(
                    selected = Countries.byIso(state.buyerContactCountryIso),
                    onSelect = { vm.setBuyerContactCountryIso(it.iso); showContactCountryPicker = false },
                    onDismiss = { showContactCountryPicker = false },
                )
            }
        }

        // Walk-in buyer capture (ticket #77): optional Bill-To name/phone for the invoice,
        // shown only for the anonymous party. Blank → the PDF reads "Walk-in Customer".
        // Free-text; never gates Confirm. A named customer's Entity details are used instead.
        if (state.isWalkIn) {
            Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                Text(strings(Strings.sales_buyer_name_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                OutlinedTextField(
                    value = state.buyerName,
                    onValueChange = vm::setBuyerName,
                    enabled = enabled,
                    singleLine = true,
                    placeholder = { Text(strings(Strings.sales_buyer_name_placeholder), color = colors.textTertiary) },
                    colors = fieldColors(),
                    shape = RoundedCornerShape(dims.radiusField),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(strings(Strings.sales_buyer_phone_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                BuyerPhoneField(
                    phone = state.buyerPhone,
                    country = Countries.byIso(state.buyerCountryIso),
                    enabled = enabled,
                    onPhoneChange = vm::setBuyerPhone,
                    onCountryClick = { showBuyerCountryPicker = true },
                )
            }
        }
        if (showBuyerCountryPicker) {
            CountryPickerDialog(
                selected = Countries.byIso(state.buyerCountryIso),
                onSelect = { vm.setBuyerCountryIso(it.iso); showBuyerCountryPicker = false },
                onDismiss = { showBuyerCountryPicker = false },
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
            MoneyInputField(
                label = strings(Strings.sales_checkout_cash, symbol),
                value = state.payments.cash,
                onValueChange = vm::setCash,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            MoneyInputField(
                label = strings(Strings.sales_checkout_card, symbol),
                value = state.payments.card,
                onValueChange = vm::setCard,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            MoneyInputField(
                label = strings(Strings.sales_checkout_bank, symbol),
                value = state.payments.bank,
                onValueChange = vm::setBank,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.errors.overpayment) HintText(strings(Strings.sales_error_overpayment))
        if (state.errors.walkInMustPayInFull) HintText(strings(Strings.sales_error_walk_in_full))

        // The business date. Today by default; the picker refuses future dates. A backdated sale
        // is called out below rather than left to be noticed on the invoice afterwards.
        DateField(
            label = strings(Strings.sale_date_label),
            millis = state.saleDate,
            onPick = vm::setSaleDate,
            enabled = enabled,
        )
        if (!isToday(state.saleDate)) {
            HintText(strings(Strings.sale_date_backdated, longDateFormat.format(Date(state.saleDate))))
        }

        Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
            Text(strings(Strings.sales_checkout_note_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
            OutlinedTextField(
                value = state.note,
                onValueChange = vm::setNote,
                enabled = enabled,
                placeholder = { Text(strings(Strings.sales_checkout_note_placeholder), color = colors.textTertiary) },
                colors = fieldColors(),
                shape = RoundedCornerShape(dims.radiusField),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Tax-inclusive toggle (ticket #106): per sale, resets to off on each new sale. Placed with
        // the totals — it changes how they're computed.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.space12),
        ) {
            Text(
                strings(Strings.sales_checkout_tax_inclusive_label),
                style = AromexTheme.typography.body,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = state.taxInclusive,
                onCheckedChange = { if (enabled) vm.setTaxInclusive(it) },
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.surface,
                    checkedTrackColor = colors.brand,
                    uncheckedThumbColor = colors.surface,
                    uncheckedTrackColor = colors.border,
                ),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            )
        }

        // Whole-sale discount (moved here from the cart pane, ticket #106) — sits with the totals.
        Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
                Text(
                    strings(Strings.sales_cart_sale_discount_label),
                    style = AromexTheme.typography.fieldLabel,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                MoneyCell(
                    value = state.saleDiscount,
                    onValue = vm::setSaleDiscount,
                    readOnly = !enabled,
                    modifier = Modifier.width(120.dp).border(1.dp, colors.border, RoundedCornerShape(dims.radiusField)),
                )
            }
            if (state.errors.saleDiscountExceedsSubtotal) {
                HintText(strings(Strings.sales_error_sale_discount))
            }
        }

        SelectionContainer { TotalsCard(state = state) }

        if (state.errors.emptyCart) HintText(strings(Strings.sales_error_empty_cart))

        PrimaryButton(
            label = strings(Strings.sales_confirm_button),
            onClick = vm::confirmSale,
            enabled = state.canConfirm,
            loading = state.confirmState is ConfirmState.Submitting,
            loadingLabel = strings(Strings.sales_confirm_submitting),
        )
    }
}

/**
 * Walk-in buyer phone field — a country dial-code chip + number entry, styled to match the
 * entity Add-Customer form's PhoneFormField so the two feel like the same control.
 */
@Composable
private fun BuyerPhoneField(
    phone: String,
    country: com.humblesolutions.aromex.util.Country,
    enabled: Boolean,
    onPhoneChange: (String) -> Unit,
    onCountryClick: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) colors.brand else colors.border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dims.fieldHeight)
            .clip(RoundedCornerShape(dims.radiusField))
            .border(dims.borderField, borderColor, RoundedCornerShape(dims.radiusField)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .background(colors.surfaceAlt)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(enabled = enabled, onClick = onCountryClick)
                .padding(horizontal = dims.space12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(country.iso, style = AromexTheme.typography.fieldLabel.copy(fontWeight = FontWeight.Bold), color = colors.textPrimary)
            Text(country.dialCode, style = AromexTheme.typography.button, color = colors.textSecondary)
            Icon(Icons.Filled.ExpandMore, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
        }
        Box(Modifier.width(dims.borderField).fillMaxHeight().background(colors.border))
        BasicTextField(
            value = phone,
            onValueChange = onPhoneChange,
            textStyle = AromexTheme.typography.body.copy(color = colors.textPrimary),
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            cursorBrush = SolidColor(colors.brand),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = dims.space12)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box {
                    if (phone.isEmpty()) Text(strings(Strings.sales_buyer_phone_placeholder), style = AromexTheme.typography.body, color = colors.textTertiary)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun MoneyInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.filterToDecimalInput()) },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text("0", color = colors.textTertiary) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            colors = fieldColors(),
            shape = RoundedCornerShape(dims.radiusField),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Keeps only digits and (at most) one decimal point — hardware-keyboard equivalent of the
 *  numeric-only input the ticket calls for; [KeyboardType.Decimal] alone only hints a soft
 *  keyboard and doesn't stop a physical keyboard from typing letters. */
private fun String.filterToDecimalInput(): String {
    val kept = filter { it.isDigit() || it == '.' }
    val firstDot = kept.indexOf('.')
    if (firstDot == -1) return kept
    return kept.substring(0, firstDot + 1) + kept.substring(firstDot + 1).replace(".", "")
}

@Composable
private fun TotalsCard(state: SalesUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val currency = state.currency
    val totals = state.totals

    val balanceColor = when {
        state.errors.overpayment || state.errors.walkInMustPayInFull -> colors.error
        Money.isZero(state.balanceRemaining) -> colors.success
        else -> colors.warning
    }

    Column(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(dims.radiusCard))
            .background(colors.surfaceAlt)
            .padding(dims.space16),
        verticalArrangement = Arrangement.spacedBy(dims.space8),
    ) {
        TotalsRow(strings(Strings.sales_totals_subtotal), MoneyFormat.format(totals.subtotal, currency))
        totals.taxLines.forEach { tax ->
            TotalsRow("${tax.name} (${tax.rate})", MoneyFormat.format(tax.amount, currency))
        }
        HorizontalDivider(color = colors.border)
        TotalsRow(
            strings(Strings.sales_totals_grand_total),
            MoneyFormat.format(totals.grandTotal, currency),
            emphasize = true,
        )
        TotalsRow(strings(Strings.sales_totals_paid), MoneyFormat.format(state.amountPaid, currency))
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(dims.radiusField))
                .background(balanceColor.copy(alpha = 0.12f))
                .padding(horizontal = dims.space12, vertical = dims.space8),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(strings(Strings.sales_totals_balance), style = AromexTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = balanceColor)
            Text(
                MoneyFormat.format(state.balanceRemaining, currency),
                style = AromexTheme.typography.body.copy(fontWeight = FontWeight.Bold),
                color = balanceColor,
            )
        }
    }
}

@Composable
private fun TotalsRow(label: String, value: String, emphasize: Boolean = false) {
    val colors = AromexTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = if (emphasize) AromexTheme.typography.body.copy(fontWeight = FontWeight.SemiBold) else AromexTheme.typography.hint,
            color = if (emphasize) colors.textPrimary else colors.textSecondary,
        )
        Text(
            value,
            style = if (emphasize) AromexTheme.typography.body.copy(fontWeight = FontWeight.Bold) else AromexTheme.typography.hint,
            color = colors.textPrimary,
        )
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, style = AromexTheme.typography.hint, color = AromexTheme.colors.error)
}

// ── Item picker modal ─────────────────────────────────────────────────────────

@Composable
private fun ItemPickerDialog(state: SalesUiState, vm: SalesViewModel, onDismiss: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val scrimColor = colors.background.copy(alpha = 0.75f)

    // Drill-down position within the picker — pure view navigation, so it lives here rather
    // than in the ViewModel. Cleared when the dialog reopens or the search box takes over.
    var brand by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf<String?>(null) }

    val searching = state.pickerSearchQuery.isNotBlank()
    val byBrandModel = state.pickerByBrandModel
    // A brand/model can empty out when the location filter changes — fall back rather than
    // stranding the user on a screen with nothing on it.
    val brandModels = brand?.let { byBrandModel[it] }
    if (brand != null && brandModels == null) { brand = null; model = null }
    if (model != null && brandModels?.get(model) == null) model = null

    val locations = remember(state.allInStockUnits) {
        state.allInStockUnits
            .filter { it.status == SerialStatus.IN_STOCK && it.isActive }
            .map { it.location }
            .distinctBy { it.attributeId }
            .filter { it.attributeId.isNotBlank() }
            .sortedBy { it.name.lowercase() }
    }
    val locationCount: (String?) -> Int = { id ->
        state.allInStockUnits.count {
            it.status == SerialStatus.IN_STOCK && it.isActive && (id == null || it.location.attributeId == id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { onDismiss(); true } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                // Responsive but never sprawling: shrinks with a small window, and on a big
                // screen stays a comfortable panel rather than swallowing the whole display.
                .fillMaxWidth(0.78f)
                .widthIn(min = 620.dp, max = 960.dp)
                .fillMaxHeight(0.78f)
                .heightIn(min = 420.dp, max = 640.dp)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                    .padding(dims.space20),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.Smartphone, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(dims.space12))
                    Text(
                        strings(Strings.sales_picker_title),
                        style = typography.sectionTitle,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.Filled.Close, strings(Strings.sales_close_cd), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            // One compact toolbar: breadcrumb (left) · location chips + search (right).
            // Kept on a single band so the controls read as a group instead of two sparse rows.
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt)
                    .padding(horizontal = dims.space16, vertical = dims.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
            ) {
                if (brand != null && !searching) {
                    IconButton(
                        onClick = { if (model != null) model = null else brand = null },
                        modifier = Modifier.size(30.dp).pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            strings(Strings.sales_picker_back_cd),
                            tint = colors.textSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PickerCrumb(strings(Strings.sales_picker_all_brands), isLink = brand != null) { brand = null; model = null }
                    brand?.let {
                        Icon(Icons.Filled.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
                        PickerCrumb(it, isLink = model != null) { model = null }
                    }
                    model?.let {
                        Icon(Icons.Filled.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
                        PickerCrumb(it, isLink = false) {}
                    }
                }
                PickerSearchField(
                    value = state.pickerSearchQuery,
                    onValueChange = vm::onPickerSearchChanged,
                    onClear = { vm.onPickerSearchChanged("") },
                )
            }

            // Always-present location switcher — its own quiet band under the toolbar.
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt)
                    .padding(start = dims.space16, end = dims.space16, bottom = dims.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.LocationOn, null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
                Text(
                    strings(Strings.sales_picker_location),
                    style = typography.hint.copy(fontSize = 12.sp),
                    color = colors.textTertiary,
                    modifier = Modifier.padding(end = 2.dp),
                )
                PickerLocationChip(
                    name = strings(Strings.inventory_browse_all),
                    count = locationCount(null),
                    selected = state.pickerLocationFilter == null,
                    onClick = { vm.onPickerLocationFilterChanged(null) },
                )
                locations.forEach { loc ->
                    PickerLocationChip(
                        name = loc.name,
                        count = locationCount(loc.attributeId),
                        selected = state.pickerLocationFilter == loc.attributeId,
                        onClick = { vm.onPickerLocationFilterChanged(loc.attributeId) },
                    )
                }
            }
            HorizontalDivider(color = colors.border)

            val units: List<Serial>? = when {
                searching -> state.pickerUnits
                model != null -> brandModels?.get(model).orEmpty()
                else -> null
            }
            val at = state.pickerLocationFilter
                ?.let { id -> locations.firstOrNull { it.attributeId == id }?.name }
                ?.let { " ${strings(Strings.sales_picker_at)} $it" }
                .orEmpty()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    (units != null && units.isEmpty()) || (units == null && byBrandModel.isEmpty()) ->
                        PickerEmpty(at.isNotEmpty()) { vm.onPickerLocationFilterChanged(null) }

                    // Units — a proper table (search results, or the chosen model's stock).
                    units != null -> Column(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(start = dims.space20, end = dims.space20, top = dims.space16)) {
                            PickerStepHeader(
                                title = if (searching) strings(Strings.sales_picker_results) else model.orEmpty(),
                                sub = if (searching) {
                                    "${units.size} ${plural(units.size, Strings.sales_picker_unit_one, Strings.sales_picker_units_match)} " +
                                        "“${state.pickerSearchQuery.trim()}”$at"
                                } else {
                                    "${units.size} ${strings(Strings.sales_picker_in_stock)}$at"
                                },
                            )
                        }
                        PickerUnitTableHeader(showPhone = searching, showLocation = at.isEmpty())
                        HorizontalDivider(color = colors.border)
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(units, key = { _, s -> s.serialId }) { index, serial ->
                                PickerUnitRow(
                                    serial = serial,
                                    phone = if (searching) {
                                        listOfNotNull(
                                            state.attributeOf(serial, AttributeType.BRAND),
                                            state.attributeOf(serial, AttributeType.MODEL),
                                        ).joinToString(" · ").ifBlank { "—" }
                                    } else {
                                        null
                                    },
                                    // Optional attributes (ticket #101) render as an empty cell, not "—".
                                    capacity = state.attributeOf(serial, AttributeType.CAPACITY).orEmpty(),
                                    colour = state.attributeOf(serial, AttributeType.COLOR).orEmpty(),
                                    carrier = state.attributeOf(serial, AttributeType.CARRIER).orEmpty(),
                                    cost = MoneyFormat.format(serial.cost, state.currency),
                                    // Unpriced SKUs (ticket #101) show an empty cell — "$" with
                                    // no number reads as a broken row, not as "not priced yet".
                                    price = state.listPriceOf(serial)
                                        .takeIf { it.isNotBlank() }
                                        ?.let { MoneyFormat.format(it, state.currency) }
                                        .orEmpty(),
                                    showLocation = at.isEmpty(),
                                    inCart = serial.serialId in state.cartSerialIds,
                                    striped = index % 2 == 1,
                                    // Add doubles as remove — tapping an added unit takes it back out.
                                    onToggle = {
                                        val lineId = state.lineIdForSerial(serial.serialId)
                                        if (lineId != null) vm.removeLine(lineId) else vm.addUnitToCart(serial.serialId)
                                    },
                                )
                                HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                            }
                        }
                    }

                    // Brands — responsive card grid (mirrors the reference's auto-fit columns).
                    brand == null -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 250.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(dims.space20),
                        horizontalArrangement = Arrangement.spacedBy(dims.space12),
                        verticalArrangement = Arrangement.spacedBy(dims.space12),
                    ) {
                        item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) {
                            PickerStepHeader(
                                title = strings(Strings.sales_picker_select_brand),
                                sub = "${state.pickerUnits.size} " +
                                    "${plural(state.pickerUnits.size, Strings.sales_picker_phone_one, Strings.sales_picker_phones)} " +
                                    "${strings(Strings.sales_picker_in_stock)}$at · " +
                                    "${strings(Strings.sales_picker_across)} ${byBrandModel.size} " +
                                    plural(byBrandModel.size, Strings.sales_picker_brand_one, Strings.sales_picker_brands),
                            )
                        }
                        byBrandModel.forEach { (name, models) ->
                            item(key = "brand_$name") {
                                PickerBrandCard(
                                    name = name,
                                    units = models.values.sumOf { it.size },
                                    models = models.size,
                                    onClick = { brand = name },
                                )
                            }
                        }
                    }

                    // Models of the chosen brand — same responsive card grid.
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 250.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(dims.space20),
                        horizontalArrangement = Arrangement.spacedBy(dims.space12),
                        verticalArrangement = Arrangement.spacedBy(dims.space12),
                    ) {
                        val models = brandModels.orEmpty()
                        item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) {
                            PickerStepHeader(
                                title = brand.orEmpty(),
                                sub = "${models.values.sumOf { it.size }} " +
                                    "${plural(models.values.sumOf { it.size }, Strings.sales_picker_phone_one, Strings.sales_picker_phones)} " +
                                    "${strings(Strings.sales_picker_in_stock)}$at",
                            )
                        }
                        models.forEach { (name, serials) ->
                            item(key = "model_$name") {
                                PickerModelCard(
                                    name = name,
                                    serials = serials,
                                    inCart = serials.count { it.serialId in state.cartSerialIds },
                                    capacities = serials.mapNotNull { state.attributeOf(it, AttributeType.CAPACITY) }.distinct(),
                                    onClick = { model = name },
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt).padding(dims.space16),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
            ) {
                val added = state.cartSerialIds.size
                Text(
                    if (added == 0) {
                        strings(Strings.sales_cart_empty_title)
                    } else {
                        "$added ${strings(Strings.sales_picker_phones)} ${strings(Strings.sales_picker_in_sale)} · " +
                            MoneyFormat.format(state.totals.subtotal, state.currency)
                    },
                    style = typography.hint,
                    color = if (added == 0) colors.textTertiary else colors.textPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // A plain Button, not PrimaryButton — the latter is fillMaxWidth by design and
                // stretched Done across the whole footer.
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.height(40.dp).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    contentPadding = PaddingValues(horizontal = dims.space20),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    Text(strings(Strings.sales_picker_done), style = typography.button, softWrap = false)
                }
            }
        }
    }
}

/** Picks the singular or plural word for [count] — avoids "across 1 brands". */
@Composable
private fun plural(count: Int, oneKey: String, manyKey: String): String =
    if (count == 1) strings(oneKey) else strings(manyKey)

// Shared column weights — the header and every row use these, so the table stays aligned.
private const val UCOL_PHONE = 1.4f
private const val UCOL_IMEI = 1.7f
private const val UCOL_CAP = 0.75f
private const val UCOL_COLOUR = 1.0f
private const val UCOL_CARRIER = 0.9f
private const val UCOL_LOC = 0.85f
private const val UCOL_COND = 0.8f
private const val UCOL_COST = 0.75f
private const val UCOL_PRICE = 0.75f
private val UCOL_ACTION = 104.dp

/** How long "Link copied" stays on the Copy button before it reverts to its normal label. */
private const val COPY_CONFIRM_MS = 2_000L

/** A compact, self-drawn search field — matches the toolbar's height instead of towering over it. */
@Composable
private fun PickerSearchField(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .width(240.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(dims.radiusField))
            .background(colors.surface)
            .border(1.dp, if (focused) colors.brand else colors.border, RoundedCornerShape(dims.radiusField))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Search, null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    strings(Strings.sales_picker_search),
                    style = AromexTheme.typography.hint.copy(fontSize = 12.sp),
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = AromexTheme.typography.hint.copy(fontSize = 12.sp, color = colors.textPrimary),
                cursorBrush = SolidColor(colors.brand),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                strings(Strings.sales_close_cd),
                tint = colors.textTertiary,
                modifier = Modifier.size(14.dp).pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClear),
            )
        }
    }
}

/** Column labels for the unit table — same weights as [PickerUnitRow] so they line up. */
@Composable
private fun PickerUnitTableHeader(showPhone: Boolean, showLocation: Boolean) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    @Composable
    fun cell(key: String, mod: Modifier) = Text(
        strings(key),
        modifier = mod.padding(end = 8.dp),
        style = AromexTheme.typography.hint.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(start = dims.space20, end = dims.space20, top = dims.space12, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showPhone) cell(Strings.sales_picker_col_phone, Modifier.weight(UCOL_PHONE))
        cell(Strings.sales_picker_col_imei, Modifier.weight(UCOL_IMEI))
        cell(Strings.sales_picker_col_capacity, Modifier.weight(UCOL_CAP))
        cell(Strings.sales_picker_col_colour, Modifier.weight(UCOL_COLOUR))
        cell(Strings.sales_picker_col_carrier, Modifier.weight(UCOL_CARRIER))
        if (showLocation) cell(Strings.sales_picker_col_location, Modifier.weight(UCOL_LOC))
        cell(Strings.sales_picker_col_condition, Modifier.weight(UCOL_COND))
        cell(Strings.sales_picker_col_cost, Modifier.weight(UCOL_COST))
        cell(Strings.sales_picker_col_price, Modifier.weight(UCOL_PRICE))
        Spacer(Modifier.width(UCOL_ACTION))
    }
}

/** The "Select a brand" / "Apple" title plus its count line, above each step's content. */
@Composable
private fun PickerStepHeader(title: String, sub: String) {
    Column(Modifier.padding(bottom = AromexTheme.dimensions.space8)) {
        Text(
            title,
            style = AromexTheme.typography.sectionTitle,
            color = AromexTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sub,
            style = AromexTheme.typography.hint,
            color = AromexTheme.colors.textTertiary,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickerCrumb(text: String, isLink: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    Text(
        text,
        style = AromexTheme.typography.hint.copy(fontWeight = if (isLink) FontWeight.Normal else FontWeight.SemiBold),
        color = if (isLink) colors.brand else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (isLink) {
            Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick).padding(horizontal = 2.dp)
        } else {
            Modifier.padding(horizontal = 2.dp)
        },
    )
}

@Composable
private fun PickerEmpty(locationFiltered: Boolean, onShowAll: () -> Unit) {
    val colors = AromexTheme.colors
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Inventory2, null, tint = colors.textTertiary, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(AromexTheme.dimensions.space12))
        Text(
            if (locationFiltered) strings(Strings.sales_picker_none_here) else strings(Strings.sales_picker_empty),
            color = colors.textTertiary,
            style = AromexTheme.typography.body,
        )
        if (locationFiltered) {
            Spacer(Modifier.height(AromexTheme.dimensions.space8))
            Text(
                strings(Strings.sales_picker_show_all_locations),
                style = AromexTheme.typography.hint,
                color = colors.brand,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onShowAll),
            )
        }
    }
}

/**
 * Hover-reactive card chrome shared by the brand + model grid cards. [height] is fixed so
 * every card in the grid is exactly the same size regardless of how much it has to say.
 */
@Composable
private fun PickerCard(height: Dp, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Column(
        modifier = Modifier.fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(dims.radiusField))
            .background(if (hovered) colors.brandTint else colors.surface)
            .border(1.dp, if (hovered) colors.brand else colors.border, RoundedCornerShape(dims.radiusField))
            .hoverable(src)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(dims.space16),
        verticalArrangement = Arrangement.spacedBy(dims.space8),
        content = content,
    )
}

/**
 * A title that shrinks to fit rather than ellipsizing — long model names ("iPhone 12 Pro Max")
 * stay fully readable inside a fixed-size card.
 */
@Composable
private fun PickerAutoTitle(text: String, modifier: Modifier = Modifier, maxLines: Int = 2) {
    BasicText(
        text = text,
        modifier = modifier,
        style = AromexTheme.typography.body.copy(
            fontWeight = FontWeight.Medium,
            color = AromexTheme.colors.textPrimary,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 16.sp, stepSize = 0.5.sp),
    )
}

@Composable
private fun PickerBrandCard(name: String, units: Int, models: Int, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    PickerCard(height = 84.dp, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.space12),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(dims.radiusField)).background(colors.brandTint),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), style = AromexTheme.typography.sectionTitle, color = colors.brand)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PickerAutoTitle(name, maxLines = 1)
                Text(
                    "$units ${strings(Strings.sales_picker_in_stock)} · $models " +
                        plural(models, Strings.sales_picker_model_one, Strings.sales_picker_models),
                    style = AromexTheme.typography.hint.copy(fontSize = 12.sp),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Filled.ChevronRight, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickerModelCard(
    name: String,
    serials: List<Serial>,
    inCart: Int,
    capacities: List<String>,
    onClick: () -> Unit,
) {
    val colors = AromexTheme.colors
    PickerCard(height = 124.dp, onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PickerAutoTitle(name, modifier = Modifier.weight(1f))
            Box(
                Modifier.clip(RoundedCornerShape(16.dp)).background(colors.brandTint)
                    .padding(horizontal = 9.dp, vertical = 3.dp),
            ) {
                Text(
                    "${serials.size} ${strings(Strings.sales_picker_in_stock)}",
                    style = AromexTheme.typography.hint.copy(fontSize = 11.sp),
                    color = colors.brand,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        // Capacities wrap; the card's fixed height means an unusually long list is clipped
        // rather than stretching this card out of line with its neighbours.
        if (capacities.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                capacities.forEach { cap -> PickerTag(cap) }
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (inCart > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = colors.success, modifier = Modifier.size(13.dp))
                Text(
                    "$inCart ${strings(Strings.sales_picker_in_sale)}",
                    style = AromexTheme.typography.hint.copy(fontSize = 12.sp),
                    color = colors.success,
                    maxLines = 1,
                )
            }
        }
    }
}

/** A neutral outlined chip (capacity, location). */
@Composable
private fun PickerTag(text: String) {
    val colors = AromexTheme.colors
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(colors.surfaceAlt)
            .border(1.dp, colors.border, RoundedCornerShape(7.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, style = AromexTheme.typography.hint.copy(fontSize = 12.sp), color = colors.textSecondary, maxLines = 1)
    }
}

/** New = success-tinted, Used = warning-tinted — mirrors the inventory screens' condition chips. */
@Composable
private fun PickerConditionTag(condition: Condition) {
    val colors = AromexTheme.colors
    val isNew = condition == Condition.NEW
    val tint = if (isNew) colors.success else colors.warning
    Box(
        Modifier.clip(RoundedCornerShape(7.dp)).background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            if (isNew) strings(Strings.inventory_condition_new) else strings(Strings.inventory_condition_used),
            style = AromexTheme.typography.hint.copy(fontSize = 12.sp),
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * One row of the unit table — plain aligned columns (no card chrome), so a long list reads
 * like a spreadsheet. The action doubles as a toggle: an added unit can be taken back out.
 */
@Composable
private fun PickerUnitRow(
    serial: Serial,
    phone: String?,
    capacity: String,
    colour: String,
    carrier: String,
    cost: String,
    price: String,
    showLocation: Boolean,
    inCart: Boolean,
    striped: Boolean,
    onToggle: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    @Composable
    fun cell(text: String, mod: Modifier, emphasis: Boolean = false) = Text(
        text,
        modifier = mod.padding(end = 8.dp),
        style = if (emphasis) {
            AromexTheme.typography.hint.copy(fontWeight = FontWeight.Medium, color = colors.textPrimary)
        } else {
            AromexTheme.typography.hint.copy(color = colors.textSecondary)
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(
                when {
                    inCart -> colors.brandTint
                    hovered -> colors.surfaceAlt
                    striped -> colors.background.copy(alpha = 0.4f)
                    else -> Color.Transparent
                },
            )
            .hoverable(src)
            .padding(start = dims.space20, end = dims.space20, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        phone?.let { cell(it, Modifier.weight(UCOL_PHONE), emphasis = true) }
        cell(serial.imei, Modifier.weight(UCOL_IMEI), emphasis = true)
        cell(capacity, Modifier.weight(UCOL_CAP))
        cell(colour, Modifier.weight(UCOL_COLOUR))
        cell(carrier, Modifier.weight(UCOL_CARRIER))
        if (showLocation) cell(serial.location.name.ifBlank { "—" }, Modifier.weight(UCOL_LOC))
        Box(Modifier.weight(UCOL_COND).padding(end = 8.dp)) { PickerConditionTag(serial.condition) }
        cell(cost, Modifier.weight(UCOL_COST))
        cell(price, Modifier.weight(UCOL_PRICE), emphasis = true)
        Box(Modifier.width(UCOL_ACTION), contentAlignment = Alignment.CenterEnd) {
            if (inCart) {
                // Tapping again removes it — hovering swaps the label to make that discoverable.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(dims.radiusButton))
                        .background(if (hovered) colors.error.copy(alpha = 0.12f) else colors.success.copy(alpha = 0.14f))
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        if (hovered) Icons.Filled.Close else Icons.Filled.CheckCircle,
                        null,
                        tint = if (hovered) colors.error else colors.success,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        if (hovered) strings(Strings.sales_picker_remove_cd) else strings(Strings.sales_picker_added_cd),
                        style = AromexTheme.typography.hint.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = if (hovered) colors.error else colors.success,
                        softWrap = false,
                    )
                }
            } else {
                Button(
                    onClick = onToggle,
                    modifier = Modifier.height(32.dp).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(
                        strings(Strings.sales_picker_add_cd),
                        style = AromexTheme.typography.hint.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun PickerLocationChip(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.brand else colors.surface)
            .border(1.dp, if (selected) colors.brand else colors.border, RoundedCornerShape(16.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            name,
            style = AromexTheme.typography.hint.copy(fontSize = 12.sp),
            color = if (selected) Color.White else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$count",
            style = AromexTheme.typography.hint.copy(fontSize = 12.sp),
            color = if (selected) Color.White.copy(alpha = 0.8f) else colors.textTertiary,
        )
    }
}

// ── Add-custom-line dialog ────────────────────────────────────────────────────

@Composable
private fun AddCustomLineDialog(currency: String, onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val symbol = MoneyFormat.symbolOf(currency)
    val scrimColor = colors.background.copy(alpha = 0.75f)

    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) { onDismiss(); true } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                    .padding(dims.space20),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.ShoppingBag, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(dims.space12))
                    Text(strings(Strings.sales_custom_title), style = typography.sectionTitle, color = Color.White, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.Filled.Close, strings(Strings.sales_close_cd), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            Column(modifier = Modifier.padding(dims.space20), verticalArrangement = Arrangement.spacedBy(dims.space16)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings(Strings.sales_custom_name_label), style = typography.fieldLabel, color = colors.textTertiary)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        placeholder = { Text(strings(Strings.sales_custom_name_placeholder), color = colors.textTertiary) },
                        colors = fieldColors(),
                        shape = RoundedCornerShape(dims.radiusField),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings(Strings.sales_custom_price_label, symbol), style = typography.fieldLabel, color = colors.textTertiary)
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it.filterToDecimalInput() },
                        singleLine = true,
                        placeholder = { Text("0", color = colors.textTertiary) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = fieldColors(),
                        shape = RoundedCornerShape(dims.radiusField),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(dims.space16),
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                ) {
                    Text(strings(Strings.sales_custom_cancel), style = typography.button, color = colors.textSecondary)
                }
                Button(
                    onClick = { onAdd(name.trim(), price.trim()) },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f).height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    Text(strings(Strings.sales_custom_add), style = typography.button, softWrap = false)
                }
            }
        }
    }
}

// ── Sale complete dialog ──────────────────────────────────────────────────────

@Composable
private fun SaleCompleteDialog(
    state: SalesUiState,
    saleId: String,
    onNewSale: () -> Unit,
    onRetryInvoice: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val currency = state.currency
    val scrimColor = colors.background.copy(alpha = 0.85f)

    Box(modifier = Modifier.fillMaxSize().background(scrimColor), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                    .padding(dims.space24),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.CheckCircle, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                    Spacer(Modifier.height(dims.space12))
                    Text(strings(Strings.sales_success_eyebrow), style = typography.fieldLabel, color = Color.White.copy(alpha = 0.7f))
                    Text(strings(Strings.sales_success_title), style = typography.sectionTitle, color = Color.White)
                }
            }
            Column(modifier = Modifier.padding(dims.space20), verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                        TotalsRow(strings(Strings.sales_success_customer), state.selectedCustomer?.name ?: "—")
                        TotalsRow(strings(Strings.sales_success_items), state.cartLines.size.toString())
                        TotalsRow(strings(Strings.sales_success_total), MoneyFormat.format(state.totals.grandTotal, currency), emphasize = true)
                        TotalsRow(strings(Strings.sales_success_paid), MoneyFormat.format(state.amountPaid, currency))
                        TotalsRow(strings(Strings.sales_success_balance), MoneyFormat.format(state.balanceRemaining, currency))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = dims.space4), color = colors.border)
                InvoiceRow(
                    invoice = state.invoice,
                    isRetrying = state.isRetryingInvoice,
                    canRetry = state.canRetryInvoice,
                    retryError = state.invoiceRetryError,
                    onRetry = onRetryInvoice,
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space16)) {
                // Labelled "Done" (ticket #106) — it still calls startNewSale() (clears the cart) so
                // the previous customer's cart never carries over. Always available: a slow/failed
                // invoice must never block the cashier.
                PrimaryButton(label = strings(Strings.sales_success_done), onClick = onNewSale)
            }
        }
    }
}

/**
 * The invoice row on the Sale-complete dialog (ticket #77). Resolves in place off the live
 * [SalesUiState.invoice]: PENDING → a spinner + "preparing", ISSUED → the number + View/Print/
 * Copy link, FAILED → a reassuring message + Retry. Never implies the sale itself failed — the
 * sale and the books are already committed. On Desktop, View/Print open the PDF in the system
 * browser and Copy link puts the URL on the clipboard.
 */
@Composable
internal fun InvoiceRow(
    invoice: SaleInvoice,
    isRetrying: Boolean,
    canRetry: Boolean,
    retryError: Boolean,
    onRetry: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
        Text(strings(Strings.sales_invoice_label), style = typography.fieldLabel, color = colors.textTertiary)
        when (invoice.status) {
            SaleInvoiceStatus.PENDING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dims.space8),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.brand)
                Text(strings(Strings.sales_invoice_preparing), style = typography.body, color = colors.textSecondary)
            }

            SaleInvoiceStatus.ISSUED -> {
                val url = invoice.url
                SelectionContainer {
                    Text(
                        invoice.number ?: "—",
                        style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (url != null) {
                    // "Link copied" is momentary confirmation, not the button's new name — it has
                    // to revert, or a second copy gives no feedback at all. Keyed on a tick rather
                    // than a flag so re-clicking restarts the window instead of inheriting the
                    // first click's countdown.
                    var copyTick by remember(url) { mutableStateOf(0) }
                    LaunchedEffect(copyTick) {
                        if (copyTick > 0) {
                            delay(COPY_CONFIRM_MS)
                            copyTick = 0
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(dims.space4)) {
                        InvoiceAction(strings(Strings.sales_invoice_view)) { openInBrowser(url) }
                        InvoiceAction(strings(Strings.sales_invoice_print)) { openInBrowser(url) }
                        val copyLabel =
                            if (copyTick > 0) Strings.sales_invoice_copied else Strings.sales_invoice_copy
                        InvoiceAction(strings(copyLabel)) {
                            clipboard.setText(AnnotatedString(url))
                            copyTick++
                        }
                    }
                }
            }

            SaleInvoiceStatus.FAILED -> {
                Text(strings(Strings.sales_invoice_failed), style = typography.body, color = colors.textSecondary)
                if (isRetrying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dims.space8),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.brand)
                        Text(strings(Strings.sales_invoice_retrying), style = typography.hint, color = colors.textTertiary)
                    }
                } else {
                    if (retryError) {
                        Text(
                            strings(Strings.sales_invoice_retry_error),
                            style = typography.hint,
                            color = colors.error,
                        )
                    }
                    TextButton(
                        onClick = onRetry,
                        enabled = canRetry,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Filled.Receipt, null, tint = colors.brand, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings(Strings.sales_invoice_retry), style = typography.hint, color = colors.brand)
                    }
                }
            }
        }
    }
}

/** A compact text action for the invoice row (View / Print / Copy link). */
@Composable
private fun InvoiceAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
        Text(label, style = AromexTheme.typography.hint, color = AromexTheme.colors.brand)
    }
}

/** Open a URL in the system browser; a no-op if the platform can't (never crashes the counter). */
internal fun openInBrowser(url: String) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    }
}

/** True when [millis] falls on today's calendar day — a same-day sale needs no explanation. */
private fun isToday(millis: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = millis }
    val b = Calendar.getInstance()
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

private val longDateFormat = java.text.SimpleDateFormat("d MMMM yyyy", Locale.getDefault())
