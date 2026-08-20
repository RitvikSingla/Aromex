package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.AttributeRef
import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.usecase.CommissionCalculator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Locations used across the calculator tests. */
private val SHOP_A = AttributeRef("loc-a", "Shop A")
private val SHOP_B = AttributeRef("loc-b", "Shop B")

private fun unitAt(location: AttributeRef, cost: String): NewUnit =
    NewUnit(imei = "355000000000000", cost = cost, condition = com.humblesolutions.aromex.model.Condition.NEW, location = location)

private fun perUnitRule(id: String, location: String, payee: String, rate: String) =
    CommissionRule(id, location, payee, RateKind.PER_UNIT, rate)

private fun percentRule(id: String, location: String, payee: String, fraction: String) =
    CommissionRule(id, location, payee, RateKind.PERCENT_OF_COST, fraction)

class CommissionCalculatorTest {

    /** AC3: a per-unit rule computes count × rate. 12 phones at Shop A × $5.00 = $60.00. */
    @Test
    fun perUnit_countTimesRate() {
        val units = List(12) { unitAt(SHOP_A, "1200.00") }
        val lines = CommissionCalculator.compute(units, listOf(perUnitRule("r1", "loc-a", "rajesh", "5.00")))
        val line = lines.single()
        assertEquals(12, line.unitCount)
        assertEquals("60.00", line.amount)
        assertEquals("rajesh", line.payeeEntityId)
    }

    /** AC3: a percent rule computes percent × summed cost. 2% of $14,400.00 = $288.00. */
    @Test
    fun percent_fractionTimesSummedCost() {
        val units = List(12) { unitAt(SHOP_A, "1200.00") } // Σ = 14,400.00
        val lines = CommissionCalculator.compute(units, listOf(percentRule("r1", "loc-a", "priya", "0.02")))
        val line = lines.single()
        assertEquals("14400.00", line.basisAmount)
        assertEquals("288.00", line.amount)
    }

    /** AC2: two active rules on one location both fire, independently. */
    @Test
    fun twoRulesOneLocation_bothFire() {
        val units = List(12) { unitAt(SHOP_A, "1200.00") }
        val lines = CommissionCalculator.compute(
            units,
            listOf(
                perUnitRule("r1", "loc-a", "rajesh", "5.00"),
                percentRule("r2", "loc-a", "priya", "0.02"),
            ),
        )
        assertEquals(2, lines.size)
        assertEquals("60.00", lines.first { it.ruleId == "r1" }.amount)
        assertEquals("288.00", lines.first { it.ruleId == "r2" }.amount)
    }

    /** AC4: a batch spanning two locations produces the right lines per location, no cross-contamination. */
    @Test
    fun twoLocations_noCrossContamination() {
        val units = List(12) { unitAt(SHOP_A, "1200.00") } + List(3) { unitAt(SHOP_B, "500.00") }
        val lines = CommissionCalculator.compute(
            units,
            listOf(
                perUnitRule("rA", "loc-a", "rajesh", "5.00"),   // only Shop A's 12
                perUnitRule("rB", "loc-b", "sam", "10.00"),     // only Shop B's 3
                percentRule("rBpct", "loc-b", "sam", "0.10"),   // 10% of Shop B's Σ 1,500
            ),
        )
        assertEquals("60.00", lines.first { it.ruleId == "rA" }.amount)      // 12 × 5
        assertEquals(12, lines.first { it.ruleId == "rA" }.unitCount)
        assertEquals("30.00", lines.first { it.ruleId == "rB" }.amount)      // 3 × 10
        assertEquals(3, lines.first { it.ruleId == "rB" }.unitCount)
        assertEquals("1500.00", lines.first { it.ruleId == "rBpct" }.basisAmount)
        assertEquals("150.00", lines.first { it.ruleId == "rBpct" }.amount)  // 10% of 1,500
    }

    /** A percent rule over zero-cost units earns zero — correct, not a bug. */
    @Test
    fun percent_overZeroCost_isZero() {
        val units = List(4) { unitAt(SHOP_A, "0") }
        val lines = CommissionCalculator.compute(units, listOf(percentRule("r1", "loc-a", "priya", "0.02")))
        assertEquals("0.00", lines.single().amount)
    }

    /** A rule whose location has no units in the batch produces no line. */
    @Test
    fun noMatchingLocation_producesNoLine() {
        val units = List(2) { unitAt(SHOP_A, "100.00") }
        val lines = CommissionCalculator.compute(units, listOf(perUnitRule("r1", "loc-b", "sam", "5.00")))
        assertTrue(lines.isEmpty())
    }

    /** No rules, or no units, yields nothing. */
    @Test
    fun emptyInputs_yieldNothing() {
        assertTrue(CommissionCalculator.compute(emptyList(), listOf(perUnitRule("r", "loc-a", "p", "5"))).isEmpty())
        assertTrue(CommissionCalculator.compute(List(1) { unitAt(SHOP_A, "1") }, emptyList()).isEmpty())
    }
}
