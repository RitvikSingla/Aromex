package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.CommissionRuleInput
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.RateKind
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.CommissionRuleRepository
import com.humblesolutions.aromex.util.Money
import kotlinx.coroutines.flow.Flow

/**
 * Admin gate for commission-rule management (ticket #97). A rule silently creates money owed on
 * every future intake — a settings-grade act, not a daily one — so it needs `role == ADMIN`, the
 * same bar as voiding a sale (`VoidSaleUseCase`), *not* a permission scope. Enforced here because
 * Desktop's Admin SDK bypasses Firestore rules (`CLAUDE.md`).
 */
internal fun requireAdmin(session: UserSession) {
    if (session.role != UserRole.ADMIN) {
        throw PermissionDeniedException("admin")
    }
}

/**
 * Streams the commission rules for the **management screen** (ticket #97) — the admin-only
 * settings list where standing arrangements are read and edited (including switched-off ones).
 * Distinct from [ObserveActiveCommissionRulesUseCase], which a cashier uses at intake.
 */
class ObserveCommissionRulesUseCase(
    private val repository: CommissionRuleRepository,
) {
    /** @param includeInactive true on the rules screen (show switched-off rules). */
    fun execute(session: UserSession, includeInactive: Boolean): Flow<List<CommissionRule>> {
        requireAdmin(session)
        return repository.observeRules(includeInactive)
    }
}

/**
 * Streams the **active** commission rules for the intake dialog (ticket #97). Gated on
 * `inventory` access, **not** admin: the cashier who adds stock must see the commission a rule
 * proposes and confirm it, even though only an admin may write the rule. Returns active rules
 * only — a switched-off rule never fires (what's owed stays owed, but nothing new accrues).
 */
class ObserveActiveCommissionRulesUseCase(
    private val repository: CommissionRuleRepository,
) {
    fun execute(session: UserSession): Flow<List<CommissionRule>> {
        requireInventoryAccess(session)
        return repository.observeRules(includeInactive = false)
    }
}

/**
 * Creates or edits a commission rule (ticket #97). Admin-only. Validates the arrangement before
 * it can start owing money: a location, a payee, and a rate that fits its kind — a per-unit amount
 * must be a positive decimal; a percent fraction must be a valid non-negative decimal (0%–100%,
 * i.e. `"0"`–`"1"`, stored as a fraction so 2% is `"0.02"`).
 */
class SaveCommissionRuleUseCase(
    private val repository: CommissionRuleRepository,
) {
    /**
     * @return the saved rule's id.
     * @throws PermissionDeniedException if [session] is not an admin.
     * @throws IllegalArgumentException on a missing location/payee or an invalid rate.
     */
    suspend fun execute(session: UserSession, input: CommissionRuleInput): String {
        requireAdmin(session)
        require(input.locationAttributeId.isNotBlank()) { "A location is required" }
        require(input.payeeEntityId.isNotBlank()) { "A payee is required" }
        val rate = input.rate.trim()
        when (input.rateKind) {
            RateKind.PER_UNIT ->
                require(Money.isValidPositiveDecimal(rate)) { "Per-unit rate must be a positive amount" }
            RateKind.PERCENT_OF_COST -> {
                // A fraction in (0, 1]: 2% is "0.02", 100% is "1". Zero or >100% is not a rate.
                require(Money.isValidPositiveDecimal(rate) && Money.lessThanOrEqual(rate, "1")) {
                    "Percent rate must be a fraction greater than 0 and at most 1 (e.g. 0.02 for 2%)"
                }
            }
        }
        return repository.saveRule(input.copy(rate = rate))
    }
}

/**
 * Switches a commission rule off (ticket #97). Admin-only. Never hard-deletes and never touches
 * commission already earned — what's owed is owed; this only stops the rule matching future intakes.
 */
class ArchiveCommissionRuleUseCase(
    private val repository: CommissionRuleRepository,
) {
    /** @throws PermissionDeniedException if [session] is not an admin. */
    suspend fun execute(session: UserSession, ruleId: String) {
        requireAdmin(session)
        require(ruleId.isNotBlank()) { "ruleId is required" }
        repository.archiveRule(ruleId)
    }
}
