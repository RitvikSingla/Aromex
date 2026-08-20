package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.CommissionInput
import com.humblesolutions.aromex.model.BusinessDate
import com.humblesolutions.aromex.model.PurchaseInput
import com.humblesolutions.aromex.model.StockBatchGroup
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.InventoryRepository
import com.humblesolutions.aromex.util.Money

/**
 * Saves a completed Add-Inventory batch (ticket #58): its stock **and** its single
 * purchase record, **atomically** — the inventory write and the `purchases/{id}` doc land
 * together or not at all (one Firestore transaction, delegated to
 * [InventoryRepository.addStockBatchWithPurchase]). This is the invariant the ticket
 * forbids breaking: there is never in-stock inventory without a purchase record (and thus
 * an HL posting). Gated on `inventory` MANAGE — the purchase is inseparable from adding
 * the stock it pays for.
 *
 * [totalCost] is summed from the batch's actual per-unit costs across every SKU (one HL
 * entry per batch, not per unit). The one hard rule: `cash + bank` may not exceed that
 * total — enforced here as the safety net behind the dialog's inline validation.
 */
class RecordInventoryPurchaseUseCase(
    private val repository: InventoryRepository,
) {
    suspend fun execute(
        session: UserSession,
        groups: List<StockBatchGroup>,
        partyEntityId: String,
        cashPaid: String,
        bankPaid: String,
        commissions: List<CommissionInput> = emptyList(),
        /** The business date — when the stock was actually bought; the caller passes today's
         *  date for a normal intake. [now] is the clock, injected so this stays testable. */
        purchaseDate: Long,
        now: Long,
    ) {
        requireInventoryManage(session)
        require(groups.isNotEmpty()) { "At least one SKU group is required" }
        groups.forEach { group ->
            require(group.skuKey.isNotBlank()) { "skuKey is required" }
            // Selling price is optional (ticket #101); validate only when one was supplied.
            group.product?.let {
                if (it.defaultSellingPrice.isNotBlank()) {
                    require(Money.isValidPositiveDecimal(it.defaultSellingPrice)) {
                        "Default selling price must be a positive decimal"
                    }
                }
            }
        }
        // Validates every unit and rejects a duplicate IMEI across the whole batch.
        validateUnitsBatch(groups.flatMap { it.units })

        require(partyEntityId.isNotBlank()) { "A party is required" }
        // Re-checked here, not just in the picker: a future-dated purchase would credit a supplier
        // in a period that hasn't happened, and this is the only gate Desktop passes through.
        require(BusinessDate.isValid(purchaseDate, now)) { "The purchase date can't be in the future" }
        val cash = cashPaid.blankToZero()
        val bank = bankPaid.blankToZero()
        require(cash.isValidAmount()) { "Cash paid must be a valid amount" }
        require(bank.isValidAmount()) { "Bank paid must be a valid amount" }

        val total = Money.sum(groups.flatMap { it.units }.map { it.cost })
        val paid = Money.add(cash, bank)
        require(Money.lessThanOrEqual(paid, total)) {
            "Paid amount cannot exceed the batch total"
        }

        // Commission lines the user confirmed at intake: each must owe a positive amount to a
        // real payee (skipped lines never reach here). The obligation rides the same transaction
        // as the stock — never stock without its commission obligation (ticket #97). A give-now
        // split (cash/bank) may not exceed the amount owed — you can't give more than you owe.
        commissions.forEach { c ->
            require(c.payeeEntityId.isNotBlank()) { "A commission payee is required" }
            require(c.locationAttributeId.isNotBlank()) { "A commission location is required" }
            require(Money.isValidPositiveDecimal(c.amount)) { "Commission amount must be a positive decimal" }
            require(c.unitCount > 0) { "Commission unit count must be positive" }
            val paidCash = c.paidCash.blankToZero()
            val paidBank = c.paidBank.blankToZero()
            require(paidCash.isValidAmount()) { "Commission cash given must be a valid amount" }
            require(paidBank.isValidAmount()) { "Commission bank given must be a valid amount" }
            require(Money.lessThanOrEqual(Money.add(paidCash, paidBank), c.amount)) {
                "Commission given now cannot exceed the amount owed"
            }
        }

        // Normalize each give-now split to "0" so a blank field never reaches Firestore/HL.
        val normalizedCommissions = commissions.map {
            it.copy(paidCash = it.paidCash.blankToZero(), paidBank = it.paidBank.blankToZero())
        }

        repository.addStockBatchWithPurchase(
            groups,
            PurchaseInput(
                partyEntityId = partyEntityId,
                totalCost = total,
                cashPaid = cash,
                bankPaid = bank,
                purchaseDate = purchaseDate,
            ),
            normalizedCommissions,
        )
    }

    private fun String.blankToZero(): String = trim().ifEmpty { "0" }

    /** A valid, non-negative money amount: zero or a positive decimal. */
    private fun String.isValidAmount(): Boolean =
        Money.isZero(this) || Money.isValidPositiveDecimal(this)
}
