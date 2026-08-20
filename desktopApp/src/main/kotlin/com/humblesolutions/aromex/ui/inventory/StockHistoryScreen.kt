package com.humblesolutions.aromex.ui.inventory

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.BatchReversalBlock
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.StockBatch
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.components.DesktopSection
import androidx.compose.ui.zIndex
import com.humblesolutions.aromex.ui.components.CollapsedSidebarWidth
import com.humblesolutions.aromex.ui.components.ExpandedSidebarWidth
import com.humblesolutions.aromex.ui.components.NavSidebar
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.money.BodyCell
import com.humblesolutions.aromex.ui.money.CellTooltip
import com.humblesolutions.aromex.ui.money.DateRangeChip
import com.humblesolutions.aromex.ui.money.EmptyBlock
import com.humblesolutions.aromex.ui.money.Hairline
import com.humblesolutions.aromex.ui.money.HeaderCell
import com.humblesolutions.aromex.ui.money.ReverseButton
import com.humblesolutions.aromex.ui.money.SearchBox
import com.humblesolutions.aromex.ui.money.SortableHeaderCell
import com.humblesolutions.aromex.ui.money.SyncDot
import com.humblesolutions.aromex.ui.money.ToolbarChip
import com.humblesolutions.aromex.ui.money.FieldShell
import com.humblesolutions.aromex.ui.money.ShellTextField
import com.humblesolutions.aromex.ui.money.timeFormat
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.MoneyFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Column widths, mirroring the money ledger so the two tables read as one system.
private val COL_DOT = 26.dp
private val COL_EXPAND = 30.dp
private const val COL_DATE = 1.1f
private const val COL_PARTY = 1.6f
private const val COL_UNITS = 0.6f
private const val COL_COST = 1.0f
private const val COL_PAID = 1.0f
private const val COL_OWING = 1.0f
private val COL_ACTION = 92.dp
private val ROW_HEIGHT = 42.dp
private val HEADER_HEIGHT = 36.dp

/** Below this the "on account" column earns less than the space it takes. */
private val OWING_BREAKPOINT = 1180.dp

private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

/**
 * Stock History (ticket #106) — every batch of stock taken in, and the one place a batch can be
 * reversed.
 *
 * Built from the money ledger's own table parts rather than a copy of them, so the two screens
 * behave identically: same sortable headers, same hairlines, same search and date-range chips,
 * same reverse button.
 */
@Composable
fun StockHistoryScreen(
    state: StockHistoryUiState,
    vm: StockHistoryViewModel,
    session: UserSession?,
    onNavigateToEntities: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToSalesHistory: () -> Unit = {},
    onNavigateToMoney: () -> Unit = {},
    onNavigateToCommissionRules: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
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
                selectedSection = DesktopSection.STOCK_HISTORY,
                session = session,
                interactionSource = sidebarSrc,
                onNavigateToEntities = onNavigateToEntities,
                onNavigateToInventory = onNavigateToInventory,
                onNavigateToSales = onNavigateToSales,
                onNavigateToSalesHistory = onNavigateToSalesHistory,
                onNavigateToMoney = onNavigateToMoney,
                onNavigateToCommissionRules = onNavigateToCommissionRules,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStockHistory = {},
                onSignOutRequest = onSignOut,
            )
            Box(Modifier.fillMaxSize().padding(start = CollapsedSidebarWidth).hoverable(contentSrc)) {
                StockHistoryContent(state, vm)
            }
        }
    }

    state.pendingReversal?.let { pending -> ReversalDialog(pending, state, vm) }
}

@Composable
private fun StockHistoryContent(state: StockHistoryUiState, vm: StockHistoryViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    if (state.noAccess) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings(Strings.stock_history_no_access), style = typography.body, color = colors.textSecondary)
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(dims.space24)) {
        Text(strings(Strings.stock_history_title), style = typography.screenTitle, color = colors.textPrimary)
        Spacer(Modifier.height(2.dp))
        Text(strings(Strings.stock_history_subtitle), style = typography.hint, color = colors.textTertiary)
        Spacer(Modifier.height(dims.space16))

        state.error?.let { message ->
            ErrorStrip(message = state.errorKey?.let { strings(it) } ?: message, onDismiss = vm::clearError)
            Spacer(Modifier.height(dims.space12))
        }

        BatchTable(state, vm, Modifier.weight(1f))
    }
}

