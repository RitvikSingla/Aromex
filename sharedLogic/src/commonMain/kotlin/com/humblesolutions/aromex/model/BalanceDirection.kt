package com.humblesolutions.aromex.model

import com.humblesolutions.aromex.util.Money

/**
 * The direction of a party's single netted HL balance.
 *
 *  - [RECEIVABLE] — they owe us   (HL balance positive, shown green)
 *  - [CREDIT]     — we owe them   (HL balance negative, shown red)
 *  - [SETTLED]    — even          (HL balance zero, shown neutral)
 *
 * HL returns one signed decimal string; direction is derived from its sign.
 * Our vocabulary is RECEIVABLE/CREDIT/SETTLED (per Brief #25). HL's
 * opening-balance endpoint uses PAYABLE for "we owe them" — map CREDIT ↔ PAYABLE
 * at the HL request boundary (ticket #27), not here.
 */
enum class BalanceDirection {
    RECEIVABLE,
    CREDIT,
    SETTLED;

    companion object {
        /**
         * Derives the direction from HL's signed balance string
         * (e.g. "500.00" → RECEIVABLE, "-500.00" → CREDIT, "0.00" → SETTLED).
         */
        fun fromBalance(balance: String): BalanceDirection = when (Money.signOf(balance)) {
            1 -> RECEIVABLE
            -1 -> CREDIT
            else -> SETTLED
        }
    }
}
