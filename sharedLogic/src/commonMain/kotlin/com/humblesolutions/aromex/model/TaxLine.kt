package com.humblesolutions.aromex.model

/**
 * One computed tax leg on a sale (ticket #61) — snapshotted onto the `sales/{id}` doc so
 * the amount is frozen at sale time. [name] is `"GST"`, `"PST"`, or `"HST"`; the Cloud
 * Function maps that name to the matching HL `*Payable` account when posting. [rate] and
 * [amount] are decimal **strings**.
 */
data class TaxLine(
    val name: String,
    val rate: String,
    val amount: String,
)
