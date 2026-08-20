---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M5] Sales T1 — backend spine: atomic sale write + HL posting"
labels: []
assignees: []
---

**Brief:** #60

## 📖 Story / Why
The shop can hold customers and stock and knows what it paid — but it still can't do the one
thing it exists to do: **sell a phone**. This ticket builds the **backend spine** for that: the
shared logic + the atomic Firestore write that empties units from stock and records the sale,
**and** the Cloud Function that posts the sale to Humble Ledger (revenue, tax, cost-of-goods
against the Inventory asset, and the payment). No ViewModels, no UI — this is the correctness
heart that Tickets T2–T4 build the counter experience on top of. It's the payoff of Entities +
Inventory + the #58 asset booking.

## 🧭 Context
- **Two backends (see `CLAUDE.md`).** Operations live in the per-client **Firebase** (the sale
  record + inventory state change). Money lives in **Humble Ledger (HL)**, reached **only from
  the Cloud Function** — HL credentials never touch a device. This ticket writes both sides.
- **This is the exact mirror of #58** (Inventory→HL purchase). Reuse its machinery — do not
  reinvent it:
  - The **atomic write** pattern: `InventoryRepository.addStockBatchWithPurchase` co-commits
    stock + a `purchases/{id}` doc in **one Firestore transaction**. We follow the same lesson —
    mark-sold + `sales/{id}` co-commit in one transaction.
  - The **dual-write spine**: Firestore doc written `PENDING` → `onPurchaseWrite` CF posts to HL →
    `SYNCED`, with the `reconcile` scheduled sweep as the retry backstop
    (`firebase/functions/src/{index,syncWorker,hl}.ts`).
  - The **placeholder-party** idea: `UNSPECIFIED_SUPPLIER_ID` (deterministic id, lazy idempotent
    CF bootstrap, visible Entity). We add the **Walk-in Customer** the same way.
  - The **`getOrCreateAccount(name, type)`** helper and **`sourceId`** idempotency scheme
    (`purchaseSourceId(id, role)`).
- **HL API is verified live** (`https://ledger.humblesolutions.in/docs`, OpenAPI at `/docs/json`)
  and the account chart is known (see 🔑). The COGS trio + `taxLines[]` on `/sales` are among the
  6 HL changes already shipped (see the HL compatibility audit).
- **M4 model already supports the sale hook:** `serials/{serialId}` carries `status`
  (`IN_STOCK|RESERVED|SOLD`), `saleId`, `cost` (per-unit, decimal string), and its sold/archive
  path **deletes `imeiIndex/{imei}`** to release the IMEI. `entities/{entityId}` already reserves
  `isWalkIn`.
- **Tax is read nowhere yet.** `companySettings/profile.tax` exists in Firestore but no shared
  code reads it. This ticket adds that read (extends the `CompanySettings` read that already
  returns `hlCompanyId` + `currency`).

## 🔑 Access & prerequisites
- **Firebase test project** (`aromex-june-2026` / current dev company) — Firestore + Functions
  deploy access. Get config from the team password manager / manager via secure channel.
- **HL gateway admin token** — already wired for the CF via the `GATEWAY_ADMIN_TOKEN` secret
  (same secret `onPurchaseWrite` uses). No new secret. Never commit it.
