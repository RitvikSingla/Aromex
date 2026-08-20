> Brief: #82 · Milestone M7 · **Desktop only** (phones are T2)

## 📖 Story / Why

A completed sale is visible for exactly as long as the Sale-complete dialog stays open. Click *New
sale* and it's gone — the sale, the invoice number, the PDF link, all of it. There is no screen in the
app that lists past sales and no repository method that reads more than one.

That makes M6's invoicing half-delivered: every PDF is sitting in S3 with a permanent public URL, and
nothing in the product can reach it. A customer asking for a second copy of their bill cannot be
served. An invoice that failed at the counter and was quietly repaired by the reconcile sweep five
minutes later is invisible to everyone, forever.

This ticket builds the way back in.

## 🎯 What you're building

A **Sales** screen on Desktop: a searchable, paged list of past sales on the left/top, and a detail
view of the selected sale showing everything it recorded — including the invoice row from #77, with
its actions and Retry, working exactly as it does on the Sale-complete dialog.

### The list
Newest first. Each row shows: date + time (in the shop's timezone), invoice number, customer name,
a short item summary (e.g. *iPhone 15 128GB +2 more*), grand total, amount paid, balance, and status
chips for sync and invoice state.

### Search & filters
- **Customer name** — free text
- **IMEI / serial** — exact
- **Invoice number** — exact (`INV-000042`)
- **Date range** — from/to
- **Only sales with a balance** — toggle

Filters combine. An empty search shows everything, newest first.

### The detail view
Every field the sale doc carries, laid out like the checkout summary the cashier already knows:
each line with label, IMEI, list price, unit price, line discount and net; sale discount; each tax
line with its name and rate; the payment split across cash/card/bank; amount paid and balance; the
note; buyer name/phone for walk-ins; who rang it up and when; sync status; and the invoice row.

## 🧠 Three things that will bite you

**1. Sales are the first unbounded collection in this app.** Every other screen (`entities`,
`serials`, `products`) observes the whole collection into a ViewModel cache. That is fine for a few
thousand rows and fatal for sales, which grow forever. This screen **pages** — `orderBy(createdAt,
desc).limit(N)` plus `startAfter(lastDoc)` — and filters run as **Firestore queries**, not as
client-side `.filter {}` over a full cache. If you find yourself loading every sale to filter it, stop.

**2. You cannot inequality-query `balanceRemaining` — it's a decimal string.** Money is stored as
strings everywhere (a hard project rule), and Firestore compares strings lexicographically:
`"100.00" < "90.00"` is *true*. Any "sales with a balance" filter built on string comparison is
silently wrong. Add a denormalized boolean to the sale doc at create time:

```
hasOutstandingBalance: boolean     // = balanceRemaining is not zero, evaluated with Money
```

Set it in the same transaction that writes the sale (client-side, where `balanceRemaining` is already
computed), document it in `docs/SCHEMA.md`, and allow it in the create rule. Filter on that.

**3. IMEI search cannot look inside `lines`.** `lines` is an array of *maps*, and Firestore's
`array-contains` only matches whole elements — there is no way to ask "any element whose `imei` field
equals X". Do not denormalize an `imeis: [...]` array just for this. Go through the serial, which
already points back:

```
serials where imei == X  →  serial.saleId  →  sales/{saleId}
```

`imeiIndex/{imei}` is **deleted** when a unit sells (that's the point of it), so it is not available
here — query the `serials` collection itself.

## ✅ Scope

- `sharedLogic`: `SaleSummary` + `SaleDetail` models; `SalesQuery` (filters + cursor);
  `SalesRepository.querySales(...)` / `getSale(saleId)` / `findSaleIdByImei(imei)`;
  `QuerySalesUseCase`, `GetSaleUseCase` (permission-gated on `sales: view`).
- `desktopApp`: `BackendSalesRepository` implementations (Admin SDK, paged);
  `ui/sales/history/SalesHistoryScreen.kt` + `SalesHistoryViewModel.kt`; nav entry.
- `firebase/firestore.indexes.json`: composite indexes for every filter combination that needs one
  (at minimum `customerEntityId + createdAt desc`, `hasOutstandingBalance + createdAt desc`,
  `createdAt desc` range scans). Deploy them — an unindexed query fails at runtime, not at build.
- `docs/SCHEMA.md`: document `hasOutstandingBalance`.
- `firebase/firestore.rules`: permit `hasOutstandingBalance` on create (boolean); it is client-owned,
  unlike the `invoice*` fields.
- Desktop VM tests: paging, each filter, the IMEI indirection, empty states, permission gating.

## 🖼️ UI standards (Definition of Done)

- Reuse the **inventory browse table** patterns from #55 — the auto-fitting column measurement, the
  hover-to-reveal on truncated cells, the header/row alignment. Do not invent a second table style.
- Reuse **`InvoiceRow`** from #77 verbatim where you can. Same states, same actions, same i18n keys.
- Theme tokens only — light **and** dark. No hardcoded colours or sizes.
- All new strings via i18n; all money via `MoneyFormat`; dates in the shop's `timezone`
  (`companySettings/profile.timezone`, added in #80).
- Empty states that say something useful: no sales yet vs. no sales *match your filters* are different
  messages.
- Loading a page must not blank the list — append, don't replace.

## 🎯 Acceptance Criteria

1. The Sales screen lists past sales newest-first and loads more as you scroll, without re-fetching
   what's already shown.
2. Searching a customer name, an IMEI, or an invoice number finds the right sale; the IMEI path works
   for a unit that has already sold (i.e. does not rely on `imeiIndex`).
3. Date-range and "has a balance" filters work, combine with search, and the balance filter is
   correct for amounts either side of a power of ten (e.g. a $90 balance and a $100 balance are both
   found — the string-ordering trap).
4. Opening a sale shows every recorded field, matching what the Sale-complete dialog showed.
5. The invoice row works from history: View / Print / Copy link on an ISSUED invoice, and Retry on a
   FAILED one, re-issuing exactly as it does at the counter.
6. A user with `sales: view` can read; a user with no sales permission sees nothing and cannot query.
7. Light + dark verified. Desktop VM tests green; `:desktopApp:test` and `:sharedLogic:jvmTest` pass.
8. Every composite index the screen needs is committed **and** deployed.

## 🚫 Out of scope

- Android/iOS (T2) · voiding (T3) · receivables (T4)
- Editing a past sale, partial returns, payment collection
- Exports, statements, printing a list

## 🔗 Dependencies

- #76 / #77 / #80 (merged) — invoice fields, `InvoiceRow`, `timezone`.
- #55 — the browse-table patterns to reuse.

## 📚 References

- `docs/SCHEMA.md` → `sales/{saleId}` (every field you'll render)
- `desktopApp/.../ui/sales/SalesScreen.kt` → `InvoiceRow`, `SaleCompleteDialog`
- `desktopApp/.../ui/inventory/InventoryScreen.kt` → column auto-fit + hover tooltips
- `desktopApp/.../data/BackendSalesRepository.kt` → Admin SDK patterns, `toSaleInvoice()`

## 🤖 Kickoff prompt

> Read brief #82 and this ticket. Build the Desktop Sales History screen: a paged, searchable list of
> past sales plus a detail view with the #77 invoice row. Mind the three traps called out above —
> pagination instead of a full-collection cache, a boolean for the balance filter because money is a
> string, and the serial indirection for IMEI search. Reuse the #55 table patterns and the #77 invoice
> row rather than rebuilding either. Commit and deploy the composite indexes.
