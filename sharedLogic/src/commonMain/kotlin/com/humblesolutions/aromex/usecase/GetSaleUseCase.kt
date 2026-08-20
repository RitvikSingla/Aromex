package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.SalesRepository

/**
 * Fetches one sale in full for the Sales History detail view (ticket #83), gated on `sales`
 * **VIEW** — the authoritative gate on Desktop, whose Admin-SDK access bypasses Firestore rules
 * (`CLAUDE.md`). A direct `sales/{saleId}` read; returns null if the sale doesn't exist.
 *
 * @throws PermissionDeniedException without `sales` VIEW.
 */
class GetSaleUseCase(
    private val repository: SalesRepository,
) {
    suspend fun execute(session: UserSession, saleId: String): SaleDetail? {
        if (session.permissions.sales == PermissionLevel.NONE) {
            throw PermissionDeniedException("sales")
        }
        require(saleId.isNotBlank()) { "saleId is required" }
        return repository.getSale(saleId)
    }
}
