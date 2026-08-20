package com.humblesolutions.aromex.ui.entities

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Inventory
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.AccountStatement
import com.humblesolutions.aromex.model.MoneyAccountRef
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.OpeningBalance
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.components.AromexDialog
import com.humblesolutions.aromex.ui.components.BalanceFreshness
import com.humblesolutions.aromex.ui.components.AromexMark
import com.humblesolutions.aromex.ui.components.Avatar
import com.humblesolutions.aromex.ui.components.DesktopSection
import androidx.compose.ui.zIndex
import com.humblesolutions.aromex.ui.components.CollapsedSidebarWidth
import com.humblesolutions.aromex.ui.components.ExpandedSidebarWidth
import com.humblesolutions.aromex.ui.components.NavSidebar
import com.humblesolutions.aromex.ui.components.CountryPickerDialog
import com.humblesolutions.aromex.ui.components.LabeledTextField
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexColors
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.Countries
import com.humblesolutions.aromex.util.Country
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.MoneyFormat
import java.math.BigDecimal
import java.util.regex.Pattern

// ── Mode ─────────────────────────────────────────────────────────────────────

private sealed interface Mode {
    data object List : Mode
    data class Form(val existing: Entity?) : Mode
}

// ── Root composable ───────────────────────────────────────────────────────────

