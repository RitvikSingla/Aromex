package com.humblesolutions.aromex.model

/**
 * The reserved "Unspecified Supplier" party (ticket #58) — a real, visible [Entity]
 * used when a purchase is recorded without naming who it was bought from.
 *
 * Its Firestore doc id (which doubles as the HL `externalId`) is **fixed and
 * deterministic**, never generated: two near-simultaneous first-uses on different
 * devices target the same doc, so it can't be duplicated, and HL's customer-create is
 * idempotent on `externalId`, so the whole bootstrap composes safely.
 */
const val UNSPECIFIED_SUPPLIER_ID: String = "unspecified-supplier"

/** The stored (non-i18n) display name for the reserved [UNSPECIFIED_SUPPLIER_ID] party. */
const val UNSPECIFIED_SUPPLIER_NAME: String = "Unspecified Supplier"
