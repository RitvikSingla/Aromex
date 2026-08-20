package com.humblesolutions.aromex.model

/**
 * The invoice a sale carries (ticket #77 surfaces what #76 writes). Read-only on the
 * client: the `onSaleWrite` Cloud Function is the sole author of the `invoice*` fields on
 * `sales/{saleId}` (Firestore rules forbid the client from ever setting them). This is the
 * client-side projection of those fields, observed live so the Sale-complete screen resolves
 * in place as issuance finishes in the background.
 *
 * @property status where issuance is: [SaleInvoiceStatus.PENDING] while it runs (or absent),
 *   [SaleInvoiceStatus.ISSUED] once the PDF exists, [SaleInvoiceStatus.FAILED] on a render error.
 * @property number the invoice number Humble Ledger minted (present once known).
 * @property url the public, permanent PDF URL (present only when [status] is ISSUED).
 * @property error the last render-failure reason, for diagnostics — never shown raw to a cashier.
 */
data class SaleInvoice(
    val status: SaleInvoiceStatus = SaleInvoiceStatus.PENDING,
    val number: String? = null,
    val url: String? = null,
    val error: String? = null,
) {
    /**
     * True once issuance has reached a good terminal state — a PDF exists and can be opened.
     *
     * Guards the Retry error paths: a retry call failing on the client does **not** prove the
     * server did nothing (the call can time out while the Cloud Function runs on to completion),
     * so a handler must never force [SaleInvoiceStatus.FAILED] over an invoice the live stream
     * has already settled. Doing so would hide a finished PDF behind the retry UI with no write
     * left to come and correct it. Also treats a bare `url` as settled — belt and braces, since
     * a URL only ever appears with ISSUED.
     */
    val hasSettled: Boolean
        get() = status == SaleInvoiceStatus.ISSUED || url != null
}

/** Lifecycle of a sale's PDF invoice (ticket #76 CF-owned; ticket #77 renders it). */
enum class SaleInvoiceStatus {
    PENDING,
    ISSUED,
    FAILED;

    companion object {
        /**
         * Maps the raw Firestore `invoiceStatus` string to an enum. An absent/unknown value is
         * [PENDING] — issuance hasn't reached this doc yet, so "preparing" is the honest state
         * (never treat a missing field as a failure).
         */
        fun fromRaw(raw: String?): SaleInvoiceStatus = when (raw) {
            "ISSUED" -> ISSUED
            "FAILED" -> FAILED
            else -> PENDING
        }
    }
}
