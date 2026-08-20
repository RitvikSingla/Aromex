package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.util.Imei
import com.humblesolutions.aromex.util.Money

/**
 * Shared permission gates + input validation for the inventory use cases. Enforcement
 * lives in shared logic (PRD §7.2) — the only line of defense on Desktop, whose Admin
 * SDK bypasses Firestore rules.
 */

/** Requires MANAGE on `inventory` (create/edit/archive). */
internal fun requireInventoryManage(session: UserSession) {
    if (session.permissions.inventory != PermissionLevel.MANAGE) {
        throw PermissionDeniedException("inventory")
    }
}

/** Requires at least VIEW on `inventory` (read/observe). */
internal fun requireInventoryAccess(session: UserSession) {
    if (session.permissions.inventory == PermissionLevel.NONE) {
        throw PermissionDeniedException("inventory")
    }
}

/** Validates a single unit: a sane IMEI, a positive-decimal cost, a chosen location. */
internal fun validateUnit(unit: NewUnit) {
    require(Imei.isValid(unit.imei)) { "Invalid IMEI: ${unit.imei}" }
    require(Money.isValidPositiveDecimal(unit.cost)) { "Unit cost must be a positive decimal" }
    require(unit.location.attributeId.isNotBlank()) { "Unit location is required" }
}

/** Validates a batch of units and rejects a duplicate IMEI within the same batch. */
internal fun validateUnitsBatch(units: List<NewUnit>) {
    require(units.isNotEmpty()) { "At least one unit is required" }
    units.forEach { validateUnit(it) }
    val imeis = units.map { it.imei.trim() }
    require(imeis.size == imeis.toSet().size) { "Duplicate IMEI within the batch" }
}
