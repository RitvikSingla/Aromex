package com.humblesolutions.aromex.settings

import com.humblesolutions.aromex.model.TaxConfig
import com.humblesolutions.aromex.usecase.SettingsAudit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rate display and the change diff.
 *
 * The percent conversion must be **exact**. An earlier version routed it through
 * `Money.multiplyRate`, which rounds half-up to two decimals — correct for money, silently wrong
 * for a rate: 7.5% became 0.08, and Quebec's 9.975% QST became 0.10.
 */
class SettingsAuditTest {

    @Test
    fun percentRoundTrip_isExactAtAnyPrecision() {
        listOf("5", "7.5", "9.975", "12.125", "0", "100").forEach { percent ->
            val fraction = SettingsAudit.percentToFraction(percent)
            assertEquals("$percent%", SettingsAudit.asPercent(fraction), "round trip for $percent%")
        }
    }

    @Test
    fun knownRatesConvertCorrectly() {
        assertEquals("0.05", SettingsAudit.percentToFraction("5"))        // GST
        assertEquals("0.07", SettingsAudit.percentToFraction("7"))        // BC PST
        assertEquals("0.13", SettingsAudit.percentToFraction("13"))       // Ontario HST
        assertEquals("0.09975", SettingsAudit.percentToFraction("9.975")) // Quebec QST
        assertEquals("5%", SettingsAudit.asPercent("0.05"))
        assertEquals("9.975%", SettingsAudit.asPercent("0.09975"))
    }

    /** Trailing zeros are noise on a rate — 7.50% should read as 7.5%. */
    @Test
    fun trailingZerosAreTrimmed() {
        assertEquals("7.5%", SettingsAudit.asPercent("0.0750"))
        assertEquals("5%", SettingsAudit.asPercent("0.0500"))
        assertEquals("0%", SettingsAudit.asPercent("0"))
    }

    @Test
    fun diff_reportsOnlyWhatChanged() {
        val before = SettingsAudit.taxFields(TaxConfig(gstEnabled = true, gstRate = "0.05"))
        val after = SettingsAudit.taxFields(TaxConfig(gstEnabled = true, gstRate = "0.06"))

        val changes = SettingsAudit.diff(before, after)
        assertEquals(1, changes.size)
        assertEquals(Triple("GST rate", "5%", "6%"), changes.single())
    }

    /** Saving a form without touching it must leave nothing behind. */
    @Test
    fun diff_isEmptyWhenNothingChanged() {
        val fields = SettingsAudit.taxFields(TaxConfig(gstEnabled = true, gstRate = "0.05"))
        assertTrue(SettingsAudit.diff(fields, fields).isEmpty())
    }

    /** Turning GST off removes its rate row — reported, not silently dropped. */
    @Test
    fun diff_reportsAFieldThatNoLongerApplies() {
        val before = SettingsAudit.taxFields(TaxConfig(gstEnabled = true, gstRate = "0.05"))
        val after = SettingsAudit.taxFields(TaxConfig(gstEnabled = false))

        val fields = SettingsAudit.diff(before, after).map { it.first }
        assertTrue("GST" in fields, "the toggle itself changed")
        assertTrue("GST rate" in fields, "the rate row disappeared and should be recorded")
    }
}
