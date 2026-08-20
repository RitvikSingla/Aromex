package com.humblesolutions.aromex.model

/**
 * The roles a party can carry. **Non-binding labels** — they never constrain what
 * you can do with a party (you can always buy from a "customer" or sell to a
 * "supplier"). One party may hold several.
 *
 * The wire form (Firestore) is the enum name verbatim, UPPERCASE — no separate
 * mapping table, so the spelling is identical on every platform.
 */
enum class EntityRole {
    CUSTOMER,
    SUPPLIER,
    MIDDLEMAN;

    companion object {
        /** Parses a stored role string; returns null for anything unrecognised. */
        fun fromWire(value: String): EntityRole? =
            entries.firstOrNull { it.name == value.trim() }
    }
}
