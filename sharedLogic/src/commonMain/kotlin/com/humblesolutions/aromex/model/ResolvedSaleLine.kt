package com.humblesolutions.aromex.model

/**
 * The per-unit snapshot for an [SaleLineInput.InventoryLineInput] (ticket #61), supplied
 * by the caller (a ViewModel already holds it from the cached inventory) so the sale
 * transaction does **not** re-read products for display data — it reads serials only for
 * the race-safe stock check.
 *
 * Keyed by [serialId]. [cost] is the unit's actual per-unit cost, snapshotted onto the
 * sale line and summed into cost-of-goods (the unit is about to leave stock, so its cost
 * must be frozen now). All money is a decimal **string**.
 */
data class ResolvedSaleLine(
    val serialId: String,
    val imei: String,
    val label: String,
    val listPrice: String,
    val cost: String,
)
