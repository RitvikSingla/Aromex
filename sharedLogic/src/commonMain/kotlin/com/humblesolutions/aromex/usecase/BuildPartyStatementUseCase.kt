package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.AgingBucket
import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.LedgerRow
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.StatementDocument
import com.humblesolutions.aromex.model.StatementEvent
import com.humblesolutions.aromex.model.StatementLine
import com.humblesolutions.aromex.repository.EntityLedgerRepository
import com.humblesolutions.aromex.util.CalendarDate
import com.humblesolutions.aromex.util.Money

/** Raised when a range has more than [BuildPartyStatementUseCase.MAX_ROWS] entries. */
class StatementTooLargeException(val maxRows: Int) : Exception(
    "This period has more than $maxRows entries — narrow the date range.",
)

/**
 * Assembles a party's statement (ticket #109) — the *logic* behind the printable PDF, kept out of
 * the UI so Android, iOS and Desktop share it exactly.
 *
 * Three jobs the raw [EntityLedgerRepository.getStatement] does not do:
 *
 * 1. **Every row, not one page.** `getStatement` pages; this loops until `hasMore` is false,
 *    concatenating, capped at [MAX_ROWS] — over the cap it throws [StatementTooLargeException]
 *    rather than printing a silently truncated statement.
 *
 * 2. **The opening balance,** which is not in the range response. One extra call
 *    (`from = null, to = the day before the period`) reads HL's own closing balance as at the day
 *    before — never recomputed from the first row, whose signed arithmetic isn't reproducible here.
 *    An all-time statement (`from == null`) opens at `0.00`.
 *
 * 3. **Aging of the balance,** derived from the ledger movements themselves — never from HL's
 *    `/receivables`, which ages unpaid *invoices* (a different quantity that ignores unapplied
 *    credits and non-invoice movements, so its buckets would not reconcile with the closing
 *    balance). Direction comes from HL's own running balance (`delta = balance − previous`), so
 *    there is no debit/credit sign convention to reason about; a FIFO queue seeded with the opening
 *    balance settles oldest debt first. The four buckets sum to the closing balance exactly.
 *
 * Gated on `profiles: view` — this is the same data as the on-screen statement.
 */