@Composable
fun EntitiesScreen(
    state: EntitiesUiState,
    @Suppress("UNUSED_PARAMETER") onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (EntitiesFilter) -> Unit,
    onBalanceFilterChange: (BalanceFilter) -> Unit = {},
    onSortChange: (EntitySort) -> Unit = {},
    onRefresh: () -> Unit,
    onSave: (EntityInput, String?, (Boolean) -> Unit) -> Unit,
    onArchive: (Entity) -> Unit,
    /** Loads a party's ledger statement when it's selected (ticket #91). */
    onLoadStatement: (String) -> Unit = {},
    onLoadMoreStatement: () -> Unit = {},
    // ── statement controls (ticket #108) ─────────────────────────────────────
    onStatementSearch: (String) -> Unit = {},
    onStatementRange: (Long?, Long?) -> Unit = { _, _ -> },
    onStatementSortToggle: () -> Unit = {},
    onStatementClearFilters: () -> Unit = {},
    // ── Print statement (ticket #109) ─────────────────────────────────────────
    onPrintOpen: () -> Unit = {},
    onPrintRange: (Long?, Long?) -> Unit = { _, _ -> },
    onPrintNotesToggle: (Boolean) -> Unit = {},
    onPrintGenerate: () -> Unit = {},
    onPrintDialogClose: () -> Unit = {},
    onPdfClose: () -> Unit = {},
    onReverseMoney: (MoneyEntry) -> Unit = {},
    onConfirmReverse: () -> Unit = {},
    onDismissReverse: () -> Unit = {},
    onNavigateToInventory: () -> Unit,
    onNavigateToSales: () -> Unit,
    onNavigateToSalesHistory: () -> Unit,
    onNavigateToMoney: () -> Unit = {},
    onNavigateToCommissionRules: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToStockHistory: () -> Unit = {},
    onSignOut: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    // ── Hover-driven sidebar expand / collapse
    var isSidebarExpanded by remember { mutableStateOf(false) }
    val sidebarSrc = remember { MutableInteractionSource() }
    val contentSrc = remember { MutableInteractionSource() }
    val isSidebarHovered by sidebarSrc.collectIsHoveredAsState()
    val isContentHovered by contentSrc.collectIsHoveredAsState()

    LaunchedEffect(isSidebarHovered) {
        if (isSidebarHovered && !isSidebarExpanded) isSidebarExpanded = true
    }
    LaunchedEffect(isContentHovered, isSidebarHovered) {
        if (isContentHovered && !isSidebarHovered && isSidebarExpanded) isSidebarExpanded = false
    }

    val sidebarWidth by animateDpAsState(
        targetValue = if (isSidebarExpanded) ExpandedSidebarWidth else CollapsedSidebarWidth,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "sidebarWidth",
    )

    // ── Navigation state
    var mode by remember { mutableStateOf<Mode>(Mode.List) }
    var selectedEntityId by remember { mutableStateOf<String?>(null) }
    val selectedRow = state.rows.firstOrNull { it.entity.id == selectedEntityId }

    // Fetch the statement whenever the selection lands on a party (ticket #91).
    LaunchedEffect(selectedEntityId) {
        selectedEntityId?.let(onLoadStatement)
    }

    LaunchedEffect(state.rows) {
        if (selectedEntityId != null && state.rows.none { it.entity.id == selectedEntityId }) {
            selectedEntityId = null
        }
    }

    var showSignOutDialog by remember { mutableStateOf(false) }
    val currency = state.session?.currency ?: "USD"

    if (state.noAccess) {
        Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
            Text(strings(Strings.entities_no_access), style = AromexTheme.typography.body, color = colors.textSecondary)
        }
        return
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(Modifier.fillMaxSize()) {
            // ── Sidebar (overlay — floats over the content instead of pushing it)
            NavSidebar(
                expanded = isSidebarExpanded,
                width = sidebarWidth,
                modifier = Modifier.zIndex(1f),
                selectedSection = DesktopSection.ENTITIES,
                session = state.session,
                interactionSource = sidebarSrc,
                onNavigateToEntities = {},
                onNavigateToInventory = onNavigateToInventory,
                onNavigateToSales = onNavigateToSales,
                onNavigateToSalesHistory = onNavigateToSalesHistory,
                onNavigateToMoney = onNavigateToMoney,
                onNavigateToCommissionRules = onNavigateToCommissionRules,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToStockHistory = onNavigateToStockHistory,
                onSignOutRequest = { showSignOutDialog = true },
            )

            // ── Main content
            Column(Modifier.fillMaxSize().padding(start = CollapsedSidebarWidth).hoverable(contentSrc)) {
                EntitiesTopBar(
                    sidebarExpanded = isSidebarExpanded,
                    onExpandSidebar = { isSidebarExpanded = true },
                    canManage = state.canManage,
                    session = state.session,
                    onNewContact = { if (state.canManage) mode = Mode.Form(null) },
                    onSignOutRequest = { showSignOutDialog = true },
                )
                HorizontalDivider(color = colors.border)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    EntitiesListPanel(
                        state = state,
                        selectedEntityId = selectedEntityId,
                        currency = currency,
                        onQueryChange = onQueryChange,
                        onFilterChange = onFilterChange,
                        onRefresh = onRefresh,
                        onBalanceFilterChange = onBalanceFilterChange,
                        onSortChange = onSortChange,
                        onSelectEntity = { selectedEntityId = it },
                        modifier = Modifier.width(320.dp).fillMaxHeight(),
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
                    EntityDetailPanel(
                        row = selectedRow,
                        canManage = state.canManage,
                        currency = currency,
                        timezone = state.session?.timezone ?: "UTC",
                        statement = state.statement.takeIf { state.statementEntityId == selectedEntityId },
                        isLoadingStatement = state.isLoadingStatement,
                        statementError = state.statementError,
                        moneyByTransaction = state.moneyByTransaction,
                        cancelledTransactionIds = state.cancelledTransactionIds,
                        reversingEntryId = state.reversingEntryId,
                        onReverseMoney = onReverseMoney,
                        onLoadMoreStatement = onLoadMoreStatement,
                        statementSearch = state.statementSearch,
                        statementFrom = state.statementFrom,
                        statementTo = state.statementTo,
                        statementAscending = state.statementAscending,
                        onStatementSearch = onStatementSearch,
                        onStatementRange = onStatementRange,
                        onStatementSortToggle = onStatementSortToggle,
                        onStatementClearFilters = onStatementClearFilters,
                        printDialogOpen = state.printDialogOpen,
                        printFrom = state.printFrom,
                        printTo = state.printTo,
                        printIncludeNotes = state.printIncludeNotes,
                        printGenerating = state.printGenerating,
                        printError = state.printError,
                        printPdfUrl = state.printPdfUrl,
                        onPrintOpen = onPrintOpen,
                        onPrintRange = onPrintRange,
                        onPrintNotesToggle = onPrintNotesToggle,
                        onPrintGenerate = onPrintGenerate,
                        onPrintDialogClose = onPrintDialogClose,
                        onPdfClose = onPdfClose,
                        onEdit = { entity -> mode = Mode.Form(entity) },
                        onArchive = { entity ->
                            onArchive(entity)
                            if (selectedEntityId == entity.id) selectedEntityId = null
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }

        // ── Add / Edit modal overlay
        if (mode is Mode.Form) {
            val m = mode as Mode.Form
            EntityFormDialog(
                existing = m.existing,
                saving = state.saving,
                saveError = state.saveError,
                currency = currency,
                onClose = { mode = Mode.List },
                onSubmit = { input -> onSave(input, m.existing?.id) { ok -> if (ok) mode = Mode.List } },
            )
        }

        // ── Sign-out confirmation
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

        // Same prompt and same wording as the Money screen — reversing from here is the same act.
        state.pendingReversal?.let { entry ->
            AromexDialog(
                title = strings(Strings.money_reverse_title),
                message = strings(Strings.money_reverse_body)
                    .replace("{amount}", MoneyFormat.format(entry.amount, currency))
                    .replace("{from}", moneyAccountLabel(entry.from, state))
                    .replace("{to}", moneyAccountLabel(entry.to, state)),
                confirmLabel = strings(Strings.money_reverse),
                dismissLabel = strings(Strings.money_cancel),
                onConfirm = onConfirmReverse,
                onDismiss = onDismissReverse,
                destructive = true,
            )
        }
    }
}

/** Names one side of a money entry using the parties already loaded on this screen. */
private fun moneyAccountLabel(ref: MoneyAccountRef, state: EntitiesUiState): String = when (ref) {
    MoneyAccountRef.Cash -> "Cash"
    MoneyAccountRef.Bank -> "Bank"
    is MoneyAccountRef.Party ->
        state.rows.firstOrNull { it.entity.id == ref.entityId }?.entity?.name ?: "—"
}

// ── Top bar ───────────────────────────────────────────────────────────────────

@Composable
private fun EntitiesTopBar(
    sidebarExpanded: Boolean,
    onExpandSidebar: () -> Unit,
    canManage: Boolean,
    session: UserSession?,
    onNewContact: () -> Unit,
    onSignOutRequest: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    // Responsive: the top bar has to survive the 420dp minimum window (see main.kt), where the
    // full breadcrumb + wide "+ New Contact" button + bell + avatar can't all fit. Below the
    // breakpoint the non-essential breadcrumb and bell drop out and the button becomes icon-only,
    // so the row never over-constrains and squeezes the avatar/title into vertical single-column
    // text (the break this fixes).
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.surface)) {
        val compact = maxWidth < 560.dp
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dims.space16),
        ) {
            if (!sidebarExpanded) {
                IconButton(onClick = onExpandSidebar) {
                    Icon(Icons.Filled.Menu, "Expand menu", tint = colors.textSecondary)
                }
            }
            // Breadcrumb — hidden when compact so the actions keep their room. Always single-line
            // with ellipsis so it truncates instead of wrapping character-by-character when tight.
            if (!compact) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        strings(Strings.entities_list_eyebrow),
                        style = AromexTheme.typography.hint,
                        color = colors.textTertiary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.Filled.ExpandMore, null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                    Text(
                        strings(Strings.entities_list_title),
                        style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            // + New Contact — icon-only when compact.
            if (canManage) {
                Button(
                    onClick = onNewContact,
                    modifier = Modifier.height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    contentPadding = if (compact) PaddingValues(horizontal = 12.dp) else ButtonDefaults.ContentPadding,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.brand, contentColor = Color.White),
                ) {
                    if (compact) {
                        Icon(Icons.Filled.Add, strings(Strings.entities_new_contact_btn), modifier = Modifier.size(20.dp))
                    } else {
                        Text(strings(Strings.entities_new_contact_btn), style = AromexTheme.typography.button, maxLines = 1, softWrap = false)
                    }
                }
            }
            // Bell (placeholder) — dropped when compact to reclaim width.
            if (!compact) {
                IconButton(onClick = {}) {
                    Icon(Icons.Filled.Notifications, null, tint = colors.textTertiary)
                }
            }
            // User avatar
            val displayName = session?.displayName?.takeIf { it.isNotBlank() } ?: session?.email ?: "U"
            Box(modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onSignOutRequest)) {
                Avatar(name = displayName, size = 36.dp)
            }
        }
    }
}

