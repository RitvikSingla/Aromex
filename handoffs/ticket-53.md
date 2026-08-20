# Ticket: #53 — [UI] SICKW paste — bulk iPhone intake via parsed IMEI text (Android · iOS · Desktop)

## Summary

Adds a **SICKW paste flow** to the Add Inventory screen on all three platforms (Android, iOS, Desktop). A shared Kotlin `parseSickw()` function splits raw SICKW lookup text into parsed phones with brand/model/capacity/color/carrier/IMEI, maps them onto managed-vocabulary `AttributeRef`s via `ResolveParsedPhonesUseCase` (find-or-create, case-insensitive), and drops them onto the existing review table pre-filled. The Desktop review table was redesigned from a grouped-SKU static list into a **flat inline-editable spreadsheet** where every attribute cell is a compact dropdown the user can adjust without a separate dialog. An **Apply-to-all bar** lets cost, selling price, condition, and location be set once and broadcast to all rows. Saves are parallelised with `async/awaitAll` per SKU group. `addAttribute` on all three platforms was hardened with a **deterministic document ID + Firestore transaction** to prevent duplicate-create races when many attributes resolve in parallel.

---

## Files changed

### sharedLogic

| File | Why |
|---|---|
| `util/SickwParser.kt` | New: pure `parseSickw(text)` + `normaliseModel()` + field-classify helpers |
| `model/SickwParseResult.kt` | New: `ParsedPhone`, `UnreadableBlock`, `SickwParseResult` data classes |
| `model/ResolvedPhone.kt` | New: `ResolvedPhone`, `ResolveResult` — parser output after vocab lookup |
| `usecase/ResolveParsedPhonesUseCase.kt` | New + rewritten: parallel resolution in 3 steps (BRAND/CAP/COLOR/CARRIER in parallel → MODEL in parallel → local map) using `coroutineScope + async/awaitAll`; no shared mutable state inside workers |
| `util/InventoryLimits.kt` | New: `BATCH_CAP = 50` single-save limit |
| `i18n/Strings.kt` + `i18n/EnglishStrings.kt` | SICKW paste + review-table i18n keys |
| `commonTest/.../SickwParserTest.kt` | New: unit tests pinned to the real SICKW sample fixture |

### desktopApp

| File | Why |
|---|---|
| `ui/inventory/InventoryScreen.kt` | Paste button + `PasteFromSickwPanel`; flat inline-editable `ReviewRow` replaces old grouped table; `ApplyToAllBar` (cost / sell price / condition / location); `ConditionCell` dropdown; `MoneyCell` BasicTextField; `ParseSummaryBanner`; `UnreadableList`; vertically scrollable outer `Column`; `NewTag` chip removed |
| `ui/inventory/AddStockViewModel.kt` | `openPaste()`, `submitPaste()`, `dismissParseSummary()`, `dismissUnreadable()`, `applyToAllRows()`; `save()` parallelised with `async/awaitAll` per SKU group |
| `ui/components/FilterableDropdownField.kt` | `outlined` param; compact cell padding 6dp; `onSizeChanged` moved to outer Box; `dismissOnClickOutside = true` |
| `data/BackendAttributeRepository.kt` | `addAttribute`: deterministic doc ID `"${type.wire}_${parentId.orEmpty()}_$nameKey"` + `runTransaction` (check-then-set); backward-compat query retained |

### androidApp

| File | Why |
|---|---|
| `ui/inventory/InventoryScreen.kt` | Paste button + paste screen + inline review-table edits (Android-native Compose) |
| `ui/inventory/AddStockViewModel.kt` | Same ViewModel changes as Desktop (paste, resolve, parallel save) |
| `data/BackendAttributeRepository.kt` | Same deterministic ID + `runTransaction` hardening as Desktop |

### iosApp

| File | Why |
|---|---|
| `ui/InventoryView.swift` | Paste button + `PasteFromSickwView` + inline review-table edits (SwiftUI) |
| `viewmodel/AddStockViewModel.swift` | Same logic: paste, resolve, parallel save with `async let` / `withTaskGroup` |
| `repository/BackendAttributeRepository.swift` | Same deterministic ID + `runTransaction` hardening |

---

## How to test

### Parser (any platform)
1. Run `./gradlew :sharedLogic:commonTest` — all `SickwParserTest` cases must pass.

