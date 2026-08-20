package com.humblesolutions.aromex.ui.sales.history

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.components.AromexDialog
import com.humblesolutions.aromex.ui.components.DesktopSection
import androidx.compose.ui.zIndex
import com.humblesolutions.aromex.ui.components.CollapsedSidebarWidth
import com.humblesolutions.aromex.ui.components.ExpandedSidebarWidth
import com.humblesolutions.aromex.ui.components.NavSidebar
import com.humblesolutions.aromex.ui.i18n.strings
import androidx.compose.foundation.lazy.itemsIndexed
import com.humblesolutions.aromex.ui.money.DateRangeChip
import com.humblesolutions.aromex.ui.money.EmptyBlock
import com.humblesolutions.aromex.ui.money.SearchBox
import com.humblesolutions.aromex.ui.money.ToolbarChip
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.MoneyFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Desktop Sales History (ticket #83): a paged, searchable list of past sales plus a detail
 * view carrying every recorded field and the reused #77 invoice row. Sales are the app's first
 * unbounded collection, so the list pages ([SalesHistoryViewModel.loadMore]) and appends — it
 * never blanks or re-fetches what's shown. Layout is resize-safe: a weighted table on a wide
 * window, reflowing to stacked cards below [COMPACT_BREAKPOINT]; truncated cells reveal on hover.
 * Theme tokens only — light + dark.
 */
@Composable
fun SalesHistoryScreen(
    vm: SalesHistoryViewModel,
    session: UserSession?,
    onNavigateToEntities: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToMoney: () -> Unit = {},
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

    var showFilters by remember { mutableStateOf(false) }
    var showSignOutDialog by remember { mutableStateOf(false) }
    var showVoidDialog by remember { mutableStateOf(false) }
    // Close the void dialog the moment the sale flips VOIDED (a successful reversal). A failure
    // leaves it open with the reason shown; the CF/use case never partially voids.
    LaunchedEffect(state.detail?.isVoided) {
        if (showVoidDialog && state.detail?.isVoided == true) showVoidDialog = false
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize()) {
            NavSidebar(
                expanded = isSidebarExpanded,
                width = sidebarWidth,
                modifier = Modifier.zIndex(1f),
                selectedSection = DesktopSection.SALES_HISTORY,
                session = session,
                interactionSource = sidebarSrc,
                onNavigateToEntities = onNavigateToEntities,
                onNavigateToInventory = onNavigateToInventory,
                onNavigateToSales = onNavigateToSales,
                onNavigateToSalesHistory = {},
                onNavigateToMoney = onNavigateToMoney,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStockHistory = onNavigateToStockHistory,
                onSignOutRequest = { showSignOutDialog = true },
            )

            // The detail top bar carries the PDF actions (Share/Download), so surface the issued
            // invoice's URL + number here. Prefer the live invoice, fall back to the loaded detail.
            val liveInvoice = state.detailInvoice.invoice
            val detailPdfUrl = (liveInvoice.url ?: state.detail?.invoice?.url)
                ?.takeIf { liveInvoice.status == SaleInvoiceStatus.ISSUED && it.isNotBlank() }
            val detailInvoiceNumber = liveInvoice.number ?: state.detail?.invoice?.number

            Column(Modifier.fillMaxSize().padding(start = CollapsedSidebarWidth).hoverable(contentSrc)) {
                HistoryTopBar(
                    sidebarExpanded = isSidebarExpanded,
                    onExpandSidebar = { isSidebarExpanded = true },
                    detailOpen = state.detail != null || state.detailLoading,
                    onBack = vm::closeDetail,
                    enabled = !state.permissionDenied,
                    pdfUrl = detailPdfUrl,
                    invoiceNumber = detailInvoiceNumber,
                    canVoid = state.canVoidOpenSale,
                    isVoiding = state.isVoiding,
                    onVoid = { vm.clearVoidError(); showVoidDialog = true },
                    isRefreshing = state.isLoading,
                    onRefresh = vm::refresh,
                )
                HorizontalDivider(color = colors.border)

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.permissionDenied -> NoAccessState()
                        state.detailLoading -> LoadingCentered()
                        state.detail != null -> SaleDetailView(state = state, vm = vm)
                        else -> HistoryListBody(state = state, vm = vm, onOpenFilters = { showFilters = true })
                    }
                }
            }
        }

        if (showFilters) {
            FilterDialog(
                initial = state.filter,
                onApply = { vm.applyFilter(it); showFilters = false },
                onClear = { vm.clearFilters(); showFilters = false },
                onDismiss = { showFilters = false },
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
        if (showVoidDialog) {
            VoidSaleDialog(
                isVoiding = state.isVoiding,
                error = state.voidError,
                onConfirm = { reason -> vm.voidSale(reason) },
                // Not dismissible by accident: only the explicit Cancel closes it, and never mid-void.
                onCancel = { if (!state.isVoiding) { vm.clearVoidError(); showVoidDialog = false } },
            )
        }
    }
}

// ── Top bar ──────────────────────────────────────────────────────────────────

@Composable
private fun HistoryTopBar(
    sidebarExpanded: Boolean,
    onExpandSidebar: () -> Unit,
    detailOpen: Boolean,
    onBack: () -> Unit,
    enabled: Boolean,
    pdfUrl: String?,
    invoiceNumber: String?,
    canVoid: Boolean,
    isVoiding: Boolean,
    onVoid: () -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface)
            .padding(horizontal = dims.space20, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dims.space12),
    ) {
        if (detailOpen) {
            IconButton(onClick = onBack, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings(Strings.sales_history_detail_back), tint = colors.textSecondary)
            }
        } else if (!sidebarExpanded) {
            IconButton(onClick = onExpandSidebar) {
                Icon(Icons.Filled.Menu, "Expand menu", tint = colors.textSecondary)
            }
        }
        Text(
            strings(if (detailOpen) Strings.sales_history_detail_title else Strings.sales_history_title),
            style = AromexTheme.typography.sectionTitle,
            color = colors.textPrimary,
        )
        Spacer(Modifier.weight(1f))

        // Search / filters / refresh now live in the table's toolbar, beside the row count they
        // act on. Only Refresh stays up here, because it belongs to the page, not the filter set.
        if (!detailOpen && enabled) {
            RefreshButton(isRefreshing = isRefreshing, onClick = onRefresh)
        }
        // The detail view carries Share / Download of the actual invoice PDF (only once issued).
        if (detailOpen && pdfUrl != null) {
            DetailPdfActions(pdfUrl = pdfUrl, invoiceNumber = invoiceNumber)
        }
        // Void lives at the very right of the detail bar — a destructive, admin-only action, kept
        // apart from the everyday PDF actions. Absent for non-admins and already-voided sales.
        if (detailOpen && canVoid) {
            VoidTopBarAction(isVoiding = isVoiding, onClick = onVoid)
        }
    }
}

