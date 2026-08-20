package com.humblesolutions.aromex.ui.inventory

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.CommissionLine
import com.humblesolutions.aromex.model.Condition
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.UnreadableBlock
import com.humblesolutions.aromex.util.InventoryLimits
import com.humblesolutions.aromex.ui.components.AromexDialog
import com.humblesolutions.aromex.ui.components.DesktopSection
import com.humblesolutions.aromex.ui.components.FilterableDropdownField
import androidx.compose.ui.zIndex
import com.humblesolutions.aromex.ui.components.CollapsedSidebarWidth
import com.humblesolutions.aromex.ui.components.ExpandedSidebarWidth
import com.humblesolutions.aromex.ui.components.NavSidebar
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.money.DateField
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.Imei
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.MoneyFormat
import com.humblesolutions.aromex.util.SkuKey

// ── Navigation mode ───────────────────────────────────────────────────────────

private sealed interface Mode {
    data object List : Mode
    data object Add : Mode
    data class Drill(val productId: String) : Mode
}

// ── SKU label ─────────────────────────────────────────────────────────────────

private fun Product.label(): String =
    AttributeType.SKU_DEFINING
        .mapNotNull { attributes[it]?.name?.takeIf { n -> n.isNotBlank() } }
        .joinToString(" · ")
        .ifBlank { productId }

private fun Map<AttributeType, AttributeRef>.skuLabel(): String =
    AttributeType.SKU_DEFINING
        .mapNotNull { this[it]?.name?.takeIf { n -> n.isNotBlank() } }
        .joinToString(" · ")

// ── Root composable ───────────────────────────────────────────────────────────

@Composable
fun InventoryScreen(
    listVm: InventoryListViewModel,
    addVm: AddStockViewModel,
    onNavigateToEntities: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToSalesHistory: () -> Unit,
    onNavigateToMoney: () -> Unit = {},
    onNavigateToCommissionRules: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStockHistory: () -> Unit = {},
    onSignOut: () -> Unit,
) {
    val listState by listVm.uiState.collectAsState()
    val addState by addVm.uiState.collectAsState()
    val colors = AromexTheme.colors

    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    var showSignOutDialog by remember { mutableStateOf(false) }

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

    LaunchedEffect(addState.done) { if (addState.done) { mode = Mode.List; addVm.reset() } }

    if (listState.noAccess) {
        Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
            Text(strings(Strings.inventory_no_access), style = AromexTheme.typography.body, color = colors.textSecondary)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize()) {
            NavSidebar(
                expanded = isSidebarExpanded,
                width = sidebarWidth,
                modifier = Modifier.zIndex(1f),
                selectedSection = DesktopSection.INVENTORY,
                session = listState.session,
                interactionSource = sidebarSrc,
                onNavigateToEntities = onNavigateToEntities,
                onNavigateToInventory = {},
                onNavigateToSales = onNavigateToSales,
                onNavigateToSalesHistory = onNavigateToSalesHistory,
                onNavigateToMoney = onNavigateToMoney,
                onNavigateToCommissionRules = onNavigateToCommissionRules,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStockHistory = onNavigateToStockHistory,
                onSignOutRequest = { showSignOutDialog = true },
            )

            Column(Modifier.fillMaxSize().padding(start = CollapsedSidebarWidth).hoverable(contentSrc)) {
                when (val m = mode) {
                    Mode.List -> {
                        InventoryTopBar(
                            sidebarExpanded = isSidebarExpanded,
                            onExpandSidebar = { isSidebarExpanded = true },
                            canManage = listState.canManage,
                            onAddStock = { addVm.reset(); mode = Mode.Add },
                            onPaste = { addVm.reset(); addVm.openPaste(); mode = Mode.Add },
                        )
                        HorizontalDivider(color = colors.border)
                        BrowseInventoryPanel(listVm = listVm, state = listState)
                    }

                    Mode.Add -> {
                        var showDiscardDialog by remember { mutableStateOf(false) }
                        val guardedClose = {
                            if (addVm.hasUnsavedChanges) showDiscardDialog = true else mode = Mode.List
                        }
                        if (showDiscardDialog) {
                            AromexDialog(
                                title = strings(Strings.inventory_discard_title),
                                message = strings(Strings.inventory_discard_body),
                                confirmLabel = strings(Strings.inventory_discard_confirm),
                                dismissLabel = strings(Strings.inventory_discard_cancel),
                                onConfirm = { showDiscardDialog = false; mode = Mode.List },
                                onDismiss = { showDiscardDialog = false },
                                destructive = true,
                            )
                        }
                        AddInventoryTopBar(
                            sidebarExpanded = isSidebarExpanded,
                            onExpandSidebar = { isSidebarExpanded = true },
                            title = strings(Strings.inventory_add_title),
                            onClose = guardedClose,
                        )
                        HorizontalDivider(color = colors.border)
                        AddStockPanel(addVm = addVm, state = addState, existingProducts = listState.rows.map { it.product })
                    }

                    is Mode.Drill -> {
                        val product = listState.rows.firstOrNull { it.product.productId == m.productId }?.product
                        DrillTopBar(
                            sidebarExpanded = isSidebarExpanded,
                            onExpandSidebar = { isSidebarExpanded = true },
                            title = product?.label() ?: "Units",
                            canManage = listState.canManage && product != null,
                            onBack = { mode = Mode.List },
                            onAddUnits = {
                                if (product != null) {
                                    addVm.reset()
                                    addVm.startAddUnits(product.productId, product.attributes, product.defaultSellingPrice)
                                    mode = Mode.Add
                                }
                            },
                        )
                        HorizontalDivider(color = colors.border)
                        DrillPanel(
                            listVm = listVm,
                            product = product,
                            canManage = listState.canManage,
                            state = listState,
                        )
                    }
                }
            }
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

// ── Top bars ──────────────────────────────────────────────────────────────────

/** Below this content width the Paste/Add-stock buttons drop their text label (icon-only)
 *  so the row never has to squeeze the breadcrumb or push actions past the window edge. */
private val TOP_BAR_COMPACT_BREAKPOINT = 560.dp

@Composable
private fun InventoryTopBar(
    sidebarExpanded: Boolean,
    onExpandSidebar: () -> Unit,
    canManage: Boolean,
    onAddStock: () -> Unit,
    onPaste: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < TOP_BAR_COMPACT_BREAKPOINT
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.weight(1f),
            ) {
                if (!compact) {
                    Text("POS", style = AromexTheme.typography.hint, color = colors.textTertiary, maxLines = 1)
                    Icon(Icons.Filled.ExpandMore, null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                }
                // maxLines/ellipsis so this NEVER wraps letter-by-letter when the weighted
                // Row is squeezed narrow — it truncates cleanly instead.
                Text(
                    strings(Strings.entities_sidebar_inventory),
                    style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (canManage) {
                OutlinedButton(
                    onClick = onPaste,
                    modifier = Modifier.height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    contentPadding = if (compact) PaddingValues(horizontal = 12.dp) else ButtonDefaults.ContentPadding,
                ) {
                    Icon(
                        Icons.Filled.ContentPaste,
                        contentDescription = if (compact) strings(Strings.inventory_paste_button) else null,
                        modifier = Modifier.size(16.dp),
                        tint = colors.brand,
                    )
                    if (!compact) {
                        Spacer(Modifier.width(6.dp))
                        Text(strings(Strings.inventory_paste_button), style = AromexTheme.typography.button, softWrap = false, color = colors.brand)
                    }
                }
                Button(
                    onClick = onAddStock,
                    modifier = Modifier.height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                    contentPadding = if (compact) PaddingValues(horizontal = 12.dp) else ButtonDefaults.ContentPadding,
                ) {
                    if (compact) {
                        Icon(Icons.Filled.Add, contentDescription = strings(Strings.inventory_add_title), modifier = Modifier.size(18.dp))
                    } else {
                        Text(strings(Strings.inventory_add_title), style = AromexTheme.typography.button, softWrap = false)
                    }
                }
            }
            IconButton(onClick = {}) {
                Icon(Icons.Filled.Notifications, null, tint = colors.textTertiary)
            }
        }
    }
}

@Composable
private fun AddInventoryTopBar(
    sidebarExpanded: Boolean,
    onExpandSidebar: () -> Unit,
    title: String,
    onClose: () -> Unit,
) {
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
            title,
            style = AromexTheme.typography.sectionTitle,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onClose, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Icon(Icons.Filled.Close, strings(Strings.inventory_close_cd), tint = colors.textSecondary)
        }
    }
}

@Composable
private fun DrillTopBar(
    sidebarExpanded: Boolean,
    onExpandSidebar: () -> Unit,
    title: String,
    canManage: Boolean,
    onBack: () -> Unit,
    onAddUnits: () -> Unit,
) {
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
        IconButton(onClick = onBack, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Icon(Icons.Filled.Close, strings(Strings.inventory_back_cd), tint = colors.textSecondary)
        }
        Text(
            title,
            style = AromexTheme.typography.sectionTitle,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (canManage) {
            Button(
                onClick = onAddUnits,
                modifier = Modifier.height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                shape = RoundedCornerShape(dims.radiusButton),
                colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
            ) {
                Text("+ Add units", style = AromexTheme.typography.button, softWrap = false)
            }
        }
    }
}

// ── Browse inventory — reference design ─────────────────────────────────────

// Tree connector styling — one consistent stroke/corner treatment across every level.
private const val TREE_STROKE_DP = 1.2f
private const val TREE_CORNER_DP = 9f
// A branch elbow stops this far short of the chevron it points at, instead of running flush
// into it, so the connector line and the expand/collapse icon never visually collide.
private val TREE_ICON_CLEARANCE = 10.dp
// A row's own downward continuation (into its children) starts this far below its own
// chevron's vertical center, instead of at dead-center, so the line clears the icon before
// dropping down rather than visually cutting through its lower half.
private val TREE_VERTICAL_CLEARANCE = 8.dp

/**
 * Every column's width, in dp. Computed by measuring the ACTUAL text that will be shown (see
 * [rememberAutoFitColumns]) — not guessed — so a column is always wide enough for its content
 * (never ellipsizes) and never wider than it needs to be (no dead space), like a spreadsheet's
 * autofit. [brand]/[model] include their chevron/gap/accent-bar prefix; [brandHeaderStartPad]/
 * [modelHeaderStartPad] are that same prefix, reused so the header label sits directly above
 * the name text rather than above the chevron.
 */
private data class BrowseColumns(
    val brand: Dp,
    val model: Dp,
    val imei: Dp,
    val capacity: Dp,
    val color: Dp,
    val carrier: Dp,
    val cost: Dp,
    val location: Dp,
    val price: Dp,
    val condition: Dp,
    val brandHeaderStartPad: Dp,
    val modelHeaderStartPad: Dp,
)

private val LocalBrowseColumns = compositionLocalOf<BrowseColumns> {
    error("LocalBrowseColumns not provided — read it from within BrowseGroupedTable's subtree")
}

// Geometry of the chevron prefix each tree row draws before its own label — accent-bar(brand
// only)/leading-gap/icon/gap — kept in sync with Brand/ModelSectionRow's actual layout below.
private val BRAND_PREFIX = 4.dp + 3.dp + 14.dp + 4.dp
private val MODEL_PREFIX = 3.dp + 13.dp + 4.dp
// x of the trunk line drawn below each expand chevron (accent-bar/gap/half-icon), used by the
// drawBehind tree-connector code; independent of the column's total (text-driven) width.
private val BRAND_TRUNK_X = 14.dp
private val MODEL_TRUNK_X_OFFSET = 9.dp // added to the (dynamic) brand column width

/**
 * Measures the real text every cell will render (at its real style, via [TextMeasurer]) across
 * the full unfiltered dataset ([groups]) plus each column's header label, and returns each
 * column's minimum content-safe width — the content's own padding is added on top so it can
 * never truncate. Keyed off [groups] (not the filtered/searched list) so widths stay put while
 * the user types a search or clicks a location instead of jumping around every keystroke.
 */
