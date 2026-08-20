package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.HlException
import com.humblesolutions.aromex.model.LedgerAccount
import com.humblesolutions.aromex.repository.LedgerRepository
import kotlin.coroutines.cancellation.CancellationException

class GetAccountBalancesUseCase(
    private val ledger: LedgerRepository,
) {
    @Throws(HlException::class, CancellationException::class)
    suspend fun execute(): List<LedgerAccount> = ledger.getAccounts()
}
