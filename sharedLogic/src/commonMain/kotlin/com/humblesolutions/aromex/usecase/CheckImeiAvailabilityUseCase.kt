package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository
import com.humblesolutions.aromex.util.Imei

/**
 * Advisory IMEI availability pre-check for the ✓ button on the Add-Inventory entry
 * screen. Validates format first, then delegates to the repository.
 *
 * Returns `true` when the IMEI is already held by an in-stock unit. **Not the
 * transactional authority** — a race between this check and the confirm-time write is
 * still caught by [com.humblesolutions.aromex.model.DuplicateImeiException].
 */
class CheckImeiAvailabilityUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(session: UserSession, imei: String): Boolean {
        requireInventoryAccess(session)
        require(Imei.isValid(imei)) { "Invalid IMEI: $imei" }
        return repository.isImeiInStock(imei.trim())
    }
}
