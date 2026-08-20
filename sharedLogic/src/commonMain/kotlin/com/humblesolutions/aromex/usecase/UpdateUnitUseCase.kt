package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.SerialEdits
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository
import com.humblesolutions.aromex.util.Imei
import com.humblesolutions.aromex.util.Money

/**
 * Corrects a unit's details (MANAGE on `inventory`). Validates only the fields being
 * changed; a changed IMEI re-keys the `imeiIndex` in the repository transaction.
 */
class UpdateUnitUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(session: UserSession, serialId: String, edits: SerialEdits) {
        requireInventoryManage(session)
        require(serialId.isNotBlank()) { "serialId is required" }
        edits.cost?.let { require(Money.isValidPositiveDecimal(it)) { "Unit cost must be a positive decimal" } }
        edits.imei?.let { require(Imei.isValid(it)) { "Invalid IMEI: $it" } }
        edits.location?.let { require(it.attributeId.isNotBlank()) { "Unit location is required" } }
        repository.updateSerial(serialId, edits)
    }
}
