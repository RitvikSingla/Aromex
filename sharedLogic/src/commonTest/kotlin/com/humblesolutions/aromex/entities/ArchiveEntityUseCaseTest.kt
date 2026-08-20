package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.model.Entity
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.usecase.ArchiveEntityUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArchiveEntityUseCaseTest {

    @Test
    fun archive_withManage_softDeletesById() = runTest {
        val repo = FakeEntityRepository()
        ArchiveEntityUseCase(repo).execute(
            sessionWith(PermissionLevel.MANAGE),
            Entity(id = "e-1", name = "Acme"),
        )
        assertEquals(listOf("e-1"), repo.archived)
    }

    @Test
    fun walkIn_cannotBeArchived() = runTest {
        val repo = FakeEntityRepository()
        assertFailsWith<IllegalArgumentException> {
            ArchiveEntityUseCase(repo).execute(
                sessionWith(PermissionLevel.MANAGE),
                Entity(id = "walkin", name = "Walk-in Customer", isWalkIn = true),
            )
        }
        assertTrue(repo.archived.isEmpty())
    }

    @Test
    fun withoutManage_throwsPermissionDenied() = runTest {
        val repo = FakeEntityRepository()
        assertFailsWith<PermissionDeniedException> {
            ArchiveEntityUseCase(repo).execute(
                sessionWith(PermissionLevel.VIEW),
                Entity(id = "e-1", name = "Acme"),
            )
        }
        assertTrue(repo.archived.isEmpty())
    }
}
