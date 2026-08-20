package com.humblesolutions.aromex.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.MoneyAccountRef
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.MoneyFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Shared metrics for every control on the Money screen.
 *
 * One height and one radius for all of them, so a row of mixed fields lines up on both edges
 * instead of each control bringing its own idea of how tall it should be — the thing that made the
 * first pass look ragged.
 */
internal val FIELD_HEIGHT = 40.dp
internal val LABEL_GAP = 6.dp

/** After the account dropdown closes, how long a press/focus replay is refused a reopen. */
private const val ACCOUNT_REOPEN_SUPPRESS_MS = 300L

/** The label above every field, so they all sit on the same baseline. */
@Composable
internal fun FieldLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    val colors = AromexTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().height(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = AromexTheme.typography.fieldLabel,
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Spacer(Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * The bordered box every field lives in — one place that owns height, radius, border and focus
 * colour. [focused] and [invalid] are drawn here rather than by each field so a form of five
 * controls can't end up with five slightly different focus rings.
 */
@Composable
internal fun FieldShell(
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    invalid: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val border = when {
        invalid -> colors.error
        focused -> colors.brand
        else -> colors.border
    }
    val shape = RoundedCornerShape(dims.radiusField)
    Row(
        modifier = modifier
            .height(FIELD_HEIGHT)
            // clip() must come BEFORE clickable(): the press/hover indication is drawn by the
            // clickable and is only clipped by shape modifiers that precede it. Backgrounding with
            // a shape alone leaves a square highlight overhanging the rounded corners.
            .clip(shape)
            .background(if (enabled) colors.surfaceAlt else colors.surfaceAlt.copy(alpha = 0.5f))
            .border(1.dp, border, shape)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { content() }
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            trailing()
        }
    }
}

/** A plain single-line text input sized to sit inside [FieldShell]. */
@Composable
internal fun ShellTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    textAlign: TextAlign = TextAlign.Start,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    Box(contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) {
            Text(
                placeholder,
                style = typography.body.copy(textAlign = textAlign),
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = typography.body.copy(color = colors.textPrimary, textAlign = textAlign),
            cursorBrush = SolidColor(colors.brand),
            modifier = Modifier.fillMaxWidth().onFocusChanged { onFocusChanged(it.isFocused) },
        )
    }
}

// ── account picker ──────────────────────────────────────────────────────────

/**
 * From / To picker (ticket #90).
 *
 * Uses the same overlapping `Popup` the Add-Inventory dropdown uses: the list floats **over** the
 * page anchored under the field, instead of expanding inline and shoving everything below it down
 * — which is what broke the layout in the first pass. Width is measured from the field so the two
 * always match, and the list is capped in height so a long party list can't run off-screen.
 *
 * Accounts are grouped (the shop's own Cash/Bank first, then parties) with each party's live HL
 * balance on the right, so the cashier can see what someone owes without leaving the form.
 */
