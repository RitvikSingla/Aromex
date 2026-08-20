---
name: Feature / Task ticket
about: Print a party's statement as a professional PDF — date range, paging, per-row notes toggle
labels: []
---

## 📖 Story / Why

A shop owner chasing payment needs to hand or send a customer **a statement**: what they owed at the
start of the period, every movement since, what they owe now, and how old the debt is. Today that
history is only on screen — there is no way to print it, email it, or put it in front of someone.

The Contacts screen already shows the statement (ticket #91, and the search / date-range / sort
controls added since). This ticket turns it into a document.

## 🧭 Context

### What already exists

- `EntityLedgerRepository.getStatement(externalId, from, to, page, limit)` → `AccountStatement`
  (`rows`, `closingBalance`, `page`, `totalRows`, `hasMore`). Each `LedgerRow` carries
  `date`, `description`, `postingType`, `debit`, `credit`, `balance`, `transactionId`.
- `EntitiesViewModel` already holds `statementFrom` / `statementTo` and a
  `moneyByTransaction: Map<String, MoneyEntry>` built from the app's own money entries — that map is
  how a row is traced back to the record that created it.
- Humble Ledger orders the ledger by **transaction date**, so a backdated sale sits where it belongs.

### The engine side is being handled — you do not touch it

The PDF is rendered by the Humble Bill Engine, exactly like an invoice. **The manager is creating a
new `billApps/aromex-statement` entry, its template, and the two rendering helpers it needs.** Do not
open the Bill Engine, its templates, or AWS. Your job is to gather the data, call a Cloud Function
with the payload contract below, and show the result.

**The engine is unauthenticated and may only be reached from a Cloud Function** — never from a
device. So this needs a **new callable**, `renderStatement`, alongside the existing `voidSale` /
`reverseStockBatch` callables. Desktop calls the callable; the callable calls the engine.

### Payload contract (live and verified — the template is built against exactly this)

The engine expects an **envelope**: `{ appId, data: { … } }`. Everything below goes inside `data`.
`buildInvoicePayload` in `syncWorker.ts` already returns that shape (`{ appId: 'aromex', data }`) —
copy it. Sending the fields flat renders a blank statement, silently.

```jsonc
{
  "appId": "aromex-statement",
  "data": {
  // Seller block — same source and shape as the invoice (companySettings/profile)
  "sellerName": "…", "sellerAddress": "…", "sellerContact": "…", "sellerPhone": "…",
  "sellerTaxLine": "GST/HST No: …",          // omit when the shop has no tax number
  "logoUrl": "…",
  // The party
  "customer": { "name": "…", "address": "…", "phone": "…", "email": "…" },
  "customerTaxLine": "GST/HST No: …",        // omit when absent
  // Period + when it was produced, formatted in the shop's timezone
  "periodFrom": "1 Jan 2026", "periodTo": "31 Mar 2026",
  "issueDate": "1 Aug 2026, 2:15 PM PDT",
  // Summary (decimal strings, no currency symbol — the engine adds it)
  "openingBalance": "1200.00", "totalDebits": "5400.00",
  "totalCredits": "4100.00", "closingBalance": "2500.00",
  // Aging buckets, oldest last. Omit the whole array when nothing is outstanding.
  "agingBuckets": [
    { "label": "0–30 days", "amount": "500.00" },
    { "label": "31–60 days", "amount": "1000.00" },
    { "label": "61–90 days", "amount": "0.00" },
    { "label": "90+ days",  "amount": "1000.00" }
  ],
  // Every row in the range, oldest first. `note` present only when the toggle is on.
    "statementRows": [
      { "date": "12 Feb 2026", "description": "Payment for INV-000042",
        "note": "paid by cheque", "debit": "", "credit": "700.00", "balance": "1800.00" }
    ]
  }
}
```

**Send plain decimal strings** — no currency symbol, no thousands separators. The engine formats
and prefixes them (`"1200.00"` renders as `$1,200.00`), so the summary and the row amounts match.
An empty string for `debit`/`credit` renders an empty cell, not `$0.00`.

**This has been rendered end to end against the live engine** — the template, its config
(`billApps/aromex-statement`) and the two rendering helpers are deployed. A payload exactly like
the above produces a correct one-page statement today, so you can build against it immediately
rather than waiting on anything.

`statementRows` and `agingBuckets` are **arrays** — the engine renders them through helpers built
for this. Everything else is a flat string. Omit a key entirely rather than sending `""` when a
value is absent, so nothing leaves a stray label. Row text is escaped by the engine; never send
HTML.

### The three data problems to solve

**1. You need every row in the range, not one page.** `getStatement` returns 50 at a time. Loop
`page` until `hasMore` is false, concatenating rows. **Cap it** — 2000 rows — and if the cap is hit,
tell the user plainly ("this period has more than 2000 entries — narrow the date range") rather than
printing a silently truncated statement.

