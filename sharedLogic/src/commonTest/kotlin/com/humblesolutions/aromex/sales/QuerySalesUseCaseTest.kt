package com.humblesolutions.aromex.sales

import com.humblesolutions.aromex.model.HlSyncStatus
import com.humblesolutions.aromex.model.PaymentInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.SaleDetail
import com.humblesolutions.aromex.model.SaleInvoice
import com.humblesolutions.aromex.model.SaleInvoiceStatus
import com.humblesolutions.aromex.model.SaleRecordLine
import com.humblesolutions.aromex.model.SaleSummary
import com.humblesolutions.aromex.model.SalesCursor
import com.humblesolutions.aromex.model.SalesPage
import com.humblesolutions.aromex.model.SalesQuery
import com.humblesolutions.aromex.usecase.QuerySalesUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuerySalesUseCaseTest {

    private fun summary(
        id: String,
        createdAt: Long = 1_000L,
        customer: String = "cust-1",
        balance: String = "0.00",
        imei: String? = "356938035699001",
    ) = SaleSummary(
        saleId = id,
        createdAtMillis = createdAt,
        customerEntityId = customer,
        isWalkIn = false,
        buyerName = null,
        firstItemLabel = "iPhone 15",
        itemCount = 1,
        firstImei = imei,
        grandTotal = "100.00",
        amountPaid = "0.00",
        balanceRemaining = balance,
        syncStatus = HlSyncStatus.SYNCED,
        invoiceNumber = "INV-000042",
        invoiceStatus = SaleInvoiceStatus.ISSUED,
    )

    private fun detail(
        id: String,
        createdAt: Long = 1_000L,
        customer: String = "cust-1",
        balance: String = "0.00",
        imei: String = "356938035699001",
    ) = SaleDetail(
        saleId = id,
        createdAtMillis = createdAt,
        createdBy = "u1",
        customerEntityId = customer,
        isWalkIn = false,
        buyerName = null,
        buyerPhone = null,
        lines = listOf(
            SaleRecordLine.Inventory(
                productId = "p1", serialId = "s1", imei = imei, label = "iPhone 15",
                listPrice = "100.00", unitPrice = "100.00", lineDiscount = "0.00",
                netPrice = "100.00", cost = "80.00",
            ),
        ),
        subtotal = "100.00", saleDiscount = "0.00", taxableAmount = "100.00",
        taxLines = emptyList(), taxTotal = "0.00", grandTotal = "100.00", cogsTotal = "80.00",
        payment = PaymentInput(), amountPaid = "0.00", balanceRemaining = balance,
        note = null, syncStatus = HlSyncStatus.SYNCED, invoice = SaleInvoice(status = SaleInvoiceStatus.ISSUED, number = "INV-000042"),
    )

    @Test
    fun deniesWithoutSalesPermission() = runTest {
        val repo = FakeSalesRepository()
        assertFailsWith<PermissionDeniedException> {
            QuerySalesUseCase(repo).execute(saleSession(sales = PermissionLevel.NONE), SalesQuery())
        }
        assertEquals(0, repo.querySalesCalls, "must not query when permission is denied")
    }

    @Test
    fun viewCanRead() = runTest {
        val repo = FakeSalesRepository(page = SalesPage(listOf(summary("s1")), null))
        val page = QuerySalesUseCase(repo).execute(saleSession(sales = PermissionLevel.VIEW), SalesQuery())
        assertEquals(1, page.sales.size)
    }

    @Test
    fun passesCursorAndFiltersThroughToRepo() = runTest {
        val repo = FakeSalesRepository(page = SalesPage(emptyList(), null))
        val cursor = SalesCursor(createdAtMillis = 500L, saleId = "s9")
        val query = SalesQuery(
            customerEntityIds = listOf("cust-1"),
            onlyWithBalance = true,
            dateFromMillis = 100L,
            dateToMillis = 900L,
            cursor = cursor,
        )
        QuerySalesUseCase(repo).execute(saleSession(), query)
        assertEquals(query, repo.lastQuery, "the use case must hand the query (incl. cursor) straight to the repo")
    }

    @Test
    fun paging_returnsNextCursorFromRepo() = runTest {
        val next = SalesCursor(createdAtMillis = 1_000L, saleId = "s1")
        val repo = FakeSalesRepository(page = SalesPage(listOf(summary("s1")), next))
        val page = QuerySalesUseCase(repo).execute(saleSession(), SalesQuery())
        assertEquals(next, page.nextCursor)
    }

    @Test
    fun imeiPath_resolvesThroughSerialNotAList() = runTest {
        // IMEI can't be queried on the sale doc: it must go serial → saleId → sale.
        val repo = FakeSalesRepository(
            salesById = mapOf("sale-42" to detail("sale-42")),
            imeiToSaleId = mapOf("356938035699001" to "sale-42"),
        )
        val page = QuerySalesUseCase(repo).execute(saleSession(), SalesQuery(imei = "356938035699001"))
        assertEquals(1, page.sales.size)
        assertEquals("sale-42", page.sales.first().saleId)
        assertEquals(0, repo.querySalesCalls, "IMEI search must not hit the list query")
    }

    @Test
    fun imeiPath_unknownImei_returnsEmpty() = runTest {
        val repo = FakeSalesRepository()
        val page = QuerySalesUseCase(repo).execute(saleSession(), SalesQuery(imei = "000000000000000"))
        assertTrue(page.sales.isEmpty())
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun imeiPath_appliesOtherFiltersClientSide() = runTest {
        // The sale for this IMEI has no balance, so a "with balance" filter must exclude it.
        val repo = FakeSalesRepository(
            salesById = mapOf("sale-42" to detail("sale-42", balance = "0.00")),
            imeiToSaleId = mapOf("356938035699001" to "sale-42"),
        )
        val page = QuerySalesUseCase(repo)
            .execute(saleSession(), SalesQuery(imei = "356938035699001", onlyWithBalance = true))
        assertTrue(page.sales.isEmpty(), "IMEI result must still respect the balance filter")
    }

    @Test
    fun imeiPath_balanceFilter_correctAcrossPowerOfTen() = runTest {
        // The string-ordering trap: "90.00" and "100.00" must BOTH count as having a balance.
        for (bal in listOf("90.00", "100.00")) {
            val repo = FakeSalesRepository(
                salesById = mapOf("s" to detail("s", balance = bal)),
                imeiToSaleId = mapOf("356938035699001" to "s"),
            )
            val page = QuerySalesUseCase(repo)
                .execute(saleSession(), SalesQuery(imei = "356938035699001", onlyWithBalance = true))
            assertEquals(1, page.sales.size, "a $bal balance must be found by the balance filter")
        }
    }
}