@Composable
internal fun AccountDropdownField(
    label: String,
    selected: MoneyAccountRef?,
    options: List<MoneyAccountOption>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (MoneyAccountRef) -> Unit,
    currency: String,
    balance: EntityBalance?,
    invalid: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val density = LocalDensity.current

    var expanded by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0.dp) }
    var fieldHeightPx by remember { mutableStateOf(0) }
    // After any close, briefly refuse to reopen — swallows the click-outside + focus/pointer replay
    // that would otherwise reopen the list on the next mouse move (see FilterableDropdownField).
    var reopenSuppressedUntilMs by remember { mutableStateOf(0L) }
    fun closeDropdown() {
        expanded = false
        reopenSuppressedUntilMs = System.currentTimeMillis() + ACCOUNT_REOPEN_SUPPRESS_MS
    }
    // Chevron toggle — guards the OPEN side with the suppression window so a paired click-outside
    // dismiss (which fires first on desktop) can't cause the chevron to reopen the list.
    fun toggleDropdown() {
        if (expanded) closeDropdown()
        else if (System.currentTimeMillis() >= reopenSuppressedUntilMs) { onQueryChange(""); expanded = true }
    }
    val interactionSource = remember { MutableInteractionSource() }

    val selectedOption = options.firstOrNull { it.ref == selected }

    // Typing filters; an untouched field (query blank, or still showing the selection) lists
    // everything — same behaviour as the inventory dropdown so the two feel identical.
    val filtered = remember(options, query, selectedOption) {
        val selectedName = selectedOption?.label.orEmpty()
        if (query.isBlank() || query.equals(selectedName, ignoreCase = true)) options
        else options.filter { it.label.contains(query.trim(), ignoreCase = true) }
    }

    Column(modifier) {
        FieldLabel(label) {
            // The party's live HL balance, right-aligned against the field's right edge.
            balance?.let {
                Text(
                    balanceText(it, currency),
                    style = typography.hint,
                    color = when (it.direction) {
                        BalanceDirection.RECEIVABLE -> colors.success
                        BalanceDirection.CREDIT -> colors.warning
                        BalanceDirection.SETTLED -> colors.textTertiary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(LABEL_GAP))
        Box(
            Modifier.fillMaxWidth().onSizeChanged {
                fieldWidth = with(density) { it.width.toDp() }
                fieldHeightPx = it.height
            },
        ) {
            FieldShell(
                modifier = Modifier.fillMaxWidth(),
                focused = isFocused || expanded,
                invalid = invalid,
                enabled = enabled,
                // Clicking anywhere in the field opens the list AND clears the typed text, so the
                // first keystroke filters instead of appending to the name already sitting there.
                // Honour the reopen-suppression so a click that just dismissed can't reopen it.
                onClick = {
                    if (!expanded && System.currentTimeMillis() >= reopenSuppressedUntilMs) {
                        onQueryChange("")
                        expanded = true
                    }
                },
                trailing = {
                    Icon(
                        Icons.Filled.ExpandMore,
                        null,
                        tint = colors.textTertiary,
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .rotate(if (expanded) 180f else 0f)
                            .pointerHoverIcon(PointerIcon.Hand)
                            // Toggle open/closed — retracts the list on click, same as field/outside.
                            .clickable(enabled = enabled) { toggleDropdown() },
                    )
                },
            ) {
                ShellTextField(
                    value = query,
                    onValueChange = {
                        onQueryChange(it)
                        if (enabled) expanded = true
                    },
                    placeholder = selectedOption?.label ?: strings(Strings.money_pick_account),
                    enabled = enabled,
                    // Open when the field gains focus. The text field fills the shell and consumes the
                    // click for focus, so FieldShell's onClick never fires on it — focus is the only
                    // reliable "user clicked the field" signal. The reopen-suppression window (set on
                    // every close) still swallows the dismiss→refocus replay that used to flicker.
                    onFocusChanged = { focused ->
                        isFocused = focused
                        if (focused && enabled && !expanded &&
                            System.currentTimeMillis() >= reopenSuppressedUntilMs
                        ) {
                            onQueryChange("")
                            expanded = true
                        }
                    },
                )
            }

            if (expanded && enabled && fieldWidth > 0.dp) {
                AccountPopup(
                    fieldWidth = fieldWidth,
                    fieldHeightPx = fieldHeightPx,
                    options = filtered,
                    selected = selected,
                    currency = currency,
                    onPick = {
                        onSelect(it.ref)
                        onQueryChange(it.label)
                        closeDropdown()
                    },
                    onDismiss = { closeDropdown() },
                )
            }
        }
    }
}

@Composable
private fun AccountPopup(
    fieldWidth: Dp,
    fieldHeightPx: Int,
    options: List<MoneyAccountOption>,
    selected: MoneyAccountRef?,
    currency: String,
    onPick: (MoneyAccountOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val gapPx = with(LocalDensity.current) { 4.dp.roundToPx() }

    val own = options.filter { it.isOwnAccount }
    val parties = options.filterNot { it.isOwnAccount }

    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(0, fieldHeightPx + gapPx),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
    ) {
        Surface(
            shape = RoundedCornerShape(dims.radiusField),
            shadowElevation = 10.dp,
            color = colors.surface,
            modifier = Modifier.width(fieldWidth),
        ) {
            if (options.isEmpty()) {
                Text(
                    strings(Strings.money_no_matches),
                    style = typography.hint,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 260.dp)) {
                    if (own.isNotEmpty()) {
                        item { PopupGroupLabel(strings(Strings.money_own_accounts)) }
                        items(own, key = { it.label }) { AccountRow(it, selected, currency, onPick) }
                    }
                    if (parties.isNotEmpty()) {
                        item {
                            if (own.isNotEmpty()) HorizontalDivider(color = colors.border)
                            PopupGroupLabel(strings(Strings.money_parties))
                        }
                        items(parties, key = { it.ref.entityIdOrNull ?: it.label }) {
                            AccountRow(it, selected, currency, onPick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PopupGroupLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        style = AromexTheme.typography.hint.copy(fontWeight = FontWeight.SemiBold, fontSize = 10.sp, letterSpacing = 0.6.sp),
        color = AromexTheme.colors.textTertiary,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun AccountRow(
    option: MoneyAccountOption,
    selected: MoneyAccountRef?,
    currency: String,
    onPick: (MoneyAccountOption) -> Unit,
) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography
    val hoverSrc = remember { MutableInteractionSource() }
    val hovered by hoverSrc.collectIsHoveredAsState()
    val isSelected = option.ref == selected

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .hoverable(hoverSrc)
            .background(
                when {
                    isSelected -> colors.brand.copy(alpha = 0.10f)
                    hovered -> colors.brand.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable { onPick(option) }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            option.label,
            style = typography.body.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        option.balance?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                balanceText(it, currency),
                style = typography.hint,
                color = when (it.direction) {
                    BalanceDirection.RECEIVABLE -> colors.success
                    BalanceDirection.CREDIT -> colors.warning
                    BalanceDirection.SETTLED -> colors.textTertiary
                },
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun balanceText(balance: EntityBalance, currency: String): String {
    val amount = MoneyFormat.format(balance.net, currency)
    return when (balance.direction) {
        BalanceDirection.RECEIVABLE -> "${strings(Strings.money_balance_owes)} $amount"
        BalanceDirection.CREDIT -> "${strings(Strings.money_balance_owed)} $amount"
        BalanceDirection.SETTLED -> strings(Strings.money_balance_settled)
    }
}

// ── date picker ─────────────────────────────────────────────────────────────

/**
 * The accounting date (ticket #90) — click to open a month grid.
 *
 * A field the cashier can only read is useless for the case that motivated backdating in the first
 * place: recording yesterday's payment this morning. Self-drawn rather than Material3's
 * `DatePicker` so it matches the theme exactly and stays off an experimental API. Future dates are
 * blocked — an entry dated tomorrow would land in the wrong period and there's no honest reason
 * for one.
 */
@Composable
internal fun DateField(
    label: String,
    millis: Long,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var fieldWidth by remember { mutableStateOf(0.dp) }
    var fieldHeightPx by remember { mutableStateOf(0) }

    Column(modifier) {
        FieldLabel(label)
        Spacer(Modifier.height(LABEL_GAP))
        Box(
            Modifier.fillMaxWidth().onSizeChanged {
                fieldWidth = with(density) { it.width.toDp() }
                fieldHeightPx = it.height
            },
        ) {
            FieldShell(
                modifier = Modifier.fillMaxWidth(),
                focused = expanded,
                enabled = enabled,
                onClick = { if (enabled) expanded = !expanded },
                trailing = {
                    Icon(Icons.Filled.CalendarToday, null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                },
            ) {
                Text(
                    dayFormat.format(Date(millis)),
                    style = AromexTheme.typography.body,
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded && fieldWidth > 0.dp) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(0, fieldHeightPx + with(density) { 4.dp.roundToPx() }),
                    onDismissRequest = { expanded = false },
                    properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
                ) {
                    Surface(
                        shape = RoundedCornerShape(dims.radiusField),
                        shadowElevation = 10.dp,
                        color = colors.surface,
                        modifier = Modifier.width(maxOf(fieldWidth, 260.dp)),
                    ) {
                        MonthGrid(millis) { picked -> onPick(picked); expanded = false }
                    }
                }
            }
        }
    }
}

/**
 * The month laid out as weeks of seven, `null` for the leading and trailing blanks.
 *
 * Pure and separate from the composable **because this is where the arithmetic went wrong once**:
 * an earlier version folded the first week's offset into every row, so every week after the first
 * repeated days and the end of the month was unreachable. Maths that subtle belongs somewhere a
 * test can reach it.
 *
 * @param firstOffset how many blank cells precede the 1st (0 = the month starts on Monday).
 */
internal fun monthGridWeeks(firstOffset: Int, daysInMonth: Int): List<List<Int?>> {
    val weeks = mutableListOf<List<Int?>>()
    var weekStart = 1 - firstOffset
    while (weekStart <= daysInMonth) {
        weeks += (0 until 7).map { slot ->
            val day = weekStart + slot
            if (day in 1..daysInMonth) day else null
        }
        weekStart += 7
    }
    return weeks
}

/** The month grid, shared by the entry-date field and the history's date-range filter. */
@Composable
internal fun InlineMonthGrid(initial: Long, onPick: (Long) -> Unit) = MonthGrid(initial, onPick)

@Composable
private fun MonthGrid(selectedMillis: Long, onPick: (Long) -> Unit) {
    val colors = AromexTheme.colors
    val typography = AromexTheme.typography

    val selectedCal = remember(selectedMillis) { Calendar.getInstance().apply { timeInMillis = selectedMillis } }
    var viewYear by remember(selectedMillis) { mutableStateOf(selectedCal.get(Calendar.YEAR)) }
    var viewMonth by remember(selectedMillis) { mutableStateOf(selectedCal.get(Calendar.MONTH)) }

    val today = remember { Calendar.getInstance() }
    val cursor = remember(viewYear, viewMonth) {
        Calendar.getInstance().apply { clear(); set(viewYear, viewMonth, 1) }
    }
    val daysInMonth = cursor.getActualMaximum(Calendar.DAY_OF_MONTH)
    // Calendar.DAY_OF_WEEK is 1=Sunday; the grid starts on Monday, hence the shift.
    val firstOffset = (cursor.get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun isFuture(day: Int): Boolean {
        val c = Calendar.getInstance().apply { clear(); set(viewYear, viewMonth, day) }
        val t = Calendar.getInstance().apply {
            clear(); set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
        }
        return c.after(t)
    }

    Column(Modifier.padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NavArrow(Icons.Filled.ChevronLeft) {
                if (viewMonth == 0) { viewMonth = 11; viewYear -= 1 } else viewMonth -= 1
            }
            Text(
                monthFormat.format(cursor.time),
                style = typography.bodyStrong,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            NavArrow(Icons.Filled.ChevronRight) {
                if (viewMonth == 11) { viewMonth = 0; viewYear += 1 } else viewMonth += 1
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(
                    it,
                    style = typography.hint.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        monthGridWeeks(firstOffset, daysInMonth).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cellDay ->
                    Box(Modifier.weight(1f).height(30.dp), contentAlignment = Alignment.Center) {
                        if (cellDay != null) {
                            val isSelected = cellDay == selectedCal.get(Calendar.DAY_OF_MONTH) &&
                                viewMonth == selectedCal.get(Calendar.MONTH) &&
                                viewYear == selectedCal.get(Calendar.YEAR)
                            val disabled = isFuture(cellDay)
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) colors.brand else Color.Transparent)
                                    .then(
                                        if (disabled) Modifier
                                        else Modifier
                                            .pointerHoverIcon(PointerIcon.Hand)
                                            .clickable {
                                                // Carry the existing time of day across. Zeroing it
                                                // would stamp every backdated entry 12:00 AM and
                                                // make the table's time column a lie.
                                                onPick(
                                                    Calendar.getInstance().apply {
                                                        timeInMillis = selectedMillis
                                                        set(Calendar.YEAR, viewYear)
                                                        set(Calendar.MONTH, viewMonth)
                                                        set(Calendar.DAY_OF_MONTH, cellDay)
                                                    }.timeInMillis,
                                                )
                                            },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    cellDay.toString(),
                                    style = typography.hint,
                                    color = when {
                                        isSelected -> colors.onBrand
                                        disabled -> colors.disabled
                                        else -> colors.textPrimary
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

@Composable
private fun NavArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Icon(
        icon,
        null,
        tint = AromexTheme.colors.textSecondary,
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick),
    )
}

internal val dayFormat = java.text.SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private val monthFormat = java.text.SimpleDateFormat("MMMM yyyy", Locale.getDefault())