@Composable
private fun rememberAutoFitColumns(groups: List<BrandGroup>): BrowseColumns {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val hint = AromexTheme.typography.hint

    val brandHeader = "BRAND"
    val modelHeader = "MODEL"
    val imeiHeader = strings(Strings.inventory_col_imei)
    val capHeader = strings(Strings.inventory_col_capacity)
    val colorHeader = strings(Strings.inventory_color_label)
    val carrierHeader = strings(Strings.inventory_carrier_label)
    val costHeader = strings(Strings.inventory_col_cost)
    val locationHeader = strings(Strings.inventory_col_location)
    val priceHeader = strings(Strings.inventory_col_sell_price)
    val conditionHeader = strings(Strings.inventory_col_condition)
    val newLabel = strings(Strings.inventory_condition_new)
    val usedLabel = strings(Strings.inventory_condition_used)

    return remember(groups, density, hint) {
        // Measured with the exact same base style (font family/weight-baseline) every real
        // cell renders with — a bare TextStyle() defaults to a DIFFERENT font family and
        // silently under-measures, which only shows up once a column's data is uniform enough
        // to leave no slack (that's what produced the earlier "$8…" truncation on Cost).
        val headerStyle = hint.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        val brandStyle = hint.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
        val modelStyle = hint.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        val bodyStyle = hint.copy(fontSize = 12.sp)
        val emphStyle = hint.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium)

        // A few dp of slack on top of every measurement, absorbing any residual sub-pixel
        // rounding between measured and rendered text — cheap insurance against truncation.
        val safety = 4.dp

        fun widthOf(text: String, style: TextStyle): Dp {
            val px = textMeasurer.measure(AnnotatedString(text), style).size.width
            return with(density) { px.toDp() } + safety
        }

        val allUnits = groups.flatMap { b -> b.models.flatMap { it.units } }
        fun maxOfBody(values: Collection<String>, style: TextStyle): Dp =
            values.maxOfOrNull { widthOf(it, style) } ?: 0.dp

        // Body-cell padding — kept identical to what BrowseCell/MoneyCell/ConditionCell
        // actually render, so the measured allowance matches the real layout exactly.
        val cellPad = 4.dp + 8.dp
        val moneyPad = 6.dp + 8.dp
        val conditionPad = (6.dp + 8.dp) + (6.dp + 6.dp) // outer box + inner chip background

        val dollarW = widthOf("$", bodyStyle)

        val brandTextW = maxOf(
            groups.maxOfOrNull { widthOf(it.brandName.uppercase(), brandStyle) } ?: 0.dp,
            widthOf(brandHeader, headerStyle),
        )
        val modelTextW = maxOf(
            groups.flatMap { it.models }.maxOfOrNull { widthOf(it.modelName, modelStyle) } ?: 0.dp,
            widthOf(modelHeader, headerStyle),
        )
        val imeiW = maxOf(maxOfBody(allUnits.map { it.imei }, emphStyle), widthOf(imeiHeader, headerStyle)) + cellPad
        val capW = maxOf(maxOfBody(allUnits.map { it.capacity }, bodyStyle), widthOf(capHeader, headerStyle)) + cellPad
        val colorW = maxOf(maxOfBody(allUnits.map { it.color }, bodyStyle), widthOf(colorHeader, headerStyle)) + cellPad
        val carrierW = maxOf(maxOfBody(allUnits.map { it.carrier }, bodyStyle), widthOf(carrierHeader, headerStyle)) + cellPad
        val costW = maxOf(dollarW + maxOfBody(allUnits.map { it.cost }, bodyStyle), widthOf(costHeader, headerStyle)) + moneyPad
        val priceW = maxOf(dollarW + maxOfBody(allUnits.map { it.sellingPrice }, bodyStyle), widthOf(priceHeader, headerStyle)) + moneyPad
        val locationW = maxOf(
            maxOfBody(allUnits.map { it.location.name.ifBlank { "—" } }, bodyStyle),
            widthOf(locationHeader, headerStyle),
        ) + cellPad
        val conditionW = maxOf(
            maxOf(widthOf(newLabel, bodyStyle), widthOf(usedLabel, bodyStyle)),
            widthOf(conditionHeader, headerStyle),
        ) + conditionPad

        BrowseColumns(
            brand = brandTextW + BRAND_PREFIX + 10.dp,
            model = modelTextW + MODEL_PREFIX + 10.dp,
            imei = imeiW,
            capacity = capW,
            color = colorW,
            carrier = carrierW,
            cost = costW,
            location = locationW,
            price = priceW,
            condition = conditionW,
            brandHeaderStartPad = BRAND_PREFIX,
            modelHeaderStartPad = MODEL_PREFIX,
        )
    }
}

/**
 * If the table has more room than its auto-fit columns need, grow Color/Carrier/Location
 * (the genuinely descriptive, variable-length columns) proportionally to use the leftover
 * space — so a wide window doesn't just leave a big blank gap trailing the table. Brand/Model/
 * IMEI/Capacity/Cost/Price/Condition stay at their content-measured width; stretching an ID or
 * a money cell to fill leftover space would look odd, not "graceful."
 */
private fun BrowseColumns.withSlackDistributed(availableWidth: Dp, showLocation: Boolean): BrowseColumns {
    val locationW = if (showLocation) location else 0.dp
    val total = brand + model + imei + capacity + cost + price + condition + color + carrier + locationW
    val slack = availableWidth - total
    if (slack <= 0.dp) return this
    // Split the leftover evenly across the flexible columns rather than growing each in
    // proportion to its own auto-fit width — proportional growth makes an already-wider
    // column grow even more, which reads as lumpy; an equal share keeps the row looking balanced.
    val flexCount = 2 + (if (showLocation) 1 else 0)
    val share = slack / flexCount
    return copy(
        color = color + share,
        carrier = carrier + share,
        location = if (showLocation) location + share else location,
    )
}

private fun formatSummaryMoney(value: String): String {
    val bd = value.toBigDecimalOrNull() ?: return value
    return "%,d".format(bd.toLong())
}

/**
 * Browse screen root — a compact top bar (KPIs + search + location filter chips) stacked
 * above the table, instead of a full-height 240dp side panel. This gives the table the
 * majority of the screen's WIDTH (not just height), which is what keeps Color / Carrier /
 * Location from being squeezed to nothing on a normal-sized window.
 */
@Composable
private fun BrowseInventoryPanel(
    listVm: InventoryListViewModel,
    state: InventoryListUiState,
) {
    val colors = AromexTheme.colors
    Column(Modifier.fillMaxSize()) {
        BrowseTopBar(state = state, listVm = listVm)
        HorizontalDivider(color = colors.border)
        BrowseGroupedTable(
            listVm = listVm,
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** Compact horizontal KPI + search row, then a horizontally-scrolling location filter row. */
@Composable
private fun BrowseTopBar(
    state: InventoryListUiState,
    listVm: InventoryListViewModel,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val summary = state.summary
    val locationCount = (state.locationEntries.size - 1).coerceAtLeast(0)

    Column(
        modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt)
            .padding(horizontal = dims.space16, vertical = dims.space12),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.space8),
        ) {
            KpiChip(Icons.Filled.Smartphone, Color(0xFFE879A0), "%,d".format(summary.totalUnits), "Devices")
            KpiChip(Icons.Filled.AttachMoney, Color(0xFF4CAF82), "$${formatSummaryMoney(summary.totalRetailValue)}", "Value")
            KpiChip(Icons.Filled.LocationOn, Color(0xFF5B8DEF), "$locationCount", "Locations")
            Spacer(Modifier.weight(1f))
            CompactSearchField(
                value = state.query,
                onValueChange = listVm::onQueryChange,
                modifier = Modifier.width(200.dp),
            )
        }
        Spacer(Modifier.height(dims.space8))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(state.locationEntries, key = { it.id ?: "all" }) { entry ->
                LocationChip(entry = entry, selected = entry.id == state.selectedLocationId, onClick = { listVm.selectLocation(entry.id) })
            }
        }
    }
}

/** Small icon + value + label pill — the compact, horizontal replacement for the old full-width KPI card. */
@Composable
private fun KpiChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconBg: Color,
    value: String,
    label: String,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(dims.radiusField))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(dims.radiusField))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Column {
            SelectionContainer {
                Text(
                    value,
                    style = AromexTheme.typography.body.copy(fontWeight = FontWeight.Bold, fontSize = 14.sp),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                label,
                style = AromexTheme.typography.hint.copy(fontSize = 10.sp),
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
    }
}

/** A small, self-drawn search field (not M3's OutlinedTextField) so it matches the compact KPI chips' height. */
@Composable
private fun CompactSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(dims.radiusField))
            .background(colors.surface)
            .border(1.dp, if (isFocused) colors.brand else colors.border, RoundedCornerShape(dims.radiusField))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Filled.Search, null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    strings(Strings.inventory_browse_search),
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
                modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
            )
        }
    }
}

/**
 * A location filter pill — "All" or a named location, with an in-stock count badge. Reuses the
 * Money toolbar's pill treatment (ticket #101): fixed height, `radiusPill`, and `clip()` **before**
 * `clickable()` so the hover/press highlight follows the rounded corners instead of overhanging.
 */
@Composable
private fun LocationChip(entry: LocationEntry, selected: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    val shape = RoundedCornerShape(dims.radiusPill)
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .hoverable(hoverSrc)
            .background(
                when {
                    selected -> colors.brand
                    hovered -> colors.brandTint
                    else -> colors.surface
                }
            )
            .border(1.dp, if (selected) colors.brand else colors.border, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            entry.name,
            style = AromexTheme.typography.hint.copy(
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) Color.White else colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) Color.White.copy(alpha = 0.25f) else colors.brand.copy(alpha = 0.1f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(
                "${entry.count}",
                style = AromexTheme.typography.hint.copy(fontSize = 10.sp),
                color = if (selected) Color.White else colors.brand,
            )
        }
    }
}

