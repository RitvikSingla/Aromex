package com.humblesolutions.aromex.repository

/**
 * Seam over the HL access-token broker. Use cases depend on this — they never
 * know that a Firebase ID token + gateway POST sits behind it.
 *
 * Implementations are expected to cache the token in memory for its full ~15-min
 * life and re-broker lazily (on expiry, or when [invalidate] is called after HL
 * rejects the token with 401).
 */
interface HlTokenProvider {
    /** Returns a non-expired HL access token, brokering a fresh one if needed. */
    suspend fun currentToken(): String

    /** Drops the cached token so the next [currentToken] call brokers a fresh one. */
    suspend fun invalidate()
}
