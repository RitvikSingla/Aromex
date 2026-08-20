package com.humblesolutions.aromex.model

/**
 * One physical unit a user supplies when adding stock: its [imei], per-unit [cost]
 * (decimal String), [condition], and storage [location].
 */
data class NewUnit(
    val imei: String,
    val cost: String,
    val condition: Condition,
    val location: AttributeRef,
)
