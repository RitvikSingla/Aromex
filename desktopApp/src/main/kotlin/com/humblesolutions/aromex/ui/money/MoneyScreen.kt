package com.humblesolutions.aromex.ui.money

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.humblesolutions.aromex.ui.components.AromexDialog
import com.humblesolutions.aromex.ui.components.BalanceFreshness
import java.util.Calendar
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.MoneyAccountRef
import com.humblesolutions.aromex.model.MoneyDirection
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.components.DesktopSection
import androidx.compose.ui.zIndex
import com.humblesolutions.aromex.ui.components.CollapsedSidebarWidth
import com.humblesolutions.aromex.ui.components.ExpandedSidebarWidth
import com.humblesolutions.aromex.ui.components.NavSidebar
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.usecase.MoneyEntryError
import com.humblesolutions.aromex.util.MoneyFormat
import java.util.Date
import java.util.Locale

/**
 * The money-movement screen (ticket #90) — the rebuilt transactions screen.
 *
 * From / To / Amount / Date / Note, where From and To are one picker holding every party plus the
 * shop's own Cash and Bank. What you pick **is** the ledger account — no "Myself" abstraction
 * hiding which account moved, as the legacy screen had.
 */
@Composable
fun MoneyScreen(
    state: MoneyUiState,
    vm: MoneyViewModel,
    session: UserSession?,
    onNavigateToEntities: () -> Unit,
    onNavigateToInventory: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToSalesHistory: () -> Unit = {},
    onNavigateToCommissionRules: () -> Unit = {},
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
                selectedSection = DesktopSection.MONEY,
                session = session,
                interactionSource = sidebarSrc,
                onNavigateToEntities = onNavigateToEntities,
                onNavigateToInventory = onNavigateToInventory,
                onNavigateToSales = onNavigateToSales,
                onNavigateToSalesHistory = onNavigateToSalesHistory,
                onNavigateToMoney = {},
                onNavigateToCommissionRules = onNavigateToCommissionRules,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStockHistory = onNavigateToStockHistory,
                onSignOutRequest = onSignOut,
            )
            Box(Modifier.fillMaxSize().padding(start = CollapsedSidebarWidth).hoverable(contentSrc)) {
                MoneyContent(state, vm)
            }
        }
    }
}

@Composable
private fun MoneyContent(state: MoneyUiState, vm: MoneyViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    if (state.noAccess) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings(Strings.money_no_access), style = typography.body, color = colors.textSecondary)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = dims.space24, vertical = dims.space20),
        verticalArrangement = Arrangement.spacedBy(dims.space16),
    ) {
        // The form scrolls on its own rather than squeezing the table off the bottom. `fill = false`
        // lets it take only the height it needs on a tall window, and caps it on a short one — where
        // it previously clipped, because a Column with no scroll simply runs off the screen.
        // It can't share one scroll with the table below: that table is a LazyColumn, and a lazy
        // list inside a vertical scroll is measured with infinite height and crashes.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(dims.space16),
        ) {
            // ── header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings(Strings.money_title), style = typography.screenTitle, color = colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(strings(Strings.money_subtitle), style = typography.hint, color = colors.textTertiary)
                }
                BalanceFreshness(
                    refreshedAt = state.balancesRefreshedAt,
                    isLoading = state.isLoadingBalances,
                    onRefresh = vm::refreshBalances,
                )
            }

            EntryCard(state, vm)

            NoticeStrip(state)
        }

        // Always keeps a usable slice of the window, so the table can't be squeezed to nothing.
        LedgerTable(state, vm, Modifier.weight(1f).heightIn(min = 180.dp))
    }

    // Reversing posts real money back. It asks first, and says exactly what it will undo.
    state.pendingReversal?.let { entry ->
        AromexDialog(
            title = strings(Strings.money_reverse_title),
            message = strings(Strings.money_reverse_body)
                .replace("{amount}", MoneyFormat.format(entry.amount, state.currency))
                .replace("{from}", state.labelFor(entry.from))
                .replace("{to}", state.labelFor(entry.to)),
            confirmLabel = strings(Strings.money_reverse),
            dismissLabel = strings(Strings.money_cancel),
            onConfirm = vm::confirmReverse,
            onDismiss = vm::dismissReverse,
            destructive = true,
        )
    }
}

