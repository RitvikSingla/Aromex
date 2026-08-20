package com.humblesolutions.aromex.model

/**
 * A commission the system proposes at intake for one matching [CommissionRule] (ticket #97) —
 * what `CommissionCalculator` computes from a batch's units and the active rules, before the
 * user confirms/edits/skips it. Carries enough to show *how the figure was reached*
 * (`12 × $5.00`, `2% of $14,400.00`), never a bare total.
 *
 * One line per matching rule; a rule with no units at its location produces no line. The user
 * turns a kept line into a [CommissionInput] at save (accrue or pay-now, possibly overridden).
 *
 * @property unitCount units at this line's location.
 * @property basisAmount summed cost of those units (the base a percent rule multiplies;
 *   `"0"` and irrelevant for per-unit rules, but still surfaced for percent).
 * @property amount decimal **string** — the rule's computed figure (`count × rate` or
 *   `rate × basisAmount`).
 */
data class CommissionLine(
    val ruleId: String,
    val payeeEntityId: String,
    val locationAttributeId: String,
    val rateKind: RateKind,
    val rate: String,
    val unitCount: Int,
    val basisAmount: String,
    val amount: String,
)
