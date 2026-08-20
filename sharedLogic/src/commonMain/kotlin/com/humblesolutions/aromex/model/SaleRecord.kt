package com.humblesolutions.aromex.model

/**
 * A fully-resolved, validated, ready-to-persist sale (ticket #61) — the output of
 * `RecordSaleUseCase` and the input to [com.humblesolutions.aromex.repository.SalesRepository].
 * All money math (via `SaleCalculator`) and all snapshotting already happened in the use
 * case, so each platform's transaction impl only maps this to a `sales/{id}` Firestore doc
 * and flips the referenced serials — no business logic in the repository. All money is a
 * decimal **string**.
 */
data class SaleRecord(
    val customerEntityId: String,
    val isWalkIn: Boolean,
    val lines: List<SaleRecordLine>,
    val subtotal: String,
    val saleDiscount: String,
    val taxableAmount: String,
    val taxLines: List<TaxLine>,
    val taxTotal: String,
    val grandTotal: String,
    val cogsTotal: String,
    val payment: PaymentInput,
    val amountPaid: String,
    val balanceRemaining: String,
    val note: String?,
    /** The author's Firebase uid (audit + HL `actorRef`). */
    val createdBy: String,
    /**
     * Optional walk-in buyer capture (ticket #77): the name/phone the cashier typed for an
     * anonymous buyer, persisted on the sale so the CF can use it as the invoice Bill-To.
     * Null for a named customer (the Entity's own details are used) or when left blank.
     */
    val buyerName: String? = null,
    val buyerPhone: String? = null,
    /** The business date — stored as the doc's `createdAt`. See [BusinessDate]. */
    val saleDate: Long = 0L,
    /**
     * True when this sale was priced tax-**inclusive** (ticket #106) — the cashier typed all-in
     * prices and the tax was backed out. Snapshotted so a later report can say which way each sale
     * was priced; absent/false on every pre-#106 sale, which is correct (they were all exclusive).
     */
    val taxInclusive: Boolean = false,
    /**
     * The buyer's tax/GST number snapshotted from their [Entity] at record time (ticket #106) — so
     * an already-issued invoice keeps the number it was rendered with even if the contact is edited
     * later. Null for a walk-in or a customer without one; the invoice line simply doesn't appear.
     */
    val buyerTaxNumber: String? = null,
) {
    /** The in-stock units this sale marks sold — read by the transaction for the race check. */
    val inventoryLines: List<SaleRecordLine.Inventory>
        get() = lines.filterIsInstance<SaleRecordLine.Inventory>()
}

/**
 * One persisted sale line (ticket #61) — the resolved form of a [SaleLineInput], with the
 * derived [netPrice] and (for inventory) the unit snapshots frozen in.
 */
sealed interface SaleRecordLine {
    val unitPrice: String
    val lineDiscount: String

    /** `unitPrice − lineDiscount`, computed in the use case. */
    val netPrice: String

    data class Inventory(
        val productId: String,
        val serialId: String,
        val imei: String,
        val label: String,
        val listPrice: String,
        override val unitPrice: String,
        override val lineDiscount: String,
        override val netPrice: String,
        /** The unit's actual per-unit cost, summed into cost-of-goods. */
        val cost: String,
    ) : SaleRecordLine

    data class Custom(
        val name: String,
        override val unitPrice: String,
        override val lineDiscount: String,
        override val netPrice: String,
    ) : SaleRecordLine
}
