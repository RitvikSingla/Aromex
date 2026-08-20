package com.humblesolutions.aromex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.ui.theme.AromexTheme

/**
 * How long, after a dropdown closes, a press/focus replay is refused a reopen. Long enough to
 * outlast the paired click-outside + pointer-event replay, short enough that a deliberate reopen
 * (human reaction time is well over this) still works immediately.
 */
private const val REOPEN_SUPPRESS_MS = 300L

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
    compact: Boolean = false,
    outlined: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val density = LocalDensity.current

    var searchText by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var isAdding by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    // After any close, briefly refuse to reopen. The popup floats BELOW the field, so a click that
    // dismisses it can also land on the trigger (and pointer/focus events get replayed on the next
    // mouse move); this window swallows that paired reopen so the dropdown stays closed until a
    // genuinely new click. `closeDropdown()` sets it; the press-to-open path honours it.
    var reopenSuppressedUntilMs by remember { mutableStateOf(0L) }
    fun closeDropdown() {
        expanded = false
        reopenSuppressedUntilMs = System.currentTimeMillis() + REOPEN_SUPPRESS_MS
    }
    // Chevron toggle. Must respect the suppression window on the OPEN side: when the popup dismiss
    // fires before this click (desktop ordering), it already set expanded=false + suppression, so a
    // naive `if (expanded) close else open` would wrongly reopen. Guarding the open makes the
    // chevron retract the list exactly like clicking the field or outside.
    fun toggleDropdown() {
        if (expanded) closeDropdown()
        else if (System.currentTimeMillis() >= reopenSuppressedUntilMs) expanded = true
    }
    val interactionSource = remember { MutableInteractionSource() }

    // Track field dimensions so the popup matches the field width and appears below it
    var fieldWidthDp by remember { mutableStateOf(0.dp) }
    var fieldHeightPx by remember { mutableStateOf(0) }

    // rememberUpdatedState ensures the LaunchedEffect coroutine always reads the
    // latest enabled/isLoading values even though the coroutine is started only once.
    val enabledState = rememberUpdatedState(enabled)
    val isLoadingState = rememberUpdatedState(isLoading)

    // Open ONLY on an intentional press/tap of the field — never on focus or hover. Opening on
    // focus was the flicker's root cause: a click-outside dismiss leaves/returns focus to the field,
    // and the replayed focus event reopened it on the next pointer move. Tabbing away still closes.
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Release -> {
                    if (enabledState.value && !isLoadingState.value &&
                        System.currentTimeMillis() >= reopenSuppressedUntilMs
                    ) {
                        expanded = true
                    }
                }
                is FocusInteraction.Unfocus -> closeDropdown()
            }
        }
    }

    LaunchedEffect(selectedItem) {
        isAdding = false
        searchText = selectedItem?.name ?: ""
    }

    // Show all items when search text matches the selected item's name (i.e. user just
    // opened the dropdown without typing yet). Filtering kicks in only when the user
    // types something different from the current selection.
    val filteredItems = remember(items, searchText, selectedItem) {
        val selectedName = selectedItem?.name ?: ""
        if (searchText.isBlank() || searchText.equals(selectedName, ignoreCase = true)) items
        else items.filter { it.name.contains(searchText, ignoreCase = true) }
    }

    val hasExactMatch = remember(items, searchText) {
        items.any { it.name.equals(searchText.trim(), ignoreCase = true) }
    }

    val showAddNew = onAddNew != null && searchText.isNotBlank() && !hasExactMatch

    Column(modifier = modifier) {
        // Compact table-cell mode omits the standalone field label entirely.
        if (!compact) {
            Text(
                text = label,
                style = typography.fieldLabel,
                color = colors.textTertiary,
                modifier = Modifier.padding(bottom = dims.space8),
            )
        }

        // Box so the Popup can anchor below the field without affecting layout below.
        // onSizeChanged is here (the outer box) so fieldHeightPx = full visual cell height,
        // giving the popup the correct downward offset regardless of internal padding structure.
        Box(
            modifier = Modifier.fillMaxWidth().onSizeChanged { size ->
                fieldWidthDp = with(density) { size.width.toDp() }
                fieldHeightPx = size.height
            },
        ) {
            if (compact) {
                // Compact table-cell: BasicTextField with zero internal padding so text aligns
                // flush with the column header. OutlinedTextField always adds 16dp start padding
                // which shifts every cell's text rightward vs the header label.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (isFocused) colors.brand else if (outlined) colors.border else Color.Transparent, RoundedCornerShape(dims.radiusField))
                        .padding(start = 6.dp, top = 9.dp, bottom = 9.dp, end = 18.dp),
                ) {
                    if (searchText.isEmpty() && !isLoading && !isAdding) {
                        Text(
                            placeholder,
                            style = typography.hint,
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (isLoading || isAdding) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.brand)
                    } else {
                        BasicTextField(
                            value = searchText,
                            onValueChange = { typed ->
                                val normalized = if (typed.isNotEmpty()) typed.first().uppercaseChar() + typed.drop(1) else typed
                                searchText = normalized
                                isAdding = false
                                if (enabled && !isLoading) expanded = true
                            },
                            enabled = enabled,
                            singleLine = true,
                            textStyle = typography.hint.copy(
                                color = if (enabled) colors.textPrimary else colors.textPrimary.copy(alpha = 0.5f),
                            ),
                            cursorBrush = SolidColor(colors.brand),
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused },
                        )
                    }
                }
                // Chevron overlaid at the right edge of the outer Box
                if (!isLoading && !isAdding) {
                    Icon(
                        Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 2.dp)
                            .size(14.dp)
                            .rotate(if (expanded) 180f else 0f)
                            .pointerHoverIcon(PointerIcon.Hand)
                            // Toggle open/closed — retracts the list on click, same as the field/outside.
                            .clickable(enabled = enabled) {
                                if (!isLoading) toggleDropdown()
                            },
                    )
                }
            } else {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { typed ->
                        val normalized = if (typed.isNotEmpty()) {
                            typed.first().uppercaseChar() + typed.drop(1)
                        } else typed
                        searchText = normalized
                        isAdding = false
                        if (enabled && !isLoading) expanded = true
                    },
                    enabled = enabled && !isLoading && !isAdding,
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
                            isLoading || isAdding -> CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = colors.brand,
                            )
                            searchText.isNotBlank() -> IconButton(
                                onClick = { searchText = ""; onClear?.invoke(); expanded = true },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(Icons.Default.Close, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                            }
                            else -> IconButton(
                                // Toggle open/closed — retracts the list on click, same as field/outside.
                                onClick = { if (enabled && !isLoading) toggleDropdown() },
                                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.ExpandMore,
                                    null,
                                    tint = colors.textTertiary,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(if (expanded) 180f else 0f),
                                )
                            }
                        }
                    },
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
                        disabledTextColor = colors.textPrimary,
                    ),
                    shape = RoundedCornerShape(dims.radiusField),
                    textStyle = typography.body.copy(color = colors.textPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Popup — floats above the rest of the UI (both compact and non-compact)
            if (expanded && enabled && !isLoading && !isAdding && fieldWidthDp > 0.dp) {
                DropdownPopup(
                    fieldWidthDp = fieldWidthDp,
                    fieldHeightPx = fieldHeightPx,
                    filteredItems = filteredItems,
                    showAddNew = showAddNew,
                    searchText = searchText,
                    selectedItem = selectedItem,
                    compact = compact,
                    onItemSelected = { item ->
                        onItemSelected(item)
                        searchText = item.name
                        closeDropdown()
                    },
                    onAddNew = {
                        isAdding = true
                        closeDropdown()
                        onAddNew?.invoke(searchText.trim())
                    },
                    // Click-outside closes AND suppresses the paired reopen (the press that dismissed
                    // may also land on the trigger; the suppression window swallows it).
                    onDismiss = { closeDropdown() },
                )
            }
        }
    }
}

