> Brief: #82 · Milestone M7 · **Cloud Function + shared + Desktop UI** · depends on #83

## 📖 Story / Why

A sale rung up by mistake is permanent today. The money is in the ledger, the tax is accrued, the cost
is expensed, the phone is marked SOLD and can never be sold again. The only remedy is editing
Firestore by hand, which leaves Humble Ledger wrong.

This ticket makes a mistake undoable: **money reversed, tax reversed, cost reversed, phone back on the
shelf, customer's balance back where it was.**

## ⚖️ Void means reverse, not delete — read this first

Do **not** delete the sale, the invoice, or the ledger entries.

An issued invoice is a tax document. Its number must stay in the sequence (a gap is an audit flag),
and a ledger is append-only: you cancel a transaction by posting its mirror, never by editing it. The
customer-visible outcome is identical — nets to zero, nothing owed, unit back in stock — but the trail
survives. A cancelled invoice is a normal thing for an accountant to see. A missing one is not.

## 🔬 Verified HL behaviour (measured 2026-07-31 — build on this, don't re-derive it)

A real 5-leg sale was posted to the Aromex dev company and cancelled. Observed:

```
SALE  (5 legs)                          REVERSAL (5 legs, posted by cancel)
  DEBIT  105  Walk-in Customer            CREDIT 105  Walk-in Customer
  CREDIT 100  Sales Revenue               DEBIT  100  Sales Revenue
  CREDIT   5  GST Payable                 DEBIT    5  GST Payable
  DEBIT   60  Cost of Goods Sold          CREDIT  60  Cost of Goods Sold
  CREDIT  60  Inventory                   DEBIT   60  Inventory
```

- **`POST /api/v1/invoices/{id}/cancel` reverses everything, COGS pair included.** The API docs list
  only the three revenue legs — that description is incomplete. **Do not reverse COGS yourself: you
  would double-reverse it and silently corrupt inventory valuation.** One call.
- It sets the invoice `CANCELLED`, records a `CreditNote` of type `CANCELLATION`, stamps
  `cancelledAt` / `cancelledById` / `cancelReason`, and drops the invoice out of `GET /receivables`
  (verified absent).
- `cancel` takes `{ reason }` and requires it.
- **Payments are separate transactions** (`syncSale` posts one `/payments` per non-zero method), so
  cancel does *not* return the customer's money — it leaves them holding a credit equal to
  `amountPaid`. `POST /api/v1/refunds` returns it: `{ invoiceId, amount, reason, paymentAccountId?,
  appId, sourceId }`, journalling `DR AR / CR Cash|Bank`. Refundable cap is
  `amountPaid − totalRefunded`. Idempotent on `(appId, sourceId)`.

## 🎯 What you're building

**Refunds mirror the original payment split.** Paid $200 cash + $100 card → one refund of $200 against
the **Cash** account and one of $100 against **Bank**, using `paymentAccountId` to target each, exactly
inverting what `syncSale` posted (cash → Cash, card → Bank, bank → Bank). The books land precisely
where they started. Never ask the cashier where the money goes.

**Admin-only, with a trail.** Voiding needs `role == 'admin'` — not `sales: manage`. Record who, when
and why on the sale doc, and pass the same reason into HL's `cancelReason` so the ledger and the app
tell the same story months later. **The CF must verify admin server-side** by reading
`users/{uid}.role`; do not trust the client, because Desktop's Admin SDK bypasses rules entirely.

**Any sale can be voided, but a reason is required.** No time limit — a mistake found late is still
worth fixing, and blocking it leaves the books wrong for longer.

### Transport — mirror #77
Same split, same reasons: mobile calls a new `voidSale` **callable**; Desktop's Admin SDK writes the
request onto the sale doc and `onSaleWrite` edge-triggers on it. Both funnel into one idempotent
`voidSaleCore`, exactly as `retryInvoice` / `invoiceRetryRequestedAt` both funnel into
`retryInvoiceCore`.

### Sale doc additions
```
status:            "COMPLETED" | "VOIDED"        // VOIDED is new
voidStatus:        "PENDING" | "DONE" | "FAILED" | null   // dual-write spine, like syncStatus
voidReason:        "..." | null                  // required, typed by the admin
voidRequestedBy:   "<uid>" | null                // CF re-checks this uid is an admin
voidRequestedAt:   <Timestamp> | null            // Desktop's edge-trigger
voidedAt:          <Timestamp> | null            // CF-owned
voidError:         "..." | null                  // CF-owned
hlVoidTxnId:       "..." | null                  // the REVERSAL transaction
hlRefundIds:       ["...", ...] | null           // one per payment method refunded
```
Follow the existing spine: client sets the request fields and `voidStatus: PENDING`; the CF owns
everything after. Rules must let an **admin** set exactly those request fields on an existing sale (a
client may currently never update a sale doc at all) and never the CF-owned ones.

### Restoring stock
In **one transaction**, for every `kind: "INVENTORY"` line: serial → `IN_STOCK`, clear `saleId`,
re-create `imeiIndex/{imei}`, and set the sale `status: VOIDED`. All-or-nothing — the #58 lesson, same
as the sale commit itself.

