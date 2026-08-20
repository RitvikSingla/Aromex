package com.humblesolutions.aromex.model

/**
 * The condition of a physical unit ([Serial]). v1 is intentionally coarse
 * (New / Used) — detailed grading (A/B/C, battery %) is deferred (Brief #41).
 *
 * Wire form (Firestore) is the enum name verbatim, UPPERCASE.
 */
enum class Condition {
    NEW,
    USED;

    companion object {
        /** Parses a stored value; returns null for anything unrecognised. */
        fun fromWire(value: String): Condition? =
            entries.firstOrNull { it.name == value.trim() }
    }
}