### Desktop paste flow
1. Launch the Desktop app and navigate to **Inventory**.
2. Click **"Paste from SICKW"** in the top bar.
3. Paste a real SICKW block (or the test fixture from the ticket) and press **"Parse & add"**.
4. Confirm the summary banner shows the correct parsed / unreadable count.
5. In the review table, verify every cell is editable inline (brand/model/capacity/color/carrier dropdowns, cost/sell price text fields, condition toggle, location dropdown).
6. Set cost in the **Apply-to-all bar** and press "Apply to all rows" — all rows should update.
7. Scroll down — rows should not be hidden behind the confirm bar.
8. Delete a row; verify count in header updates.
9. Click outside an open dropdown — it should dismiss.
10. Press **Confirm** — verify saves complete and the screen closes. With 20 phones, save should be noticeably faster than before (parallel).

### Attribute race safety
- Paste 20 phones with a brand not yet in the vocabulary. After save, check Firestore `attributes` collection — exactly **one** document for that brand should exist (no duplicates).

### Android / iOS paste flow
- Same steps 2–10 on each mobile platform.

---

## Acceptance criteria

| Criterion | Status |
|---|---|
| Shared `parseSickw()` with unit tests pinned to sample | ✅ Met |
| "Paste from SICKW" entry point on all 3 platforms | ✅ Met |
| Parsed phones land on review table pre-filled | ✅ Met |
| Parse summary banner (parsed count / couldn't read) | ✅ Met |
| Unreadable blocks surfaced, not silently dropped | ✅ Met |
| "Paste more" appends to existing batch | ✅ Met (via `openPaste()` from sub-header) |
| `ResolveParsedPhonesUseCase` find-or-create, case-insensitive | ✅ Met |
| Batch-size cap (`BATCH_CAP = 50`) enforced with dialog | ✅ Met |
| Inline review-table editing (Desktop) | ✅ Met |
| Apply-to-all bar (cost, condition, location, selling price) | ✅ Met |
| Parallel saves (`async/awaitAll` per SKU group) | ✅ Met |
| Attribute writes idempotent under concurrency (transaction) | ✅ Met — all 3 platforms |

---

## Deviations / decisions

- **`NewTag` chip removed**: the "new" chip that appeared below newly-created attribute cells was removed entirely. It was noisy (showed the full attribute name) and adds no actionable value in the review table context.
- **Deterministic attribute doc IDs**: `addAttribute` on all 3 platforms now uses `"${type.wire}_${parentId.orEmpty()}_$nameKey"` as the Firestore document ID instead of a server-generated UUID. This enables `runTransaction` (which requires a known doc ref) and eliminates the race where two concurrent `addAttribute` calls for the same attribute create duplicate documents. Legacy UUID-id documents are found by the backward-compat query and returned as-is; only new creates use the deterministic ID.
- **`ResolveParsedPhonesUseCase` fully parallel**: the original sequential phone-by-phone resolution (up to 100 sequential Firestore calls for 20 phones × 5 attributes) was replaced with a 3-step parallel approach. Step 1 resolves BRAND/CAPACITY/COLOR/CARRIER in one parallel batch; step 2 resolves MODEL (after brand IDs are known); step 3 is a pure local map lookup. No shared mutable state is written inside concurrent workers — all merges happen after `awaitAll`.
- **Desktop review table**: replaced the old grouped-SKU `LazyColumn` (static rows, edit-via-dialog) with a flat `Column` of inline-editable rows. `LazyColumn` was incompatible with the `verticalScroll` wrapper required for the confirm-bar padding. The new design matches the spreadsheet intent of the ticket.

---

## Open questions / follow-ups

- **SICKW API integration** (noted in ticket as a later enhancement): the parser pipeline is in place; a direct API call would feed `parseSickw()` with the same text the web lookup returns.
- **Non-iPhone blocks**: the parser currently marks any block that isn't an iPhone (no recognisable model) as "unreadable." Android phones and other brands will need parser extensions when those are added.
- **Duplicate IMEI within a paste**: detected and shown as a "Duplicate in this paste" status chip (inherited from the pre-existing review-table logic). Cross-paste duplicates are caught by the `AddStockUseCase` duplicate-IMEI check on save.
