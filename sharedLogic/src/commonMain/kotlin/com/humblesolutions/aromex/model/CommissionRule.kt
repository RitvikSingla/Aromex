package com.humblesolutions.aromex.model

/**
 * A standing arrangement to pay a party per phone brought into a location (ticket #97):
 * *"whenever phones land at Shop A, Rajesh gets $5 each."* Admin-managed settings — a rule
 * silently creates money owed on every future intake, so only an admin may write it (the
 * use cases gate on `role == ADMIN`; the cashier still confirms each commission at intake).
 *
 * Several rules may target one location; every active rule fires independently at intake.
 * Switching a rule off ([isActive] = false) stops future matches but never touches
 * commission already earned — what's owed is owed.
 *
 * @property locationAttributeId an `AttributeType.LOCATION` attribute value's id.
 * @property payeeEntityId the Firestore entity id of the party who earns it (its HL customer
 *   id is resolved server-side when the commission posts).
 * @property rate decimal **string** — a per-unit amount (`"5.00"`) or a percent-of-cost
 *   fraction (`"0.02"` for 2%), read according to [rateKind]. Never floating point.
 */
data class CommissionRule(
    val ruleId: String,
    val locationAttributeId: String,
    val payeeEntityId: String,
    val rateKind: RateKind,
    val rate: String,
    val isActive: Boolean = true,
    val createdBy: String? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
)

/**
 * What an admin filled in on the commission-rule form (ticket #97), before it becomes a
 * [CommissionRule] with an id. A blank [ruleId] is a create; a non-blank one edits that rule
 * in place (the same doc, so its earned commissions keep pointing at a live rule).
 */
data class CommissionRuleInput(
    val ruleId: String = "",
    val locationAttributeId: String,
    val payeeEntityId: String,
    val rateKind: RateKind,
    val rate: String,
    val isActive: Boolean = true,
)
