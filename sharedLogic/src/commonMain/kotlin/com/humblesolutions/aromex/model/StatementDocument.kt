package com.humblesolutions.aromex.model

/**
 * A party's statement, fully assembled and ready to render as a PDF (ticket #109).
 *
 * This is the output of [com.humblesolutions.aromex.usecase.BuildPartyStatementUseCase] — every
 * row in the range (not one page), the opening balance HL computed for the day before the period,
 * the period totals, and the aging of the *outstanding balance* (never of pending invoices).
 *
 * All money is a **decimal string** with no currency symbol and no thousands separators — the Bill
 * Engine prefixes the `$` and formats, so the summary and the row amounts render consistently. An
 * empty string for [StatementLine.debit]/[credit] renders an empty cell, not `$0.00`.
 *
 * Dates are **display strings** (e.g. `"12 Feb 2026"`), already formatted: a calendar date is
 * timezone-independent, so it is formatted here rather than in the Cloud Function (which only needs
 * the shop's timezone for the *issue* instant).
 *
 * @property periodFrom the start of the range, or null for an all-time statement (opening `0.00`).
 * @property agingBuckets oldest last; **empty** when the party owes nothing (in credit or settled),
 *   so the PDF omits the aging block entirely rather than printing four zeros. When non-empty, the
 *   four bucket amounts sum to [closingBalance] exactly — the invariant the FIFO bucketing exists
 *   to guarantee.
 */
data class StatementDocument(
    val periodFrom: String?,
    val periodTo: String,
    val openingBalance: String,
    val totalDebits: String,
    val totalCredits: String,
    val closingBalance: String,
    val rows: List<StatementLine>,
    val agingBuckets: List<AgingBucket>,
)

/**
 * One line of the rendered statement — one **business event**, not one ledger leg.
 *
 * A sale and the cash taken for it are a single line: [moneyIn]/[moneyOut] carry what actually
 * changed hands, and [billed] carries what the sale was worth. Printing the charge and its payment
 * as two lines is how the books are kept, not how a customer reads a statement — see
 * [StatementEvent].
 *
 * [moneyIn], [moneyOut] and [billed] are decimal strings; `""` renders an empty cell rather than
 * `$0.00`. At most one of [moneyIn]/[moneyOut] is non-empty. [note] is null unless the "Include
 * notes" toggle was on *and* a matching app record carried a note.
 */
data class StatementLine(
    val date: String,
    val description: String,
    val note: String?,
    /** What the sale/purchase was worth; `""` for an event that only moved money. */
    val billed: String,
    /**
     * The event's posting type (`SALE`, `PURCHASE`, `PAYMENT`…). The renderer names [billed] from
     * it — a purchase on a supplier's statement must not print "Sale of $2,000".
     */
    val kind: String,
    val moneyIn: String,
    val moneyOut: String,
    val balance: String,
)

/** One aging bucket, e.g. `("31–60 days", "1000.00")`. */
data class AgingBucket(
    val label: String,
    val amount: String,
)
