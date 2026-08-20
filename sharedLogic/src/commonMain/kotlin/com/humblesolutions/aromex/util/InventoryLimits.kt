package com.humblesolutions.aromex.util

/**
 * Client-side limits for the Add-Inventory write (ticket #53).
 *
 * A Firestore transaction is capped at **~500 document writes**. Adding one serialized
 * unit costs ~2 writes (the unit doc + its stock/index doc) plus ~1 per distinct new SKU
 * product doc, so a paste of a few hundred phones can blow the cap and the whole
 * transaction would be rejected. We therefore **cap the batch** (ticket's chosen path:
 * warn + ask to split, no chunking) at a conservative ceiling that leaves headroom for
 * the product docs.
 */
object InventoryLimits {
    /** Max units confirmable in a single Add-Inventory transaction (safe under the ~500-write cap). */
    const val SAFE_BATCH_CEILING = 180

    /** True when [unitCount] exceeds [SAFE_BATCH_CEILING] and the user must split the batch. */
    fun exceedsBatchCap(unitCount: Int): Boolean = unitCount > SAFE_BATCH_CEILING
}
