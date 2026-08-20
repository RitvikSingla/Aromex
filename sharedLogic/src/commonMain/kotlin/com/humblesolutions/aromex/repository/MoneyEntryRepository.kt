package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.MoneyEntry
import com.humblesolutions.aromex.model.MoneyEntryInput
import kotlinx.coroutines.flow.Flow

/**
 * Records and observes money movements (ticket #90) — `moneyEntries/{entryId}` in the per-client
 * Firebase project.
 *
 * **The client never posts to Humble Ledger.** Implementations write the Firestore doc as
 * `PENDING`; the `onMoneyEntryWrite` Cloud Function is the only thing that talks to HL, and it owns
 * `syncStatus` / `hl*` from then on. That is what keeps HL credentials off every device, and it is
 * also what makes a retry safe — the CF derives a deterministic `sourceId` from the entry id, so
 * replaying it can never post the money twice.
 *
 * Implementations are per-platform (`/kmp-arch`), mirroring `SalesRepository`.
 */
interface MoneyEntryRepository {

    /**
     * Creates one money movement as `PENDING` and returns its new `entryId`. Returning as soon as
     * the doc is committed — **not** when HL settles — is deliberate: the money is recorded the
     * moment Firestore accepts it, and [observeRecentEntries] carries the sync result. Blocking the
     * cashier on a round-trip to HL would make a recorded entry look lost whenever the ledger is slow.
     */
    suspend fun recordEntry(input: MoneyEntryInput): String

    /** The newest [limit] entries, newest first, live — so an entry's PENDING → SYNCED settles in place. */
    fun observeRecentEntries(limit: Int): Flow<List<MoneyEntry>>

    /**
     * Posts the mirror of [entryId] as a new entry, and links the two. Entries are immutable; this
     * is the only correction mechanism (same principle as voiding a sale). The reversal is itself a
     * `PENDING` entry that the Cloud Function settles against HL's `/transactions/{id}/reverse`.
     *
     * @return the new reversing entry's id.
     */
    suspend fun reverseEntry(entryId: String): String
}
