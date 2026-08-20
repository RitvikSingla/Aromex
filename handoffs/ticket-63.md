# Handoff — Ticket #63

**Ticket:** #63 — [M5] Sales T3 — Desktop UI (polished counter screen)
**Brief:** #60
**Branch:** `ticket-63-sales-desktop-ui`
**Platforms:** Desktop (no Android/iOS UI — out of scope for this ticket), plus a small, necessary
Cloud Functions fix and one new shared use case surfaced by live testing.

## Summary
Adds the polished Desktop counter screen: a two-pane cart/checkout layout bound to T2's
`SalesViewModel` (rendering + action dispatch only, no business logic), an item-picker modal, a
custom-line dialog, and the four `confirmState` outcomes (`Submitting`/`Success`/`AlreadySold`/
`Error`). While wiring this up and testing it end-to-end against the live dev backend, we found and
fixed four real bugs surfaced during that testing, plus one deliberate scope addition requested
mid-review: an inline "add customer" affordance in the Sales checkout dropdown (mirroring the
existing add-supplier-inline pattern in the Inventory purchase dialog). None of the fixes below were
in the original ticket text; each is called out under Deviations with why it was necessary.
A manager-review pass (see **Post-review fixes** below) then found and fixed a locked-inputs gap, a
UI-side money-math duplication, a missing i18n key, an inaccurate recovery-time claim in an earlier
draft of this handoff, and added the regression test the race fix was missing.

## Files changed

### Desktop (`desktopApp`)
- `ui/sales/SalesScreen.kt` (**new**) — the whole screen: gradient top bar, two-pane cart/checkout
  layout, `CartLineRow` (editable price/discount, derived net, struck-through original price when
  discounted), whole-sale discount field, customer `FilterableDropdownField` (+ Walk-in quick-select
  button, + inline add-customer), split Cash/Card/Bank fields, totals card (subtotal → per-tax-line →
  grand total → paid → highlighted balance), item-picker modal (search + location chips, grouped by
  brand, stays open for multi-add with an "Added" toast), custom-line dialog, and the
  Submitting/Success/AlreadySold/Error overlays.
