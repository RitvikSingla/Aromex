package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.CommissionRule
import com.humblesolutions.aromex.model.CommissionRuleInput
import kotlinx.coroutines.flow.Flow

/**
 * The operational store for commission rules — the client's Firebase (Firestore),
 * `commissionRules/{ruleId}` (ticket #97). Implementations are per-platform; this ticket
 * ships Desktop only (Admin SDK). Rules touch no Humble Ledger — they only decide what a
 * later intake will owe. Permission enforcement (admin-only) lives in the use cases, not
 * here, because Desktop's Admin SDK bypasses Firestore rules.
 */
interface CommissionRuleRepository {
    /**
     * Live stream of commission rules. When [includeInactive] is false, only `isActive == true`
     * (the set that fires at intake). The rules screen passes true to show switched-off rules.
     */
    fun observeRules(includeInactive: Boolean): Flow<List<CommissionRule>>

    /**
     * Creates a rule (blank [CommissionRuleInput.ruleId]) or updates that rule in place
     * (non-blank id — the same doc, so its earned commissions keep pointing at a live rule).
     * Returns the rule's id.
     */
    suspend fun saveRule(input: CommissionRuleInput): String

    /**
     * Switches a rule off (`isActive = false`) — it stops matching future intakes but is never
     * hard-deleted, so already-earned commission keeps a live rule to point back at.
     */
    suspend fun archiveRule(ruleId: String)

    /** Releases any resources the implementation holds (e.g. the Desktop Admin-SDK client). */
    fun close() {}
}
