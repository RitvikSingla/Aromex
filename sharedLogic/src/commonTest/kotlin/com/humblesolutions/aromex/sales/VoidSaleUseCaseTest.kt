package com.humblesolutions.aromex.sales

import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.usecase.VoidSaleUseCase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VoidSaleUseCaseTest {

    @Test
    fun adminWithReason_delegatesToRepo() = runTest {
        val repo = FakeSalesRepository()
        VoidSaleUseCase(repo).execute(
            saleSession(role = UserRole.ADMIN),
            saleId = "s1",
            reason = "  wrong customer  ",
        )
        // Reason is trimmed before it reaches the repo (→ HL cancelReason).
        assertEquals(listOf("s1" to "wrong customer"), repo.voided)
    }

    @Test
    fun nonAdmin_isBlocked_evenWithSalesManage() = runTest {
        val repo = FakeSalesRepository()
        // A cashier with full `sales: manage` is still not allowed to void — void needs ADMIN.
        val e = assertFailsWith<PermissionDeniedException> {
            VoidSaleUseCase(repo).execute(
                saleSession(sales = PermissionLevel.MANAGE, role = UserRole.MEMBER),
                saleId = "s1",
                reason = "wrong customer",
            )
        }
        assertEquals("admin", e.feature)
        assertTrue(repo.voided.isEmpty(), "nothing should be voided when the gate fails")
    }

    @Test
    fun blankReason_isRejected() = runTest {
        val repo = FakeSalesRepository()
        assertFailsWith<IllegalArgumentException> {
            VoidSaleUseCase(repo).execute(saleSession(role = UserRole.ADMIN), "s1", "   ")
        }
        assertTrue(repo.voided.isEmpty())
    }

    @Test
    fun blankSaleId_isRejected() = runTest {
        val repo = FakeSalesRepository()
        assertFailsWith<IllegalArgumentException> {
            VoidSaleUseCase(repo).execute(saleSession(role = UserRole.ADMIN), "  ", "reason")
        }
        assertTrue(repo.voided.isEmpty())
    }
}
