package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository

/**
 * Soft-archives a unit (MANAGE on `inventory`) — a correction, not a sale. Releases the
 * unit's `imeiIndex` entry so its IMEI can be re-added later. Never a hard delete.
 */
class ArchiveUnitUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(session: UserSession, serialId: String) {
        requireInventoryManage(session)
        require(serialId.isNotBlank()) { "serialId is required" }
        repository.archiveSerial(serialId)
    }
}
