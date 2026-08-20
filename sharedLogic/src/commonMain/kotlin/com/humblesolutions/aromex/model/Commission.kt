package com.humblesolutions.aromex.model

/**
 * One payee's commission earned from one Add-Inventory batch (ticket #97) — the obligation
 * a matching [CommissionRule] creates the moment stock is added. Written **in the same
 * Firestore transaction as the stock and its purchase** (see
 * [com.humblesolutions.aromex.repository.InventoryRepository.addStockBatchWithPurchase]): the
 * #58 invariant "never stock without its purchase record" extends here to "never stock
 * without its commission obligation" — a second write that could fail alone would eventually
 * leave phones in the system and a forgotten debt.
 *
 * Same dual-write spine as purchases/moneyEntries: the client writes this doc
 * [HlSyncStatus.PENDING] and the `onCommissionWrite` Cloud Function posts it to HL — accrue
 * the full [amount] via `/customer-purchases` against a `Commission` expense account, then a
 * `/customer-payouts` per non-zero [paidCash]/[paidBank] (mirroring an inventory purchase) —
 * and flips it to [HlSyncStatus.SYNCED]. HL credentials never touch a device.
 *
 * @property ruleId the rule that proposed this line, or `null` when the amount was hand-edited
 *   (so a reviewer can tell it wasn't the rule's figure).
 * @property unitCount how many units at [locationAttributeId] this covers.
 * @property basisAmount the summed cost a percent rule applied to (`"0"` for per-unit rules).
 * @property amount decimal **string** — what's owed to the payee (always accrued to their balance).
 * @property paidCash decimal **string** — cash given to the payee now; `"0"` for none (accrue only).
 * @property paidBank decimal **string** — bank given to the payee now; `"0"` for none. `paidCash`
 *   + `paidBank` nets the payee's balance back down and may not exceed [amount].
 */
data class Commission(
    val commissionId: String,
    val payeeEntityId: String,
    val locationAttributeId: String,
    val ruleId: String? = null,
    val unitCount: Int,
    val basisAmount: String,
    val amount: String,
    val paidCash: String = "0",
    val paidBank: String = "0",
    val sourceBatchId: String,
    val createdBy: String? = null,
    val createdAt: Long? = null,
    val syncStatus: HlSyncStatus = HlSyncStatus.PENDING,
    val hlTransactionId: String? = null,
    val hlSyncError: String? = null,
)

/**
 * A commission line the user confirmed at intake, ready to persist alongside its batch
 * (ticket #97). Carries no id/sync state yet — those are assigned when the doc is written
 * in the batch transaction. [sourceBatchId] is set by the repository to the batch's purchase
 * id, so it need not be supplied here.
 *
 * @property ruleId the proposing rule, or `null` when [amount] was hand-edited.
 * @property amount decimal **string** — the confirmed figure (possibly overridden), always accrued.
 * @property basisAmount the cost a percent rule applied to; `"0"` for per-unit.
 * @property paidCash decimal **string** — cash given now (`"0"` when "add to balance").
 * @property paidBank decimal **string** — bank given now (`"0"` when "add to balance"). The sum
 *   `paidCash + paidBank` is what leaves the till now and may not exceed [amount].
 */
data class CommissionInput(
    val payeeEntityId: String,
    val locationAttributeId: String,
    val ruleId: String?,
    val unitCount: Int,
    val basisAmount: String,
    val amount: String,
    val paidCash: String = "0",
    val paidBank: String = "0",
)
