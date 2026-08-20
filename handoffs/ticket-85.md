# Handoff — Ticket #85

**Ticket:** #85 — [M7] Sales T3 — Void a sale: full reversal of money, tax, cost and stock

## Summary
Voiding a sale is now possible as a **full reversal, never a delete**: Humble Ledger (HL) cancels the
invoice — which reverses revenue, AR, tax **and** the COGS/Inventory pair in one call — any amount
paid is refunded across the same accounts the payment used, and every phone returns to `IN_STOCK`
with its `imeiIndex` restored, all in one atomic Firestore transaction; the sale flips to `VOIDED`.
It is **admin-only, verified server-side**, and requires a typed reason that flows into HL's
`cancelReason`. The whole path is **idempotent** (deterministic `sourceId`s + status guards), so
retries never double-reverse or double-refund. Both transports from #77 are mirrored — a `voidSale`
callable (mobile) and an `onSaleWrite` edge-trigger on `voidRequestedAt` (Desktop) — funnelling into
one `voidSaleCore` worker. Per the product owner, the void action was added to **all three
platforms** (Desktop, Android, iOS), not Desktop-only as the ticket originally scoped.

## Files changed

### sharedLogic (shared Kotlin)
- `model/SaleVoidState.kt` **(new)** — `SaleStatus` (COMPLETED/VOIDED), `VoidStatus`
  (PENDING/DONE/FAILED), and the `SaleVoidState` projection the UI reads.
- `model/SaleVoidFailedException.kt` **(new)** — carries the CF's failure reason (e.g. a re-used IMEI)
  to the UI.
- `model/SaleDetail.kt` — adds `status` + `voidState` (+ `isVoided`) so the detail renders the badge
  and gates the action.
- `model/SaleSummary.kt` — adds `status` so the history list can show a VOIDED badge.
- `repository/SalesRepository.kt` — adds `suspend fun voidSale(saleId, reason)` to the contract.
- `usecase/VoidSaleUseCase.kt` **(new)** — the real gate: requires `role == ADMIN` and a non-blank
  reason, then delegates.
- `i18n/Strings.kt` + `i18n/EnglishStrings.kt` — void dialog/badge/banner strings + the list
  `Refresh` label.
- `commonTest/.../SalesTestFakes.kt` — fake repo implements `voidSale`; `saleSession` gains a `role`.
- `commonTest/.../VoidSaleUseCaseTest.kt` **(new)** — admin gate, blank-reason/blank-saleId rejection,
  delegation (reason trimmed).

### firebase/functions (server)
- `hl.ts` — new `getInvoice`, `cancelInvoice`, `reverseTransaction`, `refundPayment`; `CreateSaleResult`
  captures the sale transaction id; `HlHttpError` now carries the response **body** (so a 422's coded
  reason is visible), and `hlPost`/`hlGet` include it.
- `syncWorker.ts` — `voidSaleCore` (the idempotent worker), `voidSourceId`, `VoidPermanentError`,
  `restoreStockAndVoid` (atomic stock restore + IMEI guard), and permanence classification
  (`isPermanentHlError`, `refundAlreadySettledByCancel`); `syncSale` now persists `hlSaleId`/
  `hlInvoiceId` and **skips a VOIDED sale**; `SaleData` gains the void + HL-id fields.
- `index.ts` — new `voidSale` callable (auth + server-side admin check via `voidSaleCore`); `onSaleWrite`
  edge-triggers the void on `voidRequestedAt` changing and swallows permanent failures (no redelivery
  loop).
- `voidWorker.test.ts` **(new)** — every edge-case row + admin/reason gates + idempotent replay +
  refund `NOTHING_TO_REFUND` tolerance + a genuine-422 failure.

### firebase (rules)
- `firestore.rules` — on `sales/{saleId}`, an **admin** may set exactly the four void-request fields
  (+`updatedAt`) with `voidStatus == 'PENDING'`; the CF-owned void/HL fields and `status` stay
  server-only; deletes remain forbidden.

### desktopApp
- `data/BackendSalesRepository.kt` — `voidSale` writes the request fields via the Admin SDK and waits
  for the CF to settle `voidStatus` (mirrors `retryInvoice`); parses `status`/`voidState`.
- `ui/.../SalesHistoryViewModel.kt` — `isAdmin`/`isVoiding`/`voidError` state, `canVoidOpenSale`,
  `voidSale`/`clearVoidError`/`reloadAfterVoid`, and a `refresh()` for the list.
- `ui/.../SalesHistoryScreen.kt` — void action at the **far right of the detail top bar** (admin-only,
  hidden once voided), a themed `VoidSaleDialog` (required reason, inert scrim, destructive confirm),
  a VOIDED badge (list + masthead) + read-only banner, and a **Refresh** button on the list top bar.
- test — fakes/`bindForTest` gain `voidSale`/`VoidSaleUseCase`; new tests for admin gating, required
  reason, the optimistic→settled state machine, and already-voided.

### androidApp
- `data/BackendSalesRepository.kt` — `voidSale` via the `voidSale` callable; parses `status`/`voidState`.
- `ui/.../SalesHistoryViewModel.kt` — same void state/actions as Desktop (admin-gated, optimistic,
  reload-on-success).
- `ui/.../SalesHistoryScreen.kt` — void action in the detail `TopAppBar` (admin-only), a Material
  `VoidSaleDialog` (required reason, not accidentally dismissible, destructive confirm), VOIDED badge
  (list + banner).
- test — fake repo implements `voidSale`.

### iosApp
- `repository/BackendSalesRepository.swift` — `__voidSale` via the callable; parses `status`/`voidState`.
- `viewmodel/SalesHistoryViewModel.swift` — void state/actions mirroring the others (`canVoidOpenSale`,
  `voidSale`, `reloadAfterVoid`, `withStatus`).
