package com.humblesolutions.aromex.model

/**
 * One physical unit — a single phone identified by its [imei], belonging to a SKU via
 * [productId] (the whole bridge to Sales: a sale line references product + this unit).
 *
 * [cost] is **per-unit** (a decimal String — drives profit-per-device later).
 * [location] and [condition] are per-unit (the same SKU can sit in different places
 * and be New/Used). [status] is the stock lifecycle; [isActive] is a separate
 * soft-archive flag (a correction), never conflated with SOLD. [saleId] is set when
 * the unit is sold.
 *
 * [purchaseId] ties the unit back to the Add-Inventory batch that brought it in (the
 * `purchases/{id}` doc). Without it a batch reversal cannot know which phones to pull, so it is
 * written at intake and never changed afterwards. Null on units added before Stock History
 * shipped, and on the two direct add paths that book no purchase.
 */
data class Serial(
    val serialId: String = "",
    val productId: String = "",
    val imei: String = "",
    val cost: String = "0",
    val condition: Condition = Condition.NEW,
    val status: SerialStatus = SerialStatus.IN_STOCK,
    val location: AttributeRef = AttributeRef(),
    val isActive: Boolean = true,
    val saleId: String? = null,
    val purchaseId: String? = null,
)
