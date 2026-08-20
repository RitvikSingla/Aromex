package com.humblesolutions.aromex.sales

import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.usecase.GetSaleUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GetSaleUseCaseTest {

    private fun detail(id: String) = SaleDetail(
        saleId = id, createdAtMillis = 1_000L, createdBy = "u1", customerEntityId = "cust-1",
        isWalkIn = false, buyerName = null, buyerPhone = null, lines = emptyList(),
        subtotal = "0.00", saleDiscount = "0.00", taxableAmount = "0.00", taxLines = emptyList(),
        taxTotal = "0.00", grandTotal = "0.00", cogsTotal = "0.00", payment = PaymentInput(),
        amountPaid = "0.00", balanceRemaining = "0.00", note = null,
        syncStatus = HlSyncStatus.SYNCED, invoice = SaleInvoice(),
    )

    @Test
    fun deniesWithoutSalesPermission() = runTest {
        val repo = FakeSalesRepository(salesById = mapOf("s1" to detail("s1")))
        assertFailsWith<PermissionDeniedException> {
            GetSaleUseCase(repo).execute(saleSession(sales = PermissionLevel.NONE), "s1")
        }
    }

    @Test
    fun viewReadsTheSale() = runTest {
        val repo = FakeSalesRepository(salesById = mapOf("s1" to detail("s1")))
        val result = GetSaleUseCase(repo).execute(saleSession(sales = PermissionLevel.VIEW), "s1")
        assertEquals("s1", result?.saleId)
    }

    @Test
    fun missingSaleReturnsNull() = runTest {
        val repo = FakeSalesRepository()
        assertNull(GetSaleUseCase(repo).execute(saleSession(), "nope"))
    }

    @Test
    fun blankIdRejected() = runTest {
        val repo = FakeSalesRepository()
        assertFailsWith<IllegalArgumentException> {
            GetSaleUseCase(repo).execute(saleSession(), "  ")
        }
    }
}
