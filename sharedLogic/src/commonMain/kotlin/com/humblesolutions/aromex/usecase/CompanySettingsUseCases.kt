package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.CompanyDetails
import com.humblesolutions.aromex.model.CompanySettingsChange
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.repository.CompanySettingsRepository
import com.humblesolutions.aromex.util.Money
import kotlinx.coroutines.flow.Flow

// Company settings are admin-only to change — `requireAdmin` (CommissionRuleUseCases) is the same
// bar used for voiding a sale and for commission rules. A tax rate decides what every future sale
// charges the customer and owes the government; the company details are what appears on a legal
// invoice. Neither is a daily task, and both are wrong in a way nobody notices for weeks.

/** Reading settings needs only a signed-in user — every screen needs the currency and tax. */
class ObserveCompanyProfileUseCase(
    private val repository: CompanySettingsRepository,
) {
    fun execute(): Flow<CompanyProfile> = repository.observeProfile()
}

/** Why a tax configuration can't be saved — one case per inline message the form shows. */
enum class TaxConfigError { GstRateInvalid, PstRateInvalid, HstNeedsGst, NothingEnabled }

/**
 * Changes the tax configuration (admin only).
 *
 * **Past sales are untouched.** Each one snapshots its own `taxLines`, Humble Ledger holds the
 * posted amounts, and a void reverses the original transaction by id — so a new rate reaches new
 * sales and nothing else. That is what makes a plain overwrite safe here, with no need for
 * effective-dated rate history.
 */
class UpdateTaxConfigUseCase(
    private val repository: CompanySettingsRepository,
) {
    suspend fun execute(session: UserSession, config: TaxConfig) {
        requireAdmin(session)
        when (validate(config)) {
            TaxConfigError.GstRateInvalid ->
                throw IllegalArgumentException("GST rate must be between 0% and 100%")
            TaxConfigError.PstRateInvalid ->
                throw IllegalArgumentException("PST rate must be between 0% and 100%")
            TaxConfigError.HstNeedsGst ->
                throw IllegalArgumentException("HST is charged on the GST line, so GST must be enabled")
            TaxConfigError.NothingEnabled -> Unit // legitimate: a shop that isn't tax-registered
            null -> Unit
        }
        repository.updateTax(config, session.uid, session.displayName)
    }

    companion object {
        /**
         * Exposed so a form can gate its Save button on exactly what `execute` enforces, rather
         * than keeping a second copy of the rules that drifts.
         *
         * Rates are decimal **strings** holding a fraction (`"0.05"` = 5%) — never floats, per
         * `CLAUDE.md`. A rate above `"1"` would mean over 100% tax: a typo, not a jurisdiction.
         */
        fun validate(config: TaxConfig): TaxConfigError? = when {
            config.gstEnabled && !isValidRate(config.gstRate) -> TaxConfigError.GstRateInvalid
            config.pstEnabled && !isValidRate(config.pstRate) -> TaxConfigError.PstRateInvalid
            // HST replaces GST+PST as a single combined line carried on the GST leg.
            config.isHST && !config.gstEnabled -> TaxConfigError.HstNeedsGst
            !config.gstEnabled && !config.pstEnabled -> TaxConfigError.NothingEnabled
            else -> null
        }

        /** A blocking problem — [TaxConfigError.NothingEnabled] is a warning, not an error. */
        fun blockingError(config: TaxConfig): TaxConfigError? =
            validate(config)?.takeIf { it != TaxConfigError.NothingEnabled }

        /**
         * A rate is an unsigned decimal string in [0, 1]. Blank, negative, non-numeric and >100%
         * all fail.
         *
         * Well-formedness is checked first and on its own: `Money.isZero` calls anything it cannot
         * parse zero, so testing the value *is* a number has to come before testing what it equals
         * — otherwise `"abc"` saves as a rate and every later sale quietly charges no tax.
         */
        private fun isValidRate(rate: String): Boolean {
            val t = rate.trim()
            if (!Money.isValidUnsignedDecimal(t)) return false
            return Money.lessThanOrEqual(t, "1")
        }
    }
}

