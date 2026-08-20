package com.humblesolutions.aromex.ui.entities

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.money.DateRangeChip
import com.humblesolutions.aromex.ui.sales.history.PdfLoadState
import com.humblesolutions.aromex.ui.sales.history.ZoomablePdfViewer
import com.humblesolutions.aromex.ui.sales.history.downloadAndRevealPdf
import com.humblesolutions.aromex.ui.sales.history.downloadAndSavePdf
import com.humblesolutions.aromex.ui.sales.history.rememberInvoicePdf
import com.humblesolutions.aromex.ui.theme.AromexTheme
import kotlinx.coroutines.launch

/**
 * The "Print statement" dialog (ticket #109): a date range (reusing the ledger's [DateRangeChip]),
 * an off-by-default "Include notes" switch, and Generate. Complete states — progress while it
 * renders, inline error — with minimal chrome.
 */
@Composable
internal fun PrintStatementDialog(
    from: Long?,
    to: Long?,
    includeNotes: Boolean,
    generating: Boolean,
    error: String?,
    onRange: (Long?, Long?) -> Unit,
    onNotesToggle: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    AlertDialog(
        onDismissRequest = { if (!generating) onDismiss() },
        title = { Text(strings(Strings.statement_print_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings(Strings.statement_range), style = typography.hint, color = colors.textTertiary)
                DateRangeChip(from = from, to = to, onPick = onRange)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.width(320.dp)) {
                        Text(strings(Strings.statement_include_notes), color = colors.textPrimary)
                        Text(
                            strings(Strings.statement_include_notes_hint),
                            style = typography.hint,
                            color = colors.textTertiary,
                        )
                    }
                    Switch(checked = includeNotes, onCheckedChange = onNotesToggle, enabled = !generating)
                }

                error?.let { Text(it, style = typography.hint, color = colors.error) }
                if (generating) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(strings(Strings.statement_generating), color = colors.textSecondary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onGenerate, enabled = !generating) { Text(strings(Strings.statement_generate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !generating) { Text(strings(Strings.money_cancel)) }
        },
    )
}

/**
 * Opens the rendered statement PDF in a resizable window, reusing the Sales-History PDF viewer
 * ([rememberInvoicePdf] + [ZoomablePdfViewer]) and its Share / Download actions.
 */
@Composable
internal fun StatementPdfWindow(url: String, partyName: String, onClose: () -> Unit) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    DialogWindow(
        onCloseRequest = onClose,
        state = rememberDialogState(width = 820.dp, height = 1000.dp),
        title = "${strings(Strings.statement_print_title)} — $partyName",
    ) {
        val pdf = rememberInvoicePdf(url)
        val scope = rememberCoroutineScope()
        Column(Modifier.fillMaxSize().background(colors.background)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(partyName, style = typography.bodyStrong, color = colors.textPrimary)
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { scope.launch { runCatching { downloadAndRevealPdf(url, partyName) } } }) {
                    Text(strings(Strings.statement_share))
                }
                TextButton(onClick = { scope.launch { runCatching { downloadAndSavePdf(url, partyName) } } }) {
                    Text(strings(Strings.statement_download))
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, strings(Strings.money_cancel), tint = colors.textSecondary) }
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val state = pdf) {
                    is PdfLoadState.Loading -> CircularProgressIndicator()
                    is PdfLoadState.Ready -> ZoomablePdfViewer(state.pages, Modifier.fillMaxSize())
                    is PdfLoadState.Failed -> Text(
                        strings(Strings.statement_error_generic),
                        style = typography.body,
                        color = colors.textTertiary,
                    )
                }
            }
        }
    }
}
