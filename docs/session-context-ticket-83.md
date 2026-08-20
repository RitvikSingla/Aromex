# Session Context — Ticket #83: Desktop Sales History

> **Date:** 2026-07-31
> **Branch:** `ticket-83-sales-history-desktop` (off `master`)
> **Scope:** Desktop only (Android/iOS is T2). Milestone **M7**, brief **#82**.
> **Status:** Implemented, compiling on all targets, all unit tests green, Firestore
> indexes + rules deployed. Not yet visually verified against a live login (see
> [Open items](#open-items--caveats)).

This document captures everything done in this working session so anyone picking it up has
full context without re-reading the whole conversation.

---

## 1. What the ticket asked for

A **Sales history screen on Desktop**: a searchable, paged list of past completed sales plus a
detail view that shows everything a sale recorded, including the invoice row from #77 (View /
Print / Copy link / Retry). Before this, a completed sale was visible only for as long as the
Sale-complete dialog stayed open — there was no screen listing past sales and no repository
method that read more than one.

The ticket called out **three traps**:
1. **Sales are the first unbounded collection.** Every other screen caches the whole collection;
   sales grow forever, so this screen must **page** (`orderBy(createdAt desc).limit(N)` +
   `startAfter`) and filter via **Firestore queries**, not a client-side `.filter {}` over a full
   cache.
2. **Money is a decimal string**, and Firestore orders strings lexicographically
   (`"100.00" < "90.00"` is true). Any "sales with a balance" filter built on a string comparison
   of `balanceRemaining` is silently wrong → add a denormalized boolean `hasOutstandingBalance`.
3. **IMEI search can't look inside `lines`** (an array of maps; `array-contains` only matches whole
   elements) and `imeiIndex/{imei}` is deleted when a unit sells → resolve via the serial:
   `serials where imei == X → serial.saleId → sales/{saleId}`.

---

## 2. Architecture followed (from `CLAUDE.md` / kmp-architecture skill)

- **Three layers, no shared UI, no `expect`/`actual`, manual DI.**
  - `sharedLogic/` — pure Kotlin: `model/`, `repository/` (interfaces only, all `suspend`),
    `usecase/` (depend only on interfaces).
  - `desktopApp/` — Compose-Desktop UI + ViewModels (`StateFlow`) + repo impls (Firestore Admin SDK).
  - `androidApp/` — Jetpack Compose + its own repo impls (native Firestore SDK).
