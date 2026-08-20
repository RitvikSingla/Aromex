# Handoff — Ticket #58

**Ticket:** #58 — [Inventory→HL] Record purchase on Add-Inventory save — Humble Ledger posting
**Branch:** `ticket-58-inventory-purchase-hl` (5 commits)
**Platforms:** Android · iOS · Desktop · Firebase Functions

## Summary
Hitting **Confirm** on the Add-Inventory review screen now opens a purchase dialog — who the
batch was bought from (searchable party dropdown, suppliers first, add-new-inline, defaulting
to a reserved **Unspecified Supplier**) plus cash/bank paid on the spot. Confirming writes the
stock **and** a single `purchases/{id}` doc (PENDING) for the batch **atomically — in one
Firestore transaction, so either both land or neither does.** That closes the invariant the
ticket forbids breaking: there is never in-stock inventory without a purchase record (and thus
no phantom stock the books never learn about). A new `onPurchaseWrite` Cloud Function posts the
purchase to Humble Ledger: the batch cost as an **asset** (`customer-purchases` against the
company's Inventory ASSET account) plus a `customer-payouts` per non-zero cash/bank amount — all
idempotent, with the existing reconcile sweep extended as the retry backstop. The cashier never
waits on or sees HL. Money is decimal strings throughout (no floats). Follows `/kmp-arch` (shared
model + use case; native dialog per platform; HL writes only in the CF).

## Files changed

### Shared logic (`sharedLogic`)
- `model/PurchaseInput.kt`, `model/UnspecifiedSupplier.kt` — write model + the fixed
  `UNSPECIFIED_SUPPLIER_ID`/`_NAME` (deterministic id so concurrent first-use can't duplicate).
- `model/StockBatchGroup.kt` — one SKU's slice of a batch (skuKey + optional `NewProduct` to
  find-or-create + its units), passed to the atomic write.
- `repository/InventoryRepository.kt` — `addStockBatchWithPurchase(groups, purchase)`: the whole
  batch (products + serials + imeiIndex) **and** the `purchases/{id}` doc commit in one Firestore
  transaction. Pre-capped by `SAFE_BATCH_CEILING` (180) so it always fits under the ~500-write limit.
- `usecase/RecordInventoryPurchaseUseCase.kt` — validates the batch (units + selling price) and
  the purchase (`cash+bank ≤ total`, party present), sums per-unit costs across every SKU, then
  performs the atomic write (gated on `inventory` MANAGE). **Replaces the old
  `RecordPurchaseUseCase` + `PurchaseRepository`**, which did a separate, best-effort purchase write.
- `usecase/ObservePartiesForPurchaseUseCase.kt` — live party list for the dropdown, gated on
  **`inventory`** (not `profiles`) so any inventory user sees suppliers.
- `usecase/AddSupplierInlineUseCase.kt` — name-only SUPPLIER party for add-new-inline (gated on `profiles` MANAGE).
- `util/Money.kt` — `add`/`sum`/`compare`/`lessThanOrEqual` on decimal **strings** (no float).
- `i18n/Strings.kt` + `EnglishStrings.kt` — dialog strings.

### Platform repos
- Android/Desktop/iOS `BackendInventoryRepository.*` — implement `addStockBatchWithPurchase`: one
  transaction reads every product + imeiIndex doc, aborts on a duplicate in-stock IMEI, then
  writes all products/serials/imeiIndex **and** the PENDING `purchases/{id}` doc together (native
  SDK / Admin SDK / iOS SDK).
- **Removed** Android/Desktop/iOS `BackendPurchaseRepository.*` — the separate purchase-write path
  is gone; the purchase doc is now written inside the inventory transaction.

### ViewModels + UI (native per platform)
- Android/Desktop/iOS `AddStockViewModel.*` — dialog state (party/cash/bank/currency), live party
  observe, `purchasePartyOptions()` (suppliers first + Unspecified Supplier), `confirmPurchaseAndSave()`
  → `saveInventoryWithPurchase()` which builds `StockBatchGroup`s and calls the atomic
  `RecordInventoryPurchaseUseCase` (a real failure is surfaced, **never swallowed**),
  `dismissPurchaseDialog()` (cancel — no save).
- Android/Desktop `InventoryScreen.kt`, iOS `InventoryView.swift` — Confirm opens the dialog;
  native `PurchaseDialog` reusing `FilterableDropdownField` + money fields + inline validation.
  Desktop dialog styled like the entity form (gradient header + card + footer).

### Cloud Functions (`firebase/functions`)
- `hl.ts` — `getOrCreateAccount` (GET-then-create, handles 409), `createCustomerPurchase`, `createCustomerPayout`, `hlGet`.
- `syncWorker.ts` — `syncPurchase` (resolve party HL id, lazily bootstrap Unspecified Supplier,
  sync party inline if unsynced else leave PENDING, post purchase + 0–2 payouts), `purchaseSourceId`
  idempotency helper.
- `index.ts` — `onPurchaseWrite` trigger; reconcile sweep extended to purchases.
- `firestore.rules` + `SCHEMA.md` — `purchases/{id}` block + docs. `syncWorker.test.ts` — tests.

## How to test
**Automated**
- `./gradlew :sharedLogic:jvmTest` — Money math + `RecordInventoryPurchaseUseCase` (sums across SKUs,
  atomic write recorded once, validation, permission gate) + AddSupplierInline. ✅ pass.
- `./gradlew :androidApp:compileDebugKotlin :desktopApp:compileKotlin` ✅. (iOS: build in Xcode
  after `pod install` — the shared framework must regenerate to pick up `StockBatchGroup` /
  `RecordInventoryPurchaseUseCase`.)
- `firebase/functions`: `npm run build`; unit tests in `src/syncWorker.test.ts`.

**Manual** (backend deployed to `aromex-june-2026`)
1. Add Inventory → add a phone → Confirm → purchase dialog appears before the write.
2. Confirm at defaults → one `products`/`serials`/`imeiIndex` write **and** one PENDING `purchases`
   doc land together; HL shows one `customer-purchases` (Inventory asset ↑) against Unspecified Supplier.
3. Pick a supplier + enter cash/bank → HL shows the purchase + a payout per non-zero amount.
4. `cash + bank > batch total` → inline error, Confirm disabled.
5. Esc / ✕ / click-away → cancels the dialog, nothing saved.
6. **Atomicity:** a duplicate in-stock IMEI in the batch aborts the whole transaction — **no**
   stock and **no** `purchases` doc are written (verify neither appears in Firestore).

## Acceptance criteria
- ✅ Confirm shows the dialog before the write; posting against Unspecified Supplier is the default path.
- ✅ Dropdown lists all parties (suppliers first), add-new-inline, Unspecified Supplier default.
- ✅ `cash + bank` over the real total is blocked (decimal-string math, no float).
- ✅ **Inventory write is race-safe and its HL outcome never blocks the cashier** — HL posting is
  still entirely the CF's concern. *(Refinement vs. the ticket wording "runs first / unchanged":
  the purchase doc now co-commits inside the same inventory transaction — see Deviations.)*
