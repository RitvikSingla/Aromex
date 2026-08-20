package com.humblesolutions.aromex.purchases

import com.humblesolutions.aromex.inventory.FakeInventoryRepository
import com.humblesolutions.aromex.inventory.sessionWith
import com.humblesolutions.aromex.inventory.skuAttributes
import com.humblesolutions.aromex.inventory.validUnit
import com.humblesolutions.aromex.model.CommissionInput
import com.humblesolutions.aromex.model.NewProduct
import com.humblesolutions.aromex.model.NewUnit
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.StockBatchGroup
import com.humblesolutions.aromex.model.UNSPECIFIED_SUPPLIER_ID
import com.humblesolutions.aromex.usecase.RecordInventoryPurchaseUseCase
import com.humblesolutions.aromex.util.SkuKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Hands out a fresh, valid (15-digit), globally-unique IMEI per unit across the file. */
private var imeiCounter = 0
private fun nextImei(): String = "35693803564" + (imeiCounter++).toString().padStart(4, '0')

/** One SKU group with a unit per entry in [costs] (each a distinct, valid IMEI). */
private fun groupWith(vararg costs: String): StockBatchGroup {
    val attrs = skuAttributes()
    val units: List<NewUnit> = costs.map { c -> validUnit(imei = nextImei(), cost = c) }
    return StockBatchGroup(
        skuKey = SkuKey.build(attrs),
        product = NewProduct(attributes = attrs, defaultSellingPrice = "999.00"),
        units = units,
    )
}

class RecordInventoryPurchaseUseCaseTest {