- **Permissions enforced in shared app logic** (Desktop's Admin SDK bypasses Firestore rules), so
  the use cases carry the `sales: VIEW` gate.
- **Caching rule exception:** sales are unbounded, so this screen pages server-side — the one place
  the app does *not* fetch-all-and-filter-client-side.
- **Money is never floating point** in shared/UI code — decimal strings throughout, math via
  `com.humblesolutions.aromex.util.Money`.

---

## 3. Feature walkthrough (final state)

### 3.1 Sales history list
- Paged, newest-first. First page 30 rows; `loadMore()` appends on scroll (never blanks the list).
- **Columns (after a later change):** Date · Invoice # · Customer · Total · Paid · Balance · Status
  (sync + invoice chips) · invoice action.
  - **Items and IMEI columns were removed by request.** The data is still in the model for local
    search; it's just not shown as columns. The compact card also no longer shows the item line.
- Resize-safe: weighted columns inside `BoxWithConstraints`, hover-reveal tooltips on truncated
  cells, and a reflow to stacked **cards** below `COMPACT_BREAKPOINT` (900dp).
- Skeleton loader on first load; append-with-footer-spinner on paging.
- Two empty states: *no sales yet* vs *no sales match your filters*.

### 3.2 Search — hybrid (local + server)
- **Top-right search box = LOCAL.** Filters the already-loaded rows in memory (no Firestore):
  matches **customer name, invoice #, any item/phone label, any IMEI** (substring, case-insensitive).
  Instant. Only sees loaded pages (accepted limitation — see below).
- **Filters dialog = SERVER-SIDE.** Customer / IMEI / invoice # / date range / "only with a balance"
  run as Firestore queries (indexed), so they find sales anywhere in history, not just loaded pages.
  This is where the IMEI serial-indirection and the `hasOutstandingBalance` boolean are used.
- The two are independent: the box refines what's loaded; the dialog decides what loads.

### 3.3 Detail screen — two-pane bill
- **Wide (≥ `DETAIL_SPLIT_BREAKPOINT` = 1000dp):** side-by-side —
  - **Left (~42%)** = structured detail as a bordered "paper": masthead (invoice #, sync + invoice
    chips, date, sold-by), bill-to, a **line-items table** (Item · IMEI · List · Discount · Unit ·
    Amount), totals with **each tax line as "GST (5%)" / "PST (7%)"**, payment split, paid, balance,
    note, and the reused #77 `InvoiceRow`. Scrolls internally.
  - **Right (~58%)** = the **actual generated invoice PDF**, rendered inline, **fit-to-height so no
    scrolling** is needed to see the whole bill.
- **Narrow:** stacks vertically (detail on top, bill below), each pane bounded (no nested-scroll).
- **No PDF yet** (invoice PENDING/FAILED, or render fails) → collapses to a **single pane** (just the
  structured detail). The app-drawn bill body is the always-available fallback.

### 3.4 Inline PDF viewer (why not WebView)
- Compose Desktop has **no native WebView**; embedding one (JavaFX or Chromium via KCEF) would bundle
  a ~100 MB engine for a one-page bill.
- Instead: download the PDF (OkHttp) and rasterize each page to an `ImageBitmap` with **Apache
  PDFBox** (`org.apache.pdfbox:pdfbox:2.0.32`), a small JVM library. Shows the bill exactly as
  generated.
- **Zoomable like a laptop:** trackpad pinch-zoom + pan, mouse-wheel zoom, and +/−/reset buttons.
  Zoom 1×–5×; drag to pan when zoomed. A page flipper (‹ 1 / N ›) appears only for multi-page PDFs.
- T2 mobile will later use native PDF viewers (`WKWebView` / `PdfRenderer`).

---

## 4. Files changed / created

### sharedLogic (pure Kotlin)
| File | Change |
|---|---|
| `model/SaleSummary.kt` | **New.** List-row projection. Carries `itemLabels` + `imeis` (all lines) for local search, plus `firstItemLabel`/`firstImei`/`itemCount` for display. |
| `model/SaleDetail.kt` | **New.** Full read projection of a sale doc (lines, taxes, payments, invoice, sync). |
| `model/SalesQuery.kt` | **New.** `SalesQuery` (filters + cursor) + `SalesCursor` (opaque, pure data — no Firestore types) + `SalesPage`. |
| `repository/SalesRepository.kt` | Added `querySales(query)`, `getSale(saleId)`, `findSaleIdByImei(imei)`. |
| `usecase/QuerySalesUseCase.kt` | **New.** `sales: VIEW` gate; IMEI path via serial indirection; list path via `querySales`. Includes `SaleDetail.toSummary()`. |
| `usecase/GetSaleUseCase.kt` | **New.** `sales: VIEW` gate; direct sale read for the detail view. |
| `model/UserSession.kt` | Added `timezone` (default `"UTC"`). |
| `repository/UserRepository.kt` | `CompanyProfile.timezone` (default `"UTC"`). |
| `usecase/LoginUseCase.kt`, `usecase/RestoreSessionUseCase.kt` | Thread `timezone` into the session. |
| `i18n/Strings.kt`, `i18n/EnglishStrings.kt` | All new UI strings (list, filters, detail, bill, chips). |

### desktopApp
| File | Change |
|---|---|
| `data/BackendSalesRepository.kt` | Implemented the 3 new methods (paged `querySales`, `getSale`, `findSaleIdByImei`); added `hasOutstandingBalance` to `saleData()`; mappers `toSaleSummary`/`toSaleDetail`/`parseLines`. |
| `data/FirestoreUserRepository.kt` | Read `companySettings/profile.timezone`. |
| `ui/sales/history/SalesHistoryViewModel.kt` | **New.** Paging, local-search recompute (`visibleSales`), server-side filters, customer-name→entity-id resolution, detail load + live invoice observe + retry. |
| `ui/sales/history/SalesHistoryScreen.kt` | **New.** List (table/cards, skeleton, empty states), search box, filter dialog, two-pane detail, bill layout. |
| `ui/sales/history/PdfBillView.kt` | **New.** OkHttp download + PDFBox render; `rememberInvoicePdf`, `ZoomablePdfViewer`, `PdfLoadingBox`. |
| `ui/sales/SalesScreen.kt` | Refactored `InvoiceRow` to explicit params (`invoice`, `isRetrying`, `canRetry`, `retryError`, `onRetry`) + made `internal`; `openInBrowser` made `internal`; updated the counter's call site. |
| `ui/components/DesktopNavSidebar.kt` | Added `DesktopSection.SALES_HISTORY` + nav item (gated on `sales` view). |
| `navigation/AromexApp.kt` | Wired the new VM + section + `onNavigateToSalesHistory` through all screens. |
| `ui/entities/EntitiesScreen.kt`, `ui/inventory/InventoryScreen.kt`, `ui/sales/SalesScreen.kt` | Added the `onNavigateToSalesHistory` nav callback. |
| `build.gradle.kts` + `gradle/libs.versions.toml` | Added the `pdfbox` dependency. |

### androidApp (kept compiling / cross-platform parity — no Android UI, that's T2)
| File | Change |
|---|---|
| `data/BackendSalesRepository.kt` | Implemented the 3 new interface methods with the Android SDK + `hasOutstandingBalance` on create + mappers. |

### firebase / docs / tests
| File | Change |
|---|---|
| `firebase/firestore.indexes.json` | 4 composite `sales` indexes (see below). **Deployed.** |
| `firebase/firestore.rules` | Permit client-set `hasOutstandingBalance` (boolean) on create. **Deployed.** |
| `docs/SCHEMA.md` | Documented `hasOutstandingBalance`. |
| `sharedLogic/.../sales/SalesTestFakes.kt` | Extended the fake with the 3 new methods + configurable page/sales/imei maps. |
| `sharedLogic/.../sales/QuerySalesUseCaseTest.kt` | **New.** Permission gating, cursor passthrough, filters, IMEI indirection, **$90 vs $100 balance** power-of-ten check. |
| `sharedLogic/.../sales/GetSaleUseCaseTest.kt` | **New.** Gating, mapping, missing sale, blank id. |
| `desktopApp/.../ui/sales/history/SalesHistoryViewModelTest.kt` | **New.** Paging-appends, local search (imei/label/customer/invoice) **without hitting Firebase**, filters, empty states, permission gating, open/close detail. |
| `desktopApp/.../ui/sales/history/PdfRenderSmokeTest.kt` | **New.** Runtime test: generate a PDF and render it via PDFBox (proves the capability at runtime). |
| `desktopApp/.../ui/sales/SalesViewModelTest.kt` | Updated its fake with the 3 new methods. |

---

## 5. Firestore indexes (deployed to `aromex-june-2026`)

All ordered `createdAt DESC, saleId DESC` (saleId is the cursor tiebreak):
1. `createdAt DESC, saleId DESC` — base list + date range.
2. `customerEntityId ASC, createdAt DESC, saleId DESC`.
3. `hasOutstandingBalance ASC, createdAt DESC, saleId DESC`.
4. `customerEntityId ASC, hasOutstandingBalance ASC, createdAt DESC, saleId DESC`.

Deployed via `firebase deploy --only firestore:indexes` (and `:rules`). At the last check they were
still **`CREATING`** — confirm they've flipped to **`READY`** before testing the list against live
data (an unindexed query errors at runtime). The `firebase firestore:indexes` CLI lists definitions
but not build state; state was read via the Firestore Admin REST API using the CLI's cached token.

---

## 6. Key implementation notes / decisions

- **Opaque cursor:** `SalesCursor(createdAtMillis, saleId)` is pure data so `sharedLogic` never
  touches a Firestore `DocumentSnapshot`. The repo converts it to `startAfter(Timestamp, saleId)`.
- **Exact invoice # search** is a single equality read (no `orderBy` → uses the auto single-field
  index, no composite needed); other filters then applied client-side to that handful.