- ✅ **Stock and its purchase record are atomic** — either both commit or neither (one Firestore
  transaction). No in-stock unit can exist without its purchase record.
- ✅ One `customer-purchases` (asset) per batch + a `customer-payouts` per non-zero cash/bank; idempotent; reconcile retries.
- ✅ Unspecified Supplier is a real, visible Entity with a fixed id; concurrent first-use can't duplicate.
- ✅ No secrets; i18n; `/kmp-arch`. Android + Desktop compile; shared JVM tests pass. iOS mirrors the change (build in Xcode).
- ⚠️ **Dismissal changed from the ticket:** Esc/click-away now **cancels** (no save) at the PO's request, rather than "confirm-at-defaults." Confirm is the only save path.

## Deviations / decisions
- **Atomic inventory + purchase write (PR #59 manager review).** The purchase record was
  previously written best-effort *after* the committed inventory transaction, with the failure
  swallowed — which could leave in-stock inventory with no purchase record and therefore no HL
  posting (the phantom-inventory case the ticket forbids). It's now written **inside the same
  Firestore transaction** via `InventoryRepository.addStockBatchWithPurchase`, so both land or
  neither does, and a real failure is surfaced to the cashier. This obsoleted and removed the
  separate `PurchaseRepository` / `RecordPurchaseUseCase` / `BackendPurchaseRepository.*` path.
  Safe because the batch is pre-capped (`SAFE_BATCH_CEILING = 180`), so one transaction always
  fits under Firestore's ~500-write limit.
- **GST/input-tax not handled** (deferred with PO + confirmed out of scope in the review): posts
  full cost to Inventory, no `taxAmount`/`taxLines`. Lives in the future standalone Purchase feature.
- **Party list gated on `inventory`, not `profiles`** — a cashier adding stock can pick a supplier without party-management rights.
- **Unspecified Supplier bootstrapped in the CF** (Admin SDK), not the client; the dialog injects a synthetic default so it's always selectable.
- **`reset()`/`startPaste()`/`startAddUnits()`** preserve the observed `entities` + `currency` (a
  prior bug wiped them, emptying the dropdown).
- **Dismiss = cancel** (see AC note; confirmed by the PO).

## Open questions / follow-ups
- **GST on purchases** — input-tax split per company tax config, in the future standalone Purchase feature.
- **Inventory UI polish (Android + iOS):** the Add-Inventory screens + purchase dialog need
  design/UX work to match Desktop quality. Recommend a dedicated UI-polish ticket.
- **iOS build:** after pulling, run `pod install` in `iosApp` and Clean Build Folder so the shared
  framework regenerates with the new `commonMain` symbols.
- **`AddSupplierInlineUseCase` gate mismatch** (noted in review): inline add is gated on `profiles`
  MANAGE while the dropdown is gated on `inventory` — an inventory-only user can pick but not add. Accepted.
- **Functions unit tests** live in `src/` and aren't in the default `tests/**` vitest include — worth aligning the config.