- `ui/sales/SalesViewModel.kt` — adds `AddCustomerInlineUseCase` wiring, `canAddCustomerInline()`,
  `addNewCustomer(name)`, and a dedicated `customerAddError` state field (kept separate from
  `ConfirmState.Error` so a failed add-customer isn't mislabeled as a failed sale).
- `ui/components/DesktopNavSidebar.kt` — adds `DesktopSection.SALES`; the "Sales" nav item is now
  wired to `onNavigateToSales` and shown only when `session.permissions.sales != NONE`.
- `ui/entities/EntitiesScreen.kt`, `ui/inventory/InventoryScreen.kt` — thread a new
  `onNavigateToSales` parameter through to `NavSidebar` (both screens already navigate to each
  other; Sales joins the same router).
- `navigation/AromexApp.kt` — instantiates/binds/disposes `SalesViewModel` alongside the existing
  three VMs; adds `DesktopSection.SALES` to the screen router.
- `ui/components/FilterableDropdownField.kt` — **bug fix**: the chevron's `onClick` toggled
  `expanded` unconditionally; when the popup was already open, a click on the chevron was *also*
  seen by the `Popup`'s `dismissOnClickOutside` as a click outside, so the two handlers fired for
  the same click and cancelled each other out (had to click twice to close). Both the compact and
  standard-mode chevrons now only ever *open* (`if (!expanded) expanded = true`); closing is left
  entirely to the popup's own dismiss handling.
- `ui/inventory/InventoryScreen.kt` — two more fixes surfaced during testing:
  - The inline "Condition" dropdown cell had the identical double-toggle bug as above; fixed the
    same way (both its own `.clickable` and its chevron icon).
  - `InventoryTopBar`'s breadcrumb text had no `maxLines`/ellipsis, so narrowing the window made
    "Inventory" wrap vertically letter-by-letter, inflating the top bar's height and pushing the
    Paste/Add-stock buttons off the visible window edge. Fixed: breadcrumb (and the Add-Inventory
    screen's title, same unguarded pattern) now truncates with `maxLines = 1` +
    `TextOverflow.Ellipsis`; the Paste/Add-stock buttons collapse to icon-only below a 560dp content
    width breakpoint (`BoxWithConstraints`) instead of ever being pushed off-window.

### Shared (`sharedLogic`)
- `usecase/AddCustomerInlineUseCase.kt` (**new**) — creates a name-only `CUSTOMER`-role entity,
  gated on `profiles` MANAGE. Mirrors `AddSupplierInlineUseCase` exactly (same "no phone required,
  entity written PENDING, `onEntityWrite` picks it up" shape).
- `i18n/Strings.kt`, `i18n/EnglishStrings.kt` — all `sales_*` keys for the new screen (cart, checkout,
  totals, picker, custom-line dialog, confirm outcomes) — no hardcoded UI strings.

### Server (`firebase/functions`)
- `src/syncWorker.ts` — **bug fix**: `resolvePartyHlCustomerId`, when it had to lazily bootstrap a
  reserved placeholder party (Walk-in Customer / Unspecified Supplier) for the very first time,
  wrote the placeholder doc with `syncStatus: 'PENDING'` (which fires `onEntityWrite` asynchronously)
  and then *also* called `syncEntity` inline in the same invocation — two concurrent
  `createCustomer` calls to Humble Ledger for the same `externalId`. This is what broke every
  first-ever Walk-in Customer sale (confirmed live: the HL account never existed, `createSale`
  reliably 404'd on a bad/never-persisted cached `hlCustomerId`). Fixed: the bootstrap branch now
  returns `undefined` immediately and lets the trigger it just fired be the sole creator; the
  sale/purchase is left `PENDING`. **Correction from an earlier draft of this handoff:** nothing
  re-touches the referencing `sales/{id}`/`purchases/{id}` doc once the entity finishes syncing —
  `onEntityWrite` firing on the entity doc cannot re-trigger `onSaleWrite`/`onPurchaseWrite` on a
  different document. The only thing that picks the sale/purchase back up is the
  `reconcileEntities` scheduled sweep, bounded by its 3-minute staleness guard plus its 5-minute
  schedule — i.e. **up to ~8 minutes**, not "fast." This was not directly observed in this session
  (the live sale used to validate the fix was hitting the pre-existing poisoned-cache problem, a
  different failure than a fresh bootstrap) — see the self-healing follow-up below, which would
  also close this gap.
- `syncWorker.test.ts` — **new regression tests**: `resolvePartyHlCustomerId` is now exported and
  covered by two tests against a fake Firestore + a mocked `hl.ts`: bootstrapping a fresh placeholder
  returns `undefined` **without** calling `createCustomer` inline (guards the exact race just fixed),
  and an already-synced placeholder returns its cached `hlCustomerId` without re-creating anything.
- `vitest.config.ts` (**new**) — `npm test` inside `firebase/functions` was silently inheriting the
  parent `firebase/vitest.config.ts` (scoped to Firestore-rules tests only, per its own comment: "The
  functions/ codebase has its own vitest run"), so `firebase/functions`' own unit tests were never
  actually running (`npm test` reported 0 tests found). Added the missing config so `npm test`
  correctly targets `src/**/*.test.ts`.

## How to test
1. `./gradlew :desktopApp:compileKotlin` → BUILD SUCCESSFUL.
2. `./gradlew :sharedLogic:compileKotlinJvm :desktopApp:test` → `SalesViewModelTest` (10 tests, from
   #62) still green.
3. `cd firebase/functions && npm run build && npm test` → `tsc` clean; `syncWorker.test.ts` (11
   tests) now actually runs and passes.
4. Run the Desktop app (`./gradlew :desktopApp:run`), sign in, open **Sales** from the sidebar (only
   visible with `sales` permission ≠ NONE):
   - Add a phone via "+ Add phone" (picker modal, search/location filter, multi-add, "Added" toast).
   - Add a custom line via "+ Item".
   - Edit a line's price/discount — confirm the struck-through original price appears and the net
     column updates.
   - Set a whole-sale discount.
   - Pick a named customer via search, or the Walk-in quick-select button; try typing a brand-new
     name to confirm the inline "Add '\<name\>'" row appears (only if you have `profiles` MANAGE).
   - Split payment across Cash/Card/Bank; confirm the totals card's balance updates and highlights
     (green when settled, amber when a named customer carries a balance, red when over/short).
   - Confirm the sale → "Sale complete" modal → "New sale" resets the form but keeps the cached
     picker/customer lists.
5. Resize the window narrow on both Inventory and Sales screens — confirm no text wraps vertically
   and no buttons get pushed off-window.

## Acceptance criteria
- ✅ Cart-building flow (multi-phone + custom item + per-item/whole-sale discount + named/Walk-in
  customer + split payment + note + confirm) is implemented in `SalesScreen.kt` and was exercised
  live against the dev backend — confirmed via an actual `sales/{id}` Firestore doc rung up through
  this UI (walk-in, one inventory line, cash+card split, GST+PST tax lines, `balanceRemaining: "0"`).
- ✅ Original price + discount both visible — implemented (`CartLineRow`'s struck-through `listPrice`
  + separate price/discount/net columns). Not separately screenshot-verified in this handoff.
- ✅ Totals card (subtotal → per-tax-line → grand total → paid → highlighted balance) — implemented;
  the live sale doc above shows the full chain (`subtotal "500"` → GST `25.00`/PST `35.00` →
  `grandTotal "560.00"` → paid `560` → `balanceRemaining "0"`), consistent with the walk-in
  pay-in-full gate.
- ✅ "Sale complete" on-screen confirmation with "New sale" — implemented (`SaleCompleteDialog`,
  `startNewSale()`); the live sale doc's `status: "COMPLETED"` confirms the confirm flow completed.
- ⚠️ Already-sold graceful dialog — implemented (`ConfirmState.AlreadySold` → `AromexDialog`), but
  **not exercised in this session** (no concurrent-sale race was actually triggered to observe it).
- ⚠️ Light/dark + resize reflow — the Inventory top bar's resize-reflow bug (found via a live
  screenshot) is fixed and verified by that same repro; the **Sales screen itself** has not yet been
  separately screenshot-verified for narrow-width reflow or in dark theme.
- ✅ Nav item gated on `sales` VIEW (`DesktopNavSidebar`'s `canViewSales` check); `RecordSaleUseCase`
  (unchanged from #61/#62) remains the sole authoritative confirm gate — the UI only reflects
  `canConfirm`/`errors` the ViewModel already computes.
- ✅ `desktopApp:compileKotlin` passes; the Desktop app was run end-to-end against the live dev
  project by the developer, including ringing up and troubleshooting real sales (not just a
  headless smoke test).

## Deviations / decisions
- **Item picker is a simplified flat list, not the literal `InventoryListViewModel` browse-table
  tree.** The #55/#57 browse table's grouped/collapsible tree (`BrowseGroupedTable` et al.) is
  private to `InventoryScreen.kt` and entangled with `InventoryListViewModel`'s state shape.
  Reproducing its full collapse/expand/auto-fit-column machinery for a modal picker (where search +
  multi-add matter more than dense browsing) wasn't a clean extraction within scope, so the picker
  is a lighter brand-grouped list sized off `SalesUiState.visibleUnits`/`products` instead — same
  search + location-filter UX, not the identical tree widget. Flagged in the original plan before
  building; not re-confirmed with the PM since no design assets were provided for this ticket.
- **No design assets were provided** (the ticket's own "Access & prerequisites" section says the PM
  supplies them out-of-band); built against `docs/brand-kit.md` + the existing Entities/Inventory
  Desktop screens' visual language (gradient-header dialogs, `FilterableDropdownField`, `MoneyCell`/
  `fieldColors()` reuse from Inventory, `PrimaryButton`, `AromexDialog`).
- **Inline add-customer (`AddCustomerInlineUseCase`) is a `SalesViewModel` change**, which the
  ticket's "Out of scope" section explicitly excludes ("Any new business logic or ViewModel
  changes — consume T2 as-is"). Added anyway at explicit direction after live testing showed the
  checkout dropdown could only select *existing* customers, mirroring the Inventory purchase
  dialog's established add-supplier-inline pattern exactly (same permission gate, same "name only,
  flesh out later on Entities" shape).
- **The Walk-in/Unspecified-Supplier race fix and the `vitest.config.ts` fix are backend/tooling
  changes outside this ticket's own file list**, but were required to get a real sale to sync to HL
  at all during testing — without them, every first-ever Walk-in sale failed permanently (a bad
  `hlCustomerId` gets cached and trusted forever once set).

## Post-review fixes
A manager-review pass on the original PR found 8 issues; all 8 are fixed on this branch:
1. **Whole-sale discount field wasn't locked during `Submitting`** (`SalesScreen.kt`) — every other
   cart/checkout input threaded `readOnly = !enabled` except this one `MoneyCell`. Fixed: now passes
   `readOnly = !enabled` like the rest.
2. **The UI was computing per-line net/discounted-ness itself** (`Money.subtract`/`Money.compare` +
   a duplicated `orZero()` helper in `SalesScreen.kt`), which is exactly the "business logic in the
   UI" `/kmp-arch` forbids — T2's `CartLine` never exposed this. Fixed by relocating it to the
   model: `CartLine.netAmount` and `CartLine.Inventory.isDiscounted` are now computed properties on
   the (already T3-touched) `SalesViewModel.kt`; `SalesScreen.kt` just reads them, and the
   duplicated `String.safeOrZero()` helper in the UI file is deleted.
3. **This handoff previously overstated the walk-in-race fix's recovery speed** ("fast... no
   5-minute reconcile wait needed") — corrected above, under Files changed → Server, to state the
   actual bound (~8 minutes, via the reconcile sweep) and that the fast path was never directly
   observed.
4. **No regression test existed for the race fix.** `resolvePartyHlCustomerId` is now exported and
   covered by two new tests in `syncWorker.test.ts` (against a fake Firestore + a mocked `hl.ts`):
   bootstrapping a fresh placeholder returns `undefined` **without** calling `createCustomer`
   inline, and an already-synced placeholder returns its cached id without re-creating anything.
5. **Hardcoded `"Cart"` section title** (`SalesScreen.kt`) — added the missing `sales_cart_title`
   key to `Strings.kt`/`EnglishStrings.kt` and routed it through `strings(...)`.
6. **Money fields didn't actually enforce numeric input** — `KeyboardType.Decimal` only hints a
   soft keyboard, so a physical keyboard could type letters into Cash/Card/Bank or the custom-line
   price field. Added a `String.filterToDecimalInput()` helper (digits + at most one decimal point)
   and applied it to `MoneyInputField`'s and the custom-line dialog's `onValueChange`.
7. **Redundant permission check** in `DesktopNavSidebar.kt`'s `canViewSales` — simplified from
   `session?.permissions?.sales != null && session.permissions.sales != NONE` to
   `session != null && session.permissions.sales != NONE` (the first clause was always true
   whenever `session` was non-null, since `permissions` is non-nullable).
8. **Hardcoded fallback error string** in `SalesViewModel.addNewCustomer`'s `onFailure` — added
   `Strings.sales_error_add_customer_generic` and resolved it via `LocalizationRegistry.get("en",
   ...)` (the ViewModel isn't `@Composable`, so it can't call `strings(...)` directly, but
   `LocalizationRegistry` is plain Kotlin and reads the same `EnglishStrings` table).

All builds/tests re-verified after these fixes: `desktopApp:compileKotlin` clean (no warnings),
`desktopApp:test` green, `firebase/functions` `npm run build && npm test` green (13 tests, up from
11 — the two new regression tests).

## Open questions / follow-ups
- The already-poisoned `entities/walk-in-customer` Firestore doc from **before** the race fix was
  deployed still needs a one-time manual repair (clear `hlCustomerId`, reset `syncStatus` to
  `PENDING`) so it re-syncs cleanly — this is a data fix, not a code fix, and is on the developer to
  apply directly in the Firestore console.
- Suggested (not yet built): harden `syncSale`/`syncPurchase` so a `404`/"customer not found" from
  HL on a *cached* `hlCustomerId` automatically clears it and re-syncs, instead of trusting a bad
  cached id forever. Would make this class of failure self-healing without a manual Firestore edit.
- Sales-screen-specific resize/dark-theme verification (see Acceptance criteria above) is still
  outstanding.
- Already-sold race path is implemented but unexercised — worth a deliberate two-cashier test before
  sign-off.
- Android/iOS Sales UI (T4) and any printed receipt/returns/refunds work remain out of scope, per the
  ticket.
