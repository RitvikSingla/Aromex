> Brief: #82 · Milestone M7 · **Desktop** · independent of #83–#85, can run in parallel

## 📖 Story / Why

The app can create credit — a named customer walks out owing money — but nowhere shows who owes what.
The owner's most basic question, *"who hasn't paid me?"*, has no answer in the product today.

Humble Ledger already knows. `GET /api/v1/receivables` returns every non-cancelled invoice with
`outstanding > 0`, oldest-first, with aging already computed. There are 17 such rows in the dev company
right now. This ticket renders them.

## ⚠️ The one rule: don't do the arithmetic

**Read receivables from HL. Never recompute them locally.**

It is tempting to sum `balanceRemaining` across the sales collection — it's right there, and it looks
equivalent. It isn't. Firestore knows nothing about payments recorded elsewhere, refunds, cancellations
or manual ledger adjustments, and the moment those diverge you have two numbers that disagree and no
way to say which is right in front of a customer. HL is the book of record; the app is a window onto
it. If the number looks wrong, it is wrong *in the ledger*, and that's where it gets fixed.

## 🔬 The endpoint (verified 2026-07-31)

`GET /api/v1/receivables` → rows of:

```
invoiceId, invoiceNumber, customerId, customerName,
issueDate, dueDate, status,
total, amountPaid, totalRefunded, outstanding,
ageInDays, isOverdue, daysOverdue
```

`outstanding = total − amountPaid + totalRefunded` (a refund re-opens the receivable). Cancelled
invoices are excluded — verified: a cancelled probe invoice is absent from the response.

## 🎯 What you're building

A **Receivables** screen on Desktop, in two levels.

**By customer (default):** one row per party — customer name, number of open invoices, total
outstanding, age of the oldest, and an overdue flag. Sorted by outstanding, largest first. This is the
"who owes me" answer, and the aggregation is a straight group-by of HL's rows — no money math beyond
summing what HL returned, using `Money`, never floats.

**Drill into a customer:** their open invoices, oldest first — invoice number, issue date, due date,
total, paid, outstanding, age, and an overdue badge. Clicking an invoice opens that sale in Sales
History (#83) when a matching sale exists — match on `invoiceNumber`. Some rows may have no local sale
(created directly in HL, or predating the app); show them anyway, without a link. Do not hide a
receivable just because the app can't find a sale for it.

**Summary strip:** total outstanding, total overdue, and the count of customers owing — the three
numbers an owner glances at.

## 🧠 Notes that will save you a day

- **This is an HL read, not Firestore.** It goes through the existing gateway-token path — the same one
  `EntityLedgerRepository` uses for balances. Do not add a new auth path, and do not put HL credentials
  anywhere near the client.
- **Money stays strings.** HL returns `"880.00"` as a string; keep it that way and total with `Money`.
  A `Double` here is a bug even when it looks right.
- **Dates are HL's, not Firestore's.** Render `issueDate`/`dueDate` in the shop's `timezone`
  (`companySettings/profile.timezone`, #80), the same as invoices.
- **Don't trust the row count to stay small.** Page or virtualize the drill-in list; a busy shop will
  accumulate hundreds of open invoices.
- `ageInDays` / `isOverdue` / `daysOverdue` are computed **by HL**. Render them; don't recompute them
  from dates, or the app and the ledger will disagree about what "overdue" means.

## ✅ Scope

- `sharedLogic`: `Receivable` + `CustomerReceivables` models; `ReceivablesRepository`;
  `ObserveReceivablesUseCase` (gates on `transactions: view`, matching how balances are gated today).
- Shared Ktor HL client: the `GET /receivables` call — this is the blessed shared-networking exception,
  same as the existing HL reads.
- `desktopApp`: `ui/receivables/` screen + ViewModel; nav entry; refresh action.
- Tests: grouping and totalling from a fixture payload (including a refund that re-opens a receivable,
  and a customer with several invoices), and the empty state.

## 🖼️ UI standards (Definition of Done)

- Theme tokens, light + dark. Reuse the #55 table patterns; don't invent a third table style.
- i18n for every string; `MoneyFormat` for money.
- Overdue is not colour-only — pair it with a label or icon, so it survives a colourblind reader and a
  greyscale print.
- Empty state that reads as good news ("nobody owes you anything"), not as an error.
- A visible failure state when HL is unreachable: this screen is useless-but-honest offline, and must
  never render a stale or zeroed total as if it were current.

## 🎯 Acceptance Criteria

1. The screen lists customers who owe money, largest outstanding first, with each customer's open
   invoice count and oldest age.
2. Drilling in lists that customer's open invoices oldest-first with per-invoice outstanding and
   overdue status.
3. Every number matches `GET /api/v1/receivables` exactly — spot-check three rows against a raw API
   response and paste the comparison.
4. A cancelled invoice never appears (void a sale from #85, if merged, and confirm it drops off).
5. A receivable with no matching local sale still renders, just without the link.
6. Totals are computed with `Money`; a grep for `toDouble()`/`Double(` in the new code returns nothing.
7. HL unreachable → an explicit error state, never a silent zero.
8. Light + dark verified; `:desktopApp:test` and `:sharedLogic:jvmTest` green.

## 🚫 Out of scope

- **Recording a payment against a receivable** — that's the next milestone, and the obvious follow-on.
- Aging buckets (30/60/90), statements, reminder emails, CSV export
- Phones (Desktop-first, like #83)
- Supplier/payable side

## 🔗 Dependencies

- None blocking. Links into #83's detail view when that merges; ship without the link if it hasn't.

## 📚 References

- `sharedLogic/.../repository/EntityLedgerRepository.kt` — the existing HL read path to follow
- `firebase/functions/src/hl.ts` — gateway token brokering
- HL OpenAPI: `https://ledger.humblesolutions.in/docs/json` → `GET /api/v1/receivables`

## 🤖 Kickoff prompt

> Read brief #82 and this ticket. Build the Desktop Receivables screen as a **render of HL's
> `/receivables`** — group by customer, drill into invoices, three-number summary strip. Do not
> recompute outstanding balances from Firestore; HL is the book of record. Money stays strings, and
> `ageInDays`/`isOverdue` come from HL rather than being re-derived.
