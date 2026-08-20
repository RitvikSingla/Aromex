# Handoff — Ticket #83

**Ticket:** #83 — [M7] Sales History T1 — Desktop: searchable past sales + reach the invoice

## Summary

Adds a **Desktop Sales History** screen: a paged, searchable list of past sales plus a detail
view that renders every field a sale recorded and the generated invoice PDF inline. Sales are the
app's first unbounded collection, so the list pages server-side (`orderBy(createdAt desc).limit(N)`
+ `startAfter` cursor) and its structured filters run as Firestore queries — never a client-side
filter over a full-collection cache. The three called-out traps are handled: a denormalized
`hasOutstandingBalance` boolean powers the balance filter (money is a lexicographically-ordered
decimal string), and IMEI search resolves through the serial (`serials where imei == X →
serial.saleId → sales/{saleId}`) so it still works after a unit has sold. The detail view shows the
seller's **display name** (not the raw uid), renders the actual invoice PDF via PDFBox, and exposes
**Share** / **Download** of the PDF file from the top bar. New composite indexes and a rules change
for the boolean are committed. `sharedLogic` stays pure Kotlin; Desktop uses the Firestore Admin
SDK and enforces the `sales: view` gate in app logic.

## Files changed

### Shared logic (`sharedLogic/`, pure Kotlin)
- `model/SaleSummary.kt` **(new)** — list-row projection; carries `itemLabels` + `imeis` for local search plus first-item/count for display.
- `model/SaleDetail.kt` **(new)** — full read projection of a `sales/{saleId}` doc (lines, taxes, payments, sync, invoice, `createdBy`).
- `model/SalesQuery.kt` **(new)** — `SalesQuery` (filters + cursor), `SalesCursor` (opaque pure data — no Firestore types), `SalesPage`.
- `repository/SalesRepository.kt` — added `querySales(query)`, `getSale(saleId)`, `findSaleIdByImei(imei)`.
- `usecase/QuerySalesUseCase.kt` **(new)** — `sales: VIEW` gate; IMEI serial-indirection path; list path; `SaleDetail.toSummary()`.
- `usecase/GetSaleUseCase.kt` **(new)** — `sales: VIEW` gate; single-sale read for the detail view.
- `model/UserSession.kt`, `repository/UserRepository.kt`, `usecase/LoginUseCase.kt`, `usecase/RestoreSessionUseCase.kt` — thread `timezone` (default `"UTC"`) from `companySettings/profile` into the session (dependency from #80 that nothing previously read).
- `i18n/Strings.kt`, `i18n/EnglishStrings.kt` — all new UI strings (list, filters, detail, bill, chips) plus the Share/Download/PDF-saved/PDF-error strings.

### Desktop (`desktopApp/`)
- `data/BackendSalesRepository.kt` — implemented the 3 new interface methods (paged `querySales`, `getSale`, `findSaleIdByImei`); writes `hasOutstandingBalance` on create; `toSaleSummary`/`toSaleDetail`/`parseLines` mappers.
- `data/FirestoreUserRepository.kt` — reads `companySettings/profile.timezone` (reused to resolve `createdBy` uid → display name for "Sold by").
- `ui/sales/history/SalesHistoryViewModel.kt` **(new)** — paging + append, local-search recompute, server-side filters, customer-name→entity-id resolution, detail load + live invoice observe + retry, and `createdBy` uid → display-name resolution (session for the current user, `users/{uid}` read cached otherwise).
- `ui/sales/history/SalesHistoryScreen.kt` **(new)** — list (weighted table / compact cards / skeleton / empty states), local search box, server-side filter dialog, two-pane detail, app-drawn bill; top-bar **Share** / **Download** PDF actions; minimal non-issued invoice status (preparing / Retry).
- `ui/sales/history/PdfBillView.kt` **(new)** — OkHttp download + PDFBox rasterize (`rememberInvoicePdf`, `ZoomablePdfViewer`, `PdfLoadingBox`) and the PDF file actions (`downloadPdfBytes`, `downloadAndSavePdf` via native Save dialog, `downloadAndRevealPdf` = save to Downloads + reveal in Finder/Explorer).
- `ui/sales/SalesScreen.kt` — refactored `InvoiceRow` to explicit params and made it (and `openInBrowser`) `internal` so it stays reusable; updated the counter's call site.
- `ui/components/DesktopNavSidebar.kt` — `DesktopSection.SALES_HISTORY` + nav item (gated on `sales` view).
- `navigation/AromexApp.kt` — wired the new VM + section + `onNavigateToSalesHistory` through the screens.
- `ui/entities/EntitiesScreen.kt`, `ui/inventory/InventoryScreen.kt` — added the `onNavigateToSalesHistory` nav callback.
- `build.gradle.kts` — added the PDFBox dependency.

### Config / firebase / docs
- `firebase/firestore.indexes.json` — 4 composite `sales` indexes (`createdAt DESC, saleId DESC` base + range; `customerEntityId + …`; `hasOutstandingBalance + …`; `customerEntityId + hasOutstandingBalance + …`).
- `firebase/firestore.rules` — permit a client-set boolean `hasOutstandingBalance` on sale create (unlike the CF-owned `invoice*` fields).
- `docs/SCHEMA.md` — documented `hasOutstandingBalance`.
- `gradle/libs.versions.toml` — PDFBox version entry.

### Tests
- `sharedLogic/.../sales/QuerySalesUseCaseTest.kt` **(new)** — permission gating, cursor passthrough, filters, IMEI indirection, and the $90-vs-$100 string-money balance check.
- `sharedLogic/.../sales/GetSaleUseCaseTest.kt` **(new)** — gating, mapping, missing sale, blank id.
- `sharedLogic/.../sales/SalesTestFakes.kt` — extended the fake with the 3 new methods + configurable page/sales/imei maps.
- `desktopApp/.../ui/sales/history/SalesHistoryViewModelTest.kt` **(new)** — paging-appends, local search (imei/label/customer/invoice) without hitting Firebase, filters, empty states, permission gating, open/close detail, and seller-name resolution (current user / cached / cleared on close).
- `desktopApp/.../ui/sales/history/PdfRenderSmokeTest.kt` **(new)** — generates a PDF and renders it via PDFBox to prove the capability at runtime.
- `desktopApp/.../ui/sales/SalesViewModelTest.kt` — updated its fake with the 3 new methods.

### Android (`androidApp/`) — cross-platform parity only (no Android UI; that's T2)
- `data/BackendSalesRepository.kt` — implemented the 3 new interface methods with the native Android SDK + `hasOutstandingBalance` on create + mappers, so the shared interface still compiles on Android.

## How to test

1. `./gradlew :sharedLogic:jvmTest :desktopApp:test` — all green (paging, filters, IMEI indirection, string-money balance trap, local search, empty/permission states, seller-name resolution, PDFBox render smoke test).
2. `./gradlew :androidApp:compileDebugKotlin` — confirms the shared-interface additions still compile on Android.
3. Confirm the composite `sales` indexes are **READY** in the Firebase console before live testing (an unindexed query fails at runtime).
4. Launch the desktop app (`./gradlew :desktopApp:run`), sign in as a user with `sales: view`, open **Sales**:
   - List loads newest-first; scroll to page more (list never blanks).
   - Local search box: type a customer / invoice # / item label / IMEI → filters loaded rows instantly.
   - Filters dialog: customer, IMEI (try one already sold), invoice #, date range, "only with a balance" → full-history Firestore queries; verify a $90 and a $100 balance are both found.
   - Open a sale: every recorded field shows; **Sold by** is the seller's display name; the invoice PDF renders on the right (zoom/pan); payment / paid / balance line up under the grand total.
   - Top bar: **Download** (native Save dialog) and **Share** (saves to Downloads + reveals in Finder) both produce the actual PDF file.
   - A FAILED invoice shows Retry in the detail; re-issue works.
5. Sign in as a user with no `sales` permission → the screen shows the no-access state and issues no queries.

## Acceptance criteria

1. **Lists newest-first, pages on scroll without re-fetching** — ✅ Met (`SalesHistoryViewModel.loadFirstPage`/`loadMore` append via cursor).
2. **Customer / IMEI / invoice search finds the right sale; IMEI works post-sale** — ✅ Met (server-side filters; IMEI via `findSaleIdByImei` serial indirection, tested).
3. **Date-range + balance filters combine and the balance filter is correct across a power of ten** — ✅ Met (`hasOutstandingBalance` boolean; $90-vs-$100 test).
4. **Opening a sale shows every recorded field** — ✅ Met (`SaleDetail` + detail sheet render lines/taxes/payments/note/buyer/sync).
5. **Invoice row works from history: View/Print/Copy on ISSUED, Retry on FAILED** — ⚠️ Partially changed by request: View/Print/Copy-link were **replaced** with top-bar **Share/Download of the actual PDF file** (an improvement over copying the S3 link); **Retry on FAILED is retained**. See Deviations.
6. **`sales: view` can read; no permission sees nothing and cannot query** — ✅ Met (use-case gate; `permissionDenied` test asserts no query runs).
7. **Light + dark verified; `:desktopApp:test` + `:sharedLogic:jvmTest` pass** — ⚠️ Tests pass; light/dark **not** visually verified against a live login yet (theme tokens only, no hardcoded colours).
8. **Every composite index committed and deployed** — ⚠️ Committed (4 indexes); confirm they are **READY** in the console before merge.

## Deviations / decisions

- **AC5 — invoice actions in the detail view changed by request.** The reused #77 `InvoiceRow`
  (View / Print / Copy link) was removed from the history detail and replaced with two top-bar
  buttons that operate on the **real PDF bytes**, not the public S3 link:
  - **Download** → native Save dialog → writes the PDF where the user chooses.
  - **Share** → downloads the PDF into `~/Downloads` and reveals it in the OS file browser
    (Compose Desktop has no native share sheet; `Desktop.browseFileDirectory` on macOS, with an
    open-folder fallback for Windows/Linux).
  The **Retry** path for FAILED invoices and the PENDING "preparing" state are retained as a
  minimal status line. `InvoiceRow` itself is unchanged and still used by the counter screen.
- **Seller shown as display name, not uid.** The sale stores `createdBy` (a uid); the detail
  resolves it to a display name — the session for the current user, else a cached `users/{uid}`
  read. Falls back to "—" while resolving or if the lookup fails.
- **Payment block aligned under the total.** In the app-drawn bill, cash / card / bank / paid /
  balance now sit in the same right-aligned money column as subtotal / tax / grand total.
- **Inline PDF via PDFBox rather than a WebView.** Compose Desktop has no WebView; bundling a
  browser engine (~100 MB) for a one-page bill isn't justified, so pages are rasterized with
  PDFBox. T2 mobile will use native PDF viewers.
- **Caching-rule exception.** This is the one screen that pages server-side instead of
  fetch-all-and-filter-client-side, because sales are unbounded (as the ticket directs).

## Open questions / follow-ups

- **Visual verification pending.** The two-pane split, PDF zoom, Share/Download + Finder reveal,
  and light/dark all need a live login with an issued sale before merge (AC7).
- **Confirm indexes are READY** in the Firebase console (AC8) before live-testing the list.
- **Local search only sees loaded pages** (by design of the hybrid) — full-history exact lookups
  are the Filters dialog's job.
- **`BackendSalesRepository` has no `close()`** on Desktop (pre-existing note from #62); the history
  VM only closes the entity repo.
- **T2:** mirror the screen on Android/iOS with native PDF viewers.
