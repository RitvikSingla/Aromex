package com.humblesolutions.aromex.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.ui.theme.AromexTheme

@Composable
fun FilterableDropdownField(
    label: String,
    items: List<AttributeValue>,
    selectedItem: AttributeRef?,
    onItemSelected: (AttributeValue) -> Unit,
    onClear: (() -> Unit)? = null,
    onAddNew: ((String) -> Unit)? = null,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    placeholder: String = "",
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    var searchText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var addNewLoading by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    // rememberUpdatedState ensures the LaunchedEffect coroutine always reads the
    // latest enabled/isLoading values even though the coroutine is started only once.
    val enabledState = rememberUpdatedState(enabled)
    val isLoadingState = rememberUpdatedState(isLoading)

    // Open dropdown when the field gains focus (tap or tab on Android)
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            if (interaction is FocusInteraction.Focus && enabledState.value && !isLoadingState.value) {
                expanded = true
            }
        }
    }

    // Keep display text in sync with external selection; reset add-in-progress spinner
    LaunchedEffect(selectedItem) {
        addNewLoading = false
        searchText = selectedItem?.name ?: ""
    }

    val filteredItems = remember(items, searchText) {
        if (searchText.isBlank()) items
        else items.filter { it.name.contains(searchText, ignoreCase = true) }
    }

    val hasExactMatch = remember(items, searchText) {
        items.any { it.name.equals(searchText.trim(), ignoreCase = true) }
    }

    val showAddNew = onAddNew != null && searchText.isNotBlank() && !hasExactMatch

    Column(modifier = modifier) {
        Text(
            text = label,
            style = typography.fieldLabel,
            color = colors.textTertiary,
            modifier = Modifier.padding(bottom = dims.space8),
        )

        Column {
            OutlinedTextField(
                value = searchText,
                onValueChange = { typed ->
                    val normalized = if (typed.isNotEmpty()) {
                        typed.first().uppercaseChar() + typed.drop(1)
                    } else typed
                    searchText = normalized
                    if (enabled && !isLoading) expanded = true
                },
                enabled = enabled && !isLoading && !addNewLoading,
                singleLine = true,
                interactionSource = interactionSource,
                placeholder = {
                    Text(
                        placeholder,
                        style = typography.body,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    when {
                        isLoading || addNewLoading -> CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.brand,
                        )
                        searchText.isNotBlank() -> IconButton(onClick = {
                            searchText = ""
                            onClear?.invoke()
                            expanded = true
                        }) {
                            Icon(Icons.Default.Close, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                        }
                        else -> IconButton(onClick = {
                            if (enabled && !isLoading) expanded = !expanded
                        }) {
                            Icon(
                                Icons.Default.ExpandMore,
                                null,
                                tint = colors.textTertiary,
                                modifier = Modifier.size(20.dp).rotate(if (expanded) 180f else 0f),
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    keyboardType = KeyboardType.Text,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.brand,
                    unfocusedBorderColor = colors.border,
                    focusedLabelColor = colors.brand,
                    unfocusedLabelColor = colors.textTertiary,
                    cursorColor = colors.brand,
                    focusedContainerColor = colors.surface,
                    unfocusedContainerColor = colors.surface,
                    disabledContainerColor = colors.surfaceAlt,
                    disabledBorderColor = colors.border,
                    disabledTextColor = colors.textTertiary,
                ),
                shape = RoundedCornerShape(dims.radiusField),
                textStyle = typography.body.copy(color = colors.textPrimary),
                modifier = Modifier.fillMaxWidth(),
            )

            AnimatedVisibility(
                visible = expanded && enabled && !isLoading && !addNewLoading,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Surface(
                    shape = RoundedCornerShape(dims.radiusCard),
                    shadowElevation = 4.dp,
                    color = colors.surface,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Column {
                        when {
                            filteredItems.isEmpty() && !showAddNew -> Text(
                                text = if (searchText.isNotBlank()) "No results found" else "No items available",
                                style = typography.hint,
                                color = colors.textTertiary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                            else -> LazyColumn(modifier = Modifier.heightIn(max = 192.dp)) {
                                items(filteredItems) { item ->
                                    DropdownItem(
                                        name = item.name,
                                        isSelected = selectedItem?.attributeId == item.attributeId,
                                        onClick = {
                                            onItemSelected(item)
                                            searchText = item.name
                                            expanded = false
                                        },
                                    )
                                }
                                if (showAddNew) {
                                    item {
                                        if (filteredItems.isNotEmpty()) HorizontalDivider(color = colors.border)
                                        AddNewRow(
                                            query = searchText.trim(),
                                            onClick = {
                                                addNewLoading = true
                                                expanded = false
                                                onAddNew?.invoke(searchText.trim())
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropdownItem(name: String, isSelected: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = typography.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isSelected) {
            Icon(Icons.Default.Check, null, tint = colors.brand, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AddNewRow(query: String, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(colors.brandTint.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Add, null, tint = colors.brand, modifier = Modifier.size(16.dp))
        Text(
            text = "Add \"$query\"",
            style = typography.body.copy(fontWeight = FontWeight.Medium),
            color = colors.brand,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
