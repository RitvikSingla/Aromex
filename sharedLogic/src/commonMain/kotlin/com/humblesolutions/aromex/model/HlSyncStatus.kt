package com.humblesolutions.aromex.model

/**
 * Where an entity's operational record stands relative to its Humble Ledger
 * customer. The client writes [PENDING] on save; the server-side Cloud Function
 * (ticket #27) creates the HL customer and flips it to [SYNCED], or [FAILED] on
 * error (then retried by the reconcile sweep).
 *
 * Wire form (Firestore) is the enum name verbatim, UPPERCASE.
 */
enum class HlSyncStatus {
    PENDING,
    SYNCED,
    FAILED;

    companion object {
        /** Parses a stored status; unknown/missing defaults to [PENDING] (safe: it will be reconciled). */
        fun fromWire(value: String): HlSyncStatus =
            entries.firstOrNull { it.name == value.trim() } ?: PENDING
    }
}
