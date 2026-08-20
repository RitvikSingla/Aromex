package com.humblesolutions.aromex.model

/**
 * The fields a user supplies when creating or editing a party. Separate from the
 * [Entity] read model because they diverge — e.g. [opening] is only meaningful on
 * creation, and the HL sync fields are owned by the server, not the client.
 */
data class EntityInput(
    val name: String,
    val phones: List<String>,
    val email: String? = null,
    val address: String? = null,
    val roles: Set<EntityRole> = emptySet(),
    val notes: String? = null,
    /** Optional tax/GST number (ticket #106); printed on this party's invoices when they buy. */
    val taxNumber: String? = null,
    /** Optional starting balance; only applied on create. */
    val opening: OpeningBalance? = null,
)
