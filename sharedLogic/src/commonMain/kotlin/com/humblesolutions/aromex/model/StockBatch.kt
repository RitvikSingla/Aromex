package com.humblesolutions.aromex.model

import com.humblesolutions.aromex.util.Money

/** Where a batch stands: still on the books, or reversed out of them. */
enum class StockBatchStatus {
    ACTIVE,
    REVERSED;

    companion object {
        /** Unknown/missing reads as [ACTIVE] — a batch is on the books until something says otherwise. */
        fun fromWire(value: String?): StockBatchStatus =
            entries.firstOrNull { it.name == value?.trim() } ?: ACTIVE
    }
}

/**
 * How a requested reversal is going. Cloud-Function owned, exactly like the sale-void trail.
 *
 * [PENDING] is the window where the request has been made but the ledger hasn't moved yet — the
 * row must not offer Reverse again during it, or a slow function would take two reversals.
 */
enum class BatchReversalStatus {
    PENDING,
    DONE,
    FAILED;

    companion object {
        fun fromWire(value: String?): BatchReversalStatus? =
            entries.firstOrNull { it.name == value?.trim() }
    }
}

/**
 * One Add-Inventory batch as the Stock History screen shows it — a `purchases/{id}` doc, which is
 * written per submission rather than per unit.
 *
 * A batch is the unit of *reversal* because it is the unit of *posting*: one Humble Ledger
 * transaction covers the whole batch, and HL reverses a transaction whole. Undoing a single phone
 * from a batch would leave the posted purchase amount describing stock that no longer exists.
 */
data class StockBatch(
    val purchaseId: String,
    val partyEntityId: String,
    /** Batch total = Σ per-unit cost. Decimal string. */
    val totalCost: String,
    val cashPaid: String,
    val bankPaid: String,
    /**
     * How many units the batch created, recorded at intake. Denormalized deliberately: deriving it
     * by counting serials would report a shrinking batch as units sell, and "how many phones did
     * this purchase bring in" is a fact about the past.
     */
    val unitCount: Int,
    val createdAt: Long,
    val createdBy: String = "",
    val createdByName: String? = null,
    val syncStatus: HlSyncStatus = HlSyncStatus.PENDING,
    val status: StockBatchStatus = StockBatchStatus.ACTIVE,
    val reversalStatus: BatchReversalStatus? = null,
    val reversalReason: String? = null,
    val reversedAt: Long? = null,
    val reversedByName: String? = null,
    val reversalError: String? = null,
) {
    /** Paid at intake, across both methods. */
    val paid: String get() = Money.add(cashPaid.orZero(), bankPaid.orZero())

    /** What the batch left owing to the party — the amount that moved onto their balance. */
    val balanceAdded: String get() = Money.subtract(totalCost.orZero(), paid)

    val isReversed: Boolean get() = status == StockBatchStatus.REVERSED

    /** A reversal is in flight — the row must not offer Reverse again until it settles. */
    val isReversing: Boolean get() = reversalStatus == BatchReversalStatus.PENDING

    /** The last reversal attempt failed; the batch is still on the books and can be retried. */
    val reversalFailed: Boolean get() = !isReversed && reversalStatus == BatchReversalStatus.FAILED

    private fun String.orZero(): String =
        trim().let { if (Money.isValidUnsignedDecimal(it)) it else "0" }
}

/**
 * Why a batch cannot be reversed. Each case carries what the user needs to act on it, because
 * a greyed-out button with no reason is the thing people file bugs about.
 */
sealed interface BatchReversalBlock {

    /** Already reversed — nothing left to undo. */
    data object AlreadyReversed : BatchReversalBlock

    /** A reversal is already running. */
    data object InFlight : BatchReversalBlock

    /**
     * The batch predates unit tagging (`serials.purchaseId`), so which phones it brought in is
     * unknowable. Reversing would have to guess at stock to remove — so it doesn't.
     */
    data object UnitsUnknown : BatchReversalBlock

    /** Some units have left stock. Their sales must be voided first — HL reverses whole transactions. */
    data class UnitsSold(val imeis: List<String>) : BatchReversalBlock

    /** Some units were archived individually, so the batch is no longer whole. */
    data class UnitsRemoved(val imeis: List<String>) : BatchReversalBlock
}

/**
 * Whether [batch] can be reversed given [units] — every serial that carries its `purchaseId`,
 * whatever their status.
 *
 * Pure, so the screen's greyed-out state and the reason it shows come from the same rule the
 * Cloud Function re-checks server-side. Null means it can go ahead.
 */
fun batchReversalBlock(batch: StockBatch, units: List<Serial>): BatchReversalBlock? = when {
    batch.isReversed -> BatchReversalBlock.AlreadyReversed
    batch.isReversing -> BatchReversalBlock.InFlight
    // A batch that recorded units but has none tagged is a pre-tagging batch, not an empty one.
    units.isEmpty() && batch.unitCount > 0 -> BatchReversalBlock.UnitsUnknown
    else -> {
        val sold = units.filter { it.status != SerialStatus.IN_STOCK }.map { it.imei }
        val removed = units.filter { it.status == SerialStatus.IN_STOCK && !it.isActive }.map { it.imei }
        when {
            sold.isNotEmpty() -> BatchReversalBlock.UnitsSold(sold)
            removed.isNotEmpty() -> BatchReversalBlock.UnitsRemoved(removed)
            // Fewer tagged units than the batch recorded: part of it is unaccounted for, and
            // reversing would pull back less stock than the ledger is about to un-book.
            units.size < batch.unitCount -> BatchReversalBlock.UnitsUnknown
            else -> null
        }
    }
}
