package com.humblesolutions.aromex.model

/**
 * One row of the Sales History list (ticket #83) — the projection of a `sales/{saleId}` doc
 * that the paged list needs, and no more. Kept deliberately small so a page of these is cheap
 * to hold in memory; the full record is fetched into a [SaleDetail] only when a row is opened.
 *
 * Money is a decimal **string** everywhere (project rule). [createdAtMillis] is epoch millis
 * (UTC); the UI formats it in the shop's `timezone`. Customer/buyer are carried raw
 * ([customerEntityId], [isWalkIn], [buyerName]) so the ViewModel can resolve a display name
 * from its cached entities rather than the sale doc denormalizing it.
 */
data class SaleSummary(
    val saleId: String,
    val createdAtMillis: Long,
    val customerEntityId: String,
    val isWalkIn: Boolean,
    val buyerName: String?,
    /** Display label of the first line (e.g. "Apple iPhone 15 · 128 GB"); "" if the sale has none. */
    val firstItemLabel: String,
    /** Total line count, so the list can render "iPhone 15 +2 more". */
    val itemCount: Int,
    /** The first inventory line's IMEI, if any — a compact identifier shown in the row. */
    val firstImei: String?,
    /**
     * Every line's display label (ticket #83) — powers the **local** quick-search over already
     * loaded rows, so typing a phone/brand/model matches a sale by *any* of its items, not just
     * the first. Kept off the wire only in the sense that it's derived from the doc's `lines`.
     */
    val itemLabels: List<String> = emptyList(),
    /** Every inventory line's IMEI — for local IMEI substring matching over loaded rows. */
    val imeis: List<String> = emptyList(),
    val grandTotal: String,
    val amountPaid: String,
    val balanceRemaining: String,
    val syncStatus: HlSyncStatus,
    val invoiceNumber: String?,
    val invoiceStatus: SaleInvoiceStatus,
    /** Operational lifecycle (ticket #85): drives the VOIDED badge in the history list. */
    val status: SaleStatus = SaleStatus.COMPLETED,
)
