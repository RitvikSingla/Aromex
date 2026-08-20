package com.humblesolutions.aromex.model

/**
 * The reserved "Walk-in Customer" party (ticket #61) — a real, visible [Entity] used
 * when a sale is rung up without naming who it was sold to. The sell-side mirror of
 * [UNSPECIFIED_SUPPLIER_ID].
 *
 * Its Firestore doc id (which doubles as the HL `externalId`) is **fixed and
 * deterministic**, never generated: two near-simultaneous first-uses on different devices
 * target the same doc, so it can't be duplicated, and HL's customer-create is idempotent
 * on `externalId`, so the whole bootstrap composes safely. A walk-in sale must be paid in
 * full — no balance is ever carried on this anonymous party.
 */
const val WALK_IN_CUSTOMER_ID: String = "walk-in-customer"

/** The stored (non-i18n) display name for the reserved [WALK_IN_CUSTOMER_ID] party. */
const val WALK_IN_CUSTOMER_NAME: String = "Walk-in Customer"
