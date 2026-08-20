package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.ResolvedCompany

interface CompanyDirectoryRepository {
    /**
     * Calls the gateway's /resolve-company with the given email.
     * Returns the list of companies that email belongs to. Empty list = unknown email.
     * Throws LoginException on network or gateway-level failures.
     */
    suspend fun resolveCompanies(email: String): List<ResolvedCompany>
}
