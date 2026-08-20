package com.humblesolutions.aromex.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AccountType
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.LedgerAccount
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.ui.i18n.strings

/**
 * Port of Android's HomeScreen. Adds a small "live" AssistChip that shows
 * the Firestore listener fire count — the visible proof that
 * google-cloud-firestore's onSnapshot works on Desktop through the
 * gateway-brokered datastore token (ticket #19 acceptance criterion).
 */
@Composable
fun HomeScreen(
    session: UserSession,
    state: HomeUiState,
    onSignOut: () -> Unit,
    onRetryBalances: () -> Unit,
    onOpenEntities: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = strings(Strings.home_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = strings(Strings.home_signed_in_as, session.email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SelectionContainer {
            Text(
                text = strings(Strings.home_role, session.role.label()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Real-time listener proof — count of fires. This chip flips label
        // every time google-cloud-firestore delivers a snapshot for
        // companySettings/profile.
        AssistChip(
            onClick = {},
            enabled = false,
            label = {
                Text(
                    text = if (state.listenerFireCount == 0) "Firestore listener: attaching…"
                    else "Firestore listener: live (${state.listenerFireCount} update${if (state.listenerFireCount == 1) "" else "s"})",
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = strings(Strings.home_balances_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
        )
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).weight(1f),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                state.isLoadingBalances && state.accounts.isEmpty() -> BalancesLoading()
                state.balancesError != null -> BalancesError(
                    error = state.balancesError,
                    onRetry = onRetryBalances,
                )
                state.accounts.isEmpty() -> Text(
                    text = strings(Strings.home_balances_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> BalancesList(
                    accounts = state.accounts,
                    currency = session.currency,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedButton(
            onClick = onOpenEntities,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            Text("Parties (Profiles)")
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onSignOut,
            enabled = !state.isSigningOut,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        ) {
            if (state.isSigningOut) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(strings(Strings.home_sign_out))
            }
        }
    }
}

@Composable
private fun BalancesLoading() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = strings(Strings.home_balances_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BalancesError(error: HlError, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = strings(Strings.home_balances_error),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = strings(error.toStringKey()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRetry) {
                Text(strings(Strings.home_balances_retry))
            }
        }
    }
}

@Composable
private fun BalancesList(accounts: List<LedgerAccount>, currency: String) {
    val cash = accounts.filter { it.type == AccountType.CASH }
    val bank = accounts.filter { it.type == AccountType.BANK }
    val creditCard = accounts.filter { it.type == AccountType.CREDIT_CARD }
    val other = accounts.filter {
        it.type != AccountType.CASH && it.type != AccountType.BANK &&
            it.type != AccountType.CREDIT_CARD
    }

    val cashTitle = strings(Strings.home_balances_section_cash)
    val bankTitle = strings(Strings.home_balances_section_bank)
    val ccTitle = strings(Strings.home_balances_section_credit_card)
    val otherTitle = strings(Strings.home_balances_section_other)

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        if (cash.isNotEmpty()) section(cashTitle, cash, currency)
        if (bank.isNotEmpty()) section(bankTitle, bank, currency)
        if (creditCard.isNotEmpty()) section(ccTitle, creditCard, currency)
        if (other.isNotEmpty()) section(otherTitle, other, currency)
    }
}

private fun LazyListScope.section(title: String, accounts: List<LedgerAccount>, currency: String) {
    item(key = "section-$title") {
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    items(accounts, key = { it.id }) { account ->
        AccountRow(account = account, currency = currency)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AccountRow(account: LedgerAccount, currency: String) {
    SelectionContainer {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = strings(account.type.labelKey()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (currency.isBlank()) account.balance else "${account.balance} $currency",
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun UserRole.label(): String = when (this) {
    UserRole.ADMIN -> "Admin"
    UserRole.MEMBER -> "Member"
}

private fun HlError.toStringKey(): String = when (this) {
    HlError.NetworkUnavailable -> Strings.hl_error_network
    HlError.GatewayUnreachable -> Strings.hl_error_gateway
    HlError.TokenRejected -> Strings.hl_error_token_rejected
    HlError.HlUnreachable -> Strings.hl_error_hl_unreachable
    HlError.Unauthorized -> Strings.hl_error_unauthorized
    is HlError.Unexpected -> Strings.hl_error_unexpected
}

private fun AccountType.labelKey(): String = when (this) {
    AccountType.CASH -> Strings.account_type_cash
    AccountType.BANK -> Strings.account_type_bank
    AccountType.CREDIT_CARD -> Strings.account_type_credit_card
    AccountType.RECEIVABLE -> Strings.account_type_receivable
    AccountType.PAYABLE -> Strings.account_type_payable
    AccountType.REVENUE -> Strings.account_type_revenue
    AccountType.EXPENSE -> Strings.account_type_expense
    AccountType.TAX -> Strings.account_type_tax
    AccountType.OTHER -> Strings.account_type_other
}