@Composable
private fun BrowseGroupedTable(
    listVm: InventoryListViewModel,
    state: InventoryListUiState,
    modifier: Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val showLocation = state.selectedLocationId == null

    // Track what's EXPANDED, not what's collapsed (ticket #101). Anything unseen is closed by
    // definition, so a model that first appears after a filter/location change stays collapsed
    // instead of dumping its units. Model keys are brand-prefixed ("$brand/$model") so two
    // same-named models under different brands expand independently.
    var expandedBrands by remember { mutableStateOf(setOf<String>()) }
    var expandedModels by remember { mutableStateOf(setOf<String>()) }

    fun modelKey(brandName: String, modelName: String) = "$brandName/$modelName"

    val allBrandNames = state.browseGroups.map { it.brandName }.toSet()
    val allModelKeys = state.browseGroups.flatMap { b -> b.models.map { modelKey(b.brandName, it.modelName) } }.toSet()
    val allExpanded = allBrandNames.isNotEmpty() && allBrandNames.all { it in expandedBrands }

    // Columns are measured from the FULL (unfiltered) dataset so widths stay stable while
    // searching/filtering, then any extra window width beyond what they need is distributed
    // to Color/Carrier/Location instead of sitting empty.
    val autoFitColumns = rememberAutoFitColumns(state.allBrowseGroups)

    BoxWithConstraints(modifier) {
        val columns = remember(autoFitColumns, maxWidth, showLocation) {
            autoFitColumns.withSlackDistributed(maxWidth, showLocation)
        }
        CompositionLocalProvider(LocalBrowseColumns provides columns) {
        Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.surface)
                .padding(horizontal = dims.space16, vertical = dims.space8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.space8),
        ) {
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (allExpanded) {
                        expandedBrands = emptySet()
                        expandedModels = emptySet()
                    } else {
                        expandedBrands = allBrandNames
                        expandedModels = allModelKeys
                    }
                },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Icon(
                    if (allExpanded) Icons.Filled.UnfoldLess else Icons.Filled.UnfoldMore,
                    null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.brand,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    if (allExpanded) "Collapse All" else "Expand All",
                    style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.brand,
                )
            }
        }
        state.error?.let { err ->
            Text(err, color = colors.error, style = AromexTheme.typography.hint,
                modifier = Modifier.padding(horizontal = dims.space16, vertical = 4.dp))
        }
        state.actionError?.let { err ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(err, color = colors.error, style = AromexTheme.typography.hint, modifier = Modifier.weight(1f))
                TextButton(onClick = listVm::clearActionError, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Text("Dismiss", color = colors.brand)
                }
            }
        }
        HorizontalDivider(color = colors.border)

        val totalInStock = state.locationEntries.firstOrNull { it.id == null }?.count ?: 0
        when {
            state.browseGroups.isEmpty() && totalInStock == 0 -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(dims.space12),
                    ) {
                        Icon(Icons.Filled.Inventory2, null, tint = colors.textTertiary, modifier = Modifier.size(40.dp))
                        Text(strings(Strings.inventory_browse_empty_all), color = colors.textTertiary, style = AromexTheme.typography.body)
                    }
                }
            }
            state.browseGroups.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(strings(Strings.inventory_browse_empty_location), color = colors.textTertiary, style = AromexTheme.typography.body)
                }
            }
            else -> {
                BrowseTableHeader(showLocation = showLocation)
                HorizontalDivider(color = colors.border)
                LazyColumn(Modifier.fillMaxSize()) {
                    state.browseGroups.forEachIndexed { brandIdx, brand ->
                        val brandExpanded = brand.brandName in expandedBrands
                        val isLastBrand = brandIdx == state.browseGroups.lastIndex
                        item(key = "brand_${brand.brandName}") {
                            BrandSectionRow(
                                brand = brand,
                                collapsed = !brandExpanded,
                                showLocation = showLocation,
                                onToggle = {
                                    expandedBrands = if (brandExpanded)
                                        expandedBrands - brand.brandName
                                    else
                                        expandedBrands + brand.brandName
                                },
                            )
                            HorizontalDivider(color = colors.border)
                        }
                        if (brandExpanded) {
                            brand.models.forEachIndexed { modelIdx, model ->
                                val key = modelKey(brand.brandName, model.modelName)
                                val modelExpanded = key in expandedModels
                                val isLastModel = modelIdx == brand.models.lastIndex
                                item(key = "model_$key") {
                                    ModelSectionRow(
                                        model = model,
                                        collapsed = !modelExpanded,
                                        isLastModel = isLastModel,
                                        showLocation = showLocation,
                                        onToggle = {
                                            expandedModels = if (modelExpanded)
                                                expandedModels - key
                                            else
                                                expandedModels + key
                                        },
                                    )
                                    // No divider — preserves vertical tree line continuity into IMEI rows
                                }
                                if (modelExpanded) {
                                    model.units.forEachIndexed { unitIdx, unit ->
                                        val isLastUnit = unitIdx == model.units.lastIndex
                                        item(key = "unit_${unit.serialId}") {
                                            ImeiSectionRow(
                                                unit = unit,
                                                isLastUnit = isLastUnit,
                                                isLastModel = isLastModel,
                                                showLocation = showLocation,
                                            )
                                            // Subtle separator only between sibling models
                                            if (isLastUnit && !isLastModel) {
                                                HorizontalDivider(color = colors.border.copy(alpha = 0.35f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Separator between top-level brand groups only (not after the last). A small
                        // gap + rule reads as a group break rather than doubling the header's hairline.
                        if (!isLastBrand) {
                            item(key = "brand_sep_${brand.brandName}") {
                                Spacer(Modifier.height(dims.space8))
                                HorizontalDivider(thickness = 2.dp, color = colors.border)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(dims.space20)) }
                }
            }
        }
        }
        }
    }
}

/**
 * Table header — dedicated BRAND / MODEL / IMEI columns + data columns.
 *
 * NOTE: this Row must reserve NO extra row-level padding/spacer beyond what each individual
 * cell applies internally — [BrandSectionRow] / [ModelSectionRow] / [ImeiSectionRow] mirror
 * this exact same column list (same fixed widths, same weights, no trailing spacer) so every
 * row computes an identical total column layout and every cell lines up under its heading.
 */
@Composable
private fun BrowseTableHeader(showLocation: Boolean) {
    val colors = AromexTheme.colors
    val cols = LocalBrowseColumns.current
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // BRAND/MODEL start-padding matches exactly where the row's own label text starts
        // (past its accent-bar/gap/chevron/gap prefix — see Brand/ModelSectionRow), so the
        // header word sits directly above the NAME, not above the chevron.
        BrowseHeaderCell("BRAND", Modifier.width(cols.brand), startPadding = cols.brandHeaderStartPad)
        BrowseHeaderCell("MODEL", Modifier.width(cols.model), startPadding = cols.modelHeaderStartPad)
        BrowseHeaderCell(strings(Strings.inventory_col_imei), Modifier.width(cols.imei))
        BrowseHeaderCell(strings(Strings.inventory_col_capacity), Modifier.width(cols.capacity))
        BrowseHeaderCell(strings(Strings.inventory_color_label), Modifier.width(cols.color))
        BrowseHeaderCell(strings(Strings.inventory_carrier_label), Modifier.width(cols.carrier))
        BrowseHeaderCell(strings(Strings.inventory_col_cost), Modifier.width(cols.cost), startPadding = 6.dp)
        if (showLocation) BrowseHeaderCell(strings(Strings.inventory_col_location), Modifier.width(cols.location))
        BrowseHeaderCell(strings(Strings.inventory_col_sell_price), Modifier.width(cols.price), startPadding = 6.dp)
        BrowseHeaderCell(strings(Strings.inventory_col_condition), Modifier.width(cols.condition), startPadding = 6.dp)
    }
}

@Composable
private fun BrowseHeaderCell(text: String, modifier: Modifier, startPadding: Dp = 4.dp) {
    Text(
        text,
        modifier = modifier.padding(start = startPadding, end = 4.dp),
        style = AromexTheme.typography.hint.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = AromexTheme.colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Brand row: blue accent bar in tree column, brand name in BRAND column, model count in MODEL column.
 * Draws a downward connector stub at BRAND_X when expanded to join the first model row's vertical.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrandSectionRow(
    brand: BrandGroup,
    collapsed: Boolean,
    showLocation: Boolean,
    onToggle: () -> Unit,
) {
    val colors = AromexTheme.colors
    val cols = LocalBrowseColumns.current
    val treeColor = colors.brand.copy(alpha = 0.32f)
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (hovered) colors.brand.copy(alpha = 0.11f) else colors.brand.copy(alpha = 0.07f))
            .hoverable(hoverSrc)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onToggle)
            .drawBehind {
                if (!collapsed) {
                    val x = BRAND_TRUNK_X.toPx()
                    // Starts a bit below this row's own chevron center so the line clears the
                    // icon before dropping into the first model row, instead of cutting through it.
                    drawLine(
                        treeColor,
                        Offset(x, size.height / 2f + TREE_VERTICAL_CLEARANCE.toPx()),
                        Offset(x, size.height),
                        TREE_STROKE_DP.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Blue accent bar spans full row height (direct child so fillMaxHeight works against IntrinsicSize.Min)
        Box(Modifier.width(4.dp).fillMaxHeight().background(colors.brand))
        // Brand col content: gap + chevron + uppercase name (width = cols.brand - 4dp bar).
        // Leading gap kept tight (3dp) so the label sits close to the BRAND header's left edge.
        Row(
            modifier = Modifier.width(cols.brand - 4.dp).padding(top = 12.dp, bottom = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = colors.brand,
                modifier = Modifier.size(14.dp).rotate(if (collapsed) -90f else 0f),
            )
            Spacer(Modifier.width(4.dp))
            var brandOverflowing by remember(brand.brandName) { mutableStateOf(false) }
            TooltipArea(tooltip = { if (brandOverflowing) BrowseCellTooltip(brand.brandName) }) {
                Text(
                    brand.brandName.uppercase(),
                    style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { brandOverflowing = it.hasVisualOverflow },
                )
            }
        }
        // Model col: model count hint
        Text(
            "${brand.totalModels} ${if (brand.totalModels == 1) "model" else "models"}",
            modifier = Modifier.width(cols.model).padding(start = 8.dp, end = 8.dp),
            style = AromexTheme.typography.hint.copy(fontSize = 11.sp),
            color = colors.textTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.width(cols.imei))
        Spacer(Modifier.width(cols.capacity))
        Spacer(Modifier.width(cols.color))
        Spacer(Modifier.width(cols.carrier))
        Spacer(Modifier.width(cols.cost))
        if (showLocation) Spacer(Modifier.width(cols.location))
        Spacer(Modifier.width(cols.price))
        Spacer(Modifier.width(cols.condition))
    }
}

/**
 * Model row: brand-level ├─/└─ connector + downward stub to IMEIs in tree column,
 * model name in its own MODEL column.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelSectionRow(
    model: ModelGroup,
    collapsed: Boolean,
    isLastModel: Boolean,
    showLocation: Boolean,
    onToggle: () -> Unit,
) {
    val colors = AromexTheme.colors
    val cols = LocalBrowseColumns.current
    val spineColor = colors.brand.copy(alpha = 0.32f)
    val branchColor = colors.textTertiary.copy(alpha = 0.45f)
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(if (hovered) colors.brand.copy(alpha = 0.04f) else colors.background)
            .hoverable(hoverSrc)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onToggle)
            .drawBehind {
                val bx = BRAND_TRUNK_X.toPx()
                val mx = (cols.brand + MODEL_TRUNK_X_OFFSET).toPx()
                val mid = size.height / 2f
                val strokeW = TREE_STROKE_DP.dp.toPx()
                val cornerR = TREE_CORNER_DP.dp.toPx()
                // Elbow: vertical from top to mid, rounded turn, horizontal toward the model
                // chevron — but stopping TREE_ICON_CLEARANCE short of it so the line ends
                // cleanly beside the icon instead of running underneath/into it.
                val elbowPath = Path().apply {
                    moveTo(bx, 0f)
                    lineTo(bx, mid)
                    lineTo(mx - TREE_ICON_CLEARANCE.toPx(), mid)
                }
                drawPath(
                    elbowPath, branchColor,
                    style = Stroke(
                        width = strokeW,
                        cap = StrokeCap.Round,
                        pathEffect = PathEffect.cornerPathEffect(cornerR),
                    ),
                )
                // Brand trunk continuation below mid (only when more model siblings follow)
                if (!isLastModel) {
                    drawLine(spineColor, Offset(bx, mid), Offset(bx, size.height), strokeW, cap = StrokeCap.Round)
                }
                // Model trunk downward into IMEI rows when expanded — starts below this row's
                // own chevron center so the line clears the icon instead of cutting through it.
                if (!collapsed) {
                    drawLine(
                        branchColor,
                        Offset(mx, mid + TREE_VERTICAL_CLEARANCE.toPx()),
                        Offset(mx, size.height),
                        strokeW, cap = StrokeCap.Round,
                    )
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Brand col: empty (tree lines drawn via drawBehind)
        Spacer(Modifier.width(cols.brand))
        // Model col: gap + chevron + model name. Leading gap kept tight (3dp), matching the
        // brand row, so the label sits close to the MODEL header's left edge.
        Row(
            modifier = Modifier.width(cols.model).padding(top = 10.dp, bottom = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (collapsed) "Expand" else "Collapse",
                tint = colors.textSecondary,
                modifier = Modifier.size(13.dp).rotate(if (collapsed) -90f else 0f),
            )
            Spacer(Modifier.width(4.dp))
            var modelOverflowing by remember(model.modelName) { mutableStateOf(false) }
            TooltipArea(tooltip = { if (modelOverflowing) BrowseCellTooltip(model.modelName) }) {
                Text(
                    model.modelName,
                    style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { modelOverflowing = it.hasVisualOverflow },
                )
            }
        }
        Spacer(Modifier.width(cols.imei))
        Spacer(Modifier.width(cols.capacity))
        Spacer(Modifier.width(cols.color))
        Spacer(Modifier.width(cols.carrier))
        Spacer(Modifier.width(cols.cost))
        if (showLocation) Spacer(Modifier.width(cols.location))
        Spacer(Modifier.width(cols.price))
        Spacer(Modifier.width(cols.condition))
    }
}

/**
 * IMEI row: two-level tree connector (brand │ continuation + model ├─/└─),
 * IMEI in dedicated IMEI column, data cells in remaining columns.
 */
@Composable
private fun ImeiSectionRow(
    unit: BrowseUnit,
    isLastUnit: Boolean,
    isLastModel: Boolean,
    showLocation: Boolean,
) {
    val colors = AromexTheme.colors
    val cols = LocalBrowseColumns.current
    val spineColor = colors.brand.copy(alpha = 0.22f)
    val branchColor = colors.textTertiary.copy(alpha = 0.45f)
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    // The IMEI row's cells (IMEI, capacity, colour, carrier, cost, location, price, condition)
    // are read-only data a user will plausibly copy — and the row is hover-only, not clickable —
    // so the whole data row is one selection scope. (Tree lines stay on the Row's drawBehind.)
    SelectionContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(if (hovered) colors.brand.copy(alpha = 0.03f) else colors.surface)
                .hoverable(hoverSrc)
                .drawBehind {
                    val bx = BRAND_TRUNK_X.toPx()
                    val mx = (cols.brand + MODEL_TRUNK_X_OFFSET).toPx()
                    val imeiStartX = (cols.brand + cols.model).toPx()
                    val mid = size.height / 2f
                    val strokeW = TREE_STROKE_DP.dp.toPx()
                    val cornerR = TREE_CORNER_DP.dp.toPx()
                    // Brand trunk continuation — only when more model siblings follow
                    if (!isLastModel) {
                        drawLine(spineColor, Offset(bx, 0f), Offset(bx, size.height), strokeW, cap = StrokeCap.Round)
                    }
                    // Model elbow: vertical from top to mid, rounded turn, horizontal to IMEI column
                    val elbowPath = Path().apply {
                        moveTo(mx, 0f)
                        lineTo(mx, mid)
                        lineTo(imeiStartX, mid)
                    }
                    drawPath(
                        elbowPath, branchColor,
                        style = Stroke(
                            width = strokeW,
                            cap = StrokeCap.Round,
                            pathEffect = PathEffect.cornerPathEffect(cornerR),
                        ),
                    )
                    // Model trunk continuation below mid (only when more IMEI siblings follow)
                    if (!isLastUnit) {
                        drawLine(branchColor, Offset(mx, mid), Offset(mx, size.height), strokeW, cap = StrokeCap.Round)
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(cols.brand))
            Spacer(Modifier.width(cols.model))
            BrowseCell(unit.imei, Modifier.width(cols.imei), color = colors.textPrimary, emphasize = true)
            BrowseCell(unit.capacity, Modifier.width(cols.capacity))
            BrowseCell(unit.color, Modifier.width(cols.color))
            BrowseCell(unit.carrier, Modifier.width(cols.carrier))
            MoneyCell(value = unit.cost, readOnly = true, modifier = Modifier.width(cols.cost))
            if (showLocation) BrowseCell(unit.location.name.ifBlank { "—" }, Modifier.width(cols.location))
            MoneyCell(value = unit.sellingPrice, readOnly = true, modifier = Modifier.width(cols.price))
            ConditionCell(unit.condition, readOnly = true, modifier = Modifier.width(cols.condition))
        }
    }
}

/**
 * A single left-aligned data cell. If its text is too long for the column and gets
 * ellipsized, hovering reveals the full value in a small tooltip — the ellipsis is a
 * space-saving affordance, not a dead end.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseCell(
    text: String,
    modifier: Modifier,
    color: Color = AromexTheme.colors.textSecondary,
    emphasize: Boolean = false,
) {
    var overflowing by remember(text) { mutableStateOf(false) }
    TooltipArea(
        tooltip = { if (overflowing) BrowseCellTooltip(text) },
        modifier = modifier,
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
            style = AromexTheme.typography.hint.copy(
                fontSize = 12.sp,
                fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
            ),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflowing = it.hasVisualOverflow },
        )
    }
}

/** Themed tooltip surface — inverted (bg = textPrimary) so contrast holds in both themes. */
@Composable
private fun BrowseCellTooltip(text: String) {
    val colors = AromexTheme.colors
    Box(
        modifier = Modifier
            .shadow(6.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(colors.textPrimary)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = AromexTheme.typography.hint.copy(fontSize = 12.sp),
            color = colors.background,
            maxLines = 1,
        )
    }
}

// ── Product list (legacy minimal list — kept for drill-in context) ─────────────

@Composable
private fun ProductListPanel(
    listVm: InventoryListViewModel,
    state: InventoryListUiState,
    canManage: Boolean,
    onDrill: (String) -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    Column(Modifier.fillMaxSize().padding(dims.space20)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = listVm::onQueryChange,
            placeholder = { Text("Search brand / model / IMEI…", color = colors.textTertiary) },
            singleLine = true,
            colors = fieldColors(),
            shape = RoundedCornerShape(dims.radiusField),
            modifier = Modifier.fillMaxWidth(),
        )

        state.error?.let {
            Spacer(Modifier.height(dims.space8))
            Text(it, color = colors.error, style = AromexTheme.typography.hint)
        }
        state.actionError?.let {
            Spacer(Modifier.height(dims.space8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = colors.error, style = AromexTheme.typography.hint, modifier = Modifier.weight(1f))
                TextButton(onClick = listVm::clearActionError, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Text("Dismiss", color = colors.brand)
                }
            }
        }

        Spacer(Modifier.height(dims.space16))

        if (state.rows.isEmpty()) {
            Text("No products yet.", color = colors.textTertiary, style = AromexTheme.typography.body)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                items(state.rows, key = { it.product.productId }) { row ->
                    SkuCard(
                        row = row,
                        canManage = canManage,
                        onDrill = { onDrill(row.product.productId) },
                        onEditPrice = { price -> listVm.editSellingPrice(row.product.productId, price) },
                        onArchive = { listVm.archiveProduct(row.product.productId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SkuCard(
    row: SkuRow,
    canManage: Boolean,
    onDrill: () -> Unit,
    onEditPrice: (String) -> Unit,
    onArchive: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var editingPrice by remember { mutableStateOf(false) }
    var priceText by remember(row.product.productId, row.product.defaultSellingPrice) {
        mutableStateOf(row.product.defaultSellingPrice)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onDrill),
    ) {
        Column(Modifier.padding(dims.space16)) {
            Text(row.product.label(), style = AromexTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                "${row.inStockCount} in stock · price ${row.product.defaultSellingPrice}" +
                    if (!row.product.isActive) " · ARCHIVED" else "",
                style = AromexTheme.typography.hint,
                color = colors.textTertiary,
            )
            if (canManage) {
                Spacer(Modifier.height(dims.space8))
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                    OutlinedButton(
                        onClick = { editingPrice = !editingPrice },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(dims.radiusButton),
                    ) { Text("Edit price", softWrap = false) }
                    OutlinedButton(
                        onClick = onArchive,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(dims.radiusButton),
                    ) { Text("Archive", softWrap = false) }
                }
                if (editingPrice) {
                    Spacer(Modifier.height(dims.space8))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                        OutlinedTextField(
                            value = priceText,
                            onValueChange = { priceText = it },
                            label = { Text("Selling price") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = { onEditPrice(priceText.trim()); editingPrice = false },
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.brand),
                        ) { Text("Save") }
                    }
                }
            }
        }
    }
}

// ── Drill-in: units of one SKU ────────────────────────────────────────────────

@Composable
private fun DrillPanel(
    listVm: InventoryListViewModel,
    product: Product?,
    canManage: Boolean,
    state: InventoryListUiState,
) {
    val dims = AromexTheme.dimensions
    val colors = AromexTheme.colors
    val units: List<Serial> = if (product == null) emptyList() else listVm.unitsFor(product.productId)

    Column(Modifier.fillMaxSize().padding(dims.space20)) {
        state.actionError?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(it, color = colors.error, style = AromexTheme.typography.hint, modifier = Modifier.weight(1f))
                TextButton(onClick = listVm::clearActionError, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Text("Dismiss", color = colors.brand)
                }
            }
            Spacer(Modifier.height(dims.space16))
        }
        if (units.isEmpty()) {
            Text("No in-stock units.", color = colors.textTertiary, style = AromexTheme.typography.body)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                items(units, key = { it.serialId }) { unit ->
                    UnitCard(
                        unit = unit,
                        canManage = canManage,
                        onSetStatus = { listVm.setUnitStatus(unit.serialId, it) },
                        onArchive = { listVm.archiveUnit(unit.serialId) },
                        onEdit = { cost, condition -> listVm.editUnit(unit.serialId, cost, condition) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UnitCard(
    unit: Serial,
    canManage: Boolean,
    onSetStatus: (SerialStatus) -> Unit,
    onArchive: () -> Unit,
    onEdit: (String?, Condition?) -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var editing by remember { mutableStateOf(false) }
    var confirmingDelete by remember(unit.serialId) { mutableStateOf(false) }
    var costText by remember(unit.serialId, unit.cost) { mutableStateOf(unit.cost) }
    var condition by remember(unit.serialId, unit.condition) { mutableStateOf(unit.condition) }

    if (confirmingDelete) {
        AromexDialog(
            title = strings(Strings.inventory_delete_unit_title),
            message = strings(Strings.inventory_delete_unit_body, unit.imei) + "\n\n" +
                strings(Strings.inventory_delete_unit_ledger),
            confirmLabel = strings(Strings.inventory_delete_unit_confirm),
            dismissLabel = strings(Strings.inventory_delete_unit_cancel),
            onConfirm = { confirmingDelete = false; onArchive() },
            onDismiss = { confirmingDelete = false },
            destructive = true,
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(dims.space16)) {
            SelectionContainer {
                Column {
                    Text("IMEI ${unit.imei}", style = AromexTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = colors.textPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "cost ${unit.cost} · ${unit.condition.name.lowercase()} · ${unit.status.name.lowercase()} · ${unit.location.name}",
                        style = AromexTheme.typography.hint,
                        color = colors.textTertiary,
                    )
                }
            }
            if (canManage) {
                Spacer(Modifier.height(dims.space8))
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                    OutlinedButton(
                        onClick = { onSetStatus(SerialStatus.SOLD) },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(dims.radiusButton),
                    ) { Text("Mark sold", softWrap = false) }
                    OutlinedButton(
                        // Confirmed, because it takes a phone off the shelf and frees its IMEI —
                        // and because it deliberately does NOT adjust the books, which the
                        // confirmation says out loud rather than leaving to be discovered.
                        onClick = { confirmingDelete = true },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(dims.radiusButton),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.error),
                    ) { Text(strings(Strings.inventory_delete_unit), softWrap = false) }
                    OutlinedButton(
                        onClick = { editing = !editing },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(dims.radiusButton),
                    ) { Text("Edit", softWrap = false) }
                }
                if (editing) {
                    Spacer(Modifier.height(dims.space8))
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it },
                        label = { Text("Cost") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(dims.space8))
                    ConditionToggle(condition) { condition = it }
                    Spacer(Modifier.height(dims.space8))
                    Button(
                        onClick = { onEdit(costText.trim(), condition); editing = false },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.brand),
                    ) { Text("Save unit") }
                }
            }
        }
    }
}

// ── Add-Stock staging panel (desktop table design) ───────────────────────────

@Composable
private fun AddStockPanel(
    addVm: AddStockViewModel,
    state: AddStockUiState,
    existingProducts: List<Product>,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    // SICKW paste entry screen — fully replaces the table while active.
    if (state.route == AddInventoryRoute.PASTE) {
        PasteFromSickwPanel(addVm = addVm, state = state)
        return
    }

    // Dialog visibility state
    var showAddDialog by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var showBatchCapDialog by remember { mutableStateOf(false) }

    // Error banner
    if (state.error != null) {
        val msg = when {
            state.error.startsWith("duplicate:") -> strings(Strings.inventory_save_error_duplicate)
            else -> strings(Strings.inventory_save_error_network)
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(colors.error.copy(alpha = 0.1f))
                .padding(horizontal = dims.space20, vertical = dims.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(msg, color = colors.error, style = AromexTheme.typography.hint, modifier = Modifier.weight(1f))
            TextButton(onClick = addVm::dismissError, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Text("Dismiss", color = colors.error)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 130.dp)) {
            // Parse summary banner (after a SICKW paste)
            state.parseSummary?.let { encoded -> ParseSummaryBanner(encoded, onDismiss = addVm::dismissParseSummary) }
            // "Couldn't read" list — never silently dropped
            if (state.unreadable.isNotEmpty()) {
                UnreadableList(
                    blocks = state.unreadable,
                    onDismiss = { addVm.dismissUnreadable(it) },
                    onDismissAll = addVm::dismissAllUnreadable,
                )
            }
            if (state.reviewUnits.isEmpty()) {
                // Empty state
                Box(Modifier.fillMaxWidth().heightIn(min = 300.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(dims.space16)) {
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(colors.brand.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Inventory2, null, tint = colors.brand, modifier = Modifier.size(32.dp))
                        }
                        Text(
                            "No units added yet",
                            style = AromexTheme.typography.sectionTitle,
                            color = colors.textPrimary,
                        )
                        Text(
                            "Click \"Add unit\" to enter the first device",
                            style = AromexTheme.typography.body,
                            color = colors.textSecondary,
                        )
                        Button(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                            shape = RoundedCornerShape(dims.radiusButton),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                        ) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(dims.space8))
                            Text("Add unit", style = AromexTheme.typography.button)
                        }
                    }
                }
            } else {
                // Sub-header: count + action buttons
                Row(
                    modifier = Modifier.fillMaxWidth().background(colors.surfaceAlt)
                        .padding(horizontal = dims.space20, vertical = dims.space12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dims.space8),
                ) {
                    val count = state.reviewUnits.size
                    Text(
                        if (count == 1) strings(Strings.inventory_review_unit_count_one)
                        else strings(Strings.inventory_review_unit_count).replace("{0}", count.toString()),
                        style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.Medium),
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    // SICKW paste button
                    OutlinedButton(
                        onClick = { addVm.openPaste() },
                        modifier = Modifier.height(36.dp).pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(dims.radiusButton),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.brand),
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.brand),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dims.space12, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("SICKW", style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold), softWrap = false)
                    }
                    Button(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.height(36.dp).pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(dims.radiusButton),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dims.space16, vertical = 0.dp),
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add unit", style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold), softWrap = false)
                    }
                }

                // Apply-to-all bar (cost / selling price / condition / location → fills every row)
                ApplyToAllBar(addVm = addVm, state = state)

                // Table header — flat inline-editable columns (review-table design)
                ReviewTableHeader()
                HorizontalDivider(color = colors.border)

                // One flat row per unit; each carries its full SKU inline (mixed SKUs OK).
                Column {
                    state.reviewUnits.forEachIndexed { index, unit ->
                        ReviewRow(
                            addVm = addVm,
                            state = state,
                            unit = unit,
                            originalIndex = index,
                            striped = index % 2 == 0,
                            onEdit = { editingIndex = index },
                        )
                        HorizontalDivider(color = colors.border)
                    }
                }
            }
        }

        // Sticky bottom confirm bar
        val incompleteCount = state.reviewUnits.count { !it.isComplete() }
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(colors.surface)
                .border(1.dp, colors.border)
                .padding(dims.space20),
            verticalArrangement = Arrangement.spacedBy(dims.space8),
        ) {
            if (incompleteCount > 0) {
                Text(
                    strings(Strings.inventory_status_must_fill) + " · " +
                        (if (incompleteCount == 1) strings(Strings.inventory_review_unit_count_one)
                        else strings(Strings.inventory_review_unit_count, incompleteCount)),
                    style = AromexTheme.typography.hint,
                    color = colors.warning,
                )
            }
            // Full-width PrimaryButton across the bottom bar (owner reverted the right-aligned variant).
            PrimaryButton(
                label = strings(Strings.inventory_confirm_btn),
                onClick = {
                    if (InventoryLimits.exceedsBatchCap(state.reviewUnits.size)) showBatchCapDialog = true
                    else addVm.openPurchaseDialog()
                },
                enabled = !state.saving && state.reviewUnits.isNotEmpty() && incompleteCount == 0,
                loading = state.saving,
            )
        }

        // Purchase dialog (#58) — bookkeeping capture before the inventory write commits
        if (state.showPurchaseDialog) {
            PurchaseDialog(addVm = addVm, state = state)
        }

        // Batch-size cap — block Confirm and ask to split (no chunking)
        if (showBatchCapDialog) {
            AromexDialog(
                title = strings(Strings.inventory_batch_cap_title),
                message = strings(
                    Strings.inventory_batch_cap_body,
                    state.reviewUnits.size,
                    InventoryLimits.SAFE_BATCH_CEILING,
                ),
                confirmLabel = strings(Strings.inventory_unreadable_dismiss),
                dismissLabel = strings(Strings.inventory_dialog_cancel),
                onConfirm = { showBatchCapDialog = false },
                onDismiss = { showBatchCapDialog = false },
            )
        }

        // Add unit dialog — shown over the full screen with scrim
        if (showAddDialog) {
            AddUnitDialog(
                addVm = addVm,
                state = state,
                existingProducts = existingProducts,
                onDismiss = { showAddDialog = false },
                onSave = { units ->
                    units.forEach { addVm.addReviewUnit(it) }
                    showAddDialog = false
                },
            )
        }

        // Edit unit dialog
        val editIdx = editingIndex
        if (editIdx != null && editIdx in state.reviewUnits.indices) {
            EditUnitDialog(
                unit = state.reviewUnits[editIdx],
                unitIndex = editIdx,
                addVm = addVm,
                state = state,
                onSave = { updated ->
                    addVm.editReviewUnit(editIdx, updated)
                    editingIndex = null
                },
                onDismiss = { editingIndex = null },
            )
        }
    }
}

