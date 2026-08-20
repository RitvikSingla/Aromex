# Handoff — Ticket #51

**Ticket:** #51 — [UI] Add Inventory flow — batch entry + review-before-write (Android · iOS · Desktop)

## Summary

Replaces the bare M4 add-stock test screens with the **full, polished Add-Inventory UX** on all three platforms. The M4 shared logic (`AddStockUseCase`, `AddUnitsUseCase`, the race-safe transaction, `DuplicateImeiException`) is reused as-is. New shared additions are thin: one use case (`CheckImeiAvailabilityUseCase`) for the advisory IMEI pre-check, a `checkImeiAvailability` method on `InventoryRepository`, all inventory i18n strings, and `skuLabel` helpers on `AttributeName`. Each platform ships its own `FilterableDropdownField` (inline searchable dropdown with add-new-inline), its own ViewModels, and its own two-screen flow:

**Screen 1 — Entry:** pick the SKU (Brand → Model → Capacity → Color → Carrier, each filterable + add-new-inline), set Selling Price, enter batch Cost / Condition / Location once, then scan/type IMEIs one-by-one. Each IMEI gets an advisory availability pre-check (duplicate-in-batch + already-in-stock); errors are shown inline. A live IMEI list with per-row remove. **Review** action in the header activates when SKU + batch details are complete and ≥ 1 IMEI is staged.

**Screen 2 — Review:** the full batch displayed before anything is written. Per-unit overrides (edit IMEI / cost / condition / location via an edit sheet/dialog, or delete a unit). **Confirm** triggers `AddStockUseCase`/`AddUnitsUseCase` (atomic write); on `DuplicateImeiException` or network failure the screen stays and shows an error banner — nothing is partially written.

Platform notes:
- **Android:** `NavHost` with typed `@Serializable` routes (`List/Detail/AddInventory`), `rememberSaveable` for transient booleans, keyboard-aware `LazyColumn` via `WindowInsets.ime` (Review button pinned at screen bottom, not pushed up with keyboard), CameraX scan-to-add wired into the entry flow.
- **Desktop:** side-panel layout; `EditUnitDialog` overlay for per-unit edits; table-style review with column headers; Escape closes dialogs.
- **iOS:** `NavigationStack` + `fullScreenCover` routing; Review button pinned at screen bottom via `.overlay + .ignoresSafeArea(.keyboard)` with `NotificationCenter` keyboard-height tracking for scroll content padding; keyboard toolbar "Add"/"Done" buttons on IMEI fields; `DropdownItemStyle` touch-down guard prevents double-select in the inline dropdown.

## Files changed

### sharedLogic
- `usecase/CheckImeiAvailabilityUseCase.kt` **(new)** — advisory pre-check: delegates to `InventoryRepository.checkImeiAvailability`; returns `ImeiCheckState` (AVAILABLE / INVALID / ALREADY_IN_BATCH / ALREADY_IN_STOCK).
- `repository/InventoryRepository.kt` — added `suspend fun checkImeiAvailability(imei: String): ImeiCheckState`.
- `i18n/Strings.kt` + `i18n/EnglishStrings.kt` — all `inventory_*` strings (labels, placeholders, errors, section headers, action names, accessibility labels).
- `util/AttributeName.kt` — `skuLabel()` / `skuLabel` extension/computed-property on attribute maps; used by all platforms to render a consistent SKU display name.
- `inventory/AttributeNameTest.kt` + `inventory/InventoryTestFakes.kt` — updated fakes/tests for the new `checkImeiAvailability` interface method.

