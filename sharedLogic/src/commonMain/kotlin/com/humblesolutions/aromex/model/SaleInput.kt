package com.humblesolutions.aromex.model

/**
 * A completed sale captured at checkout (ticket #61). Written to Firestore as a
 * `sales/{id}` doc `PENDING` **inside the same transaction that marks its units sold** (so
 * a unit is never sold without its sale record, or vice-versa — the #58 atomicity lesson),
 * then posted to Humble Ledger by the `onSaleWrite` Cloud Function (HL credentials never
 * touch a device; same dual-write spine as [PurchaseInput]).
 *
 * @property customerEntityId the Firestore entity id of the buyer — a named [Entity] or
 *   the reserved [WALK_IN_CUSTOMER_ID]. Its HL customer id is resolved server-side.
 * @property isWalkIn true when [customerEntityId] is the walk-in placeholder; a walk-in
 *   must pay in full (enforced by [RecordSaleUseCase]).
 * @property lines one or more cart lines (at least one).
 * @property saleDiscount a whole-sale discount applied after per-item discounts (`"0"`
 *   when none); kept so discounting is reportable.
 * @property payment the cash/card/bank split taken now.
 * @property note optional free-text sale note.
 * @property buyerName optional walk-in buyer name captured at checkout (ticket #77) — used as
 *   the invoice's Bill-To; blank/null → the PDF reads "Walk-in Customer". Ignored for a named
 *   customer (their [Entity] details are used).
 * @property buyerPhone optional buyer phone for the invoice's Bill-To. For a walk-in it's captured
 *   alongside [buyerName]; for a named customer it's prefilled from their [Entity]'s primary phone
 *   and editable per sale (mirrors [buyerTaxNumber]). Snapshotted onto the sale; null/blank → none.
 * @property saleDate the **business date** — when the sale happened, which may be earlier than
 *   now when old books are being entered. Defaults to now at the call site. See [BusinessDate].
 * @property taxInclusive when true, the typed line prices already contain tax (ticket #106) — the
 *   sale's totals are computed by the inverted path in `SaleCalculator`. Per-sale and defaults to
 *   false (tax-exclusive, as before), so it must be re-set on every new sale rather than sticking.
 */
data class SaleInput(
    val customerEntityId: String,
    val isWalkIn: Boolean,
    val lines: List<SaleLineInput>,
    val saleDiscount: String = "0",
    val payment: PaymentInput = PaymentInput(),
    val note: String? = null,
    val buyerName: String? = null,
    val buyerPhone: String? = null,
    val saleDate: Long,
    val taxInclusive: Boolean = false,
    /**
     * The buyer's tax/GST number as entered at checkout (ticket #106) — prefilled from the selected
     * customer's [Entity] but editable per sale, and snapshotted onto the sale so a later contact
     * edit can't alter an issued invoice. Set for a walk-in too (the cashier may type one for the
     * bill); null/blank when none.
     */
    val buyerTaxNumber: String? = null,
)
