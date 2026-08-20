package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.LedgerAccount

interface LedgerRepository {
    /**
     * Reads the chart of accounts (with balances) for the signed-in company from HL.
     * Throws HlException on transport, auth, or HL upstream failures.
     */
    suspend fun getAccounts(): List<LedgerAccount>
}