- `ui/SalesHistoryView.swift` — void toolbar button (admin-only), a `VoidSaleSheet` (required reason,
  `interactiveDismissDisabled` while voiding, destructive confirm), VOIDED badge (list + banner).

### docs
- `SCHEMA.md` — documents the new `sales/{saleId}` fields (`status: VOIDED`, the void spine,
  `hlSaleId`/`hlInvoiceId`, `hlVoidTxnId`/`hlRefundIds`) and the void-vs-delete rule.

## How to test
1. **Server (unit):** `cd firebase/functions && npm run build && npx vitest run` → 78 pass, incl.
   `voidWorker.test.ts` (every edge-case row).
2. **Shared:** `./gradlew :sharedLogic:jvmTest` (incl. `VoidSaleUseCaseTest`).
3. **Desktop:** `./gradlew :desktopApp:test` (incl. the void VM tests).
4. **Android:** `./gradlew :androidApp:testDebugUnitTest`; app compiles.
5. **iOS:** builds in Xcode (rebuild the shared framework so the new symbols are exposed).
6. **End-to-end (dev backend, requires deployed functions + admin user):** open a sale in Sales
   History → **Void** → type a reason → confirm. Verify the invoice is `CANCELLED` in HL, the units
   are back `IN_STOCK`, the sale shows `VOIDED`, and a non-admin sees no void action.

## Acceptance criteria
1. **Void unpaid/synced/invoiced → HL CANCELLED, 5-leg REVERSAL, AR to zero, units IN_STOCK + imeiIndex
   restored, sale VOIDED** — **Met** (cancel path + `restoreStockAndVoid`; covered by tests).
2. **Paid sale additionally refunds amountPaid, split across the same accounts** — **Met in code**
   (`refundLegs` mirrors `syncSale`); see Deviations re: this HL instance returning the money on
   cancel.
3. **Trial balance before == after (pasted numbers on dev)** — **Not verified in this PR.** Needs a
   live before/after run on the dev company (see Open questions).
4. **COGS + Inventory reversed exactly once** — **Met** (single `invoices/{id}/cancel`; we never
   reverse COGS ourselves; guarded by `hlVoidTxnId`).
5. **Non-admin cannot void (use case + CF)** — **Met** (`VoidSaleUseCase` + `assertAdmin` reading
   `users/{uid}.role`; tested both sides).
6. **A void with no reason is rejected client + server** — **Met** (use case + `voidSaleCore` +
   callable + rules; tested).
7. **Every edge-case row behaves as stated, each with a test** — **Met** (see `voidWorker.test.ts`),
   with the row-2 refinement noted in Deviations.
8. **Re-running a void is a no-op (no second reversal/refund)** — **Met** (`status==VOIDED` short-circuit,
   `hlVoidTxnId` guard, cancel status-guard, `sourceId`-idempotent refunds; tested).
9. **`:desktopApp:test`, `:sharedLogic:jvmTest`, functions tests green; Android + iOS compile** —
   **Met** (Android/Desktop/shared/functions all green; iOS builds per the reporter).

## Deviations / decisions
- **Void added to phones too (scope change).** The ticket scoped mobile as read-only ("No void action
  on phones in v1"). At the product owner's request the full admin-gated void action + dialog was
  added to Android and iOS as well.
- **HL id resolution (not on the doc originally).** `syncSale` never stored HL's invoice/transaction
  ids. It now persists `hlInvoiceId`/`hlSaleId`; a pre-#85 sale recovers them via an idempotent
  `syncSale` replay.
- **Reversal driven by `syncStatus`, not `invoiceStatus` (edge-row 2 refinement).** `invoiceStatus`
  tracks only the PDF; HL mints its invoice at sale time, so a SYNCED sale whose PDF failed still has a
  real HL invoice and is cancelled. `reverseTransaction` remains a defensive fallback when no invoice
  id resolves.
- **Refund after cancel — `NOTHING_TO_REFUND` is treated as success.** Measured live: on this HL
  instance, cancelling a fully-paid invoice already clears its paid balance, so the per-method
  `/refunds` returns `422 NOTHING_TO_REFUND`/`ALREADY_REFUNDED`. The void treats those (and
  `EXCESS_REFUND`) as "already returned by cancel" and completes, rather than failing. A genuine
  `INVALID_STATE`/other 4xx still fails.
- **Permanent vs transient failures.** HL 4xx (and re-used-IMEI / not-admin / no-reason) are
  `VoidPermanentError` → settle `voidStatus: FAILED` and do **not** rethrow, so `onSaleWrite`'s
  `retry:true` cannot loop; only 5xx/network rethrow to redeliver.

## Open questions / follow-ups
- **AC #3 (trial balance) must be proven on the dev company** — do a sale → void → confirm
  before/after trial balance is identical (Cash + the customer's balance return to baseline). If Cash
  is left hanging, switch to **refund-before-cancel** (fix is ready) instead of the current
  cancel-then-tolerate-`NOTHING_TO_REFUND`.
- **Credit-note PDF** — out of scope by decision (HL records the CreditNote; the customer-facing
  document is a later ticket).
- **Deploy note:** the functions are deployed **by name** (`voidSale`, `onSaleWrite`,
  `reconcileEntities`) so an unrelated project function (`onMoneyEntryWrite`, not in this branch) is
  never deleted. `firestore.rules` must be deployed for the void request-field write.
- **Mobile void is a product decision** to keep — money-reversal is now reachable from cashier
  devices (admin-gated); flag if it should be Desktop-only after all.
