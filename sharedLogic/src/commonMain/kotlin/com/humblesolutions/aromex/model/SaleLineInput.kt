package com.humblesolutions.aromex.model

/**
 * One line of a sale cart (ticket #61). A generic sale line references a product + unit
 * (`INVENTORY`) or is a revenue-only ad-hoc line (`CUSTOM`, e.g. a case or a fee) — the
 * retrofittable shape from `CLAUDE.md` (a phone is a `SERIALIZED` product, not a
 * top-level entity). All money is a decimal **string**.
 *
 * The original [unitPrice] **and** [lineDiscount] are both kept so discounting is
 * reportable; the net price is derived (`unitPrice − lineDiscount`).
 */
sealed interface SaleLineInput {
    /** The price before any per-item discount (editable at checkout). */
    val unitPrice: String

    /** The per-item discount (`"0"` when none); kept alongside [unitPrice], never merged. */
    val lineDiscount: String

    /**
     * A phone (or other serialized unit) leaving stock. The unit's actual cost and display
     * details are supplied separately as a [ResolvedSaleLine] snapshot, so this write model
     * stays minimal and the transaction reads serials only for the race check.
     */
    data class InventoryLineInput(
        val productId: String,
        val serialId: String,
        override val unitPrice: String,
        override val lineDiscount: String = "0",
    ) : SaleLineInput

    /** A revenue-only line with no stock and no cost-of-goods (e.g. a case, a fee). */
    data class CustomLineInput(
        val name: String,
        override val unitPrice: String,
        override val lineDiscount: String = "0",
    ) : SaleLineInput
}
