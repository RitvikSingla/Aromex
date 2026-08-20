package com.humblesolutions.aromex.ui.entities

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.OpeningBalance
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.components.AromexDialog
import com.humblesolutions.aromex.ui.components.BrandHeader
import com.humblesolutions.aromex.ui.components.CountryDialCodeTrigger
import com.humblesolutions.aromex.ui.components.CountryPickerDialog
import com.humblesolutions.aromex.ui.components.PrimaryButton
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.Countries
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.MoneyFormat
import com.humblesolutions.aromex.util.isOnline
import java.math.BigDecimal

/**
 * Entities feature (ticket #37 + revision): a **NavHost** graph — List → Detail,
 * List → Add, Detail → Edit as real destinations, so Add/Edit are separate
 * screens (not dialogs) and the back stack survives configuration changes.
 *
 * **Presentation only** — the shared [EntitiesViewModel] / use cases / repos /
 * HL dual-write are untouched. The single [EntitiesViewModel] instance (retained
 * across config changes by `viewModel()`) is the cache; every destination reads
 * its [EntitiesUiState].
 */
@Composable
fun EntitiesFeature(
    authenticated: AuthenticatedSession,
    onExit: () -> Unit,
) {
    val vm: EntitiesViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val currency = state.session?.currency ?: authenticated.session.currency

    LaunchedEffect(authenticated.session.uid) {
        vm.bind(authenticated.session, authenticated.config)
    }

    if (state.noAccess) {
        NoAccessScreen(onBack = onExit)
        return
    }

    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            EntitiesListScreen(
                state = state,
                currency = currency,
                onBack = onExit,
                onQueryChange = vm::onQueryChange,
                onRefresh = vm::refreshBalances,
                onAdd = { if (state.canManage) nav.navigate("add") },
                onOpen = { entity -> nav.navigate("detail/${entity.id}") },
            )
        }
        composable("detail/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val row = state.rows.firstOrNull { it.entity.id == id }
            if (row == null) {
                LaunchedEffect(id) { nav.popBackStack() }
            } else {
                EntityDetailScreen(
                    row = row,
                    currency = currency,
                    canManage = state.canManage,
                    session = authenticated.session,
                    config = authenticated.config,
                    onBack = { nav.popBackStack() },
                    onEdit = { nav.navigate("edit/$id") },
                    onArchive = { vm.archive(row.entity); nav.popBackStack() },
                )
            }
        }
        composable("add") {
            EntityFormScreen(
                existing = null,
                currency = currency,
                saving = state.saving,
                saveError = state.saveError,
                onClose = { nav.popBackStack() },
                onSubmit = { input -> vm.save(input, null) { ok -> if (ok) nav.popBackStack() } },
            )
        }
        composable("edit/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val existing = state.rows.firstOrNull { it.entity.id == id }?.entity
            EntityFormScreen(
                existing = existing,
                currency = currency,
                saving = state.saving,
                saveError = state.saveError,
                onClose = { nav.popBackStack() },
                onSubmit = { input -> vm.save(input, id) { ok -> if (ok) nav.popBackStack() } },
            )
        }
    }
}

