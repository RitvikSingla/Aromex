package com.humblesolutions.aromex.model

/**
 * One side of a money movement (ticket #90) — *which ledger account* the money left or landed in.
 *
 * The legacy app modelled "the shop" as the magic party id `myself_special_id`, with its balance in
 * a differently-shaped document from everyone else's, so every operation branched on it. Here the
 * shop's own accounts are ordinary choices in the same picker: what the cashier selects **is** the
 * account, and there is no hidden mapping between the choice and where the money went.
 */
sealed interface MoneyAccountRef {

    /** A party (customer / supplier / middleman) — one of the unified `entities`. */
    data class Party(val entityId: String) : MoneyAccountRef {
        init {
            require(entityId.isNotBlank()) { "entityId is required" }
        }
    }

    /** The shop's cash drawer. */
    data object Cash : MoneyAccountRef

    /** The shop's bank account. Card settlements route here too (the #61 convention). */
    data object Bank : MoneyAccountRef

    /** True when this is one of the shop's own asset accounts rather than a party. */
    val isOwnAccount: Boolean get() = this !is Party

    /** Wire form for Firestore: `PARTY` / `CASH` / `BANK`. */
    val kind: String
        get() = when (this) {
            is Party -> KIND_PARTY
            Cash -> KIND_CASH
            Bank -> KIND_BANK
        }

    /** The party's entity id, or null for the shop's own accounts. */
    val entityIdOrNull: String?
        get() = (this as? Party)?.entityId

    companion object {
        const val KIND_PARTY = "PARTY"
        const val KIND_CASH = "CASH"
        const val KIND_BANK = "BANK"

        /**
         * Rebuilds a ref from its stored form. Returns null for anything unrecognised — a `PARTY`
         * with no entity id included — so a malformed document surfaces as a skipped row rather
         * than as an entry that silently points at the wrong account.
         */
        fun fromWire(kind: String?, entityId: String?): MoneyAccountRef? = when (kind?.trim()) {
            KIND_CASH -> Cash
            KIND_BANK -> Bank
            KIND_PARTY -> entityId?.takeIf { it.isNotBlank() }?.let { Party(it) }
            else -> null
        }
    }
}
