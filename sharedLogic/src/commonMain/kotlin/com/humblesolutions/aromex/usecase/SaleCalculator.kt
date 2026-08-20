package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.SaleLineInput
import com.humblesolutions.aromex.model.SaleTotals
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.TaxLine
import com.humblesolutions.aromex.util.Money

/**
 * The **single home** for a sale's money math (ticket #61) — a pure function used both by
 * [RecordSaleUseCase] for the authoritative write and by the per-platform Sales
 * ViewModels (T2) for the live checkout display, so the two can never drift. No floats:
 * every figure is a decimal string via [Money].
 *
 * Order of operations (tax-**exclusive**, the default; Brief #60):
 * 1. `netPrice = unitPrice − lineDiscount` per line (clamped ≥ 0)
 * 2. `subtotal = Σ netPrice`
 * 3. `taxableAmount = subtotal − saleDiscount` (clamped ≥ 0)
 * 4. one tax leg per enabled tax: `amount = round½up(taxableAmount × rate, 2)`
 * 5. `grandTotal = taxableAmount + taxTotal`
 * 6. `cogsTotal = Σ` inventory-line cost (from [costBySerialId])
 *
 * Tax-**inclusive** (ticket #106) inverts steps 3–5: the typed prices already contain the tax, so
 * `grandTotal = subtotal − saleDiscount` (what the customer pays, as typed) and the tax-exclusive
 * base is recovered by dividing it out. The exactness rule — `taxableAmount + Σ taxLines` must equal
 * `grandTotal` **to the penny**, for one tax, two taxes, or none:
 * 1. `taxableAmount = round½up(grandTotal ÷ (1 + Σ rates), 2)`
 * 2. every tax leg **except the last** = `round½up(taxableAmount × rate, 2)`
 * 3. the **last** leg absorbs the remainder: `grandTotal − taxableAmount − Σ(other legs)`
 */
object SaleCalculator {

    /**
     * @param costBySerialId per-unit cost keyed by serialId, for cost-of-goods. Callers
     *   that don't need COGS (e.g. a ViewModel showing only customer-facing totals) may
     *   pass an empty map → `cogsTotal = "0"`.
     * @param taxInclusive when true, the typed line prices already contain tax — the totals are
     *   computed by the inverted, last-leg-absorbs-the-remainder path above. Defaults to false,
     *   which reproduces the original tax-exclusive arithmetic byte-for-byte.
     */
    fun compute(
        lines: List<SaleLineInput>,
        saleDiscount: String,
        tax: TaxConfig,
        costBySerialId: Map<String, String> = emptyMap(),
        taxInclusive: Boolean = false,
    ): SaleTotals {
        val subtotal = Money.sum(lines.map { Money.subtract(it.unitPrice, it.lineDiscount) })

        // The enabled tax legs as (name, rate), in display order: GST/HST first, then PST. Shared
        // by both modes so the leg set is identical whichever way the sale is priced.
        val taxSpecs = buildList {
            if (tax.gstEnabled) add((if (tax.isHST) "HST" else "GST") to tax.gstRate)
            if (tax.pstEnabled && !tax.isHST) add("PST" to tax.pstRate)
        }

        val taxableAmount: String
        val taxLines: List<TaxLine>
        val grandTotal: String
        if (!taxInclusive) {
            taxableAmount = Money.subtract(subtotal, saleDiscount)
            taxLines = taxSpecs.map { (name, rate) ->
                TaxLine(name, rate, Money.multiplyRate(taxableAmount, rate))
            }
            grandTotal = Money.add(taxableAmount, Money.sum(taxLines.map { it.amount }))
        } else {
            // Inclusive: the customer pays exactly what was typed (net of the sale discount).
            grandTotal = Money.subtract(subtotal, saleDiscount)
            if (taxSpecs.isEmpty()) {
                taxableAmount = grandTotal
                taxLines = emptyList()
            } else {
                val divisor = Money.add("1", Money.sum(taxSpecs.map { it.second }))
                taxableAmount = Money.divide(grandTotal, divisor, 2)
                taxLines = buildList {
                    taxSpecs.forEachIndexed { i, (name, rate) ->
                        val amount = if (i < taxSpecs.lastIndex) {
                            Money.multiplyRate(taxableAmount, rate)
                        } else {
                            // Last leg absorbs the rounding remainder so the legs + base re-sum to
                            // exactly the typed grand total.
                            val others = Money.sum(map { it.amount })
                            Money.subtract(grandTotal, Money.add(taxableAmount, others))
                        }
                        add(TaxLine(name, rate, amount))
                    }
                }
            }
        }
        val taxTotal = Money.sum(taxLines.map { it.amount })

        val cogsTotal = Money.sum(
            lines.filterIsInstance<SaleLineInput.InventoryLineInput>()
                .map { costBySerialId[it.serialId] ?: "0" },
        )

        return SaleTotals(
            subtotal = subtotal,
            taxableAmount = taxableAmount,
            taxLines = taxLines,
            taxTotal = taxTotal,
            grandTotal = grandTotal,
            cogsTotal = cogsTotal,
        )
    }
}
