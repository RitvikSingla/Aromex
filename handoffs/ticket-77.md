# Handoff — Ticket #77

**Ticket:** #77 — [M6] Invoicing T2 — invoice on Sale Complete + walk-in buyer capture (UI)

## Summary
Surfaces the PDF invoice that T1 (#76) auto-issues server-side. The **Sale complete** screen now
carries an invoice row that **resolves in place** — the ViewModel observes the `sales/{saleId}` doc
live and the row moves from *Preparing invoice…* (PENDING) to the **invoice number + actions**
(ISSUED) or a reassuring message + **Retry** (FAILED) as the Cloud Function finishes. The checkout
pane gains an optional **"Name for invoice"** + phone capture, shown only for the walk-in party, so a
walk-in buyer's name lands on the PDF (blank → "Walk-in Customer"); it never blocks Confirm and is
absent for named customers. Because 5 minutes (the reconcile-sweep cadence) is too slow at a live
counter, Retry is **immediate**: a new `retryInvoice` callable Cloud Function re-runs T1's existing,
idempotent issuance on demand (mobile), and Desktop's Admin SDK bumps `invoiceRetryRequestedAt` which
`onSaleWrite` now edge-triggers — both funnel into the same `retryInvoiceCore`, so no duplicate PDF is
ever produced. Desktop is polished (light+dark, View/Print/Copy link, ellipsis, reflow); Android/iOS
are bare-but-stable with stock components (number, Open, OS share sheet, Retry). No new invoice
business logic — the client only renders and dispatches; issuance stays entirely in T1's server code.

## Files changed

### Shared logic (`sharedLogic`)
- `model/SaleInvoice.kt` *(new)* — `SaleInvoice` + `SaleInvoiceStatus { PENDING, ISSUED, FAILED }`
  with `fromRaw` (absent/unknown → PENDING, never treated as failure) — the client-side projection of
  the CF-owned `invoice*` fields.
- `model/SaleInput.kt`, `model/SaleRecord.kt` — add `buyerName`/`buyerPhone` so the walk-in capture
  flows from the checkout form to the persisted sale doc.
- `repository/SalesRepository.kt` — add `observeSaleInvoice(saleId): Flow<SaleInvoice>` (live surface)
  and `suspend retryInvoice(saleId): SaleInvoice` (on-demand re-issue).
- `repository/SaleInvoiceObserve.kt` *(new)* — `saleInvoiceCallbackFlow` adapter + `SaleInvoiceObservation`
  handle so iOS can produce the shared Flow from a native listener (mirror of `EntityObserve`).
- `usecase/ObserveSaleInvoiceUseCase.kt`, `usecase/RetryInvoiceUseCase.kt` *(new)* — thin pass-throughs
  so the ViewModels depend only on shared interfaces (`/kmp-arch`); no logic (issuance is T1's).
- `usecase/RecordSaleUseCase.kt` — thread `buyerName`/`buyerPhone` through, kept only for a walk-in,
  trimmed, blank → null (CF falls back to "Walk-in Customer").
- `i18n/Strings.kt`, `i18n/EnglishStrings.kt` — new keys for the buyer fields and the invoice row
  (preparing / number / view / print / copy / copied / share / open / failed / retry / retrying).
- `commonTest/.../sales/SalesTestFakes.kt` — `FakeSalesRepository` implements the two new methods.

### Android
- `data/BackendSalesRepository.kt` — write `buyerName`/`buyerPhone`; `observeSaleInvoice` via the
  Firestore snapshot `Flow`; `retryInvoice` via the `retryInvoice` callable (Functions SDK).
- `ui/sales/SalesViewModel.kt` — buyer input state, live invoice observation started on success, the
  single-click Retry state machine; `setBuyerPhone` sanitizes to digits-only, max 10.
- `ui/sales/SalesScreen.kt` — walk-in `BuyerSection` (numeric keyboard on phone); the invoice row in
  `SaleCompleteDialog` with all four states + Open (`ACTION_VIEW`) / Share (`ACTION_SEND`) / Retry.
- `build.gradle.kts`, `gradle/libs.versions.toml` — add `firebase-functions` dependency.

### iOS
- `repository/BackendSalesRepository.swift` — write buyer fields; `observeSaleInvoice` via
  `saleInvoiceCallbackFlow` + a native snapshot listener; `__retryInvoice` via the callable.
- `viewmodel/SalesViewModel.swift` — buyer input, live observation, Retry state machine;
  `setBuyerPhone` sanitizes to digits-only, max 10.
- `ui/SalesView.swift` — walk-in buyer `Section` (phone routed through the sanitizing setter,
  `.phonePad`); the success **alert → sheet** (`SaleCompleteView`) so the invoice row can update live
  with `Link` (Open) / `ShareLink` (Share) / Retry.
- `Podfile`, `Podfile.lock` — add the `FirebaseFunctions` pod.

### Desktop
- `data/BackendSalesRepository.kt` — write buyer fields; `observeSaleInvoice` via an Admin-SDK
  snapshot listener → `callbackFlow`; `retryInvoice` bumps `invoiceRetryRequestedAt` + `updatedAt`.
- `ui/sales/SalesViewModel.kt` — same buyer/invoice/retry wiring as Android; `setBuyerPhone`
  digits-only, max 10; `bindForTest` gains the two new use cases.
- `ui/sales/SalesScreen.kt` — walk-in buyer fields on the checkout pane; the invoice row in
  `SaleCompleteDialog` (View/Print open the URL via `Desktop.browse`, Copy link via clipboard).
- `test/.../SalesViewModelTest.kt` — 6 new tests (buyer capture on walk-in, named-customer drops it,
  invoice resolves in place, Retry lock + re-enable, no-op when not FAILED, new-sale clears state).

### Cloud Functions (`firebase/functions`)
- `index.ts` — new `retryInvoice` `onCall` (auth-gated, wraps `retryInvoiceCore`, maps not-found →
  HttpsError); `onSaleWrite` now edge-triggers a re-issue when `invoiceRetryRequestedAt` advances on a
  SYNCED sale (the Desktop retry path).
- `syncWorker.ts` — `retryInvoiceCore` (sets PENDING, calls T1's idempotent `issueSaleInvoice`, returns
  the settled state); `SaleData` gains `invoiceUrl` + `invoiceRetryRequestedAt`.
- `invoice.test.ts` — 5 new tests for `retryInvoiceCore` (success→ISSUED, still-failing→FAILED,
  idempotent no-op when ISSUED, no-op when not yet SYNCED, not-found).

## How to test
Automated:
```bash
# Shared payload/model + use cases
./gradlew :sharedLogic:jvmTest
# Desktop VM (cart/gating/totals + buyer capture + invoice observe/retry state machine)
./gradlew :desktopApp:test --tests "*SalesViewModelTest"
# Cloud Function tests
cd firebase/functions && npm test
# Compilation
./gradlew :sharedLogic:jvmTest :desktopApp:compileKotlin :androidApp:compileDebugKotlin
```
iOS — `Pods/` is gitignored, so a checkout needs `pod install` before the `FirebaseFunctions` import
resolves (otherwise: *"sandbox is not in sync with the Podfile.lock"*). Verified with:
```bash
cd iosApp
# LANG is not optional: CocoaPods 1.16 dies with an ASCII-8BIT Unicode-normalize error without it
LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 pod install
xcodebuild -workspace iosApp.xcworkspace -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.6' \
  -configuration Debug build CODE_SIGNING_ALLOWED=NO
```

Manual (Desktop, dev project `aromex-june-2026` where `retryInvoice` is deployed):
1. Ring up a sale → the Sale complete dialog shows *Preparing invoice…*, which becomes the invoice
   number + View/Print/Copy link within seconds; "New sale" is available throughout.
2. For a walk-in, type a name at checkout → it appears as the Bill-To on the PDF; leave it blank →
   "Walk-in Customer". The field is absent for a named customer.
3. Force a FAILED invoice → the row shows the reassuring message + Retry; click Retry → it locks and
   re-issues immediately; on success it resolves to the number, on a repeat failure it re-enables.
4. Verify light + dark on Desktop.

## Acceptance criteria
- ✅ Completing a sale shows *Preparing invoice…* → invoice number + actions once T1 finishes; "New
  sale" available at any point (`SaleCompleteDialog` observes `observeSaleInvoice`; button unconditional).
- ✅ View/Print open the PDF (Desktop `Desktop.browse`; Android `ACTION_VIEW`; iOS `Link`); Copy link
  → clipboard (Desktop); Share → OS share sheet on phones (`ACTION_SEND` / `ShareLink`).
- ✅ Walk-in: a name typed at checkout appears as the buyer; blank → "Walk-in Customer"; field absent
  for named customers; never blocks Confirm (`buyerName`/`buyerPhone` plumbed; no gating).
- ✅ A FAILED invoice shows the reassuring message + Retry, and the sale still reads as complete.
- ✅ Light + dark on Desktop (theme tokens only); both phone builds use stock components; Desktop VM
  tests + `sharedLogic:jvmTest` green. Counts as merged (Gate 2 round 2): **20 desktop VM, 124
  `sharedLogic:jvmTest`, 48 functions**; Android `compileDebugKotlin` clean and the iOS app builds
  in Xcode (see *Review fixes — round 2*).
- ✅ All new strings via i18n; money via the existing `MoneyFormat`.

## Deviations / decisions
- **Retry is immediate via a small new callable CF**, a conscious step beyond the ticket's literal
  "no new business logic / consume T1". The 5-minute reconcile sweep is unacceptable at a live counter
  (PO-confirmed), and this was the only way to make Retry on-demand. It builds no new invoice logic —
  `retryInvoice`/`retryInvoiceCore` only invoke T1's existing idempotent `issueSaleInvoice` (same HL
  number → same PDF, already-ISSUED skipped).
- **Two retry transports by platform**, matching each platform's auth model: mobile has a Firebase
  Auth token → calls the `onCall`; Desktop authenticates via the gateway/service account (no user
  token) and uses its Admin SDK to bump `invoiceRetryRequestedAt`, which `onSaleWrite` edge-triggers.
  No Firestore rules change: mobile never writes the sale doc; Desktop's Admin SDK bypasses rules.
- **Retry state machine:** Retry shows only for FAILED; a click optimistically flips the row to
  "preparing" and locks the button; the lock clears when the call returns; the live stream is the
  source of truth (ISSUED hides Retry, a fresh FAILED re-enables it).
- **iOS success alert → sheet:** an alert can't host a live-updating row + share sheet, so the summary
  is a `SaleCompleteView` sheet (still stock SwiftUI). Pull-to-dismiss resets for the next sale.
- **Phone field is digits-only, capped at 10** on all three platforms (sanitized in each
  `setBuyerPhone`, not just via keyboard hint, so paste/hardware input can't bypass it). Money fields
  already strip letters (`filterToDecimalInput` / `filteredToDecimalInput`); name stays free text.

## Review fixes (Gate 2)
Addressing the manager review on PR #79 — items 1, 2, and 4 (items 3 = iOS Xcode build and the
per-tenant functions deploy remain ops steps, tracked below).

- **1 — Retry failure is no longer silent (all 3 platforms).** When the manual Retry *call itself*
  can't reach the invoice service (callable missing / unauthenticated / functions not deployed) the
  live stream never settles, so the tap used to be an observable no-op. The retry handler now catches
  that failure, flips the optimistic PENDING back to FAILED, and shows an inline i18n reassurance:
  *"Couldn't reach the invoice service — it'll retry automatically."*
  - `i18n/Strings.kt` + `i18n/EnglishStrings.kt` — new `sales_invoice_retry_error` key.
  - Android/Desktop VMs — `runCatching{ … }.onFailure { … }` sets a new `invoiceRetryError` UiState
    flag (cleared on next tap and on New Sale); iOS VM — `do/catch` replaces the swallowing `try?`.
  - Android/Desktop `SalesScreen.kt` + iOS `SalesView.swift` — render the line under the FAILED
    branch (`colorScheme.error` / `colors.error` theme token / `.red`).
- **2 — Manual Retry no longer consumes the automatic-retry budget.** `issueSaleInvoice` gained a
  `countAttempt = true` param; only automatic issuance (sale-sync + reconcile) increments
  `invoiceAttempts`. `retryInvoiceCore` now passes `countAttempt: false`, so a cashier's taps can
  never exhaust `MAX_INVOICE_ATTEMPTS` and permanently disable the automatic sweep. New test in
  `invoice.test.ts` asserts a failing manual retry leaves `invoiceAttempts` unchanged.
- **4 — Schema + rules parity for `invoiceRetryRequestedAt`.** `docs/SCHEMA.md` now documents the
  field (and clarifies `invoiceAttempts` is the *automatic* count); `firebase/firestore.rules` adds
  `invoiceRetryRequestedAt` to the sales create-forbidden key list alongside every other `invoice*`
  field. (Desktop writes it via the Admin SDK, which bypasses rules; mobile uses the callable — so no
  rules-enforced client should ever seed it.)

Verification: `firebase/functions` tsc clean + 48 tests pass (was 47, +1 for the budget test);
Android + Desktop + shared Kotlin compile clean.

## Open questions / follow-ups
- **Per-tenant rollout:** `retryInvoice` + the `onSaleWrite` change are deployed to the dev project
  `aromex-june-2026`; each real client project needs the same `firebase deploy --only functions`.
- ~~**iOS build not run here**~~ — **done in round 2**: builds clean for the iPhone 16 Pro simulator
  (iOS 18.6) under Xcode 26.2 after `pod install`. See *Review fixes — round 2* for the one real
  defect that build surfaced.
- ~~**Desktop retry has no synchronous result**~~ — **changed in round 2**: it now waits for the
  Cloud Function to acknowledge the request, because "the write landed" was indistinguishable from
  "nothing is listening". The live listener is still the source of truth for the outcome.
- **Sales History / reaching invoices for older sales** remains the next ticket (out of scope here).

## Review fixes — round 2 (Gate 2, manager)

Round 1 fixed items 2 and 4 outright. Round 2 closes the rest, plus a regression the round-1 fix
introduced and a latent iOS defect the (finally-run) Xcode build exposed.

- **Retry failure no longer clobbers an invoice that already issued.** Round 1's handler forced
  `status = FAILED` on any client-side failure, reasoning that "the call never reached the service, so
  the live stream won't settle". That doesn't hold for a *timeout*: the callable client gives up well
  inside the CF's 120 s budget, and T1 notes a cold-Lambda render can take ~30 s — so a
  slow-but-successful re-issue settles the doc to ISSUED while the call throws. The handler then
  overwrote ISSUED with FAILED; the FAILED branch renders Retry and *not* Open/Share, so a finished,
  correct PDF became unreachable, with no further doc write coming to correct it. Most likely to fire
  exactly when Retry is used, since you press Retry when the engine is already misbehaving.
  New shared predicate `SaleInvoice.hasSettled` guards all three handlers
  (`SalesViewModel.kt` Desktop/Android, `SalesViewModel.swift`).
- **Desktop can now actually detect the failure item 1 was about.** Desktop nudges the sale doc with
  the Admin SDK instead of calling a callable, and that write succeeds whether or not any function is
  listening — with functions undeployed nothing threw, `onFailure` never ran, and the listener
  re-emitted the same FAILED. The cashier saw the spinner blink and nothing else: the original report,
  unchanged. `retryInvoiceCore` flips the doc out of FAILED before it re-renders, so
  `BackendSalesRepository.retryInvoice` now waits up to 20 s for that flip and raises
  `InvoiceRetryNotAcknowledgedException` if it never comes. This also means the button lock releases
  on backend acknowledgement rather than on a bare write-ack (round-1 follow-up).
- **iOS: the invoice stream's error handling was dead code.** The Xcode build warned *"'catch' block
  is unreachable because no errors are thrown in 'do' block"* on `observeInvoice`. SKIE bridges an
  unannotated `Flow`-returning function as a **non-throwing** `SkieSwiftFlow`, so
  `ObserveSaleInvoiceException` (raised by `saleInvoiceCallbackFlow` when the Firestore listener
  fails) had nowhere to surface — the graceful "leave the row preparing" path could never run.
  `ObserveSaleInvoiceUseCase.execute` now carries `@Throws(ObserveSaleInvoiceException::class)`, the
  same way `ObserveInventoryUseCase`/`ObserveEntitiesUseCase` already do, and the iOS call site takes
  the `try`. The warning is gone — which is the proof the catch is now live.
- **Two `SalesRepository.retryInvoice` KDoc claims were wrong** and are corrected: it does *not*
  return once the attempt settles (a cold render outlasts the call), and it is the *Cloud Function*,
  not Desktop, that flips the doc to `PENDING` — Desktop bumps `invoiceRetryRequestedAt`. The KDoc now
  also states the invariant the guard above depends on: a thrown exception doesn't prove nothing
  happened.
- **"Link copied" reverts** after 2 s (Desktop). It was a permanent relabel, so a second copy gave no
  feedback at all. Keyed on a tick so re-clicking restarts the window.
- **Two new Desktop VM tests**, both of which fail without their fix:
  `retry_surfacesAnInlineError_whenTheCallFails` (round 1 shipped the flag with no test) and
  `retry_doesNotClobberAnInvoiceThatAlreadyIssued`. `FakeSalesRepository` gained a `retryError` hook.

Verification: 48 functions tests + `tsc` clean; 20 Desktop VM tests; 124 `sharedLogic:jvmTest`;
`:androidApp:compileDebugKotlin` clean; **iOS `xcodebuild` succeeds** for the iPhone 16 Pro simulator
(iOS 18.6, Xcode 26.2) after `pod install` — which also confirms the one genuinely novel Swift
construct here, the `switch` over the SKIE-bridged `SaleInvoiceStatus` enum, and the newly bridged
`SaleInvoice.hasSettled`.

### Knowingly left open
- **Stuck-PENDING has no client-side fallback.** If issuance dies mid-render the row shows "preparing"
  forever and Retry never appears (it requires FAILED). Server-side reconcile flips it to FAILED
  within ~5 min, so it self-heals; not worth client complexity yet.
- **`retryInvoice` gates on `request.auth` only** (`index.ts:270`) — no `sales` permission check,
  unlike the Firestore create rule (`hasPermission('sales','manage')`), and with the attempt counter
  no longer applying to the manual path that path has no brake. A `view`-only user can trigger
  renders. Not exploitable beyond a signed-in tenant member; worth a permission check + rate limit.
- **`AddStockViewModel.swift:134,150` have the same dead-catch bug** as the one fixed above
  (ticket #58's attribute/serial observation). Same one-line `@Throws` fix; out of scope here.
