package com.humblesolutions.aromex.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.ResolvedCompany
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * Choose which workspace to sign into when the email resolved to more than
 * one. Ticket-#21 restyle: applies tokens from [AromexTheme] to the card
 * list; keeps the flow (per PRD §7.1 shows `projectId` only).
 */
@Composable
fun ChooseCompanyScreen(
    candidates: List<ResolvedCompany>,
    onChoose: (ResolvedCompany) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(
                horizontal = dimensions.screenPadding,
                vertical = dimensions.space24,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(dimensions.space12),
    ) {
        Text(
            text = strings(Strings.choose_company_title),
            style = AromexTheme.typography.screenTitle,
            color = colors.textPrimary,
        )
        Text(
            text = strings(Strings.choose_company_subtitle),
            style = AromexTheme.typography.body,
            color = colors.textSecondary,
        )

        Spacer(Modifier.height(dimensions.space8))

        LazyColumn(
            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(dimensions.space8),
        ) {
            items(candidates, key = { it.companyId }) { company ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(dimensions.radiusCard))
                        .background(colors.surface)
                        .clickable { onChoose(company) }
                        .padding(dimensions.space20),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = company.firebaseConfig.projectId,
                        style = AromexTheme.typography.bodyStrong,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = colors.textTertiary,
                    )
                }
            }
        }

        Spacer(Modifier.height(dimensions.space8))

        TextButton(onClick = onCancel) {
            Text(
                text = "Back",
                style = AromexTheme.typography.button,
                color = colors.brand,
            )
        }
    }
}
