package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.PermissionLevel
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.EntityLedgerRepository

/**
 * Reads party balances from HL (VIEW or MANAGE on `profiles`). [execute] returns
 * all balances keyed by `externalId` for the list; [executeSingle] reads one party
 * for the detail screen. The merge with entities happens in the ViewModel.
 */
class GetEntityBalancesUseCase(
    private val ledger: EntityLedgerRepository,
) {
    suspend fun execute(session: UserSession): Map<String, EntityBalance> {
        requireView(session)
        return ledger.getBalances()
    }

    suspend fun executeSingle(session: UserSession, externalId: String): EntityBalance? {
        requireView(session)
        return ledger.getBalance(externalId)
    }

    private fun requireView(session: UserSession) {
        if (session.permissions.profiles == PermissionLevel.NONE) {
            throw PermissionDeniedException("profiles")
        }
    }
}