@Composable
private fun ErrorStrip(message: String, onDismiss: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(dims.radiusCard))
            .background(colors.error.copy(alpha = 0.1f))
            .padding(horizontal = dims.space16, vertical = dims.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = AromexTheme.typography.hint, color = colors.error, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
            Text(strings(Strings.offline_dismiss), color = colors.error)
        }
    }
}

// ── table ────────────────────────────────────────────────────────────────────

@Composable
private fun BatchTable(state: StockHistoryUiState, vm: StockHistoryViewModel, modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val rows = state.visibleBatches

    Column(modifier.fillMaxWidth()) {
        Toolbar(state, vm, rows.size)
        Spacer(Modifier.height(dims.space8))

        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(dims.radiusCard)),
        ) {
            val showOwing = maxWidth >= OWING_BREAKPOINT

            Column(Modifier.fillMaxSize()) {
                TableHeader(state, vm, showOwing)
                HorizontalDivider(color = colors.border)
                when {
                    state.isLoading && rows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.brand)
                    }
                    rows.isEmpty() && state.isFiltered -> EmptyBlock(
                        title = strings(Strings.stock_history_empty_filtered),
                        hint = strings(Strings.stock_history_empty_filtered_hint),
                    )
                    rows.isEmpty() -> EmptyBlock(
                        title = strings(Strings.stock_history_empty),
                        hint = strings(Strings.stock_history_empty_hint),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, b -> b.purchaseId }) { index, batch ->
                            BatchRow(batch, state, vm, striped = index % 2 == 1, showOwing = showOwing)
                        }
                        // Older history is a press away rather than invisible. Deliberately a
                        // button, not infinite scroll: widening the live window re-runs the query,
                        // so it should happen when someone asks for it.
                        if (state.mayHaveMore) {
                            item(key = "load_more") { LoadMoreFooter(state, vm) }
                        }
                    }
                }
            }
        }
    }
}

/** The end of the loaded window: what you're seeing, and how to see further back. */
@Composable
private fun LoadMoreFooter(state: StockHistoryUiState, vm: StockHistoryViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Column(
        Modifier.fillMaxWidth().padding(vertical = dims.space12),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.isLoadingMore) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp, color = colors.brand)
        } else {
            ToolbarChip(strings(Strings.stock_history_load_more), onClick = vm::loadMore)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            strings(Strings.stock_history_window_hint, state.batches.size.toString()),
            style = AromexTheme.typography.hint.copy(fontSize = 11.sp),
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun Toolbar(state: StockHistoryUiState, vm: StockHistoryViewModel, shown: Int) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(strings(Strings.stock_history_recent), style = typography.sectionTitle, color = colors.textPrimary)
        Spacer(Modifier.width(dims.space8))
        // "12 of 40" while filtered, plain count otherwise — so a filter can never look like data loss.
        Text(
            if (state.isFiltered) "$shown ${strings(Strings.money_of)} ${state.batches.size}" else "$shown",
            style = typography.hint,
            color = colors.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(dims.radiusPill))
                .background(colors.surfaceAlt)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        StatusFilterChips(state.statusFilter, vm::setStatusFilter)
        Spacer(Modifier.width(dims.space8))
        SearchBox(state.searchQuery, vm::setSearch)
        Spacer(Modifier.width(dims.space8))
        DateRangeChip(state.rangeFrom, state.rangeTo) { f, t -> vm.setDateRange(f, t) }
        if (state.isFiltered) {
            Spacer(Modifier.width(dims.space8))
            ToolbarChip(strings(Strings.money_clear_filters), onClick = vm::clearFilters)
        }
    }
}

/**
 * Active / Reversed / All. Reversed batches are out of the default view — they describe stock that
 * isn't there — but they stay one click away, because "what happened to that purchase" is exactly
 * the question this screen exists to answer.
 */
