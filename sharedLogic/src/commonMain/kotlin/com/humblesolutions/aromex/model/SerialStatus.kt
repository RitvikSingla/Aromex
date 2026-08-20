package com.humblesolutions.aromex.model

/**
 * The stock lifecycle of a physical unit ([Serial]). Kept **separate from** the
 * unit's `isActive` soft-archive flag, so "sold" (a real business outcome) is never
 * confused with "removed by mistake". Only in-stock units hold an `imeiIndex` entry;
 * leaving stock (SOLD) releases it (Brief #41 PO decisions #1/#2).
 *
 * Wire form (Firestore) is the enum name verbatim, UPPERCASE.
 */
enum class SerialStatus {
    IN_STOCK,
    RESERVED,
    SOLD;

    companion object {
        /** Parses a stored value; returns null for anything unrecognised. */
        fun fromWire(value: String): SerialStatus? =
            entries.firstOrNull { it.name == value.trim() }
    }
}