### Android
- `ui/components/FilterableDropdownField.kt` **(new)** — Compose inline filterable dropdown; `AnimatedVisibility` drop-list below the field; add-new-inline row when no exact match; `enabled` / `onAddNew` nullability gates the add path.
- `ui/inventory/AddStockViewModel.kt` — extended: attribute pickers with `options(type)` (model filtered by brand), `setPrice`, `setBatchCost/Condition/Location`, `checkAndAddImei` (delegates to `CheckImeiAvailabilityUseCase`), `removeImei`, `proceedToReview` (builds `ReviewUnit` list), `addMoreFromReview`, `startAddUnits` (locked-SKU path), `reset`.
- `ui/inventory/InventoryScreen.kt` — `InventoryFeature` now uses a `NavHost` with typed `InventoryRoute` (List / Detail / AddInventory); `AddInventoryScreen` routes between `AddInventoryEntryScreen` and `ReviewConfirmScreen`; `rememberSaveable` on `showDiscardDialog`, `scanning`, `editingIndex`; keyboard-aware `LazyColumn` (no `imePadding()` on the outer Column; `WindowInsets.ime` bottom padding only on the list content so the Review button stays pinned); `EditUnitDialog` for per-unit overrides on the review screen.
- `data/BackendInventoryRepository.kt` — implemented `checkImeiAvailability` (Firestore `imeiIndex` lookup).

### Desktop
- `ui/components/FilterableDropdownField.kt` **(new)** — Compose-Desktop inline filterable dropdown matching Android; `pointerHoverIcon` on items.
- `ui/components/DesktopNavSidebar.kt` **(new)** — shared sidebar nav component extracted from `AromexApp.kt` during this pass.
- `ui/inventory/AddStockViewModel.kt` — same shape as Android: pickers, `checkAndStageImei`, `startAddUnits`, dialog-local IMEI state, `saveAll`.
- `ui/inventory/InventoryScreen.kt` — full polished Desktop flow: `AddStockPanel` (list of review units + "Add unit" button → `EditUnitDialog` overlay); `EditUnitDialog` with gradient header, all SKU pickers (pre-filled when locked), cost/condition/location fields, IMEI batch entry with Enter-key staging; review table with grouped SKU headers, per-row edit/delete; Confirm triggers `AddStockUseCase`/`AddUnitsUseCase`; error banner on duplicate/network failure; Escape dismisses dialogs.
- `ui/entities/EntitiesScreen.kt` — minor alignment/style fixes during the design-system pass.
- `navigation/AromexApp.kt` — wires `AddStockViewModel` into the signed-in Home alongside the list VM.
- `data/BackendInventoryRepository.kt` — implemented `checkImeiAvailability`.

### iOS
- `ui/components/FilterableDropdownField.swift` **(new)** — SwiftUI inline filterable dropdown; `.animation(.spring, value: expanded)` on the outer `VStack` for smooth open/close; `.animation(nil, value: searchText)` on content to keep filter updates instant; `DropdownItemStyle` sets `isSelectingItem = true` at Touch Down (before blur fires) preventing the double-select-to-close bug; keyboard toolbar "Done" button.
- `ui/InventoryView.swift` — `AddInventoryEntryView` (Screen 1) and `ReviewConfirmView` (Screen 2): Review button pinned via `.overlay(alignment: .bottom) + .ignoresSafeArea(.keyboard, edges: .bottom)`; `kbHeight` tracked via `NotificationCenter` keyboard notifications for scroll content padding; IMEI field keyboard toolbar "Add"/"Done"; `EditUnitSheet` for per-unit overrides; `@FocusState` drives toolbar visibility; discard confirmation dialog guards close/back.
- `viewmodel/AddStockViewModel.swift` — rewritten for the full flow: `@Published` picker maps, `batchCost/Condition/Location`, `pendingImei`, `stagedImeis`, `imeiCheckState`, `reviewUnits`, `route`; `checkAndAddImei` uses `CheckImeiAvailabilityUseCase`; `proceedToReview`, `addMoreFromReview`, `confirm` (async); `startAddUnits` locked-SKU path; `reset`.
- `repository/BackendInventoryRepository.swift` — implemented `checkImeiAvailability`.

## How to test

