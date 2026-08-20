package com.humblesolutions.aromex.model

/**
 * A company's tax configuration, read from `companySettings/profile.tax` (ticket #61).
 * One currency and one fixed tax regime per company (`CLAUDE.md`). Prices are
 * **tax-exclusive**; tax is added at checkout from this config. All rates are decimal
 * **strings** (e.g. `"0.05"`), never floats.
 *
 * The four supported regimes (Brief #60):
 * - **None** — `gstEnabled = false`, `pstEnabled = false` → no tax lines.
 * - **GST-only** — `gstEnabled = true`, `pstEnabled = false`, `isHST = false` → one "GST" line.
 * - **GST + PST** — `gstEnabled = true`, `pstEnabled = true` → two lines ("GST", "PST").
 * - **HST** — `isHST = true` (with `gstEnabled = true`, `gstRate` holding the HST rate) →
 *   one combined "HST" line; any PST is ignored.
 */
data class TaxConfig(
    val gstEnabled: Boolean = false,
    val gstRate: String = "0",
    val pstEnabled: Boolean = false,
    val pstRate: String = "0",
    val isHST: Boolean = false,
)