// ── Purchase + Add Unit dialogs ──────────────────────────────────────────────

/**
 * Purchase dialog (#58): capture who the batch was bought from and what was paid on the
 * spot, before the inventory write commits. All fields are optional with safe defaults
 * (Unspecified Supplier, nothing paid); confirming — or dismissing via Escape/click-away —
 * is the valid "skip" path and never cancels the underlying inventory save.
 */
@Composable
private fun PurchaseDialog(addVm: AddStockViewModel, state: AddStockUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val symbol = MoneyFormat.symbolOf(state.currency)
    val scrimColor = colors.background.copy(alpha = 0.75f)

    // Escape / click-away behaves identically to confirming at all-defaults.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                    addVm.dismissPurchaseDialog(); true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { addVm.dismissPurchaseDialog() },
        contentAlignment = Alignment.Center,
    ) {
        // Cap the card to the window so a short window scrolls the body instead of clipping.
        val maxCardHeight = maxHeight * 0.94f
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .heightIn(max = maxCardHeight)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface)
                // Swallow clicks on the card so they don't dismiss via the scrim.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {},
        ) {
            // ── Blue gradient header (matches the entity/add-unit dialogs)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                    .padding(dims.space20),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.AttachMoney, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(dims.space12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            strings(Strings.inventory_purchase_eyebrow),
                            style = typography.fieldLabel,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Text(
                            strings(Strings.inventory_purchase_title),
                            style = typography.sectionTitle,
                            color = Color.White,
                        )
                    }
                    IconButton(
                        onClick = { addVm.dismissPurchaseDialog() },
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Filled.Close, strings(Strings.inventory_close_cd), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            // ── Body (scrolls between the pinned header + footer when the window is short)
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(dims.space20),
                verticalArrangement = Arrangement.spacedBy(dims.space16),
            ) {
                // Supplier / purchase block — labelled so it's clearly distinct from the
                // Commission block below (which pays a different party for bringing stock in).
                Text(
                    strings(Strings.commission_section_supplier),
                    style = typography.fieldLabel,
                    color = colors.textTertiary,
                )
                Text(
                    strings(Strings.inventory_purchase_total, MoneyFormat.format(state.batchTotalCost, state.currency)),
                    style = typography.hint,
                    color = colors.textTertiary,
                )

                FilterableDropdownField(
                    label = strings(Strings.inventory_purchase_bought_from),
                    items = addVm.purchasePartyOptions(),
                    selectedItem = state.purchaseParty,
                    onItemSelected = { addVm.setPurchaseParty(it) },
                    onAddNew = if (addVm.canAddSupplierInline()) { { addVm.addNewSupplier(it) } } else null,
                    placeholder = strings(Strings.inventory_purchase_bought_from_hint),
                    modifier = Modifier.fillMaxWidth(),
                )

                // The business date. Today by default; the picker refuses future dates. Backdating
                // is what lets an old purchase land on the supplier's balance in the right month.
                DateField(
                    label = strings(Strings.purchase_date_label),
                    millis = state.purchaseDate,
                    onPick = addVm::setPurchaseDate,
                )
                if (!isSameDayAsToday(state.purchaseDate)) {
                    Text(
                        strings(
                            Strings.purchase_date_backdated,
                            purchaseDateFormat.format(java.util.Date(state.purchaseDate)),
                        ),
                        style = typography.hint,
                        color = colors.warning,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.inventory_purchase_cash, symbol), style = typography.fieldLabel, color = colors.textTertiary)
                        OutlinedTextField(
                            value = state.purchaseCash,
                            onValueChange = addVm::setPurchaseCash,
                            singleLine = true,
                            placeholder = { Text("0", color = colors.textTertiary) },
                            colors = fieldColors(),
                            shape = RoundedCornerShape(dims.radiusField),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.inventory_purchase_bank, symbol), style = typography.fieldLabel, color = colors.textTertiary)
                        OutlinedTextField(
                            value = state.purchaseBank,
                            onValueChange = addVm::setPurchaseBank,
                            singleLine = true,
                            isError = state.purchasePaidExceedsTotal,
                            placeholder = { Text("0", color = colors.textTertiary) },
                            colors = fieldColors(),
                            shape = RoundedCornerShape(dims.radiusField),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (state.purchasePaidExceedsTotal) {
                    Text(
                        strings(Strings.inventory_purchase_exceeds, MoneyFormat.format(state.batchTotalCost, state.currency)),
                        style = typography.hint,
                        color = colors.error,
                    )
                }

                // Commission on intake (#97) — only shown when a rule matched this batch.
                if (state.commissionLines.isNotEmpty()) {
                    CommissionSection(addVm = addVm, state = state)
                }
            }

            // ── Pinned footer
            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space16),
            ) {
                Button(
                    onClick = { addVm.confirmPurchaseAndSave() },
                    enabled = !state.purchasePaidExceedsTotal && !state.commissionGiveExceeds && !state.saving,
                    modifier = Modifier.fillMaxWidth().height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(strings(Strings.inventory_purchase_confirm), style = typography.button, softWrap = false)
                    }
                }
            }
        }
    }
}

