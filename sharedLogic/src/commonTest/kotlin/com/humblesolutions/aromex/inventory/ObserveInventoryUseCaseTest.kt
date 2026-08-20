package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.usecase.ObserveInventoryUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObserveInventoryUseCaseTest {

    @Test
    fun none_throwsForBothStreams() = runTest {
        val repo = FakeInventoryRepository()
        val useCase = ObserveInventoryUseCase(repo)
        assertFailsWith<PermissionDeniedException> { useCase.observeProducts(sessionWith(PermissionLevel.NONE)) }
        assertFailsWith<PermissionDeniedException> { useCase.observeInStockSerials(sessionWith(PermissionLevel.NONE)) }
    }

    @Test
    fun view_returnsProductsAndFiltersArchived() = runTest {
        val repo = FakeInventoryRepository()
        repo.products.value = listOf(
            Product(productId = "p-active", isActive = true),
            Product(productId = "p-archived", isActive = false),
        )
        val visible = ObserveInventoryUseCase(repo).observeProducts(sessionWith(PermissionLevel.VIEW)).first()
        assertEquals(listOf("p-active"), visible.map { it.productId })
        assertEquals(false, repo.lastIncludeArchived)
    }

    @Test
    fun includeArchived_passesThrough() = runTest {
        val repo = FakeInventoryRepository()
        repo.products.value = listOf(
            Product(productId = "p-active", isActive = true),
            Product(productId = "p-archived", isActive = false),
        )
        val all = ObserveInventoryUseCase(repo)
            .observeProducts(sessionWith(PermissionLevel.MANAGE), includeArchived = true).first()
        assertEquals(listOf("p-active", "p-archived"), all.map { it.productId })
        assertEquals(true, repo.lastIncludeArchived)
    }
}
