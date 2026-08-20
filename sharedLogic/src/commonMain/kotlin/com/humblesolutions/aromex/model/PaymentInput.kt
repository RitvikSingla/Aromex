package com.humblesolutions.aromex.model

/**
 * The payment taken on a sale (ticket #61), split across the three methods. All decimal
 * **strings** (`"0"` when a method is unused). A named customer's unpaid remainder becomes
 * their HL balance; a walk-in must pay in full.
 *
 * Card is recorded as its own field here, but posts to the HL **Bank** account (this
 * company uses only Cash and Bank — PO ruling, ticket #61); the split is preserved on the
 * operational `sales/{id}` doc for reporting.
 */
data class PaymentInput(
    val cash: String = "0",
    val card: String = "0",
    val bank: String = "0",
)
