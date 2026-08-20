package com.humblesolutions.aromex.model

/**
 * One recorded change to company settings — who changed what, when, and from what to what.
 *
 * Tax rates are the thing an accountant asks about a year later, and *"we think it changed some time
 * in August"* is not an answer. The ledger holds what each individual sale was actually taxed, but
 * reconstructing **when a rate changed** from thousands of sales is archaeology; this makes it a
 * lookup.
 *
 * Append-only. A settings change is a fact about the past, so it is never edited or deleted — the
 * Firestore rules enforce that too.
 *
 * @property field what changed, in plain words ("GST rate", "Business address") — stored rather than
 *   derived, so a later rename of a model property can't rewrite history.
 * @property oldValue the previous value as displayed, or null when nothing was set before.
 * @property newValue the new value as displayed.
 */
data class CompanySettingsChange(
    val changeId: String,
    val field: String,
    val oldValue: String?,
    val newValue: String,
    val changedBy: String,
    val changedByName: String? = null,
    val changedAt: Long,
)

/**
 * The editable half of `companySettings/profile` (the shop's identity on every invoice).
 *
 * Deliberately excludes `hlCompanyId`, `currency` and `timezone`: the first two are fixed at
 * provisioning and changing either would orphan every posting already made against them, and the
 * timezone decides the calendar date on issued invoices. Those stay a provisioning concern.
 */
data class CompanyDetails(
    val companyName: String,
    val legalName: String?,
    val taxNumber: String?,
    val businessAddress: String?,
    val contactEmail: String?,
    val contactPhone: String?,
)