@Composable
private fun DropdownPopup(
    fieldWidthDp: Dp,
    fieldHeightPx: Int,
    filteredItems: List<AttributeValue>,
    showAddNew: Boolean,
    searchText: String,
    selectedItem: AttributeRef?,
    compact: Boolean,
    onItemSelected: (AttributeValue) -> Unit,
    onAddNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val density = LocalDensity.current
    val gapPx = with(density) { 2.dp.roundToPx() }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, fieldHeightPx + gapPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnClickOutside = true,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(dims.radiusField),
            shadowElevation = 8.dp,
            color = colors.surface,
            modifier = Modifier.width(fieldWidthDp),
        ) {
            Column {
                when {
                    filteredItems.isEmpty() && !showAddNew -> Text(
                        text = if (searchText.isNotBlank()) "No results found" else "No items available",
                        style = if (compact) typography.hint else typography.body,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(
                            start = if (compact) 6.dp else 16.dp,
                            end = if (compact) 6.dp else 16.dp,
                            top = if (compact) 6.dp else 12.dp,
                            bottom = if (compact) 6.dp else 12.dp,
                        ),
                    )
                    else -> LazyColumn(modifier = Modifier.heightIn(max = 168.dp)) {
                        items(filteredItems) { item ->
                            DropdownItem(
                                name = item.name,
                                isSelected = selectedItem?.attributeId == item.attributeId,
                                compact = compact,
                                onClick = { onItemSelected(item) },
                            )
                        }
                        if (showAddNew) {
                            item {
                                if (filteredItems.isNotEmpty()) HorizontalDivider(color = colors.border)
                                AddNewRow(query = searchText.trim(), compact = compact, onClick = onAddNew)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropdownItem(name: String, isSelected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(hoverSrc)
            .background(if (hovered) colors.brand.copy(alpha = 0.06f) else Color.Transparent)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(
                start = if (compact) 6.dp else 16.dp,
                end = if (compact) 6.dp else 16.dp,
                top = if (compact) 9.dp else 10.dp,
                bottom = if (compact) 9.dp else 10.dp,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = if (compact) typography.hint else typography.body,
            color = if (isSelected) colors.brand else colors.textPrimary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isSelected) {
            Icon(
                Icons.Default.Check,
                null,
                tint = colors.brand,
                modifier = Modifier.size(if (compact) 14.dp else 18.dp),
            )
        }
    }
}

@Composable
private fun AddNewRow(query: String, compact: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .background(colors.brandTint.copy(alpha = 0.4f))
            .padding(
                start = if (compact) 6.dp else 16.dp,
                end = if (compact) 6.dp else 16.dp,
                top = if (compact) 9.dp else 10.dp,
                bottom = if (compact) 9.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Default.Add, null, tint = colors.brand, modifier = Modifier.size(if (compact) 13.dp else 16.dp))
        Text(
            text = "Add \"$query\"",
            style = (if (compact) typography.hint else typography.body).copy(fontWeight = FontWeight.Medium),
            color = colors.brand,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
