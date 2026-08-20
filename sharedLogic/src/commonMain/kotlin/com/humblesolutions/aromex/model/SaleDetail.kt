package com.humblesolutions.aromex.model

/**
 * The full, read-only projection of a `sales/{saleId}` doc (ticket #83), for the Sales
 * History detail view — every field the sale recorded, laid out to match the checkout
 * summary the cashier already knows. Distinct from [SaleRecord] (the *write* model): this is
 * what comes back off the wire, including the CF-owned sync/invoice fields the client never
 * sets. Reuses [SaleRecordLine]/[TaxLine]/[PaymentInput] so line/tax/payment shapes stay
 * identical across write and read. Money is a decimal **string** throughout.
 */
data class SaleDetail(
    val saleId: String,
    val createdAtMillis: Long,
    val createdBy: String,
    val customerEntityId: String,
    val isWalkIn: Boolean,
    val buyerName: String?,
    val buyerPhone: String?,
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
    val syncStatus: HlSyncStatus,
    /** The invoice projection (#76/#77), reused so the detail view can render `InvoiceRow`. */
    val invoice: SaleInvoice,
    /** Operational lifecycle (ticket #85): COMPLETED, or VOIDED once fully reversed. */
    val status: SaleStatus = SaleStatus.COMPLETED,
    /** The void spine + trail (ticket #85) — drives the VOIDED badge and gates the void action. */
    val voidState: SaleVoidState = SaleVoidState(),
) {
    val inventoryLines: List<SaleRecordLine.Inventory>
        get() = lines.filterIsInstance<SaleRecordLine.Inventory>()

    /** True once the sale has been fully reversed (ticket #85) — the detail renders read-only. */
    val isVoided: Boolean get() = status.isVoided
}
