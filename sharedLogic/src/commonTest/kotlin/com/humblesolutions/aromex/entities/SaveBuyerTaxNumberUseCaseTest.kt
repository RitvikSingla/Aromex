package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.usecase.SaveBuyerTaxNumberUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaveBuyerTaxNumberUseCaseTest {

    @Test
    fun withManage_writesTrimmedTaxNumber() = runTest {
        val repo = FakeEntityRepository()
        SaveBuyerTaxNumberUseCase(repo).execute(
            sessionWith(PermissionLevel.MANAGE), "e-1", "  987654321 RT0002  ",
        )
        assertEquals(listOf<Pair<String, String?>>("e-1" to "987654321 RT0002"), repo.taxNumberUpdates)
    }

    @Test
    fun blankTaxNumber_clearsToNull() = runTest {
        val repo = FakeEntityRepository()
        SaveBuyerTaxNumberUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), "e-1", "   ")
        assertEquals(listOf<Pair<String, String?>>("e-1" to null), repo.taxNumberUpdates)
    }

    @Test
    fun withoutManage_deniesAndDoesNotWrite() = runTest {
        val repo = FakeEntityRepository()
        assertFailsWith<PermissionDeniedException> {
            SaveBuyerTaxNumberUseCase(repo).execute(sessionWith(PermissionLevel.VIEW), "e-1", "123")
        }
        assertTrue(repo.taxNumberUpdates.isEmpty())
    }

    @Test
    fun blankEntityId_isRejected() = runTest {
        val repo = FakeEntityRepository()
        assertFailsWith<IllegalArgumentException> {
            SaveBuyerTaxNumberUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), "  ", "123")
        }
        assertTrue(repo.taxNumberUpdates.isEmpty())
    }
}