/** Changes the shop's identity on invoices (admin only). */
class UpdateCompanyDetailsUseCase(
    private val repository: CompanySettingsRepository,
) {
    suspend fun execute(session: UserSession, details: CompanyDetails) {
        requireAdmin(session)
        require(details.companyName.isNotBlank()) { "Company name is required" }
        repository.updateDetails(
            details.copy(
                companyName = details.companyName.trim(),
                legalName = details.legalName?.trim()?.takeIf { it.isNotEmpty() },
                taxNumber = details.taxNumber?.trim()?.takeIf { it.isNotEmpty() },
                businessAddress = details.businessAddress?.trim()?.takeIf { it.isNotEmpty() },
                contactEmail = details.contactEmail?.trim()?.takeIf { it.isNotEmpty() },
                contactPhone = details.contactPhone?.trim()?.takeIf { it.isNotEmpty() },
            ),
            session.uid,
            session.displayName,
        )
    }
}

/** The change log. Admin-only to read — it names who changed what. */
class ObserveSettingsChangesUseCase(
    private val repository: CompanySettingsRepository,
) {
    fun execute(session: UserSession, limit: Int = DEFAULT_LIMIT): Flow<List<CompanySettingsChange>> {
        requireAdmin(session)
        return repository.observeChanges(limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}

/**
 * A settings value rendered the way the change log should show it.
 *
 * Both the diff and the log read from these, so "GST rate" is spelled one way and a rate always
 * appears as a percentage rather than the fraction stored underneath — nobody reads `0.05` and
 * thinks *five percent* under pressure.
 */
object SettingsAudit {

    /** The tax configuration as displayable field → value pairs, in the order a form shows them. */
    fun taxFields(config: TaxConfig): Map<String, String> = buildMap {
        put("GST", if (config.gstEnabled) "on" else "off")
        if (config.gstEnabled) put("GST rate", asPercent(config.gstRate))
        put("PST", if (config.pstEnabled) "on" else "off")
        if (config.pstEnabled) put("PST rate", asPercent(config.pstRate))
        put("HST", if (config.isHST) "on" else "off")
    }

    fun detailFields(details: CompanyDetails): Map<String, String> = buildMap {
        put("Company name", details.companyName)
        put("Legal name", details.legalName.orEmpty())
        put("Tax number", details.taxNumber.orEmpty())
        put("Business address", details.businessAddress.orEmpty())
        put("Contact email", details.contactEmail.orEmpty())
        put("Contact phone", details.contactPhone.orEmpty())
    }

    /**
     * What actually changed between two renderings — the log records nothing for a save that
     * altered nothing, so an admin opening the form and pressing Save doesn't leave noise behind.
     *
     * A field that appears or disappears (turning GST off hides its rate) is reported with a null
     * old or a blank new, rather than being silently dropped.
     */
    fun diff(before: Map<String, String>, after: Map<String, String>): List<Triple<String, String?, String>> =
        (before.keys + after.keys).mapNotNull { field ->
            val old = before[field]
            val new = after[field]
            when {
                new == null -> Triple(field, old, "") // the field no longer applies
                old == new -> null
                else -> Triple(field, old, new)
            }
        }

    /** `"0.05"` → `"5%"`, `"0.09975"` → `"9.975%"`. Exact — see [shiftPoint]. */
    fun asPercent(rate: String): String = shiftPoint(rate.trim().ifEmpty { "0" }, 2) + "%"

    /** `"5"` as typed → `"0.05"` as stored; `"9.975"` → `"0.09975"`. Exact. */
    fun percentToFraction(percent: String): String =
        shiftPoint(percent.trim().removeSuffix("%").trim().ifEmpty { "0" }, -2)

    /**
     * Moves a decimal string's point [places] to the right (negative moves left), by moving the
     * point rather than multiplying.
     *
     * `Money.multiplyRate` rounds half-up to **two** decimals, which is right for money and wrong
     * for a rate: 7.5% would round to `0.08`, and Quebec's 9.975% QST to `0.10`. Shifting the point
     * is exact at any precision and stays free of floating point.
     */
    internal fun shiftPoint(value: String, places: Int): String {
        val negative = value.startsWith("-")
        val bare = value.trimStart('+', '-')
        val dot = bare.indexOf('.')
        val digits = if (dot < 0) bare else bare.removeRange(dot, dot + 1)
        // Where the point sits among `digits`, before shifting.
        var point = if (dot < 0) bare.length else dot
        point += places

        val padded = when {
            point < 0 -> "0".repeat(-point) + digits          // 0.05 → shift left → 0.0005
            point > digits.length -> digits + "0".repeat(point - digits.length)
            else -> digits
        }
        val at = if (point < 0) 0 else point

        val intPart = padded.take(at).trimStart('0').ifEmpty { "0" }
        val fracPart = padded.drop(at).trimEnd('0')
        val body = if (fracPart.isEmpty()) intPart else "$intPart.$fracPart"
        return if (negative && body != "0") "-$body" else body
    }
}