// ── entry form ──────────────────────────────────────────────────────────────

/**
 * The form. Two rows of equal-height controls sharing one label baseline, so both edges of the
 * card line up: **From ⇄ To** on top (equal weights, the swap fixed between them) and
 * **Amount · Date · Note · Record** below.
 */
@Composable
private fun EntryCard(state: MoneyUiState, vm: MoneyViewModel) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    val sameAccount = state.validationError == MoneyEntryError.SameAccount
    val badAmount = state.amount.isNotBlank() && state.validationError == MoneyEntryError.InvalidAmount

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dims.radiusCard))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(dims.radiusCard))
            .padding(dims.space20),
        verticalArrangement = Arrangement.spacedBy(dims.space16),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
            AccountDropdownField(
                label = strings(Strings.money_from),
                selected = state.from,
                options = state.accounts,
                query = state.fromQuery,
                onQueryChange = vm::setFromQuery,
                onSelect = vm::setFrom,
                currency = state.currency,
                balance = state.fromBalance,
                invalid = sameAccount,
                enabled = state.canManage,
                modifier = Modifier.weight(1f),
            )
            // Sits on the field's own baseline, not the label's, so it centres against the boxes.
            SwapButton(enabled = state.canManage, onClick = vm::swapDirection)
            AccountDropdownField(
                label = strings(Strings.money_to),
                selected = state.to,
                options = state.accounts,
                query = state.toQuery,
                onQueryChange = vm::setToQuery,
                onSelect = vm::setTo,
                currency = state.currency,
                balance = state.toBalance,
                invalid = sameAccount,
                enabled = state.canManage,
                modifier = Modifier.weight(1f),
            )
        }

        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
            Column(Modifier.width(170.dp)) {
                FieldLabel("${strings(Strings.money_amount)}${state.currency.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""}")
                Spacer(Modifier.height(LABEL_GAP))
                var focused by remember { mutableStateOf(false) }
                FieldShell(
                    modifier = Modifier.fillMaxWidth(),
                    focused = focused,
                    invalid = badAmount,
                    enabled = state.canManage,
                ) {
                    ShellTextField(
                        value = state.amount,
                        onValueChange = vm::setAmount,
                        placeholder = "0.00",
                        enabled = state.canManage,
                        onFocusChanged = { focused = it },
                    )
                }
            }
            DateField(
                label = strings(Strings.money_date),
                millis = state.entryDate,
                onPick = vm::setEntryDate,
                enabled = state.canManage,
                modifier = Modifier.width(170.dp),
            )
            Column(Modifier.weight(1f)) {
                FieldLabel(strings(Strings.money_note))
                Spacer(Modifier.height(LABEL_GAP))
                var focused by remember { mutableStateOf(false) }
                FieldShell(
                    modifier = Modifier.fillMaxWidth(),
                    focused = focused,
                    enabled = state.canManage,
                ) {
                    ShellTextField(
                        value = state.note,
                        onValueChange = vm::setNote,
                        placeholder = strings(Strings.money_note_placeholder),
                        enabled = state.canManage,
                        onFocusChanged = { focused = it },
                    )
                }
            }
            PrimaryButton(
                label = strings(Strings.money_record),
                onClick = vm::record,
                enabled = state.canSave,
                loading = state.isSaving,
                loadingLabel = strings(Strings.money_recording),
                modifier = Modifier.width(150.dp).height(FIELD_HEIGHT),
            )
        }

        InlineStatus(state)
    }
}

