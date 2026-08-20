package com.humblesolutions.aromex.ui.entities

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.i18n.strings

/**
 * The "Print statement" dialog (ticket #109): a date range, an off-by-default "Include notes"
 * switch, and Generate. On success the PDF opens in the device viewer with the system's own
 * Share/Download. Bare-minimum chrome — the real work is the shared use case + the callable.
 */
@Composable
fun PrintStatementDialog(
    entity: Entity,
    session: UserSession,
    config: FirebaseClientConfig,
    onDismiss: () -> Unit,
) {
    val vm: PrintStatementViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(session.uid, config) {
        vm.bind(session, config)
        vm.openFor()
    }

    AlertDialog(
        onDismissRequest = { if (!state.generating) onDismiss() },
        title = { Text(strings(Strings.statement_print_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(entity.name, style = MaterialTheme.typography.bodyMedium)

                OutlinedTextField(
                    value = state.fromIso,
                    onValueChange = vm::onFromChange,
                    label = { Text(strings(Strings.money_statement_from) + " (YYYY-MM-DD)") },
                    singleLine = true,
                    enabled = !state.generating,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.toIso,
                    onValueChange = vm::onToChange,
                    label = { Text(strings(Strings.money_statement_to) + " (YYYY-MM-DD)") },
                    singleLine = true,
                    enabled = !state.generating,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(strings(Strings.statement_include_notes))
                        Text(
                            strings(Strings.statement_include_notes_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = state.includeNotes,
                        onCheckedChange = vm::onToggleNotes,
                        enabled = !state.generating,
                    )
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (state.generating) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(strings(Strings.statement_generating))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vm.generate(entity) },
                enabled = !state.generating,
            ) { Text(strings(Strings.statement_generate)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.generating) {
                Text(strings(Strings.money_cancel))
            }
        },
    )

    // On success, hand off to the system PDF viewer (which carries Share/Download), then close.
    LaunchedEffect(state.pdfUrl) {
        val url = state.pdfUrl ?: return@LaunchedEffect
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        vm.consumeResult()
        onDismiss()
    }
}