// ── List panel ────────────────────────────────────────────────────────────────

@Composable
private fun EntitiesListPanel(
    state: EntitiesUiState,
    selectedEntityId: String?,
    onRefresh: () -> Unit,
    onBalanceFilterChange: (BalanceFilter) -> Unit,
    onSortChange: (EntitySort) -> Unit,
    currency: String,
    onQueryChange: (String) -> Unit,
    onFilterChange: (EntitiesFilter) -> Unit,
    onSelectEntity: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    val receivableTotal = remember(state.rows) {
        state.rows.filter { it.balance?.direction == BalanceDirection.RECEIVABLE }
            .mapNotNull { it.balance?.net }
            .fold(BigDecimal.ZERO) { acc, net -> acc.add(BigDecimal(Money.abs(net))) }
            .toPlainString()
    }
    val payableTotal = remember(state.rows) {
        state.rows.filter { it.balance?.direction == BalanceDirection.CREDIT }
            .mapNotNull { it.balance?.net }
            .fold(BigDecimal.ZERO) { acc, net -> acc.add(BigDecimal(Money.abs(net))) }
            .toPlainString()
    }

    Column(modifier.background(colors.surface)) {
        // Title + count
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space16),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("All Contacts", style = AromexTheme.typography.sectionTitle, color = colors.textPrimary)
                Text(
                    strings(Strings.entities_total_count, state.rows.size),
                    style = AromexTheme.typography.hint,
                    color = colors.textTertiary,
                )
            }
            // Balances are read from the ledger on request, not streamed — so say how old they are
            // and offer the re-read, rather than showing a number of unknown age.
            BalanceFreshness(
                refreshedAt = state.balancesRefreshedAt,
                isLoading = state.isLoadingBalances,
                onRefresh = onRefresh,
            )
        }

        // Filter search
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.space16)
                .clip(RoundedCornerShape(dims.radiusField))
                .background(colors.surfaceAlt)
                .border(dims.borderField, colors.border, RoundedCornerShape(dims.radiusField))
                .padding(horizontal = dims.space12, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = colors.textTertiary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(dims.space8))
            BasicTextField(
                value = state.query,
                onValueChange = onQueryChange,
                textStyle = AromexTheme.typography.body.copy(color = colors.textPrimary),
                singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box {
                        if (state.query.isEmpty()) Text("Filter…", style = AromexTheme.typography.body, color = colors.textTertiary)
                        inner()
                    }
                },
            )
        }

        Spacer(Modifier.height(dims.space12))

        // Stat cards
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space16),
            horizontalArrangement = Arrangement.spacedBy(dims.space8),
        ) {
            // The totals ARE the filter: tapping "receivable" narrows the list to who owes you.
            // Cheaper to learn than another row of chips, and it answers the obvious next question.
            StatCard(
                label = strings(Strings.entities_summary_receivable),
                amount = MoneyFormat.format(receivableTotal, currency),
                amountColor = colors.success,
                selected = state.balanceFilter == BalanceFilter.OWES_ME,
                onClick = { onBalanceFilterChange(BalanceFilter.OWES_ME) },
                modifier = Modifier.weight(1f),
            )
            StatCard(
                label = strings(Strings.entities_summary_payable),
                amount = MoneyFormat.format(payableTotal, currency),
                amountColor = colors.error,
                selected = state.balanceFilter == BalanceFilter.I_OWE,
                onClick = { onBalanceFilterChange(BalanceFilter.I_OWE) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(dims.space8))

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                strings(Strings.entities_sort_label),
                style = AromexTheme.typography.hint,
                color = colors.textTertiary,
            )
            Spacer(Modifier.width(dims.space8))
            SortPill(
                label = strings(Strings.entities_sort_name),
                selected = state.sortBy == EntitySort.NAME,
            ) { onSortChange(EntitySort.NAME) }
            Spacer(Modifier.width(6.dp))
            SortPill(
                label = strings(Strings.entities_sort_balance),
                selected = state.sortBy == EntitySort.BALANCE,
            ) { onSortChange(EntitySort.BALANCE) }
        }

        Spacer(Modifier.height(dims.space12))

        // Role filter chips — horizontally scrollable so labels never wrap
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dims.space16)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                EntitiesFilter.ALL to strings(Strings.entities_filter_all),
                EntitiesFilter.CUSTOMER to strings(Strings.entities_filter_customers),
                EntitiesFilter.SUPPLIER to strings(Strings.entities_filter_suppliers),
            ).forEach { (filter, label) ->
                val active = state.filter == filter
                val chipSrc = remember { MutableInteractionSource() }
                val chipHovered by chipSrc.collectIsHoveredAsState()
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(dims.radiusPill))
                        .background(if (active) colors.brandTint else if (chipHovered) colors.surfaceAlt else colors.surface)
                        .border(dims.borderThin, if (active) colors.brand else colors.border, RoundedCornerShape(dims.radiusPill))
                        .hoverable(chipSrc)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { onFilterChange(filter) }
                        .padding(horizontal = dims.space12, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = AromexTheme.typography.fieldLabel, color = if (active) colors.brand else colors.textSecondary)
                }
            }
        }

        Spacer(Modifier.height(dims.space12))
        HorizontalDivider(color = colors.border)

        // List content
        when {
            state.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(dims.space32)) {
                        Text(strings(Strings.entities_error_title), style = AromexTheme.typography.bodyStrong, color = colors.error)
                        Spacer(Modifier.height(dims.space8))
                        TextButton(onClick = {}) { Text(strings(Strings.entities_error_retry), color = colors.brand) }
                    }
                }
            }
            state.rows.isEmpty() && !state.isLoadingBalances -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(dims.space32)) {
                        Icon(Icons.Outlined.People, null, tint = colors.disabled, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(dims.space12))
                        Text(strings(Strings.entities_empty_title), style = AromexTheme.typography.bodyStrong, color = colors.textTertiary)
                    }
                }
            }
            state.isLoadingBalances && state.rows.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = colors.brand)
                        Spacer(Modifier.height(dims.space12))
                        Text(strings(Strings.entities_loading), style = AromexTheme.typography.hint, color = colors.textTertiary)
                    }
                }
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.rows, key = { it.entity.id }) { row ->
                        ContactCard(
                            row = row,
                            selected = row.entity.id == selectedEntityId,
                            currency = currency,
                            onClick = { onSelectEntity(row.entity.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    amount: String,
    amountColor: Color,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val shape = RoundedCornerShape(10.dp)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Column(
        modifier = modifier
            // clip() before clickable so the press highlight follows the rounded corners.
            .clip(shape)
            .hoverable(src)
            .background(
                when {
                    selected -> amountColor.copy(alpha = 0.10f)
                    hovered && onClick != null -> colors.surfaceAlt
                    else -> Color.Transparent
                },
            )
            .border(
                if (selected) dims.borderField else dims.borderThin,
                if (selected) amountColor else colors.border,
                shape,
            )
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
        Spacer(Modifier.height(2.dp))
        Text(
            amount,
            style = AromexTheme.typography.bodyStrong.copy(fontWeight = FontWeight.Bold),
            color = amountColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small either/or control for list order. Rounded highlight, like everything else on screen. */
@Composable
private fun SortPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val shape = RoundedCornerShape(dims.radiusPill)
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .clip(shape)
            .hoverable(src)
            .background(
                when {
                    selected -> colors.brand.copy(alpha = 0.12f)
                    hovered -> colors.surfaceAlt
                    else -> Color.Transparent
                },
            )
            .border(dims.borderThin, if (selected) colors.brand else colors.border, shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = AromexTheme.typography.hint,
            color = if (selected) colors.brand else colors.textSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ContactCard(row: EntityRow, selected: Boolean, currency: String, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val entity = row.entity
    val hoverSrc = remember { MutableInteractionSource() }
    val isHovered by hoverSrc.collectIsHoveredAsState()
    val (balanceText, balanceColor) = balanceDisplay(row.balance, currency, colors)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.brandTint else if (isHovered) colors.surfaceAlt else colors.surface)
            .then(if (selected) Modifier.border(1.5.dp, colors.brand) else Modifier)
            .hoverable(hoverSrc)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = dims.space20, vertical = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(name = entity.name, size = 40.dp)
            Spacer(Modifier.width(dims.space12))
            Column(Modifier.weight(1f)) {
                Text(
                    entity.name.ifBlank { "(no name)" },
                    style = AromexTheme.typography.bodyStrong,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RoleGlyphs(entity.roles, colors)
            }
            Spacer(Modifier.width(dims.space8))
            Text(
                balanceText,
                style = AromexTheme.typography.bodyStrong.copy(fontWeight = FontWeight.Bold),
                color = balanceColor,
                maxLines = 1,
            )
        }
    }
    HorizontalDivider(color = colors.border)
}

@Composable
private fun RoleGlyphs(roles: Set<EntityRole>, colors: AromexColors) {
    if (roles.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
        if (EntityRole.CUSTOMER in roles) Icon(Icons.Filled.Star, null, tint = colors.brand, modifier = Modifier.size(12.dp))
        if (EntityRole.SUPPLIER in roles) Box(Modifier.size(10.dp).clip(CircleShape).background(colors.warning))
        if (EntityRole.MIDDLEMAN in roles) Icon(Icons.Outlined.People, null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
    }
}

// ── Detail panel ─────────────────────────────────────────────────────────────

@Composable
private fun EntityDetailPanel(
    row: EntityRow?,
    canManage: Boolean,
    currency: String,
    /** The shop's IANA zone — statement times are the shop's clock. */
    timezone: String,
    statement: AccountStatement?,
    isLoadingStatement: Boolean,
    statementError: String?,
    moneyByTransaction: Map<String, MoneyEntry>,
    cancelledTransactionIds: Set<String>,
    reversingEntryId: String?,
    onReverseMoney: (MoneyEntry) -> Unit,
    onLoadMoreStatement: () -> Unit,
    statementSearch: String = "",
    statementFrom: Long? = null,
    statementTo: Long? = null,
    statementAscending: Boolean = false,
    onStatementSearch: (String) -> Unit = {},
    onStatementRange: (Long?, Long?) -> Unit = { _, _ -> },
    onStatementSortToggle: () -> Unit = {},
    onStatementClearFilters: () -> Unit = {},
    // ── Print statement (ticket #109) ────────────────────────────────────────────
    printDialogOpen: Boolean = false,
    printFrom: Long? = null,
    printTo: Long? = null,
    printIncludeNotes: Boolean = false,
    printGenerating: Boolean = false,
    printError: String? = null,
    printPdfUrl: String? = null,
    onPrintOpen: () -> Unit = {},
    onPrintRange: (Long?, Long?) -> Unit = { _, _ -> },
    onPrintNotesToggle: (Boolean) -> Unit = {},
    onPrintGenerate: () -> Unit = {},
    onPrintDialogClose: () -> Unit = {},
    onPdfClose: () -> Unit = {},
    onEdit: (Entity) -> Unit,
    onArchive: (Entity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    if (row == null) {
        Box(modifier.background(colors.background), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.People, null, tint = colors.disabled, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(dims.space12))
                Text(strings(Strings.entities_no_selection), style = AromexTheme.typography.body, color = colors.textTertiary)
            }
        }
        return
    }

    val entity = row.entity
    val (_, balanceColor) = balanceDisplay(row.balance, currency, colors)
    val signedAmount = row.balance?.let {
        MoneyFormat.formatSigned(Money.abs(it.net), currency, positive = it.direction == BalanceDirection.RECEIVABLE, negative = it.direction == BalanceDirection.CREDIT)
    } ?: "—"
    val directionLabel = when (row.balance?.direction) {
        BalanceDirection.RECEIVABLE -> strings(Strings.entity_detail_they_owe_you)
        BalanceDirection.CREDIT -> strings(Strings.entity_detail_you_owe_them)
        BalanceDirection.SETTLED -> strings(Strings.entity_detail_settled)
        null -> "…"
    }

    var showArchiveDialog by remember(entity.id) { mutableStateOf(false) }

    Column(modifier.background(colors.background)) {
        // ── Blue gradient header
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(colors.headerGradientStart, colors.headerGradientEnd)))
                .padding(dims.space20),
        ) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    // Avatar + name + roles
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = entity.name, size = 52.dp)
                        Spacer(Modifier.width(dims.space16))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SelectionContainer {
                                    Text(
                                        entity.name.ifBlank { "(no name)" },
                                        style = AromexTheme.typography.screenTitle.copy(fontSize = 20.sp),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!entity.isWalkIn && canManage) {
                                    Spacer(Modifier.width(dims.space8))
                                    IconButton(onClick = { onEdit(entity) }, modifier = Modifier.size(28.dp)) {
                                        Icon(Icons.Filled.Edit, strings(Strings.entity_detail_edit), tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                            if (entity.roles.isNotEmpty()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(dims.space4), modifier = Modifier.padding(top = 4.dp)) {
                                    entity.roles.forEach { role ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(dims.radiusPill))
                                                .background(Color.White.copy(alpha = 0.15f))
                                                .padding(horizontal = 8.dp, vertical = 3.dp),
                                        ) {
                                            Text(
                                                role.name.lowercase().replaceFirstChar { it.uppercaseChar() },
                                                style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold),
                                                color = Color.White,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // Balance (right)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(strings(Strings.entity_detail_balance_label), style = AromexTheme.typography.fieldLabel, color = Color.White.copy(alpha = 0.55f))
                        SelectionContainer {
                            Text(
                                signedAmount,
                                style = AromexTheme.typography.screenTitle.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
                                color = Color.White,
                                maxLines = 1,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(dims.radiusPill))
                                .background(balanceColor.copy(alpha = 0.25f))
                                .border(dims.borderThin, balanceColor.copy(alpha = 0.4f), RoundedCornerShape(dims.radiusPill))
                                .padding(horizontal = dims.space8, vertical = 3.dp),
                        ) {
                            Text(directionLabel, style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold), color = balanceColor)
                        }
                    }
                }

                Spacer(Modifier.height(dims.space20))

                // Contact info 2-col grid
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dims.space12)) {
                        if (entity.phones.isNotEmpty()) DetailField(Icons.Outlined.Phone, strings(Strings.entity_detail_phone_label), entity.phones.joinToString(", "))
                        entity.address?.takeIf { it.isNotBlank() }?.let { DetailField(Icons.Outlined.LocationOn, strings(Strings.entity_detail_address_label), it) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dims.space12)) {
                        entity.email?.takeIf { it.isNotBlank() }?.let { DetailField(Icons.Outlined.Email, strings(Strings.entity_detail_email_label), it) }
                        entity.notes?.takeIf { it.isNotBlank() }?.let { DetailField(Icons.Outlined.Description, strings(Strings.entity_detail_notes_label), it) }
                    }
                }

                // Walk-in note
                if (entity.isWalkIn) {
                    Spacer(Modifier.height(dims.space12))
                    Text(strings(Strings.entity_detail_walkin_note), style = AromexTheme.typography.hint, color = Color.White.copy(alpha = 0.6f))
                }

                // Action buttons
                if (!entity.isWalkIn && canManage) {
                    Spacer(Modifier.height(dims.space16))
                    Row(horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(dims.radiusButton))
                                .background(Color.White.copy(alpha = 0.15f))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { onEdit(entity) }
                                .padding(horizontal = dims.space16, vertical = 8.dp),
                        ) { Text(strings(Strings.entity_detail_edit), style = AromexTheme.typography.button, color = Color.White) }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(dims.radiusButton))
                                .border(dims.borderThin, Color.White.copy(alpha = 0.3f), RoundedCornerShape(dims.radiusButton))
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { showArchiveDialog = true }
                                .padding(horizontal = dims.space16, vertical = 8.dp),
                        ) { Text(strings(Strings.entity_detail_archive), style = AromexTheme.typography.button, color = Color.White.copy(alpha = 0.8f)) }
                    }
                }
            }
        }

        // ── Ledger statement (ticket #91): the party's real history, read from HL
        Column(Modifier.weight(1f).fillMaxWidth().background(colors.background)) {
            PartyStatementSection(
                statement = statement,
                isLoading = isLoadingStatement,
                error = statementError,
                currency = currency,
                timezone = timezone,
                moneyByTransaction = moneyByTransaction,
                cancelledTransactionIds = cancelledTransactionIds,
                canManage = canManage,
                reversingEntryId = reversingEntryId,
                onReverse = onReverseMoney,
                onLoadMore = onLoadMoreStatement,
                search = statementSearch,
                onSearchChange = onStatementSearch,
                rangeFrom = statementFrom,
                rangeTo = statementTo,
                onRangeChange = onStatementRange,
                ascending = statementAscending,
                onToggleSort = onStatementSortToggle,
                onClearFilters = onStatementClearFilters,
                onPrint = onPrintOpen,
            )
        }
    }

    if (printDialogOpen) {
        PrintStatementDialog(
            from = printFrom,
            to = printTo,
            includeNotes = printIncludeNotes,
            generating = printGenerating,
            error = printError,
            onRange = onPrintRange,
            onNotesToggle = onPrintNotesToggle,
            onGenerate = onPrintGenerate,
            onDismiss = onPrintDialogClose,
        )
    }
    if (printPdfUrl != null) {
        StatementPdfWindow(url = printPdfUrl, partyName = entity.name, onClose = onPdfClose)
    }

    if (showArchiveDialog) {
        AromexDialog(
            title = strings(Strings.entity_detail_archive_title),
            message = strings(Strings.entity_detail_archive_body),
            confirmLabel = strings(Strings.entity_detail_archive_confirm),
            dismissLabel = strings(Strings.entity_detail_cancel),
            onConfirm = { showArchiveDialog = false; onArchive(entity) },
            onDismiss = { showArchiveDialog = false },
            destructive = true,
        )
    }
}

