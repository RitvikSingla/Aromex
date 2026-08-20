---
name: Feature / Task ticket
about: A unit of work for a developer (built with Claude Code)
title: "[M5] Sales T4 — Android + iOS UI (bare-but-stable)"
labels: []
assignees: []
---

**Brief:** #60

## 📖 Story / Why
Sales must run on **all** platforms (PRD §9.4: Sales is on iPhone too). This ticket delivers
**functional Sales screens on Android and iOS** — feature-complete in capability but with default
styling and **no polish**. It closes the brief's non-negotiable: "all three platforms stable ·
all three ViewModels · all three compiling · **bare functional UI**." Phone visual polish comes
later, built off the Desktop reference (T3).

## 🧭 Context
- **`/kmp-arch`:** Android Jetpack Compose + iOS SwiftUI, each bound to its T2 `SalesViewModel`;
  rendering + action dispatch only, **no business logic in the UI**.
- **"Bare functional" is the explicit bar** (brief): default Material3 (Android) / stock SwiftUI
  `Form`/`List` (iOS), a **single scrolling screen** (phone form factor), **no** gradient/card
  theming, **no** animations. It must **compile, run, and be able to ring a full sale without
  crashing** — not be pretty.
- **Full capability parity:** the same sale the Desktop can do (multi-phone cart, price/discount
  edits, custom line, named-customer or walk-in, split cash/card/bank, partial/full, tax, note,
  confirm) — just plain.
- **iOS shared bridging:** follow the **SKIE path from #34**. Re-verify against current code.

## 🔑 Access & prerequisites
- **T2 merged** (Android + iOS `SalesViewModel`).
- Android emulator; iOS simulator + Xcode (`pod install` in `iosApp` first).
- Firebase dev config (team password manager / manager).
- **No design assets required** — this ticket is explicitly **"build it bare yourself"** with
  default components; do **not** block on a Figma. Polish is a later ticket.

## ✅ Scope / What to build
### Android (Jetpack Compose)
- [ ] `SalesScreen(viewModel)` — `uiState` via `collectAsStateWithLifecycle()`; a single
      scrollable `Column`: cart-line rows (editable price + discount `TextField`s, remove),
      "Add phone" / "Add item", whole-sale discount, customer dropdown, Cash/Card/Bank fields,
      note, a plain totals block, Confirm (disabled unless `canConfirm`, `errors` as plain `Text`).
- [ ] **Item picker:** a full-screen or bottom-sheet **`LazyColumn`** of `visibleUnits` with a
      search field (plain rows — **not** the Desktop table).
- [ ] **Confirm outcomes:** `Success` → `AlertDialog` ("Sale complete" + "New sale"); `AlreadySold`
      → `AlertDialog` naming the phone; `Error` → `Snackbar`.
- [ ] Register a `Sales` destination in the Android nav graph; gate on `sales` VIEW.

### iOS (SwiftUI)
- [ ] `SalesView` bound to `SalesViewModel` (`@StateObject`); a `Form`/`List` single screen with
      sections for cart, discount, customer, payment, note, totals, confirm — stock controls.
- [ ] **Item picker:** a `.sheet` with a searchable `List` of `visibleUnits`.
- [ ] **Confirm outcomes:** `.alert` for Success (+ "New sale") and AlreadySold; inline error /
      alert for Error.
- [ ] Register in `AppDestination`; gate on `sales`. Observe shared `Flow`s via the **#34 SKIE
      path**.

## 🖼️ UI standards (reduced — this is an explicit bare pass)
Full visual polish is **deferred**; these still apply because they're about *stability*, not looks:
- [ ] **Native components only** — stock Material3 / stock SwiftUI. No hand-rolled chrome.
- [ ] **Both light and dark** must render without broken/unreadable colors (use theme defaults —
      don't hardcode colors that break in one mode).
- [ ] **Safe areas / insets:** no content or tap target under the status bar, notch, home
      indicator, or Android gesture/nav bar; the screen scrolls edge-to-edge without obscuring
      content.
- [ ] **Keyboard:** numeric keypad for all money fields (price, discount, cash/card/bank); a Done
      accessory to dismiss the number pad; **keep the focused field visible** above the keyboard;
      dismiss on tap-outside/scroll.
- [ ] **Correct truncation** — long labels/IMEIs ellipsize, don't overflow the row.
- [ ] **States:** loading / empty (no in-stock units, no customers) / error / disabled all render;
      Confirm disabled + progress during `Submitting`; errors surfaced (not raw dumps or silent
      failures).
- [ ] **Preserve state** across rotation / config change / process death — cart, inputs, selection.
- [ ] Accessible labels on interactive elements; minimum touch target (48dp Android / 44pt iOS);
      respect dynamic type / font scaling without breaking layout.
- [ ] **No hardcoded user-facing strings** (i18n); `/kmp-arch` — **no business logic in the UI**;
      no secrets.
- [ ] *Deferred (not required here):* gradient/card theming, custom motion, the polished
      browse-table picker, tablet/landscape layouts — all come in the later polish ticket off T3.

## 🎯 Acceptance Criteria
- [ ] On **both** Android and iOS, a cashier can ring a full sale — multi-phone + split payment;
      a walk-in pay-in-full sale; a partial named-customer sale — and see the sale-complete and
      already-sold paths **without crashing**.
- [ ] Both screens use default platform components, single scrolling layout; money fields use a
      numeric keypad with a Done affordance and stay visible above the keyboard.
- [ ] Walk-in cannot be confirmed while short-paid; discounts and totals reflect the VM state.
- [ ] Light + dark render legibly; safe-area insets respected; state survives rotation.
- [ ] `androidApp:compileDebugKotlin` passes; iOS builds in Xcode after `pod install`;
      `sharedLogic:jvmTest` stays green.

## 🚫 Out of scope
- Visual polish / theming / animations; the polished browse-table picker on phones; tablet /
  landscape layouts (all in the later polish ticket).
- Any new shared or ViewModel logic (consume T2 as-is).
- Printed / PDF receipt; tab-collection; returns/refunds.

## 🔗 Dependencies
- **T2** (`M5-60-T2`) — Android + iOS `SalesViewModel`.
- **T3** (`M5-60-T3`) — the Desktop reference the later polish pass will trace (not blocking).

## 📚 References
- Brief: #60 · `docs/briefs/B60-sales.md` · PRD `docs/PRD.md` §9.4 (Sales on all platforms incl.
  iPhone)
- `/kmp-architecture`; #34 handoff / memory (iOS SKIE); `CLAUDE.md`

## 🤖 Kickoff prompt (paste into Claude Code)
```
/start-ticket <this-issue-number>
```