@Composable
private fun NoAccessScreen(onBack: () -> Unit) {
    val colors = AromexTheme.colors
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(colors.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            strings(Strings.entities_no_access),
            style = AromexTheme.typography.body,
            color = colors.textSecondary,
            modifier = Modifier.padding(AromexTheme.dimensions.space24),
            textAlign = TextAlign.Center,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LIST
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EntitiesListScreen(
    state: EntitiesUiState,
    currency: String,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (Entity) -> Unit,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val context = LocalContext.current

    BackHandler(onBack = onBack)

    // Offline dialog: shown when a pull-to-refresh is attempted with no
    // connection, or when a load/refresh fails on a network error.
    var offlineShown by remember { mutableStateOf(false) }
    var errorDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(state.error) { if (state.error != null) errorDismissed = false }
    val showOffline = offlineShown || (state.error != null && !errorDismissed)
    if (showOffline) {
        AromexDialog(
            title = strings(Strings.offline_title),
            message = strings(Strings.offline_body),
            confirmLabel = strings(Strings.offline_retry),
            onConfirm = {
                offlineShown = false
                errorDismissed = true
                if (isOnline(context)) onRefresh()
            },
            dismissLabel = strings(Strings.offline_dismiss),
            onDismiss = { offlineShown = false; errorDismissed = true },
        )
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            BrandHeader(bottomRadius = 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            strings(Strings.entities_list_eyebrow),
                            style = AromexTheme.typography.fieldLabel,
                            color = colors.onBrand.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(dimensions.space4))
                        Text(
                            strings(Strings.entities_list_title),
                            style = AromexTheme.typography.screenTitle,
                            color = colors.onBrand,
                        )
                    }
                    if (state.canManage) {
                        HeaderCircleButton(
                            icon = Icons.Filled.Add,
                            contentDescription = strings(Strings.entities_add_cd),
                            onClick = onAdd,
                        )
                    }
                }
                Spacer(Modifier.height(dimensions.space16))
                HeaderSearchField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = strings(Strings.entities_search_placeholder),
                )
            }

            SummaryBar(rows = state.rows, currency = currency)

            PullToRefreshBox(
                isRefreshing = state.isLoadingBalances,
                onRefresh = { if (isOnline(context)) onRefresh() else offlineShown = true },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.rows.isEmpty() && state.error != null ->
                        ListMessage(
                            title = strings(Strings.entities_error_title),
                            actionLabel = strings(Strings.entities_error_retry),
                            onAction = { if (isOnline(context)) onRefresh() else offlineShown = true },
                        )

                    state.rows.isEmpty() && state.isLoadingBalances ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = colors.brand)
                                Spacer(Modifier.height(dimensions.space12))
                                Text(
                                    strings(Strings.entities_loading),
                                    style = AromexTheme.typography.body,
                                    color = colors.textSecondary,
                                )
                            }
                        }

                    state.rows.isEmpty() ->
                        ListMessage(
                            title = strings(Strings.entities_empty_title),
                            actionLabel = if (state.canManage) strings(Strings.entities_empty_cta) else null,
                            onAction = onAdd,
                        )

                    else ->
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = dimensions.screenPadding,
                                end = dimensions.screenPadding,
                                top = dimensions.space12,
                                bottom = dimensions.space48 + dimensions.space48,
                            ),
                            verticalArrangement = Arrangement.spacedBy(dimensions.space12),
                        ) {
                            items(state.rows, key = { it.entity.id }) { row ->
                                ContactCard(row = row, currency = currency, onClick = { onOpen(row.entity) })
                            }
                        }
                }
            }
        }

        if (state.canManage) {
            IconButton(
                onClick = onAdd,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(dimensions.space20)
                    .size(56.dp)
                    .background(colors.brand, CircleShape),
            ) {
                Icon(Icons.Filled.Add, strings(Strings.entities_add_cd), tint = colors.onBrand)
            }
        }
    }
}

@Composable
private fun SummaryBar(rows: List<EntityRow>, currency: String) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions

    val (toReceive, toGive) = remember(rows) {
        var receive = BigDecimal.ZERO
        var give = BigDecimal.ZERO
        rows.forEach { row ->
            val b = row.balance ?: return@forEach
            val amount = runCatching { BigDecimal(Money.abs(b.net)) }.getOrNull() ?: BigDecimal.ZERO
            when (b.direction) {
                BalanceDirection.RECEIVABLE -> receive = receive.add(amount)
                BalanceDirection.CREDIT -> give = give.add(amount)
                BalanceDirection.SETTLED -> {}
            }
        }
        receive.toPlainString() to give.toPlainString()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(vertical = dimensions.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryHalf(
            label = strings(Strings.entities_summary_to_receive),
            amount = MoneyFormat.format(toReceive, currency),
            color = colors.success,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(1.dp).height(36.dp).background(colors.border))
        SummaryHalf(
            label = strings(Strings.entities_summary_to_give),
            amount = MoneyFormat.format(toGive, currency),
            color = colors.error,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = colors.border)
}

@Composable
private fun SummaryHalf(label: String, amount: String, color: Color, modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = AromexTheme.typography.fieldLabel, color = colors.textTertiary)
        Spacer(Modifier.height(AromexTheme.dimensions.space4))
        Text(
            amount,
            style = AromexTheme.typography.sectionTitle.copy(fontWeight = FontWeight.Bold),
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ContactCard(row: EntityRow, currency: String, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val entity = row.entity
    val parts = balanceParts(row.balance, currency)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimensions.radiusCard))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(dimensions.radiusCard))
            .clickable(onClick = onClick)
            .padding(dimensions.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = entity.name)
        Spacer(Modifier.width(dimensions.space12))
        Column(Modifier.weight(1f)) {
            Text(
                entity.name.ifBlank { "—" },
                style = AromexTheme.typography.bodyStrong,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entity.roles.isNotEmpty()) {
                Spacer(Modifier.height(dimensions.space4))
                RoleGlyphs(entity.roles)
            }
        }
        Spacer(Modifier.width(dimensions.space8))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                parts.amount,
                style = AromexTheme.typography.bodyStrong.copy(fontWeight = FontWeight.Bold),
                color = parts.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(parts.subLabel, style = AromexTheme.typography.hint, color = colors.textTertiary)
        }
    }
}