/**
 * Commission on intake (#97). One block per payee that a rule proposed for this batch, each
 * decided separately: accrue ("add to what I owe") or pay now, its amount editable (and marked
 * Edited when changed), or skipped entirely. Every block shows *how the figure was reached*
 * (`12 × $5.00`, `2% of $14,400.00`) — never a bare total. Reuses the money-screen field shell
 * and theme tokens; no new dropdown (Cash/Bank and accrue/pay-now are two-way toggles).
 */
@Composable
private fun CommissionSection(addVm: AddStockViewModel, state: AddStockUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val symbol = MoneyFormat.symbolOf(state.currency)

    Column(verticalArrangement = Arrangement.spacedBy(dims.space12)) {
        HorizontalDivider(color = colors.border)
        Text(
            strings(Strings.commission_section_title),
            style = typography.fieldLabel,
            color = colors.textTertiary,
        )
        state.commissionLines.forEach { line ->
            val decision = state.commissionDecisions[line.ruleId] ?: return@forEach
            CommissionLineBlock(addVm, line, decision, state.currency, symbol)
        }
    }
}

@Composable
private fun CommissionLineBlock(
    addVm: AddStockViewModel,
    line: CommissionLine,
    decision: CommissionDecision,
    currency: String,
    symbol: String,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    val payee = addVm.payeeName(line.payeeEntityId)
    val location = addVm.locationName(line.locationAttributeId)
    val reach = when (line.rateKind) {
        RateKind.PER_UNIT -> strings(
            Strings.commission_reach_per_unit,
            payee,
            line.unitCount.toString(),
            location,
            MoneyFormat.format(line.rate, currency),
        )
        RateKind.PERCENT_OF_COST -> strings(
            Strings.commission_reach_percent,
            payee,
            percentLabel(line.rate),
            MoneyFormat.format(line.basisAmount, currency),
            location,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dims.radiusField))
            .background(colors.surfaceAlt)
            .padding(dims.space12),
        verticalArrangement = Arrangement.spacedBy(dims.space8),
    ) {
        // Reach + computed total
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(reach, style = typography.hint, color = colors.textSecondary, modifier = Modifier.weight(1f))
            Text(
                MoneyFormat.format(line.amount, currency),
                style = typography.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
            )
        }

        if (!decision.included) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    strings(Strings.commission_skipped),
                    style = typography.hint,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { addVm.setCommissionIncluded(line.ruleId, true) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) { Text(strings(Strings.commission_undo_skip), color = colors.brand) }
            }
            return@Column
        }

        // Add to balance / Give now toggle
        Row(horizontalArrangement = Arrangement.spacedBy(dims.space8), verticalAlignment = Alignment.CenterVertically) {
            CommissionToggle(
                label = strings(Strings.commission_accrue),
                selected = !decision.giveNow,
                onClick = { addVm.setCommissionGiveNow(line.ruleId, false) },
            )
            CommissionToggle(
                label = strings(Strings.commission_pay_now),
                selected = decision.giveNow,
                onClick = { addVm.setCommissionGiveNow(line.ruleId, true) },
            )
        }

        // Editable amount owed + Edited badge + Skip
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                    Text(strings(Strings.commission_amount_label, symbol), style = typography.fieldLabel, color = colors.textTertiary)
                    if (decision.overridden) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.brand.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                strings(Strings.commission_overridden),
                                style = typography.hint.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = colors.brand,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = decision.amount,
                    onValueChange = { addVm.setCommissionAmount(line.ruleId, it) },
                    singleLine = true,
                    placeholder = { Text("0", color = colors.textTertiary) },
                    colors = fieldColors(),
                    shape = RoundedCornerShape(dims.radiusField),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(
                onClick = { addVm.setCommissionIncluded(line.ruleId, false) },
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) { Text(strings(Strings.commission_skip), color = colors.error) }
        }

        // Give-now split: cash + bank fields, the amount to give above (like the supplier UI),
        // and the running sum below.
        if (decision.giveNow) {
            Text(
                strings(Strings.commission_give_owed, MoneyFormat.format(decision.amount.ifBlank { "0" }, currency)),
                style = typography.hint,
                color = colors.textTertiary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings(Strings.commission_cash_field, symbol), style = typography.fieldLabel, color = colors.textTertiary)
                    OutlinedTextField(
                        value = decision.cash,
                        onValueChange = { addVm.setCommissionCash(line.ruleId, it) },
                        singleLine = true,
                        placeholder = { Text("0", color = colors.textTertiary) },
                        colors = fieldColors(),
                        shape = RoundedCornerShape(dims.radiusField),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings(Strings.commission_bank_field, symbol), style = typography.fieldLabel, color = colors.textTertiary)
                    OutlinedTextField(
                        value = decision.bank,
                        onValueChange = { addVm.setCommissionBank(line.ruleId, it) },
                        singleLine = true,
                        isError = decision.giveExceedsAmount,
                        placeholder = { Text("0", color = colors.textTertiary) },
                        colors = fieldColors(),
                        shape = RoundedCornerShape(dims.radiusField),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (decision.giveExceedsAmount) {
                Text(
                    strings(Strings.commission_give_exceeds, MoneyFormat.format(decision.amount.ifBlank { "0" }, currency)),
                    style = typography.hint,
                    color = colors.error,
                )
            } else {
                Text(
                    strings(Strings.commission_giving_now, MoneyFormat.format(decision.givenNow, currency)) +
                        "  ·  " +
                        strings(Strings.commission_left_on_balance, MoneyFormat.format(decision.leftOnBalance, currency)),
                    style = typography.hint,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** A small two-way pill toggle (accrue/pay-now, Cash/Bank) — not a dropdown, per the DoD. */
@Composable
internal fun CommissionToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(dims.radiusField))
            .background(if (selected) colors.brand.copy(alpha = 0.14f) else colors.surface)
            .border(
                width = 1.dp,
                color = if (selected) colors.brand else colors.border,
                shape = RoundedCornerShape(dims.radiusField),
            )
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = dims.space12, vertical = 6.dp),
    ) {
        Text(
            label,
            style = AromexTheme.typography.hint.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (selected) colors.brand else colors.textSecondary,
        )
    }
}

/** Fraction string → percent label, e.g. "0.02" → "2%", "0.025" → "2.5%". Pure string maths. */
internal fun percentLabel(fraction: String): String {
    val pct = Money.multiplyRate(fraction, "100")
    val trimmed = if (pct.contains('.')) pct.trimEnd('0').trimEnd('.') else pct
    return "$trimmed%"
}

