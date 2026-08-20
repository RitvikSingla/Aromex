package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.AccountStatement
import com.humblesolutions.aromex.model.EntityBalance

/**
 * Reads party balances from Humble Ledger.
 *
 * Unlike [EntityRepository] (per-platform Firestore), this interface is backed by a
 * **single shared Ktor implementation** (the same HL REST client on Android, iOS,
 * and Desktop) — see `data/KtorEntityLedgerRepository`. Throws HlException on
 * transport, auth, or HL upstream failures.
 */
interface EntityLedgerRepository {
    /**
     * All party balances in one call, keyed by `externalId` (== the Aromex entity id).
     * Used to populate the list; join to the entities client-side.
     */
    suspend fun getBalances(): Map<String, EntityBalance>

    /** A single party's current balance (for the detail screen). Null if HL has no such customer yet. */
    suspend fun getBalance(externalId: String): EntityBalance?

    /**
     * A page of a party's statement (ticket #91): every ledger entry against them with HL's own
     * running balance. Null when HL has no customer for [externalId] yet.
     *
     * [from]/[to] are inclusive `yyyy-MM-dd` bounds; HL keeps the opening balance correct even when
     * the range is narrow, which is precisely why the running balance must come from HL rather than
     * being accumulated here.
     */
    suspend fun getStatement(
        externalId: String,
        from: String? = null,
        to: String? = null,
        page: Int = 1,
        limit: Int = 50,
    ): AccountStatement?

    /**
     * Releases any resources held (e.g. the HTTP client). Consumers that create a
     * repo per ViewModel MUST call this when the ViewModel is cleared (Android
     * `onCleared`, iOS `deinit`, Desktop dispose) — otherwise each screen leaks a
     * client. A no-op when the client was supplied by the caller.
     */
    fun close()
}
