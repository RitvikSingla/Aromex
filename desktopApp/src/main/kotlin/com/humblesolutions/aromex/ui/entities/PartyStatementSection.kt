package com.humblesolutions.aromex.ui.entities

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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.humblesolutions.aromex.i18n.Strings
import com.humblesolutions.aromex.model.AccountStatement
import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.StatementEvent
import com.humblesolutions.aromex.model.MoneyDirection
import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.ui.i18n.strings
import com.humblesolutions.aromex.ui.money.SearchBox
import com.humblesolutions.aromex.ui.money.DateRangeChip
import com.humblesolutions.aromex.ui.money.ToolbarChip
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.humblesolutions.aromex.ui.theme.AromexTheme
import com.humblesolutions.aromex.util.Money
import com.humblesolutions.aromex.util.MoneyFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A party's statement (ticket #91), inside the Entity detail panel.
 *
 * Reads as **what actually happened**, not as bookkeeping: one signed, coloured amount per row —
 * green for money that came into the business, red for money that left — instead of the debit and
 * credit columns that are how the ledger stays correct but not how anyone thinks about their own
 * shop. Reversed movements and their mirrors are gone entirely; reversing something is how you say
 * it didn't happen.
 *
 * The running balance is still **Humble Ledger's**, never recomputed here: a client-side total is
 * wrong across pages (page 2 can't know page 1's sum) and wrong for any date range (no opening
 * balance), and the moment it disagrees with the ledger nobody can say which number is right.
 */