/** The destructive Void action in the detail top bar (ticket #85): a spinner while a void runs. */
@Composable
private fun VoidTopBarAction(isVoiding: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    if (isVoiding) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.error)
    } else {
        IconButton(onClick = onClick, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Icon(Icons.Filled.Block, strings(Strings.sales_history_void), tint = colors.error)
        }
    }
}

/**
 * The detail top-bar PDF actions: **Share** (save the PDF to Downloads and reveal it in the OS
 * file browser) and **Download** (save it wherever the user picks). Both act on the real PDF
 * bytes, not the S3 link. Shows a spinner while working and a brief confirmation/error after.
 */
@Composable
private fun DetailPdfActions(pdfUrl: String, invoiceNumber: String?) {
    val colors = AromexTheme.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val savedMsg = strings(Strings.sales_history_pdf_saved)
    val errMsg = strings(Strings.sales_history_pdf_error)

    message?.let { msg ->
        LaunchedEffect(msg) { delay(2500); message = null }
        Text(msg, style = AromexTheme.typography.hint, color = colors.textTertiary, maxLines = 1)
    }
    if (busy) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.brand)
    }
    IconButton(
        onClick = {
            if (busy) return@IconButton
            busy = true
            scope.launch {
                val ok = runCatching { downloadAndRevealPdf(pdfUrl, invoiceNumber) }.isSuccess
                busy = false
                message = if (ok) savedMsg else errMsg
            }
        },
        enabled = !busy,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Icons.Filled.Share, strings(Strings.sales_history_share), tint = colors.textSecondary)
    }
    IconButton(
        onClick = {
            if (busy) return@IconButton
            busy = true
            scope.launch {
                // A cancelled Save dialog returns null — not an error, and not "saved".
                val result = runCatching { downloadAndSavePdf(pdfUrl, invoiceNumber) }
                busy = false
                message = when {
                    result.isFailure -> errMsg
                    result.getOrNull() == null -> null
                    else -> savedMsg
                }
            }
        },
        enabled = !busy,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    ) {
        Icon(Icons.Filled.Download, strings(Strings.sales_history_download), tint = colors.textSecondary)
    }
}


/** Reloads the list from Firestore (ticket #85) — a spinner while a reload is in flight. */
@Composable
private fun RefreshButton(isRefreshing: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    if (isRefreshing) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.brand)
        }
    } else {
        IconButton(onClick = onClick, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Icon(Icons.Filled.Refresh, strings(Strings.sales_history_refresh), tint = colors.textSecondary)
        }
    }
}


// ── List body ────────────────────────────────────────────────────────────────

