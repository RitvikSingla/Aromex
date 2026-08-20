# Handoff — Ticket #109

**Ticket:** #109 — [M11] Print a party's statement — date range, multi-page PDF, notes toggle, aging

## Summary

Adds the ability to generate a **party statement PDF** — opening balance, every ledger movement in a
date range, closing balance, and aging of the outstanding balance — rendered by the Humble Bill
Engine via a new `renderStatement` Cloud Function. The data logic (paging every row, the
opening-balance call, and the FIFO aging that makes the buckets reconcile to the closing balance)
lives in a **shared Kotlin use case** with tests; the callable does only what must be server-side
(re-check `profiles: view`, read the seller/buyer letterhead, stamp the issue date, call the engine).
A **"Print statement"** entry point was built on **Android, iOS, and Desktop** — a date range +
off-by-default "Include notes" toggle + Generate, opening the resulting PDF in each platform's viewer.
The engine template itself is owned by the manager and was not touched.

> Scope note: the ticket was written Desktop-only; this change delivers **Android + iOS + Desktop**
> at the product owner's request. It also carries a few unrelated pre-existing compile fixes (see
> Deviations).

## Files changed

### Shared logic (`sharedLogic`)
- `model/StatementDocument.kt` *(new)* — the assembled statement (rows, opening, totals, closing, aging buckets).
- `util/CalendarDate.kt` *(new)* — pure `yyyy-MM-dd` epoch-day math (no datetime lib on the classpath) for the aging day-diffs and day-before-period.
- `util/Money.kt` — adds `negate` / `signedAdd` / `signedSubtract` (ledger deltas can be negative) and `round2` (2dp display normalization).
- `usecase/BuildPartyStatementUseCase.kt` *(new)* — the paging loop (cap 2000), the opening-balance call, and the FIFO aging; gated on `profiles: view`.
- `repository/StatementPdfRepository.kt` *(new)* — interface for rendering a `StatementDocument` to a PDF URL.
- `i18n/Strings.kt` + `i18n/EnglishStrings.kt` — the print-statement UI strings.
- `commonTest/.../statement/BuildPartyStatementUseCaseTest.kt` *(new)* — 11 tests (paging, opening balance, FIFO buckets == closing, notes, cap, permission gate).

### Server (`firebase/functions`)
- `src/statement.ts` *(new)* — `renderStatementCore` + pure `buildStatementPayload`: permission re-check, seller/buyer letterhead, `{ appId: 'aromex-statement', data }` envelope, engine call.
- `src/index.ts` — the `renderStatement` `onCall` callable (auth → core → `HttpsError`, mirroring `voidSale`).
- `src/syncWorker.ts` — exports `blank` / `composeContact` for reuse by `statement.ts`.
- `src/statement.test.ts` *(new)* — 9 tests (envelope shape, omitted-vs-empty keys, permission gate, missing party).

### Android (`androidApp`)
- `data/BackendStatementPdfRepository.kt` *(new)* — calls the `renderStatement` callable via the Firebase Functions SDK.
- `ui/entities/PrintStatementViewModel.kt` *(new)* — form state + generate(); wires the use case + repo.
- `ui/entities/PrintStatementDialog.kt` *(new)* — the bare-minimum dialog; opens the PDF in the system viewer.
- `ui/entities/EntitiesScreen.kt` — "Print statement" button on the contact detail.
- `ui/sales/SalesViewModel.kt`, `ui/inventory/AddStockViewModel.kt` — pre-existing #106 compile fixes (see Deviations).

### iOS (`iosApp`)
- `repository/BackendStatementPdfRepository.swift` *(new)* — calls the callable via the Firebase Functions SDK.
- `viewmodel/PrintStatementViewModel.swift` *(new)* — form state + generate().
- `ui/PrintStatementSheet.swift` *(new)* — the sheet; opens the PDF in the system viewer.
- `ui/EntityDetailView.swift` — "Print statement" button + sheet.
- `viewmodel/SalesViewModel.swift`, `viewmodel/AddStockViewModel.swift`, `repository/BackendInventoryRepository.swift` — pre-existing #106 compile fixes (see Deviations).

### Desktop (`desktopApp`)
- `data/BackendStatementPdfRepository.kt` *(new)* — invokes the callable over its HTTPS endpoint using the callable protocol + the desktop Firebase ID token (Desktop has no Firebase client SDK).
- `ui/entities/PrintStatementUi.kt` *(new)* — the print dialog (reuses `DateRangeChip`) + a PDF window reusing `rememberInvoicePdf` / `ZoomablePdfViewer` with Share / Download.
- `ui/entities/EntitiesViewModel.kt` — print state + `generateStatementPdf()`; resolves money-entry notes from the entries it already holds.
- `ui/entities/PartyStatementSection.kt` — "Print statement" chip in the statement toolbar.
- `ui/entities/EntitiesScreen.kt`, `navigation/AromexApp.kt` — thread the print state + callbacks through.

