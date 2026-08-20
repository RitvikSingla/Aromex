package com.humblesolutions.aromex.model

/**
 * How a [CommissionRule]'s [CommissionRule.rate] is applied to a batch's units at a
 * location (ticket #97).
 *
 * - [PER_UNIT] — a fixed amount per phone: `count × rate` (e.g. `12 × "5.00" = "60.00"`).
 * - [PERCENT_OF_COST] — a fraction of the units' summed cost: `summedCost × rate`
 *   (rate is stored as a fraction, so 2% is `"0.02"`; `"14400.00" × "0.02" = "288.00"`).
 *
 * Both go through `Money.multiplyRate` — pure half-up decimal, never floating point.
 *
 * Wire form (Firestore) is the enum name verbatim, UPPERCASE.
 */
enum class RateKind {
    PER_UNIT,
    PERCENT_OF_COST;

    companion object {
        /** Parses a stored value; returns null for anything unrecognised. */
        fun fromWire(value: String): RateKind? =
            entries.firstOrNull { it.name == value.trim() }
    }
}