@Composable
private fun HistoryListBody(state: SalesHistoryUiState, vm: SalesHistoryViewModel, onOpenFilters: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    Column(Modifier.fillMaxSize().padding(dims.space24)) {
        Text(strings(Strings.sales_history_title), style = typography.screenTitle, color = colors.textPrimary)
        Spacer(Modifier.height(2.dp))
        Text(strings(Strings.sales_history_subtitle), style = typography.hint, color = colors.textTertiary)
        Spacer(Modifier.height(dims.space16))

        state.error?.let { err ->
            Text(
                "${strings(Strings.sales_history_error)}: $err",
                color = colors.error,
                style = typography.hint,
                modifier = Modifier.padding(bottom = dims.space8),
            )
        }

        HistoryToolbar(state, vm, onOpenFilters)

        // A partial result has to look partial: the customer name matched more parties than one
        // Firestore query can cover, so these rows are missing some of them.
        state.customerMatchesCapped?.let { total ->
            Spacer(Modifier.height(dims.space8))
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(dims.radiusCard))
                    .background(colors.warning.copy(alpha = 0.12f))
                    .padding(horizontal = dims.space12, vertical = dims.space8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    strings(
                        Strings.sales_history_customers_capped,
                        state.filter.customerName.trim(),
                        total.toString(),
                        SalesHistoryViewModel.CUSTOMER_IN_LIMIT.toString(),
                    ),
                    style = typography.hint,
                    color = colors.textPrimary,
                )
            }
        }

        Spacer(Modifier.height(dims.space8))

        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(dims.radiusCard)),
        ) {
            val compact = maxWidth < COMPACT_BREAKPOINT
            Column(Modifier.fillMaxSize()) {
                if (!compact) {
                    HistoryTableHeader()
                    HorizontalDivider(color = colors.border)
                }
                when {
                    state.isLoading && state.visibleSales.isEmpty() -> SkeletonList(compact = compact)
                    state.visibleSales.isEmpty() && state.hasActiveFilter -> EmptyBlock(
                        title = strings(Strings.sales_history_no_match_title),
                        hint = strings(Strings.sales_history_no_match_body),
                    )
                    state.visibleSales.isEmpty() -> EmptyBlock(
                        title = strings(Strings.sales_history_empty_title),
                        hint = strings(Strings.sales_history_empty_body),
                    )
                    else -> {
                        val listState = rememberLazyListState()
                        // Infinite scroll: request the next page as the tail comes into view.
                        // Appends, never replaces (the ticket's "loading a page must not blank
                        // the list" rule).
                        val atEnd by remember {
                            derivedStateOf {
                                val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                last >= state.visibleSales.size - PREFETCH_THRESHOLD
                            }
                        }
                        LaunchedEffect(atEnd, state.hasMore, state.visibleSales.size) {
                            if (atEnd && state.hasMore) vm.loadMore()
                        }

                        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(state.visibleSales, key = { _, it -> it.saleId }) { index, sale ->
                                if (compact) {
                                    SaleCard(sale = sale, state = state, vm = vm)
                                } else {
                                    SaleRow(sale = sale, state = state, vm = vm, striped = index % 2 == 1)
                                }
                                HorizontalDivider(color = colors.border.copy(alpha = 0.45f))
                            }
                            if (state.isLoadingMore) {
                                item(key = "loading_more") { LoadingMoreFooter() }
                            }
                            item(key = "tail_spacer") { Spacer(Modifier.height(dims.space12)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Count, search, date range, filters — the same toolbar the money ledger and Stock History use,
 * built from the same parts so the three screens read as one system.
 *
 * The date range sits out here rather than behind Filters: it is the control people reach for
 * most, and burying it made the Filters sheet look like the only way to narrow anything.
 */
@Composable
private fun HistoryToolbar(state: SalesHistoryUiState, vm: SalesHistoryViewModel, onOpenFilters: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(strings(Strings.sales_history_recent), style = typography.sectionTitle, color = colors.textPrimary)
        Spacer(Modifier.width(dims.space8))
        // "12 of 40" while the quick-search is narrowing, plain count otherwise — a filter must
        // never be mistakable for data loss.
        Text(
            if (state.visibleSales.size != state.sales.size) {
                "${state.visibleSales.size} ${strings(Strings.money_of)} ${state.sales.size}"
            } else {
                "${state.sales.size}${if (state.hasMore) "+" else ""}"
            },
            style = typography.hint,
            color = colors.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(dims.radiusPill))
                .background(colors.surfaceAlt)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        SearchBox(state.searchText, vm::onSearchChanged)
        Spacer(Modifier.width(dims.space8))
        DateRangeChip(state.filter.dateFromMillis, state.filter.dateToMillis) { from, to ->
            vm.applyFilter(state.filter.copy(dateFromMillis = from, dateToMillis = to))
        }
        Spacer(Modifier.width(dims.space8))
        ToolbarChip(
            if (state.filter.activeCount > 0) {
                strings(Strings.sales_history_filters_active, state.filter.activeCount.toString())
            } else {
                strings(Strings.sales_history_filters)
            },
            onClick = onOpenFilters,
        )
        if (state.hasActiveFilter) {
            Spacer(Modifier.width(dims.space8))
            ToolbarChip(strings(Strings.money_clear_filters), onClick = vm::clearFilters)
        }
    }
}

// Shared column weights — the header and every row use these so the table stays aligned.
// (Items + IMEI columns were removed by request; the local search still matches on them.)
private const val COL_DATE = 1.6f
private const val COL_INVOICE = 1.2f
private const val COL_CUSTOMER = 2.0f
private const val COL_TOTAL = 1.0f
private const val COL_PAID = 1.0f
private const val COL_BALANCE = 1.0f
private const val COL_STATUS = 1.3f
private val COL_ACTIONS = 92.dp

@Composable
private fun HistoryTableHeader() {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    @Composable
    fun HeaderText(key: String, modifier: Modifier, alignEnd: Boolean = false) = Text(
        strings(key),
        modifier = modifier.padding(end = 8.dp),
        style = AromexTheme.typography.hint.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
    Row(
        modifier = Modifier.fillMaxWidth().background(colors.surface)
            .padding(start = dims.space20, end = dims.space20, top = dims.space12, bottom = dims.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderText(Strings.sales_history_col_date, Modifier.weight(COL_DATE))
        HeaderText(Strings.sales_history_col_invoice, Modifier.weight(COL_INVOICE))
        HeaderText(Strings.sales_history_col_customer, Modifier.weight(COL_CUSTOMER))
        HeaderText(Strings.sales_history_col_total, Modifier.weight(COL_TOTAL), alignEnd = true)
        HeaderText(Strings.sales_history_col_paid, Modifier.weight(COL_PAID), alignEnd = true)
        HeaderText(Strings.sales_history_col_balance, Modifier.weight(COL_BALANCE), alignEnd = true)
        HeaderText(Strings.sales_history_col_status, Modifier.weight(COL_STATUS))
        Text(
            strings(Strings.sales_history_col_actions),
            modifier = Modifier.width(COL_ACTIONS),
            style = AromexTheme.typography.hint.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textTertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun SaleRow(
    sale: SaleSummary,
    state: SalesHistoryUiState,
    vm: SalesHistoryViewModel,
    striped: Boolean = false,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    // SelectionContainer *around* the clickable row: a plain click still opens the sale (the inner
    // clickable wins the tap), while a press-and-drag selects the row's text to copy.
    SelectionContainer {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(
                when {
                    hovered -> colors.brand.copy(alpha = 0.05f)
                    striped -> colors.surfaceAlt.copy(alpha = 0.45f)
                    else -> Color.Transparent
                },
            )
            .hoverable(hoverSrc)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { vm.openSale(sale.saleId) }
            .padding(start = dims.space20, end = dims.space20, top = dims.space12, bottom = dims.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryCell(formatDateTime(sale.createdAtMillis, state.timezone), Modifier.weight(COL_DATE))
        HistoryCell(sale.invoiceNumber ?: "—", Modifier.weight(COL_INVOICE))
        HistoryCell(vm.customerNameOf(sale), Modifier.weight(COL_CUSTOMER), emphasize = true)
        HistoryMoneyCell(sale.grandTotal, state.currency, Modifier.weight(COL_TOTAL))
        HistoryMoneyCell(sale.amountPaid, state.currency, Modifier.weight(COL_PAID))
        HistoryMoneyCell(
            sale.balanceRemaining, state.currency, Modifier.weight(COL_BALANCE),
            color = if (Money.isZero(sale.balanceRemaining)) colors.textSecondary else colors.warning,
        )
        Column(Modifier.weight(COL_STATUS), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (sale.status.isVoided) VoidedChip()
            SyncChip(sale.syncStatus)
            InvoiceChip(sale.invoiceStatus)
        }
        Box(Modifier.width(COL_ACTIONS), contentAlignment = Alignment.CenterStart) {
            RowInvoiceAction(sale = sale, vm = vm)
        }
    }
    }
}

/** Compact card for narrow windows — the same data, stacked so nothing clips or scrolls sideways. */
@Composable
private fun SaleCard(sale: SaleSummary, state: SalesHistoryUiState, vm: SalesHistoryViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    // See SaleRow: click-to-open still works; press-and-drag selects the card's text.
    SelectionContainer {
    Column(
        modifier = Modifier.fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { vm.openSale(sale.saleId) }
            .padding(horizontal = dims.space20, vertical = dims.space12),
        verticalArrangement = Arrangement.spacedBy(dims.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
            Text(
                vm.customerNameOf(sale),
                style = AromexTheme.typography.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RowInvoiceAction(sale = sale, vm = vm)
        }
        Text(
            "${formatDateTime(sale.createdAtMillis, state.timezone)} · ${sale.invoiceNumber ?: "—"}",
            style = AromexTheme.typography.hint,
            color = colors.textTertiary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
            Text("${strings(Strings.sales_history_col_total)}: ${MoneyFormat.format(sale.grandTotal, state.currency)}", style = AromexTheme.typography.hint, color = colors.textSecondary)
            if (!Money.isZero(sale.balanceRemaining)) {
                Text("${strings(Strings.sales_history_col_balance)}: ${MoneyFormat.format(sale.balanceRemaining, state.currency)}", style = AromexTheme.typography.hint, color = colors.warning)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (sale.status.isVoided) VoidedChip()
            SyncChip(sale.syncStatus)
            InvoiceChip(sale.invoiceStatus)
        }
    }
    }
}

/** Per-row invoice action: Print/Download an ISSUED invoice, Retry a FAILED one, spinner while PENDING. */
@Composable
private fun RowInvoiceAction(sale: SaleSummary, vm: SalesHistoryViewModel) {
    val colors = AromexTheme.colors
    // Excluded from the row's SelectionContainer — these are buttons, not selectable text, so a
    // press-and-drag must not turn them into a text selection instead of a click.
    DisableSelection {
        when (sale.invoiceStatus) {
            SaleInvoiceStatus.ISSUED -> {
                // Row-level quick action opens the sale so Print/Download/Copy run through the same
                // reused #77 InvoiceRow in the detail view (single source of the invoice actions).
                IconButton(
                    onClick = { vm.openSale(sale.saleId) },
                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Filled.Print, strings(Strings.sales_invoice_print), tint = colors.brand, modifier = Modifier.size(18.dp))
                }
            }
            SaleInvoiceStatus.FAILED -> {
                IconButton(
                    onClick = { vm.openSale(sale.saleId) },
                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Filled.Refresh, strings(Strings.sales_invoice_retry), tint = colors.warning, modifier = Modifier.size(18.dp))
                }
            }
            SaleInvoiceStatus.PENDING ->
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.textTertiary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCell(text: String, modifier: Modifier, emphasize: Boolean = false, color: Color = AromexTheme.colors.textSecondary) {
    var overflowing by remember(text) { mutableStateOf(false) }
    TooltipArea(tooltip = { if (overflowing) CellTooltip(text) }, modifier = modifier) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            style = AromexTheme.typography.hint.copy(
                fontSize = 12.sp,
                fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (emphasize) AromexTheme.colors.textPrimary else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflowing = it.hasVisualOverflow },
        )
    }
}

@Composable
private fun HistoryMoneyCell(amount: String, currency: String, modifier: Modifier, color: Color = AromexTheme.colors.textSecondary) {
    Text(
        MoneyFormat.format(amount, currency),
        modifier = modifier.padding(end = 8.dp),
        style = AromexTheme.typography.hint.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
    )
}

@Composable
private fun CellTooltip(text: String) {
    val colors = AromexTheme.colors
    Box(
        modifier = Modifier.shadow(6.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp))
            .background(colors.textPrimary).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, style = AromexTheme.typography.hint.copy(fontSize = 12.sp), color = colors.background, maxLines = 1)
    }
}

// ── Status chips ─────────────────────────────────────────────────────────────

@Composable
private fun SyncChip(status: com.humblesolutions.aromex.model.HlSyncStatus) {
    val colors = AromexTheme.colors
    val (label, tint) = when (status) {
        com.humblesolutions.aromex.model.HlSyncStatus.SYNCED -> Strings.sales_history_sync_synced to colors.success
        com.humblesolutions.aromex.model.HlSyncStatus.FAILED -> Strings.sales_history_sync_failed to colors.error
        com.humblesolutions.aromex.model.HlSyncStatus.PENDING -> Strings.sales_history_sync_pending to colors.warning
    }
    StatusChip(strings(label), tint)
}

@Composable
private fun InvoiceChip(status: SaleInvoiceStatus) {
    val colors = AromexTheme.colors
    val (label, tint) = when (status) {
        SaleInvoiceStatus.ISSUED -> Strings.sales_history_inv_issued to colors.success
        SaleInvoiceStatus.FAILED -> Strings.sales_history_inv_failed to colors.error
        SaleInvoiceStatus.PENDING -> Strings.sales_history_inv_pending to colors.textTertiary
    }
    StatusChip(strings(label), tint)
}

/** The VOIDED badge (ticket #85) — a reversed sale, shown in the list and on the detail masthead. */
@Composable
private fun VoidedChip() {
    StatusChip(strings(Strings.sales_history_voided_badge), AromexTheme.colors.error)
}

@Composable
private fun StatusChip(label: String, tint: Color) {
    Box(
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = AromexTheme.typography.hint.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium), color = tint, maxLines = 1)
    }
}

// ── States (empty / loading / no-access) ──────────────────────────────────────


@Composable
private fun NoAccessState() {
    val colors = AromexTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(strings(Strings.sales_history_no_access), style = AromexTheme.typography.body, color = colors.textSecondary)
    }
}

@Composable
private fun LoadingCentered() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AromexTheme.colors.brand)
    }
}

@Composable
private fun LoadingMoreFooter() {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(
        modifier = Modifier.fillMaxWidth().padding(dims.space16),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.brand)
        Spacer(Modifier.width(dims.space8))
        Text(strings(Strings.sales_history_loading_more), style = AromexTheme.typography.hint, color = colors.textTertiary)
    }
}

/** Shimmer-free skeleton: greyed placeholder rows so the first load reads as "loading", not "empty". */
@Composable
private fun SkeletonList(compact: Boolean) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Column(Modifier.fillMaxSize().padding(dims.space20), verticalArrangement = Arrangement.spacedBy(dims.space12)) {
        repeat(if (compact) 6 else 10) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dims.space12), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceAlt))
                Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceAlt))
                if (!compact) {
                    Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceAlt))
                    Box(Modifier.weight(1f).height(16.dp).clip(RoundedCornerShape(4.dp)).background(colors.surfaceAlt))
                }
            }
        }
    }
}

