package com.humblesolutions.aromex.settings

import com.humblesolutions.aromex.model.CompanyDetails
import com.humblesolutions.aromex.model.CompanySettingsChange
import com.humblesolutions.aromex.model.PermissionDeniedException
import com.humblesolutions.aromex.model.Permissions
import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.model.UserRole
import com.humblesolutions.aromex.model.UserSession
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.repository.CompanySettingsRepository
import com.humblesolutions.aromex.usecase.ObserveCompanyProfileUseCase
import com.humblesolutions.aromex.usecase.ObserveSettingsChangesUseCase
import com.humblesolutions.aromex.usecase.TaxConfigError
import com.humblesolutions.aromex.usecase.UpdateCompanyDetailsUseCase
import com.humblesolutions.aromex.usecase.UpdateTaxConfigUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun adminSession() = UserSession(
    uid = "a1", email = "a@test", displayName = "Ada", role = UserRole.ADMIN,
    permissions = Permissions(), companyId = "c1", hlCompanyId = "hl1",
    currency = "CAD", isActive = true,
)

private fun memberSession() = UserSession(
    uid = "m1", email = "m@test", displayName = "Mo", role = UserRole.MEMBER,
    permissions = Permissions(), companyId = "c1", hlCompanyId = "hl1",
    currency = "CAD", isActive = true,
)

private val BC_TAX = TaxConfig(
    gstEnabled = true, gstRate = "0.05", pstEnabled = true, pstRate = "0.07", isHST = false,
)

private class FakeCompanySettingsRepository : CompanySettingsRepository {
    val profile = MutableStateFlow(
        CompanyProfile(hlCompanyId = "hl1", currency = "CAD", companyName = "Acme", tax = BC_TAX),
    )
    val changes = MutableStateFlow<List<CompanySettingsChange>>(emptyList())

    var taxWrites = 0
    var detailWrites = 0
    var lastTax: TaxConfig? = null
    var lastDetails: CompanyDetails? = null
    var lastChangedBy: String? = null
    var lastChangedByName: String? = null
    var lastLimit: Int? = null

    override fun observeProfile(): Flow<CompanyProfile> = profile

    override suspend fun updateTax(config: TaxConfig, changedBy: String, changedByName: String?) {
        taxWrites++
        lastTax = config
        lastChangedBy = changedBy
        lastChangedByName = changedByName
    }

    override suspend fun updateDetails(details: CompanyDetails, changedBy: String, changedByName: String?) {
        detailWrites++
        lastDetails = details
        lastChangedBy = changedBy
        lastChangedByName = changedByName
    }

    override fun observeChanges(limit: Int): Flow<List<CompanySettingsChange>> {
        lastLimit = limit
        return changes
    }
}

class CompanySettingsUseCasesTest {

    // ── Admin gate ──────────────────────────────────────────────────────────────
    // A tax rate decides what every future sale charges the customer and owes the government.

    @Test
    fun updateTax_rejectsNonAdmin_andWritesNothing() = runTest {
        val repo = FakeCompanySettingsRepository()
        assertFailsWith<PermissionDeniedException> {
            UpdateTaxConfigUseCase(repo).execute(memberSession(), BC_TAX.copy(gstRate = "0.06"))
        }
        assertEquals(0, repo.taxWrites)
    }

    @Test
    fun updateDetails_rejectsNonAdmin_andWritesNothing() = runTest {
        val repo = FakeCompanySettingsRepository()
        assertFailsWith<PermissionDeniedException> {
            UpdateCompanyDetailsUseCase(repo).execute(
                memberSession(),
                CompanyDetails("Acme 2", null, null, null, null, null),
            )
        }
        assertEquals(0, repo.detailWrites)
    }

    @Test
    fun observeChanges_rejectsNonAdmin_theLogNamesWhoDidWhat() = runTest {
        val repo = FakeCompanySettingsRepository()
        assertFailsWith<PermissionDeniedException> {
            ObserveSettingsChangesUseCase(repo).execute(memberSession())
        }
    }

    @Test
    fun observeProfile_needsNoAdmin_everyScreenNeedsCurrencyAndTax() = runTest {
        val repo = FakeCompanySettingsRepository()
        assertEquals(BC_TAX, ObserveCompanyProfileUseCase(repo).execute().first().tax)
    }

    // ── The write itself ────────────────────────────────────────────────────────

    @Test
    fun updateTax_writesTheConfigAndStampsTheAuthor() = runTest {
        val repo = FakeCompanySettingsRepository()
        val next = BC_TAX.copy(gstRate = "0.06")
        UpdateTaxConfigUseCase(repo).execute(adminSession(), next)

        assertEquals(1, repo.taxWrites)
        assertEquals(next, repo.lastTax)
        // Who changed it is not the caller's to supply — it comes from the session.
        assertEquals("a1", repo.lastChangedBy)
        assertEquals("Ada", repo.lastChangedByName)
    }

    @Test
    fun updateDetails_trimsAndNullsOutBlanks() = runTest {
        val repo = FakeCompanySettingsRepository()
        UpdateCompanyDetailsUseCase(repo).execute(
            adminSession(),
            CompanyDetails(
                companyName = "  Acme Mobile  ",
                legalName = "   ",
                taxNumber = " 123456789RT0001 ",
                businessAddress = null,
                contactEmail = "",
                contactPhone = " +1 604 555 0100 ",
            ),
        )
        val saved = repo.lastDetails!!
        assertEquals("Acme Mobile", saved.companyName)
        // A field cleared to whitespace is absent, not a blank string that prints as an empty
        // line on the invoice letterhead.
        assertNull(saved.legalName)
        assertNull(saved.contactEmail)
        assertEquals("123456789RT0001", saved.taxNumber)
        assertEquals("+1 604 555 0100", saved.contactPhone)
    }

