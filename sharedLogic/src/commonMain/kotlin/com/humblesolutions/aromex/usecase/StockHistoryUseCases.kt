package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.BatchReversalBlock
import com.humblesolutions.aromex.model.Serial
import com.humblesolutions.aromex.model.StockBatch
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.model.batchReversalBlock
import com.humblesolutions.aromex.repository.StockHistoryRepository
import kotlinx.coroutines.flow.Flow

/** Reading the intake history needs `inventory` VIEW — the same bar as seeing the stock itself. */
class ObserveStockBatchesUseCase(
    private val repository: StockHistoryRepository,
) {
    fun execute(
        session: UserSession,
        limit: Int = PAGE_SIZE,
        from: Long? = null,
        to: Long? = null,
    ): Flow<List<StockBatch>> {
        requireInventoryAccess(session)
        return repository.observeBatches(limit, from, to)
    }

    companion object {
        /** One Load more's worth. Small enough to be quick, large enough to rarely need pressing. */
        const val PAGE_SIZE = 100
    }
}

/** The units one batch brought in, for the expanded row and the reversal pre-check. */
class LoadBatchUnitsUseCase(
    private val repository: StockHistoryRepository,
) {
    suspend fun execute(session: UserSession, purchaseId: String): List<Serial> {
        requireInventoryAccess(session)
        require(purchaseId.isNotBlank()) { "purchaseId is required" }
        return repository.loadBatchUnits(purchaseId)
    }
}

/**
 * Asks for a batch to be reversed (admin only).
 *
 * A reversal un-books a purchase: the party stops being owed for it, any money paid at intake
 * comes back, and the phones leave stock. That is the same weight as voiding a sale, so it takes
 * the same gate — `role == ADMIN` plus a written reason — and the Cloud Function re-checks both
 * server-side rather than trusting this call.
 *
 * The blocking rules are evaluated here too, from [units], so the screen can explain *why* a
 * batch can't be reversed instead of failing at the far end of a round trip. The function is
 * still the authority; this is the same rule applied early.
 */
class RequestStockBatchReversalUseCase(
    private val repository: StockHistoryRepository,
) {
    suspend fun execute(
        session: UserSession,
        batch: StockBatch,
        units: List<Serial>,
        reason: String,
    ) {
        requireAdmin(session)
        val trimmed = reason.trim()
        require(trimmed.isNotEmpty()) { "A reason is required" }

        when (val block = batchReversalBlock(batch, units)) {
            null -> Unit
            BatchReversalBlock.AlreadyReversed ->
                throw IllegalStateException("This batch has already been reversed")
            BatchReversalBlock.InFlight ->
                throw IllegalStateException("A reversal for this batch is already running")
            BatchReversalBlock.UnitsUnknown ->
                throw IllegalStateException("This batch's units can't be identified, so it can't be reversed")
            is BatchReversalBlock.UnitsSold ->
                throw IllegalStateException(
                    "${block.imeis.size} of ${batch.unitCount} units have left stock — void those sales first",
                )
            is BatchReversalBlock.UnitsRemoved ->
                throw IllegalStateException(
                    "${block.imeis.size} of ${batch.unitCount} units were already removed individually",
                )
        }

        repository.requestReversal(batch.purchaseId, trimmed, session.uid)
    }
}
