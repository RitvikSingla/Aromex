# Handoff — Ticket #64

**Ticket:** #64 — [M5] Sales T4 — Android + iOS UI (bare-but-stable)
**Brief:** #60
**Branch:** `ticket-64-sales-phone-ui`
**Platforms:** Android (Jetpack Compose), iOS (SwiftUI), plus a small cross-platform repository fix
surfaced by live testing (Android/Desktop/iOS).

## Summary
Adds bare, functional Sales screens on Android and iOS, bound to T2's existing `SalesViewModel`s
with no new business logic — rendering + action dispatch only. Both screens are a single scrolling
layout (stock Material3 / stock SwiftUI, no theming) covering the full capability set: cart of
in-stock phones + custom lines with editable price/discount, whole-sale discount, named-customer or
Walk-in selection, split Cash/Card/Bank payment, note, live totals, and Confirm gated on
`canConfirm`. Item and customer selection are full-screen/sheet pickers with search. Confirm
outcomes (`Success`/`AlreadySold`/`Error`) are surfaced via stock dialogs/alerts, never a crash.
While testing an end-to-end sale on a real Android device, we hit a `PERMISSION_DENIED` on sale
creation that turned out to be a pre-existing bug in `BackendSalesRepository` on **all three**
platforms (from ticket #61) — fixed as part of this ticket since it blocked the acceptance criterion
that a sale must actually complete, not just avoid crashing.

## Files changed

### Android (`androidApp`)
- `ui/sales/SalesScreen.kt` (**new**) — `SalesFeature` (binds the VM, gates on `sales` VIEW in the
  UI layer only) + `SalesScreen`: cart rows (numeric price/discount fields, remove), Add
  phone/Add item, whole-sale discount, customer picker + Walk-in shortcut, Cash/Card/Bank fields
  (`KeyboardType.Decimal` + `ImeAction`), note, plain totals rows, Confirm (progress state while
  `Submitting`). Item picker and customer picker are full-screen `Dialog`s with a search field over
  a `LazyColumn`. Add-custom-line and the `Success`/`AlreadySold` outcomes use stock `AlertDialog`;
  `Error` surfaces via `Snackbar`. Reuses the `sales_*` i18n keys already defined for T3 — no new
  strings.
- `navigation/Route.kt` — adds `Sales` to the top-level route enum.
- `navigation/AromexApp.kt` — wires the `Route.Sales` branch to `SalesFeature` and threads
  `onOpenSales` through `HomeRoute`.
- `ui/home/HomeScreen.kt` — adds a "Sales" entry button alongside the existing Entities/Inventory
  buttons.
- `data/BackendSalesRepository.kt` — **bug fix**, see below.

### iOS (`iosApp`)
- `ui/SalesView.swift` (**new**) — stock SwiftUI `Form` bound to `SalesViewModel`; same UI-layer
  permission gate as Android. Sections for cart, customer, payment, note, totals, confirm. Item and
  customer pickers are `.sheet`s with `.searchable` `List`s. A shared `MoneyField` helper gives every
  money field `.decimalPad` + a keyboard "Done" toolbar accessory. Confirm outcomes
  (`Success`/`AlreadySold`/`Error`) are `.alert`s.
- `ui/HomeView.swift` — adds a `salesLink` (`fullScreenCover`) mirroring `entitiesLink`/
  `inventoryLink`.
- `repository/BackendSalesRepository.swift` — **bug fix**, see below.

### Desktop (`desktopApp`)
- `data/BackendSalesRepository.kt` — same **bug fix** applied for consistency, even though Desktop's
  Admin SDK bypasses Firestore rules and was never actually broken by this.

## Bug fix: sale creation denied by Firestore rules on rules-enforced clients
`firebase/firestore.rules`'s `sales/{saleId}` create rule requires `hlSyncedAt` to be **absent**
from the write (`!('hlSyncedAt' in request.resource.data)`) — only the `onSaleWrite` Cloud Function
is meant to add HL-sync fields, after the fact. All three `BackendSalesRepository` implementations
wrote `hlSaleId`/`hlSyncedAt`/`hlSyncError` as explicit `null` in the create payload; in Firestore, a
field set to `null` still counts as present, so the rule evaluated false and the whole sale
transaction (including the mark-sold serial updates) was rejected with `PERMISSION_DENIED` —
regardless of the caller's actual `sales`/`inventory` permission levels. Desktop never surfaced this
because it writes via the Firebase Admin SDK, which bypasses security rules entirely (per
CLAUDE.md); Android/iOS use the native, rules-enforced SDK, so this ticket's mobile testing is what
caught it. Fixed by omitting the three fields at creation on all three platforms, matching the
already-working `purchases/{id}` precedent (ticket #58), which never wrote them in the first place.
No changes were needed to `firestore.rules` or the Cloud Function.

## How to test
1. **Android:** `./gradlew :androidApp:compileDebugKotlin` (passes). Run on an emulator/device
   signed in with a `sales` VIEW-or-above account: Home → Sales → add an in-stock phone via the
   picker, add a custom line, apply a line/whole-sale discount, pick a named customer (or Walk-in),
   split payment across Cash/Card/Bank, add a note, Confirm. Verify: Confirm disabled until
   `canConfirm`; numeric keypad + Done on money fields; Success/AlreadySold/Error paths render
   without crashing; rotate the device mid-entry and confirm cart/inputs survive.
2. **iOS:** `pod install` in `iosApp` if needed, then build the `iosApp` scheme
   (`xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator build` passes).
   Run in the simulator: Home → Sales, same flow as above; verify `.decimalPad` + the keyboard
   "Done" button, the `.searchable` item/customer sheets, and the three `.alert` outcomes.
3. **Shared:** `./gradlew :sharedLogic:jvmTest` stays green (no shared-logic changes in this ticket).
4. **Regression check for the rules bug:** on a device/simulator, complete a sale end-to-end and
   confirm no `PERMISSION_DENIED` — the `sales/{id}` doc should be created and the sold unit(s)
   flip to `SOLD` in the same transaction.

## Acceptance criteria
- [x] A cashier can ring a full sale (multi-phone + split payment, walk-in pay-in-full, partial
      named-customer) and see Success/AlreadySold without crashing — verified end-to-end on both
      Android and iOS after the repository fix.
- [x] Both screens use default platform components in a single scrolling layout; money fields use a
      numeric keypad with a Done affordance (Android `KeyboardActions`/iOS keyboard toolbar).
- [x] Walk-in cannot be confirmed while short-paid; discounts/totals reflect VM state — enforced
      entirely by T2's `canConfirm`/`errors`, unchanged here.
- [x] Light/dark render via stock theme defaults (no hardcoded colors); safe-area insets respected
      via `Scaffold`/`NavigationStack` defaults; cart/inputs live in the VM (`viewModelScope`/
      `@StateObject`), which survives rotation/config change.
- [x] `androidApp:compileDebugKotlin` passes; iOS builds in Xcode after `pod install`;
      `sharedLogic:jvmTest` stays green.

## Deviations / decisions
- **No inline "add customer"** — Desktop's T3 `SalesViewModel` (this branch's sibling, not the T2
  base) added `canAddCustomerInline()`/`addNewCustomer()`; Android/iOS's T2 `SalesViewModel`s don't
  have this, and the ticket said to consume T2 as-is, so phone customer selection is pick-from-list +
  Walk-in only, no inline add.
- **Permission gate lives in the UI layer, not the ViewModel** — Inventory/Entities gate inside their
  ViewModels' `bind()` (a `noAccess` flag). `SalesViewModel` has no such flag and the ticket's scope
  explicitly excludes new ViewModel logic, so `SalesFeature`/`SalesView` check
  `session.permissions.sales == PermissionLevel.NONE` directly before rendering the form.
- **Item/customer pickers are full-screen (Android `Dialog`) / sheet (iOS)**, not the Desktop
  browse-table — ticket explicitly calls this out as deferred to the later polish ticket.
- **Sale-repository bug fix included in this branch** rather than filed separately — it's a small,
  contained fix (omit three fields) blocking this ticket's own acceptance criteria, with no shared
  or rules changes.

## Open questions / follow-ups
- Manual end-to-end verification of both platforms (multi-phone sale, walk-in pay-in-full, partial
  named-customer) has since been confirmed working by the developer on Android and iOS.
- Visual polish, the browse-table picker, and tablet/landscape layouts are explicitly deferred to
  the later phone-polish ticket (off T3), per this ticket's scope.

## Post-review fixes
A manager-review pass found five issues, all fixed on this branch before merge:
- **Customer picker had no empty-state message** on either platform when a search matched nothing
  (unlike the item picker, which already handled this) — both now show the existing
  `inventory_dropdown_no_results` ("No results") string instead of a silent blank list.
- **Android touch targets below the ticket's 48dp minimum** — the item-picker "Add" button and the
  Add phone/Add item buttons defaulted to Material3's ~40dp; all three now enforce `heightIn(min =
  48.dp)`.
- **Android Success dialog reset the form on an outside tap/back-press** (`onDismissRequest` was
  wired to `startNewSale()`); it's now a no-op, so only the explicit "New sale" button resets —
  matching iOS, where a native `.alert` can't be dismissed any other way.
- **Neither bare `MoneyField` filtered hardware-keyboard input** — `KeyboardType.Decimal`/
  `.decimalPad` only hint a soft keyboard; a Bluetooth/attached keyboard could type letters into a
  money field. Both platforms now filter to digits + at most one decimal point, mirroring the fix
  Desktop's T3 `SalesScreen.kt` already had for the same reason.
- **iOS alreadySold/error alerts double-called `dismissConfirmState()`** — SwiftUI sets an alert's
  `isPresented` binding to `false` after any button's own action already runs, so wiring the
  binding's `set` to the same call fired it twice (harmless today since the call is idempotent, but
  fragile). The binding setters are now no-ops; the button actions are the single source of truth.
