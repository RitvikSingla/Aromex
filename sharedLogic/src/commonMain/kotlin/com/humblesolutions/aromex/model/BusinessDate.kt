package com.humblesolutions.aromex.model

/**
 * The **business date** of a record — the day it actually happened, which is not always the day
 * someone typed it in.
 *
 * Backdating exists so a shop can bring its old books into the app: last March's sale has to land
 * in last March, in the app, in the ledger and on the invoice, or the import is decoration.
 *
 * Stored as the doc's `createdAt`, with the real keystroke time kept separately as `enteredAt`.
 * That keeps one meaning per field — every list, filter, cursor and invoice already treats
 * `createdAt` as "when this happened", and for a same-day sale the two are the same instant.
 */
object BusinessDate {

    /** Milliseconds of slack for a clock that is a little ahead — not a licence to post forward. */
    private const val CLOCK_SKEW_MS = 5 * 60 * 1000L

    /**
     * True when [millis] is a usable business date: not in the future, and not absurdly old.
     *
     * A future-dated record would sit in a period that hasn't been closed and would report revenue
     * that hasn't happened. There is no honest reason for one, so it is refused rather than warned
     * about.
     */
    fun isValid(millis: Long, now: Long): Boolean =
        millis in EARLIEST_MS..(now + CLOCK_SKEW_MS)

    /** 1 Jan 2000. Anything before this is a mis-typed year, not history worth importing. */
    const val EARLIEST_MS = 946_684_800_000L
}
