package com.humblesolutions.aromex.model

/**
 * An optional starting balance captured when a party is first created — e.g. they
 * already owed you money before you started using Aromex.
 *
 * [amount] is a **positive** decimal string; [direction] must be [BalanceDirection.RECEIVABLE]
 * (they owe you) or [BalanceDirection.CREDIT] (you owe them) — never SETTLED.
 * Posted once, server-side, as an HL opening-balance entry (ticket #27).
 */
data class OpeningBalance(
    val amount: String,
    val direction: BalanceDirection,
)
