package com.humblesolutions.aromex.model

/**
 * Thrown when confirming a sale whose serial is no longer sellable at commit time — its
 * `status` is not `IN_STOCK` (already `SOLD`/`RESERVED`) or it has been archived
 * (`isActive == false`). The race-safe, no-cart-lock guarantee (Brief #60): two cashiers
 * may hold the same unit in their carts, but the mark-sold transaction re-checks stock at
 * commit and the loser is rejected gracefully rather than double-selling.
 *
 * Declared here for the shared contract; it is thrown by the platform Firestore
 * **transaction** implementations, not from shared code. [imei] identifies the offending
 * unit so the UI can point at the right cart line.
 */
class AlreadySoldException(val imei: String) :
    RuntimeException("Unit already sold or unavailable: $imei")
