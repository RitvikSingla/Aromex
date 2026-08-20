package com.humblesolutions.aromex.ui.entities

import com.humblesolutions.aromex.model.StatementEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The time under a statement row's date.
 *
 * HL stores a posting's accounting date as a **calendar date** (`@db.Date`) — there is no time of
 * day on it — so the clock has to come from when the posting was recorded. For an entry made as it
 * happens those are the same moment. For a **backdated** one they are deliberately different: a
 * June sale typed in August. Pairing August's clock with June's date would print a moment that
 * never existed, which is the case these tests exist to prevent.
 */
class StatementTimeOfDayTest {

    private fun event(date: String, recordedAt: String?) = StatementEvent(
        date = date,
        description = "Sale (Aromex)",
        postingType = "SALE",
        billed = "918",
        cashIn = "918",
        cashOut = null,
        balance = "0",
        recordedAt = recordedAt,
        transactionIds = listOf("t1"),
    )

    private val vancouver = "America/Vancouver"

    /**
     * Case-insensitive on purpose: the pattern is `h:mm a`, whose AM/PM casing follows the JVM's
     * default format locale (this app renders "pm" here, and Sales History uses the same pattern).
     * The meaning — the hour, and which half of the day — is what must hold on any machine.
     */
    private fun assertTime(expected: String, actual: String?) =
        assertEquals(expected.lowercase(), actual?.lowercase())

    @Test
    fun `shows the shop's clock, not UTC`() {
        // 21:45 UTC on the 31st is 2:45 PM the same day in Vancouver (PDT, UTC−7).
        val e = event("2026-07-31", "2026-07-31T21:45:12.000Z")
        assertTime("2:45 PM", timeOfDay(e, vancouver))
    }

    /**
     * The instant is the 1st in UTC but still the 31st in the shop's zone — the row's own date. The
     * time belongs, and a naive UTC comparison would have dropped it.
     */
    @Test
    fun `an evening sale keeps its time across the UTC date boundary`() {
        val e = event("2026-07-31", "2026-08-01T02:30:00.000Z")
        assertTime("7:30 PM", timeOfDay(e, vancouver))
    }

    /** The case this rule exists for: a June sale entered in August has no June clock time. */
    @Test
    fun `a backdated entry shows no time at all`() {
        val e = event("2026-06-14", "2026-08-02T16:10:00.000Z")
        assertNull(timeOfDay(e, vancouver), "August's clock is not June's time of day")
    }

    @Test
    fun `no recorded instant means no time`() {
        assertNull(timeOfDay(event("2026-07-31", null), vancouver))
        assertNull(timeOfDay(event("2026-07-31", ""), vancouver))
    }

    /** A malformed instant is dropped rather than crashing a statement someone is reading. */
    @Test
    fun `an unparseable instant is ignored`() {
        assertNull(timeOfDay(event("2026-07-31", "not-a-timestamp"), vancouver))
    }

    /** An unknown zone id falls back to UTC instead of failing. */
    @Test
    fun `a bad timezone falls back to UTC`() {
        val e = event("2026-07-31", "2026-07-31T21:45:12.000Z")
        assertTime("9:45 PM", timeOfDay(e, "Not/AZone"))
    }

    /** Midnight prints as 12:00 AM, not 0:00. */
    @Test
    fun `midnight reads as twelve`() {
        val e = event("2026-07-31", "2026-07-31T07:00:00.000Z") // 00:00 PDT
        assertTime("12:00 AM", timeOfDay(e, vancouver))
    }
}
