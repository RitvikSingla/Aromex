package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleRecord
import com.humblesolutions.aromex.model.SalesPage
import com.humblesolutions.aromex.model.SalesQuery
import kotlinx.coroutines.flow.Flow

/**
 * Persists a completed sale (ticket #61). The operational half of the Sales dual-write
 * spine — the HL posting is entirely the `onSaleWrite` Cloud Function's concern.
 *
 * Implemented per platform with the native Firestore SDK as **one atomic transaction**
 * (mirror of `InventoryRepository.addStockBatchWithPurchase`): every referenced serial is
 * re-checked in-stock, flipped to `SOLD` with its `saleId`, and its `imeiIndex/{imei}`
 * deleted, **and** the `sales/{id}` doc is created `PENDING` — all-or-nothing. So a unit is
 * never marked sold without its sale record, or vice-versa.
 */
interface SalesRepository {
    /**
     * Commits [record] and its stock changes in one transaction; returns the new `saleId`.
     *
     * @throws com.humblesolutions.aromex.model.AlreadySoldException if any referenced serial
     *   is no longer `IN_STOCK`/active at commit — nothing is written (the race loser).
     */
    suspend fun recordSale(record: SaleRecord): String

    /**
     * Observes the invoice on `sales/{saleId}` live (ticket #77) so the Sale-complete screen
     * resolves in place as the `onSaleWrite` Cloud Function issues the PDF in the background.
     * A real-time surface (like balances/scanner) — not the cached-list pattern. Emits the
     * current [SaleInvoice] on every change to the doc's `invoice*` fields.
     */
    fun observeSaleInvoice(saleId: String): Flow<SaleInvoice>

    /**
     * Requests an **immediate** re-issue of a `FAILED` invoice (ticket #77) instead of waiting
     * for the ~5-minute reconcile sweep — the cashier-facing Retry. It re-runs T1's existing,
     * idempotent issuance (same HL number → same PDF, never a duplicate); it does not build a
     * new invoice.
     *
     * Returns the invoice state known when the attempt is **under way**, which is not necessarily
     * the final one: a cold-Lambda render can outlast the call. Treat the return as an
     * acknowledgement and the live [observeSaleInvoice] stream as the source of truth for the UI.
     * Conversely, a thrown exception does **not** prove nothing happened — the call can fail or
     * time out while the backend goes on to issue the PDF — so a caller must never force a
     * `FAILED` state over an invoice the stream has already settled (see [SaleInvoice.hasSettled]).
     *
     * Platform note: on mobile this calls the `retryInvoice` callable Cloud Function (the client
     * may never write `invoice*` fields); on Desktop the Admin SDK bumps `invoiceRetryRequestedAt`
     * on the sale doc, which edge-triggers `onSaleWrite` — the Cloud Function, not the client, is
     * what flips the doc to `PENDING`. Because that Admin-SDK write succeeds whether or not any
     * function is listening, Desktop additionally waits for the doc to leave `FAILED` and reports
     * [com.humblesolutions.aromex.model.InvoiceRetryNotAcknowledgedException] if it never does.
     */
    suspend fun retryInvoice(saleId: String): SaleInvoice

    /**
     * Reads one page of past sales (ticket #83), newest-first, applying [query]'s filters as
     * Firestore queries and paging via its cursor — **never** a full-collection load (sales are
     * unbounded). Returns the rows plus the cursor for the next page (null at the end).
     *
     * The balance filter reads the denormalized `hasOutstandingBalance` boolean, not a string
     * comparison of `balanceRemaining`. IMEI is **not** handled here (it can't be queried on the
     * sale doc) — use [findSaleIdByImei] then [getSale]. Implemented per platform (Admin SDK on
     * Desktop); the permission gate lives in the use case, as Desktop bypasses Firestore rules.
     */
    suspend fun querySales(query: SalesQuery): SalesPage

    /**
     * Fetches the full record of a single sale (ticket #83) for the detail view — a direct
     * `sales/{saleId}` document read (no query). Returns null if the sale does not exist.
     */
    suspend fun getSale(saleId: String): SaleDetail?

    /**
     * Resolves an IMEI to the id of the sale that sold that unit (ticket #83), or null if no
     * sold unit carries it. Goes through the `serials` collection —
     * `serials where imei == [imei] → serial.saleId` — because the IMEI is buried in the sale's
     * `lines` array (not directly queryable) and `imeiIndex/{imei}` is deleted the moment a unit
     * sells (so it's gone for exactly the already-sold units this search targets).
     */
    suspend fun findSaleIdByImei(imei: String): String?

    /**
     * Voids a sale (ticket #85) — a **full reversal**, never a delete: HL cancels the invoice
     * (reversing money + tax + COGS in one call), refunds any amount paid split across the same
     * accounts the payment used, and the units go back to `IN_STOCK` with their `imeiIndex`
     * restored. The sale, invoice and ledger entries all survive; the sale is flipped `VOIDED`.
     *
     * All of that is the `voidSale` Cloud Function's concern — this call only *requests* the void
     * and reports when it settles. Admin-only and reason-required; both are enforced in
     * [com.humblesolutions.aromex.usecase.VoidSaleUseCase] (the real gate on Desktop, which
     * bypasses Firestore rules) and re-verified server-side by the CF (which reads
     * `users/{uid}.role`, since the client is never trusted).
     *
     * Platform note, mirroring [retryInvoice]: on mobile this calls the `voidSale` callable Cloud
     * Function (the client may never write the void fields directly); on Desktop the Admin SDK
     * writes the request fields + `voidStatus: PENDING` onto the sale doc, which edge-triggers
     * `onSaleWrite`, then waits for `voidStatus` to leave PENDING. The whole path is idempotent, so
     * a retry never double-reverses or double-refunds.
     *
     * @throws com.humblesolutions.aromex.model.SaleVoidFailedException if the CF reports the void
     *   FAILED (e.g. a re-used IMEI index), carrying the reason for the UI.
     */
    suspend fun voidSale(saleId: String, reason: String)
}
