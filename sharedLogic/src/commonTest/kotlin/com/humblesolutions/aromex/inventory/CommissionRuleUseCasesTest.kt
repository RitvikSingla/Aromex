package com.humblesolutions.aromex.inventory

import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.CommissionRuleInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.CommissionRuleRepository
import com.humblesolutions.aromex.usecase.ArchiveCommissionRuleUseCase
import com.humblesolutions.aromex.usecase.ObserveActiveCommissionRulesUseCase
import com.humblesolutions.aromex.usecase.ObserveCommissionRulesUseCase
import com.humblesolutions.aromex.usecase.SaveCommissionRuleUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun adminSession(inventory: PermissionLevel = PermissionLevel.MANAGE) = UserSession(
    uid = "a1", email = "a@test", displayName = "A", role = UserRole.ADMIN,
    permissions = Permissions(inventory = inventory), companyId = "c1", hlCompanyId = "hl1",
    currency = "CAD", isActive = true,
)

private fun memberSession(inventory: PermissionLevel = PermissionLevel.MANAGE) = UserSession(
    uid = "m1", email = "m@test", displayName = "M", role = UserRole.MEMBER,
    permissions = Permissions(inventory = inventory), companyId = "c1", hlCompanyId = "hl1",
    currency = "CAD", isActive = true,
)

private class FakeCommissionRuleRepository : CommissionRuleRepository {
    val saved = mutableListOf<CommissionRuleInput>()
    val archived = mutableListOf<String>()
    var lastIncludeInactive: Boolean? = null
    val rules = MutableStateFlow<List<CommissionRule>>(emptyList())

    override fun observeRules(includeInactive: Boolean): Flow<List<CommissionRule>> {
        lastIncludeInactive = includeInactive
        return rules
    }

    override suspend fun saveRule(input: CommissionRuleInput): String {
        saved += input
        return input.ruleId.ifBlank { "new-id" }
    }

    override suspend fun archiveRule(ruleId: String) { archived += ruleId }
}

class CommissionRuleUseCasesTest {

    // ── AC1: admin gate ─────────────────────────────────────────────────────────

    @Test
    fun save_rejectsNonAdmin_andWritesNothing() = runTest {
        val repo = FakeCommissionRuleRepository()
        assertFailsWith<PermissionDeniedException> {
            SaveCommissionRuleUseCase(repo).execute(
                memberSession(),
                CommissionRuleInput(locationAttributeId = "loc", payeeEntityId = "p", rateKind = RateKind.PER_UNIT, rate = "5"),
            )
        }
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun archive_rejectsNonAdmin() = runTest {
        val repo = FakeCommissionRuleRepository()
        assertFailsWith<PermissionDeniedException> {
            ArchiveCommissionRuleUseCase(repo).execute(memberSession(), "r1")
        }
        assertTrue(repo.archived.isEmpty())
    }

    @Test
    fun observeRules_rejectsNonAdmin() {
        val repo = FakeCommissionRuleRepository()
        assertFailsWith<PermissionDeniedException> {
            ObserveCommissionRulesUseCase(repo).execute(memberSession(), includeInactive = true)
        }
    }

    // ── The intake read path is inventory-gated, not admin ───────────────────────

    @Test
    fun observeActiveRules_allowsNonAdminWithInventoryAccess_activeOnly() {
        val repo = FakeCommissionRuleRepository()
        ObserveActiveCommissionRulesUseCase(repo).execute(memberSession(PermissionLevel.VIEW))
        assertEquals(false, repo.lastIncludeInactive) // active rules only fire at intake
    }

    @Test
    fun observeActiveRules_rejectsNoInventoryAccess() {
        val repo = FakeCommissionRuleRepository()
        assertFailsWith<PermissionDeniedException> {
            ObserveActiveCommissionRulesUseCase(repo).execute(memberSession(PermissionLevel.NONE))
        }
    }

    // ── Validation ───────────────────────────────────────────────────────────────

    @Test
    fun save_persistsValidPerUnitRule() = runTest {
        val repo = FakeCommissionRuleRepository()
        SaveCommissionRuleUseCase(repo).execute(
            adminSession(),
            CommissionRuleInput(locationAttributeId = "loc", payeeEntityId = "p", rateKind = RateKind.PER_UNIT, rate = "5.00"),
        )
        assertEquals("5.00", repo.saved.single().rate)
    }

    @Test
    fun save_rejectsZeroPercent() = runTest {
        val repo = FakeCommissionRuleRepository()
        assertFailsWith<IllegalArgumentException> {
            SaveCommissionRuleUseCase(repo).execute(
                adminSession(),
                CommissionRuleInput(locationAttributeId = "loc", payeeEntityId = "p", rateKind = RateKind.PERCENT_OF_COST, rate = "0"),
            )
        }
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun save_rejectsPercentAboveOne() = runTest {
        val repo = FakeCommissionRuleRepository()
        assertFailsWith<IllegalArgumentException> {
            SaveCommissionRuleUseCase(repo).execute(
                adminSession(),
                CommissionRuleInput(locationAttributeId = "loc", payeeEntityId = "p", rateKind = RateKind.PERCENT_OF_COST, rate = "1.5"),
            )
        }
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun save_rejectsMissingLocationOrPayee() = runTest {
        val repo = FakeCommissionRuleRepository()
        assertFailsWith<IllegalArgumentException> {
            SaveCommissionRuleUseCase(repo).execute(
                adminSession(),
                CommissionRuleInput(locationAttributeId = "", payeeEntityId = "p", rateKind = RateKind.PER_UNIT, rate = "5"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SaveCommissionRuleUseCase(repo).execute(
                adminSession(),
                CommissionRuleInput(locationAttributeId = "loc", payeeEntityId = " ", rateKind = RateKind.PER_UNIT, rate = "5"),
            )
        }
        assertTrue(repo.saved.isEmpty())
    }

    @Test
    fun archive_switchesOffById() = runTest {
        val repo = FakeCommissionRuleRepository()
        ArchiveCommissionRuleUseCase(repo).execute(adminSession(), "r1")
        assertEquals("r1", repo.archived.single())
    }
}