// ── Detail view — a professional, bill-style invoice ──────────────────────────

// Line-items table columns — header and rows share these so everything lines up.
private const val BILL_ITEM = 3.2f
private const val BILL_IMEI = 2.2f
private const val BILL_LIST = 1.1f
private const val BILL_DISC = 1.0f
private const val BILL_UNIT = 1.1f
private const val BILL_AMOUNT = 1.3f

@Composable
private fun SaleDetailView(state: SalesHistoryUiState, vm: SalesHistoryViewModel) {
    val detail = state.detail ?: return
    val colors = AromexTheme.colors
    val liveInvoice = state.detailInvoice.invoice
    val pdfUrl = liveInvoice.url ?: detail.invoice.url
    val hasPdf = liveInvoice.status == SaleInvoiceStatus.ISSUED && !pdfUrl.isNullOrBlank()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= DETAIL_SPLIT_BREAKPOINT
        // Load the PDF only when there's an issued invoice. A render failure collapses back to a
        // single pane (the structured detail already shows everything), so we never split for it.
        val pdfState = if (hasPdf) rememberInvoicePdf(pdfUrl!!) else null
        val showBill = pdfState is PdfLoadState.Loading || pdfState is PdfLoadState.Ready

        when {
            // Wide + a bill to show → side by side: detail (left) · zoomable bill (right, bigger).
            showBill && wide -> Row(Modifier.fillMaxSize()) {
                DetailSheet(state, vm, Modifier.weight(0.42f).fillMaxHeight())
                VerticalDivider(color = colors.border)
                BillPane(pdfState!!, Modifier.weight(0.58f).fillMaxHeight())
            }
            // Narrow + a bill → stack: detail on top, bill below (each pane bounded, no nested scroll).
            showBill -> Column(Modifier.fillMaxSize()) {
                DetailSheet(state, vm, Modifier.fillMaxWidth().weight(1f))
                HorizontalDivider(color = colors.border)
                BillPane(pdfState!!, Modifier.fillMaxWidth().weight(1f))
            }
            // No bill yet (PENDING/FAILED) or render failed → single pane, just the detail.
            else -> DetailSheet(state, vm, Modifier.fillMaxSize())
        }
    }
}