### Docs
- `docs/SCHEMA.md` — a section documenting the statement document, the callable request, and the `aromex-statement` engine envelope.

## How to test

**Automated**
- `./gradlew :sharedLogic:jvmTest --tests "com.humblesolutions.aromex.statement.*"` — 11 pass.
- `cd firebase/functions && npx vitest run src/statement.test.ts` — 9 pass (full suite: 125).
- `./gradlew :androidApp:compileDebugKotlin`, `:desktopApp:test` — build/pass.

**End-to-end (Desktop, verified live)**
1. `firebase deploy --only functions:renderStatement` (already deployed to `aromex-june-2026`, `us-central1`).
2. Run the desktop app, sign in with a `profiles: view`/`manage` account.
3. Contacts → a party with history → **Print statement** chip → set range / toggle notes → **Generate** → the PDF opens in a window with Share / Download.
4. Confirm: PDF closing balance == on-screen closing balance; `opening + charges − received == closing`; aging buckets sum to closing exactly.

## Acceptance criteria

- [x] Statement contains **every** row in the range, not the first 50 (paging loop, tested).
- [x] `openingBalance + totalDebits − totalCredits == closingBalance` (tested).
- [x] PDF closing balance == on-screen closing balance (verified live: $408 for the test party).
- [x] **Include notes off** (default) → no note text.
- [~] **Include notes on** → **money-entry** notes appear (Desktop resolves them; mobile currently passes an empty note map — see follow-ups). **Sale** notes not yet indexed.
- [x] **Aging buckets sum to `closingBalance` exactly** (tested over several histories, incl. partial-payment and partly-unpaid-opening).
- [x] Aging derived from ledger movements, not `/receivables` (an unapplied credit still ages correctly — tested).
- [x] Party in credit → **no** aging block (tested).
- [x] All-time statement (no `from`) → opening `0.00` (tested).
- [x] Range with no activity → valid statement, opening == closing, no rows (tested).
- [x] Over 2000 rows → user told to narrow the range; nothing truncated (tested).
- [x] User without `profiles: view` cannot generate one — re-checked in the callable (tested).
- [~] Multi-page pagination with repeating column headers — **engine template's responsibility** (manager-owned); the app sends every row.
- [x] `:sharedLogic:jvmTest` and `firebase/functions` `npm test` pass. (`:desktopApp:test` passes; no new desktop unit tests were added — the logic + its tests live in `sharedLogic`.)

## Deviations / decisions

1. **Platforms:** built on **Android + iOS + Desktop** (ticket was Desktop-only), per the product owner.
2. **Architecture:** the paging / opening-balance / FIFO-aging logic lives in the **shared Kotlin use case** (tested in `:sharedLogic:jvmTest`), and the callable receives the assembled figures rather than recomputing them from `{entityId, from, to}`. This keeps one canonical, tested implementation serving all three platforms and avoids duplicating the aging in TypeScript. The security invariant is preserved (the engine is only ever called from the Cloud Function, which re-checks `profiles: view`).
3. **Pre-existing #106 compile fixes (unrelated to #109):** Android and iOS callers of `SaleInput` / `RecordSaleUseCase.execute` / `RecordInventoryPurchaseUseCase.execute` / the `Serial` model were never updated when #106 added `saleDate` / `now` / `purchaseDate` / `purchaseId`, so neither app compiled. Minimal fixes (pass current time / map the new field) were made to unblock building — mirroring the desktop call sites #106 did update.

## Open questions / follow-ups

- **PDF column alignment (manager / engine template):** in the rendered PDF, the Account Activity table's numeric columns (CHARGES / RECEIVED / BALANCE) — their header cells and value cells don't share the same right edge, so the amounts read as drifting from their headers. This is CSS in `templates/aromex/statement.html` (`billApps/aromex-statement`), which the manager owns and this ticket puts out of bounds. Fix: right-align the `<th>` to the same edge as the `<td>` (or set fixed column widths). Everything the app sends is correct (plain decimal strings, blanks for empty cells).
- **Notes index on mobile:** the shared use case takes `noteByTransactionId`; Desktop populates it from money entries, but Android/iOS currently pass an empty map (a row with no match simply shows no note). Populating it from the party's money entries on mobile is a small follow-up.
- **Sale notes:** no `hlSaleId`-keyed sale index yet, so sale notes don't appear on any platform. A follow-up per the ticket's notes section.
