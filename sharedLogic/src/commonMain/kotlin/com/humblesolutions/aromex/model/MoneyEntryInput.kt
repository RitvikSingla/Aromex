package com.humblesolutions.aromex.model

/**
 * What the cashier filled in on the money-movement form (ticket #90), before it becomes a
 * [MoneyEntry] with an id and a sync state.
 *
 * @property amount decimal string, positive. Validated by `RecordMoneyEntryUseCase`.
 * @property entryDate the accounting date — epoch millis, may be backdated.
 */
data class MoneyEntryInput(
    val from: MoneyAccountRef,
    val to: MoneyAccountRef,
    val amount: String,
    val note: String? = null,
    val entryDate: Long,
)
