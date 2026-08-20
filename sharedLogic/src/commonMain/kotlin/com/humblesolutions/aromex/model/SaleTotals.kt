package com.humblesolutions.aromex.model

/**
 * The computed money totals for a sale (ticket #61), produced by `SaleCalculator` from a
 * cart. The single source of truth for the figures shown live at checkout **and** written
 * to the `sales/{id}` doc — so the number the cashier sees always equals the number the
 * books receive. All money is a decimal **string**.
 *
 * @property subtotal Σ line net prices (after per-item discounts, pre-tax).
 * @property taxableAmount `subtotal − saleDiscount` (the tax-exclusive base).
 * @property taxLines the computed tax legs (0–2), snapshotted with name + rate + amount.
 * @property taxTotal Σ [taxLines] amounts.
 * @property grandTotal `taxableAmount + taxTotal` (what the customer owes).
 * @property cogsTotal Σ inventory-line costs (cost-of-goods; `"0"` when no phones).
 */
data class SaleTotals(
    val subtotal: String,
    val taxableAmount: String,
    val taxLines: List<TaxLine>,
    val taxTotal: String,
    val grandTotal: String,
    val cogsTotal: String,
)