class BuildPartyStatementUseCase(
    private val ledger: EntityLedgerRepository,
) {
    /**
     * @param entity the party (its [Entity.id] is the HL externalId).
     * @param permissions the caller's scopes — throws [PermissionDeniedException] without `profiles`.
     * @param fromIso inclusive `yyyy-MM-dd` start, or null for an all-time statement.
     * @param toIso inclusive `yyyy-MM-dd` end (callers pass today when the range is open-ended) —
     *   the date the aging is measured at.
     * @param includeNotes when false, no [StatementLine.note] is ever set.
     * @param noteByTransactionId note text keyed by the row's `transactionId` (money-entry / sale
     *   notes the caller has indexed); a row with no match simply has no note.
     */
    suspend fun execute(
        entity: Entity,
        permissions: Permissions,
        fromIso: String?,
        toIso: String,
        includeNotes: Boolean,
        noteByTransactionId: Map<String, String> = emptyMap(),
    ): StatementDocument {
        if (permissions.profiles == PermissionLevel.NONE) throw PermissionDeniedException("profiles")

        val periodFromDisplay = fromIso?.let(CalendarDate::formatDisplay)
        val periodToDisplay = CalendarDate.formatDisplay(toIso)

        // ── 1. Page every row in the range ───────────────────────────────────────
        val rows = ArrayList<LedgerRow>()
        var closingBalance = "0.00"
        var page = 1
        var sawStatement = false
        while (true) {
            val statement = ledger.getStatement(entity.id, fromIso, toIso, page, PAGE_LIMIT)
                ?: break // HL has no customer for this party yet → empty statement
            sawStatement = true
            if (page == 1) closingBalance = Money.round2(statement.closingBalance)
            rows.addAll(statement.rows)
            if (rows.size > MAX_ROWS) throw StatementTooLargeException(MAX_ROWS)
            if (!statement.hasMore) break
            page++
        }
        if (!sawStatement) {
            return StatementDocument(
                periodFrom = periodFromDisplay,
                periodTo = periodToDisplay,
                openingBalance = "0.00",
                totalDebits = "0.00",
                totalCredits = "0.00",
                closingBalance = "0.00",
                rows = emptyList(),
                agingBuckets = emptyList(),
            )
        }

        // HL returns rows in transaction-date order; the running-balance walk below needs them
        // oldest-first. If the concatenated pages came back newest-first, reversing the whole list
        // restores oldest-first exactly (including within-day order).
        val oldestFirst = orientOldestFirst(rows)

        // ── 2. Opening balance (the day before the period), from HL itself ───────
        val openingBalance = openingBalanceFor(entity.id, fromIso)

        // ── Totals + display rows ────────────────────────────────────────────────
        // One printed line per business event, so a sale rung up at the counter prints once — the
        // cash in the money column, the sale's value beside it — instead of as a charge line and a
        // payment line that a customer has to net off in their head.
        val events = StatementEvent.from(oldestFirst)
        val debits = ArrayList<String>(events.size)
        val credits = ArrayList<String>(events.size)
        val lines = events.map { event ->
            event.billed?.takeIf { !Money.isZero(it) }?.let { debits.add(it) }
            event.cashIn?.takeIf { !Money.isZero(it) }?.let { credits.add(it) }
            // An event can span several transactions; the note belongs to whichever of them the app
            // recorded one against.
            val note = if (includeNotes) {
                event.transactionIds.firstNotNullOfOrNull { noteByTransactionId[it]?.takeIf(String::isNotBlank) }
            } else {
                null
            }
            StatementLine(
                date = CalendarDate.formatDisplay(event.date),
                description = event.description,
                note = note,
                billed = event.billed?.let(Money::round2) ?: "",
                kind = event.postingType,
                moneyIn = event.cashIn?.let(Money::round2) ?: "",
                moneyOut = event.cashOut?.let(Money::round2) ?: "",
                balance = Money.round2(event.balance),
            )
        }

        // ── 3. FIFO aging of the outstanding balance ─────────────────────────────
        val agingBuckets = ageBalance(
            openingBalance = openingBalance,
            openingChargeDate = fromIso?.let(CalendarDate::dayBefore),
            rowsOldestFirst = oldestFirst,
            closingBalance = closingBalance,
            asAtIso = toIso,
        )

        return StatementDocument(
            periodFrom = periodFromDisplay,
            periodTo = periodToDisplay,
            openingBalance = Money.round2(openingBalance),
            totalDebits = Money.round2(Money.sum(debits)),
            totalCredits = Money.round2(Money.sum(credits)),
            closingBalance = closingBalance,
            rows = lines,
            agingBuckets = agingBuckets,
        )
    }

    private suspend fun openingBalanceFor(externalId: String, fromIso: String?): String {
        if (fromIso == null) return "0.00"
        val dayBefore = CalendarDate.dayBefore(fromIso) ?: return "0.00"
        val asAt = ledger.getStatement(externalId, from = null, to = dayBefore, page = 1, limit = 1)
        return asAt?.closingBalance ?: "0.00"
    }

    private fun orientOldestFirst(rows: List<LedgerRow>): List<LedgerRow> {
        if (rows.size < 2) return rows
        val first = CalendarDate.toEpochDay(rows.first().date)
        val last = CalendarDate.toEpochDay(rows.last().date)
        return if (first != null && last != null && first > last) rows.asReversed() else rows
    }

    /**
     * Walks the ledger oldest-first, turning each running-balance step into a dated charge (balance
     * went up) or a payment (balance went down), then settles payments against charges FIFO. What
     * remains is bucketed by age at [asAtIso]. Returns an empty list when the party owes nothing
     * (closing ≤ 0) — no aging block rather than four zeros.
     */
    private fun ageBalance(
        openingBalance: String,
        openingChargeDate: String?,
        rowsOldestFirst: List<LedgerRow>,
        closingBalance: String,
        asAtIso: String,
    ): List<AgingBucket> {
        if (Money.signOf(closingBalance) <= 0) return emptyList()

        // (dateIso, remaining amount) charges, oldest first; plus a pool of unapplied payments.
        val charges = ArrayList<Pair<String, String>>()
        var payments = "0"

        fun applyDelta(dateIso: String?, delta: String) {
            when {
                Money.signOf(delta) > 0 -> if (dateIso != null) charges.add(dateIso to Money.abs(delta))
                Money.signOf(delta) < 0 -> payments = Money.add(payments, Money.abs(delta))
            }
        }

        // The opening balance is the oldest charge (or a starting credit), dated the day before the
        // period. Seeding it is what makes the buckets sum to the closing balance.
        applyDelta(openingChargeDate, openingBalance)

        var prevBalance = openingBalance
        for (row in rowsOldestFirst) {
            applyDelta(row.date, Money.signedSubtract(row.balance, prevBalance))
            prevBalance = row.balance
        }

        // Settle payments against charges, oldest first, splitting a charge that is only part-paid.
        val outstanding = ArrayList<Pair<String, String>>()
        for ((dateIso, amount) in charges) {
            var remaining = amount
            if (Money.signOf(payments) > 0) {
                if (Money.compare(payments, remaining) >= 0) {
                    payments = Money.subtract(payments, remaining)
                    remaining = "0"
                } else {
                    remaining = Money.subtract(remaining, payments)
                    payments = "0"
                }
            }
            if (Money.signOf(remaining) > 0) outstanding.add(dateIso to remaining)
        }

        var b0to30 = "0"; var b31to60 = "0"; var b61to90 = "0"; var b90plus = "0"
        for ((dateIso, amount) in outstanding) {
            val age = CalendarDate.daysBetween(dateIso, asAtIso) ?: 0L
            when {
                age <= 30 -> b0to30 = Money.add(b0to30, amount)
                age <= 60 -> b31to60 = Money.add(b31to60, amount)
                age <= 90 -> b61to90 = Money.add(b61to90, amount)
                else -> b90plus = Money.add(b90plus, amount)
            }
        }
        return listOf(
            AgingBucket(LABEL_0_30, Money.round2(b0to30)),
            AgingBucket(LABEL_31_60, Money.round2(b31to60)),
            AgingBucket(LABEL_61_90, Money.round2(b61to90)),
            AgingBucket(LABEL_90_PLUS, Money.round2(b90plus)),
        )
    }

    companion object {
        /** getStatement page size for the concatenation loop. */
        const val PAGE_LIMIT = 100

        /** Hard cap so an unbounded range can't build an unbounded document. */
        const val MAX_ROWS = 2000

        const val LABEL_0_30 = "0–30 days"
        const val LABEL_31_60 = "31–60 days"
        const val LABEL_61_90 = "61–90 days"
        const val LABEL_90_PLUS = "90+ days"
    }
}
