package com.humblesolutions.aromex.model

/**
 * How a [Product]'s stock is tracked. **North star:** a phone is not a top-level
 * entity — it is a [Product] with `trackingMode = SERIALIZED` carrying a phone
 * attribute set. Only [SERIALIZED] is implemented in v1; the other modes exist so a
 * general POS (quantity/variant/service retail) retrofits without a rewrite.
 *
 * Wire form (Firestore) is the enum name verbatim, UPPERCASE.
 */
enum class TrackingMode {
    /** Each unit is individually identified by a serial (IMEI). The phone mode. */
    SERIALIZED,

    /** Tracked by an on-hand quantity, no per-unit serial. (Not implemented in v1.) */
    QUANTITY,

    /** A product with variants (size/material/…). (Not implemented in v1.) */
    VARIANT,

    /** A non-stock service line. (Not implemented in v1.) */
    SERVICE;

    companion object {
        /** Parses a stored value; returns null for anything unrecognised. */
        fun fromWire(value: String): TrackingMode? =
            entries.firstOrNull { it.name == value.trim() }
    }
}
