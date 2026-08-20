package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.Countries
import com.humblesolutions.aromex.util.Country

@Composable
fun CountryPickerDialog(
    selected: Country,
    onSelect: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    val dims = AromexTheme.dimensions
    var query by remember { mutableStateOf("") }

    val filtered = remember(query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) Countries.all
        else Countries.all.filter {
            it.name.lowercase().contains(q) ||
                it.dialCode.contains(q) ||
                it.iso.lowercase().contains(q)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 400.dp)
                .heightIn(max = 500.dp),
            shape = RoundedCornerShape(dims.radiusCard),
            color = colors.surface,
            shadowElevation = 8.dp,
        ) {
            Column {
                // Title
                Text(
                    text = strings(Strings.country_picker_title),
                    style = typography.sectionTitle,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(start = dims.space20, top = dims.space20, end = dims.space20, bottom = dims.space12),
                )

                // Search field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = dims.space16)
                        .clip(RoundedCornerShape(dims.radiusField))
                        .background(colors.surfaceAlt)
                        .padding(horizontal = dims.space12, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(dims.space8))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = typography.body.copy(color = colors.textPrimary),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        strings(Strings.country_picker_search),
                                        style = typography.body,
                                        color = colors.textTertiary,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }

                Spacer(Modifier.height(dims.space12))
                HorizontalDivider(color = colors.border)

                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(dims.space32),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            strings(Strings.country_picker_empty),
                            style = typography.body,
                            color = colors.textTertiary,
                        )
                    }
                } else {
                    LazyColumn {
                        items(filtered, key = { it.iso }) { country ->
                            val isSelected = country.iso == selected.iso
                            val interactionSource = remember { MutableInteractionSource() }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isSelected) colors.brandTint else colors.surface)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(
                                        interactionSource = interactionSource,
                                        indication = null,
                                    ) { onSelect(country) }
                                    .padding(horizontal = dims.space20, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = country.iso,
                                    style = typography.fieldLabel.copy(fontWeight = FontWeight.Bold),
                                    color = if (isSelected) colors.brand else colors.textPrimary,
                                    modifier = Modifier.width(32.dp),
                                )
                                Spacer(Modifier.width(dims.space12))
                                Text(
                                    text = country.dialCode,
                                    style = typography.body.copy(color = if (isSelected) colors.brand else colors.textSecondary),
                                    modifier = Modifier.width(48.dp),
                                )
                                Text(
                                    text = country.name,
                                    style = typography.body,
                                    color = if (isSelected) colors.brand else colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            HorizontalDivider(color = colors.border.copy(alpha = 0.5f))
                        }
                    }
                }

                // Dismiss button
                HorizontalDivider(color = colors.border)
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space16, vertical = dims.space8),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            strings(Strings.entity_detail_cancel),
                            style = typography.button,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}
