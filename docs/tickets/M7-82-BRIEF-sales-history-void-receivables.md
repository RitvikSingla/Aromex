## 🎯 What we're building & why

Sales are recorded and invoiced correctly today — and then become **unreachable**. The Sale-complete
dialog is the only place a sale or its PDF is ever shown, and it is destroyed the moment the cashier
clicks *New sale*. Right now nobody can answer "what did I sell yesterday?", a customer can't get a
second copy of their receipt, an invoice that failed at the counter and was later repaired by the
reconcile sweep can never be opened by anyone, and a sale rung up by mistake stays on the books
forever with the phone marked SOLD.

M7 gives the shop a way back into its own sales: **find them, open them, undo them, and see who still
owes money.**

## 👤 Who it's for

The person behind the counter looking up a past sale, and the owner asking "who owes me?" and "cancel
that one, it was a mistake."

## ✅ What it must do (capabilities)

1. **Find any past sale** — search by customer name, IMEI/serial, or invoice number; filter by date
   range and by "still owes money". Newest first.
2. **Open a sale** and see everything it recorded: lines, prices and discounts, tax, payment split,
   balance, note, buyer, sync + invoice state.
3. **Reach the invoice** from any past sale — View / Print / Copy link / Share, and Retry when
   issuance failed. This is what makes M6 usable.
4. **Void a sale that shouldn't exist** — money reversed, tax reversed, cost reversed, the phone back
   on the shelf, the customer's balance back where it was.
5. **See outstanding receivables** — who owes what, how old it is, what's overdue.

## 🌟 What "good" looks like

- The counter can answer a customer's "can I get my bill again?" in under ten seconds.
- Voiding a mistake takes one admin, one reason, and leaves the books provably square — the trial
  balance nets to exactly where it was before the sale.
- The receivables number in the app is the *same* number Humble Ledger would give an accountant,
  because it came from Humble Ledger.

## 🚫 Non-negotiables

- **A void is a reversal, never a deletion.** An issued invoice is a tax document: its number stays
  in the sequence, the original journal entries are never modified, and the cancellation is visible.
  "As if it never happened" means *net zero*, not *no trace*. A gap in the invoice numbers is an audit
  flag; a cancelled invoice is normal.
- **Voiding is admin-only and always records who, when, and why.** No silent, unattributed voids.
- **Receivables are read from HL, never recomputed locally.** If the app does its own arithmetic it
  will eventually disagree with the ledger, and then nobody knows which is right.
- Money stays decimal **strings** end to end. No floats, ever.
- The client may never write `invoice*` fields, and may never post to HL directly — the Cloud Function
  owns both, as it does today.

## 🧭 Technical steers

**Verified against live HL (2026-07-31) — these are measured, not assumed:**

- `POST /api/v1/invoices/{id}/cancel` posts a **complete mirror** of the sale transaction. Measured on
  a real 5-leg sale: it reversed AR, Sales Revenue, GST Payable **and the COGS/Inventory pair**. The
  API docs enumerate only the three revenue legs — that description is incomplete. **Do not reverse
  COGS separately; you would double-reverse it and silently corrupt inventory valuation.**
- Cancelling also records a `CreditNote` of type `CANCELLATION`, sets the invoice `CANCELLED`, and
  drops it out of receivables (confirmed: the probe invoice is absent from `GET /receivables`).
- If the customer had paid, cancelling leaves them holding a **credit** equal to `amountPaid`;
  `POST /api/v1/refunds` returns that money (`DR AR / CR Cash|Bank`). Idempotent on
  `(appId, sourceId)` like everything else we post.
- `GET /api/v1/receivables` returns non-cancelled invoices with `outstanding > 0`, oldest-first, each
  row carrying `invoiceNumber, customerId, customerName, issueDate, dueDate, total, amountPaid,
  totalRefunded, outstanding, ageInDays, isOverdue, daysOverdue`. The receivables view is essentially
  a render of this — no local aggregation.

**App-side steers:**

- **Sales are the first unbounded collection in this app.** Every other screen observes-everything into
  a cache; that pattern will not survive a year of sales. This one pages (newest-first) and queries the
  backend. Do not load all sales into memory.
- **IMEI search must not scan the `lines` array.** Firestore cannot match a field inside an array of
  maps. Go through the serial instead: `serials` where `imei == X` → `serial.saleId` → load that sale.
  (`imeiIndex` is deleted at sale time, so it is not available for this.)
- Compound queries need composite indexes in `firestore.indexes.json`; there are currently **none** for
  `sales`.
- Firestore rules **already** allow `get`/`list` on `sales` for `sales: view` — no rules change is
  needed to read. Voiding *does* need a rules and permission change: today a client may never update a
  sale doc at all.
- Reuse the invoice row from #77 rather than rebuilding it — same states, same actions, same strings.

## 🧊 Happy to defer

- **The credit-note PDF.** HL records the CreditNote; the customer-facing document comes later, once
  we see how often voids actually happen. The bill-engine template for it is ours to build, not the
  dev's.
- **Partial returns / refunds** (one line of a multi-line sale coming back). Voiding is all-or-nothing
  in v1.
- **Editing a past sale.** Void and re-ring instead.
- **Payment collection** ("customer pays down their balance") — the receivables view will make the gap
  obvious, and it is the natural next milestone.
- Statements, aging buckets, and exports.

## 📎 References

- M6 invoicing: #76 (issuance), #77 (UI), #80 (walk-in/timezone fixes) — all merged.
- `docs/SCHEMA.md` → `sales/{saleId}`; `firebase/functions/src/syncWorker.ts` (`issueSaleInvoice`,
  `retryInvoiceCore`); `firebase/functions/src/hl.ts` (gateway token + HL client).
- HL OpenAPI: `https://ledger.humblesolutions.in/docs/json` (56 endpoints, v2.0.0).
