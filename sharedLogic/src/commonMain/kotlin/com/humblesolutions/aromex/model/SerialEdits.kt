package com.humblesolutions.aromex.model

/**
 * The editable fields of a unit ("correct details"). Every field is nullable — only
 * the non-null ones are applied. Changing [imei] re-keys the `imeiIndex` (release old
 * + claim new) in the platform transaction.
 */
data class SerialEdits(
    val cost: String? = null,
    val condition: Condition? = null,
    val location: AttributeRef? = null,
    val imei: String? = null,
)
