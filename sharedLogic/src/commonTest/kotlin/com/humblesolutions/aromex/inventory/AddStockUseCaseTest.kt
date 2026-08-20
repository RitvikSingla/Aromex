package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.NewProduct
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.usecase.AddStockUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddStockUseCaseTest {

    private fun product(sellingPrice: String = "699.00") =
        NewProduct(attributes = skuAttributes(), defaultSellingPrice = sellingPrice)

    @Test
    fun withManage_buildsSkuKeyAndAddsStock() = runTest {
        val repo = FakeInventoryRepository()
        val result = AddStockUseCase(repo).execute(
            sessionWith(PermissionLevel.MANAGE),
            product(),
            listOf(validUnit(imei = "356938035643809"), validUnit(imei = "356938035643817")),
        )
        assertEquals(1, repo.addStockCalls.size)
        assertEquals("b1_m1_cap1_col1_car1", repo.addStockCalls.first().skuKey)
        assertEquals("b1_m1_cap1_col1_car1", result.productId)
        assertEquals(2, result.createdSerialIds.size)
    }

    @Test
    fun withoutManage_throwsPermissionDenied() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<PermissionDeniedException> {
            AddStockUseCase(repo).execute(sessionWith(PermissionLevel.VIEW), product(), listOf(validUnit()))
        }
        assertTrue(repo.addStockCalls.isEmpty())
    }

    @Test
    fun missingSkuAttribute_throws() = runTest {
        val repo = FakeInventoryRepository()
        val bad = NewProduct(
            attributes = skuAttributes() - com.humblesolutions.aromex.model.AttributeType.MODEL,
            defaultSellingPrice = "699.00",
        )
        assertFailsWith<IllegalArgumentException> {
            AddStockUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), bad, listOf(validUnit()))
        }
        assertTrue(repo.addStockCalls.isEmpty())
    }

    @Test
    fun noUnits_throws() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            AddStockUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), product(), emptyList())
        }
    }

    @Test
    fun duplicateImeiInBatch_throws() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            AddStockUseCase(repo).execute(
                sessionWith(PermissionLevel.MANAGE),
                product(),
                listOf(validUnit(imei = "356938035643809"), validUnit(imei = "356938035643809")),
            )
        }
        assertTrue(repo.addStockCalls.isEmpty())
    }

    @Test
    fun invalidCost_throws() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            AddStockUseCase(repo).execute(
                sessionWith(PermissionLevel.MANAGE),
                product(),
                listOf(validUnit(cost = "-5")),
            )
        }
    }

    @Test
    fun badImei_throws() = runTest {
        // Ticket #101: short numeric serials are now valid; a doc-id-unsafe value (slash) is not.
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            AddStockUseCase(repo).execute(
                sessionWith(PermissionLevel.MANAGE),
                product(),
                listOf(validUnit(imei = "bad/imei")),
            )
        }
        assertTrue(repo.addStockCalls.isEmpty())
    }

    @Test
    fun invalidSellingPrice_throws() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            AddStockUseCase(repo).execute(
                sessionWith(PermissionLevel.MANAGE),
                product(sellingPrice = "abc"),
                listOf(validUnit()),
            )
        }
    }

    // ── ticket #101: capacity/colour/carrier + selling price optional; cost stays required ──

    @Test
    fun blankOptionalAttributesAndNoSellingPrice_passes() = runTest {
        val repo = FakeInventoryRepository()
        val product = NewProduct(
            attributes = skuAttributes(capacity = "", color = "", carrier = ""),
            defaultSellingPrice = "", // set later at sale time
        )
        val result = AddStockUseCase(repo).execute(
            sessionWith(PermissionLevel.MANAGE),
            product,
            listOf(validUnit()),
        )
        assertEquals(1, repo.addStockCalls.size)
        assertEquals("b1_m1___", result.productId) // empty segments held for the blank attributes
    }

    @Test
    fun zeroCost_throws() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            AddStockUseCase(repo).execute(
                sessionWith(PermissionLevel.MANAGE),
                product(),
                listOf(validUnit(cost = "0")),
            )
        }
        assertTrue(repo.addStockCalls.isEmpty())
    }

    @Test
    fun blankCost_throws() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            AddStockUseCase(repo).execute(
                sessionWith(PermissionLevel.MANAGE),
                product(),
                listOf(validUnit(cost = "")),
            )
        }
        assertTrue(repo.addStockCalls.isEmpty())
    }
}
