package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.CompanyDetails
import com.humblesolutions.aromex.model.CompanySettingsChange
import com.humblesolutions.aromex.model.TaxConfig
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes `companySettings/profile` — the shop's tax configuration and its identity on
 * every invoice.
 *
 * **Observed, not fetched.** A tax rate that only refreshes at sign-in means an admin changing GST
 * at noon leaves every open till charging the old rate for the rest of the day, silently. Everything
 * that computes tax must follow this stream rather than a value captured at login.
 *
 * Changing a rate never rewrites history: each sale snapshots its own `taxLines` (name, rate,
 * amount), Humble Ledger holds the posted amounts, and a void reverses the original transaction by
 * id. A new rate applies to new sales and to nothing else.
 */
interface CompanySettingsRepository {

    /** The live company profile. Emits on every change, so tax follows within seconds. */
    fun observeProfile(): Flow<CompanyProfile>

    /**
     * Replaces the tax configuration and records the change.
     *
     * The profile write and its audit entries must land **together** — a rate that changed with no
     * record of who changed it is exactly the gap the log exists to close.
     */
    suspend fun updateTax(config: TaxConfig, changedBy: String, changedByName: String?)

    /** Replaces the company's invoice identity and records what changed. */
    suspend fun updateDetails(details: CompanyDetails, changedBy: String, changedByName: String?)

    /** The change log, newest first. Append-only. */
    fun observeChanges(limit: Int): Flow<List<CompanySettingsChange>>
}