**Android / iOS:**
1. Open Inventory → tap **＋** (new SKU): pick Brand → Model → Capacity → Color → Carrier (use the search field; type a value that doesn't exist and it offers "Add …"; model list filters by the picked brand). Set Selling Price. Set Cost, Condition, Location. Add ≥ 2 IMEIs (tap ✓ or Done; invalid IMEI → inline error; already-in-stock → inline error; already-in-batch → inline error). Tap **Review**.
2. On Review: edit one unit (change cost + location); delete another; tap **Add more** to return to entry; add one back. Tap **Confirm**. Verify units appear in the SKU detail.
3. Tap a **SKU** → **Add units**: form opens pre-filled with the SKU (attributes locked, only Cost / IMEI / Location empty). Add 1 IMEI + cost + location → Review → Confirm.
4. Attempt to add an IMEI already in stock during Confirm (race): the error banner shows, nothing is partially written, the review screen stays.
5. Rotate the device mid-entry (Android) — entered text, the scan state, and the editing-unit index survive.
6. Close/back mid-entry with staged IMEIs → **discard confirmation dialog** appears.

**Desktop:**
- Same flow via the "Add unit" button in the Add Inventory panel; Escape closes the dialog; Enter stages a pending IMEI.

## Acceptance criteria (all met)

- [x] Screen 1: SKU pickers (Brand/Model/Capacity/Color/Carrier + Selling Price) with inline search and add-new-inline; model list filtered by brand.
- [x] Screen 1: Batch cost/condition/location fields; IMEI entry with ✓ button + advisory pre-check; inline error states; removable IMEI list; Review action enabled only when complete.
- [x] Screen 2: Full unit list before any write; per-unit edit (IMEI / cost / condition / location); per-unit delete; add-more back to entry.
- [x] Confirm → atomic `AddStockUseCase`/`AddUnitsUseCase` write; `DuplicateImeiException` and network errors surface as banner, nothing partially written.
- [x] Add-to-existing-SKU (from SKU detail) pre-fills attributes, locks the SKU pickers, leaves only Cost/IMEI/Location for the user.
- [x] Unsaved-changes guard on close/back when the batch is non-empty.
- [x] Android: `NavHost` typed routes; `rememberSaveable` state; keyboard-aware layout (Review button stays pinned at screen bottom).
- [x] iOS: Review button stays pinned at screen bottom regardless of keyboard state.
- [x] Desktop: table-style review; `EditUnitDialog` overlay; Escape/Enter keyboard shortcuts.
- [x] No shared UI; no `expect`/`actual`; `sharedLogic` additions are pure Kotlin with no platform imports.

## Deviations / decisions

- **`FilterableDropdownField` is per-platform, not shared.** Each platform has subtly different interaction models (iOS `@FocusState` + spring animation + touch-down guard; Android `AnimatedVisibility`; Desktop `pointerHoverIcon`) — sharing would require `expect/actual` which the architecture forbids.
- **iOS inline dropdown replaces the prior bottom-sheet picker.** The bottom-sheet approach (NavigationStack drill-in) was replaced with an inline animated dropdown matching Android, for UX consistency and to avoid a navigation-stack depth issue on the entry screen.
- **Android Review button stays at screen bottom (not pushed by keyboard).** `imePadding()` on the outer Column would lift the button; instead `WindowInsets.ime` drives only the `LazyColumn` content padding, keeping the button in place — matching iOS behavior.
- **Desktop `EditUnitDialog` holds its own local IMEI batch state** (independent of the ViewModel's `stagedImeis`) so it clears cleanly on dismiss without touching global VM state.

## Open questions / follow-ups

- **"Match Desktop flow when adding to existing SKU" UX pass** — a follow-up polish pass to ensure the pre-filled form UX is identical across platforms.
- **Scan-to-add on iOS** — the iOS `fullScreenCover` scanner sheet was wired in but not yet verified end-to-end on device; needs a real-device test run.
- **`BackendEntityRepository` self-refreshing token** — still uses the old fixed-token pattern (noted in ticket #46 handoff); a shared follow-up.