/** The right pane: the zoomable invoice, or a spinner while it loads. */
@Composable
private fun BillPane(pdfState: PdfLoadState, modifier: Modifier) {
    val colors = AromexTheme.colors
    Box(modifier.background(colors.background)) {
        when (pdfState) {
            PdfLoadState.Loading -> PdfLoadingBox()
            is PdfLoadState.Ready -> ZoomablePdfViewer(pdfState.pages, Modifier.fillMaxSize())
            PdfLoadState.Failed -> Unit // never reached (Failed collapses to single pane)
        }
    }
}

/**
 * The left/single pane: the structured detail as a bordered "paper" sheet — masthead, the
 * app-drawn bill body, and the reused #77 invoice row. Scrolls internally, so it's safe inside a
 * weighted split cell (no nested-scroll crash).
 */
@Composable
private fun DetailSheet(state: SalesHistoryUiState, vm: SalesHistoryViewModel, modifier: Modifier) {
    val detail = state.detail ?: return
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val currency = state.currency
    val liveInvoice = state.detailInvoice.invoice

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(dims.space24),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().widthIn(max = 780.dp)
                .shadow(2.dp, RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(dims.radiusCard))
                .padding(dims.space24),
            verticalArrangement = Arrangement.spacedBy(dims.space20),
        ) {
            // ── Masthead: invoice number + status (left), date + cashier (right) ──
            SelectionContainer {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(strings(Strings.sales_history_bill_eyebrow), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                        Text(
                            liveInvoice.number ?: detail.invoice.number ?: strings(Strings.sales_history_no_invoice),
                            style = AromexTheme.typography.sectionTitle,
                            color = colors.textPrimary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (detail.isVoided) VoidedChip()
                            SyncChip(detail.syncStatus)
                            InvoiceChip(liveInvoice.status)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BillFact(strings(Strings.sales_history_detail_sold_at), formatDateTime(detail.createdAtMillis, state.timezone))
                        BillFact(strings(Strings.sales_history_detail_sold_by), state.sellerName ?: "—")
                    }
                }
            }

            if (detail.isVoided) VoidedBanner(detail.voidState.reason)

            HorizontalDivider(color = colors.border)

            SelectionContainer { BuiltBillBody(detail, vm, currency) }

            // An issued invoice's actions (Share / Download the PDF) live in the top bar; here we
            // only surface a non-issued invoice's status — "preparing" (PENDING) or a Retry (FAILED).
            val invoiceState = state.detailInvoice
            if (invoiceState.invoice.status != SaleInvoiceStatus.ISSUED) {
                HorizontalDivider(color = colors.border)
                DetailInvoiceStatus(invoiceState, vm::retryInvoice)
            }
        }
        Spacer(Modifier.height(dims.space20))
    }
}

/**
 * Minimal invoice status for a non-issued invoice in the detail view: a "preparing" spinner while
 * PENDING, or a reassuring message + Retry while FAILED. An issued invoice shows nothing here —
 * its Share/Download actions live in the top bar. (Replaces the old #77 View/Print/Copy row.)
 */
