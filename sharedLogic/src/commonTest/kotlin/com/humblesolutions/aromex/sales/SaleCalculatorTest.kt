package com.humblesolutions.aromex.sales

import com.humblesolutions.aromex.model.SaleLineInput
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.usecase.SaleCalculator
import com.humblesolutions.aromex.util.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaleCalculatorTest {

    private val phone = SaleLineInput.InventoryLineInput(
        productId = "p1", serialId = "s1", unitPrice = "699.00", lineDiscount = "20.00",
    )
    private val caseLine = SaleLineInput.CustomLineInput(name = "Case", unitPrice = "25.00")
    private val lines = listOf(phone, caseLine)
    private val costs = mapOf("s1" to "560.00")

    @Test
    fun subtotal_isNetOfPerItemDiscounts() {
        val t = SaleCalculator.compute(lines, "0", TaxConfig())
        assertEquals("704.00", t.subtotal) // 679.00 + 25.00
        assertEquals("704.00", t.taxableAmount)
    }

    @Test
    fun gstOnly_oneLine() {
        val t = SaleCalculator.compute(lines, "0", TaxConfig(gstEnabled = true, gstRate = "0.05"), costs)
        assertEquals(1, t.taxLines.size)
        assertEquals("GST", t.taxLines[0].name)
        assertEquals("35.20", t.taxTotal)
        assertEquals("739.20", t.grandTotal)
        assertEquals("560.00", t.cogsTotal)
    }

    @Test
    fun gstPlusPst_twoLines() {
        val tax = TaxConfig(gstEnabled = true, gstRate = "0.05", pstEnabled = true, pstRate = "0.07")
        val t = SaleCalculator.compute(lines, "0", tax)
        assertEquals(listOf("GST", "PST"), t.taxLines.map { it.name })
        assertEquals("35.20", t.taxLines[0].amount)
        assertEquals("49.28", t.taxLines[1].amount)
        assertEquals("84.48", t.taxTotal)
        assertEquals("788.48", t.grandTotal)
    }

    @Test
    fun hst_singleCombinedLine_ignoresPst() {
        val tax = TaxConfig(gstEnabled = true, gstRate = "0.13", pstEnabled = true, pstRate = "0.07", isHST = true)
        val t = SaleCalculator.compute(lines, "0", tax)
        assertEquals(listOf("HST"), t.taxLines.map { it.name })
        assertEquals("91.52", t.taxTotal)
        assertEquals("795.52", t.grandTotal)
    }

    @Test
    fun none_noTaxLines() {
        val t = SaleCalculator.compute(lines, "0", TaxConfig())
        assertEquals(0, t.taxLines.size)
        assertEquals("0", t.taxTotal)
        assertEquals("704.00", t.grandTotal)
    }

    @Test
    fun wholeSaleDiscount_reducesTaxableBase() {
        val t = SaleCalculator.compute(lines, "4.00", TaxConfig(gstEnabled = true, gstRate = "0.05"))
        assertEquals("700.00", t.taxableAmount)
        assertEquals("35.00", t.taxTotal)
        assertEquals("735.00", t.grandTotal)
    }

    @Test
    fun cogs_zero_whenNoInventoryLines() {
        val t = SaleCalculator.compute(listOf(caseLine), "0", TaxConfig())
        assertEquals("0", t.cogsTotal)
    }

    // ─────────────────────────── tax-inclusive (ticket #106) ───────────────────────────

    private val gstPst = TaxConfig(gstEnabled = true, gstRate = "0.05", pstEnabled = true, pstRate = "0.07")

    /** The BC case: a single line typed as $700 out-the-door, GST 5% + PST 7%. */
    @Test
    fun inclusive_bcCase_backsOutTaxToThePenny() {
        val line = SaleLineInput.CustomLineInput(name = "Phone", unitPrice = "700.00")
        val t = SaleCalculator.compute(listOf(line), "0", gstPst, taxInclusive = true)
        assertEquals("625.00", t.taxableAmount)
        assertEquals(listOf("GST", "PST"), t.taxLines.map { it.name })
        assertEquals("31.25", t.taxLines[0].amount) // 625.00 × 0.05
        assertEquals("43.75", t.taxLines[1].amount) // remainder: 700 − 625 − 31.25
        assertEquals("75.00", t.taxTotal)
        // Customer pays exactly what was typed, and the base + legs re-sum to it.
        assertEquals("700.00", t.grandTotal)
        assertEquals("700.00", Money.add(t.taxableAmount, t.taxTotal))
    }

    @Test
    fun inclusive_noTax_isPassThrough() {
        val line = SaleLineInput.CustomLineInput(name = "Phone", unitPrice = "700.00")
        val t = SaleCalculator.compute(listOf(line), "0", TaxConfig(), taxInclusive = true)
        assertEquals(0, t.taxLines.size)
        assertEquals("0", t.taxTotal)
        assertEquals("700.00", t.taxableAmount)
        assertEquals("700.00", t.grandTotal)
    }

    /**
     * The exactness identity — `taxableAmount + Σ taxLines == grandTotal`, and the customer-facing
     * total equals exactly the typed amount less the sale discount — must hold across many amounts
     * and every tax regime, in inclusive mode.
     */
    @Test
    fun inclusive_identityHoldsAcrossAmountsAndRegimes() {
        val gstOnly = TaxConfig(gstEnabled = true, gstRate = "0.05")
        val hst = TaxConfig(gstEnabled = true, gstRate = "0.13", isHST = true)
        val regimes = listOf(TaxConfig(), gstOnly, gstPst, hst)
        val amounts = listOf("0.01", "0.03", "1.00", "9.99", "100.00", "333.33", "700.00", "1234.56", "99999.99")
        val discounts = listOf("0", "0.01", "5.00")
        for (tax in regimes) {
            for (amount in amounts) {
                for (discount in discounts) {
                    // Keep the discount ≤ subtotal (mirrors the use-case guard).
                    if (!Money.lessThanOrEqual(discount, amount)) continue
                    // A line discount too, to exercise the netPrice path.
                    val line = SaleLineInput.CustomLineInput(
                        name = "L", unitPrice = Money.add(amount, "0.50"), lineDiscount = "0.50",
                    )
                    val t = SaleCalculator.compute(listOf(line), discount, tax, taxInclusive = true)
                    val expectedGrand = Money.subtract(amount, discount)
                    val recomposed = Money.add(t.taxableAmount, t.taxTotal)
                    assertTrue(
                        Money.compare(recomposed, t.grandTotal) == 0,
                        "identity failed: base ${t.taxableAmount} + tax ${t.taxTotal} != grand ${t.grandTotal} " +
                            "(amount=$amount discount=$discount tax=$tax)",
                    )
                    assertTrue(
                        Money.compare(t.grandTotal, expectedGrand) == 0,
                        "grand ${t.grandTotal} != typed-less-discount $expectedGrand " +
                            "(amount=$amount discount=$discount tax=$tax)",
                    )
                }
            }
        }
    }
}