@Composable
private fun StatusFilterChips(selected: BatchStatusFilter, onSelect: (BatchStatusFilter) -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val shape = RoundedCornerShape(dims.radiusPill)

    Row(
        Modifier.clip(shape).background(colors.surfaceAlt).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(
            BatchStatusFilter.ACTIVE to Strings.stock_history_filter_active,
            BatchStatusFilter.REVERSED to Strings.stock_history_filter_reversed,
            BatchStatusFilter.ALL to Strings.stock_history_filter_all,
        ).forEach { (filter, key) ->
            val active = selected == filter
            Box(
                Modifier
                    .clip(shape)
                    .background(if (active) colors.surface else Color.Transparent)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    strings(key),
                    style = AromexTheme.typography.hint,
                    color = if (active) colors.brand else colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TableHeader(state: StockHistoryUiState, vm: StockHistoryViewModel, showOwing: Boolean) {
    val colors = AromexTheme.colors
    val style = AromexTheme.typography.hint.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.7.sp,
    )

    Row(
        Modifier.fillMaxWidth().height(HEADER_HEIGHT).background(colors.surfaceAlt),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The status dot's column: no label, it'd be noise above a 7px circle.
        Box(Modifier.width(COL_DOT).fillMaxHeight()) { Hairline(Modifier.align(Alignment.CenterEnd)) }
        Box(Modifier.width(COL_EXPAND).fillMaxHeight())
        SortableHeaderCell(
            text = strings(Strings.stock_history_col_date),
            weight = COL_DATE,
            style = style,
            active = state.sortBy == BatchSortColumn.DATE,
            ascending = state.sortAscending,
            onClick = { vm.toggleSort(BatchSortColumn.DATE) },
        )
        HeaderCell(strings(Strings.stock_history_col_party), COL_PARTY, style)
        SortableHeaderCell(
            text = strings(Strings.stock_history_col_units),
            weight = COL_UNITS,
            style = style,
            active = state.sortBy == BatchSortColumn.UNITS,
            ascending = state.sortAscending,
            align = TextAlign.End,
            onClick = { vm.toggleSort(BatchSortColumn.UNITS) },
        )
        SortableHeaderCell(
            text = strings(Strings.stock_history_col_cost),
            weight = COL_COST,
            style = style,
            active = state.sortBy == BatchSortColumn.COST,
            ascending = state.sortAscending,
            align = TextAlign.End,
            onClick = { vm.toggleSort(BatchSortColumn.COST) },
        )
        HeaderCell(strings(Strings.stock_history_col_paid), COL_PAID, style, align = TextAlign.End)
        if (showOwing) {
            HeaderCell(strings(Strings.stock_history_col_owing), COL_OWING, style, align = TextAlign.End)
        }
        if (state.isAdmin) Box(Modifier.width(COL_ACTION))
    }
}

@Composable
private fun BatchRow(
    batch: StockBatch,
    state: StockHistoryUiState,
    vm: StockHistoryViewModel,
    striped: Boolean,
    showOwing: Boolean,
) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    val expanded = state.expandedBatchId == batch.purchaseId
    val busy = state.submittingReversalFor == batch.purchaseId ||
        state.preparingReversalFor == batch.purchaseId ||
        batch.isReversing

    Column {
        // One SelectionContainer around the whole row: a press-and-drag selects the batch's date,
        // party and figures together; the expand chevron and Reverse button still click normally.
        SelectionContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT)
                .hoverable(src)
                .background(
                    when {
                        hovered -> colors.brand.copy(alpha = 0.05f)
                        striped -> colors.surfaceAlt.copy(alpha = 0.45f)
                        else -> Color.Transparent
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(COL_DOT).fillMaxHeight(), contentAlignment = Alignment.Center) {
                SyncDot(batch.syncStatus)
                Hairline(Modifier.align(Alignment.CenterEnd))
            }
            // Expand chevron — the phones a batch brought in are its detail, not its summary.
            Box(
                Modifier.width(COL_EXPAND).fillMaxHeight()
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { vm.toggleExpanded(batch) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
            BodyCell(COL_DATE) {
                Column {
                    Text(
                        dayFormat.format(Date(batch.createdAt)),
                        style = typography.hint.copy(fontSize = 12.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        timeFormat.format(Date(batch.createdAt)),
                        style = typography.hint.copy(fontSize = 10.sp),
                        color = colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
            BodyCell(COL_PARTY) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.partyName(batch.partyEntityId),
                        style = typography.body.copy(fontSize = 14.sp),
                        color = if (batch.isReversed) colors.textTertiary else colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StateTag(batch)
                }
            }
            BodyCell(COL_UNITS) { NumberCell(batch.unitCount.toString(), batch.isReversed) }
            BodyCell(COL_COST) {
                NumberCell(MoneyFormat.format(batch.totalCost, state.currency), batch.isReversed)
            }
            BodyCell(COL_PAID, showHairline = showOwing || state.isAdmin) {
                NumberCell(MoneyFormat.format(batch.paid, state.currency), batch.isReversed)
            }
            if (showOwing) {
                BodyCell(COL_OWING, showHairline = state.isAdmin) {
                    NumberCell(MoneyFormat.format(batch.balanceAdded, state.currency), batch.isReversed)
                }
            }
            if (state.isAdmin) {
                Box(Modifier.width(COL_ACTION).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (!batch.isReversed) {
                        // Excluded from the row's SelectionContainer — it's a button, not text.
                        DisableSelection {
                            ReverseButton(
                                busy = busy,
                                enabled = state.submittingReversalFor == null &&
                                    state.preparingReversalFor == null &&
                                    !batch.isReversing,
                                onClick = { vm.askReverse(batch) },
                            )
                        }
                    }
                }
            }
        }
        }
        if (expanded) BatchUnits(state)
        HorizontalDivider(color = colors.border.copy(alpha = 0.45f))
    }
}

/**
 * A right-aligned figure, dimmed when the batch it belongs to has been reversed. Selection is
 * handled by the SelectionContainer around the whole BatchRow, so this stays a plain Text.
 */
@Composable
private fun NumberCell(text: String, dimmed: Boolean) {
    Text(
        text,
        style = AromexTheme.typography.body.copy(fontSize = 14.sp, textAlign = TextAlign.End),
        color = if (dimmed) AromexTheme.colors.textTertiary else AromexTheme.colors.textPrimary,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Reversed / reversing / failed, as a small pill next to the party. Absent on a normal batch. */
@Composable
private fun StateTag(batch: StockBatch) {
    val colors = AromexTheme.colors
    val (label, tint) = when {
        batch.isReversed -> strings(Strings.stock_history_reversed_tag) to colors.textTertiary
        batch.isReversing -> strings(Strings.stock_history_reversing_tag) to colors.warning
        batch.reversalFailed -> strings(Strings.stock_history_reverse_failed_tag) to colors.error
        else -> return
    }
    Spacer(Modifier.width(6.dp))
    Box(
        Modifier
            .clip(RoundedCornerShape(AromexTheme.dimensions.radiusPill))
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(label, style = AromexTheme.typography.hint.copy(fontSize = 10.sp), color = tint, maxLines = 1)
    }
}

/** The phones a batch brought in, shown under the expanded row. */
@Composable
private fun BatchUnits(state: StockHistoryUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    Column(
        Modifier.fillMaxWidth()
            .background(colors.surfaceAlt.copy(alpha = 0.4f))
            .padding(start = COL_DOT + COL_EXPAND, end = dims.space16, top = dims.space8, bottom = dims.space12),
    ) {
        Text(
            strings(Strings.stock_history_units_title),
            style = typography.hint.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textTertiary,
        )
        Spacer(Modifier.height(4.dp))
        when {
            state.isLoadingUnits -> CircularProgressIndicator(
                Modifier.size(16.dp),
                strokeWidth = 1.5.dp,
                color = colors.brand,
            )
            state.expandedUnits.isEmpty() -> Text(
                strings(Strings.stock_history_units_empty),
                style = typography.hint,
                color = colors.textTertiary,
            )
            else -> Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                state.expandedUnits.forEach { unit -> UnitRow(unit, state) }
            }
        }
    }
}

@Composable
private fun UnitRow(unit: Serial, state: StockHistoryUiState) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    val (statusLabel, statusTint) = when {
        !unit.isActive -> strings(Strings.stock_history_unit_removed) to colors.textTertiary
        unit.status != SerialStatus.IN_STOCK -> strings(Strings.stock_history_unit_sold) to colors.warning
        else -> strings(Strings.stock_history_unit_in_stock) to colors.success
    }
    // One SelectionContainer around the whole unit row: a press-and-drag selects the IMEI, label
    // and cost together.
    SelectionContainer {
    Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(statusTint))
        Spacer(Modifier.width(8.dp))
        Text(
            unit.imei,
            style = typography.hint.copy(fontSize = 12.sp),
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.width(150.dp),
        )
        Text(
            state.unitLabel(unit),
            style = typography.hint,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            MoneyFormat.format(unit.cost, state.currency),
            style = typography.hint,
            color = colors.textSecondary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(90.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(statusLabel, style = typography.hint.copy(fontSize = 11.sp), color = statusTint, maxLines = 1)
    }
    }
}

// ── reversal confirmation ────────────────────────────────────────────────────

/**
 * The confirmation. It states what will actually happen — how many phones leave, what comes off the
 * party's balance, what money comes back — or exactly why it can't happen, rather than presenting a
 * disabled button and letting the user guess.
 */
@Composable
private fun ReversalDialog(pending: PendingReversal, state: StockHistoryUiState, vm: StockHistoryViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val batch = pending.batch
    var reasonFocused by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = vm::dismissReversal,
        containerColor = colors.surface,
        title = {
            Text(
                strings(Strings.stock_history_reverse_title),
                style = typography.sectionTitle,
                color = colors.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(dims.space8),
            ) {
                val block = pending.block
                if (block != null) {
                    Text(blockMessage(block, batch), style = typography.body, color = colors.error)
                } else {
                    Text(
                        strings(Strings.stock_history_reverse_body, batch.unitCount.toString()),
                        style = typography.body,
                        color = colors.textSecondary,
                    )
                    Text(
                        strings(
                            Strings.stock_history_reverse_ledger,
                            MoneyFormat.format(batch.balanceAdded, state.currency),
                            state.partyName(batch.partyEntityId),
                        ),
                        style = typography.body,
                        color = colors.textSecondary,
                    )
                    if (!com.humblesolutions.aromex.util.Money.isZero(batch.paid)) {
                        Text(
                            strings(Strings.stock_history_reverse_refund, MoneyFormat.format(batch.paid, state.currency)),
                            style = typography.body,
                            color = colors.textSecondary,
                        )
                    }
                    Text(
                        strings(Strings.stock_history_reverse_commission),
                        style = typography.hint,
                        color = colors.textTertiary,
                    )
                    Spacer(Modifier.height(dims.space4))
                    Text(
                        strings(Strings.stock_history_reverse_reason),
                        style = typography.fieldLabel,
                        color = colors.textTertiary,
                    )
                    FieldShell(focused = reasonFocused) {
                        ShellTextField(
                            value = pending.reason,
                            onValueChange = vm::setReversalReason,
                            placeholder = strings(Strings.stock_history_reverse_reason_hint),
                            onFocusChanged = { reasonFocused = it },
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (pending.block == null) {
                TextButton(
                    onClick = vm::confirmReverse,
                    enabled = pending.canConfirm,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text(
                        strings(Strings.stock_history_reverse_confirm),
                        color = if (pending.canConfirm) colors.error else colors.disabled,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = vm::dismissReversal, modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                Text(strings(Strings.stock_history_reverse_cancel), color = colors.textSecondary)
            }
        },
    )
}

@Composable
private fun blockMessage(block: BatchReversalBlock, batch: StockBatch): String = when (block) {
    BatchReversalBlock.AlreadyReversed -> strings(Strings.stock_history_block_already)
    BatchReversalBlock.InFlight -> strings(Strings.stock_history_block_inflight)
    BatchReversalBlock.UnitsUnknown -> strings(Strings.stock_history_block_unknown)
    is BatchReversalBlock.UnitsSold ->
        strings(Strings.stock_history_block_sold, block.imeis.size.toString(), batch.unitCount.toString())
    is BatchReversalBlock.UnitsRemoved ->
        strings(Strings.stock_history_block_removed, block.imeis.size.toString(), batch.unitCount.toString())
}