/** The one-line status under the form. Reserves its height so the card never jumps. */
@Composable
private fun InlineStatus(state: MoneyUiState) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    val hint: String? = when (state.validationError) {
        MoneyEntryError.SameAccount -> strings(Strings.money_err_same_account)
        MoneyEntryError.InvalidAmount -> strings(Strings.money_err_amount)
        MoneyEntryError.NoteTooLong -> strings(Strings.money_err_note_long)
        null -> null
    }
    // Only nag once there's something to nag about — an untouched form isn't an error.
    val showHint = state.from != null && state.to != null &&
        (state.amount.isNotBlank() || state.validationError == MoneyEntryError.SameAccount)

    // A recognised failure gets a sentence; anything else keeps its own words.
    val saveMessage = state.saveErrorKey?.let { strings(it) } ?: state.saveError
    Box(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
        when {
            saveMessage != null -> Text(saveMessage, style = typography.hint, color = colors.error, maxLines = 1)
            showHint && hint != null -> Text(hint, style = typography.hint, color = colors.error, maxLines = 1)
            state.justSaved -> Text(strings(Strings.money_recorded), style = typography.hint, color = colors.success, maxLines = 1)
        }
    }
}

@Composable
private fun SwapButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(dims.radiusField)
    Box(
        modifier = Modifier
            .size(FIELD_HEIGHT)
            .clip(shape)
            .hoverable(src)
            .background(if (hovered && enabled) colors.brandTint else colors.surfaceAlt)
            .border(1.dp, colors.border, shape)
            .then(if (enabled) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.SwapHoriz,
            strings(Strings.money_swap),
            tint = if (enabled) colors.brand else colors.disabled,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun NoticeStrip(state: MoneyUiState) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val notice: Pair<String, Color>? = when {
        state.partiesUnavailable -> strings(Strings.money_parties_unavailable) to colors.warning
        state.errorKey != null -> strings(state.errorKey) to colors.error
        state.error != null -> state.error to colors.error
        else -> null
    }
    if (notice == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dims.radiusField))
            .background(notice.second.copy(alpha = 0.08f))
            .border(1.dp, notice.second.copy(alpha = 0.25f), RoundedCornerShape(dims.radiusField))
            .padding(horizontal = dims.space12, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(notice.first, style = typography.hint, color = notice.second)
    }
}

// ── ledger table ────────────────────────────────────────────────────────────
//
// Column weights live here so the header and every row read the same numbers — the alignment
// drift that comes from each row re-deciding its own widths is what this prevents.
//
// Order follows how the entry is read aloud: when, who → whom, how much, and only then why.

private val COL_DOT = 26.dp
private const val COL_DATE = 1.25f
private const val COL_FROM = 1.25f
private val COL_ARROW = 28.dp
private const val COL_TO = 1.25f
private const val COL_AMOUNT = 1.0f
private const val COL_NOTE = 1.5f
private val COL_ACTION = 84.dp
private val ROW_HEIGHT = 42.dp
private val HEADER_HEIGHT = 36.dp

/** Below this the Note column is dropped — it's the one a reader can most afford to lose. */
private val NOTE_BREAKPOINT = 1000.dp

/**
 * The history, as a ledger (ticket #90): fixed-height rows, hairline column separators, zebra
 * striping and right-aligned amounts — the spreadsheet shape an accountant reads without being
 * taught, rather than a stack of cards.
 *
 * Sortable by date or amount from the header, searchable, and filterable by date range, because a
 * ledger you can only scroll stops being useful the week after you start using it.
 */
