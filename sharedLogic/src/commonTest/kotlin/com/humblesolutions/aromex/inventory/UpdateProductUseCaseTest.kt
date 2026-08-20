package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.ProductEdits
import com.humblesolutions.aromex.usecase.UpdateProductUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpdateProductUseCaseTest {

    @Test
    fun withManage_updatesSellingPrice() = runTest {
        val repo = FakeInventoryRepository()
        UpdateProductUseCase(repo).execute(
            sessionWith(PermissionLevel.MANAGE),
            "b1_m1_cap1_col1_car1",
            ProductEdits(defaultSellingPrice = "749.00"),
        )
        assertEquals("b1_m1_cap1_col1_car1" to ProductEdits("749.00"), repo.updatedProducts.single())
    }

    @Test
    fun invalidPrice_throws() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<IllegalArgumentException> {
            UpdateProductUseCase(repo).execute(
                sessionWith(PermissionLevel.MANAGE),
                "b1_m1_cap1_col1_car1",
                ProductEdits(defaultSellingPrice = "-1"),
            )
        }
        assertTrue(repo.updatedProducts.isEmpty())
    }

    @Test
    fun withoutManage_throwsPermissionDenied() = runTest {
        val repo = FakeInventoryRepository()
        assertFailsWith<PermissionDeniedException> {
            UpdateProductUseCase(repo).execute(
                sessionWith(PermissionLevel.VIEW),
                "b1_m1_cap1_col1_car1",
                ProductEdits(defaultSellingPrice = "749.00"),
            )
        }
        assertTrue(repo.updatedProducts.isEmpty())
    }
}