**2. The opening balance is not in the response.** Don't compute it from the first row — HL's signed
arithmetic isn't reproducible on the client. Instead make **one extra call**:
`getStatement(externalId, from = null, to = <the day before `from`>, page = 1, limit = 1)` and read
its `closingBalance`. That is the balance as at the day before the period, computed by HL itself. If
`from` is null (an all-time statement) the opening balance is `"0.00"`.

**3. Aging is computed from the money itself — never from pending invoices.**

Do **not** use HL's `/receivables`. It ages unpaid *invoices*, which is a different quantity from
what the party actually owes: it ignores unapplied credits and every non-invoice movement, so its
buckets would not add up to the closing balance printed above them. Two numbers on one page that
don't reconcile is worse than no aging at all.

Age the **balance**, using the ledger rows you already fetched:

1. Work out each row's effect from **HL's own running balance**: `delta = balance(row) −
   balance(previous row)`, with the opening balance as the "previous" for the first row. A positive
   delta increases what they owe (a charge); a negative one reduces it (money received). Deriving
   the direction this way means you never have to reason about debit/credit sign conventions or
   account types — HL has already done it.
2. Seed a FIFO queue with the **opening balance** as the oldest charge, dated the day before the
   period. Skipping this is the classic way to get buckets that don't sum to the balance.
3. Walk the rows oldest-first. A charge is pushed onto the queue with its date. Money received
   consumes from the **front** of the queue — oldest debt settled first — splitting the front entry
   when it only partly covers it.
4. What remains on the queue is the outstanding balance, each piece carrying the date it arose.
   Bucket by age at the statement's `to` date: 0–30 / 31–60 / 61–90 / 90+.

**The buckets must sum to `closingBalance` exactly.** Assert it in a test — that identity is the
whole reason for doing it this way.

If the party is **in credit** (they owe nothing; the closing balance is in their favour), there is
nothing to age: omit the aging block entirely rather than printing four zeros.

### The notes toggle

"Include notes" is **off by default** — a customer copy shouldn't carry your staff's shorthand.

When on, each row gains the free-text note typed when the record was created:

- **Money entries** — already available via `moneyByTransaction[row.transactionId]?.note`.
- **Sales** — a sale stores `note` and its HL id as `hlSaleId`. You'll need a second index,
  sales-by-`hlSaleId`, to reach it. Query the sales for this customer over the range once and index
  them; don't fetch per row.
- Anything with no matching record simply has no note. That's normal — HL's own `description` still
  carries the row.

## 🔑 Access & prerequisites

- **Repo:** `Aromex-KMP`. Desktop + `sharedLogic` + `firebase/functions`.
- **Firebase project:** `aromex-june-2026`. Sign-in credentials: **ask the manager via the team
  password manager** — never in the issue, a commit, or a handoff.
- **Cloud Functions deploy:** needed for the new callable. Deploy **by name**:
  `firebase deploy --only functions:renderStatement`. Never a bare `--only functions`, and **never
  `--force`** — that has already deleted a colleague's function on this project once.
- **Bill Engine URL:** already in the functions config as `BILL_ENGINE_URL`; reuse
  `billEngine.ts`'s `renderInvoice(...)` client as-is (it just POSTs a payload and returns a URL —
  the name is historical, it is not invoice-specific).
- **AWS / Bill Engine templates:** **not required and out of bounds.** The manager owns that side.

## ✅ Scope / What to build

**Cloud Functions**
- `renderStatement` callable: takes `{ entityId, from, to, includeNotes }`, re-checks the caller has
  `profiles: view`, assembles the payload above (seller block from `companySettings/profile`, buyer
  from the entity), calls the engine, returns `{ url }`. Errors surface as `HttpsError` with a
  readable message, matching `voidSale`'s shape.
- Dates formatted in the shop's timezone with the existing `formatIssueDate` / `isoDateInZone`.

**Shared (`sharedLogic`)**
- A `StatementDocument` model for the assembled data, and a `BuildPartyStatementUseCase` that does
  the paging loop, the opening-balance call, and the aging bucketing — gated on `profiles: view`.
  This is logic, so it does **not** live in the UI.

**Desktop**
- On the contact detail, beside the existing statement controls: a **Print statement** action that
  opens a small dialog — date range (default: the range already selected, else last 3 months), an
  **Include notes** switch (off), and Generate.
- Progress while it renders; on success open it in the existing PDF viewer with Share / Download,
  reusing `PdfBillView` and `DetailPdfActions` from Sales History rather than new ones.
- Errors inline in the dialog, in the screen's existing style.

**Docs**
- `docs/SCHEMA.md`: a short section on the statement document and the `aromex-statement` payload
  contract, so the next person doesn't have to reverse-engineer it from the template.

## 🎯 Acceptance Criteria

