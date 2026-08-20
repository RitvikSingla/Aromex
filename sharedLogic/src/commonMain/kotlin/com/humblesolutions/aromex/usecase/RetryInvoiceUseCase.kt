package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.repository.SalesRepository

/**
 * Requests an immediate re-issue of a `FAILED` invoice (ticket #77) — the cashier-facing
 * Retry, so the PDF need not wait for the ~5-minute reconcile sweep. A thin pass-through to
 * [SalesRepository.retryInvoice]: re-issuance itself is T1's server-side, idempotent worker
 * (same HL number → same PDF), so there is no business logic to place here. The use case
 * keeps the ViewModel depending only on shared interfaces (`/kmp-arch`).
 */
class RetryInvoiceUseCase(
    private val repository: SalesRepository,
) {
    suspend fun execute(saleId: String): SaleInvoice = repository.retryInvoice(saleId)
}