@Composable
private fun DetailInvoiceStatus(inv: DetailInvoiceState, onRetry: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Column(verticalArrangement = Arrangement.spacedBy(dims.space8)) {
        Text(strings(Strings.sales_invoice_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
        when (inv.invoice.status) {
            SaleInvoiceStatus.PENDING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dims.space8),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.brand)
                Text(strings(Strings.sales_invoice_preparing), style = AromexTheme.typography.body, color = colors.textSecondary)
            }
            SaleInvoiceStatus.FAILED -> {
                Text(strings(Strings.sales_invoice_failed), style = AromexTheme.typography.body, color = colors.textSecondary)
                if (inv.isRetrying) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dims.space8),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.brand)
                        Text(strings(Strings.sales_invoice_retrying), style = AromexTheme.typography.hint, color = colors.textTertiary)
                    }
                } else {
                    if (inv.retryError) {
                        Text(strings(Strings.sales_invoice_retry_error), style = AromexTheme.typography.hint, color = colors.error)
                    }
                    TextButton(
                        onClick = onRetry,
                        enabled = inv.canRetry,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Filled.Refresh, null, tint = colors.brand, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(strings(Strings.sales_invoice_retry), style = AromexTheme.typography.hint, color = colors.brand)
                    }
                }
            }
            SaleInvoiceStatus.ISSUED -> Unit
        }
    }
}

/**
 * The app-drawn bill body (bill-to → line table → totals → payment → note) — shown when there's
 * no issued PDF to render (PENDING/FAILED), or as the graceful fallback if the PDF can't be
 * downloaded/rendered. Mirrors the issued invoice's structure so the view is consistent either way.
 */
@Composable
private fun BuiltBillBody(
    detail: com.humblesolutions.aromex.model.SaleDetail,
    vm: SalesHistoryViewModel,
    currency: String,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dims.space20)) {
        // ── Bill to ──
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(strings(Strings.sales_history_bill_to), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
            Text(vm.customerNameOf(detail), style = AromexTheme.typography.body.copy(fontWeight = FontWeight.SemiBold), color = colors.textPrimary)
            if (detail.isWalkIn && !detail.buyerName.isNullOrBlank() && detail.buyerName != vm.customerNameOf(detail)) {
                Text(detail.buyerName!!, style = AromexTheme.typography.hint, color = colors.textSecondary)
            }
            if (!detail.buyerPhone.isNullOrBlank()) {
                Text(detail.buyerPhone!!, style = AromexTheme.typography.hint, color = colors.textTertiary)
            }
        }

        // ── Line items table ──
        Column {
            BillLineHeader()
            HorizontalDivider(color = colors.border)
            detail.lines.forEachIndexed { i, line ->
                BillLineRow(line = line, currency = currency, striped = i % 2 == 1)
            }
            HorizontalDivider(color = colors.border)
        }

        // ── Totals + payment: one right-aligned block, so cash / paid / balance line up directly
        // under the grand total (a bill reads top-down in a single money column). ──
        Row(Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.widthIn(min = 260.dp).fillMaxWidth(0.5f), verticalArrangement = Arrangement.spacedBy(dims.space8)) {
                BillTotalLine(strings(Strings.sales_totals_subtotal), MoneyFormat.format(detail.subtotal, currency))
                if (!Money.isZero(detail.saleDiscount)) {
                    BillTotalLine("− ${strings(Strings.sales_history_detail_sale_discount)}", MoneyFormat.format(detail.saleDiscount, currency))
                }
                detail.taxLines.forEach { tax ->
                    BillTotalLine("${tax.name} (${ratePercent(tax.rate)})", MoneyFormat.format(tax.amount, currency))
                }
                HorizontalDivider(color = colors.border)
                BillTotalLine(strings(Strings.sales_totals_grand_total), MoneyFormat.format(detail.grandTotal, currency), emphasize = true)

                // Payment split + paid + balance, aligned under the total.
                HorizontalDivider(color = colors.border)
                Text(strings(Strings.sales_history_detail_payments), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                if (!Money.isZero(detail.payment.cash)) BillTotalLine(strings(Strings.sales_checkout_cash, MoneyFormat.symbolOf(currency)), MoneyFormat.format(detail.payment.cash, currency))
                if (!Money.isZero(detail.payment.card)) BillTotalLine(strings(Strings.sales_checkout_card, MoneyFormat.symbolOf(currency)), MoneyFormat.format(detail.payment.card, currency))
                if (!Money.isZero(detail.payment.bank)) BillTotalLine(strings(Strings.sales_checkout_bank, MoneyFormat.symbolOf(currency)), MoneyFormat.format(detail.payment.bank, currency))
                BillTotalLine(strings(Strings.sales_totals_paid), MoneyFormat.format(detail.amountPaid, currency))
                BillTotalLine(
                    strings(Strings.sales_totals_balance),
                    MoneyFormat.format(detail.balanceRemaining, currency),
                    emphasize = true,
                    valueColor = if (Money.isZero(detail.balanceRemaining)) colors.success else colors.warning,
                )
            }
        }

        if (!detail.note.isNullOrBlank()) {
            HorizontalDivider(color = colors.border)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(strings(Strings.sales_history_detail_note), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
                Text(detail.note!!, style = AromexTheme.typography.body, color = colors.textSecondary)
            }
        }
    }
}

/** A right-aligned header fact (Date / Sold by) in the bill masthead. */
@Composable
private fun BillFact(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, style = AromexTheme.typography.hint.copy(fontSize = 10.sp), color = AromexTheme.colors.textTertiary)
        Text(value, style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.Medium), color = AromexTheme.colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun BillLineHeader() {
    val colors = AromexTheme.colors
    @Composable
    fun HeaderCell(key: String, modifier: Modifier, end: Boolean = false) = Text(
        strings(key),
        modifier = modifier.padding(horizontal = 4.dp),
        style = AromexTheme.typography.hint.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell(Strings.sales_history_col_item, Modifier.weight(BILL_ITEM))
        HeaderCell(Strings.sales_history_col_imei, Modifier.weight(BILL_IMEI))
        HeaderCell(Strings.sales_history_detail_line_list, Modifier.weight(BILL_LIST), end = true)
        HeaderCell(Strings.sales_history_detail_line_discount, Modifier.weight(BILL_DISC), end = true)
        HeaderCell(Strings.sales_history_detail_line_unit, Modifier.weight(BILL_UNIT), end = true)
        HeaderCell(Strings.sales_history_col_amount, Modifier.weight(BILL_AMOUNT), end = true)
    }
}