**Guard the IMEI index.** `imeiIndex/{imei}` was deleted when the unit sold, so someone may have
re-added that handset in the meantime. If the index entry exists and points at a *different* serial,
do **not** overwrite it — fail the void with a message naming the IMEI. Silently repointing the index
would make two serials claim one handset.

## 🧠 Edge cases that decide whether this is correct

| Situation | Behaviour |
|---|---|
| Sale never reached HL (`syncStatus: PENDING`/`FAILED`) | Nothing to cancel. Restore stock, mark VOIDED, skip HL. Must not error. |
| Invoice never issued (`invoiceStatus: PENDING`/`FAILED`) | No invoice to cancel — but the **sale transaction** still exists in HL. Reverse it via `POST /transactions/{id}/reverse` using `hlSaleId`'s transaction. Cancel is invoice-scoped; this path isn't. |
| `amountPaid == 0` | Cancel only. No refunds. Customer's AR returns to zero on its own. |
| Partially paid | Cancel, then refund exactly `amountPaid`, split by method. |
| Already VOIDED | No-op, return success. The whole path is idempotent and gets retried. |
| CF retried mid-way (HL cancelled, Firestore not yet) | Deterministic `sourceId`s make the HL calls no-ops on replay; `voidStatus` drives the resume. Never double-refund. |
| Unit re-added under the same IMEI | Fail with a clear message (above). |
| Invoice PDF already in S3 | Stays reachable — it is a real document that was really issued. The UI shows VOIDED alongside it. Do not attempt to delete it from S3. |

## ✅ Scope

- `firebase/functions`: `voidSaleCore` (HL cancel/reverse + split refunds + stock restore, idempotent);
  `voidSale` callable (admin-checked); `onSaleWrite` edge-trigger on `voidRequestedAt`; `hl.ts` gains
  `cancelInvoice`, `refundPayment`, `reverseTransaction`.
- `sharedLogic`: `SalesRepository.voidSale(saleId, reason)`; `VoidSaleUseCase` (gates on admin);
  `SaleVoidState` model.
- `desktopApp`: void action on the #83 detail view — confirm dialog that **requires a typed reason**,
  states plainly what will be reversed, and is not dismissible by accident; VOIDED badge; the sale
  stays visible in history.
- `firebase/firestore.rules` + `docs/SCHEMA.md`: the fields above.
- Android/iOS: render VOIDED state read-only. **No void action on phones in v1.**
- Tests: functions tests for every row of the table above; Desktop VM tests for gating, the required
  reason, and the optimistic/settled state machine.

## 🎯 Acceptance Criteria

1. Voiding an unpaid, synced, invoiced sale: HL invoice `CANCELLED`, a 5-leg REVERSAL posted, customer
   AR back to zero, every unit `IN_STOCK` with its `imeiIndex` restored, sale `VOIDED`.
2. Voiding a paid sale additionally refunds `amountPaid`, **split across the same accounts the payment
   used**, and the customer ends with a zero balance — not a credit.
3. **Trial balance before the sale == trial balance after the void.** Verify on the dev company and
   paste the numbers. This is the acceptance criterion that matters.
4. COGS and Inventory are reversed exactly once. Show the reversal transaction's legs.
5. A non-admin cannot void — blocked in the use case, and blocked again in the CF even when the
   request is written straight to the doc with the Admin SDK.
6. A void with no reason is rejected client-side and server-side.
7. Every row of the edge-case table behaves as stated, each with a test.
8. Re-running a void is a no-op: no second reversal, no second refund.
9. `:desktopApp:test`, `:sharedLogic:jvmTest`, functions tests all green; Android + iOS compile.

## 🚫 Out of scope

- **The credit-note PDF** — deferred by decision; HL records the CreditNote, the customer-facing
  document comes later and the bill-engine template is ours, not the dev's.
- Partial/line-level returns · editing a sale · restocking fees · voiding a *purchase*
- Void from phones (read-only there in v1)

## 🔗 Dependencies

- **#83** — the detail view this action lives on.
- #76/#77 — the CF patterns (`retryInvoiceCore`, the callable + edge-trigger dual transport).

## 📚 References

- `firebase/functions/src/syncWorker.ts` → `syncSale` (what to invert), `retryInvoiceCore` (the shape
  to copy), `issueSaleInvoice` (idempotency style)
- `firebase/functions/src/hl.ts` → gateway token + `hlPost`
- HL OpenAPI: `https://ledger.humblesolutions.in/docs/json`
- `docs/SCHEMA.md` → `sales/{saleId}`, `serials`, `imeiIndex`

## 🤖 Kickoff prompt

> Read brief #82 and this ticket, including the measured HL behaviour. Build voiding as a **reversal**:
> one `invoices/{id}/cancel` (which already reverses COGS — do not reverse it again), refunds mirroring
> the original payment split by account, and an atomic stock restore that refuses to clobber a
> re-used IMEI index. Admin-only, reason required, verified server-side. Prove it with a
> before/after trial balance on the dev company.
