package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.SalesRepository

/**
 * Voids a sale (ticket #85) — a full reversal of money, tax, cost and stock. Two hard gates live
 * here, the authoritative check on Desktop (whose Admin-SDK access bypasses Firestore rules,
 * `CLAUDE.md`) and re-verified server-side by the CF (which never trusts the client):
 *
 *  1. **Admin only.** Voiding needs `role == ADMIN` — *not* `sales: manage`. Reversing money and
 *     restocking a phone is a strictly higher privilege than ringing a sale, so a cashier who can
 *     record a sale still cannot undo one.
 *  2. **A reason is required.** No time limit — a mistake found late is still worth fixing — but the
 *     reason is mandatory and is carried into HL's `cancelReason` so the ledger and the app tell the
 *     same story months later.
 *
 * The reversal itself (HL cancel/reverse + split refunds + atomic stock restore) is the CF's
 * idempotent worker; this use case only validates and delegates, keeping the ViewModel depending on
 * shared interfaces only (`/kmp-arch`).
 */
class VoidSaleUseCase(
    private val repository: SalesRepository,
) {
    /**
     * @throws PermissionDeniedException if [session] is not an admin.
     * @throws IllegalArgumentException if [reason] is blank, or [saleId] is blank.
     * @throws com.humblesolutions.aromex.model.SaleVoidFailedException if the CF settles it FAILED.
     */
    suspend fun execute(session: UserSession, saleId: String, reason: String) {
        if (session.role != UserRole.ADMIN) {
            throw PermissionDeniedException("admin")
        }
        require(saleId.isNotBlank()) { "saleId is required" }
        val trimmed = reason.trim()
        require(trimmed.isNotEmpty()) { "A reason is required to void a sale" }
        repository.voidSale(saleId, trimmed)
    }
}
