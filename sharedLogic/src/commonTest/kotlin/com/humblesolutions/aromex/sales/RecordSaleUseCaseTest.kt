package com.humblesolutions.aromex.sales

import com.humblesolutions.aromex.model.AlreadySoldException
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.ResolvedSaleLine
import com.humblesolutions.aromex.model.SaleInput
import com.humblesolutions.aromex.model.SaleLineInput
import com.humblesolutions.aromex.model.SaleRecordLine
import com.humblesolutions.aromex.model.WALK_IN_CUSTOMER_ID
import com.humblesolutions.aromex.usecase.RecordSaleUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordSaleUseCaseTest {

    private val phone = SaleLineInput.InventoryLineInput(
        productId = "p1", serialId = "s1", unitPrice = "699.00", lineDiscount = "20.00",
    )
    private val caseLine = SaleLineInput.CustomLineInput(name = "Case", unitPrice = "25.00")
    private val resolved = listOf(
        ResolvedSaleLine(serialId = "s1", imei = "356938035699001", label = "iPhone 15", listPrice = "699.00", cost = "560.00"),
    )

    private fun sale(
        isWalkIn: Boolean = false,
        customer: String = "cust-1",
        payment: PaymentInput = PaymentInput(cash = "700.00"),
        buyerTaxNumber: String? = null,
        buyerPhone: String? = null,
    ) = SaleInput(
        customerEntityId = customer,
        isWalkIn = isWalkIn,
        lines = listOf(phone, caseLine),
        payment = payment,
        saleDate = NOW,
        buyerTaxNumber = buyerTaxNumber,
        buyerPhone = buyerPhone,
    )

    /** A fixed clock, so the future-date guard is deterministic. */
    private companion object {
        const val NOW = 1_700_000_000_000L
    }

    @Test
    fun happyPath_buildsRecord_withSnapshotsAndTotals() = runTest {
        val repo = FakeSalesRepository()
        val id = RecordSaleUseCase(repo).execute(saleSession(), sale(), resolved, now = NOW)

        assertEquals("sale-1", id)
        val rec = repo.recorded.single()
        assertEquals("cust-1", rec.customerEntityId)
        assertEquals("739.20", rec.grandTotal)      // 704 + 5% GST
        assertEquals("560.00", rec.cogsTotal)
        assertEquals("700.00", rec.amountPaid)
        assertEquals("39.20", rec.balanceRemaining) // named customer may short-pay
        assertEquals("u1", rec.createdBy)
        val inv = rec.lines.first() as SaleRecordLine.Inventory
        assertEquals("356938035699001", inv.imei)   // snapshot from resolved
        assertEquals("679.00", inv.netPrice)
        assertEquals("560.00", inv.cost)
    }

    @Test
    fun walkIn_mustPayInFull() = runTest {
        val repo = FakeSalesRepository()
        // Short-paid walk-in → rejected.
        assertFailsWith<IllegalArgumentException> {
            RecordSaleUseCase(repo).execute(
                saleSession(),
                sale(isWalkIn = true, customer = WALK_IN_CUSTOMER_ID, payment = PaymentInput(cash = "700.00")),
                resolved, now = NOW)
        }
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun walkIn_paidInFull_succeeds() = runTest {
        val repo = FakeSalesRepository()
        RecordSaleUseCase(repo).execute(
            saleSession(),
            sale(isWalkIn = true, customer = WALK_IN_CUSTOMER_ID, payment = PaymentInput(cash = "739.20")),
            resolved, now = NOW)
        assertEquals("0", repo.recorded.single().balanceRemaining)
    }

    @Test
    fun buyerTaxNumber_snapshotted_forNamedAndWalkIn() = runTest {
        // Named customer: the trimmed tax number is snapshotted onto the record.
        val named = FakeSalesRepository()
        RecordSaleUseCase(named).execute(saleSession(), sale(buyerTaxNumber = "  987654321 RT0002 "), resolved, now = NOW)
        assertEquals("987654321 RT0002", named.recorded.single().buyerTaxNumber)

        // Walk-in: carried too (ticket #106 follow-up) — a cashier may type one for the bill.
        val walkIn = FakeSalesRepository()
        RecordSaleUseCase(walkIn).execute(
            saleSession(),
            sale(isWalkIn = true, customer = WALK_IN_CUSTOMER_ID, payment = PaymentInput(cash = "739.20"), buyerTaxNumber = "GST-123"),
            resolved,
            now = NOW,
        )
        assertEquals("GST-123", walkIn.recorded.single().buyerTaxNumber)

        // Blank → null.
        val blank = FakeSalesRepository()
        RecordSaleUseCase(blank).execute(saleSession(), sale(buyerTaxNumber = "  "), resolved, now = NOW)
        assertEquals(null, blank.recorded.single().buyerTaxNumber)
    }

    @Test
    fun buyerPhone_snapshotted_forNamedAndWalkIn() = runTest {
        // Named customer: the trimmed phone (prefilled from their Entity, editable at checkout) is
        // snapshotted onto the record — this is what makes it print on the bill.
        val named = FakeSalesRepository()
        RecordSaleUseCase(named).execute(saleSession(), sale(buyerPhone = "  +91 98100 12345 "), resolved, now = NOW)
        assertEquals("+91 98100 12345", named.recorded.single().buyerPhone)

        // Walk-in: carried too, as before.
        val walkIn = FakeSalesRepository()
        RecordSaleUseCase(walkIn).execute(
            saleSession(),
            sale(isWalkIn = true, customer = WALK_IN_CUSTOMER_ID, payment = PaymentInput(cash = "739.20"), buyerPhone = "+1 555 0100"),
            resolved,
            now = NOW,
        )
        assertEquals("+1 555 0100", walkIn.recorded.single().buyerPhone)

        // Blank → null.
        val blank = FakeSalesRepository()
        RecordSaleUseCase(blank).execute(saleSession(), sale(buyerPhone = "  "), resolved, now = NOW)
        assertEquals(null, blank.recorded.single().buyerPhone)
    }

    @Test
    fun overpayment_rejected() = runTest {
        val repo = FakeSalesRepository()
        assertFailsWith<IllegalArgumentException> {
            RecordSaleUseCase(repo).execute(saleSession(), sale(payment = PaymentInput(cash = "800.00")), resolved, now = NOW)
        }
    }

    @Test
    fun requiresSalesManage() = runTest {
        val repo = FakeSalesRepository()
        assertFailsWith<PermissionDeniedException> {
            RecordSaleUseCase(repo).execute(saleSession(sales = PermissionLevel.VIEW), sale(), resolved, now = NOW)
        }
    }

    @Test
    fun propagatesAlreadySold() = runTest {
        val repo = FakeSalesRepository(soldImei = "356938035699001")
        assertFailsWith<AlreadySoldException> {
            RecordSaleUseCase(repo).execute(saleSession(), sale(), resolved, now = NOW)
        }
    }

    @Test
    fun rejectsDuplicateSerialInOneSale() = runTest {
        val repo = FakeSalesRepository()
        val dup = SaleInput(
            customerEntityId = "cust-1",
            isWalkIn = false,
            lines = listOf(phone, phone), // same serial twice
            payment = PaymentInput(),
            saleDate = NOW,
        )
        assertFailsWith<IllegalArgumentException> {
            RecordSaleUseCase(repo).execute(saleSession(), dup, resolved, now = NOW)
        }
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun rejectsLineDiscountAboveUnitPrice() = runTest {
        val repo = FakeSalesRepository()
        val bad = SaleInput(
            customerEntityId = "cust-1",
            isWalkIn = false,
            lines = listOf(SaleLineInput.CustomLineInput(name = "X", unitPrice = "10.00", lineDiscount = "20.00")),
            payment = PaymentInput(),
            saleDate = NOW,
        )
        assertFailsWith<IllegalArgumentException> {
            RecordSaleUseCase(repo).execute(saleSession(), bad, emptyList(), now = NOW)
        }
    }

    // ── Business date (ticket #107) ──────────────────────────────────────────

    @Test
    fun aBackdatedSale_keepsTheDateItWasGiven() = runTest {
        val repo = FakeSalesRepository()
        val lastMarch = 1_679_000_000_000L
        RecordSaleUseCase(repo).execute(
            saleSession(),
            sale().copy(saleDate = lastMarch),
            resolved,
            now = NOW,
        )
        // It rides on the record, which the repository stores as the doc's createdAt — the field
        // the list, the filters and the invoice stamp all already read as "when this happened".
        assertEquals(lastMarch, repo.recorded.single().saleDate)
    }

    @Test
    fun aFutureDatedSale_isRefused() = runTest {
        val repo = FakeSalesRepository()
        val err = assertFailsWith<IllegalArgumentException> {
            RecordSaleUseCase(repo).execute(
                saleSession(),
                // A day ahead: revenue that hasn't happened, in a period that isn't closed.
                sale().copy(saleDate = NOW + 24 * 60 * 60 * 1000L),
                resolved,
                now = NOW,
            )
        }
        assertTrue(err.message!!.contains("future"))
        assertTrue(repo.recorded.isEmpty())
    }

    @Test
    fun aSaleDatedMinutesAhead_isAllowed_becauseClocksDrift() = runTest {
        val repo = FakeSalesRepository()
        RecordSaleUseCase(repo).execute(
            saleSession(),
            sale().copy(saleDate = NOW + 60_000L),
            resolved,
            now = NOW,
        )
        assertEquals(1, repo.recorded.size)
    }
}