    @Test
    fun sumsBatchCost_andWritesStockAndPurchaseAtomically() = runTest {
        val repo = FakeInventoryRepository()
        RecordInventoryPurchaseUseCase(repo).execute(
            session = sessionWith(PermissionLevel.MANAGE),
            groups = listOf(groupWith("700", "700", "1000")),
            partyEntityId = "ent-1",
            cashPaid = "1000",
            bankPaid = "", purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        // Exactly one atomic call carries BOTH the stock groups and the purchase.
        assertEquals(1, repo.batchPurchaseCalls.size)
        val call = repo.batchPurchaseCalls.single()
        assertEquals(1, call.groups.size)
        assertEquals("ent-1", call.purchase.partyEntityId)
        assertEquals("2400", call.purchase.totalCost)
        assertEquals("1000", call.purchase.cashPaid)
        assertEquals("0", call.purchase.bankPaid) // blank normalized to zero
    }

    @Test
    fun defaultPath_unspecifiedSupplier_nothingPaid() = runTest {
        val repo = FakeInventoryRepository()
        RecordInventoryPurchaseUseCase(repo).execute(
            session = sessionWith(PermissionLevel.MANAGE),
            groups = listOf(groupWith("500")),
            partyEntityId = UNSPECIFIED_SUPPLIER_ID,
            cashPaid = "",
            bankPaid = "", purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        val input = repo.batchPurchaseCalls.single().purchase
        assertEquals(UNSPECIFIED_SUPPLIER_ID, input.partyEntityId)
        assertEquals("500", input.totalCost)
        assertEquals("0", input.cashPaid)
        assertEquals("0", input.bankPaid)
    }

    @Test
    fun totalSumsAcrossMultipleSkuGroups() = runTest {
        val repo = FakeInventoryRepository()
        RecordInventoryPurchaseUseCase(repo).execute(
            session = sessionWith(PermissionLevel.MANAGE),
            groups = listOf(groupWith("600"), groupWith("400")),
            partyEntityId = "ent-1",
            cashPaid = "0",
            bankPaid = "0", purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        assertEquals("1000", repo.batchPurchaseCalls.single().purchase.totalCost)
    }

    @Test
    fun paidEqualToTotal_isAllowed() = runTest {
        val repo = FakeInventoryRepository()
        RecordInventoryPurchaseUseCase(repo).execute(
            session = sessionWith(PermissionLevel.MANAGE),
            groups = listOf(groupWith("600", "400")),
            partyEntityId = "ent-1",
            cashPaid = "600",
            bankPaid = "400", purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        assertEquals(1, repo.batchPurchaseCalls.size)
    }

    @Test
    fun paidExceedingTotal_isRejected_andNothingWritten() = runTest {
        val repo = FakeInventoryRepository()
        val err = assertFailsWith<IllegalArgumentException> {
            RecordInventoryPurchaseUseCase(repo).execute(
                session = sessionWith(PermissionLevel.MANAGE),
                groups = listOf(groupWith("2000")),
                partyEntityId = "ent-1",
                cashPaid = "1500",
                bankPaid = "600", // 2100 > 2000
                purchaseDate = 1_700_000_000_000L,
                now = 1_700_000_000_000L,
            )
        }
        assertTrue(err.message!!.contains("exceed"))
        assertTrue(repo.batchPurchaseCalls.isEmpty())
    }

    @Test
    fun blankParty_isRejected_andNothingWritten() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            RecordInventoryPurchaseUseCase(repo).execute(
                session = sessionWith(PermissionLevel.MANAGE),
                groups = listOf(groupWith("500")),
                partyEntityId = "   ",
                cashPaid = "0",
                bankPaid = "0", purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        }
        assertTrue(repo.batchPurchaseCalls.isEmpty())
    }

    @Test
    fun withoutInventoryManage_isDenied_andNothingWritten() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<PermissionDeniedException> {
            RecordInventoryPurchaseUseCase(repo).execute(
                session = sessionWith(PermissionLevel.VIEW),
                groups = listOf(groupWith("500")),
                partyEntityId = "ent-1",
                cashPaid = "0",
                bankPaid = "0", purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        }
        assertTrue(repo.batchPurchaseCalls.isEmpty())
    }

    @Test
    fun commissions_areThreadedIntoTheSameAtomicCall() = runTest {
        val repo = FakeInventoryRepository()
        val commissions = listOf(
            CommissionInput(
                payeeEntityId = "rajesh", locationAttributeId = "loc-a", ruleId = "r1",
                unitCount = 12, basisAmount = "0", amount = "60.00", // add to balance (accrue only)
            ),
            CommissionInput(
                payeeEntityId = "priya", locationAttributeId = "loc-a", ruleId = null, // hand-edited
                unitCount = 12, basisAmount = "14400.00", amount = "300.00",
                paidCash = "100.00", paidBank = "50.00", // give now: partial split (150 ≤ 300)
            ),
        )
        RecordInventoryPurchaseUseCase(repo).execute(
            session = sessionWith(PermissionLevel.MANAGE),
            groups = listOf(groupWith("1200")),
            partyEntityId = "ent-1",
            cashPaid = "0",
            bankPaid = "0",
            commissions = commissions, purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        // Same single atomic call carries stock + purchase + both commissions.
        val call = repo.batchPurchaseCalls.single()
        assertEquals(2, call.commissions.size)
        assertEquals(null, call.commissions[1].ruleId)             // override kept its null ruleId
        assertEquals("100.00", call.commissions[1].paidCash)
        assertEquals("50.00", call.commissions[1].paidBank)
        assertEquals("0", call.commissions[0].paidCash)            // accrue-only normalized to "0"
        assertEquals("0", call.commissions[0].paidBank)
    }

    @Test
    fun commission_withNonPositiveAmount_isRejected_andNothingWritten() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            RecordInventoryPurchaseUseCase(repo).execute(
                session = sessionWith(PermissionLevel.MANAGE),
                groups = listOf(groupWith("500")),
                partyEntityId = "ent-1",
                cashPaid = "0",
                bankPaid = "0",
                commissions = listOf(
                    CommissionInput("rajesh", "loc-a", "r1", unitCount = 1, basisAmount = "0", amount = "0"),
                ), purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        }
        assertTrue(repo.batchPurchaseCalls.isEmpty())
    }

    @Test
    fun commission_giveNowExceedingAmount_isRejected_andNothingWritten() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            RecordInventoryPurchaseUseCase(repo).execute(
                session = sessionWith(PermissionLevel.MANAGE),
                groups = listOf(groupWith("500")),
                partyEntityId = "ent-1",
                cashPaid = "0",
                bankPaid = "0",
                commissions = listOf(
                    // 40 + 30 = 70 given, but only 60 owed → rejected.
                    CommissionInput("rajesh", "loc-a", "r1", unitCount = 1, basisAmount = "0", amount = "60.00", paidCash = "40.00", paidBank = "30.00"),
                ), purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        }
        assertTrue(repo.batchPurchaseCalls.isEmpty())
    }

    @Test
    fun emptyGroups_isRejected() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            RecordInventoryPurchaseUseCase(repo).execute(
                session = sessionWith(PermissionLevel.MANAGE),
                groups = emptyList(),
                partyEntityId = "ent-1",
                cashPaid = "0",
                bankPaid = "0", purchaseDate = 1_700_000_000_000L, now = 1_700_000_000_000L)
        }
        assertTrue(repo.batchPurchaseCalls.isEmpty())
    }
}