@Composable
private fun LedgerTable(state: MoneyUiState, vm: MoneyViewModel, modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val rows = state.visibleEntries

    Column(modifier.fillMaxWidth()) {
        LedgerToolbar(state, vm, rows.size)
        Spacer(Modifier.height(dims.space8))

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(dims.radiusCard)),
        ) {
            val showNote = maxWidth >= NOTE_BREAKPOINT

            Column(Modifier.fillMaxSize()) {
                LedgerHeader(state, vm, showNote)
                HorizontalDivider(color = colors.border)
                when {
                    rows.isEmpty() && state.isFiltered -> EmptyBlock(
                        title = strings(Strings.money_empty_filtered),
                        hint = strings(Strings.money_empty_filtered_hint),
                    )
                    rows.isEmpty() -> EmptyBlock(
                        title = strings(Strings.money_empty),
                        hint = strings(Strings.money_empty_hint),
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(rows, key = { _, e -> e.entryId }) { index, entry ->
                            LedgerRow(entry, state, vm, striped = index % 2 == 1, showNote = showNote)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun EmptyBlock(title: String, hint: String) {
    val colors = AromexTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = AromexTheme.typography.body, color = colors.textSecondary)
            Spacer(Modifier.height(4.dp))
            Text(hint, style = AromexTheme.typography.hint, color = colors.textTertiary)
        }
    }
}

// ── toolbar: title, search, date range ──────────────────────────────────────
//
// The generic parts below — the search box, the date-range chip, the header/body cells, the
// hairline, the sync dot, the reverse button — are `internal` rather than `private` because the
// Stock History table (ticket #106) is built from the same pieces. They were left in place rather
// than moved to a shared file: they lean on this file's date helpers and RangeMode, and a second
// table is not worth disturbing a screen that already works.

@Composable
private fun LedgerToolbar(state: MoneyUiState, vm: MoneyViewModel, shown: Int) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(strings(Strings.money_recent), style = typography.sectionTitle, color = colors.textPrimary)
        Spacer(Modifier.width(dims.space8))
        // "12 of 40" while filtered, plain count otherwise — so a filter can never look like data loss.
        Text(
            if (state.isFiltered) "$shown ${strings(Strings.money_of)} ${state.entries.size}" else "$shown",
            style = typography.hint,
            color = colors.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(dims.radiusPill))
                .background(colors.surfaceAlt)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        SearchBox(state.searchQuery, vm::setSearch)
        Spacer(Modifier.width(dims.space8))
        DateRangeChip(state.rangeFrom, state.rangeTo) { f, t -> vm.setDateRange(f, t) }
        if (state.isFiltered) {
            Spacer(Modifier.width(dims.space8))
            ToolbarChip(strings(Strings.money_clear_filters), onClick = vm::clearFilters)
        }
    }
}

@Composable
internal fun SearchBox(query: String, onChange: (String) -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dims.radiusPill)
    Row(
        modifier = Modifier
            .width(260.dp)
            .height(34.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, if (focused) colors.brand else colors.border, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            ShellTextField(
                value = query,
                onValueChange = onChange,
                placeholder = strings(Strings.money_search_placeholder),
                onFocusChanged = { focused = it },
            )
        }
        if (query.isNotEmpty()) {
            Icon(
                Icons.Filled.Close,
                strings(Strings.money_clear_filters),
                tint = colors.textTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { onChange("") }
                    .padding(2.dp),
            )
        }
    }
}

/** A pill that reads as its own current value ("All dates" / "1 Jul – 31 Jul") and opens the picker. */
@Composable
internal fun DateRangeChip(from: Long?, to: Long?, onPick: (Long?, Long?) -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    var width by remember { mutableStateOf(0.dp) }
    var heightPx by remember { mutableStateOf(0) }
    val active = from != null || to != null
    val shape = RoundedCornerShape(dims.radiusPill)

    Box(
        Modifier.onSizeChanged {
            width = with(density) { it.width.toDp() }
            heightPx = it.height
        },
    ) {
        Row(
            modifier = Modifier
                .height(34.dp)
                .clip(shape)
                .background(if (active) colors.brandTint else colors.surface)
                .border(1.dp, if (active) colors.brand else colors.border, shape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { open = !open }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CalendarToday,
                null,
                tint = if (active) colors.brand else colors.textTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                rangeLabel(from, to),
                style = AromexTheme.typography.hint,
                color = if (active) colors.brand else colors.textSecondary,
                maxLines = 1,
            )
        }
        if (open) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, heightPx + with(density) { 4.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                DateRangePanel(
                    from = from,
                    to = to,
                    onApply = { f, t -> onPick(f, t); open = false },
                )
            }
        }
    }
}

@Composable
private fun rangeLabel(from: Long?, to: Long?): String = when {
    from == null && to == null -> strings(Strings.money_range_all)
    from != null && to != null -> "${shortDate.format(Date(from))} – ${shortDate.format(Date(to))}"
    from != null -> "${strings(Strings.money_range_since)} ${shortDate.format(Date(from))}"
    else -> "${strings(Strings.money_range_until)} ${shortDate.format(Date(to!!))}"
}