- **HL chart of accounts** — verified live for the current company. Accounts this ticket posts to:
  | Posting | HL account | Type | Status |
  |---|---|---|---|
  | Revenue | `Sales Revenue` | INCOME | seeded ✅ |
  | AR (remainder owed) | `Accounts Receivable` | ASSET | seeded, HL-internal ✅ |
  | Inventory relief | `Inventory` | ASSET | provisioned (#58) ✅ |
  | COGS | `Cost of Goods Sold` | EXPENSE | **get-or-create** (not seeded) |
  | GST collected | `GST Payable` | LIABILITY | seeded ✅ |
  | PST collected | `PST Payable` | LIABILITY | **get-or-create**, `isTaxAccount:true` |
  | HST collected | `HST Payable` | LIABILITY | **get-or-create**, `isTaxAccount:true` |
  | Cash payment | `Cash` | ASSET | seeded ✅ |
  | Bank **+ card** payment | `Bank` | ASSET | seeded ✅ |
  - **Names must match verbatim** (the CF matches by exact name). The three non-seeded names are
    ours to define (HL has no canonical spelling); the CF `getOrCreateAccount`s them as an
    idempotent safety net. Provisioning them at company-setup is a separate onboarding follow-up —
    **not required for this ticket to work.**

## ✅ Scope / What to build

### Shared logic (`sharedLogic`, `/kmp-arch` layers 1–3)
- [ ] **Models** (`model/`): `SaleInput` (write model) · `SaleLineInput` sealed
      (`InventoryLineInput(productId, serialId, unitPrice, lineDiscount)` ·
      `CustomLineInput(name, unitPrice, lineDiscount)`) · `PaymentInput(cash, card, bank)` ·
      `TaxLine(name, rate, amount)` · `TaxConfig(gstEnabled, gstRate, pstEnabled, pstRate, isHST)` ·
      `WalkInCustomer` (`WALK_IN_CUSTOMER_ID = "walk-in-customer"`,
      `WALK_IN_CUSTOMER_NAME = "Walk-in Customer"`) · `AlreadySoldException(imei)`.
- [ ] **`SaleCalculator` (pure)** + `SaleTotals` — the **single home** for the money math, used by
      the use case here and by the T2 ViewModels for live display:
      `compute(lines, saleDiscount, tax) → SaleTotals(subtotal, taxableAmount, taxLines, taxTotal,
      grandTotal, cogsTotal)`. Order: `netPrice = unitPrice − lineDiscount` (≥0) → `subtotal = Σ
      netPrice` → `taxableAmount = subtotal − saleDiscount` (≥0) → per enabled tax `amount =
      round½up(taxableAmount × rate, 2)` → `grandTotal = taxableAmount + taxTotal`;
      `cogsTotal = Σ` inventory-line `cost`.
- [ ] **`util/Money.kt`** — add `subtract(a,b)` and `multiplyRate(amount, rate)` (round-half-up to
      2dp). `add`/`sum`/`compare`/`lessThanOrEqual` already exist. **No floats anywhere.**
- [ ] **Tax config read** — extend the existing `CompanySettings` read
      (`UserRepository.readCompanySettings`) to also return `TaxConfig` from
      `companySettings/profile.tax`, and carry it on `UserSession` (mirror how `currency` flows).
- [ ] **`repository/SalesRepository.kt`** — `suspend fun recordSale(sale: SaleInput, resolved:
      List<ResolvedSaleLine>): String`. `ResolvedSaleLine` carries the per-inventory-line snapshots
      the caller already holds from cached inventory (`imei`, `label`, `listPrice`, `cost`) so the
      transaction reads serials only for the race check, not for display data.
- [ ] **`usecase/RecordSaleUseCase.kt`** — gated on **`sales` MANAGE**. Validate → compute (via
      `SaleCalculator`) → snapshot → write. Rules: ≥1 line; every money field a valid non-negative
      decimal string; `lineDiscount ≤ unitPrice` per line; `saleDiscount ≤ subtotal`;
      `amountPaid ≤ grandTotal` (no overpayment); **if walk-in, `amountPaid == grandTotal`**;
      customer present. Builds the full `sales/{id}` body and calls `SalesRepository.recordSale`.

### Platform repo impls (all three — Android / Desktop / iOS)
- [ ] Implement `recordSale` as **one native transaction** (mirror `addStockBatchWithPurchase`):
      **read** each `serials/{serialId}` → abort with `AlreadySoldException(imei)` if `status !=
      IN_STOCK` or `isActive == false`; **write** per inventory line → serial `status = SOLD`,
      `saleId`, `updatedAt`; **delete** `imeiIndex/{imei}`; then **create** `sales/{saleId}`
      (`PENDING`). All-or-nothing. Assert a sane line ceiling (~100) so it stays under Firestore's
      ~500-write limit.

### Cloud Functions (`firebase/functions`)
- [ ] **`hl.ts`** — `createSale(...)` → `POST /api/v1/sales`:
      `{ customerId, amount: taxableAmount, description, revenueAccountId, taxLines:[{amount,
      accountId}], cogsAmount?, inventoryAccountId?, cogsAccountId?, appId:"aromex", sourceId,
      actorRef }`. **Include the cogs trio only when `cogsTotal > 0`** (HL requires `cogsAmount ≥
      0.01`; all-or-none). `createPayment(...)` → `POST /api/v1/payments`:
      `{ customerId, amount, paymentAccountId, method, appId:"aromex", sourceId, actorRef }` — HL
      `method` is only `CASH|BANK`, so drive the account explicitly via `paymentAccountId`
      (cash→`Cash`, bank→`Bank`, **card→`Bank`**); one call per non-zero method.
- [ ] **`syncWorker.ts`** — `SaleData` type + `syncSale(saleId, data, cfg)`, mirroring
      `syncPurchase`: broker token → resolve customer HL id (**bootstrap Walk-in Customer** when
      `customerEntityId == WALK_IN_CUSTOMER_ID`, like the Unspecified Supplier path; a *named*
      customer not yet synced → leave `PENDING` **without rewriting the doc**, reconcile retries) →
      get-or-create accounts → `createSale` → `createPayment` per non-zero method → set `SYNCED`;
      on failure → `FAILED` + rethrow. `saleSourceId(saleId, role)` with
      `role ∈ {sale, payment_cash, payment_card, payment_bank}`.
- [ ] **`index.ts`** — `onSaleWrite` trigger on `sales/{saleId}` (`retry:true`); extend the
      `reconcile` scheduled sweep to also retry `sales` in `PENDING`/`FAILED`.

### Schema + rules
- [ ] **`docs/SCHEMA.md`** — add the `sales/{saleId}` block (Part 2, Sales loop) + a Walk-in
      Customer note on the `entities` section (reserved, `isWalkIn`, deterministic id).
- [ ] **`firebase/firestore.rules`** — new `match /sales/{saleId}` above the catch-all (mirror the
      `purchases` block): `get, list` on `sales` view; `create` only when `sales` manage +
      `syncStatus == 'PENDING'` + money fields are strings + no `hlSyncedAt`; `update, delete: if
      false` (CF-owned).

### `sales/{saleId}` document shape
```
saleId, customerEntityId, isWalkIn,
lines:[ {kind:"INVENTORY", productId, serialId, imei, label, listPrice, unitPrice, lineDiscount, netPrice, cost}
        | {kind:"CUSTOM", name, unitPrice, lineDiscount, netPrice} ],
subtotal, saleDiscount, taxableAmount, taxLines:[{name,rate,amount}], taxTotal, grandTotal, cogsTotal,
payments:{cash,card,bank}, amountPaid, balanceRemaining, note, status:"COMPLETED",
syncStatus:"PENDING"|"SYNCED"|"FAILED", hlSaleId, hlSyncedAt, hlSyncError,
createdBy, createdAt, updatedAt
```
All money is a decimal **string**.

## 🎯 Acceptance Criteria
- [ ] Confirming a sale writes — **in one Firestore transaction** — each sold serial (`status=SOLD`,
      `saleId` set, `imeiIndex` deleted) **and** one `sales/{id}` doc (`PENDING`). Either all commit
      or none do; **no serial is ever marked sold without its sale record, or vice-versa.**
- [ ] A serial that is already `SOLD`/inactive at commit aborts the whole transaction with
      `AlreadySoldException(imei)` — **no partial write** (verify neither the stock change nor the
      sale doc appears in Firestore).
- [ ] `onSaleWrite` posts to HL: one `/sales` (pre-tax revenue + `taxLines` + **COGS against the
      Inventory asset**) and one `/payments` per non-zero method; sets `SYNCED`. **The cashier path
      never blocks on or sees HL.**
- [ ] COGS legs are **omitted** when the sale has only non-inventory lines (`cogsTotal = 0`).
- [ ] Card payment posts to the **`Bank`** account (no `Credit Card` account created); the
      `sales/{id}` doc still records `payments.card` separately.
- [ ] Tax is computed **tax-exclusive** from the company `TaxConfig` and snapshotted as `taxLines`;
      GST→`GST Payable`, PST→`PST Payable`, HST→`HST Payable` (created `isTaxAccount:true`).
- [ ] A **named** customer's unpaid remainder becomes their HL balance; a **walk-in must pay in
      full** (the use case rejects a short-paid walk-in).
- [ ] Walk-in Customer is a real, visible Entity with the fixed id `walk-in-customer`, lazily
      bootstrapped in the CF; concurrent first-use can't duplicate.
- [ ] All HL posts are **idempotent** (`appId:"aromex"`, `saleSourceId`); the reconcile sweep
      retries `PENDING`/`FAILED` sales. Retrying a sync never double-posts.
- [ ] **Money is decimal strings end-to-end**; conversion to a number happens only at the HL
      request boundary. No floats in shared code.
- [ ] `sharedLogic:jvmTest` covers Money (`subtract`/`multiplyRate`), `SaleCalculator` (tax math,
      discounts, COGS sum), and `RecordSaleUseCase` (walk-in pay-in-full, overpay reject, permission
      gate, `AlreadySold` propagation). `firebase/functions` tests cover `syncSale` (walk-in
      bootstrap, cogs-omitted-when-0, one payment per method, idempotent sourceIds,
      named-unsynced → left PENDING). `androidApp:compileDebugKotlin` + `desktopApp:compileKotlin`
      pass; iOS mirrors the change.

## ✅ PO rulings (Gate-1 sign-off, 2026-07-22 — resolved, build as stated)
- **Card → account:** **`Bank`** confirmed/intended — this company uses only Cash and Bank; card
  payments post to `Bank` and are still recorded separately on the `sales/{id}` doc
  (`payments.card`). No change. *(The PRD-listed "Credit Card" account is unused — minor doc
  cleanup later, not part of this ticket.)*
- **Card swipe / processing fee:** **(a) — business absorbs it.** Record the **full amount the
  customer paid**; the ~2–3% fee is a later **bank-reconciliation** expense (a `Merchant Fee` /
  processing-fees EXPENSE) and is **deferred — no code in this ticket.** Do **not** build explicit
  fee handling (option c). Option (b) — customer surcharge via a non-inventory custom line —
  remains available for free if the shop ever chooses to surcharge, but is not built here.

## 🚫 Out of scope
- Any ViewModel or UI (T2–T4).
- The standalone "customer pays down their tab later" collection screen.
- Returns / refunds; printed / PDF receipt.
- Input-GST on purchases (`GST Input`) — that's the purchase side, deferred with #58.
- Provisioning the new accounts at company-setup (onboarding-runbook follow-up; the CF
  get-or-creates them, so this ticket works without it).
- Middleman flow.

## 🔗 Dependencies
- **#58** (Inventory→HL purchase) — merged; provides the atomic-write + dual-write + placeholder
  patterns and the `Inventory` HL asset this relieves COGS against.
- **#41/M4** inventory model (serials `status`/`saleId`/`cost`/`imeiIndex`) — merged.

## 📚 References
- Brief: #60 · `docs/briefs/B60-sales.md`
- PRD: `docs/PRD.md` §9.4 (Sales), §7/§13 (HL; inventory-as-asset + COGS-on-sale)
- `docs/SCHEMA.md` — `serials`/`entities`/`purchases` blocks
- #58 handoff: `handoffs/ticket-58.md`; `firebase/functions/src/{index,syncWorker,hl}.ts`
- HL API: `https://ledger.humblesolutions.in/docs` (OpenAPI `/docs/json`) — `/sales` (cogs +
  taxLines), `/payments`, `/accounts`, `GET /customers/{id}` (balance)
- `CLAUDE.md` (two-backends, money-as-strings, `/kmp-arch`)

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
