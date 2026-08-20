package com.humblesolutions.aromex.model

/**
 * A sale's operational lifecycle (ticket #85). A sale is born [COMPLETED]; voiding it — a full
 * reversal of money, tax, cost and stock — flips it to [VOIDED]. There is no delete: an issued
 * invoice is a tax document and the ledger is append-only, so a void reverses rather than removes.
 *
 * Wire form (Firestore `status`) is the enum name verbatim, UPPERCASE.
 */
enum class SaleStatus {
    COMPLETED,
    VOIDED;

    val isVoided: Boolean get() = this == VOIDED

    companion object {
        /** Parses a stored `status`; unknown/missing defaults to [COMPLETED] (the v1-era value). */
        fun fromWire(value: String?): SaleStatus =
            entries.firstOrNull { it.name == value?.trim() } ?: COMPLETED
    }
}

/**
 * The dual-write spine of a void (ticket #85), mirroring `syncStatus`. The client (an admin) sets
 * [PENDING] alongside the void request fields; the `voidSale` Cloud Function owns every transition
 * after — HL cancel/reverse + split refunds + the atomic stock restore — flipping it to [DONE] or,
 * on a genuine failure, [FAILED] (then retried, since the whole path is idempotent).
 *
 * Wire form is the enum name verbatim, UPPERCASE; an absent field means "no void in flight" (null).
 */
enum class VoidStatus {
    PENDING,
    DONE,
    FAILED;

    companion object {
        /** Parses a stored `voidStatus`; absent/unknown → null ("no void requested"). */
        fun fromWire(value: String?): VoidStatus? =
            entries.firstOrNull { it.name == value?.trim() }
    }
}

/**
 * The client-side projection of a sale's void fields (ticket #85), read off `sales/{saleId}` for the
 * detail view. All CF-owned except [reason]/[requestedBy], which the requesting admin sets. Present
 * even on a never-voided sale (all-null [status]) so the UI can render unconditionally.
 *
 * @property status where the void stands ([VoidStatus]); null when no void was ever requested.
 * @property reason the admin's typed reason (required to void; passed through to HL's `cancelReason`).
 * @property voidedAtMillis when the CF finished the void (epoch millis, UTC); null until DONE.
 * @property error the last failure reason, for diagnostics — never shown raw to a user.
 */
data class SaleVoidState(
    val status: VoidStatus? = null,
    val reason: String? = null,
    val voidedAtMillis: Long? = null,
    val error: String? = null,
) {
    /** True while a void has been requested but the CF hasn't settled it yet. */
    val isInFlight: Boolean get() = status == VoidStatus.PENDING
}