- [ ] A statement for a customer with **120+ entries** in the range contains **every** row — not the
      first 50 — and the PDF paginates with the column headers repeating on each page.
- [ ] `openingBalance + totalDebits − totalCredits == closingBalance` exactly, for a range that
      starts mid-history (i.e. with a non-zero opening balance).
- [ ] The closing balance on the PDF equals the closing balance shown on screen for the same range.
- [ ] **Include notes off** (default): no note text anywhere in the PDF.
- [ ] **Include notes on**: a money entry's note and a sale's note both appear under their row; a row
      with no note shows nothing (no empty line, no dangling label).
- [ ] **Aging buckets sum to `closingBalance` exactly**, verified by a test over several histories
      (including one where a payment partly covers the oldest charge, and one where the opening
      balance is still partly unpaid).
- [ ] Aging is derived from the ledger movements, **not** from `/receivables` — a customer with an
      unapplied credit still ages correctly.
- [ ] A party in credit shows **no** aging block, not four zeros.
- [ ] An all-time statement (no `from`) shows an opening balance of `0.00`.
- [ ] A range with no activity produces a valid statement showing the opening balance, no rows, and
      an equal closing balance — not an error and not a blank page.
- [ ] Over 2000 rows: the user is told to narrow the range; nothing is silently truncated.
- [ ] A user without `profiles: view` cannot generate one (re-checked in the callable, not just
      hidden in the UI).
- [ ] `:sharedLogic:jvmTest`, `:desktopApp:test` and `firebase/functions` `npm test` all pass, with
      tests for the paging loop, the opening-balance call and the FIFO aging.

## 🚫 Out of scope

- Any change to the Bill Engine, its templates, its Lambda, or AWS — the manager owns that.
- Emailing the statement (Share/Download only for now).
- Statements for suppliers' purchase/commission notes — customer sales + money entries only.
- A statement covering more than one party at once.
- Scheduled or automatic statement runs.

## 🔗 Dependencies

- **None.** The engine side is already built, deployed and verified: the Lambda helpers,
  `templates/aromex/statement.html` and `billApps/aromex-statement` are all live. You can render a
  real statement PDF from day one.

## 📚 References

- `sharedLogic/.../repository/EntityLedgerRepository.kt` — `getStatement`, and why the running
  balance must come from HL
- `desktopApp/.../ui/entities/EntitiesViewModel.kt` — `statementFrom/To`, `moneyByTransaction`
- `desktopApp/.../ui/entities/PartyStatementSection.kt` — the on-screen statement
- `firebase/functions/src/billEngine.ts` — the engine client (reuse as-is)
- `firebase/functions/src/syncWorker.ts` — `buildInvoicePayload` for the seller/buyer block shape,
  `formatIssueDate` / `isoDateInZone` for timezone-correct dates
- `firebase/functions/src/index.ts` — `voidSale` / `reverseStockBatch` for the callable shape
- `desktopApp/.../ui/sales/history/PdfBillView.kt` + `DetailPdfActions` — the viewer and share/download

## 🖼️ UI standards

*No design attached — match the existing Contacts screen and reuse its components. The dialog should
look like the other dialogs in the app (see the reverse-batch and void-sale dialogs).*

- [ ] **Reuse, don't re-style** — existing field shells, the date-range control already on the
      statement, `PrimaryButton`, the shared dialog shell. No one-off colours or sizes.
- [ ] **Light and dark themes** both verified; every colour from a token defined in both.
- [ ] **Native components** — a platform switch for the notes toggle, not a hand-rolled control.
- [ ] **Responsive:** the dialog fits a small window and its body **scrolls** rather than pushing the
      buttons off-screen; the desktop window still reflows at its minimum size.
- [ ] **Keyboard:** Tab order through range → toggle → Generate; Esc cancels; Enter submits when the
      form is valid.
- [ ] **Truncation:** a long party name or note ellipsizes on screen; in the PDF it wraps.
- [ ] **States:** loading (generating), empty (no activity in range), error (inline, readable), and
      disabled (while generating) all defined.
- [ ] **Accessibility:** labels on the range and the toggle; logical focus order; adequate contrast;
      layout survives the largest font scale.
- [ ] **i18n:** every new string through `Strings` + `EnglishStrings` — no literals in the UI.
- [ ] **Architecture (`/kmp-arch`):** paging, opening balance and aging live in a shared use case,
      **not** the UI. The engine is called only from a Cloud Function, never from a device.

## 🤖 Kickoff prompt (paste into Claude Code)

```
/start-ticket <#>
```

The engine side is already live — POST the sample payload above to the bill-engine URL yourself and
look at the PDF before you write anything, so you know exactly what you're feeding. Then start with
the shared use case (paging loop → opening balance → FIFO aging) and its tests; the UI is the easy
part once that is right.