@Composable
private fun RoleGlyphs(roles: Set<EntityRole>) {
    val colors = AromexTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(AromexTheme.dimensions.space4)) {
        roles.sortedBy { it.ordinal }.forEach { role ->
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(4.dp)).background(colors.brandTint),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = role.glyph(),
                    contentDescription = null,
                    tint = colors.brand,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}

private fun EntityRole.glyph(): ImageVector = when (this) {
    EntityRole.CUSTOMER -> Icons.Filled.Person
    EntityRole.SUPPLIER -> Icons.Filled.Inventory2
    EntityRole.MIDDLEMAN -> Icons.Filled.SwapHoriz
}

@Composable
private fun ListMessage(title: String, actionLabel: String?, onAction: () -> Unit) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(colors.surfaceAlt),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Person, null, tint = colors.textTertiary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(dimensions.space16))
            Text(title, style = AromexTheme.typography.sectionTitle, color = colors.textSecondary)
            if (actionLabel != null) {
                Spacer(Modifier.height(dimensions.space20))
                Box(Modifier.fillMaxWidth().padding(horizontal = dimensions.space48)) {
                    PrimaryButton(label = actionLabel, onClick = onAction)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DETAIL — fixed blue hero (balance + contact pinned); only transactions scroll
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EntityDetailScreen(
    row: EntityRow,
    currency: String,
    canManage: Boolean,
    session: UserSession,
    config: FirebaseClientConfig,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val entity = row.entity
    var menuOpen by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf(false) }
    var showPrint by remember { mutableStateOf(false) }
    val actionsEnabled = canManage && !entity.isWalkIn

    BackHandler(onBack = onBack)

    if (showPrint) {
        PrintStatementDialog(
            entity = entity,
            session = session,
            config = config,
            onDismiss = { showPrint = false },
        )
    }

    if (confirmArchive) {
        AromexDialog(
            title = strings(Strings.entity_detail_archive_title),
            message = strings(Strings.entity_detail_archive_body),
            confirmLabel = strings(Strings.entity_detail_archive_confirm),
            onConfirm = { confirmArchive = false; onArchive() },
            dismissLabel = strings(Strings.entity_detail_cancel),
            onDismiss = { confirmArchive = false },
            destructive = true,
        )
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        // Fixed (non-scrolling) flat blue hero, radius 0 — header + balance card
        // + contact rows, all translucent-on-blue, per the approved design.
        BrandHeader(bottomRadius = 0.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderCircleButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = strings(Strings.entity_detail_back_cd),
                    onClick = onBack,
                )
                Spacer(Modifier.width(dimensions.space12))
                Column(Modifier.weight(1f)) {
                    Text(
                        strings(Strings.entity_detail_eyebrow),
                        style = AromexTheme.typography.fieldLabel,
                        color = colors.onBrand.copy(alpha = 0.7f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            entity.name,
                            style = AromexTheme.typography.screenTitle.copy(fontWeight = FontWeight.Bold),
                            color = colors.onBrand,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (entity.roles.isNotEmpty()) {
                            Spacer(Modifier.width(dimensions.space8))
                            RoleBadgesOnBlue(entity.roles)
                        }
                    }
                }
                // The initials monogram doubles as the actions affordance
                // (Edit / Archive) — matches the mock's top-right and keeps the
                // actions reachable. Disabled for Walk-in / view-only users.
                Box {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.onBrand.copy(alpha = 0.15f))
                            .then(if (actionsEnabled) Modifier.clickable { menuOpen = true } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            initialsOf(entity.name),
                            style = AromexTheme.typography.button.copy(fontWeight = FontWeight.Bold),
                            color = colors.onBrand,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(strings(Strings.entity_detail_edit)) },
                            onClick = { menuOpen = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(strings(Strings.entity_detail_archive)) },
                            onClick = { menuOpen = false; confirmArchive = true },
                        )
                    }
                }
            }

            Spacer(Modifier.height(dimensions.space20))
            BalanceCardOnBlue(balance = row.balance, currency = currency)

            val contactRows = buildList {
                entity.phones.filter { it.isNotBlank() }.forEach { add(Icons.Filled.Phone to it) }
                entity.email?.takeIf { it.isNotBlank() }?.let { add(Icons.Filled.MailOutline to it) }
                entity.address?.takeIf { it.isNotBlank() }?.let { add(Icons.Filled.LocationOn to it) }
                entity.notes?.takeIf { it.isNotBlank() }?.let { add(Icons.Filled.Description to it) }
            }
            if (contactRows.isNotEmpty()) {
                Spacer(Modifier.height(dimensions.space16))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(dimensions.radiusCard))
                        .background(colors.onBrand.copy(alpha = 0.10f)),
                ) {
                    contactRows.forEachIndexed { index, (icon, value) ->
                        if (index > 0) HorizontalDivider(color = colors.onBrand.copy(alpha = 0.12f))
                        Row(
                            Modifier.fillMaxWidth().padding(dimensions.space16),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(icon, null, tint = colors.onBrand.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(dimensions.space12))
                            Text(
                                value,
                                style = AromexTheme.typography.body,
                                color = colors.onBrand,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // Only the transactions area scrolls.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = dimensions.screenPadding),
        ) {
            if (entity.isWalkIn) {
                Text(
                    strings(Strings.entity_detail_walkin_note),
                    style = AromexTheme.typography.hint,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(top = dimensions.space12),
                )
            }
            SectionHeaderRule(strings(Strings.entity_detail_transactions))
            TextButton(
                onClick = { showPrint = true },
                modifier = Modifier.padding(horizontal = dimensions.space20),
            ) {
                Icon(Icons.Filled.Description, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(dimensions.space8))
                Text(strings(Strings.statement_print))
            }
            Column(
                Modifier.fillMaxWidth().padding(vertical = dimensions.space40),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(56.dp).clip(CircleShape).background(colors.surfaceAlt),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.ReceiptLong, null, tint = colors.textTertiary, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.height(dimensions.space12))
                Text(
                    strings(Strings.entity_detail_transactions_empty),
                    style = AromexTheme.typography.body,
                    color = colors.textTertiary,
                )
            }
            Spacer(Modifier.navigationBarsPadding().height(dimensions.space24))
        }
    }
}

