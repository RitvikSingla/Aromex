package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.repository.ObserveSaleInvoiceException
import com.humblesolutions.aromex.repository.SalesRepository
import kotlinx.coroutines.flow.Flow

/**
 * Streams a sale's invoice state (ticket #77) so a ViewModel can resolve the Sale-complete
 * row in place. A thin pass-through — there is no client-side invoice logic (the CF owns
 * issuance); the use-case layer exists so the ViewModel depends only on shared interfaces
 * (`/kmp-arch`: never skip the use case, even for a simple read).
 */
class ObserveSaleInvoiceUseCase(
    private val repository: SalesRepository,
) {
    /**
     * Live invoice state for `sales/{saleId}`.
     *
     * @throws ObserveSaleInvoiceException when the underlying snapshot listener fails.
     *
     * The annotation is what makes that reachable on iOS: SKIE bridges an unannotated
     * `Flow`-returning function as a *non-throwing* `SkieSwiftFlow`, which gives a failing stream
     * nowhere to surface — the Swift `catch` around it is dead code (the compiler says so) and the
     * app can't degrade gracefully. Declaring the exception bridges a throwing flow instead, so
     * iOS handles a dropped listener the way Android/Desktop already do. Same reason
     * `ObserveInventoryUseCase`/`ObserveEntitiesUseCase` carry `@Throws`.
     */
    @Throws(ObserveSaleInvoiceException::class)
    fun execute(saleId: String): Flow<SaleInvoice> = repository.observeSaleInvoice(saleId)
}
