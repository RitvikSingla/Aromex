---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M5] Sales T2 — ViewModels (Android · iOS · Desktop)"
labels: []
assignees: []
---

**Brief:** #60

## 📖 Story / Why
T1 built the backend spine that atomically sells phones and posts the books. This ticket adds the
**state + logic layer** between that use case and the UI: the cart/checkout ViewModel on **all
three platforms** — build a cart, edit prices, apply discounts, pick a customer or walk-in, enter
a split payment, see live totals + tax, confirm, and handle the "already sold" race gracefully.
No screens yet (T3 Desktop, T4 phones). This is the seam that lets **all UI land at the very end**
while keeping the non-negotiable "all three ViewModels + all three compiling" true now.

## 🧭 Context
- **`/kmp-arch` (see `CLAUDE.md` + `/kmp-architecture`):** per-platform native ViewModels, **manual
  DI**, no DI framework, no shared ViewModels. Android `AndroidViewModel` + `StateFlow` +
  `viewModelScope`; iOS `@MainActor ObservableObject` + `@Published` + `Task`; Desktop
  Compose-Desktop VM + `StateFlow`. **The ViewModel is the cache** — fetch once on init, filter
  client-side.
- **Mirror `AddStockViewModel` (#58)** for the wiring, the synthetic-default injection (it injects
  the Unspecified Supplier; we inject the Walk-in Customer), and the **reset-preserves-cache**
  bug-fix (a prior bug wiped the observed entities/currency and emptied the dropdown).
- **Consumes T1:** `RecordSaleUseCase`, `SaleInput`/`SaleLineInput`/`PaymentInput`,
  `SaleCalculator`/`SaleTotals`, `TaxConfig`, `AlreadySoldException`, `WalkInCustomer`. Reuses the
  existing inventory observe (#55/#57 / `ObserveInventoryUseCase`) and `ObserveEntitiesUseCase`.
- **iOS shared bridging:** follow the **SKIE path from #34** (the `__`-prefix suspend-impl rule +
  the shared-`Flow` observe path). Re-verify that convention against current code before relying
  on it.

## 🔑 Access & prerequisites
- Same Firebase test project as T1 (to run the observes against real data). Config from the team
  password manager / manager.
- **T1 merged** (or its branch available) so `RecordSaleUseCase` + `SaleCalculator` exist.
- iOS: `pod install` in `iosApp` so the shared framework regenerates with T1's new symbols.

## ✅ Scope / What to build
- [ ] **`SaleCalculator` dependency confirmed in shared** (from T1) — the VMs call it for live
      totals; **do not re-implement the math** in any ViewModel.
- [ ] **Sales ViewModel on each platform** with a mirrored `uiState`:
  - `isLoading`, `currency`, `taxConfig` (from `UserSession`)
  - **item picker:** `allInStockUnits` (cached), `pickerSearchQuery`, `pickerLocationFilter`,
    `visibleUnits` — filtered client-side, **excluding serialIds already in the cart** and any
    non-`IN_STOCK`/inactive unit (reuse the #55/#57 browse filter model)
  - **customer picker:** `allCustomers` (cached, **incl. injected Walk-in Customer**),
    `customerSearchQuery`, `selectedCustomer`, derived `isWalkIn`
  - **cart:** `cartLines` (INVENTORY: serialId, label, listPrice, unitPrice, lineDiscount, cost;
    or CUSTOM: name, unitPrice, lineDiscount), `saleDiscount`, `payments{cash,card,bank}`, `note`
  - **derived:** `totals: SaleTotals`, `amountPaid`, `balanceRemaining`
  - **gating:** `canConfirm`, `errors{emptyCart, noCustomer, lineDiscountExceedsPrice(lineId),
    saleDiscountExceedsSubtotal, overpayment, walkInMustPayInFull}`
  - **submission:** `confirmState: Idle | Submitting | Success(saleId) | AlreadySold(imei,label) |
    Error(message)`
- [ ] **Actions (mirror on all three):** `loadData()` (parallel fetch inventory + entities; read
      session tax/currency) · picker `onPickerSearchChanged`/`onPickerLocationFilterChanged`/
      `addUnitToCart(serialId)` · `addCustomLine(name, price)` · `setUnitPrice`/`setLineDiscount`/
      `removeLine(lineId)` · `setSaleDiscount` · `selectCustomer`/`selectWalkIn` ·
      `setCash`/`setCard`/`setBank` · `setNote` · private `recompute()` (→ `SaleCalculator` +
      revalidate) · `confirmSale()` · `startNewSale()`.
- [ ] **`confirmSale()`** builds `SaleInput` + `ResolvedSaleLine` snapshots from the cached units →
      `RecordSaleUseCase`; on `AlreadySoldException(imei)` → `AlreadySold` state **and flag/remove
      the offending line**; on `PermissionDeniedException`/other → `Error`.
- [ ] **`startNewSale()`** clears cart/customer/payments/note but **preserves** cached
      inventory/entities/session (the #58 reset-preserves-cache lesson).
- [ ] **Manual DI per platform** (mirror `AddStockViewModel`): `authRepo →
      BackendSalesRepository(authRepo)` + inventory observe + entity observe →
      `RecordSaleUseCase(salesRepo)`; session supplies `currency`/`taxConfig`.

## 🎯 Acceptance Criteria
- [ ] Each platform's ViewModel exposes the mirrored `uiState` and actions above; **live totals
      always equal `SaleCalculator` output** (no duplicated math).
- [ ] `canConfirm` is true only when ≥1 line, a customer is selected, no line `discount > price`,
      `saleDiscount ≤ subtotal`, `amountPaid ≤ grandTotal`, **and (walk-in) `amountPaid ==
      grandTotal`**; the matching `errors` surface for the UI.
- [ ] The item picker's `visibleUnits` excludes cart units and non-in-stock/inactive units;
      selecting a unit snapshots its `cost`/`label`/`listPrice` from cache.
- [ ] Walk-in Customer is injected as a selectable option; selecting it flips `isWalkIn` and
      enforces pay-in-full gating.
- [ ] `confirmSale()` maps success → `Success(saleId)` and `AlreadySoldException` →
      `AlreadySold(imei,label)` with the line flagged — **never an unhandled crash**.
- [ ] `startNewSale()` preserves the cached inventory/entities/session.
- [ ] VM unit tests (fake use case + fake observes) cover: totals wiring, `canConfirm` gating,
      walk-in pay-in-full, overpayment block, `AlreadySold` handling, reset-preserves-cache,
      picker exclusion.
- [ ] **All three compile:** `androidApp:compileDebugKotlin`, `desktopApp:compileKotlin`, iOS Xcode
      build (after `pod install`). `sharedLogic:jvmTest` stays green.

## 🚫 Out of scope
- Any screens / Compose / SwiftUI (T3 Desktop, T4 phones).
- New shared logic or new repository methods (all provided by T1). If a genuine gap surfaces,
  flag it — don't grow T1's contract silently.

## 🔗 Dependencies
- **T1** (`M5-60-T1`) — provides `RecordSaleUseCase`, `SaleCalculator`, the shared models.

## 📚 References
- Brief: #60 · `docs/briefs/B60-sales.md`
- `/kmp-architecture` skill (caching, manual DI, per-platform VM patterns)
- `#58` `AddStockViewModel.*` (wiring, synthetic default, reset-preserves-cache)
- #34 handoff / memory — iOS SKIE suspend + shared-Flow observe path
- `CLAUDE.md`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
