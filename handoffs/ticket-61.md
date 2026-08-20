# Handoff — Ticket #61

**Ticket:** #61 — [M5] Sales T1 — backend spine: atomic sale write + HL posting
**Brief:** #60
**Branch:** `ticket-61-sales-backend-spine` (2 commits)
**Platforms:** shared · Android · iOS · Desktop · Firebase Functions

## Summary
Builds the operational + accounting **engine** for selling in-stock phones — no ViewModels, no
UI. A new shared `RecordSaleUseCase` validates a cart, computes tax-exclusive money via a single
shared `SaleCalculator` (no floats), and commits the sale through a per-platform
`BackendSalesRepository` that runs **one Firestore transaction**: re-check each serial `IN_STOCK`,
flip it `SOLD` + stamp `saleId`, delete its `imeiIndex`, and create the `sales/{id}` doc `PENDING`
— all-or-nothing, so a unit is never sold without its record (the #58 atomicity lesson). The race
loser gets `AlreadySoldException`. A new `onSaleWrite` Cloud Function then posts the sale to Humble
Ledger (pre-tax revenue + AR + 0–2 tax legs, COGS against the Inventory asset, and one payment per
non-zero method with card routed to Bank), idempotently, lazily bootstrapping the reserved
**Walk-in Customer**; the reconcile sweep now backstops sales too. Company tax config is read from
`companySettings/profile.tax` onto `UserSession`. Money is decimal strings end-to-end; HL
credentials never touch a device. Deployed to `aromex-june-2026` (functions + rules).

## Files changed

### Shared logic (`sharedLogic`)
- `model/SaleInput.kt`, `SaleLineInput.kt` (sealed INVENTORY/CUSTOM), `PaymentInput.kt`,
  `TaxConfig.kt`, `TaxLine.kt`, `SaleTotals.kt`, `ResolvedSaleLine.kt`, `SaleRecord.kt` — the sale
  write model, the per-line unit snapshot, and the fully-resolved persist model.
- `model/WalkInCustomer.kt` — fixed `WALK_IN_CUSTOMER_ID`/`_NAME` (deterministic; no dup under concurrency).
- `model/AlreadySoldException.kt` — thrown by the transaction when a unit lost the race.
- `usecase/SaleCalculator.kt` — the single home for the money math (netPrice → subtotal → taxable →
  tax legs → grand total → COGS); used by the use case and (later) the T2 ViewModels.
- `usecase/RecordSaleUseCase.kt` — `sales` MANAGE gate, per-line + payment validation, walk-in
  pay-in-full, overpay reject, cost/label snapshotting → builds `SaleRecord` → `recordSale`.
- `repository/SalesRepository.kt` — `recordSale(record): String` contract (one atomic transaction).
- `util/Money.kt` — `subtract` + `multiplyRate` (round-half-up to 2dp), pure decimal strings.
- `repository/UserRepository.kt`, `model/UserSession.kt`, `usecase/LoginUseCase.kt`,
  `RestoreSessionUseCase.kt` — carry `TaxConfig` from `companySettings/profile.tax` onto the session.

### Platform repositories (native transaction, one per platform)
- `androidApp/.../data/BackendSalesRepository.kt`, `desktopApp/.../data/BackendSalesRepository.kt`,
  `iosApp/.../repository/BackendSalesRepository.swift` — implement `recordSale`: read serials
  (abort `AlreadySold` if not `IN_STOCK`/active), flip `SOLD`+`saleId`, delete `imeiIndex`, write
  `sales/{id}` PENDING — all in one transaction (Android GMS / Desktop Admin SDK / iOS SDK).
- `androidApp/.../data/FirestoreUserRepository.kt`, `desktopApp/.../data/FirestoreUserRepository.kt`,
  `iosApp/.../repository/FirestoreUserRepository.swift` — parse the nested `tax` map into `TaxConfig`
  (rates kept as decimal strings) in `getCompanyProfile`.

### Cloud Functions (`firebase/functions`)
- `src/hl.ts` — `createSale` (`/sales`; cogs trio only when `cogsTotal>0`) + `createPayment`
  (`/payments`; card→Bank via `paymentAccountId`); `getOrCreateAccount` gains an `isTaxAccount` flag.
- `src/syncWorker.ts` — `WALK_IN_CUSTOMER_ID` + a generalized placeholder-party bootstrap;
  `SaleData` type, `saleSourceId`, and `syncSale` (resolve/bootstrap customer, get-or-create
  Sales Revenue / Cost of Goods Sold / Inventory / GST·PST·HST Payable / Cash / Bank, post sale +
  payments, mark SYNCED).
- `src/index.ts` — `onSaleWrite` trigger on `sales/{saleId}` (`retry:true`); reconcile sweep
  extended to retry `sales` in PENDING/FAILED.
- `src/syncWorker.test.ts` — `saleSourceId` + Walk-in id pure-helper tests.

### Schema, rules, tests, docs
- `docs/SCHEMA.md` — Part 3 "Sales": the `sales/{saleId}` block + write-path/concurrency notes.
- `firebase/firestore.rules` — `match /sales/{saleId}` (create-only PENDING with string money
  fields; CF-owned after; read/create gated on `sales`).
- `sharedLogic/.../commonTest/.../sales/` — `MoneySalesTest`, `SaleCalculatorTest`,
  `RecordSaleUseCaseTest`, `SalesTestFakes`.
- `docs/tickets/M5-60-T1..T4-*.md` — the four Brief-#60 ticket specs (T1 is this ticket).

## How to test
**Automated**
- `./gradlew :sharedLogic:jvmTest` — Money (`subtract`/`multiplyRate`), `SaleCalculator`
  (GST-only / GST+PST / HST / none / whole-sale discount / rounding / COGS), `RecordSaleUseCase`
  (happy path snapshots, walk-in pay-in-full, overpay reject, permission gate, `AlreadySold`,
  discount-over-price). ✅ pass.
- `./gradlew :androidApp:compileDebugKotlin :desktopApp:compileKotlin` ✅.
- iOS: `cd iosApp && pod install`, then
  `xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' build` — **BUILD SUCCEEDED** ✅.
- `firebase/functions`: `npm run build`; unit tests in `src/syncWorker.test.ts` (11 pass — note the
  default vitest `include` is `tests/**`, so run with an `src/**` include).

**Manual** (backend deployed to `aromex-june-2026`; needs a UI or a synthetic `sales` doc to trigger)
1. Write a `sales/{id}` doc `PENDING` (named customer or `walk-in-customer`) → `onSaleWrite` posts to
   HL and flips the doc to `SYNCED` with `hlSaleId` set; `firebase functions:log --only onSaleWrite`.
2. Confirm HL: one `/sales` (revenue + tax legs + COGS against Inventory) + one `/payments` per
   non-zero method; new accounts `Cost of Goods Sold` / `PST Payable` / `HST Payable` appear if used.
3. Duplicate/again with the same `saleId` → idempotent (no double post).

## Acceptance criteria
- ✅ Sale write is one Firestore transaction: serial `SOLD`+`saleId`, `imeiIndex` deleted, `sales/{id}`
  PENDING — all-or-nothing (`BackendSalesRepository` on all three platforms).
- ✅ Already-`SOLD`/inactive serial aborts with `AlreadySoldException`; no partial write.
- ✅ `onSaleWrite` posts `/sales` (revenue + taxLines + COGS against Inventory) + `/payments` per
  method; sets SYNCED; cashier path never blocks on HL (CF-only).
- ✅ COGS omitted when `cogsTotal = 0` (`createSale` guards the trio).
- ✅ Card posts to `Bank`; `payments.card` still recorded on the doc.
- ✅ Tax tax-exclusive from `TaxConfig`, snapshotted as `taxLines`; GST/PST/HST → `* Payable`
  (`isTaxAccount:true`).
- ✅ Named customer's remainder → HL balance; walk-in must pay in full (use case rejects short-pay).
- ✅ Walk-in Customer bootstrapped in the CF with fixed id; concurrent first-use can't duplicate.
- ✅ Idempotent posts (`saleSourceId`); reconcile retries PENDING/FAILED sales.
- ✅ Money is decimal strings; number conversion only at the HL boundary.
- ✅ jvmTest + functions tests + Android/Desktop/iOS builds. **iOS included.**
- ⚠️ **Not yet exercised at runtime** — no real sale has flowed through the deployed `onSaleWrite`
  (the account-creation + HL posting path is proven by unit tests + deploy, not a live sale). See below.

## Deviations / decisions
- **Repo takes a resolved `SaleRecord`, not `recordSale(sale, resolved)`** (the ticket's literal
  signature). The use case does all validation + math (via `SaleCalculator`) and hands the platform a
  ready-to-persist record, so the money math has one shared home and each platform only maps
  record→doc. Same behaviour, cleaner boundary.
- **PO rulings baked in (Gate-1):** card → `Bank` (no `Credit Card` account); swipe fee = business
  absorbs it (deferred, no fee code). No open question remains.
- **`getOrCreateAccount` gains `isTaxAccount`** so the safety-net creation of `PST/HST Payable` matches
  the seeded `GST Payable`.
- **iOS fixes found by actually building:** `TaxConfig()` no-arg init is unavailable via SKIE (pass
  explicit defaults); `.trimmed()` is a fileprivate extension in the inventory repo (use
  `.trimmingCharacters(in:)`).
- **Deployed** `onSaleWrite` + `reconcileEntities` + `firestore.rules` to `aromex-june-2026` (needed
  `--force` for the retry policy, same as `onPurchaseWrite`).

## Open questions / follow-ups
- **Runtime verification of the CF** — do one real end-to-end sale (ideally once T3's UI exists) to
  confirm the HL accounts get created and the sale/payment post correctly on the dev company. HL's
  ledger is immutable, so treat the first test sale as durable dev data.
- **HL account provisioning** — ask the HL team to pre-provision `Cost of Goods Sold` + `PST/HST
  Payable` (with `isTaxAccount:true`) per company so the CF's get-or-create is only a safety net.
- **Functions unit tests** live in `src/` and aren't in the default `tests/**` vitest include — worth
  aligning the config (carried over from #58).
- **Node.js 20 / firebase-functions** are flagged deprecated at deploy — schedule an upgrade before
  the Oct 2026 decommission (repo-wide, not this ticket).
- **T2–T4** (ViewModels · Desktop UI · phone UI) build on this spine next.
