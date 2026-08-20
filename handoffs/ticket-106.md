# Handoff — Ticket #106

**Ticket:** #106 — [M11] Sales — tax-inclusive pricing, discount placement, post-sale dialog, customer tax number

## Summary
Four Sales changes plus a product-owner-requested extension. **(1) Tax-inclusive pricing:** a per-sale
toggle lets the cashier type an all-in price; `SaleCalculator` inverts the math — `taxableAmount =
round½up(grandTotal ÷ (1 + Σ rates))`, every tax leg but the last computed from it, and the **last leg
absorbs the remainder** so `taxableAmount + Σ taxLines == grandTotal` to the penny (one tax, two, or
none). This needed a new `Money.divide` (pure string long division, no floats). The flag is stored on
the sale and **resets to off on every new sale**. **(2)** The whole-sale discount moved from the cart
pane to the checkout pane (Desktop). **(3)** The sale-complete dialog button reads **Done** (still
calls `startNewSale()`). **(4)** A customer tax number can be stored on a contact and prints on the
invoice's Bill-To box (`GST/HST No: …`), snapshotted onto the sale at record time. **Extension (per the
owner):** the customer tax number is also an **editable field at checkout** — prefilled from the
selected customer, usable for a walk-in, with an optional **"Save to contact"** write-back gated on
`profiles: MANAGE`. All logic lives in `sharedLogic`; UI is full on Desktop and native-minimal on
Android + iOS. The `onSaleWrite` Cloud Function was updated and deployed (targeted).

## Files changed

### sharedLogic (shared Kotlin)
- `util/Money.kt` — new `divide(amount, divisor, scale=2)` as string long division (half-up, no
  floats), with `divDigits`/`cmpDigits` helpers. The inverse of `multiplyRate` for backing out tax.
- `usecase/SaleCalculator.kt` — `compute(..., taxInclusive: Boolean = false)`; inclusive path with the
  last-leg-absorbs-the-remainder rule. Default false keeps the exclusive path byte-identical.
- `model/SaleInput.kt` — `taxInclusive` + `buyerTaxNumber` (the per-sale, editable tax number).
- `model/SaleRecord.kt` — `taxInclusive` + `buyerTaxNumber` snapshot fields.
- `model/Entity.kt` + `model/EntityInput.kt` — optional `taxNumber`.
- `usecase/RecordSaleUseCase.kt` — threads `taxInclusive` into the calculator; snapshots
  `buyerTaxNumber` (carried for a walk-in too; blank → null).
- `repository/EntityRepository.kt` — new `updateTaxNumber(id, taxNumber)` (targeted single-field write).
- `usecase/SaveBuyerTaxNumberUseCase.kt` **(new)** — the "Save to contact" action; gated on
  `profiles: MANAGE`, trims, blank → null.
- `i18n/Strings.kt` + `i18n/EnglishStrings.kt` — tax-inclusive label, `Done`, contact tax-number
  label/placeholder, checkout tax-number label/placeholder, save/saved/error strings.
- `commonTest/.../MoneyTest.kt` — `divide` cases (BC `700÷1.12`, divisor 1, repeating decimals, scale
  0, divide-by-zero).
- `commonTest/.../SaleCalculatorTest.kt` — inclusive BC case, no-tax pass-through, and a property-style
  identity check across amounts × regimes × discounts.
- `commonTest/.../RecordSaleUseCaseTest.kt` — `buyerTaxNumber` snapshot for named + walk-in + blank.
- `commonTest/.../SaveBuyerTaxNumberUseCaseTest.kt` **(new)** — manage gate, trim, blank→null, blank id.
- `commonTest/.../entities/TestFakes.kt` — fake implements `updateTaxNumber` and records calls.

### desktopApp
- `data/BackendEntityRepository.kt` — read/write `taxNumber`; implement `updateTaxNumber`.
- `data/BackendSalesRepository.kt` — write `taxInclusive` + `buyerTaxNumber` on the sale doc.
- `ui/sales/SalesViewModel.kt` — `taxInclusive` state + reset; `buyerTaxNumber` field (prefill on
  select, empty for walk-in/new), `setBuyerTaxNumber`, `saveBuyerTaxNumberToContact`,
  `canManageProfiles`/`canSaveTaxToContact`; passes both into `SaleInput`.
- `ui/sales/SalesScreen.kt` — tax-inclusive `Switch`; discount field + hint moved to `CheckoutPane`;
  customer tax-number field + "Save to contact" (states/gating); dialog button → **Done**.
- `ui/entities/EntitiesScreen.kt` — optional Tax number field on the contact form.
- `test/.../ui/money/MoneyViewModelTest.kt` — fake repo implements `updateTaxNumber`.

### androidApp
- `data/BackendEntityRepository.kt`, `data/BackendSalesRepository.kt` — same field read/writes +
  `updateTaxNumber` as Desktop.
- `ui/sales/SalesViewModel.kt` — same state/actions as Desktop.
- `ui/sales/SalesScreen.kt` — `TaxInclusiveToggle`, `CustomerTaxNumberSection`, dialog button → Done
  (native-minimal).
- `ui/entities/EntitiesScreen.kt` — Tax number field on the contact form.

### iosApp
- `repository/BackendEntityRepository.kt`(swift) — `taxNumber` in profile fields + `mapDoc`; new
  `__updateTaxNumber`.
- `repository/BackendSalesRepository.swift` — write `taxInclusive` + `buyerTaxNumber`.
- `viewmodel/SalesViewModel.swift` — same state/actions; passes `taxInclusive`/`buyerTaxNumber` into
  `SaleInput`; explicit `taxConfig` on `RecordSaleUseCase.execute` (SKIE strips Kotlin defaults).
