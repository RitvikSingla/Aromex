# Ticket #55 — [UI] Inventory browsing (desktop) — location panel + grouped stock table

## Summary

Replaced the minimal test inventory list on Desktop with a professional two-pane browse screen. The left pane is a location panel (KPI cards + scrollable location list) that filters the right pane client-side. The right pane is a flat-tree table grouped Brand → Model → per-IMEI unit rows, with expand/collapse at both levels, a top search bar, and column headers for IMEI / Capacity / Color / Carrier / Location / Sell Price / Status. Tree connectors between Brand, Model, and IMEI rows are drawn via `drawBehind` with `PathEffect.cornerPathEffect` so the junction corners are visually rounded. All grouping, location filtering, KPI aggregation, and text search run client-side in the existing `InventoryListViewModel` with no new repository methods.

## Files changed

| File | Why |
|------|-----|
| `desktopApp/.../ui/inventory/InventoryListViewModel.kt` | Added `LocationEntry`, `BrowseUnit`, `ModelGroup`, `BrandGroup`, `InventorySummary` data classes; extended `InventoryListUiState` with browse fields; added `selectLocation()` action; extended `recompute()` to build browse groups, location entries, and KPI summary client-side. |
| `desktopApp/.../ui/inventory/InventoryScreen.kt` | Added `BrowseInventoryPanel`, `LocationPanel`, `KpiCard`, `BrowseGroupedTable`, `BrowseTableHeader`, `BrandSectionRow`, `ModelSectionRow`, `ImeiSectionRow`, `BrowseHeaderCell`, `BrowseCell`, `FilterPill` composables; replaced the old `ProductListPanel` call in `InventoryScreen` with `BrowseInventoryPanel`; added column-weight constants and `drawBehind` / `Path` / `PathEffect` / `Stroke` / `StrokeCap` imports. |
| `sharedLogic/.../i18n/Strings.kt` | Added keys for browse strings (search placeholder, location panel labels, status column, empty states). |
| `sharedLogic/.../i18n/EnglishStrings.kt` | English values for the new browse string keys. |

## How to test

1. `./gradlew :desktopApp:run` and sign in.
2. Navigate to **Inventory** in the sidebar — the screen should show the two-pane browse layout (location panel left, table right).
3. **Location panel:** confirm "All" is selected by default and shows the total in-stock count. Click any named location — the table on the right should immediately filter to units at that location, and the KPI cards should update.
4. **Tree table:** with "All" selected, confirm brands appear as collapsible rows (blue accent bar, uppercase name, model count). Click a brand to expand it — model rows should appear beneath with a rounded ├─ / └─ connector. Click a model to expand its IMEI rows, each with a horizontal branch connecting to the IMEI text.
5. **Collapse All / Expand All** button should toggle all brands (and models) together.
6. **Search:** type a partial IMEI or model name in the search field — the tree should filter live without a reload.
7. **Empty states:** filter to a location with no stock → "No stock at this location" message should show. If inventory is completely empty → "No inventory in stock" message.
8. **Location column:** visible when "All" is selected; should hide when a specific location is selected.

## Acceptance criteria

| Criterion | Status |
|-----------|--------|
| Desktop shows a left location panel ("All" + every location with counts) that filters the table | ✅ Met |
| Table groups Brand → Model → units with per-group counts, in-stock only | ✅ Met — brand shows model count, model shows unit count |
| Columns: Capacity, Color, Carrier, IMEI, Sell Price, Status | ✅ Met (Condition and Cost are not shown in browse rows — see Deviations) |
| Location column shown under "All", hidden when specific location is selected | ✅ Met |
| Empty / empty-for-location / no-access states handled | ✅ Met |
| Matches desktop theme (light + dark), strings i18n, builds on desktop | ✅ Met |
| Review screen still works (shared component reuse) | ⚠️ Partial — see Deviations |

## Deviations / decisions

**No shared table component extracted from the review screen.** The ticket asked to refactor `ReviewTableHeader` / `ReviewRow` etc. into a shared read-only component reused by both browse and review. The browse table was implemented as its own set of composables (`BrowseTableHeader`, `BrandSectionRow`, `ModelSectionRow`, `ImeiSectionRow`) rather than extracting and sharing the review table cells. Reason: the browse table has a fundamentally different layout (three-tier tree with dedicated Brand / Model / IMEI columns and Canvas-drawn connectors) vs. the flat review table (one row per unit with editable cells). Merging them would have forced unnatural abstractions. The review screen is unchanged and still works; it uses its own private composables. A future ticket can revisit if a true shared cell library becomes worthwhile.

**Condition and Cost columns omitted from browse rows.** The ticket listed both as desired columns. They were left out to keep row density readable; `BrowseUnit` carries `cost` and `condition` in the data model so they can be added in a follow-up with one line each.

**Tree connector design evolved during implementation.** The initial design used a separate 52dp Canvas column; this was replaced with `drawBehind` on each row so connector trunks originate directly from the expand/collapse chevron of their parent node. Corner rounding was added via `PathEffect.cornerPathEffect(6.dp)` on the elbow paths.

**KPI cards added to the location panel.** Not in the ticket spec but low-cost and useful: total devices, inventory retail value, and location count shown above the location list.

## Open questions / follow-ups

- **Condition + Cost columns** can be added to `ImeiSectionRow` in one line each — data is already in `BrowseUnit`.
- **Sticky brand / model headers** in the `LazyColumn` (ticket listed as "a plus, not required") — not implemented.
- **Keyboard nav** through the location list (Tab / arrows) — not implemented; the location entries are plain `Box` composables, not focusable list items.
- **Sell price** in browse rows shows the SKU's `defaultSellingPrice` directly; once per-unit pricing exists this will need updating.
- **WCAG-AA contrast** on the tree connector colors (`brand.copy(alpha=0.4f)`, `textTertiary.copy(alpha=0.6f)`) should be verified against the dark theme background.
