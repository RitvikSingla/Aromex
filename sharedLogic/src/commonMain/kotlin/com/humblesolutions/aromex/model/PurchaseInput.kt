package com.humblesolutions.aromex.model

/**
 * A lightweight purchase captured at Add-Inventory time (ticket #58). Written to
 * Firestore as PENDING **inside the same transaction as the stock it pays for** (so a
 * purchase record and its inventory always commit together — no phantom stock the books
 * never learn about), then posted to Humble Ledger by the `onPurchaseWrite` Cloud
 * Function (HL credentials never touch a device; same dual-write spine as [EntityInput]).
 *
 * There is exactly **one** purchase per Add-Inventory batch: [totalCost] is the sum of
 * every reviewed unit's cost, so a multi-SKU batch still books a single HL entry.
 *
 * @property partyEntityId the Firestore entity id of the party bought from (its HL
 *   customer id is resolved server-side — it may not be synced yet). Defaults to
 *   [UNSPECIFIED_SUPPLIER_ID] when the cashier leaves the dialog at its defaults.
 * @property totalCost decimal string; the batch's total cost (booked as an ASSET in HL).
 * @property cashPaid decimal string; cash paid to the party now (`"0"` when none).
 * @property bankPaid decimal string; bank paid to the party now (`"0"` when none).
 * @property purchaseDate the **business date** — when the stock was actually bought, which may be
 *   earlier than now when old books are being entered. See [BusinessDate].
 */
data class PurchaseInput(
    val partyEntityId: String,
    val totalCost: String,
    val cashPaid: String,
    val bankPaid: String,
    val purchaseDate: Long,
)