/** Small translucent role badges shown next to the name on the blue hero. */
@Composable
private fun RoleBadgesOnBlue(roles: Set<EntityRole>) {
    val colors = AromexTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(AromexTheme.dimensions.space4)) {
        roles.sortedBy { it.ordinal }.forEach { role ->
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(colors.onBrand.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(role.glyph(), null, tint = colors.onBrand, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** Balance card rendered translucent on the blue hero: white amount + tinted pill. */
@Composable
private fun BalanceCardOnBlue(balance: EntityBalance?, currency: String) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val direction = balance?.direction ?: BalanceDirection.SETTLED
    val amount = balance?.let {
        MoneyFormat.formatSigned(
            absoluteAmount = Money.abs(it.net),
            currency = currency,
            positive = it.direction == BalanceDirection.RECEIVABLE,
            negative = false,
        )
    } ?: "—"
    val (pillLabel, pillColor) = when (direction) {
        BalanceDirection.RECEIVABLE -> strings(Strings.entity_detail_they_owe_you) to colors.success
        BalanceDirection.CREDIT -> strings(Strings.entity_detail_you_owe_them) to colors.error
        BalanceDirection.SETTLED -> strings(Strings.entity_detail_settled) to colors.onBrand
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimensions.radiusCard))
            .background(colors.onBrand.copy(alpha = 0.12f))
            .padding(dimensions.space20),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                strings(Strings.entity_detail_balance_label),
                style = AromexTheme.typography.fieldLabel,
                color = colors.onBrand.copy(alpha = 0.7f),
            )
            Spacer(Modifier.height(dimensions.space4))
            Text(
                amount,
                style = AromexTheme.typography.screenTitle.copy(fontWeight = FontWeight.Bold),
                color = colors.onBrand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier
                .clip(CircleShape)
                .background(pillColor.copy(alpha = 0.28f))
                .padding(horizontal = dimensions.space12, vertical = dimensions.space8),
        ) {
            Text(pillLabel, style = AromexTheme.typography.button, color = colors.onBrand)
        }
    }
}

