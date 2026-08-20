package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.EntityInput
import com.humblesolutions.aromex.model.EntityRole
import com.humblesolutions.aromex.model.OpeningBalance
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.usecase.SaveEntityUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaveEntityUseCaseTest {

    private fun validInput() = EntityInput(
        name = "Sharma Traders",
        phones = listOf("98765"),
        email = "a@b.com",
        roles = setOf(EntityRole.CUSTOMER),
    )

    @Test
    fun create_withManage_writesPendingAndReturnsId() = runTest {
        val repo = FakeEntityRepository().apply { nextId = "e-42" }
        val id = SaveEntityUseCase(repo).execute(sessionWith(PermissionLevel.MANAGE), validInput())
        assertEquals("e-42", id)
        assertEquals(1, repo.created.size)
        assertTrue(repo.updated.isEmpty())
    }

    @Test
    fun update_withExistingId_callsUpdateAndReturnsSameId() = runTest {
        val repo = FakeEntityRepository()
        val id = SaveEntityUseCase(repo).execute(
            sessionWith(PermissionLevel.MANAGE),
            validInput(),
            existingId = "e-7",
        )
        assertEquals("e-7", id)
        assertEquals(1, repo.updated.size)
        assertEquals("e-7", repo.updated.first().first)
        assertTrue(repo.created.isEmpty())
    }

    @Test
    fun withoutManage_throwsPermissionDenied() = runTest {
        val repo = FakeEntityRepository()
        assertFailsWith<PermissionDeniedException> {
            SaveEntityUseCase(repo).execute(sessionWith(PermissionLevel.VIEW), validInput())
        }
        assertTrue(repo.created.isEmpty())
    }

    @Test
    fun blankName_isRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SaveEntityUseCase(FakeEntityRepository())
                .execute(sessionWith(PermissionLevel.MANAGE), validInput().copy(name = "   "))
        }
    }

    @Test
    fun noPhone_isRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SaveEntityUseCase(FakeEntityRepository())
                .execute(sessionWith(PermissionLevel.MANAGE), validInput().copy(phones = listOf(" ")))
        }
    }

    @Test
    fun invalidEmail_isRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SaveEntityUseCase(FakeEntityRepository())
                .execute(sessionWith(PermissionLevel.MANAGE), validInput().copy(email = "not-an-email"))
        }
    }

    @Test
    fun openingWithNonPositiveAmount_isRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SaveEntityUseCase(FakeEntityRepository()).execute(
                sessionWith(PermissionLevel.MANAGE),
                validInput().copy(opening = OpeningBalance("0", BalanceDirection.RECEIVABLE)),
            )
        }
    }

    @Test
    fun openingWithSettledDirection_isRejected() = runTest {
        assertFailsWith<IllegalArgumentException> {
            SaveEntityUseCase(FakeEntityRepository()).execute(
                sessionWith(PermissionLevel.MANAGE),
                validInput().copy(opening = OpeningBalance("100.00", BalanceDirection.SETTLED)),
            )
        }
    }

    @Test
    fun validOpeningCredit_isAccepted() = runTest {
        val repo = FakeEntityRepository()
        SaveEntityUseCase(repo).execute(
            sessionWith(PermissionLevel.MANAGE),
            validInput().copy(opening = OpeningBalance("100.00", BalanceDirection.CREDIT)),
        )
        assertEquals(1, repo.created.size)
    }
}
