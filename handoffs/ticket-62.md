# Handoff — Ticket #62

**Ticket:** #62 — [M5] Sales T2 — ViewModels (Android · iOS · Desktop)
**Brief:** #60
**Branch:** `ticket-62-sales-viewmodels`
**Platforms:** Android · iOS · Desktop (no shared-logic changes)

## Summary
Adds the **state + logic layer** between T1's sale engine and the (later) checkout screens — a Sales
ViewModel on all three platforms, no UI. Each VM builds a cart from cached in-stock units, edits unit
prices + line/sale discounts, picks a named customer or the injected **Walk-in Customer**, takes a
cash/card/bank split, shows live totals + tax computed **only** by T1's shared `SaleCalculator` (no
duplicated math), gates confirm with a mirrored error set, and confirms through `RecordSaleUseCase`.
The "already sold" race maps to a graceful `AlreadySold(imei,label)` state that flags/removes the
offending line rather than crashing; `startNewSale()` clears the cart but **preserves** the cached
inventory/customers/session (the #58 reset-preserves-cache lesson). Manual DI mirrors
`AddStockViewModel` per platform. The Desktop VM carries an `internal` test seam and is the JVM home
for unit tests covering the shared gating/totals behavior; Android/iOS mirror it. No new shared logic
or repository methods were added — everything consumes T1.

## Files changed

### Desktop (`desktopApp`)
- `src/main/kotlin/com/humblesolutions/aromex/ui/sales/SalesViewModel.kt` — the checkout VM (plain
  class + `CoroutineScope`): `SalesUiState`, `CartLine` (Inventory/Custom), `ConfirmState`,
  `SaleErrors`, cached pickers, `SaleCalculator`-backed derived totals, gating, `confirmSale()`,
  `startNewSale()`, manual DI in `bind()`, and an `internal bindForTest(...)` seam. `dispose()`
  closes the inventory/entity Admin-SDK clients.
- `src/test/kotlin/com/humblesolutions/aromex/ui/sales/SalesViewModelTest.kt` — 10 unit tests over
  fake repo + cached streams (see Acceptance criteria).
- `build.gradle.kts` — adds a JVM test source set: `kotlin-test`, `kotlin-test-junit`,
  `kotlinx-coroutines-test`, and `tasks.withType<Test> { useJUnit() }`.

### Android (`androidApp`)
- `src/main/kotlin/com/humblesolutions/aromex/ui/sales/SalesViewModel.kt` — the same VM as an
  `AndroidViewModel` + `StateFlow`, DI via `getApplication()` + `FirebaseClientConfig`. Mirrors the
  Desktop behavior exactly (state shape, actions, gating, submission mapping).

### iOS (`iosApp`)
- `iosApp/viewmodel/SalesViewModel.swift` — the same VM as a `@MainActor ObservableObject`; SKIE
  suspend/Flow observe path (per #34), derived totals/errors as computed properties, and typed
  Kotlin-exception handling (`AlreadySoldException`/`PermissionDeniedException` via
  `NSError.kotlinException`). Added under the file-system-synced `viewmodel/` group (no `.pbxproj`
  edit needed).

## How to test
1. **Desktop VM unit tests + shared tests:**
   `./gradlew :desktopApp:test :sharedLogic:jvmTest` → both green (10 desktop VM tests pass).
2. **Compile all three:**
   `./gradlew :androidApp:compileDebugKotlin :desktopApp:compileKotlin` → BUILD SUCCESSFUL.
3. **iOS:** `cd iosApp && pod install` (regenerates the shared framework with T1 symbols), then build
   the `iosApp` scheme for an iPhone simulator in Xcode (or
   `xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -sdk iphonesimulator -destination 'id=<sim-udid>' build`) → **BUILD SUCCEEDED**.

## Acceptance criteria
- ✅ Each platform's VM exposes the mirrored `uiState` (`isLoading`, `currency`, `taxConfig`, item
  picker, customer picker, cart, derived totals/`amountPaid`/`balanceRemaining`, `errors`,
  `confirmState`) and actions (`loadData`/`bind`, picker/customer/cart/payment/note setters,
  `confirmSale`, `startNewSale`). Live totals are **always** `SaleCalculator` output — verified by
  `totals_alwaysEqualSaleCalculator` (asserts VM totals `==` a direct `SaleCalculator.compute`).
- ✅ `canConfirm` true only with ≥1 line, a customer, no line `discount > price`, `saleDiscount ≤
  subtotal`, `amountPaid ≤ grandTotal`, and (walk-in) `amountPaid == grandTotal`; matching `errors`
  surface — `canConfirm_gating`, `lineDiscount_exceedingPrice_blocks_withOffendingLineFlagged`,
  `overpayment_blocks`, `walkIn_mustPayInFull`.
- ✅ Picker `visibleUnits` excludes cart units and non-in-stock/inactive units; selecting a unit
  snapshots `cost`/`label`/`listPrice`/`imei` from cache — `picker_excludesCartUnits_andNonInStockOrInactive`
  + the snapshot is asserted via the AlreadySold label test.
- ✅ Walk-in Customer injected as a selectable option; selecting it flips `isWalkIn` + enforces
  pay-in-full — `customerOptions_injectsWalkIn`, `walkIn_mustPayInFull`.
- ✅ `confirmSale()` maps success → `Success(saleId)` and `AlreadySoldException` →
  `AlreadySold(imei,label)` with the offending line removed, never crashing —
  `confirmSale_success_mapsToSuccessState`, `confirmSale_alreadySold_flagsAndRemovesLine_neverCrashes`.
- ✅ `startNewSale()` preserves cached inventory/entities/session — `startNewSale_preservesCache_clearsCart`.
- ✅ VM unit tests (fake use case + fake observes) cover totals wiring, `canConfirm` gating, walk-in
  pay-in-full, overpayment block, `AlreadySold` handling, reset-preserves-cache, picker exclusion.
- ✅ All three compile: `androidApp:compileDebugKotlin`, `desktopApp:compileKotlin`, iOS Xcode build
  (after `pod install`); `sharedLogic:jvmTest` stays green.

## Deviations / decisions
- **VM tests live in `desktopApp` (JVM), not per-platform.** The Desktop VM is plain Kotlin, so an
  `internal bindForTest(session, recordSaleUseCase, serialsFlow, productsFlow, entitiesFlow)` seam +
  a new `desktopApp` test source set is the least-invasive testable home for the shared
  gating/totals logic. Android/iOS mirror the same behavior but are not separately unit-tested (one
  testable implementation covers the logic; the ACs don't require test triplication). Confirmed with
  the PO before building.
- **Money is sanitized to `"0"` for live math.** A blank/partial/invalid field (the UI feeds text
  while typing) collapses to `"0"` for `SaleCalculator`/gating; `RecordSaleUseCase` does the
  authoritative validation at confirm. Prevents `Money` throwing mid-type.
- **Derived state.** Desktop/Android store `totals`+`errors` and rebuild them via a private
  `recompute()` after each mutation (matches the ticket's action list); iOS exposes them as computed
  properties (the idiomatic SwiftUI equivalent) — both guarantee no drift from `SaleCalculator`.
- **Permission-denied observe path is guarded (manager-review fix).** The observe use cases throw
  `PermissionDeniedException` **synchronously** (before returning the Flow), which `.catch` cannot
  see. All three VMs construct the observes inside their guarded launch/Task bodies —
  Desktop/Android wrap them in `runCatching` (and clear `isLoading` on failure), iOS in `do/catch` —
  so a user lacking `inventory`/`profiles` access degrades gracefully instead of crashing `bind()`.
  Covered by the `observeError_clearsLoading_neverCrashes` test.
- **Walk-in id/name hardcoded on iOS** (`"walk-in-customer"`/`"Walk-in Customer"`), mirroring how
  `AddStockViewModel.swift` hardcodes the Unspecified-Supplier id — kept in sync with the shared
  constant + Cloud Function + rules.

## Open questions / follow-ups
- **T1 gap (flagged, not grown):** `BackendSalesRepository` exposes no `close()` (unlike the
  inventory/entity repos), so the Desktop VM's `dispose()` cannot close its Admin-SDK Firestore
  client. Impact is minor — that client is created lazily only on an actual sale — but a small T1
  follow-up should add `close()` for symmetry. Noted in a `dispose()` code comment.
- **Screens are out of scope** (T3 Desktop, T4 phones) — these VMs are the seam they'll bind to.
- `pod install` produced no tracked changes in this branch; the framework regeneration is a local
  build artifact.
