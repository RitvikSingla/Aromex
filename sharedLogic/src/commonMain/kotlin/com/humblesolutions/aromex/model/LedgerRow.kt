package com.humblesolutions.aromex.model

/**
 * One line of a party's statement (ticket #91), straight from HL's `GET /api/v1/ledger`.
 *
 * [balance] is **HL's** running balance after this row — deliberately not recomputed on the client.
 * A client-side accumulator gets page 2 wrong (it has no idea what page 1 summed to) and gets any
 * date-filtered view wrong (it has no opening balance), and the moment it disagrees with the ledger
 * nobody can say which number is right in front of a customer.
 *
 * @property debit decimal string when this row debited the account, else null. Exactly one of
 *   [debit]/[credit] is set.
 * @property postingType HL's classification (`SALE`, `PAYMENT`, `PAYOUT`, `JOURNAL`, `REVERSAL`…),
 *   kept raw here and turned into a human label at the UI edge.
 * @property sourceId the Aromex idempotency key HL stored with the transaction (`sale_<id>:sale`,
 *   `sale_<id>:payment_cash`, `money_<id>`…), or null when it couldn't be resolved. HL's `/ledger`
 *   doesn't return it, so it's joined on from `/transactions`; everything downstream must treat a
 *   null as "unknown", never as "unrelated". This is what pairs a sale's charge with the cash taken
 *   for it — see [com.humblesolutions.aromex.model.StatementEvent].
 */
data class LedgerRow(
    val date: String,
    val description: String,
    val postingType: String,
    val debit: String?,
    val credit: String?,
    val balance: String,
    val transactionId: String,
    val sourceId: String? = null,
    /**
     * When the posting was actually recorded, as an ISO instant — HL's `transaction.createdAt`,
     * joined on from `/transactions` alongside [sourceId], or null when unresolved.
     *
     * Not the same thing as [date]: HL stores [date] as a **calendar date** (`@db.Date`) because
     * that is the accounting date, so it carries no time of day at all. This is the clock. For a
     * backdated entry the two fall on different days on purpose — the sale happened in June, it was
     * typed in August — so a caller must never present this as the time-of-day of [date].
     */
    val recordedAt: String? = null,
) {
    /** True for HL's mirror of an earlier transaction. Shown, never filtered — a reversal and its
     *  original together *are* the audit trail. */
    val isReversal: Boolean get() = postingType.equals(POSTING_REVERSAL, ignoreCase = true)

    companion object {
        const val POSTING_REVERSAL = "REVERSAL"
    }
}

/**
 * A page of a party's statement plus the account it belongs to (ticket #91).
 *
 * @property closingBalance the account's balance at the end of the range — HL's number, authoritative.
 * @property hasMore true when another page exists; drives paging without the caller doing arithmetic
 *   on [total].
 */
data class AccountStatement(
    val accountId: String,
    val accountName: String,
    val closingBalance: String,
    val rows: List<LedgerRow>,
    val page: Int,
    val totalRows: Int,
    val hasMore: Boolean,
)
