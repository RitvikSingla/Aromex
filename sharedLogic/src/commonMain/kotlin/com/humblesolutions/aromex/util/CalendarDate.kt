package com.humblesolutions.aromex.util

/**
 * Pure calendar-date math on `yyyy-MM-dd` strings (ticket #109).
 *
 * `kotlinx-datetime` is not on the shared classpath, and a calendar *date* (no time, no zone) is
 * all the statement needs: the ledger row's date, the day before the period, and the age in days
 * at the `to` date. This computes epoch days with the proleptic-Gregorian formula (the same one
 * `java.time.LocalDate` uses) so aging is exact and testable on every platform.
 *
 * Inputs may be a bare `yyyy-MM-dd` or a full ISO instant (`2026-07-28T00:00:00.000Z`) — only the
 * leading 10 characters are read, which is the calendar date HL stamps on a ledger row.
 */
object CalendarDate {

    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /** (year, month 1-12, day 1-31) parsed from the leading `yyyy-MM-dd`, or null if malformed. */
    fun parse(value: String): Triple<Int, Int, Int>? {
        val s = value.trim()
        if (s.length < 10) return null
        val y = s.substring(0, 4).toIntOrNull() ?: return null
        if (s[4] != '-' || s[7] != '-') return null
        val m = s.substring(5, 7).toIntOrNull() ?: return null
        val d = s.substring(8, 10).toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return Triple(y, m, d)
    }

    /** Days since 1970-01-01 for the leading `yyyy-MM-dd`, or null if malformed. */
    fun toEpochDay(value: String): Long? {
        val (y, m, d) = parse(value) ?: return null
        return epochDay(y, m, d)
    }

    /** `yyyy-MM-dd` for the given epoch day. */
    fun fromEpochDay(epochDay: Long): String {
        var zeroDay = epochDay + DAYS_0000_TO_1970
        zeroDay -= 60 // adjust to 0000-03-01 so leap day is at the end of a four-year cycle
        var adjust = 0L
        if (zeroDay < 0) {
            val adjustCycles = (zeroDay + 1) / DAYS_PER_CYCLE - 1
            adjust = adjustCycles * 400
            zeroDay += -adjustCycles * DAYS_PER_CYCLE
        }
        var yearEst = (400 * zeroDay + 591) / DAYS_PER_CYCLE
        var doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
        if (doyEst < 0) {
            yearEst--
            doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400)
        }
        yearEst += adjust
        val marchDoy0 = doyEst.toInt()
        val marchMonth0 = (marchDoy0 * 5 + 2) / 153
        val month = (marchMonth0 + 2) % 12 + 1
        val dom = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1
        yearEst += (marchMonth0 / 10).toLong()
        return format2(yearEst.toInt(), month, dom)
    }

    /** The day before the given `yyyy-MM-dd`, as `yyyy-MM-dd`; null if malformed. */
    fun dayBefore(value: String): String? {
        val ed = toEpochDay(value) ?: return null
        return fromEpochDay(ed - 1)
    }

    /**
     * Whole days from [fromDate] to [toDate] (`to − from`), both `yyyy-MM-dd`; null if either is
     * malformed. Negative when [toDate] precedes [fromDate].
     */
    fun daysBetween(fromDate: String, toDate: String): Long? {
        val a = toEpochDay(fromDate) ?: return null
        val b = toEpochDay(toDate) ?: return null
        return b - a
    }

    /** `"2026-02-12"` → `"12 Feb 2026"`; returns the input unchanged if it can't be parsed. */
    fun formatDisplay(value: String): String {
        val (y, m, d) = parse(value) ?: return value
        return "$d ${MONTHS[m - 1]} $y"
    }

    private fun epochDay(year: Int, month: Int, day: Int): Long {
        val y = year.toLong()
        var total = 365 * y
        total += if (y >= 0) {
            (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400
        } else {
            -(y / -4 - y / -100 + y / -400)
        }
        total += (367 * month - 362) / 12
        total += (day - 1).toLong()
        if (month > 2) {
            total -= 1
            if (!isLeap(year)) total -= 1
        }
        return total - DAYS_0000_TO_1970
    }

    private fun isLeap(year: Int): Boolean =
        (year % 4 == 0) && (year % 100 != 0 || year % 400 == 0)

    private fun format2(y: Int, m: Int, d: Int): String {
        val mm = if (m < 10) "0$m" else "$m"
        val dd = if (d < 10) "0$d" else "$d"
        return "$y-$mm-$dd"
    }

    private const val DAYS_0000_TO_1970 = 719528L
    private const val DAYS_PER_CYCLE = 146097L
}
