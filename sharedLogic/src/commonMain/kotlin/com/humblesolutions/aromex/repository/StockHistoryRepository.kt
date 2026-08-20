package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.StockBatch
import kotlinx.coroutines.flow.Flow

/**
 * Reads the Add-Inventory history (`purchases/{id}`) and asks for a batch to be reversed.
 *
 * **Requesting is not doing.** [requestReversal] only records the request on the doc; a Cloud
 * Function does the work — it re-checks the requester is an admin, reverses the Humble Ledger
 * legs, and pulls the stock, all in one idempotent worker. The client never reverses a ledger
 * entry itself: Desktop's Admin SDK bypasses Firestore rules, so a client-side reversal would be
 * a client-side promise about the books.
 */
interface StockHistoryRepository {

    /**
     * Batches newest first, live — a reversal settles under the user as the function finishes.
     *
     * [from]/[to] bound `createdAt` **in the query**, not after it. Filtering a fixed recent window
     * client-side meant picking last March returned nothing once the window had moved past it: the
     * rows were never fetched, and the screen said "no batches match" rather than "you can't see
     * that far back".
     *
     * [limit] is the window, grown by the caller's Load more. One live query rather than a live
     * head plus paged tails, so a batch's state is the same however deep the user has scrolled.
     */
    fun observeBatches(limit: Int, from: Long? = null, to: Long? = null): Flow<List<StockBatch>>

    /**
     * Every unit tagged with this batch, **whatever its status** — sold and archived ones
     * included, because those are exactly what blocks a reversal.
     *
     * A one-shot read, not a stream: the list only matters when a row is expanded or a reversal
     * is being considered, and a listener per row would mean one per batch on screen.
     */
    suspend fun loadBatchUnits(purchaseId: String): List<Serial>

    /** Records the reversal request. The Cloud Function picks it up and does the work. */
    suspend fun requestReversal(purchaseId: String, reason: String, requestedBy: String)
}