@Composable
private fun BillLineRow(line: com.humblesolutions.aromex.model.SaleRecordLine, currency: String, striped: Boolean) {
    val colors = AromexTheme.colors
    val inventory = line as? com.humblesolutions.aromex.model.SaleRecordLine.Inventory
    @Composable
    fun Cell(text: String, modifier: Modifier, end: Boolean = false, emphasize: Boolean = false) = Text(
        text,
        modifier = modifier.padding(horizontal = 4.dp),
        style = AromexTheme.typography.hint.copy(fontSize = 12.sp, fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal),
        color = if (emphasize) colors.textPrimary else colors.textSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (end) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (striped) colors.surfaceAlt.copy(alpha = 0.5f) else Color.Transparent)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cell(
            when (line) {
                is com.humblesolutions.aromex.model.SaleRecordLine.Inventory -> line.label
                is com.humblesolutions.aromex.model.SaleRecordLine.Custom -> line.name
            },
            Modifier.weight(BILL_ITEM), emphasize = true,
        )
        Cell(inventory?.imei ?: "—", Modifier.weight(BILL_IMEI))
        Cell(inventory?.let { MoneyFormat.format(it.listPrice, currency) } ?: "—", Modifier.weight(BILL_LIST), end = true)
        Cell(if (Money.isZero(line.lineDiscount)) "—" else MoneyFormat.format(line.lineDiscount, currency), Modifier.weight(BILL_DISC), end = true)
        Cell(MoneyFormat.format(line.unitPrice, currency), Modifier.weight(BILL_UNIT), end = true)
        Cell(MoneyFormat.format(line.netPrice, currency), Modifier.weight(BILL_AMOUNT), end = true, emphasize = true)
    }
}

@Composable
private fun BillTotalLine(label: String, value: String, emphasize: Boolean = false, valueColor: Color = AromexTheme.colors.textPrimary) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = if (emphasize) AromexTheme.typography.body.copy(fontWeight = FontWeight.SemiBold) else AromexTheme.typography.hint,
            color = if (emphasize) AromexTheme.colors.textPrimary else AromexTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            value,
            style = if (emphasize) AromexTheme.typography.body.copy(fontWeight = FontWeight.Bold) else AromexTheme.typography.hint,
            color = valueColor,
            maxLines = 1,
        )
    }
}

/** A tax rate decimal string → a percent label, e.g. "0.05" → "5%", "0.0725" → "7.25%". */
private fun ratePercent(rate: String): String {
    val pct = Money.multiplyRate("100", rate)
    val trimmed = if (pct.contains('.')) pct.trimEnd('0').trimEnd('.') else pct
    return "$trimmed%"
}

// ── Filter dialog ────────────────────────────────────────────────────────────

@Composable
private fun FilterDialog(
    initial: SalesHistoryFilter,
    onApply: (SalesHistoryFilter) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    var customer by remember { mutableStateOf(initial.customerName) }
    var imei by remember { mutableStateOf(initial.imei) }
    var invoice by remember { mutableStateOf(initial.invoiceNumber) }
    var from by remember { mutableStateOf(initial.dateFromMillis?.let { millisToDateString(it) } ?: "") }
    var to by remember { mutableStateOf(initial.dateToMillis?.let { millisToDateString(it) } ?: "") }
    var onlyBalance by remember { mutableStateOf(initial.onlyWithBalance) }
    var dateError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.75f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 380.dp, max = 460.dp).fillMaxWidth(0.9f)
                .heightIn(max = 640.dp)
                .shadow(24.dp, RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard)).background(colors.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(dims.space20),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings(Strings.sales_history_filter_title), style = AromexTheme.typography.sectionTitle, color = colors.textPrimary, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Icon(Icons.Filled.Close, strings(Strings.sales_close_cd), tint = colors.textTertiary)
                }
            }
            HorizontalDivider(color = colors.border)
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(dims.space20),
                verticalArrangement = Arrangement.spacedBy(dims.space16),
            ) {
                FilterField(strings(Strings.sales_history_filter_customer), customer, strings(Strings.sales_history_filter_customer_ph)) { customer = it }
                FilterField(strings(Strings.sales_history_filter_imei), imei, strings(Strings.sales_history_filter_imei_ph), numeric = true) { imei = it }
                FilterField(strings(Strings.sales_history_filter_invoice), invoice, strings(Strings.sales_history_filter_invoice_ph)) { invoice = it }
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
                    Box(Modifier.weight(1f)) { FilterField(strings(Strings.sales_history_filter_date_from), from, "2026-01-01") { from = it; dateError = false } }
                    Box(Modifier.weight(1f)) { FilterField(strings(Strings.sales_history_filter_date_to), to, "2026-12-31") { to = it; dateError = false } }
                }
                if (dateError) {
                    Text(strings(Strings.sales_history_filter_date_error), style = AromexTheme.typography.hint, color = colors.error)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { onlyBalance = !onlyBalance },
                ) {
                    Checkbox(
                        checked = onlyBalance,
                        onCheckedChange = { onlyBalance = it },
                        colors = CheckboxDefaults.colors(checkedColor = colors.brand),
                    )
                    Text(strings(Strings.sales_history_filter_balance), style = AromexTheme.typography.body, color = colors.textPrimary)
                }
            }
            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(dims.space16),
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClear, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Text(strings(Strings.sales_history_filter_clear), color = colors.textSecondary)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                    Text(strings(Strings.sales_history_filter_cancel), color = colors.textSecondary)
                }
                androidx.compose.material3.Button(
                    onClick = {
                        val fromMillis = parseDateStart(from)
                        val toMillis = parseDateEnd(to)
                        if ((from.isNotBlank() && fromMillis == null) || (to.isNotBlank() && toMillis == null)) {
                            dateError = true
                        } else {
                            onApply(
                                SalesHistoryFilter(
                                    customerName = customer.trim(),
                                    imei = imei.trim(),
                                    invoiceNumber = invoice.trim(),
                                    dateFromMillis = fromMillis,
                                    dateToMillis = toMillis,
                                    onlyWithBalance = onlyBalance,
                                ),
                            )
                        }
                    },
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(strings(Strings.sales_history_filter_apply), softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun FilterField(label: String, value: String, placeholder: String, numeric: Boolean = false, onValueChange: (String) -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp)
                .clip(RoundedCornerShape(dims.radiusField)).background(colors.surfaceAlt)
                .border(1.dp, if (focused) colors.brand else colors.border, RoundedCornerShape(dims.radiusField))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(placeholder, style = AromexTheme.typography.body.copy(fontSize = 13.sp), color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value,
                    onValueChange = { if (numeric) onValueChange(it.filter(Char::isDigit)) else onValueChange(it) },
                    singleLine = true,
                    keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                    textStyle = AromexTheme.typography.body.copy(fontSize = 13.sp, color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.brand),
                    modifier = Modifier.fillMaxWidth().onFocusChangedCompat { focused = it },
                )
            }
        }
    }
}

