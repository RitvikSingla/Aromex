package com.humblesolutions.aromex.util

/**
 * Serial/IMEI validation helper. The value is stored verbatim as a **Firestore document id**
 * (`imeiIndex/{imei}`) — that doc is what makes the in-stock uniqueness guard race-safe — so
 * validation here is **document-id safety**, not a phone-specific length/format check. Aromex
 * carries more than phones, and real serials are not 15 digits, so any well-formed identifier
 * that Firestore can key on is accepted.
 *
 * Rejected (a `/`-bearing paste would otherwise fail the write with an error pointing nowhere
 * near the cause):
 * - blank after trimming;
 * - any `/` (path separator);
 * - exactly `.` or `..`;
 * - anything matching `__.*__` (Firestore reserves double-underscore ids);
 * - longer than 64 characters (Firestore's real limit is 1500 bytes — nothing legitimate is near).
 *
 * Callers must **trim before both validating and storing** so the stored value and the doc id
 * can never diverge.
 */
object Imei {
    private const val MAX_LENGTH = 64
    private val RESERVED = Regex("^__.*__$")

    /** True when [value] (trimmed) is safe to use as a Firestore document id. */
    fun isValid(value: String): Boolean {
        val v = value.trim()
        return v.isNotEmpty() &&
            !v.contains('/') &&
            v != "." &&
            v != ".." &&
            !RESERVED.matches(v) &&
            v.length <= MAX_LENGTH
    }
}
