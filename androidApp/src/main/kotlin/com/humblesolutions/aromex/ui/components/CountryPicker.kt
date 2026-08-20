package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.Countries
import com.humblesolutions.aromex.util.Country

/** The flag emoji for an ISO-3166 alpha-2 code (regional-indicator symbols). */
fun countryFlag(iso: String): String {
    if (iso.length != 2) return ""
    return buildString {
        iso.uppercase().forEach { c ->
            append(String(Character.toChars(0x1F1E6 + (c - 'A'))))
        }
    }
}

/** Inline trigger placed in the phone field: flag + dial code + a dropdown chevron. */
@Composable
fun CountryDialCodeTrigger(country: Country, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    Row(
        Modifier
            .clickable(onClick = onClick)
            .padding(end = AromexTheme.dimensions.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(countryFlag(country.iso), style = AromexTheme.typography.body)
        Spacer(Modifier.width(AromexTheme.dimensions.space4))
        Text(country.dialCode, style = AromexTheme.typography.bodyStrong, color = colors.brand)
        Icon(Icons.Filled.ArrowDropDown, null, tint = colors.brand, modifier = Modifier.size(18.dp))
        Box(Modifier.width(1.dp).height(20.dp).background(colors.border))
        Spacer(Modifier.width(AromexTheme.dimensions.space8))
    }
}

/** A searchable, full-height dialog to pick a dial-code country. */
@Composable
fun CountryPickerDialog(
    selected: Country,
    onSelect: (Country) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dimensions = AromexTheme.dimensions
    var query by remember { mutableStateOf("") }

    val results = remember(query) {
        val q = query.trim()
        if (q.isEmpty()) Countries.all
        else Countries.all.filter {
            it.name.contains(q, ignoreCase = true) || it.dialCode.contains(q) || it.iso.contains(q, ignoreCase = true)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(dimensions.radiusCard),
            color = colors.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.8f),
        ) {
            Column(Modifier.padding(dimensions.space20)) {
                Text(
                    strings(Strings.country_picker_title),
                    style = AromexTheme.typography.sectionTitle,
                    color = colors.textPrimary,
                )
                Spacer(Modifier.height(dimensions.space16))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surfaceAlt, RoundedCornerShape(dimensions.radiusField))
                        .padding(horizontal = dimensions.space12, vertical = dimensions.space12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(dimensions.space8))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        textStyle = AromexTheme.typography.body.copy(color = colors.textPrimary),
                        singleLine = true,
                        cursorBrush = SolidColor(colors.brand),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    strings(Strings.country_picker_search),
                                    style = AromexTheme.typography.body,
                                    color = colors.textTertiary,
                                )
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.height(dimensions.space12))
                if (results.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(dimensions.space24), contentAlignment = Alignment.Center) {
                        Text(strings(Strings.country_picker_empty), style = AromexTheme.typography.body, color = colors.textTertiary)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(results, key = { it.iso }) { country ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(country) }
                                    .padding(vertical = dimensions.space12),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(countryFlag(country.iso), style = AromexTheme.typography.body)
                                Spacer(Modifier.width(dimensions.space12))
                                Text(
                                    country.name,
                                    style = AromexTheme.typography.body,
                                    color = colors.textPrimary,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(country.dialCode, style = AromexTheme.typography.body, color = colors.textSecondary)
                                if (country.iso == selected.iso) {
                                    Spacer(Modifier.width(dimensions.space8))
                                    Icon(Icons.Filled.Check, null, tint = colors.brand, modifier = Modifier.size(18.dp))
                                }
                            }
                            HorizontalDivider(color = colors.border)
                        }
                    }
                }
            }
        }
    }
}
