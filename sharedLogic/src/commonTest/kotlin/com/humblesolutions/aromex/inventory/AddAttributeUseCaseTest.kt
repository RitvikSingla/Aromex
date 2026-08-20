package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.AttributeType
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.usecase.AddAttributeUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AddAttributeUseCaseTest {

    @Test
    fun normalizesNameAndDelegates() = runTest {
        val repo = FakeAttributeRepository()
        AddAttributeUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), AttributeType.BRAND, "  Apple  ")
        assertEquals("Apple", repo.addCalls.single().name)
    }

    @Test
    fun caseVariant_dedupesToSameId() = runTest {
        val repo = FakeAttributeRepository()
        val useCase = AddAttributeUseCase(repo)
        val first = useCase.execute(sessionWith(PermissionLevel.MANAGE), AttributeType.BRAND, "Apple")
        val second = useCase.execute(sessionWith(PermissionLevel.MANAGE), AttributeType.BRAND, "apple")
        assertEquals(first, second) // "apple" folds onto the existing "Apple"
        assertEquals(1, repo.attributes.value.size)
        assertEquals("Apple", repo.attributes.value.single().name) // display keeps first-seen case
    }

    @Test
    fun sameNameDifferentBrand_areDistinctModels() = runTest {
        val repo = FakeAttributeRepository()
        val useCase = AddAttributeUseCase(repo)
        val a = useCase.execute(sessionWith(PermissionLevel.MANAGE), AttributeType.MODEL, "Pro", parentId = "brandA")
        val b = useCase.execute(sessionWith(PermissionLevel.MANAGE), AttributeType.MODEL, "pro", parentId = "brandB")
        assertEquals(false, a == b) // same name, different parent brand → distinct
    }

    @Test
    fun collapsesInternalWhitespace() = runTest {
        val repo = FakeAttributeRepository()
        AddAttributeUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), AttributeType.MODEL, " iPhone   15 ", parentId = "b1")
        assertEquals("iPhone 15", repo.addCalls.single().name)
    }

    @Test
    fun model_requiresParentBrand() = runTest {
        val repo = FakeAttributeRepository()
        assertFailsWith<IllegalArgumentException> {
            AddAttributeUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), AttributeType.MODEL, "iPhone 15", parentId = null)
        }
        assertTrue(repo.addCalls.isEmpty())
    }

    @Test
    fun blankName_throws() = runTest {
        val repo = FakeAttributeRepository()
        assertFailsWith<IllegalArgumentException> {
            AddAttributeUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), AttributeType.COLOR, "   ")
        }
    }

    @Test
    fun withoutManage_throwsPermissionDenied() = runTest {
        val repo = FakeAttributeRepository()
        assertFailsWith<PermissionDeniedException> {
            AddAttributeUseCase(repo).execute(sessionWith(PermissionLevel.VIEW), AttributeType.BRAND, "Apple")
        }
        assertTrue(repo.addCalls.isEmpty())
    }
}
