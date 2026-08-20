package com.humblesolutions.aromex.ui.sales.history

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AuthenticatedSession
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleRecordLine
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.MoneyFormat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Android Sales History (ticket #84) — bare-but-stable phone screen on the shared #83 query layer.
 * Gates the whole feature on `sales` VIEW **in the UI layer** (the VM carries no permission logic).
 * Shows the paged list or the sale detail depending on VM state; a hardware back closes the detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SalesHistoryFeature(authenticated: AuthenticatedSession, onExit: () -> Unit) {
    val vm: SalesHistoryViewModel = viewModel()
    LaunchedEffect(authenticated.session.uid, authenticated.config) {
        vm.bind(authenticated.session, authenticated.config)
    }

    if (authenticated.session.permissions.sales == PermissionLevel.NONE) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(strings(Strings.sales_history_title)) },
                    navigationIcon = {
                        IconButton(onClick = onExit) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, strings(Strings.sales_close_cd))
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(strings(Strings.sales_history_no_access), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val state by vm.uiState.collectAsStateWithLifecycle()
    if (state.detail != null || state.detailLoading) {
        BackHandler(onBack = vm::closeDetail)
        SaleDetailScreen(state = state, vm = vm)
    } else {
        SalesListScreen(state = state, vm = vm, onExit = onExit)
    }
}

// ── List ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SalesListScreen(state: SalesHistoryUiState, vm: SalesHistoryViewModel, onExit: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it); vm.clearError() }
    }

    // Paging: load the next page as the user nears the end of what's loaded (append, never blank).
    LaunchedEffect(listState, state.hasMore, state.visibleSales.size) {
        if (!state.hasMore) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (lastVisible >= state.visibleSales.size - 3) vm.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings(Strings.sales_history_title)) },
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
                // Tap blank space dismisses the keyboard (#71 lesson). Transparent to fields.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { focusManager.clearFocus(); keyboardController?.hide() })
                },
        ) {
            SearchField(
                value = state.searchText,
                onValueChange = vm::onSearchChanged,
                onSubmit = { focusManager.clearFocus(); keyboardController?.hide(); vm.onSearchSubmit() },
                onClear = { vm.clearSearch() },
            )
            SearchHint(state.searchKind)

            when {
                state.isLoading -> CenterBox { CircularProgressIndicator() }
                // A denial at query time (permission revoked after entry, or Firestore rules on
                // mobile) must read as "no access", not the misleading "no sales yet" empty state.
                state.permissionDenied -> CenterBox {
                    Text(strings(Strings.sales_history_no_access), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.visibleSales.isEmpty() -> EmptyState(hasActiveSearch = state.hasActiveSearch)
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.visibleSales, key = { it.saleId }) { sale ->
                        SaleRow(
                            sale = sale,
                            customerName = vm.customerNameOf(sale),
                            currency = state.currency,
                            timezone = state.timezone,
                            onClick = { vm.openSale(sale.saleId) },
                        )
                        HorizontalDivider()
                    }
                    if (state.isLoadingMore) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.size(8.dp))
                                Text(strings(Strings.sales_history_loading_more), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit, onClear: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = onClear) { Icon(Icons.Filled.Close, strings(Strings.sales_close_cd)) }
            }
        },
        placeholder = { Text(strings(Strings.sales_history_search_placeholder)) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The "Searched by …" interpretation hint (ticket #84) — only after a submitted server search. */
@Composable
private fun SearchHint(kind: SalesSearchKind) {
    val res = when (kind) {
        SalesSearchKind.IMEI -> Strings.sales_history_searched_by_imei
        SalesSearchKind.INVOICE -> Strings.sales_history_searched_by_invoice
        SalesSearchKind.CUSTOMER -> Strings.sales_history_searched_by_customer
        SalesSearchKind.NONE -> null
    } ?: return
    Text(
        strings(res),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

@Composable
private fun SaleRow(
    sale: SaleSummary,
    customerName: String,
    currency: String,
    timezone: String,
    onClick: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Text(
                customerName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (sale.status.isVoided) {
                Text(
                    strings(Strings.sales_history_voided_badge),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(MoneyFormat.format(sale.grandTotal, currency), style = MaterialTheme.typography.titleSmall)
        }
        Text(
            itemSummary(sale),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDateTime(sale.createdAtMillis, timezone) + "  ·  " + (sale.invoiceNumber ?: strings(Strings.sales_history_no_invoice)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!Money.isZero(sale.balanceRemaining)) {
                AssistChip(
                    onClick = onClick,
                    label = { Text(MoneyFormat.format(sale.balanceRemaining, currency)) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    border = null,
                )
            }
        }
    }
}

@Composable
private fun itemSummary(sale: SaleSummary): String {
    val first = sale.firstItemLabel.ifBlank { "—" }
    return if (sale.itemCount > 1) "$first  ${strings(Strings.sales_history_items_more, (sale.itemCount - 1).toString())}" else first
}

@Composable
private fun EmptyState(hasActiveSearch: Boolean) {
    CenterBox {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                strings(if (hasActiveSearch) Strings.sales_history_no_match_title else Strings.sales_history_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                strings(if (hasActiveSearch) Strings.sales_history_no_match_body else Strings.sales_history_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Detail ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaleDetailScreen(state: SalesHistoryUiState, vm: SalesHistoryViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.error) {
        state.error?.let { snackbarHostState.showSnackbar(it); vm.clearError() }
    }
    var showVoidDialog by remember { mutableStateOf(false) }
    // Close the dialog the instant the sale flips VOIDED (a successful reversal). A failure leaves
    // it open with the reason shown; the CF/use case never partially voids.
    LaunchedEffect(state.detail?.isVoided) {
        if (showVoidDialog && state.detail?.isVoided == true) showVoidDialog = false
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(strings(Strings.sales_history_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = vm::closeDetail) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, strings(Strings.sales_history_detail_back))
                    }
                },
                actions = {
                    // Void — admin-only, hidden once voided. A spinner while the reversal runs.
                    if (state.canVoidOpenSale) {
                        if (state.isVoiding) {
                            CircularProgressIndicator(
                                Modifier.size(20.dp).padding(end = 4.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            IconButton(onClick = { vm.clearVoidError(); showVoidDialog = true }) {
                                Icon(Icons.Filled.Block, strings(Strings.sales_history_void), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar { Text(data.visuals.message) }
            }
        },
    ) { padding ->
        val detail = state.detail
        if (detail == null) {
            CenterBox(Modifier.padding(padding)) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (detail.isVoided) VoidedBanner(detail.voidState.reason)
            DetailHeader(detail = detail, sellerName = state.sellerName, customerName = vm.customerNameOf(detail), timezone = state.timezone)
            HorizontalDivider()
            DetailLines(detail = detail, currency = state.currency)
            HorizontalDivider()
            DetailTotals(detail = detail, currency = state.currency)
            detail.note?.takeIf { it.isNotBlank() }?.let {
                HorizontalDivider()
                Fact(strings(Strings.sales_history_detail_note), it)
            }
            HorizontalDivider()
            DetailInvoiceRow(state = state, vm = vm, snackbarHostState = snackbarHostState)
            Spacer(Modifier.height(8.dp))
        }
    }
    if (showVoidDialog) {
        VoidSaleDialog(
            isVoiding = state.isVoiding,
            error = state.voidError,
            onConfirm = { reason -> vm.voidSale(reason) },
            onCancel = { if (!state.isVoiding) { vm.clearVoidError(); showVoidDialog = false } },
        )
    }
}

/** A read-only banner on a voided sale's detail (ticket #85), naming the reason if stored. */
@Composable
private fun VoidedBanner(reason: String?) {
    val error = MaterialTheme.colorScheme.error
    Row(
        Modifier.fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(error.copy(alpha = 0.10f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Block, null, tint = error, modifier = Modifier.size(18.dp))
        Column {
            Text(strings(Strings.sales_history_voided_note), style = MaterialTheme.typography.bodyMedium, color = error)
            if (!reason.isNullOrBlank()) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * The Void confirmation on phones (ticket #85): states what a void reverses, **requires a typed
 * reason** (Confirm disabled until non-blank), isn't dismissible by an accidental outside tap, and
 * uses a destructive (error-coloured) Confirm that locks into a spinner while the reversal runs.
 */
@Composable
private fun VoidSaleDialog(
    isVoiding: Boolean,
    error: String?,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val canConfirm = reason.isNotBlank() && !isVoiding
    AlertDialog(
        onDismissRequest = { /* not dismissible by accident — only Cancel closes it */ },
        icon = { Icon(Icons.Filled.Block, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(strings(Strings.sales_history_void_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings(Strings.sales_history_void_dialog_body), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    singleLine = false,
                    enabled = !isVoiding,
                    label = { Text(strings(Strings.sales_history_void_reason_label)) },
                    placeholder = { Text(strings(Strings.sales_history_void_reason_ph)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (canConfirm) onConfirm(reason.trim()) },
                enabled = canConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                if (isVoiding) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(Modifier.size(8.dp))
                    Text(strings(Strings.sales_history_void_in_progress))
                } else {
                    Text(strings(Strings.sales_history_void_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isVoiding) {
                Text(strings(Strings.sales_history_void_cancel))
            }
        },
    )
}

@Composable
private fun DetailHeader(detail: SaleDetail, sellerName: String?, customerName: String, timezone: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Fact(strings(Strings.sales_history_detail_customer), customerName)
        if (detail.isWalkIn) {
            detail.buyerName?.takeIf { it.isNotBlank() }?.let { Fact(strings(Strings.sales_history_detail_buyer), it) }
            detail.buyerPhone?.takeIf { it.isNotBlank() }?.let { Fact(strings(Strings.sales_history_detail_buyer_phone), it) }
        }
        sellerName?.let { Fact(strings(Strings.sales_history_detail_sold_by), it) }
        Fact(strings(Strings.sales_history_detail_sold_at), formatDateTime(detail.createdAtMillis, timezone))
    }
}

@Composable
private fun DetailLines(detail: SaleDetail, currency: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings(Strings.sales_history_detail_items), style = MaterialTheme.typography.titleSmall)
        detail.lines.forEach { line ->
            val (label, imei) = when (line) {
                is SaleRecordLine.Inventory -> line.label to line.imei
                is SaleRecordLine.Custom -> line.name to null
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    imei?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    }
                    if (!Money.isZero(line.lineDiscount)) {
                        Text(
                            "${strings(Strings.sales_history_detail_line_discount)}: ${MoneyFormat.format(line.lineDiscount, currency)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(MoneyFormat.format(line.netPrice, currency), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun DetailTotals(detail: SaleDetail, currency: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TotalsRow(strings(Strings.sales_totals_subtotal), MoneyFormat.format(detail.subtotal, currency))
        if (!Money.isZero(detail.saleDiscount)) {
            TotalsRow(strings(Strings.sales_history_detail_sale_discount), "-" + MoneyFormat.format(detail.saleDiscount, currency))
        }
        detail.taxLines.forEach { tax -> TotalsRow("${tax.name} (${tax.rate})", MoneyFormat.format(tax.amount, currency)) }
        HorizontalDivider()
        TotalsRow(strings(Strings.sales_totals_grand_total), MoneyFormat.format(detail.grandTotal, currency), emphasize = true)
        TotalsRow(strings(Strings.sales_totals_paid), MoneyFormat.format(detail.amountPaid, currency))
        TotalsRow(strings(Strings.sales_totals_balance), MoneyFormat.format(detail.balanceRemaining, currency), emphasize = true)
    }
}

/**
 * The detail invoice row (ticket #84), reusing the #77 states but with the PDF actions the
 * counter lacks: **Open** (view in browser), **Share** (send the actual PDF file), **Download**
 * (save the PDF to Downloads), and **Retry** on a FAILED invoice.
 */
@Composable
private fun DetailInvoiceRow(state: SalesHistoryUiState, vm: SalesHistoryViewModel, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val invoice = state.detailInvoice.invoice
    val invoiceNumber = invoice.number ?: state.detail?.invoice?.number
    val savedMsg = strings(Strings.sales_history_pdf_saved)
    val errMsg = strings(Strings.sales_history_pdf_error)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(strings(Strings.sales_invoice_label), style = MaterialTheme.typography.titleSmall)
        when (invoice.status) {
            SaleInvoiceStatus.PENDING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(strings(Strings.sales_invoice_preparing), style = MaterialTheme.typography.bodyMedium)
            }

            SaleInvoiceStatus.ISSUED -> {
                Text(invoice.number ?: "—", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val url = invoice.url
                if (url != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }) { Text(strings(Strings.sales_invoice_open)) }
                        TextButton(onClick = {
                            scope.launch {
                                val uri = PdfActions.sharePdf(context, url, invoiceNumber)
                                if (uri != null) PdfActions.startShareChooser(context, uri)
                                else snackbarHostState.showSnackbar(errMsg)
                            }
                        }) { Text(strings(Strings.sales_history_share)) }
                        TextButton(onClick = {
                            val ok = PdfActions.downloadPdfToDownloads(context, url, invoiceNumber)
                            scope.launch { snackbarHostState.showSnackbar(if (ok) savedMsg else errMsg) }
                        }) { Text(strings(Strings.sales_history_download)) }
                    }
                }
            }

            SaleInvoiceStatus.FAILED -> {
                Text(strings(Strings.sales_invoice_failed), style = MaterialTheme.typography.bodyMedium)
                if (state.detailInvoice.isRetrying) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(strings(Strings.sales_invoice_retrying), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    if (state.detailInvoice.retryError) {
                        Text(strings(Strings.sales_invoice_retry_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = vm::retryInvoice, enabled = state.detailInvoice.canRetry) {
                        Text(strings(Strings.sales_invoice_retry))
                    }
                }
            }
        }
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TotalsRow(label: String, value: String, emphasize: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = if (emphasize) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
        Text(value, style = if (emphasize) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CenterBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

private const val DATE_TIME_FMT = "MMM d, yyyy · h:mm a"

/** Formats an epoch-millis instant in the shop's timezone; falls back to UTC on a bad zone id. */
private fun formatDateTime(millis: Long, timezone: String): String {
    if (millis <= 0L) return "—"
    val fmt = SimpleDateFormat(DATE_TIME_FMT, Locale.getDefault())
    fmt.timeZone = runCatching { TimeZone.getTimeZone(timezone) }.getOrDefault(TimeZone.getTimeZone("UTC"))
    return fmt.format(Date(millis))
}
