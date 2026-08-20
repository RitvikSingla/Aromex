package com.humblesolutions.aromex.model

/**
 * A party's current net balance, read live from Humble Ledger — never stored in
 * Firebase. [net] is the **absolute** decimal string; [direction] carries the
 * sign meaning (who owes whom). Money is a String, never a Double/Float.
 */
data class EntityBalance(
    val net: String,
    val direction: BalanceDirection,
)
