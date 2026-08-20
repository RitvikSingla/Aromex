# Handoff — Ticket #72

**Ticket:** #72 — [QA] Desktop Sales — verify dark-mode/narrow-resize + the already-sold race dialog

## Summary
QA/verification of the two Sales-T3 (#67) acceptance items the author shipped but never exercised by hand, fixing them where they didn't hold. **Dark mode** was verified correct by inspection (the whole screen reads `AromexTheme.colors.*`; hardcoded `Color.White`s sit only on brand-colored fills) — no code change. **Narrow reflow** was a real defect: the two weighted panes plus fixed-width cart columns clipped as the window shrank toward its 420 dp minimum — now the panes stack vertically below ~860 dp and each cart row degrades to a compact wrapped layout below ~520 dp, so nothing clips. The **already-sold race** was also hardened: two simultaneous same-unit sales could livelock and both exhaust Firestore's default 5 transaction retries, surfacing a raw `FirestoreException: too many retries` instead of the graceful dialog. Retries were raised to 12 and any non-`AlreadySoldException` transaction failure now re-reads stock to classify it as either a genuinely-lost race (`AlreadySoldException` → "removed from cart" dialog) or transient contention (new `SaleContentionException` → calm "please try again"). A regression test covers the contention path.

## Files changed
**Shared logic**
- `sharedLogic/.../model/SaleContentionException.kt` *(new)* — domain exception for "the sale lost to write contention but nothing was sold; retry", so the Firestore type never leaks to the ViewModel (mirrors the existing `AlreadySoldException` pattern).

**Desktop**
- `desktopApp/.../data/BackendSalesRepository.kt` — raised the sale transaction's `numberOfAttempts` to `MAX_TXN_ATTEMPTS = 12` via `TransactionOptions`; wrapped `runTransaction` in try/catch so `AlreadySoldException` passes through and any other failure routes to a new `classifyContentionFailure` helper that re-reads each unit outside the transaction → `AlreadySoldException` (a unit is gone) or `SaleContentionException` (all still in stock), falling back to the original error if the re-read itself fails.
- `desktopApp/.../ui/sales/SalesScreen.kt` — two-pane counter wrapped in `BoxWithConstraints`: side-by-side ≥860 dp, stacked (weighted `Column`) below it; `CartLineRow` refactored into reusable cell composables (`LineIcon`/`LineIdentity`/`PriceCell`/`DiscountCell`/`NetCell`/`RemoveButton`) with a `BoxWithConstraints` branch that renders a compact wrapped layout below 520 dp and the original fixed-column row above it.
- `desktopApp/.../ui/sales/SalesViewModel.kt` — added an `is SaleContentionException ->` branch in `confirmSale`'s failure handling that shows "Another sale is being completed for one of these items. Please try again." instead of the raw exception.

**Test**
- `desktopApp/.../ui/sales/SalesViewModelTest.kt` — `FakeSalesRepository` can now throw `SaleContentionException`; new `confirmSale_contention_showsRetryError_keepsCart_neverCrashes` asserts a friendly "try again" `Error` state and that the cart is left intact.

## How to test
1. **Automated:** `./gradlew :desktopApp:test --tests "com.humblesolutions.aromex.ui.sales.SalesViewModelTest"` — includes the already-sold and new contention cases (all green).
2. **Dark mode:** macOS System Settings → Appearance → Dark; open the desktop Sales screen and confirm cart, checkout, totals, and the picker/custom/success dialogs are all legible (no white-on-white / black-on-black).
3. **Narrow reflow:** drag the window inward toward the ~420 dp minimum — below ~860 dp the checkout pane drops below the cart, and cart rows go compact (price/discount/net wrap under the item name) with no text/field clipping.
4. **Already-sold race:** with two desktop sessions, put the same IMEI in both carts and confirm both at once → one shows Sale Complete, the other the graceful "already sold — removed from cart" dialog. To force the contention branch on demand, temporarily set `MAX_TXN_ATTEMPTS = 1`, reproduce, and confirm the calm "please try again" message (then revert).

## Acceptance criteria
- **Light/dark + narrow-window reflow renders correctly** — ✅ met (dark verified as correct by inspection; narrow reflow now stacks + compacts so nothing clips).
- **Already-sold race dialog appears; line removed; never a crash or silent drop** — ✅ met, and hardened: the contention/"too many retries" path that previously showed a raw error now degrades gracefully (re-check → dialog or calm retry), never a crash.

## Deviations / decisions
- The ticket framed this as verify-and-fix-if-broken. Dark mode needed no change; the **narrow reflow** and an additional **contention/livelock** failure mode (surfaced during hand-testing) did — the latter is a small scope extension beyond the literal "race dialog" item, kept because it's the same acceptance intent ("never a crash or silent drop").
- Contention handling is **Desktop-only** (the ticket's platform). Android's `BackendSalesRepository` still uses default retries and does not throw `SaleContentionException` — see follow-ups.

## Open questions / follow-ups
- **Android parity:** apply the same retry bump + `SaleContentionException` classification to `androidApp` if we want identical race UX there (separate ticket).
- **Loser's valid lines:** when the race loser rolls back, its *other* (valid) cart lines are also not sold and need a manual re-confirm — acceptable today, but worth a product decision if auto-completing them is desired.