@Composable
private fun DetailField(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = AromexTheme.typography.fieldLabel, color = Color.White.copy(alpha = 0.45f))
            SelectionContainer {
                Text(value, style = AromexTheme.typography.body, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── Add / Edit modal dialog ───────────────────────────────────────────────────

@Composable
private fun EntityFormDialog(
    existing: Entity?,
    saving: Boolean,
    saveError: String?,
    currency: String,
    onClose: () -> Unit,
    onSubmit: (EntityInput) -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val isDark = isSystemInDarkTheme()

    // ── Form state
    val initialCountry = remember(existing) {
        Countries.byDialPrefix(existing?.phones?.firstOrNull()) ?: Countries.byIso(Countries.DEFAULT_ISO)!!
    }
    val initialNumber = remember(existing) {
        val p = existing?.phones?.firstOrNull() ?: return@remember ""
        p.removePrefix(initialCountry.dialCode).trim()
    }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var nameWasTouched by remember { mutableStateOf(false) }
    var countryIso by remember { mutableStateOf(initialCountry.iso) }
    var phone by remember { mutableStateOf(initialNumber) }
    var email by remember { mutableStateOf(existing?.email ?: "") }
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var taxNumber by remember { mutableStateOf(existing?.taxNumber ?: "") }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var isCustomer by remember { mutableStateOf(existing?.roles?.contains(EntityRole.CUSTOMER) ?: false) }
    var isSupplier by remember { mutableStateOf(existing?.roles?.contains(EntityRole.SUPPLIER) ?: false) }
    var openingAmount by remember { mutableStateOf("") }
    var openingCredit by remember { mutableStateOf(false) }
    var showCountryPicker by remember { mutableStateOf(false) }
    var showUnsaved by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    val country = remember(countryIso) { Countries.byIso(countryIso) ?: initialCountry }

    val emailError = if (email.isNotBlank() && !EMAIL_REGEX.matcher(email).matches()) strings(Strings.entity_form_error_email_invalid) else null
    val phoneError = if (phone.isNotEmpty() && phone.length < 10) "Phone number must be 10 digits" else null
    val nameError = if (nameWasTouched && name.trim().isEmpty()) strings(Strings.entity_form_error_name_required) else null
    val canSave = name.trim().isNotEmpty() && emailError == null && phoneError == null && !saving

    val dirty = name != (existing?.name ?: "") ||
        phone != initialNumber || countryIso != initialCountry.iso ||
        email != (existing?.email ?: "") ||
        address != (existing?.address ?: "") ||
        taxNumber != (existing?.taxNumber ?: "") ||
        notes != (existing?.notes ?: "") ||
        isCustomer != (existing?.roles?.contains(EntityRole.CUSTOMER) ?: false) ||
        isSupplier != (existing?.roles?.contains(EntityRole.SUPPLIER) ?: false) ||
        openingAmount.isNotBlank()

    val guardedClose = { if (dirty) showUnsaved = true else onClose() }

    fun buildInput(): EntityInput {
        val fullPhone = phone.trim().takeIf { it.isNotEmpty() }?.let { "${country.dialCode} $it" }
        val opening = openingAmount.trim().takeIf { existing == null && it.isNotEmpty() }?.let {
            OpeningBalance(amount = it, direction = if (openingCredit) BalanceDirection.CREDIT else BalanceDirection.RECEIVABLE)
        }
        return EntityInput(
            name = name.trim(),
            phones = listOfNotNull(fullPhone),
            email = email.trim().takeIf { it.isNotEmpty() },
            address = address.trim().takeIf { it.isNotEmpty() },
            roles = buildSet {
                if (isCustomer) add(EntityRole.CUSTOMER)
                if (isSupplier) add(EntityRole.SUPPLIER)
            },
            notes = notes.trim().takeIf { it.isNotEmpty() },
            taxNumber = taxNumber.trim().takeIf { it.isNotEmpty() },
            opening = opening,
        )
    }

    val scrimColor = if (isDark) colors.background.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.65f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .onPreviewKeyEvent { event ->
                when {
                    event.key == Key.Escape && event.type == KeyEventType.KeyDown -> { guardedClose(); true }
                    event.key == Key.Enter && event.type == KeyEventType.KeyDown && canSave && !saving -> { showSaveConfirm = true; true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 660.dp)
                .heightIn(max = 700.dp)
                .shadow(elevation = 24.dp, shape = RoundedCornerShape(dims.radiusCard))
                .clip(RoundedCornerShape(dims.radiusCard))
                .background(colors.surface),
        ) {
            // ── Blue header
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
                    ) { Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
                    Spacer(Modifier.width(dims.space12))
                    Column {
                        Text(
                            if (existing == null) strings(Strings.entity_form_new_eyebrow) else strings(Strings.entity_form_edit_eyebrow),
                            style = AromexTheme.typography.fieldLabel,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Text(
                            if (existing == null) strings(Strings.entity_form_new_title) else strings(Strings.entity_form_edit_title),
                            style = AromexTheme.typography.sectionTitle,
                            color = Color.White,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { guardedClose() }) {
                        Icon(Icons.Filled.Close, strings(Strings.entity_form_close_cd), tint = Color.White)
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
                // Role cards
                Row(horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
                    RoleSelectCard(
                        title = strings(Strings.entity_form_role_customer_title),
                        subtitle = strings(Strings.entity_form_role_customer_subtitle),
                        selected = isCustomer,
                        onToggle = { isCustomer = !isCustomer },
                        modifier = Modifier.weight(1f),
                    )
                    RoleSelectCard(
                        title = strings(Strings.entity_form_role_supplier_title),
                        subtitle = strings(Strings.entity_form_role_supplier_subtitle),
                        selected = isSupplier,
                        onToggle = { isSupplier = !isSupplier },
                        modifier = Modifier.weight(1f),
                    )
                }

                FormSectionHeader(strings(Strings.entity_form_section_contact))

                // Full name
                LabeledTextField(
                    label = strings(Strings.entity_form_full_name_label),
                    value = name,
                    onValueChange = { v ->
                        if (v.isEmpty() || !v.startsWith(" ")) {
                            name = v.autoTitleCase()
                            if (v.isNotEmpty()) nameWasTouched = true
                        }
                    },
                    placeholder = strings(Strings.entity_form_full_name_placeholder),
                    enabled = !saving,
                    errorMessage = nameError,
                )

                // Phone + Email
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dims.space12)) {
                    PhoneFormField(
                        label = strings(Strings.entity_form_phone_label),
                        phone = phone,
                        country = country,
                        enabled = !saving,
                        onPhoneChange = { v -> phone = v.filter(Char::isDigit).take(10) },
                        onCountryClick = { showCountryPicker = true },
                        errorMessage = phoneError,
                        modifier = Modifier.weight(1f),
                    )
                    LabeledTextField(
                        label = strings(Strings.entity_form_email_label),
                        value = email,
                        onValueChange = { v -> email = v.filter { it != ' ' }.lowercase() },
                        placeholder = strings(Strings.entity_form_email_placeholder),
                        enabled = !saving,
                        keyboardType = KeyboardType.Email,
                        errorMessage = emailError,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Address
                LabeledTextField(
                    label = strings(Strings.entity_form_address_label),
                    value = address,
                    onValueChange = { address = it.autoCapFirstChar() },
                    placeholder = strings(Strings.entity_form_address_placeholder),
                    enabled = !saving,
                )

                // Tax number (ticket #106) — optional; prints on this party's invoices when they buy.
                LabeledTextField(
                    label = strings(Strings.entity_form_tax_number_label),
                    value = taxNumber,
                    onValueChange = { taxNumber = it },
                    placeholder = strings(Strings.entity_form_tax_number_placeholder),
                    enabled = !saving,
                )

                FormSectionHeader(strings(Strings.entity_form_section_notes))

                // Notes (multiline — built directly since LabeledTextField is singleLine)
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.autoCapFirstChar() },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    shape = RoundedCornerShape(dims.radiusField),
                    placeholder = { Text(strings(Strings.entity_form_notes_placeholder), style = AromexTheme.typography.body, color = colors.textTertiary) },
                    textStyle = AromexTheme.typography.body,
                    minLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        disabledTextColor = colors.textTertiary,
                        focusedContainerColor = colors.surface,
                        unfocusedContainerColor = colors.surface,
                        disabledContainerColor = colors.surfaceAlt,
                        focusedBorderColor = colors.brand,
                        unfocusedBorderColor = colors.border,
                        disabledBorderColor = colors.border,
                        cursorColor = colors.brand,
                    ),
                )

                // Opening balance (Add only)
                if (existing == null) {
                    FormSectionHeader(strings(Strings.entity_form_section_opening))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(dims.space16)) {
                        // Direction toggle
                        Column(Modifier.weight(1f)) {
                            Text("DIRECTION", style = AromexTheme.typography.fieldLabel, color = colors.textTertiary, modifier = Modifier.padding(bottom = dims.space8))
                            Row(
                                modifier = Modifier
                                    .height(dims.fieldHeight)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(dims.radiusField))
                                    .border(dims.borderField, colors.border, RoundedCornerShape(dims.radiusField)),
                            ) {
                                listOf(
                                    false to strings(Strings.entity_form_opening_to_receive),
                                    true to strings(Strings.entity_form_opening_to_give),
                                ).forEachIndexed { idx, (credit, label) ->
                                    // false = To Receive = green, true = To Give = red
                                    val chipColor = if (credit) colors.error else colors.success
                                    val isActive = openingCredit == credit
                                    if (idx > 0) Box(Modifier.width(dims.borderField).fillMaxHeight().background(colors.border))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .background(if (isActive) chipColor.copy(alpha = 0.12f) else colors.surface)
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable(enabled = !saving) { openingCredit = credit },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(label, style = AromexTheme.typography.button, color = if (isActive) chipColor else colors.textSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                        // Amount field
                        Column(Modifier.weight(1f)) {
                            var amountFocused by remember { mutableStateOf(false) }
                            Text(strings(Strings.entity_form_amount_label), style = AromexTheme.typography.fieldLabel, color = colors.textTertiary, modifier = Modifier.padding(bottom = dims.space8))
                            Row(
                                modifier = Modifier
                                    .height(dims.fieldHeight)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(dims.radiusField))
                                    .border(dims.borderField, if (amountFocused) colors.brand else colors.border, RoundedCornerShape(dims.radiusField)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.fillMaxHeight().background(colors.surfaceAlt).padding(horizontal = dims.space12),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(MoneyFormat.symbolOf(currency), style = AromexTheme.typography.button, color = colors.textSecondary)
                                }
                                Box(Modifier.width(dims.borderField).fillMaxHeight().background(colors.border))
                                BasicTextField(
                                    value = openingAmount,
                                    onValueChange = { v ->
                                        val filtered = v.filter { it.isDigit() || it == '.' }
                                        val dotIdx = filtered.indexOf('.')
                                        openingAmount = if (dotIdx == -1) filtered
                                        else filtered.substring(0, dotIdx + 1) + filtered.drop(dotIdx + 1).filter(Char::isDigit)
                                    },
                                    textStyle = AromexTheme.typography.body.copy(color = colors.textPrimary),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    enabled = !saving,
                                    cursorBrush = SolidColor(colors.brand),
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = dims.space12)
                                        .onFocusChanged { amountFocused = it.isFocused },
                                    decorationBox = { inner ->
                                        Box {
                                            if (openingAmount.isEmpty()) Text(strings(Strings.entity_form_amount_placeholder), style = AromexTheme.typography.body, color = colors.textTertiary)
                                            inner()
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    Text(strings(Strings.entity_form_opening_readonly_note), style = AromexTheme.typography.hint, color = colors.textTertiary)
                }
            }

            // ── Pinned footer
            HorizontalDivider(color = colors.border)
            if (saveError != null) {
                Text(saveError, style = AromexTheme.typography.hint, color = colors.error, modifier = Modifier.padding(horizontal = dims.space20, vertical = dims.space8))
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space16),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { guardedClose() }, enabled = !saving) {
                    Text(strings(Strings.entity_detail_cancel), style = AromexTheme.typography.button, color = colors.textSecondary)
                }
                // Save button — wrapContentWidth so label never truncates
                Button(
                    onClick = { if (canSave && !saving) onSubmit(buildInput()) },
                    enabled = canSave,
                    modifier = Modifier.height(dims.buttonHeight).pointerHoverIcon(PointerIcon.Hand),
                    shape = RoundedCornerShape(dims.radiusButton),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.brand,
                        contentColor = Color.White,
                        disabledContainerColor = colors.brandTint,
                        disabledContentColor = colors.disabled,
                    ),
                ) {
                    if (saving) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dims.space8)) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Text(strings(Strings.entity_form_saving), style = AromexTheme.typography.button)
                        }
                    } else {
                        Text(strings(Strings.entity_form_save_contact), style = AromexTheme.typography.button)
                    }
                }
            }
        }
    }

    if (showCountryPicker) {
        CountryPickerDialog(
            selected = country,
            onSelect = { selected -> countryIso = selected.iso; showCountryPicker = false },
            onDismiss = { showCountryPicker = false },
        )
    }

    if (showUnsaved) {
        AromexDialog(
            title = strings(Strings.entity_form_unsaved_title),
            message = strings(Strings.entity_form_unsaved_body),
            confirmLabel = strings(Strings.entity_form_unsaved_discard),
            dismissLabel = strings(Strings.entity_form_unsaved_keep),
            onConfirm = { showUnsaved = false; onClose() },
            onDismiss = { showUnsaved = false },
            destructive = true,
        )
    }

    if (showSaveConfirm) {
        AromexDialog(
            title = strings(Strings.entity_form_save_contact),
            message = "Save this contact?",
            confirmLabel = strings(Strings.entity_form_save_contact),
            dismissLabel = strings(Strings.entity_detail_cancel),
            onConfirm = { showSaveConfirm = false; onSubmit(buildInput()) },
            onDismiss = { showSaveConfirm = false },
        )
    }
}

