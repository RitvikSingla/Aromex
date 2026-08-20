package com.humblesolutions.aromex.ui.money

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression cover for the date picker's layout maths.
 *
 * The first version folded the opening week's offset into every row, so week 2 of July 2026 rendered
 * `4 5 6 7 8 9 10` instead of `6 7 8 9 10 11 12` — repeating days and leaving the 31st unreachable,
 * in the very control added so a cashier could backdate an entry.
 */
class MonthGridTest {

    /** July 2026 starts on a Wednesday → two blanks, 31 days. */
    @Test
    fun july2026_laysOutCorrectly() {
        val weeks = monthGridWeeks(firstOffset = 2, daysInMonth = 31)

        assertEquals(listOf(null, null, 1, 2, 3, 4, 5), weeks[0])
        assertEquals(listOf(6, 7, 8, 9, 10, 11, 12), weeks[1])   // the row that used to be wrong
        assertEquals(listOf(27, 28, 29, 30, 31, null, null), weeks.last())
    }

    /** Every day appears exactly once, in order — the property the old maths broke. */
    @Test
    fun everyDayAppearsExactlyOnce_forEveryShapeAMonthCanTake() {
        for (firstOffset in 0..6) {
            for (daysInMonth in listOf(28, 29, 30, 31)) {
                val days = monthGridWeeks(firstOffset, daysInMonth).flatten().filterNotNull()
                assertEquals(
                    (1..daysInMonth).toList(),
                    days,
                    "offset=$firstOffset days=$daysInMonth",
                )
            }
        }
    }

    /** Seven cells per row, always — otherwise the columns stop lining up with the day headers. */
    @Test
    fun everyWeekHasSevenCells() {
        for (firstOffset in 0..6) {
            for (daysInMonth in listOf(28, 29, 30, 31)) {
                monthGridWeeks(firstOffset, daysInMonth).forEach { assertEquals(7, it.size) }
            }
        }
    }

    /** A month starting on Monday has no leading blanks; February 2027 is exactly four rows. */
    @Test
    fun edgeShapes() {
        assertEquals(1, monthGridWeeks(0, 31).first().first())
        assertEquals(4, monthGridWeeks(0, 28).size)
        // The worst case — a 31-day month starting on Sunday — needs six rows.
        assertEquals(6, monthGridWeeks(6, 31).size)
        assertTrue(monthGridWeeks(6, 31).flatten().filterNotNull().contains(31))
    }
}