@Composable
internal fun PartyStatementSection(
    statement: AccountStatement?,
    isLoading: Boolean,
    error: String?,
    currency: String,
    /** The shop's IANA zone — statement times are the shop's clock, not the reader's. */
    timezone: String,
    moneyByTransaction: Map<String, MoneyEntry>,
    cancelledTransactionIds: Set<String>,
    canManage: Boolean,
    reversingEntryId: String?,
    onReverse: (MoneyEntry) -> Unit,
    onLoadMore: () -> Unit,
    // ── controls (ticket #108) ───────────────────────────────────────────────
    search: String = "",
    onSearchChange: (String) -> Unit = {},
    rangeFrom: Long? = null,
    rangeTo: Long? = null,
    onRangeChange: (Long?, Long?) -> Unit = { _, _ -> },
    ascending: Boolean = false,
    onToggleSort: () -> Unit = {},
    onClearFilters: () -> Unit = {},
    onPrint: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography

    val loaded = statement?.rows?.filterNot { it.transactionId in cancelledTransactionIds }.orEmpty()
    val q = search.trim()
    // Collapse double entry into business events FIRST, while the rows are still in HL's order — an
    // event's balance is its last leg's, so grouping after a descending sort would read the wrong
    // one. Searching and sorting then work on what the user can actually see.
    val events = remember(loaded) { StatementEvent.from(loaded) }
    val rows = remember(events, q, ascending) {
        val filtered = if (q.isEmpty()) events else events.filter {
            it.description.contains(q, true) ||
                it.postingType.contains(q, true) ||
                it.date.contains(q) ||
                it.billed?.contains(q) == true ||
                it.cashIn?.contains(q) == true ||
                it.cashOut?.contains(q) == true
        }
        orderStatementEvents(filtered, ascending)
    }
    val isFiltered = q.isNotEmpty() || rangeFrom != null || rangeTo != null

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(strings(Strings.money_statement), style = typography.sectionTitle, color = colors.textPrimary)
            statement?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        strings(Strings.money_statement_closing),
                        style = typography.hint,
                        color = colors.textTertiary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        signedBalance(it.closingBalance, currency),
                        style = typography.bodyStrong,
                        color = colors.textPrimary,
                    )
                    balanceCaption(it.closingBalance)?.let { caption ->
                        Spacer(Modifier.width(4.dp))
                        Text(caption, style = typography.hint, color = colors.textTertiary)
                    }
                }
            }
        }
        // The same controls as the money ledger, from the same parts.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = dims.space20, vertical = dims.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // "12 of 40" while narrowing, so a filter can never look like missing history.
            Text(
                // Both sides count *events*, not ledger legs — "1 of 4" when four legs became two
                // rows would read as two rows lost to the filter.
                if (isFiltered) "${rows.size} ${strings(Strings.money_of)} ${events.size}" else "${rows.size}",
                style = typography.hint,
                color = colors.textTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(dims.radiusPill))
                    .background(colors.surfaceAlt)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            if (statement != null) {
                ToolbarChip(strings(Strings.statement_print), onClick = onPrint)
                Spacer(Modifier.width(dims.space8))
            }
            ToolbarChip(
                strings(if (ascending) Strings.statement_sort_oldest else Strings.statement_sort_newest),
                onClick = onToggleSort,
            )
            Spacer(Modifier.width(dims.space8))
            SearchBox(search, onSearchChange)
            Spacer(Modifier.width(dims.space8))
            DateRangeChip(rangeFrom, rangeTo, onRangeChange)
            if (isFiltered) {
                Spacer(Modifier.width(dims.space8))
                ToolbarChip(strings(Strings.money_clear_filters), onClick = onClearFilters)
            }
        }
        // Search only reaches what has been paged in; the date range is the one that goes to HL.
        if (q.isNotEmpty() && statement?.hasMore == true) {
            Text(
                strings(Strings.statement_search_scope, loaded.size.toString()),
                style = typography.hint,
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = dims.space20, vertical = 2.dp),
            )
        }
        HorizontalDivider(color = colors.border, modifier = Modifier.padding(horizontal = dims.space20))

        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
        when {
            // An unreachable ledger must never look like a party with no history.
            error != null -> StatementMessage(error, isError = true)
            isLoading && statement == null -> Box(
                Modifier.fillMaxWidth().padding(dims.space40),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = colors.brand)
            }
            rows.isEmpty() -> StatementMessage(strings(Strings.money_statement_empty))
            else -> {
                StatementHeaderRow(canManage)
                rows.forEachIndexed { i, row ->
                    // A folded event may cover several transactions; the money entry (and so the
                    // reverse button) belongs to whichever of them this app posted.
                    val entry = row.transactionIds.firstNotNullOfOrNull { moneyByTransaction[it] }
                    StatementRow(
                        event = row,
                        currency = currency,
                        timezone = timezone,
                        striped = i % 2 == 1,
                        entry = entry,
                        canManage = canManage,
                        busy = entry != null && reversingEntryId == entry.entryId,
                        reverseLocked = reversingEntryId != null,
                        onReverse = onReverse,
                    )
                }
                if (statement?.hasMore == true) {
                    Box(Modifier.fillMaxWidth().padding(dims.space12), contentAlignment = Alignment.Center) {
                        TextButton(onClick = onLoadMore, enabled = !isLoading) {
                            Text(
                                strings(Strings.money_statement_load_more),
                                style = typography.button,
                                color = colors.brand,
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
private fun StatementMessage(text: String, isError: Boolean = false) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    Box(Modifier.fillMaxWidth().padding(dims.space40), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Description, null, tint = colors.disabled, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(dims.space12))
            Text(
                text,
                style = AromexTheme.typography.body,
                color = if (isError) colors.error else colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private const val COL_DATE = 0.85f
private const val COL_DESC = 2.2f
private const val COL_AMOUNT = 1.05f
private const val COL_BALANCE = 1f
private val COL_ACTION = 84.dp

@Composable
private fun StatementHeaderRow(canManage: Boolean) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val style = AromexTheme.typography.hint.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.7.sp,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(colors.surfaceAlt)
            .padding(horizontal = dims.space20),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeadCell(strings(Strings.money_col_date), COL_DATE, style)
        HeadCell(strings(Strings.money_col_particulars), COL_DESC, style)
        HeadCell(strings(Strings.money_col_money), COL_AMOUNT, style, TextAlign.End)
        HeadCell(strings(Strings.money_statement_balance), COL_BALANCE, style, TextAlign.End, last = !canManage)
        if (canManage) Box(Modifier.width(COL_ACTION))
    }
}

@Composable
private fun RowScope.HeadCell(
    text: String,
    weight: Float,
    style: TextStyle,
    align: TextAlign = TextAlign.Start,
    last: Boolean = false,
) {
    val colors = AromexTheme.colors
    Box(Modifier.weight(weight).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        Text(
            text.uppercase(Locale.getDefault()),
            style = style.copy(textAlign = align),
            color = colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        if (!last) Hairline(Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.width(1.dp).fillMaxHeight().background(AromexTheme.colors.border.copy(alpha = 0.6f)))
}

@Composable
private fun StatementRow(
    event: StatementEvent,
    currency: String,
    timezone: String,
    striped: Boolean,
    entry: MoneyEntry?,
    canManage: Boolean,
    busy: Boolean,
    reverseLocked: Boolean,
    onReverse: (MoneyEntry) -> Unit,
) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val typography = AromexTheme.typography
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()

    // The money column is money: the cash this event actually moved. A sale's *value* is not money —
    // it rides along under the description instead, so the column can never claim $1,111 came in
    // when $111 did.
    val cash = event.cashIn ?: event.cashOut
    val direction = when {
        event.cashIn != null -> MoneyDirection.IN
        event.cashOut != null -> MoneyDirection.OUT
        else -> MoneyDirection.INTERNAL
    }

    Column {
        // One SelectionContainer around the whole row: a press-and-drag selects the date,
        // description, amount and running balance together; the Reverse button still clicks.
        SelectionContainer {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .hoverable(src)
                .background(
                    when {
                        hovered -> colors.brand.copy(alpha = 0.05f)
                        striped -> colors.surfaceAlt.copy(alpha = 0.45f)
                        else -> Color.Transparent
                    },
                )
                .padding(horizontal = dims.space20),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BodyCell(COL_DATE) {
                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        event.date.take(10),
                        style = typography.hint.copy(fontSize = 12.sp),
                        color = colors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    timeOfDay(event, timezone)?.let { time ->
                        Text(
                            time,
                            style = typography.hint.copy(fontSize = 10.sp),
                            color = colors.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
            BodyCell(COL_DESC) {
                Column(verticalArrangement = Arrangement.Center) {
                    Text(
                        event.description.ifBlank { postingLabel(event.postingType) },
                        style = typography.body.copy(fontSize = 14.sp),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // What the event was worth, said in words rather than posted as a second row.
                    // Without it a sale that took no cash would be a blank money column and an
                    // unexplained jump in the balance.
                    if (event.isCharge) {
                        Text(
                            billedLabel(event, currency),
                            style = typography.hint.copy(fontSize = 11.sp),
                            color = colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            BodyCell(COL_AMOUNT) {
                if (cash == null) {
                    // Nothing changed hands. A dash states that; a "$0.00" would read as a payment
                    // of zero, and an empty cell as a rendering fault.
                    Text(
                        strings(Strings.money_event_nothing_paid),
                        style = typography.hint.copy(fontSize = 12.sp, textAlign = TextAlign.End),
                        color = colors.textTertiary,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    DirectionalAmountText(direction, cash, currency)
                }
            }
            BodyCell(COL_BALANCE, last = !canManage) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        signedBalance(event.balance, currency),
                        style = typography.body.copy(fontSize = 14.sp, textAlign = TextAlign.End),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    balanceCaption(event.balance)?.let { caption ->
                        Text(
                            caption,
                            style = typography.hint.copy(fontSize = 10.sp, textAlign = TextAlign.End),
                            color = colors.textTertiary,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            if (canManage) {
                Box(Modifier.width(COL_ACTION).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    // Only rows this app posted can be reversed — a sale or a purchase is undone
                    // by its own feature, not from a statement row.
                    if (entry != null && entry.canReverse) {
                        StatementReverseButton(
                            busy = busy,
                            enabled = !reverseLocked,
                            onClick = { onReverse(entry) },
                        )
                    }
                }
            }
        }
        }
        HorizontalDivider(color = colors.border.copy(alpha = 0.45f))
    }
}

@Composable
private fun RowScope.BodyCell(weight: Float, last: Boolean = false, content: @Composable () -> Unit) {
    Box(Modifier.weight(weight).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) { content() }
        if (!last) Hairline(Modifier.align(Alignment.CenterEnd))
    }
}

/** Green in, red out, plain for anything that moved nothing overall. Mirrors the Money screen. */
@Composable
private fun DirectionalAmountText(direction: MoneyDirection, amount: String, currency: String) {
    val colors = AromexTheme.colors
    val body = MoneyFormat.format(amount.removePrefix("-"), currency)
    val (text, tint) = when (direction) {
        MoneyDirection.IN -> "+$body" to colors.success
        MoneyDirection.OUT -> "−$body" to colors.error
        MoneyDirection.INTERNAL -> body to colors.textSecondary
    }
    Text(
        text,
        style = AromexTheme.typography.body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, textAlign = TextAlign.End),
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatementReverseButton(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = AromexTheme.colors
    val dims = AromexTheme.dimensions
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(dims.radiusPill)
    Row(
        modifier = Modifier
            .height(26.dp)
            .widthIn(min = 68.dp)
            .clip(shape)
            .hoverable(src)
            .background(if (hovered && enabled) colors.brandTint else Color.Transparent)
            .border(1.dp, if (enabled) colors.border else colors.border.copy(alpha = 0.5f), shape)
            .then(if (enabled && !busy) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = colors.brand)
        } else {
            Text(
                strings(Strings.money_reverse),
                style = AromexTheme.typography.hint,
                color = if (enabled) colors.brand else colors.disabled,
                maxLines = 1,
            )
        }
    }
}

/**
 * The balance with its direction on the face of it: `+$400`, `−$1,000`, `$0`.
 *
 * The column used to strip the sign, so "you owe them a thousand" and "they owe you a thousand"
 * printed identically — and a real statement did exactly that, running from +$400 to −$1,000 across
 * a single purchase. Sign and caption say which way; deliberately no colour, because green/red here
 * would fight the money column, where they already mean cash in and cash out.
 */
internal fun signedBalance(balance: String, currency: String): String {
    val body = MoneyFormat.format(Money.abs(balance), currency)
    return when (BalanceDirection.fromBalance(balance)) {
        BalanceDirection.RECEIVABLE -> "+$body"
        BalanceDirection.CREDIT -> "−$body"
        BalanceDirection.SETTLED -> body
    }
}

/** "they owe" / "you owe", or null when it's settled and there is no direction to name. */
@Composable
private fun balanceCaption(balance: String): String? = when (BalanceDirection.fromBalance(balance)) {
    BalanceDirection.RECEIVABLE -> strings(Strings.money_balance_they_owe)
    BalanceDirection.CREDIT -> strings(Strings.money_balance_you_owe)
    BalanceDirection.SETTLED -> null
}

/**
 * Orders statement events by the **transaction** date — the day the money moved, not the day it was
 * entered — newest or oldest first.
 *
 * Sorting on the date alone is not enough, and quietly produced a wrong statement. HL stores a
 * posting's accounting date as a calendar date, so every event on the same day carries an identical
 * date string; a stable `sortedByDescending` therefore reversed the *days* while leaving each day's
 * events in ascending order. A sale rung up at 9:18pm and voided at 9:21pm printed with the sale
 * above the void under "Newest first" — reading bottom-up, the refund appeared to precede the
 * payment it reversed.
 *
 * HL returns rows in true chronological order (`date asc, createdAt asc`), so that sequence — not
 * the date string — is what knows which of two same-day events came first. The date sort below is
 * stable and therefore preserves it; reversing the whole list then flips days *and* within-day
 * order together.
 */
internal fun orderStatementEvents(
    events: List<StatementEvent>,
    ascending: Boolean,
): List<StatementEvent> {
    val oldestFirst = events.withIndex()
        .sortedWith(compareBy({ it.value.date }, { it.index }))
        .map { it.value }
    return if (ascending) oldestFirst else oldestFirst.reversed()
}

/**
 * The clock time to print under a statement row's date, or null to print none.
 *
 * HL stores a posting's accounting date as a **calendar date** — no time of day exists on it — so
 * the time comes from when the posting was recorded. Those are the same moment for an entry made as
 * it happens, and deliberately different for a **backdated** one: a June sale entered in August.
 * Printing August's clock beside June's date would invent a moment that never occurred, so a row
 * whose recorded day differs from its business day shows no time at all.
 */
internal fun timeOfDay(event: StatementEvent, timezone: String): String? {
    val recorded = event.recordedAt?.takeIf { it.isNotBlank() } ?: return null
    val zone = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
    val moment = runCatching { Instant.parse(recorded).atZone(zone) }.getOrNull() ?: return null
    if (moment.toLocalDate().toString() != event.date.take(10)) return null
    return moment.format(TIME_FORMAT)
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a")

/** "Sale of $918.00" — the event's worth, named by what kind of event it was. */
@Composable
private fun billedLabel(event: StatementEvent, currency: String): String {
    val amount = MoneyFormat.format(event.billed.orEmpty().removePrefix("-"), currency)
    return when (event.postingType.uppercase()) {
        "SALE" -> strings(Strings.money_event_sale_value, amount)
        "PURCHASE" -> strings(Strings.money_event_purchase_value, amount)
        else -> strings(Strings.money_event_billed_value, amount)
    }
}

/** A fallback label for a row HL sent with no description of its own. */
@Composable
private fun postingLabel(postingType: String): String = when (postingType.uppercase()) {
    "SALE" -> strings(Strings.entities_sidebar_sales)
    "PAYMENT" -> strings(Strings.money_posting_payment)
    "PAYOUT" -> strings(Strings.money_posting_payout)
    "PURCHASE" -> strings(Strings.money_posting_purchase)
    "EXPENSE" -> strings(Strings.money_posting_expense)
    "REFUND" -> strings(Strings.money_posting_refund)
    else -> strings(Strings.money_title)
}