@Composable
private fun AddUnitDialog(
    addVm: AddStockViewModel,
    state: AddStockUiState,
    existingProducts: List<Product>,
    onDismiss: () -> Unit,
    onSave: (List<ReviewUnit>) -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val dialogScope = rememberCoroutineScope()

    // Auto-fill selling price when picked SKU matches an existing product
    LaunchedEffect(state.picked) {
        if (state.attributesLocked) return@LaunchedEffect
        if (!AttributeType.SKU_DEFINING.all { state.picked.containsKey(it) }) return@LaunchedEffect
        val key = runCatching { SkuKey.build(state.picked) }.getOrNull() ?: return@LaunchedEffect
        val match = existingProducts.firstOrNull { it.productId == key }
        if (match != null) addVm.setPrice(match.defaultSellingPrice)
    }

    // Batch-shared unit fields
    var cost by remember { mutableStateOf("") }
    var condition by remember { mutableStateOf(Condition.NEW) }

    // Local multi-IMEI batch state (independent from ViewModel's stagedImeis so the
    // dialog is self-contained and clears cleanly on dismiss without touching global state)
    var pendingImei by remember { mutableStateOf("") }
    var stagedImeis by remember { mutableStateOf(listOf<String>()) }
    var imeiCheckState by remember { mutableStateOf(ImeiCheckState.IDLE) }

    val locationPicked = state.batchLocation
    // Only brand + model define identity and are required (ticket #101); capacity/colour/carrier
    // are optional. Cost must be a positive decimal — blank/zero would understate the asset and
    // report the whole sale price as profit (it becomes COGS on sale).
    val skuComplete = state.picked[AttributeType.BRAND]?.attributeId?.isNotBlank() == true &&
        state.picked[AttributeType.MODEL]?.attributeId?.isNotBlank() == true
    val costValid = Money.isValidPositiveDecimal(cost.trim())
    val checking = imeiCheckState == ImeiCheckState.CHECKING
    val canAddImei = pendingImei.isNotBlank() && !checking
    val canSave = stagedImeis.isNotEmpty() && costValid && locationPicked != null && skuComplete

    val errInvalid = strings(Strings.inventory_imei_error_invalid)
    val errInBatch = strings(Strings.inventory_imei_error_in_batch)
    val errInStock = strings(Strings.inventory_imei_error_in_stock)

    // Already-staged IMEIs in the review table (dedup across sessions)
    val alreadyReviewedImeis = remember(state.reviewUnits) { state.reviewUnits.map { it.imei }.toSet() }

    fun checkAndStageImei() {
        val trimmed = pendingImei.trim()
        if (trimmed.isEmpty()) return
        imeiCheckState = ImeiCheckState.IDLE
        val batchSet = stagedImeis.toSet() + alreadyReviewedImeis
        dialogScope.launch {
            imeiCheckState = ImeiCheckState.CHECKING
            val result = addVm.checkImeiAvailability(trimmed, batchSet)
            if (result == ImeiCheckState.AVAILABLE) {
                stagedImeis = stagedImeis + trimmed
                pendingImei = ""
                imeiCheckState = ImeiCheckState.IDLE
            } else {
                imeiCheckState = result
            }
        }
    }

    fun saveAll() {
        val loc = locationPicked ?: return
        onSave(stagedImeis.map { imei ->
            ReviewUnit(
                imei = imei,
                cost = cost.trim(),
                condition = condition,
                location = loc,
                attributes = state.picked,
                sellingPrice = state.defaultSellingPrice,
            )
        })
    }

    val scrimColor = colors.background.copy(alpha = 0.75f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Escape && event.type == KeyEventType.KeyDown -> { onDismiss(); true }
                    // Enter stages the pending IMEI (same behaviour as the ✓ button)
                    event.key == Key.Enter && event.type == KeyEventType.KeyDown && canAddImei -> {
                        checkAndStageImei(); true
                    }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 660.dp)
                .heightIn(max = 780.dp)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface),
        ) {
            // ── Blue gradient header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                    .padding(dims.space20),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Inventory2, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(dims.space12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "ADD UNITS",
                            style = AromexTheme.typography.fieldLabel,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Text(
                            strings(Strings.inventory_add_title),
                            style = AromexTheme.typography.sectionTitle,
                            color = Color.White,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.Filled.Close, strings(Strings.inventory_close_cd), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            // ── Scrollable body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(dims.space20),
                verticalArrangement = Arrangement.spacedBy(dims.space16),
            ) {
                // PHONE / SKU section
                DialogSectionHeader("PHONE")
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_brand_label),
                        items = addVm.options(AttributeType.BRAND),
                        selectedItem = state.picked[AttributeType.BRAND],
                        onItemSelected = { addVm.pick(AttributeType.BRAND, it) },
                        onClear = { addVm.clearPick(AttributeType.BRAND) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.BRAND, it) },
                        placeholder = strings(Strings.inventory_brand_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    FilterableDropdownField(
                        label = strings(Strings.inventory_model_label),
                        items = addVm.options(AttributeType.MODEL),
                        selectedItem = state.picked[AttributeType.MODEL],
                        onItemSelected = { addVm.pick(AttributeType.MODEL, it) },
                        onClear = { addVm.clearPick(AttributeType.MODEL) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.MODEL, it) },
                        enabled = state.picked[AttributeType.BRAND] != null,
                        placeholder = if (state.picked[AttributeType.BRAND] == null)
                            strings(Strings.inventory_model_hint_brand)
                        else strings(Strings.inventory_model_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_capacity_label),
                        items = addVm.options(AttributeType.CAPACITY),
                        selectedItem = state.picked[AttributeType.CAPACITY],
                        onItemSelected = { addVm.pick(AttributeType.CAPACITY, it) },
                        onClear = { addVm.clearPick(AttributeType.CAPACITY) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.CAPACITY, it) },
                        placeholder = strings(Strings.inventory_capacity_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    FilterableDropdownField(
                        label = strings(Strings.inventory_color_label),
                        items = addVm.options(AttributeType.COLOR),
                        selectedItem = state.picked[AttributeType.COLOR],
                        onItemSelected = { addVm.pick(AttributeType.COLOR, it) },
                        onClear = { addVm.clearPick(AttributeType.COLOR) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.COLOR, it) },
                        placeholder = strings(Strings.inventory_color_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_carrier_label),
                        items = addVm.options(AttributeType.CARRIER),
                        selectedItem = state.picked[AttributeType.CARRIER],
                        onItemSelected = { addVm.pick(AttributeType.CARRIER, it) },
                        onClear = { addVm.clearPick(AttributeType.CARRIER) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.CARRIER, it) },
                        placeholder = strings(Strings.inventory_carrier_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.inventory_selling_price_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                        OutlinedTextField(
                            value = state.defaultSellingPrice,
                            onValueChange = addVm::setPrice,
                            singleLine = true,
                            placeholder = { Text(strings(Strings.inventory_selling_price_placeholder), color = colors.textTertiary) },
                            colors = fieldColors(),
                            shape = RoundedCornerShape(dims.radiusField),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // UNIT DETAILS section
                DialogSectionHeader("UNIT DETAILS")
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.inventory_cost_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                        OutlinedTextField(
                            value = cost,
                            onValueChange = { cost = it },
                            singleLine = true,
                            isError = cost.isNotBlank() && !costValid,
                            placeholder = { Text(strings(Strings.inventory_cost_placeholder), color = colors.textTertiary) },
                            colors = fieldColors(),
                            shape = RoundedCornerShape(dims.radiusField),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (cost.isNotBlank() && !costValid) {
                            Text(
                                strings(Strings.inventory_cost_required),
                                style = AromexTheme.typography.hint,
                                color = colors.error,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.inventory_condition_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                        ConditionToggle(condition) { condition = it }
                    }
                }

                FilterableDropdownField(
                    label = strings(Strings.inventory_location_label),
                    items = addVm.options(AttributeType.LOCATION),
                    selectedItem = state.batchLocation,
                    onItemSelected = { addVm.setBatchLocation(it) },
                    onClear = { addVm.clearBatchLocation() },
                    onAddNew = { addVm.addNewAttribute(AttributeType.LOCATION, it) },
                    placeholder = strings(Strings.inventory_location_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )

                // IMEI BATCH section
                DialogSectionHeader("IMEI")

                // Input row: text field + ✓ button
                val imeiIsError = imeiCheckState in listOf(
                    ImeiCheckState.INVALID, ImeiCheckState.ALREADY_IN_BATCH, ImeiCheckState.ALREADY_IN_STOCK,
                )
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(dims.space8),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = pendingImei,
                            onValueChange = { pendingImei = it; imeiCheckState = ImeiCheckState.IDLE },
                            singleLine = true,
                            isError = imeiIsError,
                            placeholder = { Text(strings(Strings.inventory_imei_placeholder), color = colors.textTertiary) },
                            colors = fieldColors(),
                            shape = RoundedCornerShape(dims.radiusField),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        when (imeiCheckState) {
                            ImeiCheckState.CHECKING -> Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.brand)
                                Text(strings(Strings.inventory_imei_checking), style = AromexTheme.typography.hint, color = colors.textTertiary)
                            }
                            ImeiCheckState.INVALID -> Text(errInvalid, style = AromexTheme.typography.hint, color = colors.error)
                            ImeiCheckState.ALREADY_IN_BATCH -> Text(errInBatch, style = AromexTheme.typography.hint, color = colors.error)
                            ImeiCheckState.ALREADY_IN_STOCK -> Text(errInStock, style = AromexTheme.typography.hint, color = colors.error)
                            else -> {}
                        }
                    }
                    // ✓ button — same height as the text field
                    Button(
                        onClick = { checkAndStageImei() },
                        enabled = canAddImei,
                        modifier = Modifier
                            .height(dims.fieldHeight)
                            .widthIn(min = 56.dp)
                            .pointerHoverIcon(PointerIcon.Hand),
                        shape = RoundedCornerShape(dims.radiusField),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                    ) {
                        if (checking) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Filled.Check, contentDescription = "Add IMEI", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // Staged IMEI list
                if (stagedImeis.isNotEmpty()) {
                    val imeiCount = stagedImeis.size
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.border, RoundedCornerShape(dims.radiusField))
                            .clip(RoundedCornerShape(dims.radiusField)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.brand.copy(alpha = 0.07f))
                                .padding(horizontal = dims.space16, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$imeiCount ${if (imeiCount == 1) "IMEI staged" else "IMEIs staged"}",
                                style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.brand,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        stagedImeis.forEachIndexed { index, imei ->
                            if (index > 0) HorizontalDivider(color = colors.border)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (index % 2 == 0) colors.surface else colors.background)
                                    .padding(start = dims.space16, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        imei,
                                        style = AromexTheme.typography.body,
                                        color = colors.textPrimary,
                                    )
                                }
                                IconButton(
                                    onClick = { stagedImeis = stagedImeis - imei },
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).size(32.dp),
                                ) {
                                    Icon(Icons.Default.Close, "Remove IMEI", tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
            }

            // ── Footer
            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(dims.space20),
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                ) {
                    Text(strings(Strings.inventory_dialog_cancel), style = AromexTheme.typography.button)
                }
                Button(
                    onClick = { saveAll() },
                    enabled = canSave,
                    modifier = Modifier.weight(2f).height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(dims.space8))
                    val n = stagedImeis.size
                    Text(
                        if (n <= 1) strings(Strings.inventory_dialog_save)
                        else "Add $n units",
                        style = AromexTheme.typography.button,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

// ── Edit Unit dialog (entity-style, all fields) ───────────────────────────────

@Composable
private fun EditUnitDialog(
    unit: ReviewUnit,
    unitIndex: Int,
    addVm: AddStockViewModel,
    state: AddStockUiState,
    onSave: (ReviewUnit) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val dialogScope = rememberCoroutineScope()

    var imei by remember(unit.imei) { mutableStateOf(unit.imei) }
    var cost by remember(unit.imei) { mutableStateOf(unit.cost) }
    var condition by remember(unit.imei) { mutableStateOf(unit.condition) }
    var location by remember(unit.imei) { mutableStateOf<AttributeRef?>(unit.location) }
    var imeiError by remember { mutableStateOf<String?>(null) }
    var checkState by remember { mutableStateOf(ImeiCheckState.IDLE) }

    // Pre-load this unit's own SKU into state.picked so the dropdowns reflect the right values
    LaunchedEffect(unit.imei) {
        addVm.setPickedFromUnit(unit.attributes, unit.sellingPrice)
    }

    // Exclude the current unit's own IMEI from dedup (it's allowed to keep the same IMEI)
    val otherImeis = remember(state.reviewUnits, unitIndex) {
        state.reviewUnits.mapIndexedNotNull { i, u -> if (i != unitIndex) u.imei else null }.toSet()
    }
    val checking = checkState == ImeiCheckState.CHECKING
    val costValid = Money.isValidPositiveDecimal(cost.trim())
    val canSave = imei.isNotBlank() && costValid && location != null && !checking

    val errInvalid = strings(Strings.inventory_imei_error_invalid)
    val errInBatch = strings(Strings.inventory_imei_error_in_batch)
    val errInStock = strings(Strings.inventory_imei_error_in_stock)

    // Auto-save when check resolves to AVAILABLE — snapshot current picked into the unit
    LaunchedEffect(checkState) {
        if (checkState == ImeiCheckState.AVAILABLE) {
            val loc = location ?: return@LaunchedEffect
            onSave(
                unit.copy(
                    imei = imei.trim(),
                    cost = cost.trim(),
                    condition = condition,
                    location = loc,
                    attributes = state.picked,
                    sellingPrice = state.defaultSellingPrice,
                )
            )
        }
    }

    fun attemptSave() {
        val trimmed = imei.trim()
        val loc = location ?: return
        imeiError = null
        if (trimmed == unit.imei) {
            // IMEI unchanged — skip the in-stock check, save directly with current picked snapshot
            onSave(
                unit.copy(
                    cost = cost.trim(),
                    condition = condition,
                    location = loc,
                    attributes = state.picked,
                    sellingPrice = state.defaultSellingPrice,
                )
            )
        } else {
            checkState = ImeiCheckState.IDLE
            dialogScope.launch {
                checkState = ImeiCheckState.CHECKING
                val result = addVm.checkImeiAvailability(trimmed, otherImeis)
                checkState = result
                imeiError = when (result) {
                    ImeiCheckState.INVALID -> errInvalid
                    ImeiCheckState.ALREADY_IN_BATCH -> errInBatch
                    ImeiCheckState.ALREADY_IN_STOCK -> errInStock
                    else -> null
                }
            }
        }
    }

    val scrimColor = colors.background.copy(alpha = 0.75f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Escape && event.type == KeyEventType.KeyDown -> { onDismiss(); true }
                    event.key == Key.Enter && event.type == KeyEventType.KeyDown && canSave -> { attemptSave(); true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 660.dp)
                .heightIn(max = 720.dp)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface),
        ) {
            // ── Blue gradient header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                    .padding(dims.space20),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(dims.space12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "EDIT UNIT",
                            style = AromexTheme.typography.fieldLabel,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Text(
                            strings(Strings.inventory_edit_unit_title),
                            style = AromexTheme.typography.sectionTitle,
                            color = Color.White,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                        Icon(Icons.Filled.Close, strings(Strings.inventory_close_cd), tint = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            // ── Scrollable body
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(dims.space20),
                verticalArrangement = Arrangement.spacedBy(dims.space16),
            ) {
                // PHONE / SKU section (editable — changes apply to the whole batch)
                DialogSectionHeader("PHONE")
                // Brand + Model
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_brand_label),
                        items = addVm.options(AttributeType.BRAND),
                        selectedItem = state.picked[AttributeType.BRAND],
                        onItemSelected = { addVm.pick(AttributeType.BRAND, it) },
                        onClear = { addVm.clearPick(AttributeType.BRAND) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.BRAND, it) },
                        placeholder = strings(Strings.inventory_brand_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    FilterableDropdownField(
                        label = strings(Strings.inventory_model_label),
                        items = addVm.options(AttributeType.MODEL),
                        selectedItem = state.picked[AttributeType.MODEL],
                        onItemSelected = { addVm.pick(AttributeType.MODEL, it) },
                        onClear = { addVm.clearPick(AttributeType.MODEL) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.MODEL, it) },
                        enabled = state.picked[AttributeType.BRAND] != null,
                        placeholder = if (state.picked[AttributeType.BRAND] == null)
                            strings(Strings.inventory_model_hint_brand)
                        else strings(Strings.inventory_model_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                }
                // Capacity + Color
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_capacity_label),
                        items = addVm.options(AttributeType.CAPACITY),
                        selectedItem = state.picked[AttributeType.CAPACITY],
                        onItemSelected = { addVm.pick(AttributeType.CAPACITY, it) },
                        onClear = { addVm.clearPick(AttributeType.CAPACITY) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.CAPACITY, it) },
                        placeholder = strings(Strings.inventory_capacity_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    FilterableDropdownField(
                        label = strings(Strings.inventory_color_label),
                        items = addVm.options(AttributeType.COLOR),
                        selectedItem = state.picked[AttributeType.COLOR],
                        onItemSelected = { addVm.pick(AttributeType.COLOR, it) },
                        onClear = { addVm.clearPick(AttributeType.COLOR) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.COLOR, it) },
                        placeholder = strings(Strings.inventory_color_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                }
                // Carrier + Selling Price
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                    FilterableDropdownField(
                        label = strings(Strings.inventory_carrier_label),
                        items = addVm.options(AttributeType.CARRIER),
                        selectedItem = state.picked[AttributeType.CARRIER],
                        onItemSelected = { addVm.pick(AttributeType.CARRIER, it) },
                        onClear = { addVm.clearPick(AttributeType.CARRIER) },
                        onAddNew = { addVm.addNewAttribute(AttributeType.CARRIER, it) },
                        placeholder = strings(Strings.inventory_carrier_placeholder),
                        modifier = Modifier.weight(1f),
                    )
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.inventory_selling_price_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                        OutlinedTextField(
                            value = state.defaultSellingPrice,
                            onValueChange = addVm::setPrice,
                            singleLine = true,
                            placeholder = { Text(strings(Strings.inventory_selling_price_placeholder), color = colors.textTertiary) },
                            colors = fieldColors(),
                            shape = RoundedCornerShape(dims.radiusField),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // IMEI
                DialogSectionHeader("IMEI")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = imei,
                        onValueChange = { imei = it; imeiError = null; checkState = ImeiCheckState.IDLE },
                        singleLine = true,
                        isError = imeiError != null,
                        placeholder = { Text(strings(Strings.inventory_imei_placeholder), color = colors.textTertiary) },
                        colors = fieldColors(),
                        shape = RoundedCornerShape(dims.radiusField),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (imeiError != null) {
                        Text(imeiError!!, style = AromexTheme.typography.hint, color = colors.error)
                    }
                }

                // UNIT DETAILS section
                DialogSectionHeader("UNIT DETAILS")
                // Cost + Condition
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space16), verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.inventory_cost_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                        OutlinedTextField(
                            value = cost,
                            onValueChange = { cost = it },
                            singleLine = true,
                            isError = cost.isNotBlank() && !costValid,
                            placeholder = { Text(strings(Strings.inventory_cost_placeholder), color = colors.textTertiary) },
                            colors = fieldColors(),
                            shape = RoundedCornerShape(dims.radiusField),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (cost.isNotBlank() && !costValid) {
                            Text(
                                strings(Strings.inventory_cost_required),
                                style = AromexTheme.typography.hint,
                                color = colors.error,
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.inventory_condition_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                        ConditionToggle(condition) { condition = it }
                    }
                }

                // Location
                FilterableDropdownField(
                    label = strings(Strings.inventory_location_label),
                    items = addVm.options(AttributeType.LOCATION),
                    selectedItem = location,
                    onItemSelected = { location = AttributeRef(it.attributeId, it.name) },
                    onClear = { location = null },
                    onAddNew = { addVm.addNewAttribute(AttributeType.LOCATION, it) },
                    placeholder = strings(Strings.inventory_location_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(4.dp))
            }

            // ── Footer
            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(dims.space20),
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                ) {
                    Text(strings(Strings.inventory_dialog_cancel), style = AromexTheme.typography.button)
                }
                Button(
                    onClick = { attemptSave() },
                    enabled = canSave,
                    modifier = Modifier.weight(2f).height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    if (checking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(dims.space8))
                        Text(strings(Strings.inventory_dialog_save), style = AromexTheme.typography.button, softWrap = false)
                    }
                }
            }
        }
    }
}