    @Test
    fun updateDetails_rejectsABlankCompanyName() = runTest {
        val repo = FakeCompanySettingsRepository()
        assertFailsWith<IllegalArgumentException> {
            UpdateCompanyDetailsUseCase(repo).execute(
                adminSession(),
                CompanyDetails("   ", null, null, null, null, null),
            )
        }
        assertEquals(0, repo.detailWrites)
    }

    // ── Rate validation ─────────────────────────────────────────────────────────
    // Rates are decimal STRINGS holding a fraction ("0.05" = 5%). A value above "1" would be
    // over 100% tax: a typo, not a jurisdiction.

    @Test
    fun validate_acceptsRealJurisdictions() {
        // BC, an HST province, Quebec's 9.975% QST, and a shop that isn't tax-registered.
        assertNull(UpdateTaxConfigUseCase.validate(BC_TAX))
        assertNull(
            UpdateTaxConfigUseCase.validate(
                TaxConfig(gstEnabled = true, gstRate = "0.13", pstEnabled = false, pstRate = "0", isHST = true),
            ),
        )
        assertNull(
            UpdateTaxConfigUseCase.validate(
                TaxConfig(gstEnabled = true, gstRate = "0.05", pstEnabled = true, pstRate = "0.09975"),
            ),
        )
        assertEquals(
            TaxConfigError.NothingEnabled,
            UpdateTaxConfigUseCase.validate(TaxConfig(gstEnabled = false, pstEnabled = false)),
        )
    }

    @Test
    fun validate_rejectsBadRates() {
        assertEquals(
            TaxConfigError.GstRateInvalid,
            UpdateTaxConfigUseCase.validate(BC_TAX.copy(gstRate = "")),
        )
        assertEquals(
            TaxConfigError.GstRateInvalid,
            UpdateTaxConfigUseCase.validate(BC_TAX.copy(gstRate = "-0.05")),
        )
        // The classic slip: typing "5" for five percent, which is 500% tax.
        assertEquals(
            TaxConfigError.GstRateInvalid,
            UpdateTaxConfigUseCase.validate(BC_TAX.copy(gstRate = "5")),
        )
        // Non-numeric garbage must not slip through as a well-behaved zero — Money.isZero says
        // yes to anything it can't parse, so a rate of "abc" would have saved and silently
        // charged nothing.
        assertEquals(
            TaxConfigError.PstRateInvalid,
            UpdateTaxConfigUseCase.validate(BC_TAX.copy(pstRate = "abc")),
        )
        assertEquals(
            TaxConfigError.GstRateInvalid,
            UpdateTaxConfigUseCase.validate(BC_TAX.copy(gstRate = "0.05%")),
        )
    }

    @Test
    fun validate_ignoresTheRateOfADisabledTax() {
        // A stale rate left behind in a switched-off field must not block a save.
        assertNull(
            UpdateTaxConfigUseCase.validate(
                TaxConfig(gstEnabled = true, gstRate = "0.05", pstEnabled = false, pstRate = "99"),
            ),
        )
    }

    @Test
    fun validate_hstNeedsGst_itRidesTheGstLine() {
        assertEquals(
            TaxConfigError.HstNeedsGst,
            UpdateTaxConfigUseCase.validate(
                TaxConfig(gstEnabled = false, pstEnabled = true, pstRate = "0.07", isHST = true),
            ),
        )
    }

    @Test
    fun blockingError_treatsNoTaxAsAWarningNotAnError() {
        // A shop that isn't tax-registered is a legitimate configuration — warn, don't block.
        val noTax = TaxConfig(gstEnabled = false, pstEnabled = false)
        assertEquals(TaxConfigError.NothingEnabled, UpdateTaxConfigUseCase.validate(noTax))
        assertNull(UpdateTaxConfigUseCase.blockingError(noTax))
        assertEquals(
            TaxConfigError.GstRateInvalid,
            UpdateTaxConfigUseCase.blockingError(BC_TAX.copy(gstRate = "5")),
        )
    }

    @Test
    fun execute_refusesAnInvalidRate_beforeItReachesTheRepository() = runTest {
        val repo = FakeCompanySettingsRepository()
        assertFailsWith<IllegalArgumentException> {
            UpdateTaxConfigUseCase(repo).execute(adminSession(), BC_TAX.copy(gstRate = "5"))
        }
        assertEquals(0, repo.taxWrites)
    }

    @Test
    fun execute_allowsAShopThatChargesNoTax() = runTest {
        val repo = FakeCompanySettingsRepository()
        UpdateTaxConfigUseCase(repo).execute(
            adminSession(),
            TaxConfig(gstEnabled = false, pstEnabled = false),
        )
        assertEquals(1, repo.taxWrites)
    }

    // ── Change log ──────────────────────────────────────────────────────────────

    @Test
    fun observeChanges_passesTheLimitThrough() = runTest {
        val repo = FakeCompanySettingsRepository()
        repo.changes.value = listOf(
            CompanySettingsChange("c1", "GST rate", "5%", "6%", "a1", "Ada", 1L),
        )
        val log = ObserveSettingsChangesUseCase(repo).execute(adminSession(), limit = 10).first()
        assertEquals(10, repo.lastLimit)
        assertTrue(log.single().field == "GST rate")
    }
}