// ── Form sub-composables ──────────────────────────────────────────────────────

@Composable
private fun RoleSelectCard(title: String, subtitle: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val hoverSrc = remember { MutableInteractionSource() }
    val isHovered by hoverSrc.collectIsHoveredAsState()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(dims.radiusCard))
            .background(if (selected) colors.brandTint else if (isHovered) colors.surfaceAlt else colors.surface)
            .border(if (selected) dims.borderFieldFocused else dims.borderField, if (selected) colors.brand else colors.border, RoundedCornerShape(dims.radiusCard))
            .hoverable(hoverSrc)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onToggle)
            .padding(dims.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AromexTheme.typography.bodyStrong, color = colors.textPrimary)
            Text(subtitle, style = AromexTheme.typography.hint, color = colors.textSecondary)
        }
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) colors.brand else Color.Transparent)
                .border(if (selected) 0.dp else dims.borderField, if (selected) colors.brand else colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(13.dp))
        }
    }
}

@Composable
private fun FormSectionHeader(text: String) {
    val colors = AromexTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = AromexTheme.typography.fieldLabel, color = colors.brand)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(color = colors.border, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PhoneFormField(
    label: String,
    phone: String,
    country: Country,
    enabled: Boolean,
    onPhoneChange: (String) -> Unit,
    onCountryClick: () -> Unit,
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        errorMessage != null -> colors.error
        focused -> colors.brand
        else -> colors.border
    }
    Column(modifier) {
        Text(label, style = AromexTheme.typography.fieldLabel.copy(color = if (errorMessage != null) colors.error else colors.textTertiary), modifier = Modifier.padding(bottom = dims.space8))
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
                Text(country.iso, style = AromexTheme.typography.fieldLabel.copy(fontWeight = FontWeight.Bold), color = AromexTheme.colors.textPrimary)
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
                        if (phone.isEmpty()) Text(strings(Strings.entity_form_phone_placeholder), style = AromexTheme.typography.body, color = colors.textTertiary)
                        inner()
                    }
                },
            )
        }
        if (errorMessage != null) {
            Spacer(Modifier.height(dims.space8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Error, null, tint = colors.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(dims.space8))
                Text(errorMessage, style = AromexTheme.typography.hint, color = colors.error)
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private data class BalanceDisplay(val text: String, val color: Color)

private fun balanceDisplay(balance: EntityBalance?, currency: String, colors: AromexColors): BalanceDisplay {
    if (balance == null) return BalanceDisplay("…", colors.textTertiary)
    return when (balance.direction) {
        BalanceDirection.RECEIVABLE -> BalanceDisplay(MoneyFormat.formatSigned(Money.abs(balance.net), currency, positive = true, negative = false), colors.success)
        BalanceDirection.CREDIT -> BalanceDisplay(MoneyFormat.formatSigned(Money.abs(balance.net), currency, positive = false, negative = true), colors.error)
        BalanceDirection.SETTLED -> BalanceDisplay(MoneyFormat.format(balance.net, currency), colors.textTertiary)
    }
}

private fun String.autoTitleCase(): String =
    split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }

private fun String.autoCapFirstChar(): String =
    if (isEmpty()) this else this[0].uppercaseChar() + substring(1)

private fun isoToFlagEmoji(iso: String): String {
    val upper = iso.uppercase()
    return upper.map { char -> 0x1F1E0 + (char.code - 'A'.code) }
        .joinToString("") { String(Character.toChars(it)) }
}

private val EMAIL_REGEX: Pattern = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