// ── Void a sale (ticket #85) ───────────────────────────────────────────────────

/** A calm, read-only banner on a voided sale's detail sheet, naming the reason if one is stored. */
@Composable
private fun VoidedBanner(reason: String?) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(dims.radiusField))
            .background(colors.error.copy(alpha = 0.10f))
            .padding(horizontal = dims.space12, vertical = dims.space8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dims.space8),
    ) {
        Icon(Icons.Filled.Block, null, tint = colors.error, modifier = Modifier.size(16.dp))
        Column {
            Text(strings(Strings.sales_history_voided_note), style = AromexTheme.typography.hint, color = colors.error)
            if (!reason.isNullOrBlank()) {
                Text(reason, style = AromexTheme.typography.hint, color = colors.textTertiary)
            }
        }
    }
}

/**
 * The Void confirmation (ticket #85): a themed, deliberately-heavy dialog. It states plainly what a
 * void reverses, **requires a typed reason** (Confirm stays disabled until non-blank), and is *not*
 * dismissible by accident — clicking the scrim does nothing; only Cancel or the OS closes it. The
 * destructive Confirm uses the error color, and locks into a spinner while the void runs.
 */
@Composable
private fun VoidSaleDialog(
    isVoiding: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var reason by remember { mutableStateOf("") }
    val canConfirm = reason.isNotBlank() && !isVoiding

    Box(
        // Scrim is intentionally inert (no onClick): a void must be an explicit choice.
        modifier = Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 380.dp, max = 460.dp).fillMaxWidth(0.9f)
                .heightIn(max = 640.dp)
                .shadow(24.dp, RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard)).background(colors.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(dims.space20),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dims.space8),
            ) {
                Icon(Icons.Filled.Block, null, tint = colors.error)
                Text(
                    strings(Strings.sales_history_void_dialog_title),
                    style = AromexTheme.typography.sectionTitle,
                    color = colors.textPrimary,
                )
            }
            HorizontalDivider(color = colors.border)
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(dims.space20),
                verticalArrangement = Arrangement.spacedBy(dims.space16),
            ) {
                Text(
                    strings(Strings.sales_history_void_dialog_body),
                    style = AromexTheme.typography.body,
                    color = colors.textSecondary,
                )
                FilterField(
                    label = strings(Strings.sales_history_void_reason_label),
                    value = reason,
                    placeholder = strings(Strings.sales_history_void_reason_ph),
                ) { reason = it }
                error?.let {
                    Text(it, style = AromexTheme.typography.hint, color = colors.error)
                }
            }
            HorizontalDivider(color = colors.border)
            Row(
                modifier = Modifier.fillMaxWidth().padding(dims.space16),
                horizontalArrangement = Arrangement.spacedBy(dims.space12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = onCancel,
                    enabled = !isVoiding,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(strings(Strings.sales_history_void_cancel), color = colors.textSecondary)
                }
                androidx.compose.material3.Button(
                    onClick = { if (canConfirm) onConfirm(reason.trim()) },
                    enabled = canConfirm,
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = colors.error,
                        contentColor = Color.White,
                        disabledContainerColor = colors.error.copy(alpha = 0.4f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f),
                    ),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    if (isVoiding) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(dims.space8))
                        Text(strings(Strings.sales_history_void_in_progress), softWrap = false)
                    } else {
                        Text(strings(Strings.sales_history_void_confirm), softWrap = false)
                    }
                }
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Below this content width the table reflows to stacked cards so nothing clips or side-scrolls. */
private val COMPACT_BREAKPOINT = 900.dp
/** At/above this width the detail screen splits side-by-side (detail · bill); below it, stacks. */
private val DETAIL_SPLIT_BREAKPOINT = 1000.dp
private const val PREFETCH_THRESHOLD = 5

/** Modifier.onFocusChanged wrapper kept local so the field composables stay terse. */
private fun Modifier.onFocusChangedCompat(onChanged: (Boolean) -> Unit): Modifier =
    this.onFocusChanged { onChanged(it.isFocused) }

private val DATE_TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

/** Formats an epoch-millis instant in the shop's timezone; falls back to UTC on a bad zone id. */
private fun formatDateTime(millis: Long, timezone: String): String {
    if (millis <= 0L) return "—"
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
    return Instant.ofEpochMilli(millis).atZone(zone).format(DATE_TIME_FMT)
}

private fun millisToDateString(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate().toString()

/** Parses "YYYY-MM-DD" → start-of-day UTC millis (inclusive lower bound); null if malformed. */
private fun parseDateStart(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return runCatching {
        java.time.LocalDate.parse(t).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    }.getOrNull()
}

/** Parses "YYYY-MM-DD" → end-of-day UTC millis (inclusive upper bound); null if malformed. */
private fun parseDateEnd(text: String): Long? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return runCatching {
        java.time.LocalDate.parse(t).plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() - 1
    }.getOrNull()
}