/**
 * Presets first, custom second — because the overwhelming majority of "filter by date" is one of
 * five stock answers, and making someone drive a calendar twice for "this month" is a tax.
 */
@Composable
private fun DateRangePanel(from: Long?, to: Long?, onApply: (Long?, Long?) -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var mode by remember { mutableStateOf(RangeMode.PRESETS) }
    var draftFrom by remember(from) { mutableStateOf(from) }
    var draftTo by remember(to) { mutableStateOf(to) }

    Surface(
        shape = RoundedCornerShape(dims.radiusCard),
        shadowElevation = 12.dp,
        color = colors.surface,
        modifier = Modifier.width(290.dp),
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            when (mode) {
                RangeMode.PRESETS -> {
                    PresetRow(strings(Strings.money_range_all)) { onApply(null, null) }
                    PresetRow(strings(Strings.money_range_today)) {
                        onApply(startOfDay(System.currentTimeMillis()), endOfDay(System.currentTimeMillis()))
                    }
                    PresetRow(strings(Strings.money_range_7)) {
                        onApply(startOfDay(daysAgo(6)), endOfDay(System.currentTimeMillis()))
                    }
                    PresetRow(strings(Strings.money_range_30)) {
                        onApply(startOfDay(daysAgo(29)), endOfDay(System.currentTimeMillis()))
                    }
                    PresetRow(strings(Strings.money_range_month)) {
                        onApply(startOfMonth(), endOfDay(System.currentTimeMillis()))
                    }
                    HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 4.dp))
                    PresetRow(
                        "${strings(Strings.money_range_from)}: ${draftFrom?.let { shortDate.format(Date(it)) } ?: "—"}",
                    ) { mode = RangeMode.PICK_FROM }
                    PresetRow(
                        "${strings(Strings.money_range_to)}: ${draftTo?.let { shortDate.format(Date(it)) } ?: "—"}",
                    ) { mode = RangeMode.PICK_TO }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ToolbarChip(strings(Strings.money_range_apply)) {
                            onApply(draftFrom?.let { startOfDay(it) }, draftTo?.let { endOfDay(it) })
                        }
                    }
                }
                RangeMode.PICK_FROM, RangeMode.PICK_TO -> {
                    Text(
                        if (mode == RangeMode.PICK_FROM) strings(Strings.money_range_from) else strings(Strings.money_range_to),
                        style = AromexTheme.typography.fieldLabel,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(start = 14.dp, top = 4.dp),
                    )
                    InlineMonthGrid(
                        initial = (if (mode == RangeMode.PICK_FROM) draftFrom else draftTo) ?: System.currentTimeMillis(),
                    ) { picked ->
                        if (mode == RangeMode.PICK_FROM) draftFrom = picked else draftTo = picked
                        mode = RangeMode.PRESETS
                    }
                }
            }
        }
    }
}

private enum class RangeMode { PRESETS, PICK_FROM, PICK_TO }

