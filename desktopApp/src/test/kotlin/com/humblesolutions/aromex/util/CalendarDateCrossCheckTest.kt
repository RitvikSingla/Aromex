package com.humblesolutions.aromex.util

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Cross-checks [CalendarDate] against `java.time.LocalDate` (ticket #109).
 *
 * `CalendarDate` hand-rolls proleptic-Gregorian date math because `kotlinx-datetime` isn't on the
 * shared classpath. Hand-rolled calendar math is exactly the kind of code that looks right and is
 * wrong on a leap day or a century boundary — and here it decides which aging bucket a debt falls
 * into, so an off-by-one silently misstates a customer's overdue balance.
 *
 * The JDK's implementation is the reference. These tests live in `desktopApp` (a JVM target)
 * because `java.time` isn't available in `commonMain`; the code under test is shared, so a failure
 * here is a failure on every platform.
 */
class CalendarDateCrossCheckTest {

    /** Wide sweep: 1970 → 2100 inclusive, every single day, both directions. */
    @Test
    fun `epoch day round-trips against java time for every day 1970 to 2100`() {
        var date = LocalDate.of(1970, 1, 1)
        val end = LocalDate.of(2100, 12, 31)
        var checked = 0
        while (!date.isAfter(end)) {
            val iso = date.toString()

            assertEquals(date.toEpochDay(), CalendarDate.toEpochDay(iso), "toEpochDay($iso)")
            assertEquals(iso, CalendarDate.fromEpochDay(date.toEpochDay()), "fromEpochDay($iso)")

            date = date.plusDays(1)
            checked++
        }
        // Guards against the loop silently not running.
        assertEquals(47_847, checked, "days covered")
    }

    /** Leap-year rules, including the century exception 1900 (not leap) vs 2000 (leap). */
    @Test
    fun `leap days and century rules match java time`() {
        // 2000 is a leap year (÷400); 1900 and 2100 are not (÷100, not ÷400).
        listOf("2000-02-29", "2024-02-29", "1996-02-29", "2400-02-29").forEach { leapDay ->
            assertEquals(
                LocalDate.parse(leapDay).toEpochDay(),
                CalendarDate.toEpochDay(leapDay),
                "leap day $leapDay",
            )
        }

        // The day after 28 Feb in a non-leap century year is 1 Mar, not 29 Feb.
        listOf(1900, 2100, 2200, 2300).forEach { year ->
            val feb28 = LocalDate.of(year, 2, 28)
            assertEquals(
                feb28.plusDays(1).toString(),
                CalendarDate.fromEpochDay(feb28.toEpochDay() + 1),
                "day after $year-02-28",
            )
        }

        // …and in a leap year it *is* 29 Feb.
        listOf(2000, 2024, 2028).forEach { year ->
            val feb28 = LocalDate.of(year, 2, 28)
            assertEquals(
                "$year-02-29",
                CalendarDate.fromEpochDay(feb28.toEpochDay() + 1),
                "day after $year-02-28",
            )
        }
    }

    /** `dayBefore` is what seeds the opening balance, so month/year boundaries must be exact. */
    @Test
    fun `dayBefore matches java time across every boundary in a leap year and a normal year`() {
        listOf(2024, 2026).forEach { year ->
            var date = LocalDate.of(year, 1, 1)
            val end = LocalDate.of(year, 12, 31)
            while (!date.isAfter(end)) {
                assertEquals(
                    date.minusDays(1).toString(),
                    CalendarDate.dayBefore(date.toString()),
                    "dayBefore($date)",
                )
                date = date.plusDays(1)
            }
        }

        // Explicit spot checks on the awkward ones.
        assertEquals("2025-12-31", CalendarDate.dayBefore("2026-01-01"))
        assertEquals("2024-02-29", CalendarDate.dayBefore("2024-03-01"))
        assertEquals("2026-02-28", CalendarDate.dayBefore("2026-03-01"))
    }

    /** `daysBetween` decides the aging bucket — cross-check the arithmetic and its sign. */
    @Test
    fun `daysBetween matches ChronoUnit including negative and zero spans`() {
        val samples = listOf(
            "2026-01-01" to "2026-01-01",   // zero
            "2026-01-01" to "2026-01-31",   // within a month
            "2026-01-31" to "2026-03-01",   // across a short month
            "2024-01-31" to "2024-03-01",   // across a leap February
            "2025-12-15" to "2026-02-14",   // across a year boundary
            "2026-03-01" to "2026-01-31",   // negative
            "2020-06-30" to "2026-06-30",   // multi-year
        )
        samples.forEach { (from, to) ->
            val expected = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to))
            assertEquals(expected, CalendarDate.daysBetween(from, to), "daysBetween($from, $to)")
        }

        // The bucket edges themselves: 30/60/90 days must land where the statement says they do.
        listOf(30L, 60L, 90L).forEach { n ->
            val to = LocalDate.of(2026, 6, 30)
            val from = to.minusDays(n)
            assertEquals(n, CalendarDate.daysBetween(from.toString(), to.toString()), "$n-day span")
        }
    }

    /**
     * A full ISO instant must read as its calendar date, and malformed input must return null
     * rather than a plausible wrong date — the statement drops a row it can't date, and silently
     * misdating one would be worse.
     */
    @Test
    fun `parses ISO instants and rejects malformed input`() {
        // HL stamps rows as full instants; only the leading date is meaningful here.
        assertEquals(
            LocalDate.of(2026, 7, 28).toEpochDay(),
            CalendarDate.toEpochDay("2026-07-28T00:00:00.000Z"),
        )
        assertEquals(
            LocalDate.of(2026, 7, 28).toEpochDay(),
            CalendarDate.toEpochDay("2026-07-28T23:59:59.999-07:00"),
        )

        listOf("", "2026-07", "not-a-date", "2026/07/28", "20260728", "2026-13-01", "2026-07-32")
            .forEach { bad -> assertNull(CalendarDate.toEpochDay(bad), "should reject '$bad'") }

        assertNull(CalendarDate.dayBefore("garbage"))
        assertNull(CalendarDate.daysBetween("2026-01-01", "garbage"))

        // formatDisplay is the one place that degrades rather than fails — it echoes bad input.
        assertEquals("12 Feb 2026", CalendarDate.formatDisplay("2026-02-12"))
        assertEquals("1 Jan 2026", CalendarDate.formatDisplay("2026-01-01T12:00:00Z"))
        assertEquals("garbage", CalendarDate.formatDisplay("garbage"))
    }
}