- `viewmodel/EntityFormViewModel.swift` — `taxNumber` field + init + save.
- `ui/SalesView.swift` — tax-inclusive `Toggle`, customer tax `Section` + Save to contact, dialog →
  Done.
- `ui/EntityFormView.swift` — Tax number field + focus order + dirty-check snapshot.

### firebase (server / config / docs)
- `functions/src/syncWorker.ts` — `SaleData` gains `taxInclusive` + `buyerTaxNumber`;
  `buildInvoicePayload` emits `customerTaxLine: "GST/HST No: <n>"` when present, omits the key when
  absent (mirrors `sellerTaxLine`). **Deployed:** `firebase deploy --only functions:onSaleWrite`.
- `functions/src/billEngine.test.ts` — customer-tax-line present/absent/walk-in cases.
- `firestore.rules` — type guards for the two new sale fields (`taxInclusive` bool, `buyerTaxNumber`
  string|null); entities need no change (no field allowlist; `taxNumber` already permitted).
- `docs/SCHEMA.md` + `firebase/SCHEMA.md` — document `entities.taxNumber`, `sales.taxInclusive`,
  `sales.buyerTaxNumber`.

## How to test
1. **Unit / server:** `./gradlew :sharedLogic:jvmTest :desktopApp:test :androidApp:testDebugUnitTest`;
   in `firebase/functions` run `npm test`; in `firebase` run `npm run test:rules`.
2. **Tax-inclusive (Desktop):** add one line priced `700.00`, set GST 5% + PST 7% in Settings, flip
   **Prices include tax** on → totals show taxable `625.00`, GST `31.25`, PST `43.75`, total `$700.00`
   exactly. Confirm the toggle is **off** again on the next sale.
3. **Discount / dialog:** confirm the sale-discount field is in the checkout pane (not the cart pane)
   and its "exceeds subtotal" hint still gates; complete a sale and confirm the dialog button reads
   **Done** and clears the cart.
4. **Customer tax number:** on a contact, set a Tax number and save. Start a sale to that customer —
   the checkout field is prefilled; edit it and it shows on the invoice; press **Save to contact** to
   persist. Sell to a customer without one, and a walk-in → no `GST/HST No:` line. Requires the Desktop
   app signed in as **admin** and the deployed `onSaleWrite` to verify the PDF.
5. **Snapshot:** issue an invoice, then edit the contact's tax number → the issued invoice is unchanged.

## Acceptance criteria
- [x] Toggle **off** → existing behaviour identical (existing `SaleCalculatorTest` passes unchanged).
- [x] Toggle **on**, GST 5% + PST 7%, `700.00` → `taxableAmount + GST + PST == 700.00`, total `$700.00`.
- [x] Identity holds to the penny for one/two/no tax + sale + line discount (property-style test).
- [x] `Money.divide` has its own tests incl. a repeating decimal and divisor `1`.
- [x] Toggle resets to off on `startNewSale()`.
- [x] `taxInclusive` stored on the sale document.
- [~] Inclusive sale posts to HL with pre-tax amount + tax legs (unchanged `syncSale`/`taxLines` path;
  **needs the manual HL end-to-end run** — not machine-verifiable here).
- [x] Discount field + hint render in the checkout pane, not the cart pane; gating unchanged.
- [x] Dialog button reads **Done** and still clears the cart.
- [~] Dismissing while invoice "preparing" leaves the sale intact and the invoice still issues
  (server-side `onSaleWrite`; **needs manual run**).
- [x] A contact can be saved with and without a tax number.
- [x] Selling to a customer with one emits `customerTaxLine` (CF test); [~] printed PDF **needs manual
  run**.
- [x] No line for a customer without one / a walk-in (CF test asserts key omitted).
- [~] Editing the contact later doesn't change an issued invoice (snapshot on the sale doc; **manual
  confirm**).
- [x] `:sharedLogic:jvmTest`, `:desktopApp:test`, `npm test`, `npm run test:rules` all pass.

## Deviations / decisions
- **Scope extension (owner-requested):** the customer tax number is editable at checkout with a
  "Save to contact" write-back, and a **walk-in can carry a tax number on the invoice** — beyond the
  ticket's "contact-only, snapshot silently" scope (which explicitly put walk-in-at-checkout out of
  scope). Implemented on the same branch; flagged here for review.
- **All three platforms**, not Desktop-only as scoped: shared model changes have safe defaults; UI is
  full on Desktop and deliberately native-minimal on Android + iOS.
- "Save to contact" is gated on **`profiles: MANAGE`** and disabled until the field differs from the
  stored value.
- Write-back uses a **targeted `updateTaxNumber`**, not `updateEntity`, to avoid re-validating
  name/phone and clobbering other fields.
- `firebase/SCHEMA.md` has no `sales` section, so the two sale fields are documented in `docs/SCHEMA.md`
  only; the entity field is added to both.

## Open questions / follow-ups
- The `~` acceptance items need a **manual end-to-end run** (admin sign-in + the deployed
  `onSaleWrite`): invoice PDF content, dismiss-during-preparing, HL posting, and snapshot-vs-edit.
- **iOS** could not be compiled here (no Xcode); one SKIE default-param gap on
  `RecordSaleUseCase.execute` was fixed — rebuild in Xcode and report any further iOS-only errors.
- If the owner wants the walk-in tax-number extension as its own record, it can be split into a
  follow-up ticket; it currently ships under #106.