@Composable
private fun PresetRow(label: String, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Text(
        label,
        style = AromexTheme.typography.body.copy(fontSize = 14.sp),
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(src)
            .background(if (hovered) colors.brand.copy(alpha = 0.06f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** A small pill button used for toolbar actions, rounded highlight included. */
@Composable
internal fun ToolbarChip(label: String, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(dims.radiusPill)
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(shape)
            .hoverable(src)
            .background(if (hovered) colors.brandTint else colors.surface)
            .border(1.dp, colors.border, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = AromexTheme.typography.hint, color = colors.brand, maxLines = 1)
    }
}

// ── header ──────────────────────────────────────────────────────────────────

@Composable
private fun LedgerHeader(state: MoneyUiState, vm: MoneyViewModel, showNote: Boolean) {
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
        SortableHeaderCell(
            text = strings(Strings.money_col_date),
            weight = COL_DATE,
            style = style,
            active = state.sortBy == MoneySortColumn.DATE,
            ascending = state.sortAscending,
            onClick = { vm.toggleSort(MoneySortColumn.DATE) },
        )
        HeaderCell(strings(Strings.money_from), COL_FROM, style, showHairline = false)
        Box(Modifier.width(COL_ARROW).fillMaxHeight())
        HeaderCell(strings(Strings.money_to), COL_TO, style)
        SortableHeaderCell(
            text = strings(Strings.money_amount),
            weight = COL_AMOUNT,
            style = style,
            active = state.sortBy == MoneySortColumn.AMOUNT,
            ascending = state.sortAscending,
            align = TextAlign.End,
            onClick = { vm.toggleSort(MoneySortColumn.AMOUNT) },
            showHairline = showNote || state.canManage,
        )
        if (showNote) HeaderCell(strings(Strings.money_col_note), COL_NOTE, style, showHairline = state.canManage)
        if (state.canManage) Box(Modifier.width(COL_ACTION))
    }
}

@Composable
internal fun RowScope.HeaderCell(
    text: String,
    weight: Float,
    style: TextStyle,
    align: TextAlign = TextAlign.Start,
    showHairline: Boolean = true,
) {
    Box(Modifier.weight(weight).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        Text(
            text.uppercase(Locale.getDefault()),
            style = style.copy(textAlign = align),
            color = AromexTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
        )
        if (showHairline) Hairline(Modifier.align(Alignment.CenterEnd))
    }
}

/** A header you can click to sort, with the arrow that tells you which way — the Excel convention. */
@Composable
internal fun RowScope.SortableHeaderCell(
    text: String,
    weight: Float,
    style: TextStyle,
    active: Boolean,
    ascending: Boolean,
    onClick: () -> Unit,
    align: TextAlign = TextAlign.Start,
    showHairline: Boolean = true,
) {
    val colors = AromexTheme.colors
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight()
            .hoverable(src)
            .background(if (hovered) colors.brand.copy(alpha = 0.06f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = if (align == TextAlign.End) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text.uppercase(Locale.getDefault()),
                style = style,
                color = if (active) colors.brand else colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                null,
                tint = if (active) colors.brand else colors.textTertiary.copy(alpha = 0.35f),
                modifier = Modifier.size(11.dp),
            )
        }
        if (showHairline) Hairline(Modifier.align(Alignment.CenterEnd))
    }
}

/** The thin column separator that gives the table its spreadsheet feel. */
@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).fillMaxHeight().background(AromexTheme.colors.border.copy(alpha = 0.6f)))
}

// ── rows ────────────────────────────────────────────────────────────────────

