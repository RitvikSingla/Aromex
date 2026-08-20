package com.humblesolutions.aromex.model

/**
 * A denormalized pointer to a managed [AttributeValue] — its [attributeId] for
 * referential integrity/filtering plus its [name] cached for display so lists render
 * without a join. Used inside [Product.attributes] and [Serial.location].
 *
 * (The cached [name] can go stale on a vocabulary rename; a rename-backfill is a
 * PO-accepted later concern, Brief #41.)
 */
data class AttributeRef(
    val attributeId: String = "",
    val name: String = "",
)