@Composable
private fun SectionHeaderRule(title: String) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    Row(
        Modifier.fillMaxWidth().padding(vertical = dimensions.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AromexTheme.typography.fieldLabel, color = colors.brand)
        Spacer(Modifier.width(dimensions.space12))
        HorizontalDivider(Modifier.weight(1f), color = colors.border)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// ADD / EDIT — flat top app bar (radius 0) + rememberSaveable form
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EntityFormScreen(
    existing: Entity?,
    currency: String,
    saving: Boolean,
    saveError: String?,
    onClose: () -> Unit,
    onSubmit: (EntityInput) -> Unit,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val isEdit = existing != null
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val initialCountry = remember(existing) { Countries.byDialPrefix(existing?.phones?.firstOrNull()) }
    val initialNumber = remember(existing) {
        existing?.phones?.firstOrNull()?.removePrefix(initialCountry.dialCode)?.trim() ?: ""
    }

    // rememberSaveable → survives rotation / theme change / process-death restore.
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var phone by rememberSaveable { mutableStateOf(initialNumber) }
    var countryIso by rememberSaveable { mutableStateOf(initialCountry.iso) }
    var email by rememberSaveable { mutableStateOf(existing?.email ?: "") }
    var address by rememberSaveable { mutableStateOf(existing?.address ?: "") }
    var taxNumber by rememberSaveable { mutableStateOf(existing?.taxNumber ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var isCustomer by rememberSaveable { mutableStateOf(existing?.roles?.contains(EntityRole.CUSTOMER) ?: false) }
    var isSupplier by rememberSaveable { mutableStateOf(existing?.roles?.contains(EntityRole.SUPPLIER) ?: false) }
    var openingAmount by rememberSaveable { mutableStateOf("") }
    var openingCredit by rememberSaveable { mutableStateOf(false) } // false = RECEIVABLE, true = CREDIT

    val country = Countries.byIso(countryIso)
    var showCountryPicker by remember { mutableStateOf(false) }

    val dirty = name != (existing?.name ?: "") ||
        phone != initialNumber ||
        countryIso != initialCountry.iso ||
        email != (existing?.email ?: "") ||
        address != (existing?.address ?: "") ||
        taxNumber != (existing?.taxNumber ?: "") ||
        notes != (existing?.notes ?: "") ||
        isCustomer != (existing?.roles?.contains(EntityRole.CUSTOMER) ?: false) ||
        isSupplier != (existing?.roles?.contains(EntityRole.SUPPLIER) ?: false) ||
        openingAmount.isNotBlank()

    var showUnsaved by rememberSaveable { mutableStateOf(false) }
    val guardedClose = { if (dirty) showUnsaved = true else onClose() }

    val emailError = if (email.isNotBlank() && !isValidEmail(email)) {
        strings(Strings.entity_form_error_email_invalid)
    } else null
    val canSave = name.trim().isNotEmpty() && emailError == null && !saving

    BackHandler(enabled = true, onBack = guardedClose)

    if (showUnsaved) {
        AromexDialog(
            title = strings(Strings.entity_form_unsaved_title),
            message = strings(Strings.entity_form_unsaved_body),
            confirmLabel = strings(Strings.entity_form_unsaved_discard),
            onConfirm = { showUnsaved = false; onClose() },
            dismissLabel = strings(Strings.entity_form_unsaved_keep),
            onDismiss = { showUnsaved = false },
            destructive = true,
        )
    }

    if (showCountryPicker) {
        CountryPickerDialog(
            selected = country,
            onSelect = { countryIso = it.iso; showCountryPicker = false },
            onDismiss = { showCountryPicker = false },
        )
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            // Fixed (non-scrolling) flat blue header, radius 0 — holds the title
            // row AND the role-selection cards, per the approved design.
            BrandHeader(bottomRadius = 0.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HeaderCircleButton(
                        icon = Icons.Filled.Close,
                        contentDescription = strings(Strings.entity_form_close_cd),
                        onClick = guardedClose,
                    )
                    Spacer(Modifier.width(dimensions.space12))
                    Column(Modifier.weight(1f)) {
                        Text(
                            strings(if (isEdit) Strings.entity_form_edit_eyebrow else Strings.entity_form_new_eyebrow),
                            style = AromexTheme.typography.fieldLabel,
                            color = colors.onBrand.copy(alpha = 0.7f),
                        )
                        Text(
                            strings(if (isEdit) Strings.entity_form_edit_title else Strings.entity_form_new_title),
                            style = AromexTheme.typography.screenTitle.copy(fontWeight = FontWeight.Bold),
                            color = colors.onBrand,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(colors.onBrand.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Person, null, tint = colors.onBrand, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.height(dimensions.space16))
                Row(horizontalArrangement = Arrangement.spacedBy(dimensions.space12)) {
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
            }

            // Only the form sections scroll; the blue header above stays fixed.
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 96.dp),
            ) {
                SectionHeaderRow(strings(Strings.entity_form_section_contact))
                FormCard {
                    FormFieldRow(
                        icon = Icons.Filled.Person,
                        label = strings(Strings.entity_form_full_name_label),
                        value = name,
                        onValueChange = { name = it },
                        placeholder = strings(Strings.entity_form_full_name_placeholder),
                        imeAction = ImeAction.Next,
                    )
                    RowDivider()
                    FormFieldRow(
                        icon = Icons.Filled.Phone,
                        label = strings(Strings.entity_form_phone_label),
                        value = phone,
                        onValueChange = { phone = it },
                        placeholder = strings(Strings.entity_form_phone_placeholder),
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Next,
                        leadingContent = { CountryDialCodeTrigger(country) { showCountryPicker = true } },
                    )
                    RowDivider()
                    FormFieldRow(
                        icon = Icons.Filled.MailOutline,
                        label = strings(Strings.entity_form_email_label),
                        value = email,
                        onValueChange = { email = it },
                        placeholder = strings(Strings.entity_form_email_placeholder),
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        error = emailError,
                    )
                    RowDivider()
                    FormFieldRow(
                        icon = Icons.Filled.LocationOn,
                        label = strings(Strings.entity_form_address_label),
                        value = address,
                        onValueChange = { address = it },
                        placeholder = strings(Strings.entity_form_address_placeholder),
                        imeAction = ImeAction.Next,
                    )
                    RowDivider()
                    // Tax number (ticket #106) — optional; prints on this party's invoices when they buy.
                    FormFieldRow(
                        icon = Icons.Filled.ReceiptLong,
                        label = strings(Strings.entity_form_tax_number_label),
                        value = taxNumber,
                        onValueChange = { taxNumber = it },
                        placeholder = strings(Strings.entity_form_tax_number_placeholder),
                        imeAction = ImeAction.Next,
                    )
                }

                SectionHeaderRow(strings(Strings.entity_form_section_notes))
                FormCard {
                    FormFieldRow(
                        icon = Icons.Filled.Description,
                        label = "",
                        value = notes,
                        onValueChange = { notes = it },
                        placeholder = strings(Strings.entity_form_notes_placeholder),
                        singleLine = false,
                        imeAction = ImeAction.Done,
                    )
                }

                SectionHeaderRow(strings(Strings.entity_form_section_opening))
                FormCard {
                    if (isEdit) {
                        Text(
                            strings(Strings.entity_form_opening_readonly_note),
                            style = AromexTheme.typography.hint,
                            color = colors.textTertiary,
                            modifier = Modifier.padding(dimensions.space16),
                        )
                    } else {
                        Column(Modifier.padding(dimensions.space16)) {
                            SegmentedToggle(
                                leftLabel = strings(Strings.entity_form_opening_to_receive),
                                rightLabel = strings(Strings.entity_form_opening_to_give),
                                rightSelected = openingCredit,
                                onSelect = { openingCredit = it },
                            )
                            Spacer(Modifier.height(dimensions.space16))
                            Text(
                                strings(Strings.entity_form_amount_label),
                                style = AromexTheme.typography.fieldLabel,
                                color = colors.textTertiary,
                            )
                            Spacer(Modifier.height(dimensions.space8))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.surfaceAlt)
                                        .padding(horizontal = dimensions.space8, vertical = dimensions.space4),
                                ) {
                                    Text(
                                        MoneyFormat.symbolOf(currency),
                                        style = AromexTheme.typography.button,
                                        color = colors.textSecondary,
                                    )
                                }
                                Spacer(Modifier.width(dimensions.space12))
                                BasicTextField(
                                    value = openingAmount,
                                    onValueChange = { openingAmount = it.filter { c -> c.isDigit() || c == '.' } },
                                    textStyle = AromexTheme.typography.body.copy(color = colors.textPrimary),
                                    singleLine = true,
                                    cursorBrush = SolidColor(colors.brand),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    }),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { inner ->
                                        if (openingAmount.isEmpty()) {
                                            Text(
                                                strings(Strings.entity_form_amount_placeholder),
                                                style = AromexTheme.typography.body,
                                                color = colors.textTertiary,
                                            )
                                        }
                                        inner()
                                    },
                                )
                            }
                        }
                    }
                }

                if (saveError != null) {
                    Text(
                        saveError,
                        style = AromexTheme.typography.hint,
                        color = colors.error,
                        modifier = Modifier.padding(horizontal = dimensions.screenPadding, vertical = dimensions.space8),
                    )
                }
            }
        }

        // Pinned Save bar — intentionally NOT imePadding()'d, so it stays put
        // at the bottom and the keyboard covers it (use the keyboard's Next/Done
        // to move between fields / dismiss), rather than floating up.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colors.background)
                .navigationBarsPadding()
                .padding(horizontal = dimensions.screenPadding, vertical = dimensions.space12),
        ) {
            PrimaryButton(
                label = strings(Strings.entity_form_save),
                loadingLabel = strings(Strings.entity_form_saving),
                loading = saving,
                enabled = canSave,
                onClick = {
                    val roles = buildSet {
                        if (isCustomer) add(EntityRole.CUSTOMER)
                        if (isSupplier) add(EntityRole.SUPPLIER)
                    }
                    val fullPhone = phone.trim().takeIf { it.isNotEmpty() }?.let { "${country.dialCode} $it" }
                    val opening = openingAmount.trim().takeIf { it.isNotEmpty() && !isEdit }?.let {
                        OpeningBalance(
                            amount = it,
                            direction = if (openingCredit) BalanceDirection.CREDIT else BalanceDirection.RECEIVABLE,
                        )
                    }
                    onSubmit(
                        EntityInput(
                            name = name.trim(),
                            phones = fullPhone?.let { listOf(it) } ?: emptyList(),
                            email = email.trim().takeIf { it.isNotEmpty() },
                            address = address.trim().takeIf { it.isNotEmpty() },
                            roles = roles,
                            notes = notes.trim().takeIf { it.isNotEmpty() },
                            taxNumber = taxNumber.trim().takeIf { it.isNotEmpty() },
                            opening = opening,
                        ),
                    )
                },
            )
        }
    }
}

