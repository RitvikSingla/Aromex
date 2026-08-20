> Brief: #82 · Milestone M7 · **Android + iOS** · depends on #83

## 📖 Story / Why

#83 gives Desktop a way back into past sales. The phones need the same reach — a manager away from the
counter should be able to look up a sale and send the customer their invoice — but they do **not** need
the polished table. This follows the same split the Sales screen itself used (#63 polished Desktop,
#64 bare-but-stable phones): stock components, no bespoke layout work, correct behaviour.

## 🎯 What you're building

A Sales list on Android and iOS backed by the **same shared query layer** #83 builds. No new repository
contract, no new use cases — if you find yourself adding a method to `SalesRepository`, something has
gone wrong in T1 and it should be fixed there instead.

**List:** newest first, paged on scroll. Each row: date, invoice number, customer, total, and a chip
when the sale still carries a balance.

**Search:** a single field that accepts a customer name, an IMEI, or an invoice number. Phones get one
box, not the five separate controls Desktop has — pick the query by what was typed (all digits and
14–17 long → IMEI; starts with the invoice prefix → invoice number; otherwise → customer name), and say
which interpretation was used so a surprising result is explainable.

**Detail:** a scrollable summary — lines, totals, tax, payments, balance, note, buyer — plus the
invoice row with **Open** and **Share** (`ACTION_SEND` / `ShareLink`, as in #77) and **Retry** when
issuance failed.

## ✅ Scope

- `androidApp`: `ui/sales/history/` screen + ViewModel (StateFlow), nav entry.
- `iosApp`: `SalesHistoryView.swift` + `SalesHistoryViewModel.swift` (`@MainActor ObservableObject`),
  nav entry.
- Both bind to `QuerySalesUseCase` / `GetSaleUseCase` from #83. **No new shared code.**
- Android VM tests for the search-type detection and paging.

## 🖼️ UI standards (Definition of Done)

- Stock Material 3 / stock SwiftUI. `LazyColumn` / `List`. No custom table.
- Theme tokens, light + dark, no hardcoded colours.
- i18n for every string — including the "searched by IMEI / by invoice number / by customer" hint.
- `MoneyFormat` for money; the shop's `timezone` for dates.
- Paging appends; it must never blank or scroll-jump the list.
- The keyboard must be dismissible on the search field (the #71 lesson).

## 🎯 Acceptance Criteria

1. Both apps list past sales newest-first and page on scroll.
2. One search box resolves IMEIs, invoice numbers and customer names, and shows which it used.
3. Opening a sale shows its full summary; Open/Share reach the PDF; Retry works on a FAILED invoice.
4. `sales: view` gates access on both platforms.
5. Light + dark checked on both.
6. **Both apps actually build**: `:androidApp:assembleDebug` and an `xcodebuild` run for the iOS
   simulator after `pod install`. Paste the result. SourceKit checking alone is not sufficient — #77
   shipped a dead `catch` block that only the real compiler caught.
7. No new methods on `SalesRepository` beyond what #83 defined.

## 🚫 Out of scope

- Desktop (#83) · voiding (T3, Desktop-first) · receivables (T4)
- Date-range and balance filters — Desktop-only in v1; phones get search and the balance chip.

## 🔗 Dependencies

- **#83 must merge first** — this consumes its shared query layer unchanged.
- #77 for the invoice row's phone behaviour (Open / Share / Retry).

## 📚 References

- `androidApp/.../ui/sales/SalesScreen.kt` + `iosApp/.../ui/SalesView.swift` — the #77 invoice UI
- `handoffs/ticket-77.md` — the verified `pod install` + `xcodebuild` invocation, including the
  `LANG=en_US.UTF-8` requirement

## 🤖 Kickoff prompt

> Read brief #82, ticket #83 and this ticket. Build the Android and iOS Sales History screens on the
> shared query layer #83 already provides — bare-but-stable, stock components, no new shared code. One
> smart search box per phone instead of Desktop's five controls. Build both apps for real and paste the
> output.
