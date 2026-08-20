package com.humblesolutions.aromex.model

import com.humblesolutions.aromex.util.Money

/**
 * One **business event** on a party's statement — a sale, a purchase, a payment — assembled from the
 * one-or-more ledger rows that double-entry produced for it.
 *
 * ── Why this exists ──────────────────────────────────────────────────────────
 * A sale rung up at the counter posts *two* rows: the charge (`SALE`, $1,111) and the cash taken for
 * it (`PAYMENT`, $1,111). Both are correct, and together they are how the books must record it — but
 * a shopkeeper reading their own statement sees the same sale twice, once as a number that never
 * moved and once as money, and reasonably asks which one is real. Worse, the two rows straddle the
 * running balance, so the balance appears to leap and then come back.
 *
 * So the ledger keeps its double entry and the statement shows the event: **the money column is
 * money only** — what actually came in or went out — with the sale's value carried alongside on the
 * same row as context. What was billed vs what was collected belongs in reports, not in a row a
 * customer is meant to read at a counter.
 *
 * ── How rows are paired ──────────────────────────────────────────────────────
 * Every posting Aromex makes carries a `sourceId` of the form `{type}_{recordId}:{leg}` —
 * `sale_a7Bc:sale` and `sale_a7Bc:payment_cash` are the same sale. The text before the first `:` is
 * therefore an exact grouping key, not a guess at descriptions or dates, and it works retroactively
 * because every posting ever made already carries one. A row whose `sourceId` is unknown (HL's
 * `/ledger` omits it, so it's joined on separately, and that join is best-effort) stands alone —
 * exactly the old behaviour, never a wrong pairing.
 */
data class StatementEvent(
    val date: String,
    val description: String,
    /** The anchor row's posting type — `SALE`, `PAYMENT`, `PURCHASE`… drives labelling. */
    val postingType: String,
    /** What was billed: the sale/purchase value. Null when the event only moved money. */
    val billed: String?,
    /** Money received, as a positive decimal string; null when none came in. */
    val cashIn: String?,
    /** Money paid out, as a positive decimal string; null when none went out. */
    val cashOut: String?,
    /** HL's running balance after the event's last row — never recomputed here. */
    val balance: String,
    /**
     * When this was recorded, as an ISO instant — see [LedgerRow.recordedAt]. Null when unresolved.
     *
     * A renderer must check this falls on the same calendar day as [date] before showing it as a
     * time of day: for a backdated entry it deliberately doesn't, and pairing the two would print a
     * moment that never happened.
     */
    val recordedAt: String?,
    /** Every ledger row folded into this event, in ledger order. Never empty. */
    val transactionIds: List<String>,
) {
    /** The row that identifies the event — what a reversal or a money-entry match keys on. */
    val anchorTransactionId: String get() = transactionIds.first()

    /** True when no cash moved: a credit sale, or a purchase taken entirely on account. */
    val movedNoCash: Boolean get() = cashIn == null && cashOut == null

    /**
     * True when this event is a charge whose cash is shown on the same row — i.e. the case that used
     * to render as two rows. The UI uses it to decide whether to show the billed figure as context.
     */
    val isCharge: Boolean get() = billed != null

    val isReversal: Boolean get() = postingType.equals(LedgerRow.POSTING_REVERSAL, ignoreCase = true)

    companion object {
        /** Posting types that record a charge rather than a movement of money. */
        private val CHARGE_TYPES = setOf("SALE", "PURCHASE")
        private val CASH_IN_TYPES = setOf("PAYMENT", "ADVANCE")
        private val CASH_OUT_TYPES = setOf("PAYOUT", "EXPENSE", "REFUND")

        /**
         * Collapses [rows] into one event per business record, preserving ledger order.
         *
         * Rows are expected in HL's order (transaction date ascending, then creation order); the
         * event's [balance] is taken from the **last** row of its group, so it is the balance after
         * the whole event rather than after an arbitrary leg of it.
         *
         * Paging caveat: if a sale's charge lands on one page and its cash on the next, the two
         * render as separate events. That degrades to today's behaviour rather than showing a wrong
         * number, and pages are large enough that same-transaction legs almost never straddle one.
         */
        fun from(rows: List<LedgerRow>): List<StatementEvent> {
            if (rows.isEmpty()) return emptyList()

            // Preserve first-appearance order of groups; a row with no resolvable sourceId gets a
            // key of its own so it can never be folded into someone else's event.
            val groups = LinkedHashMap<String, MutableList<LedgerRow>>(rows.size)
            for (row in rows) {
                val key = row.sourceId
                    ?.substringBefore(':')
                    ?.takeIf { it.isNotBlank() }
                    ?: "txn:${row.transactionId}"
                groups.getOrPut(key) { mutableListOf() }.add(row)
            }

            return groups.values.map { group -> fold(group) }
        }

        private fun fold(group: List<LedgerRow>): StatementEvent {
            // The charge is the event's identity — a sale is "a sale", not "a payment that happened
            // to have a sale attached" — so it anchors the row's date and description when present.
            val anchor = group.firstOrNull { it.postingType.uppercase() in CHARGE_TYPES } ?: group.first()

            var billed: String? = null
            var cashIn: String? = null
            var cashOut: String? = null

            for (row in group) {
                val amount = amountOf(row)
                if (Money.isZero(amount)) continue
                when (row.postingType.uppercase()) {
                    in CHARGE_TYPES -> billed = billed?.let { Money.add(it, amount) } ?: amount
                    in CASH_IN_TYPES -> cashIn = cashIn?.let { Money.add(it, amount) } ?: amount
                    in CASH_OUT_TYPES -> cashOut = cashOut?.let { Money.add(it, amount) } ?: amount
                    // JOURNAL, ADVANCE_APPLIED, REVERSAL and anything HL adds later: an adjustment,
                    // not money. Counting it as cash would overstate what was collected, so it only
                    // shows through the balance — the same treatment a charge gets.
                    else -> billed = billed?.let { Money.add(it, amount) } ?: amount
                }
            }

            return StatementEvent(
                date = anchor.date,
                description = anchor.description,
                postingType = anchor.postingType,
                billed = billed,
                cashIn = cashIn,
                cashOut = cashOut,
                // HL's balance after the event's final row, untouched (see LedgerRow.balance).
                balance = group.last().balance,
                // The charge's own clock where there is one, so a sale reads as when it was rung
                // up rather than when its cash leg happened to post.
                recordedAt = anchor.recordedAt ?: group.firstNotNullOfOrNull { it.recordedAt },
                transactionIds = group.map { it.transactionId },
            )
        }

        /** Whichever side HL posted is *the* amount; the posting type says which way it went. */
        private fun amountOf(row: LedgerRow): String =
            (row.debit ?: row.credit ?: "0").removePrefix("-")
    }
}