// ── SICKW paste entry screen ──────────────────────────────────────────────────

@Composable
private fun PasteFromSickwPanel(
    addVm: AddStockViewModel,
    state: AddStockUiState,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    val canParse = state.pasteText.isNotBlank() && !state.parsing

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                when {
                    // Escape returns to the review/table (does not discard the batch).
                    event.key == Key.Escape && event.type == KeyEventType.KeyDown -> {
                        addVm.addMoreFromReview(); true
                    }
                    // Cmd/Ctrl+Enter parses (plain Enter is a newline in the multiline field).
                    event.key == Key.Enter && event.type == KeyEventType.KeyDown &&
                        (event.isMetaPressed || event.isCtrlPressed) && canParse -> {
                        addVm.parseAndAdd(); true
                    }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(dims.space20)) {
            // Blue header card (matches add-product dialogs)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(dims.radiusCard))
                    .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                    .padding(dims.space20),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.ContentPaste, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(dims.space12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            strings(Strings.inventory_paste_title),
                            style = AromexTheme.typography.sectionTitle,
                            color = Color.White,
                        )
                        Text(
                            strings(Strings.inventory_paste_subtitle),
                            style = AromexTheme.typography.hint,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(dims.space16))

            // Multiline paste field
            OutlinedTextField(
                value = state.pasteText,
                onValueChange = addVm::onPasteTextChange,
                placeholder = { Text(strings(Strings.inventory_paste_hint), color = colors.textTertiary) },
                colors = fieldColors(),
                shape = RoundedCornerShape(dims.radiusField),
                // Bounded height so a large paste scrolls INSIDE the box (ticket #101) instead of
                // stretching the page and pushing the action buttons off-screen.
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp, max = 320.dp),
            )

            if (state.pasteText.isBlank()) {
                Spacer(Modifier.height(dims.space8))
                Text(
                    strings(Strings.inventory_paste_empty),
                    style = AromexTheme.typography.hint,
                    color = colors.textTertiary,
                )
            }

            Spacer(Modifier.height(dims.space16))

            Row(horizontalArrangement = Arrangement.spacedBy(dims.space12), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = addVm::parseAndAdd,
                    enabled = canParse,
                    modifier = Modifier.height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    if (state.parsing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(dims.space8))
                        Text(strings(Strings.inventory_paste_parse), style = AromexTheme.typography.button, softWrap = false)
                    }
                }
                if (state.pasteText.isNotBlank()) {
                    TextButton(
                        onClick = addVm::clearPasteText,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) { Text(strings(Strings.inventory_paste_clear), color = colors.textSecondary) }
                }
                Spacer(Modifier.weight(1f))
                if (state.reviewUnits.isNotEmpty()) {
                    TextButton(
                        onClick = addVm::addMoreFromReview,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) { Text(strings(Strings.inventory_back_cd), color = colors.brand) }
                }
            }
        }
    }
}

// ── Parse summary banner ──────────────────────────────────────────────────────

@Composable
private fun ParseSummaryBanner(encoded: String, onDismiss: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val parts = encoded.split("|")
    val text = when (parts.firstOrNull()) {
        Strings.inventory_parse_summary_one -> strings(Strings.inventory_parse_summary_one, parts.getOrElse(1) { "0" })
        Strings.inventory_parse_summary -> strings(Strings.inventory_parse_summary, parts.getOrElse(1) { "0" }, parts.getOrElse(2) { "0" })
        else -> strings(Strings.inventory_parse_none)
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.brand.copy(alpha = 0.1f))
            .padding(horizontal = dims.space20, vertical = dims.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Check, null, tint = colors.brand, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(dims.space8))
        Text(text, color = colors.brand, style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.Medium), modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Text(strings(Strings.inventory_unreadable_dismiss), color = colors.brand)
        }
    }
}

// ── "Couldn't read" list ──────────────────────────────────────────────────────