@Composable
private fun LedgerRow(
    entry: MoneyEntry,
    state: MoneyUiState,
    vm: MoneyViewModel,
    striped: Boolean,
    showNote: Boolean,
) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    val isReversing = state.reversingEntryId == entry.entryId

    Column {
        // One SelectionContainer around the whole row: a press-and-drag selects the entire row's
        // text in a single motion; the Reverse button inside still clicks normally.
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
            // Status: a dot, explained on hover rather than by a column of repeated words.
            Box(Modifier.width(COL_DOT).fillMaxHeight(), contentAlignment = Alignment.Center) {
                SyncDot(entry.syncStatus)
                Hairline(Modifier.align(Alignment.CenterEnd))
            }
            BodyCell(COL_DATE) {
                Column {
                    Text(
                        dayFormat.format(Date(entry.entryDate)),
                        style = typography.hint.copy(fontSize = 12.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        timeFormat.format(Date(entry.entryDate)),
                        style = typography.hint.copy(fontSize = 10.sp),
                        color = colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
            BodyCell(COL_FROM, showHairline = false) { AccountCellText(state.labelFor(entry.from)) }
            // From → To reads as one unit: the arrow gets no separators on either side, so the eye
            // groups the pair instead of seeing three unrelated cells.
            Box(Modifier.width(COL_ARROW).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
            BodyCell(COL_TO) { AccountCellText(state.labelFor(entry.to)) }
            BodyCell(COL_AMOUNT, showHairline = showNote || state.canManage) {
                DirectionalAmount(entry.direction, entry.amount, state.currency)
            }
            if (showNote) {
                BodyCell(COL_NOTE, showHairline = state.canManage) {
                    Text(
                        entry.note.orEmpty(),
                        style = typography.hint,
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.canManage) {
                Box(Modifier.width(COL_ACTION).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    if (entry.canReverse) {
                        // Excluded from the row's SelectionContainer — it's a button, not text.
                        DisableSelection {
                            ReverseButton(
                                busy = isReversing,
                                enabled = state.reversingEntryId == null,
                                onClick = { vm.askReverse(entry) },
                            )
                        }
                    }
                }
            }
        }
        }
        HorizontalDivider(color = colors.border.copy(alpha = 0.45f))
    }
}

@Composable
internal fun RowScope.BodyCell(
    weight: Float,
    showHairline: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(Modifier.weight(weight).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) { content() }
        if (showHairline) Hairline(Modifier.align(Alignment.CenterEnd))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountCellText(label: String) {
    val colors = AromexTheme.colors
    TooltipArea(tooltip = { CellTooltip(label) }) {
        Text(
            label,
            style = AromexTheme.typography.body.copy(fontSize = 14.sp),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Sync state as a single dot with its meaning on hover. An entry the ledger hasn't accepted must
 * never look identical to one it has — but a whole column repeating "In ledger" earns none of its
 * width, so the word moves to the tooltip and the colour carries the signal.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SyncDot(status: HlSyncStatus) {
    val colors = AromexTheme.colors
    val (label, tint) = when (status) {
        HlSyncStatus.PENDING -> strings(Strings.money_sync_pending) to colors.warning
        HlSyncStatus.FAILED -> strings(Strings.money_sync_failed) to colors.error
        HlSyncStatus.SYNCED -> strings(Strings.money_sync_ok) to colors.success
    }
    TooltipArea(tooltip = { CellTooltip(label) }) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
    }
}

@Composable
internal fun CellTooltip(text: String) {
    val colors = AromexTheme.colors
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.textPrimary,
        shadowElevation = 6.dp,
    ) {
        Text(
            text,
            style = AromexTheme.typography.hint,
            color = colors.surface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

/**
 * The amount, coloured by which way the money went: **green in, red out**, and plain for a transfer
 * that changed nothing overall (cash → bank, or one party settling another's account). A sign
 * reinforces it for anyone who can't rely on colour alone.
 */
@Composable
internal fun DirectionalAmount(
    direction: MoneyDirection,
    amount: String,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val body = MoneyFormat.format(amount, currency)
    val (text, tint) = when (direction) {
        MoneyDirection.IN -> "+$body" to colors.success
        MoneyDirection.OUT -> "−$body" to colors.error
        MoneyDirection.INTERNAL -> body to colors.textSecondary
    }
    Text(
        text,
        style = AromexTheme.typography.body.copy(fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End),
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        style = AromexTheme.typography.hint.copy(fontSize = 10.sp),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(AromexTheme.dimensions.radiusPill))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
internal fun ReverseButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(dims.radiusPill)
    Row(
        modifier = Modifier
            .height(26.dp)
            .widthIn(min = 68.dp)
            .clip(shape)
            .hoverable(src)
            .background(if (hovered && enabled) colors.brandTint else Color.Transparent)
            .border(1.dp, if (enabled) colors.border else colors.border.copy(alpha = 0.5f), shape)
            .then(if (enabled && !busy) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = colors.brand)
        } else {
            Text(
                strings(Strings.money_reverse),
                style = AromexTheme.typography.hint,
                color = if (enabled) colors.brand else colors.disabled,
                maxLines = 1,
            )
        }
    }
}

// ── date helpers ────────────────────────────────────────────────────────────

internal val timeFormat = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
private val shortDate = java.text.SimpleDateFormat("d MMM", Locale.getDefault())

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun endOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
}.timeInMillis

private fun daysAgo(n: Int): Long =
    Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.timeInMillis

private fun startOfMonth(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis
