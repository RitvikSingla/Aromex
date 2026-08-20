package com.humblesolutions.aromex.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.ResolvedCompany
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme

@Composable
fun ChooseCompanyScreen(
    candidates: List<ResolvedCompany>,
    onChoose: (ResolvedCompany) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions

    Box(modifier = Modifier.fillMaxSize().background(colors.background)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(dims.space32),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Text(
                text = strings(Strings.choose_company_title),
                style = AromexTheme.typography.screenTitle,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(dims.space8))
            Text(
                text = strings(Strings.choose_company_subtitle),
                style = AromexTheme.typography.body,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(dims.space24))

            LazyColumn(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(dims.space12),
            ) {
                items(candidates, key = { it.companyId }) { company ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(dims.radiusCard))
                            .background(colors.surface)
                            .clickable { onChoose(company) }
                            .pointerHoverIcon(PointerIcon.Hand)
                            .padding(dims.space20),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = company.firebaseConfig.projectId,
                            style = AromexTheme.typography.sectionTitle,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.textTertiary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(dims.space24))

            TextButton(
                onClick = onCancel,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(
                    text = "Back",
                    style = AromexTheme.typography.button,
                    color = colors.brand,
                )
            }
        }
    }
}