@Composable
private fun UnreadableList(
    blocks: List<UnreadableBlock>,
    onDismiss: (Int) -> Unit,
    onDismissAll: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(colors.warning.copy(alpha = 0.08f))
            .padding(horizontal = dims.space20, vertical = dims.space12),
        verticalArrangement = Arrangement.spacedBy(dims.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = colors.warning, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(dims.space8))
            Text(
                strings(Strings.inventory_unreadable_title) + " · " +
                    strings(Strings.inventory_unreadable_count, blocks.size),
                style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold),
                color = colors.warning,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismissAll, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Text(strings(Strings.inventory_unreadable_dismiss), color = colors.warning)
            }
        }
        blocks.forEachIndexed { index, block ->
            val reasonText = when (block.reason) {
                UnreadableBlock.Reason.NO_MODEL -> strings(Strings.inventory_unreadable_no_model)
                UnreadableBlock.Reason.NOT_IPHONE -> strings(Strings.inventory_unreadable_not_iphone)
                UnreadableBlock.Reason.NO_IMEI -> strings(Strings.inventory_unreadable_no_imei)
            }
            Row(
                modifier = Modifier.fillMaxWidth()
                    .border(1.dp, colors.border, RoundedCornerShape(dims.radiusField))
                    .clip(RoundedCornerShape(dims.radiusField))
                    .background(colors.surface)
                    .padding(start = dims.space12, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(reasonText, style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.Medium), color = colors.textSecondary)
                    SelectionContainer {
                        Text(
                            block.rawText.trim(),
                            style = AromexTheme.typography.hint,
                            color = colors.textTertiary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(
                    onClick = { onDismiss(index) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).size(32.dp),
                ) {
                    Icon(Icons.Default.Close, strings(Strings.inventory_unreadable_dismiss), tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ── Apply-to-all bar ──────────────────────────────────────────────────────────

@Composable
private fun ApplyToAllBar(
    addVm: AddStockViewModel,
    state: AddStockUiState,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    val typography = AromexTheme.typography
    var cost by remember { mutableStateOf("") }
    var costFocused by remember { mutableStateOf(false) }
    var sellingPrice by remember { mutableStateOf("") }
    var sellFocused by remember { mutableStateOf(false) }
    var condition by remember { mutableStateOf<Condition?>(null) }
    var location by remember { mutableStateOf<AttributeRef?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth()
            .background(colors.brand.copy(alpha = 0.05f))
            .padding(horizontal = dims.space12, vertical = dims.space8),
        horizontalArrangement = Arrangement.spacedBy(dims.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Apply to all:",
            style = typography.hint.copy(fontWeight = FontWeight.SemiBold),
            color = colors.brand,
            softWrap = false,
        )
        // Cost
        Box(
            modifier = Modifier
                .weight(1f)
                .border(1.dp, if (costFocused) colors.brand else colors.border, RoundedCornerShape(dims.radiusField))
                .padding(start = dims.space12, end = dims.space12, top = 9.dp, bottom = 9.dp),
        ) {
            if (cost.isEmpty()) Text("Cost per unit", style = typography.hint, color = colors.textTertiary)
            BasicTextField(
                value = cost,
                onValueChange = { cost = it },
                singleLine = true,
                textStyle = typography.hint.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.brand),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().onFocusChanged { costFocused = it.isFocused },
            )
        }
        ConditionToggleOptional(condition) { condition = it }
        // Location — outlined compact dropdown matches cost field height and border
        FilterableDropdownField(
            label = "",
            compact = true,
            outlined = true,
            items = addVm.options(AttributeType.LOCATION),
            selectedItem = location,
            onItemSelected = { location = AttributeRef(it.attributeId, it.name) },
            onClear = { location = null },
            onAddNew = { addVm.addNewAttribute(AttributeType.LOCATION, it) },
            placeholder = strings(Strings.inventory_location_placeholder),
            modifier = Modifier.weight(1.5f),
        )
        // Selling price
        Box(
            modifier = Modifier
                .weight(1f)
                .border(1.dp, if (sellFocused) colors.brand else colors.border, RoundedCornerShape(dims.radiusField))
                .padding(start = dims.space12, end = dims.space12, top = 9.dp, bottom = 9.dp),
        ) {
            if (sellingPrice.isEmpty()) Text("Sell price", style = typography.hint, color = colors.textTertiary)
            BasicTextField(
                value = sellingPrice,
                onValueChange = { sellingPrice = it },
                singleLine = true,
                textStyle = typography.hint.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.brand),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().onFocusChanged { sellFocused = it.isFocused },
            )
        }
        Button(
            onClick = {
                addVm.applyToAllRows(
                    cost = cost.trim().ifBlank { null },
                    condition = condition,
                    location = location,
                    sellingPrice = sellingPrice.trim().ifBlank { null },
                )
            },
            enabled = cost.isNotBlank() || condition != null || location != null || sellingPrice.isNotBlank(),
            modifier = Modifier.height(36.dp).pointerHoverIcon(PointerIcon.Hand),
            shape = RoundedCornerShape(dims.radiusButton),
            colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = dims.space12, vertical = 0.dp),
        ) {
            Text(strings(Strings.inventory_apply_all_action), style = typography.hint.copy(fontWeight = FontWeight.SemiBold), softWrap = false)
        }
    }
    HorizontalDivider(color = colors.border)
}

// ── Review row (inline-editable, status-coloured) ─────────────────────────────

private enum class RowStatus { PARSED, MUST_FILL, PROBLEM }

// Shared column weights so the header and every row align into one spreadsheet grid.
private const val W_BRAND = 1.1f
private const val W_MODEL = 1.7f
private const val W_CAP = 0.85f
private const val W_COLOR = 1.5f
private const val W_CARRIER = 1.2f
private const val W_IMEI = 1.9f
private const val W_COST = 1.0f
private const val W_COND = 1.7f
private const val W_LOC = 1.6f
private const val W_SELL = 1.0f
private val STRIPE_WIDTH = 4.dp
private val ACTIONS_WIDTH = 64.dp

@Composable
private fun ReviewTableHeader() {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(modifier = Modifier.fillMaxWidth().background(colors.surface)) {
        Spacer(Modifier.width(STRIPE_WIDTH)) // align with the per-row status stripe
        Row(
            modifier = Modifier.weight(1f).padding(start = dims.space12, end = dims.space8, top = dims.space8, bottom = dims.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TableCell(strings(Strings.inventory_brand_label), Modifier.weight(W_BRAND), header = true)
            TableCell(strings(Strings.inventory_model_label), Modifier.weight(W_MODEL), header = true)
            TableCell(strings(Strings.inventory_col_capacity), Modifier.weight(W_CAP), header = true)
            TableCell(strings(Strings.inventory_color_label), Modifier.weight(W_COLOR), header = true)
            TableCell(strings(Strings.inventory_carrier_label), Modifier.weight(W_CARRIER), header = true)
            TableCell(strings(Strings.inventory_col_imei), Modifier.weight(W_IMEI), header = true)
            TableCell(strings(Strings.inventory_col_cost), Modifier.weight(W_COST), header = true)
            TableCell(strings(Strings.inventory_col_condition), Modifier.weight(W_COND), header = true)
            TableCell(strings(Strings.inventory_col_location), Modifier.weight(W_LOC), header = true)
            TableCell(strings(Strings.inventory_col_sell_price), Modifier.weight(W_SELL), header = true)
            Spacer(Modifier.width(ACTIONS_WIDTH))
        }
    }
}

@Composable
private fun ReviewRow(
    addVm: AddStockViewModel,
    state: AddStockUiState,
    unit: ReviewUnit,
    originalIndex: Int,
    striped: Boolean,
    onEdit: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    // Live IMEI advisory flags
    val dupInBatch = remember(state.reviewUnits, originalIndex) {
        state.reviewUnits.count { it.imei.equals(unit.imei, ignoreCase = true) } > 1
    }
    val inStock = unit.imei in state.imeiInStock
    // Complete = cost + positive selling price + location + all SKU-defining attrs present
    // (mirrors what the shared AddStockUseCase requires).
    val shopComplete = unit.isComplete()

    val status = when {
        dupInBatch || inStock -> RowStatus.PROBLEM
        !shopComplete -> RowStatus.MUST_FILL
        else -> RowStatus.PARSED
    }
    val statusColor = when (status) {
        RowStatus.PARSED -> colors.success
        RowStatus.MUST_FILL -> colors.warning
        RowStatus.PROBLEM -> colors.error
    }

    // Add-to-existing-SKU (#52): SKU attributes are locked; paste/new-SKU rows are editable.
    val skuEditable = !state.attributesLocked
    val brandId = unit.attributes[AttributeType.BRAND]?.attributeId

    val rowSrc = remember { MutableInteractionSource() }
    val hovered by rowSrc.collectIsHoveredAsState()
    val rowBg = if (striped) colors.surface else colors.background

    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(rowBg).hoverable(rowSrc),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left status stripe (full row height)
        Box(Modifier.width(STRIPE_WIDTH).fillMaxHeight().background(statusColor))
        Row(
            modifier = Modifier.weight(1f).padding(start = dims.space12, end = dims.space8, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── SKU attribute cells (inline dropdowns, same logic as the picker) ──
            SkuCell(addVm, unit, originalIndex, AttributeType.BRAND, addVm.options(AttributeType.BRAND),
                enabled = skuEditable, placeholder = strings(Strings.inventory_brand_placeholder),
                modifier = Modifier.weight(W_BRAND))
            SkuCell(addVm, unit, originalIndex, AttributeType.MODEL, addVm.optionsFor(AttributeType.MODEL, brandId),
                enabled = skuEditable && brandId != null, placeholder = strings(Strings.inventory_model_placeholder),
                modifier = Modifier.weight(W_MODEL))
            SkuCell(addVm, unit, originalIndex, AttributeType.CAPACITY, addVm.options(AttributeType.CAPACITY),
                enabled = skuEditable, placeholder = strings(Strings.inventory_capacity_placeholder),
                modifier = Modifier.weight(W_CAP))
            SkuCell(addVm, unit, originalIndex, AttributeType.COLOR, addVm.options(AttributeType.COLOR),
                enabled = skuEditable, placeholder = strings(Strings.inventory_color_placeholder),
                modifier = Modifier.weight(W_COLOR))
            SkuCell(addVm, unit, originalIndex, AttributeType.CARRIER, addVm.options(AttributeType.CARRIER),
                enabled = skuEditable, placeholder = strings(Strings.inventory_carrier_placeholder),
                modifier = Modifier.weight(W_CARRIER))

            // ── IMEI (display text + advisory sub-text) ──
            Column(Modifier.weight(W_IMEI).padding(start = 6.dp, end = 8.dp)) {
                SelectionContainer {
                    Text(
                        unit.imei.ifBlank { "—" },
                        style = AromexTheme.typography.hint,
                        color = if (unit.imei.isBlank()) colors.textTertiary else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    inStock -> Text(strings(Strings.inventory_status_in_stock), style = AromexTheme.typography.hint, color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    dupInBatch -> Text(strings(Strings.inventory_status_dup_batch), style = AromexTheme.typography.hint, color = colors.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // ── COST ──
            MoneyCell(unit.cost, { addVm.updateReviewCell(originalIndex, cost = it) },
                mustFill = unit.cost.isBlank(), modifier = Modifier.weight(W_COST).padding(end = 8.dp))

            // ── CONDITION ──
            ConditionCell(
                condition = unit.condition,
                onChange = { addVm.updateReviewCell(originalIndex, condition = it) },
                modifier = Modifier.weight(W_COND).padding(end = 8.dp),
            )

            // ── LOCATION ──
            Box(Modifier.weight(W_LOC).padding(end = 8.dp)) {
                FilterableDropdownField(
                    label = "",
                    compact = true,
                    items = addVm.options(AttributeType.LOCATION),
                    selectedItem = unit.location.takeIf { it.attributeId.isNotBlank() },
                    onItemSelected = { addVm.updateReviewCell(originalIndex, location = AttributeRef(it.attributeId, it.name)) },
                    onClear = { addVm.updateReviewCell(originalIndex, location = AttributeRef("", "")) },
                    onAddNew = { addVm.addNewAttribute(AttributeType.LOCATION, it) },
                    placeholder = strings(Strings.inventory_location_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── SELL PRICE ──
            MoneyCell(unit.sellingPrice, { addVm.updateReviewCell(originalIndex, sellingPrice = it) },
                mustFill = unit.sellingPrice.isBlank(), modifier = Modifier.weight(W_SELL).padding(end = 8.dp))

            // ── Row actions (revealed on hover) ──
            Row(
                modifier = Modifier.width(ACTIONS_WIDTH),
                horizontalArrangement = Arrangement.End,
            ) {
                if (hovered) {
                    IconButton(onClick = onEdit, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).size(32.dp)) {
                        Icon(Icons.Default.Edit, strings(Strings.inventory_review_edit_cd), tint = colors.brand, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = { addVm.removeReviewUnit(originalIndex) }, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).size(32.dp)) {
                        Icon(Icons.Default.Delete, strings(Strings.inventory_review_delete_cd), tint = colors.error, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** One inline SKU-attribute cell: a compact [FilterableDropdownField] reusing the picker's
 *  find-or-create logic verbatim. */
@Composable
private fun SkuCell(
    addVm: AddStockViewModel,
    unit: ReviewUnit,
    index: Int,
    type: AttributeType,
    items: List<AttributeValue>,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val ref = unit.attributes[type]
    FilterableDropdownField(
        label = "",
        compact = true,
        enabled = enabled,
        items = items,
        selectedItem = ref,
        onItemSelected = { addVm.updateReviewAttribute(index, type, AttributeRef(it.attributeId, it.name)) },
        onClear = { addVm.clearReviewAttribute(index, type) },
        onAddNew = { addVm.addNewAttribute(type, it) },
        placeholder = placeholder,
        modifier = modifier.padding(end = 8.dp).fillMaxWidth(),
    )
}

@Composable
internal fun MoneyCell(
    value: String,
    onValue: (String) -> Unit = {},
    mustFill: Boolean = false,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    if (readOnly) {
        Row(
            modifier = modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$", style = AromexTheme.typography.hint, color = colors.textTertiary)
            Text(
                value.ifBlank { "—" },
                style = AromexTheme.typography.hint,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    var isFocused by remember { mutableStateOf(false) }
    // BasicTextField with zero internal padding so "$" symbol aligns flush with "COST"/"SELL PRICE" headers.
    Row(
        modifier = modifier
            .border(1.dp, if (isFocused) colors.brand else Color.Transparent, RoundedCornerShape(dims.radiusField))
            .padding(start = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$", style = AromexTheme.typography.hint, color = colors.textTertiary)
        Box(Modifier.weight(1f).padding(end = 4.dp)) {
            if (value.isEmpty()) {
                Text("0.00", style = AromexTheme.typography.hint, color = colors.textTertiary)
            }
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = AromexTheme.typography.hint.copy(
                    color = if (mustFill) colors.textTertiary else colors.textPrimary,
                ),
                cursorBrush = SolidColor(colors.brand),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused },
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Spacer(Modifier.height(2.dp))
    Text(
        text,
        style = AromexTheme.typography.hint,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ConditionToggleOptional(selected: Condition?, onSelect: (Condition) -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
        Condition.entries.forEach { c ->
            val label = if (c == Condition.NEW) strings(Strings.inventory_condition_new)
            else strings(Strings.inventory_condition_used)
            if (c == selected) {
                Button(
                    onClick = { onSelect(c) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) { Text(label, softWrap = false) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(c) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                ) { Text(label, softWrap = false) }
            }
        }
    }
}

// ── Shared sub-components ─────────────────────────────────────────────────────

@Composable
internal fun TableCell(text: String, modifier: Modifier = Modifier, header: Boolean = false) {
    val colors = AromexTheme.colors
    Text(
        text = text,
        style = AromexTheme.typography.hint.copy(
            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = if (header) colors.textTertiary else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(start = 6.dp, end = 8.dp),
    )
}

@Composable
private fun DialogSectionHeader(title: String) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dims.space12),
    ) {
        Text(
            title,
            style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold),
            color = colors.brand,
        )
        HorizontalDivider(color = colors.border, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConditionToggle(selected: Condition, onSelect: (Condition) -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
        Condition.entries.forEach { c ->
            val label = if (c == Condition.NEW) strings(Strings.inventory_condition_new)
            else strings(Strings.inventory_condition_used)
            if (c == selected) {
                Button(
                    onClick = { onSelect(c) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) { Text(label, softWrap = false) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(c) },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                ) { Text(label, softWrap = false) }
            }
        }
    }
}

// Compact table-cell for Condition (New / Used). Pass readOnly=true for the browse table.
@Composable
internal fun ConditionCell(
    condition: Condition,
    onChange: (Condition) -> Unit = {},
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (readOnly) {
        val colors = AromexTheme.colors
        val label = if (condition == Condition.NEW) strings(Strings.inventory_condition_new)
            else strings(Strings.inventory_condition_used)
        val chipColor = if (condition == Condition.NEW) colors.success else colors.warning
        Box(
            modifier = modifier.padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(chipColor.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    label,
                    style = AromexTheme.typography.hint,
                    color = chipColor,
                    maxLines = 1,
                )
            }
        }
        return
    }
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val density = LocalDensity.current

    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var fieldWidthDp by remember { mutableStateOf(0.dp) }
    var fieldHeightPx by remember { mutableStateOf(0) }
    // After a close, briefly refuse to reopen so the click-outside that dismissed can't double-fire
    // with a trigger click and reopen it (see FilterableDropdownField). 300ms outlasts the replay.
    var reopenSuppressedUntilMs by remember { mutableStateOf(0L) }
    fun closeDropdown() {
        expanded = false
        isFocused = false
        reopenSuppressedUntilMs = System.currentTimeMillis() + 300L
    }
    fun toggleDropdown() {
        if (expanded) closeDropdown()
        else if (System.currentTimeMillis() >= reopenSuppressedUntilMs) { expanded = true; isFocused = true }
    }

    val label = if (condition == Condition.NEW) strings(Strings.inventory_condition_new)
    else strings(Strings.inventory_condition_used)

    Box(
        modifier = modifier.onSizeChanged { size ->
            fieldWidthDp = with(density) { size.width.toDp() }
            fieldHeightPx = size.height
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, if (isFocused || expanded) colors.brand else Color.Transparent, RoundedCornerShape(dims.radiusField))
                .padding(start = 6.dp, top = 9.dp, bottom = 9.dp, end = 18.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                // Toggle open/closed (pure picker). Closing suppresses reopen so a paired
                // click-outside can't immediately reopen it.
                .clickable { toggleDropdown() },
        ) {
            Text(label, style = typography.hint, color = colors.textPrimary, maxLines = 1)
        }
        Icon(
            Icons.Default.ExpandMore,
            null,
            tint = colors.textTertiary,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp)
                .size(14.dp)
                .rotate(if (expanded) 180f else 0f)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { toggleDropdown() },
        )
        if (expanded && fieldWidthDp > 0.dp) {
            val gapPx = with(density) { 2.dp.roundToPx() }
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, fieldHeightPx + gapPx),
                onDismissRequest = {
                    closeDropdown()
                },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(dims.radiusField),
                    shadowElevation = 8.dp,
                    color = colors.surface,
                    modifier = Modifier.width(fieldWidthDp),
                ) {
                    Column {
                        listOf(Condition.NEW to strings(Strings.inventory_condition_new),
                               Condition.USED to strings(Strings.inventory_condition_used)).forEach { (cond, lbl) ->
                            val isSelected = condition == cond
                            val hoverSrc = remember { MutableInteractionSource() }
                            val hovered by hoverSrc.collectIsHoveredAsState()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .hoverable(hoverSrc)
                                    .background(if (hovered) colors.brand.copy(alpha = 0.06f) else Color.Transparent)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable { onChange(cond); closeDropdown() }
                                    .padding(start = 6.dp, end = 6.dp, top = 9.dp, bottom = 9.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(lbl, style = typography.hint, color = if (isSelected) colors.brand else colors.textPrimary, modifier = Modifier.weight(1f))
                                if (isSelected) Icon(Icons.Default.Check, null, tint = colors.brand, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AromexTheme.colors.brand,
    unfocusedBorderColor = AromexTheme.colors.border,
    focusedLabelColor = AromexTheme.colors.brand,
    unfocusedLabelColor = AromexTheme.colors.textTertiary,
    cursorColor = AromexTheme.colors.brand,
    focusedContainerColor = AromexTheme.colors.surface,
    unfocusedContainerColor = AromexTheme.colors.surface,
    disabledContainerColor = AromexTheme.colors.surfaceAlt,
    disabledBorderColor = AromexTheme.colors.border,
    disabledTextColor = AromexTheme.colors.textTertiary,
)

/** True when [millis] falls on today's calendar day — a same-day batch needs no explanation. */
private fun isSameDayAsToday(millis: Long): Boolean {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val b = java.util.Calendar.getInstance()
    return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
}

private val purchaseDateFormat =
    java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.getDefault())