@Composable
private fun SectionHeaderRow(title: String) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    Row(
        Modifier.fillMaxWidth().padding(horizontal = dimensions.screenPadding, vertical = dimensions.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AromexTheme.typography.fieldLabel, color = colors.brand)
        Spacer(Modifier.width(dimensions.space12))
        HorizontalDivider(Modifier.weight(1f), color = colors.border)
    }
}

@Composable
private fun RoleSelectCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    Row(
        modifier
            .clip(RoundedCornerShape(dimensions.radiusCard))
            .background(colors.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.brand else colors.border,
                shape = RoundedCornerShape(dimensions.radiusCard),
            )
            .clickable(onClick = onToggle)
            .padding(dimensions.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AromexTheme.typography.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = AromexTheme.typography.hint, color = colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) colors.brand else Color.Transparent)
                .border(1.5.dp, if (selected) colors.brand else colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Filled.Check, null, tint = colors.onBrand, modifier = Modifier.size(13.dp))
            }
        }
    }
}

@Composable
private fun FormCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = dimensions.screenPadding)
            .clip(RoundedCornerShape(dimensions.radiusCard))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(dimensions.radiusCard)),
        content = content,
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(color = AromexTheme.colors.border)
}

@Composable
private fun FormFieldRow(
    icon: ImageVector,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    singleLine: Boolean = true,
    error: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    val hasError = error != null
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .then(
                    if (hasError) {
                        Modifier.border(1.5.dp, colors.error, RoundedCornerShape(dimensions.radiusField))
                    } else Modifier,
                )
                .padding(dimensions.space16),
            // Multi-line fields (notes) top-align the icon with the first line;
            // single-line fields stay vertically centered.
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            Icon(
                icon,
                null,
                tint = if (hasError) colors.error else colors.brand,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(dimensions.space12))
            Column(Modifier.weight(1f)) {
                if (label.isNotEmpty()) {
                    Text(
                        label,
                        style = AromexTheme.typography.fieldLabel,
                        color = if (hasError) colors.error else colors.textTertiary,
                    )
                    Spacer(Modifier.height(dimensions.space4))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    leadingContent?.invoke()
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = AromexTheme.typography.body.copy(color = colors.textPrimary),
                        singleLine = singleLine,
                        cursorBrush = SolidColor(colors.brand),
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                        // Next advances focus; Done dismisses the keyboard. This
                        // keeps the pinned Save button from having to float up.
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Next) },
                            onDone = { focusManager.clearFocus(); keyboardController?.hide() },
                        ),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (value.isEmpty()) {
                                Text(placeholder, style = AromexTheme.typography.body, color = colors.textTertiary)
                            }
                            inner()
                        },
                    )
                }
            }
        }
        if (hasError) {
            Text(
                error!!,
                style = AromexTheme.typography.hint,
                color = colors.error,
                modifier = Modifier.padding(start = dimensions.space48, bottom = dimensions.space8),
            )
        }
    }
}

