package com.humblesolutions.aromex.purchases

import com.humblesolutions.aromex.entities.FakeEntityRepository
import com.humblesolutions.aromex.entities.sessionWith
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.usecase.AddSupplierInlineUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddSupplierInlineUseCaseTest {

    @Test
    fun createsPhonelessSupplier_withManage() = runTest {
        val repo = FakeEntityRepository()
        repo.nextId = "sup-1"
        val id = AddSupplierInlineUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), "  Mobile Wholesale  ")
        assertEquals("sup-1", id)
        val input = repo.created.single()
        assertEquals("Mobile Wholesale", input.name) // trimmed
        assertTrue(input.phones.isEmpty())
        assertEquals(setOf(EntityRole.SUPPLIER), input.roles)
    }

    @Test
    fun withoutProfilesManage_isDenied() = runTest {
        val repo = FakeEntityRepository()
        assertFailsWith<PermissionDeniedException> {
            AddSupplierInlineUseCase(repo).execute(sessionWith(PermissionLevel.VIEW), "X")
        }
        assertTrue(repo.created.isEmpty())
    }

    @Test
    fun blankName_isRejected() = runTest {
        val repo = FakeEntityRepository()
        assertFailsWith<IllegalArgumentException> {
            AddSupplierInlineUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), "   ")
        }
    }
}
