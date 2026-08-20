package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.BusinessDate
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.ResolvedSaleLine
import com.humblesolutions.aromex.model.SaleInput
import com.humblesolutions.aromex.model.SaleLineInput
import com.humblesolutions.aromex.model.SaleRecord
import com.humblesolutions.aromex.model.SaleRecordLine
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.SalesRepository
import com.humblesolutions.aromex.util.Money

/**
 * Records a completed sale (ticket #61): validates the cart, computes the money via the
 * shared [SaleCalculator], snapshots each unit's cost/details, and writes the sale + its
 * stock changes **atomically** through [SalesRepository.recordSale]. Gated on `sales`
 * MANAGE — the real permission gate on Desktop, whose Admin-SDK access bypasses Firestore
 * rules (`CLAUDE.md`).
 *
 * The one hard money rule beyond bounds-checking: a **walk-in must pay in full** — no
 * balance is ever carried on the anonymous party. A named customer may short-pay; the
 * remainder becomes their HL balance (posted by the Cloud Function).
 */
class RecordSaleUseCase(
    private val repository: SalesRepository,
) {
    /**
     * @param resolved per-inventory-line snapshots (imei/label/listPrice/cost), keyed by
     *   serialId — supplied by the caller from cached inventory. Every
     *   [SaleLineInput.InventoryLineInput] in [sale] must have a matching entry.
     * @param taxConfig the rate to charge. Defaults to [UserSession.tax], which is captured at
     *   sign-in and therefore goes stale the moment an admin changes it — a caller that observes
     *   `companySettings` live must pass the current value, or a rate changed at noon won't reach
     *   any till until its operator signs out and back in.
     * @return the new `saleId`.
     * @throws PermissionDeniedException without `sales` MANAGE.
     * @throws com.humblesolutions.aromex.model.AlreadySoldException if a unit lost the race.
     */
    suspend fun execute(
        session: UserSession,
        sale: SaleInput,
        resolved: List<ResolvedSaleLine>,
        taxConfig: TaxConfig = session.tax,
        /** The clock, injected so the future-date guard stays testable. */
        now: Long,
    ): String {
        if (session.permissions.sales != PermissionLevel.MANAGE) {
            throw PermissionDeniedException("sales")
        }
        require(sale.lines.isNotEmpty()) { "A sale needs at least one line" }
        require(sale.lines.size <= MAX_LINES) { "A sale cannot exceed $MAX_LINES lines" }
        require(sale.customerEntityId.isNotBlank()) { "A customer is required" }

        // A physical unit can appear at most once — else the customer is double-charged, COGS
        // is double-counted, and two lines reference one serial. The VM excludes cart units, but
        // this use case is the authoritative gate (Desktop bypasses Firestore rules).
        val serialIds = sale.lines.filterIsInstance<SaleLineInput.InventoryLineInput>().map { it.serialId }
        require(serialIds.size == serialIds.toSet().size) {
            "The same unit cannot be sold twice in one sale"
        }

        val bySerialId = resolved.associateBy { it.serialId }

        // Per-line validation + money-string checks.
        sale.lines.forEach { line ->
            require(line.unitPrice.isValidAmount()) { "Unit price must be a valid amount" }
            require(line.lineDiscount.isValidAmount()) { "Line discount must be a valid amount" }
            require(Money.lessThanOrEqual(line.lineDiscount, line.unitPrice)) {
                "Line discount cannot exceed the unit price"
            }
            when (line) {
                is SaleLineInput.InventoryLineInput -> {
                    require(line.productId.isNotBlank() && line.serialId.isNotBlank()) {
                        "Inventory line needs a product and serial"
                    }
                    requireNotNull(bySerialId[line.serialId]) {
                        "Missing resolved snapshot for serial ${line.serialId}"
                    }
                }
                is SaleLineInput.CustomLineInput ->
                    require(line.name.isNotBlank()) { "Custom line needs a name" }
            }
        }
        require(sale.saleDiscount.isValidAmount()) { "Sale discount must be a valid amount" }

        val payment = sale.payment
        require(payment.cash.isValidAmount()) { "Cash must be a valid amount" }
        require(payment.card.isValidAmount()) { "Card must be a valid amount" }
        require(payment.bank.isValidAmount()) { "Bank must be a valid amount" }

        val costBySerialId = resolved.associate { it.serialId to it.cost }
        // Re-checked here rather than trusted from the screen: a future-dated sale would report
        // revenue that hasn't happened, and this is the only gate Desktop passes through.
        require(BusinessDate.isValid(sale.saleDate, now)) { "The sale date can't be in the future" }

        val totals = SaleCalculator.compute(
            sale.lines, sale.saleDiscount, taxConfig, costBySerialId, sale.taxInclusive,
        )

        require(Money.lessThanOrEqual(sale.saleDiscount, totals.subtotal)) {
            "Sale discount cannot exceed the subtotal"
        }

        val amountPaid = Money.sum(listOf(payment.cash, payment.card, payment.bank))
        require(Money.lessThanOrEqual(amountPaid, totals.grandTotal)) {
            "Paid amount cannot exceed the sale total"
        }
        if (sale.isWalkIn) {
            require(Money.compare(amountPaid, totals.grandTotal) == 0) {
                "A walk-in sale must be paid in full"
            }
        }
        val balanceRemaining = Money.subtract(totals.grandTotal, amountPaid)

        val recordLines = sale.lines.map { line ->
            val netPrice = Money.subtract(line.unitPrice, line.lineDiscount)
            when (line) {
                is SaleLineInput.InventoryLineInput -> {
                    val snap = bySerialId.getValue(line.serialId)
                    SaleRecordLine.Inventory(
                        productId = line.productId,
                        serialId = line.serialId,
                        imei = snap.imei,
                        label = snap.label,
                        listPrice = snap.listPrice,
                        unitPrice = line.unitPrice,
                        lineDiscount = line.lineDiscount,
                        netPrice = netPrice,
                        cost = snap.cost,
                    )
                }
                is SaleLineInput.CustomLineInput -> SaleRecordLine.Custom(
                    name = line.name,
                    unitPrice = line.unitPrice,
                    lineDiscount = line.lineDiscount,
                    netPrice = netPrice,
                )
            }
        }

        return repository.recordSale(
            SaleRecord(
                saleDate = sale.saleDate,
                customerEntityId = sale.customerEntityId,
                isWalkIn = sale.isWalkIn,
                lines = recordLines,
                subtotal = totals.subtotal,
                saleDiscount = sale.saleDiscount.blankToZero(),
                taxableAmount = totals.taxableAmount,
                taxLines = totals.taxLines,
                taxTotal = totals.taxTotal,
                grandTotal = totals.grandTotal,
                cogsTotal = totals.cogsTotal,
                payment = payment,
                amountPaid = amountPaid,
                balanceRemaining = balanceRemaining,
                note = sale.note?.trim()?.ifEmpty { null },
                createdBy = session.uid,
                // Walk-in name is only meaningful for the anonymous party; a named customer's
                // Bill-To name comes from their Entity. Trim, blank → null (the CF falls back to
                // "Walk-in Customer").
                buyerName = if (sale.isWalkIn) sale.buyerName?.trim()?.ifEmpty { null } else null,
                // The buyer phone as entered at checkout — snapshotted for a named customer too (the
                // checkout prefills it from their Entity and lets it be edited per sale, mirroring
                // buyerTaxNumber), so an issued invoice keeps the number that was true at sale time.
                // Trim, blank → null.
                buyerPhone = sale.buyerPhone?.trim()?.ifEmpty { null },
                taxInclusive = sale.taxInclusive,
                // The buyer's tax number as entered at checkout (ticket #106 follow-up) — snapshotted
                // onto the sale for the invoice. Carried for a walk-in too (the cashier may type one
                // for the bill even though there's no contact to save it to); trim, blank → null.
                buyerTaxNumber = sale.buyerTaxNumber?.trim()?.ifEmpty { null },
            ),
        )
    }

    private fun String.blankToZero(): String = trim().ifEmpty { "0" }

    /** A valid, non-negative money amount: zero or a positive decimal. */
    private fun String.isValidAmount(): Boolean {
        val v = blankToZero()
        return Money.isZero(v) || Money.isValidPositiveDecimal(v)
    }

    private companion object {
        /** Keeps one transaction comfortably under Firestore's ~500-write limit. */
        const val MAX_LINES = 100
    }
}