@Composable
private fun SegmentedToggle(
    leftLabel: String,
    rightLabel: String,
    rightSelected: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimensions.radiusField))
            .border(1.dp, colors.border, RoundedCornerShape(dimensions.radiusField)),
    ) {
        SegmentHalf(leftLabel, selected = !rightSelected, onClick = { onSelect(false) }, modifier = Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(44.dp).background(colors.border))
        SegmentHalf(rightLabel, selected = rightSelected, onClick = { onSelect(true) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SegmentHalf(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AromexTheme.colors
    Box(
        modifier
            .background(if (selected) colors.brand else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = AromexTheme.dimensions.space12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = AromexTheme.typography.button,
            color = if (selected) colors.onBrand else colors.textSecondary,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared small pieces
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeaderCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = AromexTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(40.dp).background(colors.onBrand.copy(alpha = 0.15f), CircleShape),
    ) {
        Icon(icon, contentDescription, tint = colors.onBrand.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun HeaderSearchField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimensions.radiusField))
            .background(colors.onBrand.copy(alpha = 0.15f))
            .padding(horizontal = dimensions.space16, vertical = dimensions.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = colors.onBrand.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(dimensions.space8))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = AromexTheme.typography.body.copy(color = colors.onBrand),
            singleLine = true,
            cursorBrush = SolidColor(colors.onBrand),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, style = AromexTheme.typography.body, color = colors.onBrand.copy(alpha = 0.7f))
                }
                inner()
            },
        )
    }
}

