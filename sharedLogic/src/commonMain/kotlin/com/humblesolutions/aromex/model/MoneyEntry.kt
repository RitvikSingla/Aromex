package com.humblesolutions.aromex.model

/**
 * One movement of money between two accounts (ticket #90) — the rebuild of the legacy app's
 * transactions screen: *who gave, who got, how much, why, when*.
 *
 * **This record carries no balances.** Not on the parties, not as a "balance after" snapshot. The
 * legacy implementation stored a running balance on each party and mutated it with a read outside
 * any transaction, so a lost or duplicated write left a permanently wrong number with nothing to
 * check it against. Balances here are always read from Humble Ledger, derived from its journal
 * entries — which is what makes that entire class of drift impossible rather than merely unlikely.
 *
 * Same dual-write spine as sales and purchases: the client creates this doc [HlSyncStatus.PENDING]
 * and the `onMoneyEntryWrite` Cloud Function posts it to HL and flips it to
 * [HlSyncStatus.SYNCED]. HL credentials never touch a device.
 *
 * @property amount decimal **string**, always positive. Direction is carried by [from]/[to], never
 *   by a sign — a negative amount here would be ambiguous about which side it applied to.
 * @property entryDate the **accounting** date, which may be backdated (recording yesterday's
 *   payment this morning is ordinary). Distinct from [createdAt], which is when it was typed.
 * @property reversedByEntryId set when a later entry reverses this one. Entries are never edited
 *   or deleted — a mistake is corrected by posting its mirror, exactly as with a voided sale.
 */
data class MoneyEntry(
    val entryId: String,
    val from: MoneyAccountRef,
    val to: MoneyAccountRef,
    val amount: String,
    val note: String? = null,
    val entryDate: Long,
    val createdBy: String? = null,
    val createdAt: Long? = null,
    val syncStatus: HlSyncStatus = HlSyncStatus.PENDING,
    val hlTransactionId: String? = null,
    val hlSyncError: String? = null,
    val reversedByEntryId: String? = null,
    val reversesEntryId: String? = null,
) {
    /** True once HL has accepted it — the point at which it may be reversed. */
    val isSettled: Boolean get() = syncStatus == HlSyncStatus.SYNCED

    /** True when this entry has already been reversed, so it must not be reversed again. */
    val isReversed: Boolean get() = reversedByEntryId != null

    /** True when this entry *is* the correction of an earlier one. */
    val isReversal: Boolean get() = reversesEntryId != null

    /**
     * Reversing needs a settled, not-yet-reversed entry that isn't itself a reversal — HL refuses
     * to reverse a reversal, and reversing something HL never accepted would post a mirror of
     * nothing.
     */
    val canReverse: Boolean get() = isSettled && !isReversed && !isReversal

    /**
     * Whether this entry brought money into the business, took it out, or just shuffled it — the
     * only reading a shopkeeper wants. Double entry is how the books stay correct; it isn't how
     * anyone thinks about their own till.
     */
    val direction: MoneyDirection
        get() = when {
            from is MoneyAccountRef.Party && !to.isOwnAccount -> MoneyDirection.INTERNAL // party → party
            !from.isOwnAccount -> MoneyDirection.IN      // a party paid us
            to is MoneyAccountRef.Party -> MoneyDirection.OUT  // we paid a party
            else -> MoneyDirection.INTERNAL              // cash ↔ bank
        }

    /**
     * True when this entry should be hidden from the day-to-day views: a reversal, or the entry it
     * cancelled. Reversing something is how you say "this didn't happen" — showing both halves back
     * afterwards is an audit trail, and the ledger already keeps one.
     */
    val isCancelled: Boolean get() = isReversed || isReversal
}

/**
 * Which way money moved, from the business's point of view (ticket #90). Drives the green/red the
 * whole app reads by.
 *
 * [INTERNAL] covers a transfer that changes nothing overall — cash into the bank, or one party
 * settling another's account. Colouring those green or red would claim a gain or loss that never
 * happened.
 */
enum class MoneyDirection { IN, OUT, INTERNAL }
