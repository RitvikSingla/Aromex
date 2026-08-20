package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.CommissionLine
import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.util.Money

/**
 * Pure batch-and-rules → per-payee commission lines (ticket #97). Given the units a cashier
 * is adding and the active [CommissionRule]s, it proposes one [CommissionLine] per rule that
 * matches units in the batch. The rule proposes; the person saving still decides — this only
 * computes the figures the intake dialog shows.
 *
 * **Grouped by location first.** A batch can span two locations; each rule sees only its own
 * location's count and cost, so there is no cross-contamination. A rule whose location has no
 * units in the batch produces no line.
 *
 * **Money stays strings.** Per-unit is `Money.multiplyRate(rate, count)`; percent is
 * `Money.multiplyRate(summedCost, rate)`. Both are general half-up decimal multiplication —
 * no floating-point maths. A location's units costed at zero earn zero percent-commission,
 * which is correct (the dialog still surfaces the computed figure so it's never a surprise).
 */
object CommissionCalculator {

    /**
     * @param units the batch's units (each carries its [NewUnit.cost] and [NewUnit.location]).
     * @param activeRules the rules to apply — pass only `isActive == true` rules (a switched-off
     *   rule must never fire). Order of the returned lines follows [activeRules].
     */
    fun compute(units: List<NewUnit>, activeRules: List<CommissionRule>): List<CommissionLine> {
        if (units.isEmpty() || activeRules.isEmpty()) return emptyList()

        // Group once by location so each rule reads only its location's units.
        val byLocation: Map<String, List<NewUnit>> = units.groupBy { it.location.attributeId }

        return activeRules.mapNotNull { rule ->
            val locationUnits = byLocation[rule.locationAttributeId] ?: return@mapNotNull null
            if (locationUnits.isEmpty()) return@mapNotNull null

            val count = locationUnits.size
            val summedCost = Money.sum(locationUnits.map { it.cost })
            val amount = when (rule.rateKind) {
                RateKind.PER_UNIT -> Money.multiplyRate(rule.rate, count.toString())
                RateKind.PERCENT_OF_COST -> Money.multiplyRate(summedCost, rule.rate)
            }
            CommissionLine(
                ruleId = rule.ruleId,
                payeeEntityId = rule.payeeEntityId,
                locationAttributeId = rule.locationAttributeId,
                rateKind = rule.rateKind,
                rate = rule.rate,
                unitCount = count,
                basisAmount = summedCost,
                amount = amount,
            )
        }
    }
}
