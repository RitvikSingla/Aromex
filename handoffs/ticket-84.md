# Handoff — Ticket #84

**Ticket:** #84 — [M7] Sales History T2 — Android + iOS (bare-but-stable)

## Summary
Brings Sales History to the phones on the **same shared query layer** #83 built for Desktop — no
new repository methods, no new use cases. Both apps get a paged, newest-first list (append on
scroll, never blanks), a **single smart search box** (instant local filter as you type; on submit
it runs a server-side query whose interpretation — IMEI / invoice # / customer — is detected from
what was typed and shown as a hint), and a scrollable sale detail with the reused #77 invoice row.
Per the user's request, the detail's invoice actions act on the **real PDF**, not the link: **Share**
downloads the PDF and shares the file (Android `ACTION_SEND` via `FileProvider`; iOS share sheet),
and **Download** saves the PDF (Android → public **Downloads** via `DownloadManager`; iOS → **Save to
Files**), alongside **Open** and **Retry**. Implementing the #83 `SalesRepository` contract on iOS
(missing since #83) also fixed the iOS build, and both platforms now read the shop `timezone` (#80)
so dates render in the shop zone.

## Files changed

### Shared logic (i18n only — no new models/repos/use cases)
- `sharedLogic/.../i18n/Strings.kt`, `EnglishStrings.kt` — add the three phone search-hint keys
  (`sales_history_searched_by_imei` / `_invoice` / `_customer`).

### Android
- `ui/sales/history/SalesHistoryViewModel.kt` — new `AndroidViewModel` (StateFlow): paging via
  `QuerySalesUseCase`, `GetSaleUseCase`; the `detectSearchKind` classifier + `SalesSearchKind`;
  instant local filter; customer-name→entity-id resolution; detail open/close, live invoice, retry,
  seller-name resolution. Consumes the #83 layer unchanged.
- `ui/sales/history/SalesHistoryScreen.kt` — new stock Material 3 screen: gated list (row = date,
  invoice #, customer, first item, total, balance chip), single search field with the "Searched by …"
  hint, and the detail screen (summary + invoice row with Open/Share/Download/Retry). Keyboard
  dismissible; dates formatted with the shop timezone via `SimpleDateFormat` (minSdk 24, no desugaring).
- `ui/sales/history/PdfActions.kt` — new: download the invoice PDF to cache + share the **file**
  (`FileProvider`); `DownloadManager` save to public Downloads.
- `src/main/res/xml/file_paths.xml` + `AndroidManifest.xml` — new `FileProvider` (`${applicationId}.fileprovider`,
  cache `invoices/`) and a `WRITE_EXTERNAL_STORAGE` maxSdk=28 fallback for pre-Q Downloads writes.
- `navigation/Route.kt`, `navigation/AromexApp.kt`, `ui/home/HomeScreen.kt` — add `Route.SalesHistory`,
  wire `onOpenSalesHistory`, add a Home entry.
- `data/FirestoreUserRepository.kt` — read `companySettings/profile.timezone` into `CompanyProfile`
  (was defaulting to UTC), so the session carries the real shop zone for date formatting.
- `build.gradle.kts` + `gradle/libs.versions.toml` — add unit-test deps (junit, kotlin-test-junit,
  coroutines-test, Robolectric) and `testOptions` so the AndroidViewModel is unit-testable.
- `src/test/.../ui/sales/history/SalesHistoryViewModelTest.kt` — new: search-type detection matrix,
  paging (append + cursor), permission gating, per-kind server query building, and the local filter.

### iOS
- `repository/BackendSalesRepository.swift` — implement the #83 contract (`__querySales`, `__getSale`,
  `__findSaleIdByImei`) with the native Firestore SDK, mirroring Android (newest-first, cursor
  `start(after:)`, IMEI via `serials`). This was **missing since #83** — it fixed the iOS build.
- `viewmodel/SalesHistoryViewModel.swift` — new `@MainActor ObservableObject` twin of the Android VM
  (detection, local filter, paging, detail, live invoice, retry, seller name).
- `ui/SalesHistoryView.swift` — new stock SwiftUI screen: `.searchable` box + hint, `List` with
  append-on-apper paging, and the detail `Form` with the invoice actions.
- `ui/PdfExport.swift` — new: `URLSession` PDF download, `ActivityView` (share the file),
  `PdfFileDocument` (Save to Files), `ShareFile`.
- `ui/HomeView.swift` — add the Sales History entry (`fullScreenCover`).
- `repository/FirestoreUserRepository.swift` — pass `timezone` into `CompanyProfile` (SKIE requires
  every arg; this was a pre-existing iOS build break from #80) and carry the real shop zone.

## How to test
Android:
1. `./gradlew :androidApp:testDebugUnitTest` — the VM tests pass.
2. `./gradlew :androidApp:assembleDebug` — builds.
3. Run the app → Home → **Sales History**. Scroll (pages append, no blank/jump). Type a model/brand
   (instant local filter). Submit an IMEI (15 digits), an `INV-…` number, and a customer name — each
   shows the matching "Searched by …" hint and full-history result. Open a sale → **Open / Share /
   Download / Retry**; Share sends the PDF file, Download lands in the phone's Downloads.

iOS (from `iosApp/`):
1. `LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 pod install`
2. `xcodebuild -workspace iosApp.xcworkspace -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16 Pro' -configuration Debug build CODE_SIGNING_ALLOWED=NO`
3. Run → Home → **Sales history**. Same list/search/detail checks. On an issued invoice tap **Share**
   (the share sheet stays open and returns to the detail on dismiss) and **Download** (Save to Files).

Verified here: `:androidApp:assembleDebug` → `BUILD SUCCESSFUL`; `:androidApp:testDebugUnitTest` →
`BUILD SUCCESSFUL`; iOS `xcodebuild` → `** BUILD SUCCEEDED **` (iPhone 16 Pro simulator, after `pod install`).

## Acceptance criteria
1. Both apps list past sales newest-first and page on scroll — **met** (VM paging + append; list never blanks).
2. One search box resolves IMEIs, invoice numbers and customer names, and shows which it used — **met**
   (`detectSearchKind` + the "Searched by …" hint on submit).
3. Opening a sale shows its full summary; Open/Share reach the PDF; Retry works on a FAILED invoice — **met**
   (detail summary + invoice row; Share/Download act on the real PDF; Retry reuses the #77 flow).
4. `sales: view` gates access on both platforms — **met** (UI-layer gate + `QuerySalesUseCase`/`GetSaleUseCase` gate).
5. Light + dark checked on both — **partially met**: stock Material 3 / SwiftUI + theme tokens, no
   hardcoded colours (the balance chip uses `errorContainer`/`.red` roles); needs a visual pass by the reviewer.
6. Both apps actually build — **met** (outputs above).
7. No new methods on `SalesRepository` beyond what #83 defined — **met** (only the existing contract is implemented/consumed).

## Deviations / decisions
- **Share/Download act on the actual PDF, not the link** (explicit user request, extending the ticket's
  "Share the link"): Android shares a `FileProvider` file and downloads via `DownloadManager` to public
  Downloads; iOS shares the downloaded file and uses **Save to Files** (`.fileExporter`) — iOS has no
  shared Downloads folder (chosen over a plain share-to-save).
- **Single box = local + server**: as-you-type filters loaded rows instantly (the user's "local item
  search like Desktop"); submit runs the server smart-search. This satisfies AC #2 while keeping the
  common browse-and-filter interaction local.
- **iOS repo methods + timezone reads** were added although not strictly "new feature" work — the iOS
  `SalesRepository` methods were missing since #83 and `CompanyProfile.timezone` (#80) was unset on both
  platforms; both were required for the apps to build and for dates to render in the shop zone (AC #6, UI DoD).
- **Robolectric** was added to `androidApp` (no prior Android test module) so the `AndroidViewModel` can
  be unit-tested; tests pin `@Config(sdk=[34])`.
- **iOS list-row live invoice patch omitted**: the detail invoice updates live; the list row's cached
  number refreshes on the next load (avoided depending on SKIE `doCopy`, unverified in this project).
- **Share-sheet navigation fix**: detail is pushed via a plain `@State` bool (not a derived binding with
  a side-effecting setter) and the PDF presentations are hosted at the detail root — otherwise presenting
  the share sheet popped the whole stack back to the dashboard.

## Open questions / follow-ups
- Light/dark visual pass on both platforms (AC #5) — confirm the balance chip and hints read well in dark.
- iOS list-row invoice number could be patched live later if desired (see deviation).
- `androidApp/release/` is pre-existing untracked build output and is intentionally not committed.
