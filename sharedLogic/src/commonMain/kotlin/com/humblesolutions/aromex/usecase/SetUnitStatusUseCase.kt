package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.SerialStatus
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository

/**
 * Sets a unit's stock status (MANAGE on `inventory`). Leaving stock (SOLD) releases the
 * unit's `imeiIndex` entry in the repository transaction; returning to stock re-claims it.
 */
class SetUnitStatusUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(session: UserSession, serialId: String, status: SerialStatus) {
        requireInventoryManage(session)
        require(serialId.isNotBlank()) { "serialId is required" }
        repository.setSerialStatus(serialId, status)
    }
}