- **Customer name → ids:** resolved client-side against the cached (bounded) `entities`, capped at
  Firestore's `whereIn` limit of 30; an unmatched name yields a sentinel id so the query returns
  **nothing**, not everything.
- **`InvoiceRow` reuse:** extracted from `SalesUiState` to explicit params so the counter and the
  history detail render the identical component (same states, actions, i18n keys).
- **Tax rate display:** `ratePercent("0.05") → "5%"` via `Money.multiplyRate("100", rate)` (kept
  string-based, no float).
- **Timezone:** provisioned into `companySettings` by #80 but nothing read it; now threaded into the
  session and used to format sale times in the shop's zone (fallback UTC on a bad zone id).

---

## 7. Test / build status

- Compiles: `:sharedLogic` (JVM), `:desktopApp`, `:androidApp` (debug).
- Green: `:sharedLogic:jvmTest`, `:desktopApp:test` (incl. the PDFBox runtime smoke test).
- Coverage: paging, each filter, IMEI indirection, the string-money balance trap, local search
  (with a "no Firebase call" assertion), empty/permission states, use-case gating, PDF render.

---

## 8. Open items / caveats

- **Visual verification not done.** The two-pane split, zoom, light/dark, and a *real* rendered S3
  invoice need a live login + an issued sale. The pipeline is proven by tests, but a human eyeball
  is the last check before merge (ticket AC7: "Light + dark verified").
- **Local search only sees loaded pages** (by design of the hybrid). Full-history exact lookups are
  the Filters dialog's job (IMEI via serial indirection, invoice #, customer, date, balance).
- **Indexes may still be `CREATING`** — confirm `READY` before live testing.
- **A few now-unused i18n keys** remain (`sales_history_col_items`, `sales_history_col_imei`,
  `sales_history_items_more`) after the column removal — harmless, left for possible reuse.
- **`BackendSalesRepository` has no `close()`** on Desktop (pre-existing note from #62) — the
  history VM only closes the entity repo.

---

## 9. Suggested next steps

1. Confirm the composite indexes are `READY`.
2. Launch the desktop app, sign in, and eyeball: list + search, the filter dialog, the two-pane
   detail with a real issued invoice, zoom/pan, and light + dark.
3. Run `/handoff 83` to generate the formal handoff from the real diff and open the PR.
4. (Later, T2) mirror the screen on Android/iOS with native PDF viewers.