@Composable
private fun Avatar(name: String) {
    val color = avatarColor(name)
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(name),
            style = AromexTheme.typography.button.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
        )
    }
}

private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts.first().take(1) + parts.last().take(1)).uppercase()
    }
}

private val AVATAR_PALETTE = listOf(
    Color(0xFF40548A),
    Color(0xFFCC6633),
    Color(0xFF7A5AF8),
    Color(0xFF2E9E66),
    Color(0xFFD64545),
    Color(0xFF2F8FB3),
)

private fun avatarColor(name: String): Color {
    if (name.isBlank()) return AVATAR_PALETTE.first()
    val index = (name.hashCode() and Int.MAX_VALUE) % AVATAR_PALETTE.size
    return AVATAR_PALETTE[index]
}

private data class BalanceParts(val amount: String, val subLabel: String, val color: Color)

@Composable
private fun balanceParts(balance: EntityBalance?, currency: String): BalanceParts {
    val colors = AromexTheme.colors
    if (balance == null) {
        return BalanceParts("—", strings(Strings.entities_row_settled), colors.textTertiary)
    }
    val abs = Money.abs(balance.net)
    return when (balance.direction) {
        BalanceDirection.RECEIVABLE -> BalanceParts(
            amount = MoneyFormat.formatSigned(abs, currency, positive = true, negative = false),
            subLabel = strings(Strings.entities_row_to_receive),
            color = colors.success,
        )
        BalanceDirection.CREDIT -> BalanceParts(
            amount = MoneyFormat.format(abs, currency),
            subLabel = strings(Strings.entities_row_to_give),
            color = colors.error,
        )
        BalanceDirection.SETTLED -> BalanceParts(
            amount = MoneyFormat.format(abs, currency),
            subLabel = strings(Strings.entities_row_settled),
            color = colors.textTertiary,
        )
    }
}

private fun isValidEmail(value: String): Boolean =
    Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(value.trim())
